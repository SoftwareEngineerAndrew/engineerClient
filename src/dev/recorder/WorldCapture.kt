package com.devgineerclient.recorder

import com.devgineerclient.DevgineerClient
import com.odtheking.odin.events.BlockUpdateEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.LevelChunkSection
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.level.saveddata.maps.MapId
import net.minecraft.world.level.saveddata.maps.MapItemSavedData
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

/**
 * The world as the client holds it, next to the packets that built it:
 *
 *  - `cchunk`: chunks the client actually loaded and unloaded. A chunk packet the client threw away
 *    (outside its view range) is written as "ignored", so a reader knows the packet never became world.
 *  - `blk`: every block change the client applied, with the state before and after and where it came
 *    from: "server" (a block or section update), "ack" (the server settling a block you predicted) or
 *    "local" (your own prediction, an explosion the client simulates, ...).
 *  - `mapfull`: a map's whole picture each time it changes once applied (the dungeon map is assembled
 *    here, patch after patch), with its decorations.
 *  - `env`: time, weather, world border, game mode and world height, polled each second and written
 *    when changed (the clocks move, so that is once a second while time runs).
 *  - keyframes ("world"): every loaded chunk block for block with its block entities, then every map
 *    seen, the env line and the list of loaded chunks; every 60 s the chunks changed since.
 *
 * Everything here runs on the game thread and freezes what it reads before queueing it: chunk
 * sections are copied (the copies are private, so packing them later on the writer thread cannot
 * trip their thread checks), block entities and map decorations are written out at once.
 */
object WorldCapture {

    /** Where the block change being applied came from; set around the server and ack paths by BlockChangeSourceMixin (game thread only). */
    private var source: String? = null

    /** Chunk packets seen on the network thread and not yet loaded: chunk -> tick seen. */
    private val pendingLoads = ConcurrentHashMap<Long, Int>()
    /** Chunks with a block change since their last snapshot (game thread). */
    private val dirty = HashSet<Long>()
    /** Map id -> SHA-1 of the colours last written (game thread). */
    private val mapHashes = HashMap<Int, String>()

    /** Chunks still to snapshot for a keyframe, a few per tick, with the keyframe id and whether it is a dirty pass. */
    private class Pending(val key: Long, val kf: Long, val dirtyPass: Boolean)
    private val snapshotQueue = ArrayDeque<Pending>()

    private var lastSession: RecorderSession? = null
    private var lastDirtyPassTick = 0

    private const val CHUNKS_PER_TICK = 64
    private const val MIN_CHUNKS_PER_TICK = 4
    private const val TICK_BUDGET_NS = 1_000_000L
    private const val DIRTY_PASS_TICKS = 20 * 60
    /** Chunks scanned around the player for a keyframe: past any server view distance plus the cache margin. */
    private const val SCAN_RADIUS = 40
    private val FULL_REASONS = setOf("confirm", "part", "enable", "respawn", "gap")

    fun install() {
        ClientChunkEvents.CHUNK_LOAD.register { _, chunk -> if (Rec.active) DevgineerClient.safely("recorder chunk load") { chunkEvent("load", chunk.pos.x, chunk.pos.z) } }
        ClientChunkEvents.CHUNK_UNLOAD.register { _, chunk -> if (Rec.active) DevgineerClient.safely("recorder chunk unload") { chunkEvent("unload", chunk.pos.x, chunk.pos.z) } }
        // Odin posts this from LevelChunk.setBlockState before the change is made: the old state is still there.
        // Odin's mixin is common, so in singleplayer it also posts the integrated server's changes, on
        // the server thread: only the client's (the game thread's) are this world's.
        on<BlockUpdateEvent>(priority = Int.MIN_VALUE) {
            if (!Rec.active || !DevgineerClient.mc.isSameThread) return@on
            DevgineerClient.safely("recorder blk") { blockChange(this) }
        }
        on<TickEvent.End>(priority = Int.MIN_VALUE) {
            if (!Rec.active) return@on
            DevgineerClient.safely("recorder world tick") { tick() }
        }
        Rec.onKeyframe("world") { reason -> keyframe(reason) }
        EventBus.subscribe(this)
    }

    // ------------------------------------------------------------------ hooks from mixins

    /** HEAD of ClientLevel.setServerVerifiedBlockState / handleBlockChangedAck (game thread). */
    @JvmStatic
    fun sourceEnter(s: String) { if (Rec.active) source = s }

    /** RETURN of the same: back to local changes. A single field write, so it costs nothing when off. */
    @JvmStatic
    fun sourceExit() { source = null }

    /** TAIL of ClientPacketListener.handleMapItemData: on the game thread only, with the patch applied. */
    @JvmStatic
    fun mapApplied(packet: ClientboundMapItemDataPacket) {
        if (!Rec.active) return
        DevgineerClient.safely("recorder map") {
            val level = DevgineerClient.mc.level ?: return@safely
            val id = packet.mapId().id()
            val data = level.getMapData(packet.mapId()) ?: return@safely
            mapFull(id, data, kf = null)
        }
    }

    /** A chunk packet reached the network thread; it should load within a couple of ticks. */
    fun chunkPacket(x: Int, z: Int) {
        if (!Rec.active) return
        pendingLoads[ChunkPos.pack(x, z)] = Rec.tick
    }

    // ------------------------------------------------------------------ events

    private fun chunkEvent(ev: String, x: Int, z: Int) {
        val key = ChunkPos.pack(x, z)
        if (ev == "load") pendingLoads.remove(key)
        // A (re)loaded chunk is whole in its packet line; an unloaded one has nothing left to snapshot.
        dirty.remove(key)
        Rec.emit("cchunk", "\"ev\":\"$ev\",\"x\":$x,\"z\":$z")
    }

    private fun blockChange(e: BlockUpdateEvent) {
        val p = e.pos
        val x = p.x; val y = p.y; val z = p.z
        val old = e.old; val new = e.updated
        val src = source ?: "local"
        dirty += ChunkPos.pack(x shr 4, z shr 4)
        // Block states are immutable: they can be named on the writer thread.
        Rec.emitLazy("blk", 160, "blk") {
            "\"p\":[$x,$y,$z],\"old\":${RecorderFiles.q(ChunkCapture.stateName(old))},\"new\":${RecorderFiles.q(ChunkCapture.stateName(new))},\"src\":\"$src\""
        }
    }

    private fun tick() {
        val s = Rec.session
        if (s !== lastSession) {
            lastSession = s
            dirty.clear(); mapHashes.clear(); snapshotQueue.clear()
            lastDirtyPassTick = Rec.tick
        }
        val level = DevgineerClient.mc.level ?: return

        // Chunk packets the client never loaded (out of view range, or for a level already gone).
        if (pendingLoads.isNotEmpty()) {
            val it = pendingLoads.entries.iterator()
            while (it.hasNext()) {
                val (key, t) = it.next()
                if (Rec.tick - t > 2) {
                    it.remove()
                    Rec.emit("cchunk", "\"ev\":\"ignored\",\"x\":${ChunkPos.getX(key)},\"z\":${ChunkPos.getZ(key)}")
                }
            }
        }

        if (Rec.tick % 20 == 0) env(level)

        if (snapshotQueue.isEmpty() && Rec.tick - lastDirtyPassTick >= DIRTY_PASS_TICKS) {
            lastDirtyPassTick = Rec.tick
            if (ChunkCapture.enabled) dirty.toList().forEach { snapshotQueue.add(Pending(it, Rec.keyframeId, true)) }
        }
        // A time budget, not only a count: a keyframe's chunks never cost a tick more than about a millisecond.
        var n = 0
        val t0 = System.nanoTime()
        while (n < CHUNKS_PER_TICK && (n < MIN_CHUNKS_PER_TICK || System.nanoTime() - t0 < TICK_BUDGET_NS)) {
            val job = snapshotQueue.poll() ?: break
            if (snapshotChunk(level, job)) n++
        }
    }

    /** The env line, written when anything in it changed (and again in each part and keyframe). */
    private fun env(level: ClientLevel, kf: Long? = null) {
        val wb = level.worldBorder
        val mode = DevgineerClient.mc.gameMode?.playerMode?.name
        val body = "\"clock\":${level.defaultClockTime},\"gameTime\":${level.gameTime}," +
            "\"rain\":${num(level.getRainLevel(1f))},\"thunder\":${num(level.getThunderLevel(1f))}," +
            "\"border\":[${num(wb.centerX)},${num(wb.centerZ)},${num(wb.size)}],\"gameMode\":${RecorderFiles.q(mode)}," +
            "\"minY\":${level.minY},\"height\":${level.height}"
        // The clocks move every tick, so in practice this is one line a second; the rest rides along.
        if (kf != null) { Rec.changed("env", body); Rec.emit("env", "\"kf\":$kf,$body"); return }
        if (Rec.changed("env", body)) Rec.emit("env", body)
    }

    // ------------------------------------------------------------------ maps

    /** `mapfull` when the picture or anything about the map changed (always, for a keyframe). */
    private fun mapFull(id: Int, data: MapItemSavedData, kf: Long?) {
        val colors = data.colors.clone()
        val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(colors))
        // Decorations hold Components and change with every packet: written out now, on this thread.
        val dec = StringBuilder("[")
        data.decorations.forEachIndexed { i, d ->
            if (i > 0) dec.append(',')
            dec.append('[')
            PacketJson.str(dec, d.type().unwrapKey().map { it.identifier().toString() }.orElse("?"))
            dec.append(',').append(d.x()).append(',').append(d.y()).append(',').append(d.rot()).append(',')
            val name = d.name().orElse(null)
            if (name == null) dec.append("null") else RichJson.component(dec, name)
            dec.append(']')
        }
        dec.append(']')
        val decs = dec.toString()
        val state = "$sha|${data.scale}|${data.locked}|$decs"
        val changed = mapHashes.put(id, state) != state
        if (!changed && kf == null) return
        val scale = data.scale; val locked = data.locked
        Rec.emitLazy("mapfull", colors.size * 4 / 3 + decs.length + 128, "mapfull") {
            (if (kf != null) "\"kf\":$kf," else "") +
                "\"id\":$id,\"scale\":$scale,\"locked\":$locked,\"sha1\":\"$sha\",\"b64\":\"${Base64.getEncoder().encodeToString(colors)}\"," +
                "\"dec\":$decs,\"odinIgnored\":${(id and 1000) != 0}"
        }
    }

    // ------------------------------------------------------------------ keyframes

    /**
     * The "world" part of a keyframe (game thread). A full one (confirm, new part, turned on,
     * respawn, after a gap) queues every loaded chunk; any other asks only for the chunks changed
     * since their last snapshot. The chunks go out a few a tick (up to [CHUNKS_PER_TICK], within
     * about a millisecond) so a keyframe never stalls a frame; the maps, env and chunk list go out at once.
     */
    private fun keyframe(reason: String) {
        if (!Rec.active) return
        val level = DevgineerClient.mc.level ?: return
        val kf = Rec.keyframeId
        val loaded = loadedChunks(level)
        if (ChunkCapture.enabled) {
            val keys = if (reason in FULL_REASONS) loaded.toList() else dirty.filter { it in loaded }
            snapshotQueue.removeIf { it.key in keys }
            keys.forEach { snapshotQueue.add(Pending(it, kf, false)) }
        }
        for (id in mapHashes.keys.toList()) level.getMapData(MapId(id))?.let { mapFull(id, it, kf) }
        env(level, kf)
        val p = DevgineerClient.mc.player
        val cx = (p?.blockX ?: 0) shr 4; val cz = (p?.blockZ ?: 0) shr 4
        val list = loaded.joinToString(",") { "[${ChunkPos.getX(it)},${ChunkPos.getZ(it)}]" }
        Rec.emit("kfchunks", "\"kf\":$kf,\"scan\":[$cx,$cz,$SCAN_RADIUS],\"count\":${loaded.size},\"chunks\":[$list]")
    }

    /** Every chunk the client holds, found by asking its cache around the player (the cache keeps no public list). */
    private fun loadedChunks(level: ClientLevel): LinkedHashSet<Long> {
        val p = DevgineerClient.mc.player
        val cx = (p?.blockX ?: 0) shr 4; val cz = (p?.blockZ ?: 0) shr 4
        val out = LinkedHashSet<Long>()
        val cache = level.chunkSource
        // Nearest first, so the chunks around the player are snapshotted first.
        for (r in 0..SCAN_RADIUS) for (dx in -r..r) for (dz in -r..r) {
            if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dz)) != r) continue
            if (cache.getChunk(cx + dx, cz + dz, ChunkStatus.FULL, false) != null) out += ChunkPos.pack(cx + dx, cz + dz)
        }
        return out
    }

    /**
     * One chunk of a keyframe: its sections copied here, packed on the writer thread (`kfchunk`), and
     * its block entities saved here (each save is a fresh tag nothing else holds) and written out on
     * the writer thread (`kfbe`). False if the chunk is gone.
     */
    private fun snapshotChunk(level: ClientLevel, job: Pending): Boolean {
        val x = ChunkPos.getX(job.key); val z = ChunkPos.getZ(job.key)
        val chunk = level.chunkSource.getChunk(x, z, ChunkStatus.FULL, false) ?: return false
        val factory = ChunkCapture.factory() ?: return false
        val secs: List<LevelChunkSection> = chunk.sections.map { it.copy() }
        val minSy = level.minSectionY
        dirty.remove(job.key)
        val tag = if (job.dirtyPass) ",\"dirty\":true" else ""
        val kf = job.kf
        Rec.emitLazy("kfchunk", 4096 + secs.size * 2600, "kfchunk") {
            val sb = StringBuilder(16384)
            sb.append("\"kf\":").append(kf).append(tag).append(",\"x\":").append(x).append(",\"z\":").append(z).append(",\"minSy\":").append(minSy)
            RichJson.member(sb, "s") {
                sb.append('[')
                secs.forEachIndexed { i, sec ->
                    if (i > 0) sb.append(',')
                    sb.append("{\"i\":").append(i).append(",\"y\":").append(minSy + i)
                    ChunkCapture.section(sb, sec, factory)
                    sb.append('}')
                }
                sb.append(']'); true
            }
            sb.toString()
        }
        val bes = chunk.blockEntities.values.toList()
        if (bes.isNotEmpty()) {
            val ra = level.registryAccess()
            class Be(val x: Int, val y: Int, val z: Int, val type: String, val state: net.minecraft.world.level.block.state.BlockState, val tag: net.minecraft.nbt.CompoundTag?)
            val rows = bes.map { be ->
                val pos = be.blockPos
                Be(pos.x, pos.y, pos.z, BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.type)?.toString() ?: "?", be.blockState,
                    try { be.saveWithoutMetadata(ra) } catch (t: Throwable) { null })
            }
            Rec.emitLazy("kfbe", 64 + 256 * rows.size, "kfbe") {
                val sb = StringBuilder(256 * rows.size)
                sb.append("\"kf\":").append(kf).append(tag).append(",\"x\":").append(x).append(",\"z\":").append(z).append(",\"d\":[")
                rows.forEachIndexed { i, r ->
                    if (i > 0) sb.append(',')
                    sb.append('[').append(r.x).append(',').append(r.y).append(',').append(r.z).append(',')
                    PacketJson.str(sb, r.type); sb.append(',')
                    PacketJson.str(sb, ChunkCapture.stateName(r.state)); sb.append(',')
                    val snbt = r.tag?.let { runCatching { it.toString() }.getOrNull() }
                    if (snbt == null) sb.append("null") else PacketJson.str(sb, snbt)
                    sb.append(']')
                }
                sb.append(']').toString()
            }
        }
        return true
    }

    private fun num(d: Double): String = StringBuilder().also { PacketJson.num(it, d) }.toString()
    private fun num(f: Float): String = StringBuilder().also { PacketJson.num(it, f) }.toString()
}
