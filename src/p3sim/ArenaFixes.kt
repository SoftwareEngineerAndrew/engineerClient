package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import com.google.common.collect.ImmutableMultimap
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ResolvableProfile
import net.minecraft.world.level.block.SkullBlock
import net.minecraft.world.level.block.entity.SkullBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess
import java.util.UUID
import kotlin.random.Random
import net.minecraft.world.level.block.Blocks as B

/**
 * Static and per-run corrections to the arena that arena.bin (built from the ADR library) does not carry,
 * from packet captures of Hypixel P3s (`arena-fixes.json`, `arena-ext.bin`):
 * - the thicker east wall (x128-134) and the NW column ([Arena.Data] patch, `arena-ext.bin`);
 * - the y63 floor layer, each cell's most common solid block from the recorded spread (no block is removed:
 *   every block stays as built);
 * - player-head textures;
 * - [Replay]: that same recorded run's block changes from Terms on (TNT cubes, polished granite blobs,
 *   the S4 plate), so a fight gets the timing of a real one.
 * Everything that moves at runtime goes through [Blocks.set], so [Blocks.restoreAll] puts it back.
 */
object ArenaFixes {
    internal class Run(val init: IntArray, val events: IntArray)

    private class Table(
        val states: Array<BlockState>,
        val runs: List<Run>,
        val root: JsonObject,
    )

    private val table: Table? by lazy {
        try {
            val text = ArenaFixes::class.java.getResourceAsStream("/assets/engineerclient/p3sim/arena-fixes.json")?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return@lazy null
            val root = JsonParser.parseString(text).asJsonObject
            val states = root.getAsJsonArray("states").map { Arena.parse(it.asString) }.toTypedArray()
            val runs = root.getAsJsonArray("runs").map { r ->
                val o = r.asJsonObject
                fun flat(a: JsonArray): IntArray { val out = ArrayList<Int>(); a.forEach { e -> e.asJsonArray.forEach { out += it.asInt } }; return out.toIntArray() }
                Run(flat(o.getAsJsonArray("init")), flat(o.getAsJsonArray("events")))
            }
            Table(states, runs, root)
        } catch (t: Throwable) {
            EngineerClient.logger.warn("[p3sim] arena-fixes.json unreadable", t)
            null
        }
    }

    /** The recorded run this world copies (lantern pattern, and [Replay]'s timing). Picked when the arena data is read. */
    @Volatile private var pick = 0

    /** Called when the arena is read: the base data with every static correction applied (a new, larger box). */
    fun patch(base: Arena.Data): Arena.Data {
        val t = table
        val ext = try { Arena.readBin("arena-ext.bin") } catch (e: Throwable) { EngineerClient.logger.warn("[p3sim] arena-ext.bin unreadable", e); null }
        val x0 = minOf(base.x0, ext?.x0 ?: base.x0); val y0 = minOf(base.y0, ext?.y0 ?: base.y0); val z0 = minOf(base.z0, ext?.z0 ?: base.z0)
        val x1 = maxOf(base.x0 + base.w, ext?.let { it.x0 + it.w } ?: 0); val y1 = maxOf(base.y0 + base.h, ext?.let { it.y0 + it.h } ?: 0); val z1 = maxOf(base.z0 + base.d, ext?.let { it.z0 + it.d } ?: 0)
        val w = x1 - x0; val h = y1 - y0; val d = z1 - z0
        val states = ArrayList<BlockState>(base.states.toList())
        val index = HashMap<BlockState, Int>()
        states.forEachIndexed { i, s -> index.putIfAbsent(s, i) }
        fun idx(s: BlockState): Int = index.getOrPut(s) { states += s; states.size - 1 }
        val air = B.AIR.defaultBlockState()
        val airIdx = idx(air).toShort()
        val cells = ShortArray(w * h * d) { airIdx }
        for (y in 0 until base.h) for (z in 0 until base.d) {
            System.arraycopy(base.cells, (y * base.d + z) * base.w, cells, (((y + base.y0 - y0) * d) + (z + base.z0 - z0)) * w + (base.x0 - x0), base.w)
        }
        fun cell(x: Int, y: Int, z: Int) = (((y - y0) * d) + (z - z0)) * w + (x - x0)
        fun inBox(x: Int, y: Int, z: Int) = x >= x0 && x < x1 && y >= y0 && y < y1 && z >= z0 && z < z1
        fun put(x: Int, y: Int, z: Int, s: BlockState) { if (inBox(x, y, z)) cells[cell(x, y, z)] = idx(s).toShort() }
        fun at(x: Int, y: Int, z: Int): BlockState = if (inBox(x, y, z)) states[cells[cell(x, y, z)].toInt()] else air

        // The thicker east wall and the NW column.
        if (ext != null) {
            for (y in 0 until ext.h) for (z in 0 until ext.d) for (x in 0 until ext.w) {
                val s = ext.states[ext.cells[(y * ext.d + z) * ext.w + x].toInt()]
                if (s.`is`(B.STRUCTURE_VOID)) continue
                put(ext.x0 + x, ext.y0 + y, ext.z0 + z, s)
            }
        }
        if (t != null) {
            val st = t.states
            val root = t.root
            // The y63 layer varies per run on Hypixel: here always each cell's most common solid block, the same every
            // load (a roll per load left holes, and a world saved with one roll never matched the next load's).
            for (e in root.getAsJsonArray("y63")) {
                val a = e.asJsonArray
                val best = a[2].asJsonArray.map { it.asJsonArray }.filter { !st[it[0].asInt].isAir }.maxByOrNull { it[1].asInt } ?: continue
                put(a[0].asInt, 63, a[1].asInt, st[best[0].asInt])
            }
            // (No removals: the blocks Hypixel's arena has lost by Terms stay, every block as built.)
            // The run this world's Replay copies. The Arrow Align wall (x -3) is drawn by Devices.Arrows.
            pick = Random.nextInt(t.runs.size)
        }
        return Arena.Data(x0, y0, z0, w, h, d, states.toTypedArray(), cells)
    }

    // ------------------------------------------------------------------ player heads

    private class Skull(val x: Int, val y: Int, val z: Int, val profile: ResolvableProfile)

    private val skulls: Map<Long, List<Skull>> by lazy {
        val out = HashMap<Long, MutableList<Skull>>()
        EngineerClient.safely("p3sim skulls") {
            val root = table?.root ?: return@safely
            val tex = root.getAsJsonArray("skullTex").map { it.asString }
            for (e in root.getAsJsonArray("skulls")) {
                val a = e.asJsonArray
                val id = a[5].asJsonArray.map { it.asInt }
                val uuid = UUID((id[0].toLong() shl 32) or (id[1].toLong() and 0xffffffffL), (id[2].toLong() shl 32) or (id[3].toLong() and 0xffffffffL))
                val t = tex[a[3].asInt]
                val gp = GameProfile(uuid, a[4].asString, PropertyMap(ImmutableMultimap.of("textures", Property("textures", t))))
                val x = a[0].asInt; val z = a[2].asInt
                out.getOrPut(ChunkPosKey.of(x shr 4, z shr 4)) { ArrayList() } += Skull(x, a[1].asInt, z, ResolvableProfile.createResolved(gp))
            }
        }
        out
    }

    private object ChunkPosKey { fun of(cx: Int, cz: Int): Long = (cx.toLong() shl 32) or (cz.toLong() and 0xffffffffL) }

    /** A player head put back at [pos] ([Arena.restore], [Arena.resync]) gets its texture again, the clients told. */
    fun reskull(level: net.minecraft.server.level.ServerLevel, pos: BlockPos) {
        val state = level.getBlockState(pos)
        if (state.block !is SkullBlock) return
        val s = skulls[ChunkPosKey.of(pos.x shr 4, pos.z shr 4)]?.firstOrNull { it.x == pos.x && it.y == pos.y && it.z == pos.z } ?: return
        val be = level.getBlockEntity(pos) as? SkullBlockEntity ?: return
        val stack = ItemStack(Items.PLAYER_HEAD)
        stack.set(DataComponents.PROFILE, s.profile)
        be.applyComponentsFromItemStack(stack)
        be.setChanged()
        level.sendBlockUpdated(pos, state, state, 3)
    }

    /** Gives the player heads of a freshly filled chunk their texture (block entities on the proto chunk). */
    fun skulls(chunk: ChunkAccess) {
        val list = skulls[ChunkPosKey.of(chunk.pos.minBlockX shr 4, chunk.pos.minBlockZ shr 4)] ?: return
        for (s in list) {
            val pos = BlockPos(s.x, s.y, s.z)
            val state = chunk.getBlockState(pos)
            if (state.block !is SkullBlock) continue
            val be = SkullBlockEntity(pos, state)
            val stack = ItemStack(Items.PLAYER_HEAD)
            stack.set(DataComponents.PROFILE, s.profile)
            be.applyComponentsFromItemStack(stack)
            chunk.setBlockEntity(be)
        }
    }

    // ------------------------------------------------------------------ the recorded run's changes

    /** One recorded run's block changes, relative to n (server ticks since Goldor's first line). */
    class Replay internal constructor(private val run: Run, private val states: Array<BlockState>) {
        private var ptr = 0

        /** Start state at Terms (granite blobs already up, the plate's press), then the changes up to [n0] at once. */
        fun begin(n0: Int) {
            val a = run.init
            for (i in 0 until a.size step 4) if (a[i] != -3) place(a[i], a[i + 1], a[i + 2], states[a[i + 3]])
            catchUp = true
            try { tick(n0 - 1) } finally { catchUp = false }
        }

        /** While [begin] catches up on changes before the start: no primed TNT for those. */
        private var catchUp = false

        fun tick(n: Int) {
            val e = run.events
            while (ptr * 5 < e.size && e[ptr * 5] <= n) {
                val i = ptr * 5; ptr++
                if (e[i + 1] == -3) continue // the Arrow Align wall: Devices.Arrows draws it
                val s = e[i + 4]
                val pos = BlockPos(e[i + 1], e[i + 2], e[i + 3])
                if (s < 0) {
                    // A blob going away: back to as built, if it is still there.
                    if (Blocks.get(pos)?.`is`(B.POLISHED_GRANITE) == true) Blocks.set(pos, Arena.data.get(pos.x, pos.y, pos.z))
                } else place(pos.x, pos.y, pos.z, states[s])
            }
        }

        /** The blocks this replay put up itself (TNT cubes, granite blobs...): the only ones it ever takes away. */
        private var placed: HashSet<BlockPos>? = null
        private fun placed() = placed ?: HashSet<BlockPos>().also { placed = it }

        private fun place(x: Int, y: Int, z: Int, s: BlockState) {
            val pos = BlockPos(x, y, z)
            val cur = Blocks.get(pos) ?: return
            // Goldor breaks nothing: only a block this replay put up goes, back to what was built there (air only if nothing was).
            if (s.isAir) {
                if (!placed().remove(pos)) return
                val built = Arena.data.get(x, y, z)
                if (built.isAir && cur.`is`(B.TNT) && !catchUp) { primeCube(pos); return }
                Blocks.set(pos, built)
                return
            }
            // Block entities (heads, dispensers) are left alone: replacing one would drop its data.
            if (cur.hasBlockEntity()) return
            placed() += pos
            Blocks.set(pos, s)
        }

        /**
         * A TNT cube going away as Hypixel does it: a primed TNT on each block (fuse 80, vanilla's random
         * (+-0.02, 0.2, +-0.02) hop, entity.tnt.primed BLOCKS 1.0/1.0 each), the block air one tick later, and the
         * entity gone 21 ticks on, never exploding or hurting anything.
         */
        private fun primeCube(pos: BlockPos) {
            val tnt = object : net.minecraft.world.entity.item.PrimedTnt(Sim.level, pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, null) {
                override fun shouldBeSaved() = false
            }
            tnt.fuse = 80
            Sim.spawn(tnt)
            Sim.sound(net.minecraft.sounds.SoundEvents.TNT_PRIMED, 1f, 1f, tnt.position(), net.minecraft.sounds.SoundSource.BLOCKS)
            Fight.later(1, "tnt cube block") { if (Blocks.get(pos)?.`is`(B.TNT) == true) Blocks.set(pos, B.AIR.defaultBlockState()) }
            Fight.later(21, "tnt cube gone") { tnt.discard() }
        }
    }

    /** The replay of this world's picked recorded run, or null if the data is missing. */
    fun replay(): Replay? {
        val t = table ?: return null
        return Replay(t.runs[pick.coerceIn(0, t.runs.size - 1)], t.states)
    }
}
