package com.engineerclient.p3sim.clear.puzzles.hard

import com.engineerclient.p3sim.pack.rooms.RoomVolume
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlin.random.Random

/** One block change in the room's canonical frame (FORMAT-rooms 2): [state] is a block-state string, `minecraft:air` to clear. */
class BlockEdit(val x: Int, val y: Int, val z: Int, val state: String)

/** The six gate levers and the water lever of the Water Board, at the cells Odin's solver clicks (canonical room frame). */
enum class WaterLever(val x: Int, val y: Int, val z: Int, val gateBlock: String?) {
    COAL(20, 61, 10, "minecraft:coal_block"),
    GOLD(20, 61, 15, "minecraft:gold_block"),
    QUARTZ(20, 61, 20, "minecraft:quartz_block"),
    DIAMOND(10, 61, 20, "minecraft:diamond_block"),
    EMERALD(10, 61, 15, "minecraft:emerald_block"),
    CLAY(10, 61, 10, "minecraft:terracotta"),
    WATER(15, 60, 5, null);

    companion object {
        fun at(x: Int, y: Int, z: Int): WaterLever? = entries.firstOrNull { it.x == x && it.y == y && it.z == z }
        fun ofGateBlock(name: String): WaterLever? = entries.firstOrNull { it.gateBlock == name }
    }
}

/** One piston gate in the channel wall: when [extended] its block stands in the channel and stops the water. */
class Gate(val x: Int, val y: Int, val lever: WaterLever, var extended: Boolean)

/**
 * The Water Board as a state machine. Water runs down a maze of channels cut into one vertical sheet of the room (the cells at
 * z = [SHEET_Z], x across, y up); each channel holds piston gates that a lever pushes in or pulls out, five drains at the bottom lead
 * to five wool pits, and three of the five pits are closed with wool. A closed pit opens the moment water reaches its drain; the
 * puzzle is solved when all three are open and the chest behind them can be reached.
 *
 * Everything it knows about the board it reads from the room's own block volume ([create]): the gates are the sticky pistons in
 * the wall behind the sheet, the water and the walls are the blocks. The water follows [WaterSim]. The lever times in Odin's
 * Water Board table solve it (the test `WaterBoardOdinTest` runs every entry of the table against the real room).
 *
 * Time: [tick] is one server tick. A lever click moves the gates and the intake at once (the recordings' 2 ticks were
 * client sound latency; the server answers in the lever's own tick, [GATE_DELAY_TICKS] = 0); their block edits come out of the next [tick].
 */
class WaterBoardModel private constructor(
    private val sim: WaterSim,
    val gates: List<Gate>,
    private val leverOn: MutableMap<WaterLever, Boolean>,
    private val leverBase: Map<WaterLever, String>,
    val closedPits: MutableSet<Int>,
) {
    /** Pits that water has opened. */
    val openedPits: MutableSet<Int> = HashSet()

    /** Whether the water lever has been pulled (the intake is then a source and stays one). */
    var waterOn: Boolean = false
        private set

    /** Set once every pit that was closed has opened. */
    var solved: Boolean = false
        private set

    /** Ticks run since the model was built. */
    var now: Int = 0
        private set

    private class Due(val at: Int, val kind: Int, val arg: Int)
    private val pending = ArrayList<Due>()
    private val edits = ArrayList<BlockEdit>()

    fun isLeverOn(l: WaterLever): Boolean = leverOn[l] == true

    /** The edit that flips a lever's own block (powered or not). Its effect on the gates or the water follows in [tick]. */
    fun click(lever: WaterLever): BlockEdit {
        val on = !(leverOn[lever] ?: false)
        leverOn[lever] = on
        // Measured on Hypixel: the pistons and the water answer in the lever's own tick (0 to 1 ticks), so they are applied now and the
        // edits come out with the next [tick].
        if (lever == WaterLever.WATER) setWater(on) else for (g in gates) if (g.lever == lever) toggle(g)
        return leverEdit(lever)
    }

    /** The lever's block in its current position. */
    fun leverEdit(lever: WaterLever) = BlockEdit(lever.x, lever.y, lever.z, withPowered(leverBase.getValue(lever), isLeverOn(lever)))

    /** Every block the board has changed from the room as built, in its current state: for re-applying after a savepoint restore. */
    fun fullEdits(): List<BlockEdit> {
        val out = ArrayList<BlockEdit>()
        for (g in gates) out += gateEdits(g)
        for (l in WaterLever.entries) out += leverEdit(l)
        for (pit in 0..4) out += pitEdits(pit, closed = pit in closedPits && pit !in openedPits)
        val (cells, _) = sim.snapshot()
        var k = 0
        while (k + 3 < cells.size) {
            val x = cells[k]; val y = cells[k + 1]; val z = cells[k + 2]
            if (z == SHEET_Z && y >= 56) out += BlockEdit(x, y, z, "minecraft:water[level=${cells[k + 3]}]")
            k += 4
        }
        return out
    }

    /** Runs one tick; returns the block edits it caused (water, gates, pits), in canonical coordinates. */
    fun tick(): List<BlockEdit> {
        now++
        sim.advance()
        var i = 0
        while (i < pending.size) {
            val p = pending[i]
            if (p.at <= now) {
                pending.removeAt(i)
                run(p)
            } else i++
        }
        for (c in sim.runDue()) {
            edits += BlockEdit(c.x, c.y, c.z, if (c.level < 0) AIR else "minecraft:water[level=${c.level}]")
            if (c.level >= 0 && c.y == DRAIN_Y && c.z == SHEET_Z) {
                val pit = DRAIN_X.indexOf(c.x)
                if (pit >= 0 && pit in closedPits && openedPits.add(pit)) {
                    pending += Due(now, K_PIT, pit)
                    pending.sortBy { it.at }
                }
            }
        }
        // A pit that opened this tick is applied at once (its wool is pulled aside), the others wait their settle ticks.
        var j = 0
        while (j < pending.size) {
            val p = pending[j]
            if (p.at <= now) { pending.removeAt(j); run(p) } else j++
        }
        return ArrayList(edits).also { edits.clear() }
    }

    private fun run(p: Due) {
        when (p.kind) {
            K_WATER -> setWater(p.arg != 0)
            K_GATES -> pending += Due(now + GATE_DELAY_TICKS, K_APPLY, p.arg)
            K_APPLY -> {
                val lever = WaterLever.entries[p.arg]
                for (g in gates) if (g.lever == lever) toggle(g)
            }
            K_PIT -> {
                edits += pitEdits(p.arg, closed = false)
                if (openedPits.containsAll(closedPits) && !solved) solved = true
            }
        }
    }

    /** The water lever: on takes the plug out and sets the intake source; pulled again, the plug goes back and the column drains. */
    private fun setWater(on: Boolean) {
        if (on == waterOn) return
        waterOn = on
        if (on) { sim.setSolid(INTAKE_X, INTAKE_Y, SHEET_Z, false); sim.placeSource(INTAKE_X, INTAKE_Y, SHEET_Z) }
        else { sim.setSolid(INTAKE_X, INTAKE_Y, SHEET_Z, true); edits += BlockEdit(INTAKE_X, INTAKE_Y, SHEET_Z, PLUG) }
    }

    private fun toggle(g: Gate) {
        g.extended = !g.extended
        val block = g.lever.gateBlock!!
        if (g.extended) {
            sim.setSolid(g.x, g.y, SHEET_Z, true)
            edits += BlockEdit(g.x, g.y, PISTON_Z, "minecraft:sticky_piston[extended=true,facing=north]")
            edits += BlockEdit(g.x, g.y, HEAD_Z, HEAD)
            edits += BlockEdit(g.x, g.y, SHEET_Z, block)
        } else {
            edits += BlockEdit(g.x, g.y, SHEET_Z, AIR)
            sim.setSolid(g.x, g.y, SHEET_Z, false)
            edits += BlockEdit(g.x, g.y, HEAD_Z, block)
            edits += BlockEdit(g.x, g.y, PISTON_Z, "minecraft:sticky_piston[extended=false,facing=north]")
        }
    }

    /** The pits that start closed, as edits to apply to the world (nothing for the open ones: the volume already has them open). */
    fun startEdits(): List<BlockEdit> = closedPits.sorted().flatMap { pitEdits(it, closed = true) }

    /** Everything a savepoint needs. */
    fun snapshot(): JsonObject = JsonObject().also { o ->
        o.addProperty("now", now)
        o.addProperty("waterOn", waterOn)
        o.addProperty("solved", solved)
        o.add("closed", JsonArray().also { a -> closedPits.sorted().forEach(a::add) })
        o.add("opened", JsonArray().also { a -> openedPits.sorted().forEach(a::add) })
        o.add("levers", JsonArray().also { a -> WaterLever.entries.filter { isLeverOn(it) }.forEach { a.add(it.name) } })
        o.add("gates", JsonArray().also { a -> gates.forEach { a.add(if (it.extended) 1 else 0) } })
        val (cells, pend) = sim.snapshot()
        o.add("water", JsonArray().also { a -> cells.forEach(a::add) })
        o.add("updates", JsonArray().also { a -> pend.forEach(a::add) })
        o.add("due", JsonArray().also { a -> pending.forEach { a.add("${it.at - now},${it.kind},${it.arg}") } })
    }

    /** Puts the model back to a [snapshot]; returns the edits that make the room's blocks match (the caller applies them after a resync). */
    fun restore(o: JsonObject): List<BlockEdit> {
        now = o.get("now")?.asInt ?: 0
        waterOn = o.get("waterOn")?.asBoolean ?: false
        solved = o.get("solved")?.asBoolean ?: false
        closedPits.clear(); o.getAsJsonArray("closed")?.forEach { closedPits += it.asInt }
        openedPits.clear(); o.getAsJsonArray("opened")?.forEach { openedPits += it.asInt }
        leverOn.clear(); o.getAsJsonArray("levers")?.forEach { runCatching { WaterLever.valueOf(it.asString) }.getOrNull()?.let { l -> leverOn[l] = true } }
        val out = ArrayList<BlockEdit>()
        val ge = o.getAsJsonArray("gates")
        gates.forEachIndexed { i, g ->
            val want = ge?.get(i)?.asInt == 1
            if (want != g.extended) { g.extended = want; out += gateEdits(g) }
            sim.setSolid(g.x, g.y, SHEET_Z, g.extended)
        }
        sim.restore(o.getAsJsonArray("water")?.map { it.asInt }?.toIntArray() ?: IntArray(0), o.getAsJsonArray("updates")?.map { it.asInt }?.toIntArray() ?: IntArray(0))
        pending.clear()
        o.getAsJsonArray("due")?.forEach { s ->
            val f = s.asString.split(','); if (f.size == 3) pending += Due(now + (f[0].toIntOrNull() ?: 0), f[1].toIntOrNull() ?: 0, f[2].toIntOrNull() ?: 0)
        }
        for (pit in closedPits) out += pitEdits(pit, closed = pit !in openedPits)
        return out
    }

    /** The blocks of a gate in its current state. */
    fun gateEdits(g: Gate): List<BlockEdit> {
        val block = g.lever.gateBlock!!
        return if (g.extended) listOf(
            BlockEdit(g.x, g.y, PISTON_Z, "minecraft:sticky_piston[extended=true,facing=north]"), BlockEdit(g.x, g.y, HEAD_Z, HEAD), BlockEdit(g.x, g.y, SHEET_Z, block),
        ) else listOf(
            BlockEdit(g.x, g.y, PISTON_Z, "minecraft:sticky_piston[extended=false,facing=north]"), BlockEdit(g.x, g.y, HEAD_Z, block), BlockEdit(g.x, g.y, SHEET_Z, AIR),
        )
    }

    companion object {
        const val AIR = "minecraft:air"
        private const val DEFAULT_LEVER = "minecraft:lever[face=wall,facing=east,powered=false]"
        private const val HEAD = "minecraft:piston_head[facing=north,short=false,type=sticky]"

        /** Ticks from a lever click to the gates moving. */
        const val GATE_DELAY_TICKS = 0
        /** The block that plugs the intake. */
        const val PLUG = "minecraft:lapis_block"

        /** The sheet the water runs in, the wall layer where an extended gate's head sits, and the layer of the pistons themselves. */
        const val SHEET_Z = 26
        const val HEAD_Z = 27
        const val PISTON_Z = 28

        /** The intake: the water lever puts a source here (the plug of the tank above the maze). */
        const val INTAKE_X = 15
        const val INTAKE_Y = 82

        /** The five drains, by pit index (Odin's wool order: purple, orange, blue, green, red). */
        val DRAIN_X = intArrayOf(24, 20, 15, 10, 6)
        const val DRAIN_Y = 58

        /** Wool colour of each pit, in the same order. */
        val PIT_WOOL = arrayOf("purple_wool", "orange_wool", "blue_wool", "lime_wool", "red_wool")

        /** The z plane of pit [k] (Odin reads the wool at x = 15, y = 56, z = 19 - k). */
        fun pitZ(k: Int) = 19 - k

        private const val K_WATER = 0
        private const val K_GATES = 1
        private const val K_APPLY = 2
        private const val K_PIT = 3

        private const val BOX_Y_MIN = 56
        private const val BOX_Z_MIN = 24
        private const val BOX_Z_MAX = 29

        /** Three of the five pits are closed in every Water Board (Odin refuses any other number). */
        fun randomClosedPits(random: Random): Set<Int> = (0..4).shuffled(random).take(3).toSet()

        fun withPowered(state: String, on: Boolean): String =
            if (on) state.replace("powered=false", "powered=true") else state.replace("powered=true", "powered=false")

        /**
         * Builds the model from the room's volume, or null if the volume is not a Water Board (no gates, or no plug at the intake).
         * [closedPits] null reads them from the volume (a pit is closed when its wool column holds a block at x = 15, y = 56).
         */
        fun create(volume: RoomVolume, closedPits: Set<Int>? = null): WaterBoardModel? {
            if (!volume.contains(INTAKE_X, INTAKE_Y, SHEET_Z) || volume.isAir(INTAKE_X, INTAKE_Y, SHEET_Z)) return null
            val gates = ArrayList<Gate>()
            for (y in BOX_Y_MIN until volume.y0 + volume.h) for (x in 0 until volume.w) {
                val piston = volume.stateAt(x, y, PISTON_Z)
                if (!piston.startsWith("minecraft:sticky_piston") || !piston.contains("facing=north")) continue
                val extended = volume.stateAt(x, y, HEAD_Z).startsWith("minecraft:piston_head")
                val block = volume.stateAt(x, y, if (extended) SHEET_Z else HEAD_Z).substringBefore('[')
                val lever = WaterLever.ofGateBlock(block) ?: continue
                gates += Gate(x, y, lever, extended)
            }
            if (gates.isEmpty()) return null
            val yTop = volume.y0 + volume.h
            val sim = WaterSim(0, BOX_Y_MIN, BOX_Z_MIN, volume.w, yTop - BOX_Y_MIN, BOX_Z_MAX - BOX_Z_MIN + 1)
            for (y in BOX_Y_MIN until yTop) for (z in BOX_Z_MIN..BOX_Z_MAX) for (x in 0 until volume.w) {
                val s = volume.stateAt(x, y, z)
                if (s == AIR) continue
                if (s.startsWith("minecraft:water[level=")) sim.setStaticWater(x, y, z, s.substringAfter("level=").substringBefore(']').toIntOrNull() ?: 0)
                else sim.setSolid(x, y, z, true)
            }
            val closed = closedPits?.toMutableSet() ?: (0..4).filter { !volume.isAir(15, 56, pitZ(it)) }.toMutableSet()
            val lev = HashMap<WaterLever, Boolean>()
            val base = HashMap<WaterLever, String>()
            for (l in WaterLever.entries) {
                val st = volume.stateAt(l.x, l.y, l.z)
                lev[l] = st.contains("powered=true")
                base[l] = if (st.startsWith("minecraft:lever")) withPowered(st, false) else DEFAULT_LEVER
            }
            return WaterBoardModel(sim, gates, lev, base, closed)
        }

        /**
         * The blocks of pit [k] (the plane z = 19 - k between x = 11 and x = 19, y = 53 to 57): closed, the side pistons are pushed out and wool
         * fills the passage with redstone blocks behind them; open, the wool is pulled to the sides and the redstone is stone again.
         */
        fun pitEdits(k: Int, closed: Boolean): List<BlockEdit> {
            val z = pitZ(k)
            val wool = "minecraft:${PIT_WOOL[k]}"
            val out = ArrayList<BlockEdit>()
            val stone = "minecraft:stone"
            if (closed) {
                out += BlockEdit(15, 53, z, "minecraft:redstone_block")
                out += BlockEdit(15, 54, z, "minecraft:sticky_piston[extended=true,facing=up]")
                out += BlockEdit(15, 55, z, "minecraft:piston_head[facing=up,short=false,type=sticky]")
                for (y in 56..57) {
                    out += BlockEdit(11, y, z, "minecraft:redstone_block")
                    out += BlockEdit(12, y, z, "minecraft:sticky_piston[extended=true,facing=east]")
                    out += BlockEdit(13, y, z, "minecraft:piston_head[facing=east,short=false,type=sticky]")
                    for (x in 14..16) out += BlockEdit(x, y, z, wool)
                    out += BlockEdit(17, y, z, "minecraft:piston_head[facing=west,short=false,type=sticky]")
                    out += BlockEdit(18, y, z, "minecraft:sticky_piston[extended=true,facing=west]")
                    out += BlockEdit(19, y, z, "minecraft:redstone_block")
                }
            } else {
                out += BlockEdit(15, 53, z, stone)
                out += BlockEdit(15, 54, z, "minecraft:sticky_piston[extended=false,facing=up]")
                out += BlockEdit(15, 55, z, wool)
                for (y in 56..57) {
                    out += BlockEdit(11, y, z, stone)
                    out += BlockEdit(12, y, z, "minecraft:sticky_piston[extended=false,facing=east]")
                    out += BlockEdit(13, y, z, wool)
                    for (x in 14..16) out += BlockEdit(x, y, z, AIR)
                    out += BlockEdit(17, y, z, wool)
                    out += BlockEdit(18, y, z, "minecraft:sticky_piston[extended=false,facing=west]")
                    out += BlockEdit(19, y, z, stone)
                }
            }
            return out
        }
    }
}
