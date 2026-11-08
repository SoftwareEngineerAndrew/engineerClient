package com.engineerclient.waterboard

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * The tracker fed recorded packet streams as they arrived (client clock): it has to find the server's ticks itself, place
 * the lever flips where the fitted replay put them, and keep explaining the packets, through a 2.4 s server freeze (03c3a4)
 * and heavy network jitter (0d963f).
 */
class TrackerTest {
    private fun json(name: String): JsonObject =
        JsonParser.parseString(javaClass.getResourceAsStream("/waterboard/$name")!!.readAllBytes().toString(Charsets.UTF_8)).asJsonObject

    private fun run(name: String): Triple<Tracker, List<Pair<Int, Int>>, List<Pair<Int, Int>>> {
        val fx = json("stream-$name.json")
        val (geo, inn) = Geometry.parse(fx.getAsJsonArray("rows").map { it.asString }, fx.get("rotation").asString)
        val packets = fx.getAsJsonArray("packets").map { it.asJsonArray }
        val t0 = packets.first()[0].asDouble
        val tr = Tracker(geo, WaterSim.fresh(geo, inn, 0, time = Math.floor(t0 / 50).toInt() - 1), 0.0)
        var lines = 0
        if (System.getenv("WB_TRACE") == name) tr.trace = { if (lines++ < 70) println("TRACE $it") }
        var clock = t0
        for (p in packets) {
            val ms = p[0].asDouble
            while (clock + 50 < ms) { clock += 50; tr.advance(clock) }
            when (p[1].asString) {
                "lever" -> tr.lever(ms, p[2].asInt)
                "cell" -> { val c = geo.cell(p[2].asInt, p[3].asInt); val v = p[4].asInt; tr.cell(ms, c, if (v >= 0) v else if (v == -1) WaterSim.AIR else WaterSim.SOLID) }
                "gate" -> {}
            }
        }
        tr.advance(clock + 1000)
        val fitted = fx.getAsJsonArray("fitted").map { it.asJsonArray[0].asInt to it.asJsonArray[1].asInt }
        return Triple(tr, tr.flips, fitted)
    }

    private fun check(name: String, minMatch: Double, tol: Int) {
        val (tr, got, want) = run(name)
        val rate = tr.matched.toDouble() / tr.observed
        println("$name: matched ${tr.matched}/${tr.observed} (${"%.1f".format(100 * rate)}%), relocks ${tr.relocks}, resyncs ${tr.resyncs}, drift ${"%.1f".format(tr.drift)}")
        println("   flips  ${got.map { "${Geometry.LEVERS[it.second]}@${it.first}" }}")
        println("   fitted ${want.map { "${Geometry.LEVERS[it.second]}@${it.first}" }}")
        assertTrue(rate >= minMatch, "$name: ${tr.matched} of ${tr.observed} packets placed")
        assertTrue(got.size == want.size, "$name: flips ${got.size} vs ${want.size}")
        for ((g, w) in got.zip(want)) assertTrue(g.second == w.second && kotlin.math.abs(g.first - w.first) <= tol, "$name: flip $g vs fitted $w")
    }

    @Test fun cleanRun() = check("803622", 0.95, 2)
    @Test fun throughAServerFreeze() = check("03c3a4", 0.85, 3)
    @Test fun throughJitter() = check("0d963f", 0.75, 6)
}
