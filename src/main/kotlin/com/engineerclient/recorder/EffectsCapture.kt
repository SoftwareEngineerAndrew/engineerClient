package com.engineerclient.recorder

import com.engineerclient.EngineerClient
import com.engineerclient.mixin.RecParticleAccessor
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.particle.Particle
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.sounds.SoundEngine
import net.minecraft.client.sounds.SoundEventListener
import net.minecraft.client.sounds.WeighedSoundEvents
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Entity
import java.util.IdentityHashMap

/**
 * What the client actually heard and drew, not just what the server asked for: every sound the
 * sound engine was asked to play (the server's, the client's own - footsteps, clicks, ambience -
 * and every mod's) with the file it resolved to and whether it started, every stop, and every
 * particle requested from the level and spawned into the particle engine.
 *
 * The packet lines only show the server's side; a lot of what a dungeon mod reacts to (Hypixel's
 * client-predicted sounds, the particles the client makes from block breaks, entity events and
 * tracking emitters, what the particle option filtered away) never crosses the wire.
 *
 *  - `snd`: one line per [SoundEngine.play] call, written at its return so the outcome (`res`:
 *    STARTED, STARTED_SILENTLY, NOT_STARTED) is known, including the early NOT_STARTED returns that
 *    never reach the engine's listeners. The listener ([SoundEventListener]) adds the resolved event's
 *    range and subtitle when it fired.
 *  - `sndstop`: each stop the engine performs (one instance, every match of an id/source, or all).
 *  - `ptc`: one line per tick with that tick's requested (`req`) and spawned (`spawned`) particles,
 *    the tracking emitters created (`emit`) and the options of each distinct particle options
 *    instance seen (`opts`, which the rows point into).
 *
 * Everything runs on the game thread (sounds and particles are created on it). Sounds are frozen
 * into strings there; particles only as numbers (thousands a second in a boss fight), turned into
 * text on the writer thread. The particle buffer is still guarded by a lock, so a mod spawning
 * particles from another thread cannot corrupt it.
 */
object EffectsCapture {

    /** The module's "Played Sounds" and "Spawned Particles" settings, pushed in by [DungeonRecorder]. */
    @Volatile var sounds = true
    @Volatile var particles = true

    private var listening = false

    fun install() {
        // The sound manager exists once the client has started; registering earlier could find it null.
        ClientLifecycleEvents.CLIENT_STARTED.register { _ -> EngineerClient.safely("recorder sound listener") { listen() } }
        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            EngineerClient.safely("recorder effects tick") {
                if (!listening) listen()
                if (Rec.active) flushParticles() else synchronized(lock) { buf.clear() }
            }
        }
    }

    private fun listen() {
        if (listening) return
        // Annotated non-null, but still unset while the client is being built.
        val sm: net.minecraft.client.sounds.SoundManager? = EngineerClient.mc.soundManager
        if (sm == null) return
        sm.addListener(SoundEventListener { inst, weighed, range -> if (Rec.active && sounds) heard.set(Heard(inst, weighed, range)) })
        listening = true
    }

    // ------------------------------------------------------------------ sounds

    /** What the listener saw during the current play() call, picked up at its return. */
    private class Heard(val inst: SoundInstance, val weighed: WeighedSoundEvents?, val range: Float)
    private val heard = ThreadLocal<Heard?>()

    /** SoundPlayTapMixin, at every return of SoundEngine.play. */
    @JvmStatic
    fun played(inst: SoundInstance?, result: SoundEngine.PlayResult?) {
        if (!Rec.active || !sounds || inst == null) return
        try {
            val h = heard.get()?.takeIf { it.inst === inst }
            heard.remove()
            val sb = StringBuilder(320)
            sb.append("\"id\":"); idOrNull(sb) { inst.identifier }
            RichJson.member(sb, "file") { val s = inst.sound ?: return@member false; PacketJson.str(sb, s.location.toString()); true }
            RichJson.member(sb, "path") { val s = inst.sound ?: return@member false; PacketJson.str(sb, s.path.toString()); true }
            RichJson.member(sb, "src") { PacketJson.str(sb, inst.source.name); true }
            sb.append(",\"pos\":["); PacketJson.num(sb, inst.x); sb.append(','); PacketJson.num(sb, inst.y); sb.append(','); PacketJson.num(sb, inst.z); sb.append(']')
            // Volume and pitch read the resolved Sound; plays that return before resolve() (NOT_STARTED:
            // silent entities, a sound reload, no audio device) have none, so these are left out there.
            RichJson.member(sb, "vol") { if (inst.sound == null) return@member false; PacketJson.num(sb, inst.volume); true }
            RichJson.member(sb, "pitch") { if (inst.sound == null) return@member false; PacketJson.num(sb, inst.pitch); true }
            RichJson.member(sb, "att") { PacketJson.str(sb, inst.attenuation.name); true }
            sb.append(",\"rel\":").append(inst.isRelative)
            sb.append(",\"loop\":").append(inst.isLooping)
            sb.append(",\"delay\":").append(inst.delay)
            if (h != null) {
                sb.append(",\"range\":"); PacketJson.num(sb, h.range)
                RichJson.member(sb, "sub") { val c = h.weighed?.subtitle ?: return@member false; RichJson.component(sb, c); true }
            }
            sb.append(",\"cls\":"); PacketJson.str(sb, PacketJson.simpleName(inst.javaClass))
            sb.append(",\"res\":"); if (result == null) sb.append("null") else PacketJson.str(sb, result.name)
            Rec.emit("snd", sb.toString())
        } catch (t: Throwable) { fail("snd", t) }
    }

    /** Whether sound lines are wanted now (lets the mixin skip asking the engine anything when not). */
    @JvmStatic
    fun recordingSounds(): Boolean = Rec.active && sounds

    /** SoundPlayTapMixin, at the head of SoundEngine.stop(SoundInstance); [active] whether it was playing. */
    @JvmStatic
    fun stopped(inst: SoundInstance?, active: Boolean) {
        if (!Rec.active || !sounds) return
        try {
            val sb = StringBuilder(128)
            sb.append("\"what\":\"inst\",\"id\":")
            if (inst == null) sb.append("null") else idOrNull(sb) { inst.identifier }
            if (inst != null) {
                RichJson.member(sb, "src") { PacketJson.str(sb, inst.source.name); true }
                sb.append(",\"cls\":"); PacketJson.str(sb, PacketJson.simpleName(inst.javaClass))
            }
            sb.append(",\"active\":").append(active)
            Rec.emit("sndstop", sb.toString())
        } catch (t: Throwable) { fail("sndstop", t) }
    }

    /** SoundPlayTapMixin, at the head of SoundEngine.stop(Identifier, SoundSource): every sound matching either (null = any). */
    @JvmStatic
    fun stoppedMatching(id: Identifier?, src: SoundSource?) {
        if (!Rec.active || !sounds) return
        try {
            val sb = StringBuilder(96)
            sb.append("\"what\":\"match\",\"id\":"); if (id == null) sb.append("null") else PacketJson.str(sb, id.toString())
            sb.append(",\"src\":"); if (src == null) sb.append("null") else PacketJson.str(sb, src.name)
            Rec.emit("sndstop", sb.toString())
        } catch (t: Throwable) { fail("sndstop", t) }
    }

    /** SoundPlayTapMixin, at the head of SoundEngine.stopAll. */
    @JvmStatic
    fun stoppedAll() {
        if (!Rec.active || !sounds) return
        try { Rec.emit("sndstop", "\"what\":\"all\"") } catch (t: Throwable) { fail("sndstop", t) }
    }

    private inline fun idOrNull(sb: StringBuilder, id: () -> Identifier?) {
        val v = runCatching(id).getOrNull()
        if (v == null) sb.append("null") else PacketJson.str(sb, v.toString())
    }

    // ------------------------------------------------------------------ particles

    private val lock = Any()
    private val buf = ParticleBuffer { optsJson(it as ParticleOptions) }

    /** ParticleTapMixin, at the head of ClientLevel.doAddParticle (every particle the level is asked for). */
    @JvmStatic
    fun requested(opts: ParticleOptions?, force: Boolean, always: Boolean, x: Double, y: Double, z: Double, dx: Double, dy: Double, dz: Double) {
        if (!Rec.active || !particles || opts == null) return
        try {
            val id = particleId(opts)
            synchronized(lock) { buf.request(id, opts, x, y, z, dx, dy, dz, force, always) }
        } catch (t: Throwable) { fail("ptc", t) }
    }

    /** ParticleTapMixin, at the return of ClientLevel.doAddParticle: how many particles the request made. */
    @JvmStatic
    fun requestDone() {
        if (!Rec.active || !particles) return
        try { synchronized(lock) { buf.requestDone() } } catch (t: Throwable) { fail("ptc", t) }
    }

    /** ParticleEngineTapMixin, at the head of ParticleEngine.add: a particle that will actually exist. */
    @JvmStatic
    fun spawned(p: Particle?) {
        if (!Rec.active || !particles || p == null) return
        try {
            val a = p as RecParticleAccessor
            synchronized(lock) {
                buf.spawned(particleClass(p.javaClass), a.ec_getX(), a.ec_getY(), a.ec_getZ(), a.ec_getXd(), a.ec_getYd(), a.ec_getZd(), a.ec_getLifetime())
            }
        } catch (t: Throwable) { fail("ptc", t) }
    }

    /** ParticleEngineTapMixin, at the head of both createTrackingEmitter overloads ([lifetime] -1 for the default). */
    @JvmStatic
    fun emitter(entity: Entity?, opts: ParticleOptions?, lifetime: Int) {
        if (!Rec.active || !particles || opts == null) return
        try {
            val id = entity?.id ?: -1
            val type = entity?.let { runCatching { BuiltInRegistries.ENTITY_TYPE.getKey(it.type).toString() }.getOrNull() }
            synchronized(lock) { buf.emitter(id, type, opts, lifetime) }
        } catch (t: Throwable) { fail("ptc", t) }
    }

    /** The tick's particles: the numbers are taken here, the text is built on the writer thread. */
    private fun flushParticles() {
        val snap = synchronized(lock) { buf.take() } ?: return
        Rec.emitLazy("ptc", snap.est, "ptc") { snap.json() }
    }

    /** Particle type -> id, so a burst of thousands does one registry lookup. */
    private val particleIds = java.util.concurrent.ConcurrentHashMap<Any, String>()

    private fun particleId(o: ParticleOptions): String = particleIds.getOrPut(o.type) {
        runCatching { BuiltInRegistries.PARTICLE_TYPE.getKey(o.type)?.toString() }.getOrNull() ?: PacketJson.simpleName(o.javaClass)
    }

    private fun optsJson(o: ParticleOptions): String = StringBuilder(64).also { RichJson.particle(it, o) }.toString()

    private val classNames = HashMap<Class<*>, String>()

    /** Vanilla particle classes without their package (`FlameParticle`), anything else by its full name. */
    private fun particleClass(c: Class<*>): String = classNames.getOrPut(c) { ParticleBuffer.className(c.name) }

    private var failures = 0

    /** A capture that failed: an `error` line (the first few also in the log), never an exception into the game. */
    private fun fail(what: String, t: Throwable) {
        if (failures++ < 5) EngineerClient.logger.error("[ec] recorder $what capture failed", t)
        runCatching { Rec.emit("error", "\"p\":${RecorderFiles.q("effects:$what")},\"err\":${RecorderFiles.q(t.toString())}") }
    }
}

/**
 * One tick's particles (pure, so it can be tested without a game), kept as numbers while the tick
 * runs (a request or spawn only stores primitives: no formatting on the game thread) and written as
 * JSON members by [Snapshot.json] on the writer thread. Rows:
 *  - req: `[typeId, x, y, z, dx, dy, dz, force, always, o, made]`, `o` the index into `opts` and
 *    `made` how many particles the request added (0 when the particle option or distance filtered it
 *    out, null when the call never returned);
 *  - spawned: `[class, x, y, z, xd, yd, zd, lifetime]`;
 *  - emit: `[entityId, entityType, o, lifetime]` (lifetime -1: the emitter's default);
 *  - opts: `{type, opts}` per distinct options instance this tick (simple particles are singletons,
 *    so a tick of flames carries one entry), built by [optsJson] from the (immutable) options.
 */
class ParticleBuffer(private val optsJson: (Any) -> String) {
    private var reqD = DoubleArray(6 * 64)
    private var reqI = IntArray(REQ_INTS * 64)
    private var nReq = 0
    private var spD = DoubleArray(6 * 64)
    private var spI = IntArray(2 * 64)
    private var nSp = 0
    private val emitRows = ArrayList<Emit>()
    internal class Emit(val id: Int, val type: String?, val o: Int, val lifetime: Int)
    private val strings = ArrayList<String>()
    private val stringIndex = HashMap<String, Int>()
    private val opts = ArrayList<Any>()
    private val optsIndex = IdentityHashMap<Any, Int>()
    private var spawnedCount = 0
    private var openRequest = -1
    private var openRequestSpawnedAt = -1

    /** Index of [key] in this tick's opts, adding it the first time. */
    fun optsIndex(key: Any): Int = optsIndex.getOrPut(key) { opts += key; opts.size - 1 }

    private fun str(s: String): Int = stringIndex.getOrPut(s) { strings += s; strings.size - 1 }

    fun request(typeId: String, key: Any, x: Double, y: Double, z: Double, dx: Double, dy: Double, dz: Double, force: Boolean, always: Boolean) {
        closeOpen()
        if (nReq * REQ_INTS == reqI.size) { reqI = reqI.copyOf(reqI.size * 2); reqD = reqD.copyOf(reqD.size * 2) }
        val d = nReq * 6
        reqD[d] = x; reqD[d + 1] = y; reqD[d + 2] = z; reqD[d + 3] = dx; reqD[d + 4] = dy; reqD[d + 5] = dz
        val i = nReq * REQ_INTS
        reqI[i] = str(typeId); reqI[i + 1] = (if (force) 1 else 0) or (if (always) 2 else 0); reqI[i + 2] = optsIndex(key); reqI[i + 3] = -1
        openRequest = nReq
        openRequestSpawnedAt = spawnedCount
        nReq++
    }

    fun requestDone() {
        if (openRequest < 0) return
        reqI[openRequest * REQ_INTS + 3] = spawnedCount - openRequestSpawnedAt
        openRequest = -1; openRequestSpawnedAt = -1
    }

    /** A request whose return was never seen keeps made = null. */
    private fun closeOpen() { openRequest = -1; openRequestSpawnedAt = -1 }

    fun spawned(cls: String, x: Double, y: Double, z: Double, xd: Double, yd: Double, zd: Double, lifetime: Int) {
        spawnedCount++
        if (nSp * 2 == spI.size) { spI = spI.copyOf(spI.size * 2); spD = spD.copyOf(spD.size * 2) }
        val d = nSp * 6
        spD[d] = x; spD[d + 1] = y; spD[d + 2] = z; spD[d + 3] = xd; spD[d + 4] = yd; spD[d + 5] = zd
        spI[nSp * 2] = str(cls); spI[nSp * 2 + 1] = lifetime
        nSp++
    }

    fun emitter(entityId: Int, entityType: String?, key: Any, lifetime: Int) {
        emitRows += Emit(entityId, entityType, optsIndex(key), lifetime)
    }

    /** One tick's rows, private to whoever took them (the writer thread builds the JSON). */
    class Snapshot internal constructor(
        private val reqD: DoubleArray, private val reqI: IntArray, private val nReq: Int,
        private val spD: DoubleArray, private val spI: IntArray, private val nSp: Int,
        private val emits: List<Emit>, private val strings: List<String>, private val opts: List<Any>, private val optsJson: (Any) -> String,
    ) {
        val est: Int get() = 64 + nReq * 160 + nSp * 150 + emits.size * 48 + opts.size * 96

        /** `"opts":[..],"req":[..],...`, empty lists left out. */
        fun json(): String {
            val sb = StringBuilder(est)
            sb.append("\"opts\":[")
            opts.forEachIndexed { i, o -> if (i > 0) sb.append(','); sb.append(optsJson(o)) }
            sb.append(']')
            if (nReq > 0) {
                sb.append(",\"req\":[")
                for (r in 0 until nReq) {
                    if (r > 0) sb.append(',')
                    val i = r * REQ_INTS
                    sb.append('['); PacketJson.str(sb, strings[reqI[i]])
                    for (k in 0 until 6) { sb.append(','); PacketJson.num(sb, reqD[r * 6 + k]) }
                    val f = reqI[i + 1]
                    sb.append(',').append(f and 1 != 0).append(',').append(f and 2 != 0).append(',').append(reqI[i + 2]).append(',')
                    val made = reqI[i + 3]
                    if (made < 0) sb.append("null") else sb.append(made)
                    sb.append(']')
                }
                sb.append(']')
            }
            if (nSp > 0) {
                sb.append(",\"spawned\":[")
                for (r in 0 until nSp) {
                    if (r > 0) sb.append(',')
                    sb.append('['); PacketJson.str(sb, strings[spI[r * 2]])
                    for (k in 0 until 6) { sb.append(','); PacketJson.num(sb, spD[r * 6 + k]) }
                    sb.append(',').append(spI[r * 2 + 1]).append(']')
                }
                sb.append(']')
            }
            if (emits.isNotEmpty()) {
                sb.append(",\"emit\":[")
                emits.forEachIndexed { i, e ->
                    if (i > 0) sb.append(',')
                    sb.append('[').append(e.id).append(',')
                    if (e.type == null) sb.append("null") else PacketJson.str(sb, e.type)
                    sb.append(',').append(e.o).append(',').append(e.lifetime).append(']')
                }
                sb.append(']')
            }
            return sb.toString()
        }
    }

    /** The tick's rows, or null when nothing happened; starts the next tick. Cheap: copies of the used parts of the arrays. */
    fun take(): Snapshot? {
        closeOpen()
        if (nReq == 0 && nSp == 0 && emitRows.isEmpty()) { clear(); return null }
        val s = Snapshot(reqD.copyOf(nReq * 6), reqI.copyOf(nReq * REQ_INTS), nReq, spD.copyOf(nSp * 6), spI.copyOf(nSp * 2), nSp,
            ArrayList(emitRows), ArrayList(strings), ArrayList(opts), optsJson)
        clear()
        return s
    }

    /** [take] and its JSON at once (tests). */
    fun drain(): String? = take()?.json()

    fun clear() {
        nReq = 0; nSp = 0; emitRows.clear(); strings.clear(); stringIndex.clear()
        opts.clear(); optsIndex.clear(); spawnedCount = 0; openRequest = -1; openRequestSpawnedAt = -1
        if (reqI.size > REQ_INTS * 65536) { reqD = DoubleArray(6 * 64); reqI = IntArray(REQ_INTS * 64) }
        if (spI.size > 2 * 65536) { spD = DoubleArray(6 * 64); spI = IntArray(2 * 64) }
    }

    companion object {
        private const val VANILLA = "net.minecraft.client.particle."
        /** Per request: type string index, force|always bits, opts index, made (-1: never returned). */
        private const val REQ_INTS = 4

        /** `FlameParticle` for vanilla's own particle classes (nested ones keep their `$`), the full binary name otherwise. */
        fun className(binaryName: String): String = if (binaryName.startsWith(VANILLA)) binaryName.substring(VANILLA.length) else binaryName
    }
}
