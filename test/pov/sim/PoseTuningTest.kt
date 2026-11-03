package com.engineerclient.pov.sim

import com.engineerclient.pov.PoseTrack
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale

/**
 * Not a check: other [PoseTrack.Tuning]s side by side with the game's, on the networks where the position buffer matters,
 * into build/pov-sim/tuning.tsv. Only with POV_SIM_TUNING set.
 */
class PoseTuningTest {
    @Test fun compare() {
        assumeTrue(System.getenv("POV_SIM_TUNING") != null, "POV_SIM_TUNING isn't set")
        val variants = linkedMapOf(
            "game (rise 0.25, ease 0.97)" to PoseTrack.Tuning(),
            "before (rise 1, ease 0.99)" to PoseTrack.Tuning(rise = 1.0, ease = 0.99),
            "rise 1, ease 0.97" to PoseTrack.Tuning(rise = 1.0, ease = 0.97),
            "rise 1, ease 0.95" to PoseTrack.Tuning(rise = 1.0, ease = 0.95),
            "rise 0.5, ease 0.99" to PoseTrack.Tuning(rise = 0.5, ease = 0.99),
            "rise 0.1, ease 0.98" to PoseTrack.Tuning(rise = 0.1, ease = 0.98),
            "rise 1, ease 0.99, cap 80" to PoseTrack.Tuning(rise = 1.0, ease = 0.99, posBufferMaxNs = 80_000_000L),
            "cap 60, rise 0.25" to PoseTrack.Tuning(rise = 0.25, posBufferMaxNs = 60_000_000L),
        )
        val nets = listOf(Nets.TYPICAL, Nets.JITTERY, Nets.WIFI, Nets.MOBILE, Nets.BURST_LOSS, Nets.BLOAT, Nets.HORRIBLE, Nets.SATELLITE)
        val moves = listOf(Motions.STRAFE, Motions.CIRCLE, Motions.P3, Motions.JUMP)
        val out = StringBuilder("variant\tnetwork\tpos delay p50\tp95\tshape p95\tbehind p95\tjumps\tbacksteps\trewinds\tblank %\n")
        val totals = StringBuilder("\nvariant\tpos delay p50 (mean)\tp95 (mean)\tshape p95 (mean)\tjumps\tbacksteps\trewinds\n")
        for ((label, tuning) in variants) {
            val res = Scenarios.run(nets.flatMap { n -> moves.map { m -> Scenario("tuning", n, m, seconds = 60, tuning = tuning) } })
            for (n in nets) {
                val rs = res.map { it.first }.filter { it.scenario.net === n }
                fun avg(f: (Result) -> Double) = rs.map(f).filter { !it.isNaN() }.average()
                out.append(String.format(Locale.ROOT, "%s\t%s\t%.0f\t%.0f\t%.3f\t%.2f\t%d\t%d\t%d\t%.2f\n", label, n.name, avg { it.posDelay.p50 }, avg { it.posDelay.p95 },
                    avg { it.shapeErr.p95 }, avg { it.behind.p95 }, rs.sumOf { it.jumps }, rs.sumOf { it.backsteps }, rs.sumOf { it.posRewinds }, avg { 100.0 * it.blankFrames / it.frames }))
            }
            val rs = res.map { it.first }
            fun avg(f: (Result) -> Double) = rs.map(f).filter { !it.isNaN() }.average()
            totals.append(String.format(Locale.ROOT, "%s\t%.1f\t%.1f\t%.3f\t%d\t%d\t%d\n", label, avg { it.posDelay.p50 }, avg { it.posDelay.p95 }, avg { it.shapeErr.p95 },
                rs.sumOf { it.jumps }, rs.sumOf { it.backsteps }, rs.sumOf { it.posRewinds }))
        }
        File("build/pov-sim").mkdirs()
        File("build/pov-sim/tuning.tsv").writeText(out.toString() + totals)
        println(totals)
    }
}
