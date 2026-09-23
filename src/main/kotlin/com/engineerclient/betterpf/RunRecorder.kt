package com.engineerclient.betterpf

import com.engineerclient.EngineerClient
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.skyblock.Island
import com.odtheking.odin.utils.skyblock.LocationUtils
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.status.ChunkStatus
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream

/**
 * One recording session: everything that happens in one world, from the moment it loads.
 *
 * Format: gzipped JSON Lines, one event per line, every line has "k" (kind) and, for anything that
 * happens during the run, "t" (ticks since the world loaded). See tools/betterpf-viewer/FORMAT.md.
 *
 * Nothing is kept unless the world turns out to be a dungeon: until [DungeonUtils.inDungeons]
 * reports true, lines pile up in memory; the moment it does, the file is opened and the backlog
 * flushed, so the recording still starts at instance load. If Odin works out the area is something else, or a minute passes without it, the
 * session is dropped - a hub or island visit costs nothing on disk.
 *
 * Disk writes happen on a single background thread; the client thread only builds strings.
 */
class RunRecorder(private val dir: Path, private val captureGeometry: Boolean, private val onSaved: (Path) -> Unit = {}) {

    private var tick = 0
    private var confirmed = false
    var abandoned = false
        private set
    private val backlog = ArrayList<String>()

    private val io = Executors.newSingleThreadExecutor { Thread(it, "ec-betterpf-writer").apply { isDaemon = true } }
    private var writer: BufferedWriter? = null
    private var tempFile: Path? = null
    private var linesWritten = 0L

    private val startedAt = LocalDateTime.now()
    private var floorName: String? = null
    private var partyKey = ""

    // Entity tracking: last written position/name per entity id, to only write changes.
    private class Tracked(var x: Double, var y: Double, var z: Double, var yaw: Float, var name: String)
    private val tracked = HashMap<Int, Tracked>()

    // Geometry: chunks seen loading, scanned a couple per tick once the run is confirmed.
    private val pendingChunks = ArrayDeque<ChunkPos>()
    private val scannedChunks = HashSet<Long>()
    private val palette = HashMap<BlockState, Int>()

    // ------------------------------------------------------------------ inputs

    fun onTick(level: ClientLevel) {
        tick++
        if (!confirmed) {
            if (DungeonUtils.inDungeons) confirm()
            // Area is known a second or two after load; anything that is known and not a dungeon is dropped
            // right away rather than buffered for the full timeout.
            else if (tick > ABANDON_AFTER_TICKS || !LocationUtils.isCurrentArea(Island.Unknown)) { abandon(); return }
        }
        if (tick % 20 == 0) emit("""{"k":"time","t":$tick,"ms":${System.currentTimeMillis()}}""")
        recordFloorAndParty()
        if (tick % 10 == 0) recordRooms()
        recordPlayers(level)
        recordEntities(level)
        if (confirmed && captureGeometry) scanSomeChunks(level)
    }

    fun onChunkLoad(pos: ChunkPos) {
        if (captureGeometry && scannedChunks.add((pos.x().toLong() shl 32) or (pos.z().toLong() and 0xffffffffL))) pendingChunks.addLast(pos)
    }

    fun onBlockUpdate(pos: BlockPos, state: BlockState) {
        emit("""{"k":"block","t":$tick,"x":${pos.x},"y":${pos.y},"z":${pos.z},"s":${paletteIndex(state)}}""")
    }

    fun onChat(message: String) = emit("""{"k":"chat","t":$tick,"m":${str(message)}}""")

    fun onRoomEnter(name: String?) = emit("""{"k":"room","t":$tick,"name":${str(name ?: "Unknown")}}""")

    /** Ends the session: closes the file (on the writer thread) and gives it its final name. */
    fun finish() {
        if (!confirmed) { abandon(); return }
        emit("""{"k":"end","t":$tick,"ms":${System.currentTimeMillis()}}""")
        val temp = tempFile ?: return
        val finalName = "${startedAt.format(STAMP)}_${(floorName ?: "unknown").replace(Regex("[^A-Za-z0-9]+"), "")}.jsonl.gz"
        val lines = linesWritten
        io.execute {
            try {
                writer?.close()
                val target = temp.resolveSibling(finalName)
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
                val mb = Files.size(target) / 1_000_000.0
                EngineerClient.chat("§8[§6EC§8]§7 Better PF: saved run §f$finalName §7(${String.format(Locale.ROOT, "%.1f", mb)} MB, $lines lines, ${tick / 20}s)")
                onSaved(target)
            } catch (t: Throwable) {
                EngineerClient.logger.error("[ec] betterpf: failed to finish run file", t)
            }
        }
        io.shutdown()
    }

    // ------------------------------------------------------------------ lifecycle

    private fun confirm() {
        confirmed = true
        Files.createDirectories(dir)
        val temp = dir.resolve("recording-${startedAt.format(STAMP)}.jsonl.gz.part")
        tempFile = temp
        writer = BufferedWriter(OutputStreamWriter(GZIPOutputStream(Files.newOutputStream(temp)), Charsets.UTF_8), 1 shl 16)
        val self = EngineerClient.mc.player?.name?.string ?: "?"
        val version = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("engineerclient")
            .map { it.metadata.version.friendlyString }.orElse("?")
        writeNow("""{"k":"meta","format":1,"mod":${str(version)},"mc":"26.1.2","self":${str(self)},"startMs":${System.currentTimeMillis() - tick * 50L},"confirmedAtTick":$tick,"geometry":$captureGeometry}""")
        backlog.forEach(::writeNow)
        backlog.clear()
        EngineerClient.chat("§8[§6EC§8]§7 Better PF: recording this run")
    }

    private fun abandon() {
        abandoned = true
        backlog.clear()
        tracked.clear()
        pendingChunks.clear()
        io.shutdown()
    }

    private fun emit(line: String) {
        if (abandoned) return
        if (confirmed) writeNow(line) else backlog.add(line)
    }

    private fun writeNow(line: String) {
        val w = writer ?: return
        linesWritten++
        io.execute {
            try { w.write(line); w.newLine() } catch (t: Throwable) { EngineerClient.logger.error("[ec] betterpf write failed", t) }
        }
    }

    // ------------------------------------------------------------------ per-tick snapshots

    private fun recordFloorAndParty() {
        val floor = DungeonUtils.floor?.name
        if (floor != null && floor != floorName) {
            floorName = floor
            emit("""{"k":"floor","t":$tick,"floor":${str(floor)}}""")
        }
        val party = DungeonUtils.dungeonTeammates
        val key = party.joinToString("|") { "${it.name}:${it.clazz.name}" }
        if (key != partyKey && party.isNotEmpty()) {
            partyKey = key
            emit("""{"k":"party","t":$tick,"m":[${party.joinToString(",") { "[${str(it.name)},${str(it.clazz.name)}]" }}]}""")
        }
    }

    private var roomsKey = ""

    /**
     * Odin's own room classification: every room it knows (from the dungeon map, and named once its
     * core has been seen), with type, shape, rotation and checkmark. Rewritten whenever any of that
     * changes, which is also how cleared/failed state shows up over time.
     */
    private fun recordRooms() {
        val rooms = DungeonScan.rooms
        if (rooms.isEmpty()) return
        val sb = StringBuilder()
        rooms.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            sb.append('[').append(str(r.name ?: "")).append(',').append(str(r.type.name)).append(',')
                .append(str(r.shape.name)).append(',').append(str(r.rotation?.name ?: "")).append(',')
                .append(str(r.checkmark.name)).append(",[")
            r.tiles.forEachIndexed { j, t -> if (j > 0) sb.append(','); sb.append('[').append(t.x).append(',').append(t.z).append(']') }
            sb.append("]]")
        }
        val body = sb.toString()
        if (body == roomsKey) return
        roomsKey = body
        emit("""{"k":"rooms","t":$tick,"r":[$body]}""")
    }

    private fun recordPlayers(level: ClientLevel) {
        val players = level.players()
        if (players.isEmpty()) return
        val sb = StringBuilder(64 * players.size).append("""{"k":"p","t":$tick,"d":[""")
        players.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            val held = p.mainHandItem.let { if (it.isEmpty) "" else it.itemId.ifEmpty { BuiltInRegistries.ITEM.getKey(it.item).toString() } }
            sb.append('[').append(str(p.name.string)).append(',')
                .append(n(p.x)).append(',').append(n(p.y)).append(',').append(n(p.z)).append(',')
                .append(a(p.yRot)).append(',').append(a(p.xRot)).append(',').append(str(held)).append(',')
                .append(p.uuid.version()).append(']')
        }
        emit(sb.append("]}").toString())
    }

    private fun recordEntities(level: ClientLevel) {
        val seen = HashSet<Int>(tracked.size + 16)
        val moved = StringBuilder()
        var movedCount = 0
        for (e in level.entitiesForRendering()) {
            if (e is Player) continue
            val id = e.id
            seen += id
            val name = e.customName?.string ?: ""
            val t = tracked[id]
            if (t == null) {
                tracked[id] = Tracked(e.x, e.y, e.z, e.yRot, name)
                emit("""{"k":"spawn","t":$tick,"id":$id,"type":${str(typeOf(e))},"name":${str(name)},"x":${n(e.x)},"y":${n(e.y)},"z":${n(e.z)},"yaw":${a(e.yRot)}}""")
                continue
            }
            if (name != t.name) {
                t.name = name
                emit("""{"k":"name","t":$tick,"id":$id,"name":${str(name)}}""")
            }
            if (e.x != t.x || e.y != t.y || e.z != t.z || e.yRot != t.yaw) {
                t.x = e.x; t.y = e.y; t.z = e.z; t.yaw = e.yRot
                if (movedCount++ > 0) moved.append(',')
                moved.append('[').append(id).append(',').append(n(e.x)).append(',').append(n(e.y)).append(',')
                    .append(n(e.z)).append(',').append(a(e.yRot)).append(']')
            }
        }
        if (movedCount > 0) emit("""{"k":"e","t":$tick,"d":[$moved]}""")
        val gone = tracked.keys.filter { it !in seen }
        for (id in gone) {
            tracked.remove(id)
            emit("""{"k":"gone","t":$tick,"id":$id}""")
        }
    }

    // ------------------------------------------------------------------ geometry

    /**
     * Writes a chunk's "shell": solid blocks with at least one air neighbour. That is the whole
     * visible/collidable surface of a room without the solid interior, which is most of the volume.
     * Doors, levers and anything else that changes later arrives as "block" lines on top of this.
     */
    private fun scanSomeChunks(level: ClientLevel) {
        repeat(CHUNKS_PER_TICK) {
            val pos = pendingChunks.removeFirstOrNull() ?: return
            val chunk = level.chunkSource.getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false) ?: return@repeat
            val flat = StringBuilder()
            var count = 0
            val sections = chunk.sections
            val cursor = BlockPos.MutableBlockPos()
            for ((index, section) in sections.withIndex()) {
                if (section.hasOnlyAir()) continue
                val baseY = chunk.getSectionYFromSectionIndex(index) shl 4
                for (ly in 0 until 16) for (lz in 0 until 16) for (lx in 0 until 16) {
                    val state = section.getBlockState(lx, ly, lz)
                    if (state.isAir) continue
                    val x = pos.minBlockX + lx
                    val y = baseY + ly
                    val z = pos.minBlockZ + lz
                    if (!exposed(level, cursor, x, y, z)) continue
                    if (count++ > 0) flat.append(',')
                    flat.append(x).append(',').append(y).append(',').append(z).append(',').append(paletteIndex(state))
                }
            }
            if (count > 0) emit("""{"k":"chunk","t":$tick,"cx":${pos.x()},"cz":${pos.z()},"b":[$flat]}""")
        }
    }

    private fun exposed(level: ClientLevel, c: BlockPos.MutableBlockPos, x: Int, y: Int, z: Int): Boolean =
        level.getBlockState(c.set(x + 1, y, z)).isAir || level.getBlockState(c.set(x - 1, y, z)).isAir ||
            level.getBlockState(c.set(x, y + 1, z)).isAir || level.getBlockState(c.set(x, y - 1, z)).isAir ||
            level.getBlockState(c.set(x, y, z + 1)).isAir || level.getBlockState(c.set(x, y, z - 1)).isAir

    private fun paletteIndex(state: BlockState): Int = palette.getOrPut(state) {
        val i = palette.size
        emit("""{"k":"pal","i":$i,"s":${str(BlockStateParser.serialize(state))}}""")
        i
    }

    // ------------------------------------------------------------------ formatting

    private fun typeOf(e: Entity): String = BuiltInRegistries.ENTITY_TYPE.getKey(e.type).toString()

    private fun n(v: Double) = String.format(Locale.ROOT, "%.3f", v)
    private fun a(v: Float) = String.format(Locale.ROOT, "%.1f", v)

    private fun str(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (ch in s) when {
            ch == '"' -> sb.append("\\\"")
            ch == '\\' -> sb.append("\\\\")
            ch == '\n' -> sb.append("\\n")
            ch < ' ' -> sb.append(String.format(Locale.ROOT, "\\u%04x", ch.code))
            else -> sb.append(ch)
        }
        return sb.append('"').toString()
    }

    private companion object {
        const val ABANDON_AFTER_TICKS = 20 * 60
        const val CHUNKS_PER_TICK = 2
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    }
}
