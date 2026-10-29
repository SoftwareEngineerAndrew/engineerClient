package com.devgineerclient.misc

import com.devgineerclient.DevgineerClient
import com.odtheking.odin.clickgui.settings.impl.ActionSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.skyblock.Island
import com.odtheking.odin.utils.skyblock.LocationUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.IntArrayTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.StringTag
import net.minecraft.util.ProblemReporter
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.storage.TagValueOutput
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * A 1:1 copy of the Dungeon Hub for the ADS, taken live while you walk around it. While on and in the Dungeon Hub, every
 * chunk the server sends is copied (block states, biomes, block entities) a few per tick after it loads and again as it
 * unloads, so later block changes are kept; the NPCs are sampled every 2 s (real players, UUID version 4, are left out).
 * A chat line says how many chunks are in and which edges are still open: a chunk with blocks whose neighbour hasn't been
 * seen yet. Turning it off (or Save Now) writes config/devgineerclient/hub-capture-<time>.nbt, gzip NBT in the PF bot's
 * capture format, so captures merge.
 */
object HubCapture : Module(
    name = "Hub Capture",
    key = null,
    category = Category.custom("Devgineer Client", 860, 10),
    description = "Copies the Dungeon Hub's chunks and NPCs while you walk around it; saves when turned off.",
) {
    private val saveNow by ActionSetting("Save Now", desc = "Writes what has been captured so far (it keeps capturing).") { save() }

    private val chunks = HashMap<Long, CompoundTag>()
    private val nonEmpty = HashSet<Long>()
    private val pending = LinkedHashSet<Long>()
    private val entities = HashMap<String, CompoundTag>()
    private var arrived: CompoundTag? = null
    private var level: ClientLevel? = null
    private var lastSample = 0L
    private var lastReport = 0L
    private var reported = ""

    private const val PER_TICK = 3

    init {
        ClientChunkEvents.CHUNK_LOAD.register { _, c -> if (active()) pending.add(c.pos.pack()) }
        ClientChunkEvents.CHUNK_UNLOAD.register { _, c -> if (active()) DevgineerClient.safely("hub capture unload") { copy(c) } }
        ClientTickEvents.END_CLIENT_TICK.register { DevgineerClient.safely("hub capture") { if (enabled) tick() } }
    }

    private fun active() = enabled && LocationUtils.isCurrentArea(Island.DungeonHub)

    override fun onEnable() {
        super.onEnable()
        chunks.clear(); nonEmpty.clear(); pending.clear(); entities.clear(); arrived = null; level = null; reported = ""
        DevgineerClient.msg("Hub Capture on: walk around the Dungeon Hub, turn it off to save.")
    }

    override fun onDisable() {
        super.onDisable()
        DevgineerClient.safely("hub capture save") { save() }
    }

    private fun tick() {
        val mc = DevgineerClient.mc
        val lvl = mc.level ?: return
        val player = mc.player ?: return
        if (!LocationUtils.isCurrentArea(Island.DungeonHub)) { level = null; return }
        if (lvl !== level) {
            // Every chunk already loaded when the hub is (or the module is turned on in it) is still to copy.
            if (level == null && arrived == null) arrived = here()
            level = lvl
            for (key in loadedAround(player.chunkPosition())) {
                val c = ChunkPos.unpack(key)
                if (lvl.chunkSource.getChunk(c.x(), c.z(), false) != null) pending.add(key)
            }
        }
        var n = 0
        val it = pending.iterator()
        while (n < PER_TICK && it.hasNext()) {
            val pos = ChunkPos.unpack(it.next()); it.remove()
            lvl.chunkSource.getChunk(pos.x(), pos.z(), false)?.let { copy(it); n++ }
        }
        val now = System.currentTimeMillis()
        if (now - lastSample >= 2_000L) { lastSample = now; sampleEntities(lvl) }
        if (now - lastReport >= 5_000L) { lastReport = now; report(player.chunkPosition()) }
    }

    /** The chunks the server can have sent around [c] (Hypixel's view distance is under 16). */
    private fun loadedAround(c: ChunkPos): List<Long> {
        val out = ArrayList<Long>()
        for (dx in -16..16) for (dz in -16..16) out += ChunkPos(c.x() + dx, c.z() + dz).pack()
        return out
    }

    private fun here(): CompoundTag {
        val p = DevgineerClient.mc.player!!
        return CompoundTag().apply {
            putDouble("x", p.x); putDouble("y", p.y); putDouble("z", p.z); putFloat("yaw", p.yRot); putFloat("pitch", p.xRot)
            putLong("ms", System.currentTimeMillis())
        }
    }

    private fun copy(chunk: LevelChunk) {
        val level = DevgineerClient.mc.level ?: return
        val pos = chunk.pos
        val tag = CompoundTag()
        tag.putInt("x", pos.x()); tag.putInt("z", pos.z())
        val sections = ListTag()
        for ((i, s) in chunk.sections.withIndex()) {
            if (s.hasOnlyAir()) continue
            val palette = LinkedHashMap<BlockState, Int>()
            val idx = IntArray(4096)
            for (y in 0 until 16) for (z in 0 until 16) for (x in 0 until 16)
                idx[(y * 16 + z) * 16 + x] = palette.getOrPut(s.getBlockState(x, y, z)) { palette.size }
            val biomes = LinkedHashMap<String, Int>()
            val bidx = IntArray(64)
            for (y in 0 until 4) for (z in 0 until 4) for (x in 0 until 4)
                bidx[(y * 4 + z) * 4 + x] = biomes.getOrPut(s.getNoiseBiome(x, y, z).unwrapKey().map { it.identifier().toString() }.orElse("?")) { biomes.size }
            sections.add(CompoundTag().apply {
                putInt("y", chunk.getSectionYFromSectionIndex(i))
                put("palette", ListTag().apply { palette.keys.forEach { add(NbtUtils.writeBlockState(it)) } })
                put("blocks", IntArrayTag(idx))
                put("biomes", ListTag().apply { biomes.keys.forEach { add(StringTag.valueOf(it)) } })
                put("biomeCells", IntArrayTag(bidx))
            })
        }
        tag.put("sections", sections)
        tag.put("blockEntities", ListTag().apply {
            for (be in chunk.blockEntities.values) try { add(be.saveWithFullMetadata(level.registryAccess())) } catch (_: Exception) {}
        })
        val key = pos.pack()
        chunks[key] = tag
        if (sections.isEmpty()) nonEmpty.remove(key) else nonEmpty.add(key)
    }

    private fun sampleEntities(level: ClientLevel) {
        for (e in level.entitiesForRendering()) {
            if (e === DevgineerClient.mc.player) continue
            if (e is Player && e.uuid.version() == 4) continue
            try {
                val out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess())
                e.saveWithoutId(out)
                val tag = out.buildResult()
                tag.putString("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.type).toString())
                tag.putInt("netId", e.id)
                tag.putFloat("headYaw", e.yHeadRot)
                if (e is Player) DevgineerClient.mc.connection?.getPlayerInfo(e.uuid)?.profile?.let { prof ->
                    tag.putString("profileName", prof.name())
                    tag.put("properties", ListTag().apply {
                        for (p in prof.properties().values()) add(CompoundTag().apply {
                            putString("name", p.name()); putString("value", p.value()); p.signature()?.let { putString("signature", it) }
                        })
                    })
                }
                entities[e.uuid.toString()] = tag
            } catch (_: Throwable) {}
        }
    }

    /** Open edges: chunks with blocks next to a chunk not seen yet, grouped by side, nearest first. */
    private fun report(at: ChunkPos) {
        val open = HashMap<String, MutableList<ChunkPos>>()
        for (key in nonEmpty) {
            val c = ChunkPos.unpack(key)
            for ((side, dx, dz) in listOf(Triple("north", 0, -1), Triple("south", 0, 1), Triple("west", -1, 0), Triple("east", 1, 0)))
                if (ChunkPos(c.x() + dx, c.z() + dz).pack() !in chunks) open.getOrPut(side) { ArrayList() } += c
        }
        val line = if (open.isEmpty()) (if (pending.isEmpty()) "no open edges" else "none yet, still copying") else open.entries.sortedBy { it.key }.joinToString(", ") { (side, cs) ->
            val near = cs.minBy { (it.x() - at.x()) * (it.x() - at.x()) + (it.z() - at.z()) * (it.z() - at.z()) }
            "$side ${cs.size} (nearest block ${near.minBlockX + 8} ${near.minBlockZ + 8})"
        }
        if (line == reported) return
        reported = line
        DevgineerClient.msg("Hub Capture: ${chunks.size} chunks${if (pending.isEmpty()) "" else " (+${pending.size} queued)"}; open edges: $line")
    }

    private fun save() {
        val mc = DevgineerClient.mc
        val level = mc.level
        if (chunks.isEmpty()) { DevgineerClient.msg("Hub Capture: nothing captured yet."); return }
        val root = CompoundTag()
        arrived?.let { root.put("arrived", it) }
        if (mc.player != null) root.put("end", here())
        if (level != null) {
            root.putBoolean("raining", level.isRaining)
            root.putString("dimension", level.dimension().identifier().toString())
            root.putInt("minY", level.minY); root.putInt("height", level.height)
            root.put("sidebar", ListTag().apply {
                val board = level.scoreboard
                board.getDisplayObjective(DisplaySlot.SIDEBAR)?.let { obj ->
                    add(StringTag.valueOf(obj.displayName.string))
                    board.listPlayerScores(obj).filter { !it.isHidden }.sortedByDescending { it.value() }
                        .forEach { add(StringTag.valueOf(PlayerTeam.formatNameForTeam(board.getPlayersTeam(it.owner()), it.ownerName()).string)) }
                }
            })
        }
        root.put("chunks", ListTag().apply { chunks.values.forEach { add(it.copy()) } })
        root.put("entities", ListTag().apply { entities.values.forEach { add(it.copy()) } })
        val dir = mc.gameDirectory.toPath().resolve("config").resolve("devgineerclient")
        val f = dir.resolve("hub-capture-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".nbt")
        val n = chunks.size
        Thread({
            try {
                Files.createDirectories(dir)
                val tmp = dir.resolve(f.fileName.toString() + ".tmp")
                NbtIo.writeCompressed(root, tmp)
                Files.move(tmp, f, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                mc.execute { DevgineerClient.msg("Hub Capture: saved $n chunks to ${f.fileName}") }
            } catch (t: Throwable) {
                DevgineerClient.logger.warn("hub capture save failed", t)
                mc.execute { DevgineerClient.msg("Hub Capture: save failed ($t)") }
            }
        }, "hub-capture-save").start()
    }
}
