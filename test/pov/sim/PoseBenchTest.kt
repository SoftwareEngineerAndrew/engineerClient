package com.engineerclient.pov.sim

import com.engineerclient.pov.PoseTrack
import com.engineerclient.pov.PoseWire
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * What sharing costs the game, per call, once warmed up: drawing a teammate ([PoseTrack.drawn], up to 8 a frame: 4 views
 * and their bodies), hearing a pose ([PoseTrack.heard], ~80 a second per teammate at 60 Sharing FPS), and sending yours
 * ([PoseWire.Sender], a tick 20 times a second and a look every frame). Limits are far above what they take (so a slow CI
 * box passes) and far below what a frame can spare (16 ms at 60 fps).
 */
class PoseBenchTest {
    private fun timePer(n: Int, warm: Int, f: (Int) -> Unit): Double {
        repeat(warm) { f(it) }
        var best = Double.MAX_VALUE
        repeat(5) { round ->
            val t0 = System.nanoTime()
            for (i in 0 until n) f(round * n + i)
            best = minOf(best, (System.nanoTime() - t0).toDouble() / n)
        }
        return best
    }

    /** A track fed as TURN feeds it: a tick every 50 ms and a look every 16.7 ms, 10 s of it. */
    private fun fedTrack(): Pair<PoseTrack, Long> {
        val t = PoseTrack()
        var now = 1_000_000_000_000L
        var ms = 12345L
        for (i in 0 until 600) {
            now += 16_666_666L; ms += 17
            t.heard(null, 1, now + (i % 7) * 300_000L, true, true, ms and 0xFFFF, if (i % 3 == 0) net.minecraft.world.phys.Vec3(i * 0.1, 70.0, 0.0) else null, i * 0.5f, 0f, 0, 1, 20)
        }
        return t to now
    }

    @Test fun costs() {
        val (track, now) = fedTrack()
        var sink = 0.0
        val drawn = timePer(200_000, 300_000) { i -> sink += track.drawn(now + (i % 50) * 100_000L)?.pos?.x ?: 0.0 }

        var hNow = now; var hMs = 30000L
        val heard = timePer(200_000, 300_000) { i ->
            hNow += 16_666_666L; hMs += 17
            track.heard(null, 1, hNow, true, true, hMs and 0xFFFF, if (i % 3 == 0) net.minecraft.world.phys.Vec3(i * 0.01, 70.0, 0.0) else null, i * 0.3f, 0f, 0, 1, 20)
        }

        val s = PoseWire.Sender()
        var sNow = 0L
        val tick = timePer(200_000, 300_000) { i -> sNow += 50_000_000L; s.tick(sNow, i and 0xFFFF, i * 0.2, 70.0, 0.0, i * 1f, 0f, 0, 1)?.let { sink += it.size } }
        val look = timePer(200_000, 300_000) { i -> sNow += 16_666_666L; s.look(sNow, i and 0xFFFF, i * 0.7f, 10f, 16_666_666L, true)?.let { sink += it.size } }
        val relay = timePer(200_000, 300_000) { i -> sNow += 50_000_000L
            val f = PoseWire.relay(1, -1, 0, i, i * 0.2, 70.0, 0.0, i * 1f, 0f, 0, 40.0, 1)
            sink += s.relayFrames(f, sNow, true).size }
        val tickBody = PoseWire.tickBody(1, 1.0, 2.0, 3.0, 4f, 5f, 0, 7)
        val read = timePer(500_000, 500_000) { _ -> sink += PoseWire.readTick(tickBody)?.yaw ?: 0f }

        val lines = listOf("drawn" to drawn, "heard" to heard, "sender tick" to tick, "sender look" to look, "sender relay" to relay, "read tick" to read)
        // Per second of a typical P3 with 4 teammates sharing at 60: 4 views x 2 draws x 60 fps, 4 x 80 heard, 20 ticks, 60 looks.
        val perSecondUs = (8 * 60 * drawn + 4 * 80 * (heard + read) + 20 * (tick + relay) + 60 * look) / 1000
        File("build/pov-sim").mkdirs()
        File("build/pov-sim/bench.txt").writeText(lines.joinToString("\n") { "%-14s %8.0f ns".format(it.first, it.second) } +
            "\nper second, 4 teammates at 60: %.0f µs (%.3f%% of one core)\n".format(perSecondUs, perSecondUs / 10_000) + "(sink ${sink.toLong() % 10})\n")
        println(File("build/pov-sim/bench.txt").readText())
        assertTrue(drawn < 20_000, "drawn: $drawn ns")
        assertTrue(heard < 20_000, "heard: $heard ns")
        assertTrue(tick < 20_000 && look < 20_000 && relay < 20_000, "sender: $tick / $look / $relay ns")
        assertTrue(perSecondUs < 5_000, "per second: $perSecondUs µs")
    }
}
