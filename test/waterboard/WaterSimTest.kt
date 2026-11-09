package com.engineerclient.waterboard

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The simulator against the server. Fixtures (test/resources/waterboard) come from the 2026-10 Dungeon Recorder recordings:
 * the four boards as they are before anyone touches them, and four played runs (one per room rotation) with every block
 * packet the server sent for the board and the lever flips, their server ticks fitted to those packets. The reference sends
 * are the scratchpad model's, which matched every packet of those four runs.
 */
class WaterSimTest {
    private fun json(name: String): JsonObject =
        JsonParser.parseString(javaClass.getResourceAsStream("/waterboard/$name")!!.readAllBytes().toString(Charsets.UTF_8)).asJsonObject

    private fun view(v: Int): String = when {
        v >= 0 -> "w${v and 15}"
        v == WaterSim.AIR -> "air"
        else -> "solid"
    }

    /** A recorded run replayed: per tick, every cell written since the last send, in its state at the send. */
    private fun replay(fx: JsonObject): Pair<WaterSim, Map<Int, Map<Int, String>>> {
        val rows = fx.getAsJsonArray("rows").map { it.asString }
        val (geo, inn) = Geometry.parse(rows, fx.get("rotation").asString)
        val sim = WaterSim.fresh(geo, inn, 0, time = -6)
        val flips = HashMap<Int, Int>()
        for (f in fx.getAsJsonArray("flips")) {
            val a = f.asJsonArray
            flips.merge(a[0].asInt, 1 shl Geometry.LEVERS.indexOf(a[1].asString)) { x, y -> x xor y }
        }
        val sent = HashMap<Int, Map<Int, String>>()
        val dirty = LinkedHashSet<Int>()
        sim.onWrite = { c, _ -> dirty += c }
        sim.onSend = { sent[sim.time] = dirty.associateWith { view(sim.cells[it]) }; dirty.clear() }
        // the run's water-lever flip is at tick 0: start a little before it
        while (sim.time < -4) sim.step()
        val end = (fx.getAsJsonArray("obs").maxOf { it.asJsonArray[0].asInt }) + 30
        while (sim.time < end) sim.step(flips[sim.time + 1] ?: 0)
        return sim to sent
    }

    @Test fun matchesTheValidatedModelTickForTick() {
        for (name in listOf("bad137", "394ab1", "803622", "9c9239")) {
            val fx = json("replay-$name.json")
            val (sim, sent) = replay(fx)
            val geo = sim.geo
            val want = fx.getAsJsonObject("sent")
            var compared = 0
            for ((k, v) in want.entrySet()) {
                val t = k.toInt()
                val exp = v.asJsonArray.mapNotNull { e -> val a = e.asJsonArray; if (a[1].asInt >= 82) null else geo.cell(a[0].asInt, a[1].asInt) to a[2].asString }.toMap()
                val got = (sent[t] ?: emptyMap()).filterKeys { geo.y(it) < 82 }
                val gotMov = got.mapValues { (c, s) -> if (s == "solid" && exp[c] == "mov") "mov" else s }
                assertEquals(exp, gotMov, "$name tick $t")
                compared += exp.size
            }
            assertTrue(compared > 300, "$name: $compared sends compared")
        }
    }

    /** The recorded packets themselves, matched in order around their arrival time (the same check the model passed). */
    @Test fun explainsTheRecordedPackets() {
        for (name in listOf("bad137", "394ab1", "803622", "9c9239")) {
            val fx = json("replay-$name.json")
            val (sim, sent) = replay(fx)
            val geo = sim.geo
            val byKey = HashMap<Pair<Int, String>, MutableList<Int>>()
            for (t in sent.keys.sorted()) for ((c, s) in sent[t]!!) byKey.getOrPut(c to s) { ArrayList() } += t
            val used = HashSet<Pair<Int, Int>>()
            val obs = fx.getAsJsonArray("obs").map { it.asJsonArray }.filter { it[2].asInt < 82 }
                .map { Triple(it[0].asInt, geo.cell(it[1].asInt, it[2].asInt), it[3].asString.let { s -> if (s == "mov") "solid" else s }) }
            var hits = 0; var drift = 0.0; var streak = 0; var i = 0
            while (i < obs.size) {
                val (est, c, s) = obs[i]
                val e = est + Math.round(drift).toInt()
                val got = byKey[c to s]?.filter { it in e - 6..e + 6 && (it to c) !in used }?.minByOrNull { kotlin.math.abs(it - e) }
                if (got == null) {
                    // a server freeze holds packets back: after a run of misses, re-lock on the shift the next packets agree on
                    if (++streak >= 6) {
                        val hist = HashMap<Int, Int>()
                        for ((est2, c2, s2) in obs.subList(maxOf(0, i - 5), minOf(obs.size, i + 30)))
                            for (k in byKey[c2 to s2].orEmpty().map { it - est2 }.filter { it in -150..40 }.toSet()) hist.merge(k, 1, Int::plus)
                        val k = hist.maxByOrNull { it.value }?.key
                        if (k != null && kotlin.math.abs(k - drift) >= 2) { drift = k.toDouble(); streak = 0; i -= 5; continue }
                    }
                    i++; continue
                }
                used += got to c; hits++; streak = 0
                drift = 0.8 * drift + 0.2 * (got - est)
                i++
            }
            val total = obs.size
            assertTrue(hits >= total * 0.99, "$name: $hits of $total recorded packets")
        }
    }

    /** Odin's tables (read from Odin's own file) on fresh boards: the same solve tick and outlet hits as the validated model. */
    @Test fun theOneFlowTablesPlayOutAsTheModelSays() {
        val tab = OdinTable.load()
        assumeTrue(tab != null, "Odin's water-solutions.json is not in libs/odin")
        val layouts = json("layouts.json")
        val expected = json("table-expected.json")
        var cases = 0; var oneFlowSolved = 0
        for (c in OdinTable.cases(tab!!)) {
            val rows = layouts.getAsJsonArray(c.v.toString()).map { it.asString }
            val at = c.bits()
            for (rot in listOf("NORTH", "EAST", "SOUTH", "WEST")) {
                val (geo, inn) = Geometry.parse(rows, rot)
                val sim = WaterSim.fresh(geo, inn, c.closed, time = -41)
                sim.arrivals = IntArrayList()
                while (sim.time < -40) sim.step()
                while (sim.time < 600) sim.step(at[sim.time + 1] ?: 0)
                val name = "${c.opt}/${c.v}/${c.key}/$rot"
                val exp = expected.getAsJsonArray(name)
                val solved = if (exp[0].isJsonNull) null else exp[0].asInt
                assertEquals(solved, if (sim.solvedAt == WaterSim.NONE) null else sim.solvedAt, "$name solved")
                val arr = sim.arrivals!!
                val wrong = (0 until arr.size).count { c.closed and (1 shl (arr[it] and 7)) == 0 }
                assertEquals(exp[1].asInt, wrong, "$name arrivals at outlets that started open")
                cases++
                if (c.opt == "false" && solved != null) oneFlowSolved++
            }
        }
        assertEquals(320, cases)
        assertEquals(160, oneFlowSolved, "the hand-made one-flow table solves every board in every rotation")
    }

    /**
     * With the chest's hold, solved is the first time every gate is open and no water reaches an outlet (closing one again)
     * for the hold: worked out here from the outlet arrivals alone, on Odin's table plans. Odin's plans keep the gates open
     * well past [Planner.HOLD], so longer holds are tried too, for gates that do close again.
     */
    @Test fun aSolveCountsOnlyOnceEveryGateStaysOpen() {
        val tab = OdinTable.load()
        assumeTrue(tab != null, "Odin's water-solutions.json is not in libs/odin")
        val layouts = json("layouts.json")
        val end = 600
        val cases = OdinTable.cases(tab!!)
        assertEquals(80, cases.size)
        val reopened = HashMap<Int, Int>()
        for (hold in listOf(1, Planner.HOLD, 40, 120)) {
            var closedAgain = 0
            for (c in cases) {
                val (geo, inn) = Geometry.parse(layouts.getAsJsonArray(c.v.toString()).map { it.asString }, "NORTH")
                val sim = WaterSim.fresh(geo, inn, c.closed, time = -41)
                sim.hold = hold
                sim.arrivals = IntArrayList()
                val at = c.bits()
                while (sim.time < end) sim.step(at[sim.time + 1] ?: 0)
                val arr = sim.arrivals!!
                var closed = c.closed; var want: Int? = null; var firstOpen: Int? = null
                var i = 0
                while (i < arr.size) {
                    val t = arr[i] shr 3
                    while (i < arr.size && arr[i] shr 3 == t) { closed = closed xor (1 shl (arr[i] and 7)); i++ }
                    if (closed != 0) continue
                    if (firstOpen == null) firstOpen = t
                    val next = if (i < arr.size) arr[i] shr 3 else Int.MAX_VALUE
                    if (next > t + hold - 1 && t + hold - 1 <= end) { want = t; break }
                }
                if (firstOpen != null && firstOpen != want) closedAgain++
                assertEquals(want, if (sim.solvedAt == WaterSim.NONE) null else sim.solvedAt, "${c.opt}/${c.v}/${c.key} solved, held $hold")
            }
            reopened[hold] = closedAgain
        }
        assertEquals(0, reopened[1], "with no hold the first all-open is the solve")
        assertTrue(reopened[120]!! > 0, "some table plan closes a gate again within 120 ticks: $reopened")
        println("table plans whose gates were all open and closed again within the hold: $reopened")
    }
}
