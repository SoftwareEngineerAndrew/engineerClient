package com.engineerclient.pov.sim

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * POV Previews' sharing simulated over many networks, games and settings ([Scenarios]), each checked against what it
 * should manage there. Delays are behind what the teammate's own screen shows. The report lands in build/pov-sim
 * (summary.md, summary.tsv, series.tsv: every second of every run).
 */
class PoseSimTest {
    /** Networks that lose little and never stall: a view there is never blank. */
    private val clean = listOf(Nets.LAN, Nets.GOOD, Nets.TYPICAL, Nets.FAR, Nets.VERY_FAR, Nets.JITTERY, Nets.LOSSY_5, Nets.REORDER, Nets.ROUTE_CHANGE, Nets.SATELLITE)
    private val rough = listOf(Nets.WIFI, Nets.MOBILE, Nets.LOSSY_15, Nets.BURST_LOSS, Nets.BLOAT, Nets.HORRIBLE)

    private fun worstBase(n: NetProfile) = (listOf(n.baseMs) + n.steps.map { it.second }).max()

    @Test fun everyScenario() {
        val t0 = System.nanoTime()
        val results = Scenarios.run(Scenarios.all)
        Scenarios.write(results)
        println("pov-sim: ${results.size} scenarios in ${(System.nanoTime() - t0) / 1_000_000} ms -> ${File("build/pov-sim").absolutePath}")
        val bad = ArrayList<String>()
        fun check(ok: Boolean, r: Result, what: () -> String) { if (!ok) bad += "${r.scenario.name}: ${what()}" }
        val byName = results.associateBy { it.first.scenario.name }

        for ((r, dropped) in results) {
            val sc = r.scenario; val n = sc.net; val e = sc.env
            // Everywhere: something drawn; within the relay's allowance; the bandwidth Sharing FPS implies; never replaying their past.
            check(r.frames > 0 && !r.firstShownMs.isNaN() || sc.motion === Motions.STILL, r) { "nothing drawn" }
            check(r.relayFramesMaxPerS <= 100 && dropped == 0L, r) { "relay allowance: ${r.relayFramesMaxPerS}/s, $dropped dropped" }
            check(r.turnUpBytesPerS <= e.shareFps * 60.0 + 2500, r) { "TURN up ${r.turnUpBytesPerS} B/s" }
            check(r.posRewinds <= 12 && r.lookRewinds <= 3, r) { "rewinds ${r.posRewinds}/${r.lookRewinds}" }
            val jitter = 2.5 * n.jitterMs + n.noiseMs
            // Over TURN, where they are is drawn about as soon as where they look: the position buffer doesn't sit at the worst
            // tick lately (it once did, 50-60 ms further behind on 4G and Wi-Fi).
            if (sc.group == "network" && sc.motion in listOf(Motions.WALK, Motions.STRAFE, Motions.CIRCLE, Motions.P3))
                check(!(r.posDelay.p50 > r.lookDelay.p50 + 35), r) { "position ${r.posDelay.p50} ms behind, look ${r.lookDelay.p50}" }
            when (sc.group) {
                "network" -> if (n in clean) {
                    check(r.blankFrames == 0, r) { "blank ${r.blankFrames} frames" }
                    check(!(r.posDelay.p50 > worstBase(n) + jitter + 70), r) { "position ${r.posDelay.p50} ms behind" }
                    check(!(r.lookDelay.p50 > worstBase(n) + jitter + 60), r) { "look ${r.lookDelay.p50} ms behind" }
                    check(r.jumps <= 3 && r.backsteps <= 15, r) { "jumps ${r.jumps}, backsteps ${r.backsteps}" }
                } else if (n in rough) {
                    check(100.0 * r.blankFrames / r.frames <= 10 && r.longestBlankMs <= 1500, r) { "blank ${100.0 * r.blankFrames / r.frames}%, longest ${r.longestBlankMs} ms" }
                    check(!(r.posDelay.p50 > n.baseMs + 3 * jitter + 120), r) { "position ${r.posDelay.p50} ms behind" }
                } else if (n === Nets.OUTAGES) {
                    // Each outage costs no more than itself: drawn again within a second of the network coming back.
                    check(r.recoveryMs.all { it <= 1000 }, r) { "recovery ${r.recoveryMs}" }
                    check(r.longestBlankMs <= 8000 + 1000, r) { "longest blank ${r.longestBlankMs}" }
                }
                "relay only" -> {
                    val rn = sc.relay
                    if (n in listOf(Nets.LAN, Nets.GOOD, Nets.TYPICAL, Nets.JITTERY, Nets.REORDER)) {
                        check(r.blankFrames == 0, r) { "blank ${r.blankFrames} frames" }
                        check(!(r.posDelay.p50 > rn.baseMs + jitter + 60), r) { "position ${r.posDelay.p50} ms behind" }
                        check(!(r.lookDelay.p50 > rn.baseMs + jitter + 110), r) { "look ${r.lookDelay.p50} ms behind" }
                    }
                    // TCP's resends back off (200 ms, doubling), so after a long outage the relay takes a few seconds more.
                    if (n === Nets.OUTAGES) check(r.recoveryMs.all { it <= 8000 }, r) { "recovery ${r.recoveryMs}" }
                }
                "game" -> {
                    val hitches = e.stutterMs >= 600
                    check(hitches || r.blankFrames == 0, r) { "blank ${r.blankFrames} frames" }
                    check(!(r.posDelay.p50 > 110) && !(r.lookDelay.p50 > 110), r) { "${r.posDelay.p50} / ${r.lookDelay.p50} ms behind" }
                    // A clock running apart costs nothing: within 15 ms of the same run without.
                    if (e.driftPpm != 0.0) byName["network · typical · ${sc.motion.name}"]?.first?.let { base ->
                        check(abs(r.posDelay.p50.orZero() - base.posDelay.p50.orZero()) <= 15 && abs(r.lookDelay.p50 - base.lookDelay.p50) <= 15, r) {
                            "${r.posDelay.p50} / ${r.lookDelay.p50} ms vs ${base.posDelay.p50} / ${base.lookDelay.p50} without the drift" }
                    }
                }
                "route flap" -> check(r.routeSwitches <= 3, r) { "${r.routeSwitches} route switches" }
                "long" -> {
                    // Ten minutes in, no worse than one minute in.
                    fun med(from: Int, to: Int) = r.seconds.filter { it.s in from until to }.map { it.posDelayMs }.filter { !it.isNaN() }.sorted().let { it[it.size / 2] }
                    check(abs(med(540, 600) - med(60, 120)) <= 20, r) { "minute 10: ${med(540, 600)} ms, minute 1: ${med(60, 120)} ms" }
                    if (n === Nets.TYPICAL) check(100.0 * r.blankFrames / r.frames <= 0.1, r) { "blank ${r.blankFrames} frames" }
                }
                "idle" -> check(r.jumps == 0 && (n !== Nets.TYPICAL || r.blankFrames == 0), r) { "idle: jumps ${r.jumps}, blank ${r.blankFrames}" }
            }
        }

        // More Sharing FPS, less look delay.
        fun look(label: String) = byName["game · typical · strafe · $label"]!!.first.lookDelay.p50
        val l60 = byName["network · typical · strafe"]!!.first.lookDelay.p50
        if (!(look("Sharing FPS 240") < l60 && l60 < look("Sharing FPS 20"))) bad += "Sharing FPS: 240 -> ${look("Sharing FPS 240")}, 60 -> $l60, 20 -> ${look("Sharing FPS 20")} ms"

        File("build/pov-sim/failures.txt").writeText(bad.joinToString("\n") + "\n")
        assertTrue(bad.isEmpty(), "${bad.size} expectations missed (build/pov-sim/failures.txt):\n" + bad.joinToString("\n"))
    }

    private fun Double.orZero() = if (isNaN()) 0.0 else this
}
