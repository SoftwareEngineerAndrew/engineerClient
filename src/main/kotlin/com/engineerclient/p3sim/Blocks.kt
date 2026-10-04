package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.state.BlockState
import kotlin.random.Random
import net.minecraft.world.level.block.Blocks as B

/**
 * Every block the fight changes goes through here, so a restart (or leaving the world) can put the
 * arena back exactly as built. Also plays the arena's scripted animations (gates, doors, the core,
 * the floors between phases) frame by frame as recorded on Hypixel (`anims-*.json`, extracted from
 * Better PF runs), and the world's own rules that are not fixed frames: Maxor's conveyor strip
 * after its recording ends, and Goldor eating the walkway as he walks (tools/p3sim/research/world.md).
 */
object Blocks {
    private val touched = LinkedHashSet<BlockPos>()

    fun set(pos: BlockPos, state: BlockState) {
        val level = SimServer.level ?: return
        touched += pos.immutable()
        Arena.set(level, pos, state)
    }

    fun set(x: Int, y: Int, z: Int, state: BlockState) = set(BlockPos(x, y, z), state)

    fun get(pos: BlockPos): BlockState? = SimServer.level?.getBlockState(pos)

    /** Puts back every block the fight changed. */
    fun restoreAll() {
        val level = SimServer.level ?: return
        anims.clear()
        done.clear()
        conveyor = null
        carvedFor = null
        touched.forEach { Arena.restore(level, it) }
        touched.clear()
    }

    // ------------------------------------------------------------------ recorded animations

    /** [chance] < 1: the frame happens with that chance, rolled once per fight (the P1 platforms' crumble). */
    class Frame(val dt: Int, val pos: BlockPos, val state: BlockState, val chance: Float = 1f)
    class Anim(val name: String, val event: String, val frames: List<Frame>) {
        val positions: Set<BlockPos> by lazy { frames.mapTo(HashSet()) { it.pos } }
        val length: Int get() = frames.lastOrNull()?.dt ?: 0
    }

    private val library: Map<String, Anim> by lazy {
        val out = HashMap<String, Anim>()
        for (file in listOf("anims-p3.json", "anims-p124.json")) EngineerClient.safely("p3sim $file") {
            val text = Blocks::class.java.getResourceAsStream("/assets/engineerclient/p3sim/$file")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return@safely
            val root = JsonParser.parseString(text).asJsonObject
            for ((name, v) in root.entrySet()) {
                if (name.startsWith("_") || !v.isJsonObject) continue
                val o = v.asJsonObject
                val fr = o.getAsJsonArray("frames") ?: continue
                val states = HashMap<String, BlockState>()
                val frames = fr.map { e ->
                    val a = e.asJsonArray
                    val s = a[4].asString
                    Frame(a[0].asInt, BlockPos(a[1].asInt, a[2].asInt, a[3].asInt), states.getOrPut(s) { Arena.parse(if (':' in s) s else "minecraft:$s") }, if (a.size() > 5) a[5].asFloat else 1f)
                }.sortedBy { it.dt }
                out[name] = Anim(name, o.get("event")?.asString ?: "", frames)
            }
        }
        out
    }

    fun anim(name: String): Anim? = library[name]

    /** Extra data in an animation file (pillar structure and the like). */
    fun extra(file: String, key: String): JsonObject? = try {
        Blocks::class.java.getResourceAsStream("/assets/engineerclient/p3sim/$file")?.use {
            JsonParser.parseString(it.readBytes().toString(Charsets.UTF_8)).asJsonObject.getAsJsonObject(key)
        }
    } catch (t: Throwable) { null }

    private class Playing(val anim: Anim, val start: Int, var next: Int = 0) {
        /** The chance frames that lost their roll. */
        val skip = BooleanArray(anim.frames.size) { anim.frames[it].chance < 1f && Random.nextFloat() >= anim.frames[it].chance }
    }
    private val anims = ArrayList<Playing>()
    /** Animations played or finished this fight: a second [finish] (a phase catching up on one the start already did) is a no-op. */
    private val done = HashSet<String>()

    /**
     * Starts [name] now (its frame 0 this tick). [skip]: start that many ticks in (catching up).
     * [exclude]: positions the phase drives itself, left out of the recording's frames (P1's beacon column).
     */
    fun play(name: String, skip: Int = 0, exclude: Set<BlockPos> = emptySet()) {
        val a = library[name] ?: run { EngineerClient.logger.warn("[p3sim] no animation {}", name); return }
        done += name
        val p = Playing(a, Fight.serverTick - skip)
        if (exclude.isNotEmpty()) a.frames.forEachIndexed { i, f -> if (f.pos in exclude) p.skip[i] = true }
        anims += p
        advance(p)
        if (name == STRIP) conveyor = Conveyor(p.start + a.length + STRIP_PERIOD)
    }

    /** Jumps [name] to its end state at once (starting a phase past it); once per fight. */
    fun finish(name: String) {
        val a = library[name] ?: return
        if (!done.add(name)) return
        val p = Playing(a, 0)
        a.frames.forEachIndexed { i, f -> if (!p.skip[i]) set(f.pos, f.state) }
    }

    /** Stops [name] where it is (the strip when Maxor dies). */
    fun stop(name: String) {
        anims.removeAll { it.anim.name == name }
        if (name == STRIP) conveyor = null
    }

    fun isPlaying(name: String) = anims.any { it.anim.name == name }

    fun tick() {
        if (anims.isNotEmpty()) {
            anims.toList().forEach { advance(it) }
            anims.removeAll { it.next >= it.anim.frames.size }
        }
        EngineerClient.safely("p3sim strip") { conveyorTick() }
        EngineerClient.safely("p3sim carve") { carveTick() }
    }

    private fun advance(p: Playing) {
        val now = Fight.serverTick - p.start
        val f = p.anim.frames
        while (p.next < f.size && f[p.next].dt <= now) { if (!p.skip[p.next]) set(f[p.next].pos, f[p.next].state); p.next++ }
    }

    // ------------------------------------------------------------------ the world at a phase start

    /**
     * A menu start: the world as the earlier phases leave it on Hypixel (world.md §1), so a start
     * from the middle sees what a full run would. The phase itself then adds its own part.
     */
    fun prepare(start: Fight.Start) {
        if (start == Fight.Start.P1) return
        // Maxor's end: the strip where it stopped, the beacon column bedrock under red glass, the floors gone.
        finish(STRIP)
        set(73, 221, 73, B.BEDROCK.defaultBlockState())
        for (y in 222..224) set(73, y, 73, B.RED_STAINED_GLASS.defaultBlockState())
        finish("p1end")
        if (start == Fight.Start.P2) return
        stormPillars()
        if (start != Fight.Start.P4) return
        // P3 done: the drop hole, every gate and door, the core open, Goldor's walk eaten into the walkway.
        for (a in listOf("p3start", "gate12", "door1", "ss_s1done", "gate23", "door2", "gate34", "door3", "core")) finish(a)
        replayCarve(CORE_N)
    }

    /**
     * Storm's pillars as they hang at Goldor's line (101 runs): Purple and Yellow, the two that
     * crushed, back up with their bottom at y183 (181-186), every block polished diorite (Hypixel
     * puts back no plain diorite); Green and Red as built.
     */
    private fun stormPillars() {
        val p = extra("anims-p124.json", "pillars") ?: return
        val origins = p.getAsJsonObject("origins")
        val footprint = p.getAsJsonArray("footprint").map { val a = it.asJsonArray; a[0].asInt to a[1].asInt }
        for (name in listOf("Purple", "Yellow")) {
            val o = origins.getAsJsonObject(name)
            val x0 = o.get("x").asInt; val z0 = o.get("z").asInt
            for ((dx, dz) in footprint) {
                for (y in 175 until PILLAR_BOTTOM) set(x0 + dx, y, z0 + dz, B.AIR.defaultBlockState())
                for (y in PILLAR_BOTTOM..189) set(x0 + dx, y, z0 + dz, B.POLISHED_DIORITE.defaultBlockState())
            }
        }
    }

    // ------------------------------------------------------------------ Maxor's conveyor strip

    /**
     * The P1 strip keeps moving after its recording (407 ticks) while Maxor lives: per row, every
     * block takes the one west of it every 10 ticks (anims-p124.json `p1stripRule`). What comes in
     * at the west end: the floor rows' chevrons repeat every 7 blocks, the item rows bring in air;
     * past the gap at the beacon the floor comes in gray (as recorded).
     */
    private class Conveyor(var next: Int)
    private var conveyor: Conveyor? = null
    private val stripRows: List<List<BlockPos>> by lazy {
        val pos = library[STRIP]?.positions ?: emptySet()
        val rows = (70..76).map { 224 to it } + (225..226).flatMap { y -> (72..74).map { y to it } }
        rows.map { (y, z) -> (34..112).map { BlockPos(it, y, z) }.filter { it in pos && !(it.x == 73 && it.z == 73) } }
    }

    private fun conveyorTick() {
        val c = conveyor ?: return
        val maxor = Fight.phase as? P1Maxor
        // Hypixel's last update is at Maxor's death: the recording stops there too.
        if (maxor == null || maxor.status() == "Maxor dead") { stop(STRIP); return }
        if (Fight.serverTick < c.next || isPlaying(STRIP)) return
        c.next = Fight.serverTick + STRIP_PERIOD
        val level = SimServer.level ?: return
        for (row in stripRows) {
            val inRow = row.toHashSet()
            val old = row.associateWith { level.getBlockState(it) }
            for (p in row) {
                val west = p.west()
                val s = when {
                    west in inRow -> old.getValue(west)
                    p.y != 224 -> B.AIR.defaultBlockState()
                    p.x == row.first().x -> old[p.offset(6, 0, 0)] ?: B.GRAY_WOOL.defaultBlockState()
                    else -> B.GRAY_WOOL.defaultBlockState()
                }
                set(p, s)
            }
        }
    }

    // ------------------------------------------------------------------ Goldor's carving

    /**
     * Goldor eats the walkway as he walks (world.md §3, 30 runs, 126 passes with him in view):
     * every 40 server ticks from n 37 (n = 37 + 40k, ±1), every block in the 11x11x11 box round
     * his block (x, z ±5, y ±5) goes with a 60% chance, rolled again each pass, so the walls and
     * floor along his path thin out over a few passes. Barriers (the walkway's invisible walls) and
     * gold blocks always stay; the cobblestone portcullis at the S1 entrance (cobblestone, walls,
     * nether brick fences) always goes. Levers, buttons and blocks with a block entity are left for
     * the devices (never seen carved).
     */
    private var carvedFor: GoldorPhase? = null

    private fun carveTick() {
        val ph = Fight.phase as? GoldorPhase ?: run { carvedFor = null; return }
        if (carvedFor !== ph) {
            // A start past n 37: the passes Goldor's walk so far would have made.
            carvedFor = ph
            replayCarve(ph.n)
        }
        val n = ph.n
        if (ph.goldor.flying || n < CARVE_FIRST || (n - CARVE_FIRST) % CARVE_PERIOD != 0) return
        carve(ph.goldor.position.x, ph.goldor.position.y, ph.goldor.position.z)
    }

    /** The carving passes before [untilN], along his walk from (80, 119, 40) (no catch-up sprints). */
    private fun replayCarve(untilN: Int) {
        var n = CARVE_FIRST
        while (n < untilN) {
            val at = GoldorPhase.Goldor.trackPos((GoldorPhase.Goldor.START_S + GoldorPhase.Goldor.WALK * n) % GoldorPhase.Goldor.LOOP)
            carve(at.x, at.y, at.z)
            n += CARVE_PERIOD
        }
    }

    private fun carve(x: Double, y: Double, z: Double) {
        val level = SimServer.level ?: return
        val cx = Math.floor(x).toInt(); val cy = Math.floor(y).toInt(); val cz = Math.floor(z).toInt()
        val pos = BlockPos.MutableBlockPos()
        for (dx in -CARVE_R..CARVE_R) for (dy in -CARVE_R..CARVE_R) for (dz in -CARVE_R..CARVE_R) {
            val s = level.getBlockState(pos.set(cx + dx, cy + dy, cz + dz))
            if (s.isAir || !s.fluidState.isEmpty || s.hasBlockEntity() || s.`is`(B.BARRIER) || s.`is`(B.GOLD_BLOCK) || s.block is LeverBlock || s.block is ButtonBlock) continue
            val sure = s.`is`(B.COBBLESTONE) || s.`is`(B.COBBLESTONE_WALL) || s.`is`(B.NETHER_BRICK_FENCE)
            if (sure || Random.nextFloat() < CARVE_CHANCE) set(pos.immutable(), B.AIR.defaultBlockState())
        }
    }

    private const val STRIP = "p1strip"
    private const val STRIP_PERIOD = 10
    private const val PILLAR_BOTTOM = 183
    private const val CARVE_FIRST = 37
    private const val CARVE_PERIOD = 40
    private const val CARVE_R = 5
    private const val CARVE_CHANCE = 0.6f
    /** n of a Core start (GoldorPhase's median fast run): how far Goldor walked before P4. */
    private const val CORE_N = 797
}
