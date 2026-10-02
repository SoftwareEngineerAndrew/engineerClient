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
 * Everything runs on the game thread (sounds and particles are created on it), and is frozen into
 * strings there; the particle buffer is still guarded by a lock, so a mod spawning particles from
 * another thread cannot corrupt it.
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
            sb.append(",\"vol\":"); PacketJson.num(sb, inst.volume)
            sb.append(",\"pitch\":"); PacketJson.num(sb, inst.pitch)
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
    private val buf = ParticleBuffer()

    /** ParticleTapMixin, at the head of ClientLevel.doAddParticle (every particle the level is asked for). */
    @JvmStatic
    fun requested(opts: ParticleOptions?, force: Boolean, always: Boolean, x: Double, y: Double, z: Double, dx: Double, dy: Double, dz: Double) {
        if (!Rec.active || !particles || opts == null) return
        try {
            synchronized(lock) { buf.request(particleId(opts), opts, { optsJson(opts) }, x, y, z, dx, dy, dz, force, always) }
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
            synchronized(lock) { buf.emitter(id, type, opts, { optsJson(opts) }, lifetime) }
        } catch (t: Throwable) { fail("ptc", t) }
    }

    private fun flushParticles() {
        val body = synchronized(lock) { buf.drain() } ?: return
        Rec.emit("ptc", body)
    }

    private fun particleId(o: ParticleOptions): String =
        runCatching { BuiltInRegistries.PARTICLE_TYPE.getKey(o.type)?.toString() }.getOrNull() ?: PacketJson.simpleName(o.javaClass)

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
 * One tick's particles as JSON members (pure, so it can be tested without a game). Rows:
 *  - req: `[typeId, x, y, z, dx, dy, dz, force, always, o, made]`, `o` the index into `opts` and
 *    `made` how many particles the request added (0 when the particle option or distance filtered it
 *    out, null when the call never returned);
 *  - spawned: `[class, x, y, z, xd, yd, zd, lifetime]`;
 *  - emit: `[entityId, entityType, o, lifetime]` (lifetime -1: the emitter's default);
 *  - opts: `{type, opts}` per distinct options instance this tick (simple particles are singletons,
 *    so a tick of flames carries one entry).
 */
class ParticleBuffer {
    private val req = StringBuilder(4096)
    private val spawned = StringBuilder(4096)
    private val emit = StringBuilder()
    private val opts = StringBuilder()
    private val optsIndex = IdentityHashMap<Any, Int>()
    private var spawnedCount = 0
    private var openRequestSpawnedAt = -1

    /** Index of [key] in this tick's opts, adding it (built by [json]) the first time. */
    fun optsIndex(key: Any, json: () -> String): Int = optsIndex.getOrPut(key) {
        val i = optsIndex.size
        if (i > 0) opts.append(',')
        opts.append(json())
        i
    }

    fun request(typeId: String, key: Any, json: () -> String, x: Double, y: Double, z: Double, dx: Double, dy: Double, dz: Double, force: Boolean, always: Boolean) {
        closeOpen(null)
        val o = optsIndex(key, json)
        if (req.isNotEmpty()) req.append(',')
        req.append('['); PacketJson.str(req, typeId)
        for (d in doubleArrayOf(x, y, z, dx, dy, dz)) { req.append(','); PacketJson.num(req, d) }
        req.append(',').append(force).append(',').append(always).append(',').append(o)
        openRequestSpawnedAt = spawnedCount
    }

    fun requestDone() {
        if (openRequestSpawnedAt < 0) return
        closeOpen(spawnedCount - openRequestSpawnedAt)
    }

    private fun closeOpen(made: Int?) {
        if (openRequestSpawnedAt < 0) return
        req.append(',').append(made?.toString() ?: "null").append(']')
        openRequestSpawnedAt = -1
    }

    fun spawned(cls: String, x: Double, y: Double, z: Double, xd: Double, yd: Double, zd: Double, lifetime: Int) {
        spawnedCount++
        if (spawned.isNotEmpty()) spawned.append(',')
        spawned.append('['); PacketJson.str(spawned, cls)
        for (d in doubleArrayOf(x, y, z, xd, yd, zd)) { spawned.append(','); PacketJson.num(spawned, d) }
        spawned.append(',').append(lifetime).append(']')
    }

    fun emitter(entityId: Int, entityType: String?, key: Any, json: () -> String, lifetime: Int) {
        val o = optsIndex(key, json)
        if (emit.isNotEmpty()) emit.append(',')
        emit.append('[').append(entityId).append(',')
        if (entityType == null) emit.append("null") else PacketJson.str(emit, entityType)
        emit.append(',').append(o).append(',').append(lifetime).append(']')
    }

    /** The tick's members (`"opts":[..],"req":[..],...`, empty lists left out), or null when nothing happened; starts the next tick. */
    fun drain(): String? {
        closeOpen(null)
        if (req.isEmpty() && spawned.isEmpty() && emit.isEmpty()) { clear(); return null }
        val sb = StringBuilder(opts.length + req.length + spawned.length + emit.length + 48)
        sb.append("\"opts\":[").append(opts).append(']')
        if (req.isNotEmpty()) sb.append(",\"req\":[").append(req).append(']')
        if (spawned.isNotEmpty()) sb.append(",\"spawned\":[").append(spawned).append(']')
        if (emit.isNotEmpty()) sb.append(",\"emit\":[").append(emit).append(']')
        clear()
        return sb.toString()
    }

    fun clear() {
        req.setLength(0); spawned.setLength(0); emit.setLength(0); opts.setLength(0)
        optsIndex.clear(); spawnedCount = 0; openRequestSpawnedAt = -1
    }

    companion object {
        private const val VANILLA = "net.minecraft.client.particle."

        /** `FlameParticle` for vanilla's own particle classes (nested ones keep their `$`), the full binary name otherwise. */
        fun className(binaryName: String): String = if (binaryName.startsWith(VANILLA)) binaryName.substring(VANILLA.length) else binaryName
    }
}
