package com.engineerclient.waterboard

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The simulation against Hypixel's own packets: two Water Boards recorded by the Dungeon Recorder on 2026-10-12 (a teammate
 * solving them in view; the room's blocks decoded from the raw chunk packets). waterboard-recorded.json holds, per run, the
 * board (rows as [Geometry.parse] reads them, the rotation, the gates closed), the flips at the server tick they happened,
 * the random ticks the packets show, and every board-cell write the server sent, placed on its server tick.
 *
 *  - a18b44 (NORTH): only the water lever; 11 s of flow into the green outlet, whose open gate closes on arrival.
 *  - a88e9b (SOUTH): water, emerald, quartz, coal, gold, coal: piston pushes into dry and wet cells, pulls, a coal block
 *    pushed over a falling column (no level-7 block is left under the dried cell), and two random ticks.
 */
class WaterBoardRecordedTest {
    private val runs: JsonObject = JsonParser.parseString(
        javaClass.getResource("/waterboard-recorded.json")!!.readText()).asJsonObject

    private class Board(val geo: Geometry, val start: WaterSim, val run: JsonObject)

    private fun board(name: String): Board {
        val r = runs[name].asJsonObject
        val (geo, inn) = Geometry.parse(r["rows"].asJsonArray.map { it.asString }, r["rotation"].asString)
        return Board(geo, WaterSim.fresh(geo, inn, r["closed"].asInt), r)
    }

    /** What the server sends at the end of each tick: every cell whose block really changed (1.8 PlayerChunkMap), as now. */
    private fun sends(b: Board, ticks: Int, random: Boolean): Map<Int, Set<Triple<Int, Int, Int>>> {
        val s = b.start.copy()
        val flips = b.run["flips"].asJsonArray.associate { it.asJsonArray[0].asInt to it.asJsonArray[1].asInt }
        val rnd = HashMap<Int, IntArray>()
        if (random) for (e in b.run["random"].asJsonArray) {
            val a = e.asJsonArray
            rnd[a[0].asInt] = (rnd[a[0].asInt] ?: IntArray(0)) + b.geo.cell(a[1].asInt, a[2].asInt)
        }
        val shadow = s.cells.copyOf()
        val dirty = LinkedHashSet<Int>()
        val out = HashMap<Int, Set<Triple<Int, Int, Int>>>()
        s.onWrite = { c, _ -> if (s.cells[c] != shadow[c]) { shadow[c] = s.cells[c]; dirty += c } }
        s.onSend = {
            out[s.time] = dirty.filter { b.geo.y(it) <= 81 }.map { val v = s.cells[it]; Triple(b.geo.x(it), b.geo.y(it), if (v >= 0) v and 15 else v) }.toSet()
            dirty.clear()
        }
        // the moving piston a push leaves is not written through onWrite: its landing differs from the shadow and is sent
        while (s.time < ticks) s.step(flips[s.time + 1] ?: 0, rnd[s.time + 1])
        return out
    }

    private fun observed(b: Board) = b.run["observed"].asJsonArray.map { e ->
        val a = e.asJsonArray
        a[0].asInt to a[2].asJsonArray.map { w -> val p = w.asJsonArray; Triple(p[0].asInt, p[1].asInt, p[2].asInt) }.toSet()
    }

    private fun check(name: String, mergedOk: Int) {
        val b = board(name)
        val obs = observed(b)
        val sent = sends(b, obs.last().first + 5, random = true)
        var exact = 0; var merged = 0
        val bad = ArrayList<String>()
        for ((t, items) in obs) {
            val s = sent[t].orEmpty()
            when {
                s == items -> exact++
                // two ticks' packets that arrived as one batch
                items == s + sent[t + 1].orEmpty() || items == sent[t - 1].orEmpty() + s -> merged++
                else -> bad += "T$t server ${items - s} sim ${s - items}"
            }
        }
        assertTrue(bad.isEmpty(), "$name: ${bad.joinToString("; ")}")
        assertTrue(merged <= mergedOk, "$name: $merged merged batches")
        assertEquals(obs.size, exact + merged)
    }

    @Test fun pureFlowMatchesEveryPacket() = check("a18b44", 0)

    @Test fun flipsPistonsAndRandomTicksMatchEveryPacket() = check("a88e9b", 1)

    @Test fun gateClosesOnTheTickWaterReachesItsOutlet() {
        // the green outlet (x=10) filled at tick 225; the server's lime wool came with the same packets
        val b = board("a18b44")
        val s = b.start.copy()
        s.arrivals = IntArrayList()
        s.step(1 shl WaterSim.WATER_LEVER, null)
        s.runTo(230)
        assertEquals(1, s.arrivals!!.size)
        assertEquals(225, s.arrivals!![0] shr 3)
        assertEquals(3, s.arrivals!![0] and 7)                    // green
        assertEquals(0b11011, s.closed)                          // it was open: now closed
    }

    @Test fun withoutTheRandomTicksTheSimulationIsLate() {
        // the random ticks are what the packets need: without them water is a few ticks behind the server there
        val b = board("a88e9b")
        val obs = observed(b)
        val sent = sends(b, obs.last().first + 5, random = false)
        assertTrue(obs.count { (t, items) -> sent[t].orEmpty() != items } > 5)
    }

    /**
     * Blind boards: four more from the recordings (2026-10-13 and 10-16, all WEST, a rotation the two above don't have), never
     * looked at while the simulation was changed. Packets as the game hands them to the tracker (every z=26 board write,
     * stair re-shapes and the reservoir row included; lever packets once per real change).
     */
    private fun blind(name: String): Board {
        val r = runs["blind"].asJsonObject[name].asJsonObject
        val (geo, inn) = Geometry.parse(r["rows"].asJsonArray.map { it.asString }, r["rotation"].asString)
        return Board(geo, WaterSim.fresh(geo, inn, r["closed"].asInt), r)
    }

    @Test fun blindBoardsTrackerStaysLocked() {
        for (name in runs["blind"].asJsonObject.keySet()) {
            val b = blind(name)
            val tr = Tracker(b.geo, b.start, -60.0)
            var cells = 0; var last = -60.0
            for (e in b.run["packets"].asJsonArray) {
                val a = e.asJsonArray; val ms = a[0].asDouble
                while (last + 50 < ms) { last += 50; tr.advance(last) }
                tr.advance(ms)
                if (a[1].asString == "lever") tr.lever(ms, a[2].asInt)
                else { tr.cell(ms, b.geo.cell(a[2].asInt, a[3].asInt), a[4].asInt); cells++ }
            }
            println("blind $name: matched ${tr.matched}/$cells, random ticks ${tr.randomTicks}, relocks ${tr.relocks}, resyncs ${tr.resyncs}")
            assertEquals(0, tr.resyncs, "$name resyncs")
            assertTrue(tr.matched >= minOf(cells - 2, (cells * 0.98).toInt()), "$name matched ${tr.matched}/$cells")
        }
    }

    @Test fun blindOwnSolveOpensEachGateOnTheServersTick() {
        // 6436e9: the recorder's own player solving it (10 flips, three gates to open). Flips on the server clock fitted to
        // the packets; the gates' wool as the server moved it, on the same clock.
        val b = blind("6436e9")
        val s = b.start.copy()
        s.arrivals = IntArrayList()
        val flips = b.run["flips"].asJsonArray.associate { it.asJsonArray[0].asInt to it.asJsonArray[1].asInt }
        while (s.time < 300) s.step(flips[s.time + 1] ?: 0)
        val server = b.run["gates"].asJsonArray.map { it.asJsonArray[0].asDouble to it.asJsonArray[1].asInt }
        val sim = (0 until s.arrivals!!.size).map { (s.arrivals!![it] shr 3) to (s.arrivals!![it] and 7) }
        assertEquals(server.map { it.second }, sim.map { it.second }, "outlets in order")
        for ((sv, sm) in server.zip(sim)) assertTrue(kotlin.math.abs(sv.first - sm.first) <= 1.0, "gate ${sm.second}: server ${sv.first}, sim ${sm.first}")
        assertEquals(0, s.closed, "every gate open")
    }

    @Test fun trackerFindsTheRandomTicksFromThePackets() {
        // a18b44's one random tick only re-sends a block unchanged: the tracker's matching window absorbs it, nothing to place
        for ((name, want) in listOf("a18b44" to 0, "a88e9b" to 1)) {
            val b = board(name)
            val tr = Tracker(b.geo, b.start, 0.0)
            var cells = 0
            // a lever packet comes on its tick; the run's first packet (the water lever) is tick 1 at ms 0... as recorded
            val origin = b.run["flips"].asJsonArray[0].asJsonArray[0].asInt
            for (e in b.run["packets"].asJsonArray) {
                val a = e.asJsonArray
                val ms = a[0].asDouble + origin * 50.0
                tr.advance(ms)
                if (a[1].asString == "lever") tr.lever(ms, a[2].asInt)
                else { tr.cell(ms, b.geo.cell(a[2].asInt, a[3].asInt), a[4].asInt); cells++ }
            }
            println("$name: matched ${tr.matched}/$cells, random ticks placed ${tr.randomTicks}, relocks ${tr.relocks}, resyncs ${tr.resyncs}")
            assertEquals(0, tr.resyncs, "$name resyncs")
            assertTrue(tr.matched >= cells - 2, "$name matched ${tr.matched}/$cells")
            assertTrue(tr.randomTicks >= want, "$name random ticks ${tr.randomTicks}")
        }
    }
}
