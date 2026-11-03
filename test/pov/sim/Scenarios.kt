package com.engineerclient.pov.sim

import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Every scenario the simulated POV Previews run through, and the report they write. */
object Scenarios {
    private val MOVES = listOf(Motions.WALK, Motions.STRAFE, Motions.FLICKS, Motions.CIRCLE, Motions.ETHERWARP, Motions.P3)

    /** Each network with each kind of movement, both games at 60 fps, the default settings. */
    val network = Nets.ALL.flatMap { n -> MOVES.map { m -> Scenario("network", n, m, seconds = if (m === Motions.P3) 60 else 30) } }

    /** The same networks with the relay alone (UDP blocked, or the relay has no TURN key). */
    val relayOnly = Nets.ALL.flatMap { n -> listOf(Motions.STRAFE, Motions.FLICKS, Motions.P3).map { m ->
        Scenario("relay only", n, m, Env(turn = false, label = "no TURN"), seconds = if (m === Motions.P3) 60 else 30) } }

    /** The games themselves on a typical network: frame rates, hitches, clocks and settings. */
    val games = listOf(
        Env(senderFps = 30.0, label = "sender 30 fps"),
        Env(senderFps = 144.0, label = "sender 144 fps"),
        Env(senderFps = 240.0, label = "sender 240 fps"),
        Env(senderFps = 400.0, frameJitter = 0.4, label = "sender 400 fps uneven"),
        Env(receiverFps = 30.0, label = "you at 30 fps"),
        Env(receiverFps = 144.0, label = "you at 144 fps"),
        Env(receiverFps = 240.0, label = "you at 240 fps"),
        Env(shareFps = 20, label = "Sharing FPS 20"),
        Env(shareFps = 30, label = "Sharing FPS 30"),
        Env(shareFps = 120, senderFps = 144.0, label = "Sharing FPS 120"),
        Env(shareFps = 240, senderFps = 240.0, label = "Sharing FPS 240"),
        Env(skipTiny = false, label = "skip tiny off"),
        Env(optimize = false, label = "optimize off"),
        Env(stutterEveryS = 3.0, stutterMs = 150.0, label = "sender hitches 150 ms"),
        Env(stutterEveryS = 2.0, stutterMs = 600.0, label = "sender hitches 600 ms"),
        Env(senderFps = 25.0, frameJitter = 0.5, label = "sender lagging 25 fps"),
        Env(recvStutterEveryS = 3.0, recvStutterMs = 150.0, label = "your hitches 150 ms"),
        Env(driftPpm = 500.0, label = "clock +500 ppm"),
        Env(driftPpm = -500.0, label = "clock -500 ppm"),
        Env(driftPpm = 3000.0, label = "clock +3000 ppm"),
        Env(driftPpm = -3000.0, label = "clock -3000 ppm"),
    ).flatMap { e -> listOf(Motions.P3, Motions.STRAFE, Motions.FLICKS).map { m -> Scenario("game", Nets.TYPICAL, m, e, seconds = if (m === Motions.P3) 60 else 30) } }

    /** TURN and the relay about as quick as each other, both jittery: the route choice mustn't flap. */
    val flap = listOf(0.0, 3.0, 8.0).flatMap { d ->
        listOf(Motions.P3, Motions.STRAFE).map { m ->
            Scenario("route flap", NetProfile("TURN ≈ relay +$d", 40.0, jitterMs = 15.0, noiseMs = 5.0), m, Env(label = "relay ${d.toInt()} ms slower"), seconds = 60,
                relay = NetProfile("relay", 40.0 + d, jitterMs = 15.0, noiseMs = 5.0, tcp = true))
        }
    }

    /** The relay clearly quicker (TURN's way round is long): Optimize server location must still take it, and stay. */
    val relayQuicker = listOf(Motions.P3, Motions.STRAFE).map { m ->
        Scenario("relay quicker", NetProfile("TURN 70 ms", 70.0, jitterMs = 10.0, noiseMs = 3.0), m, Env(label = "relay 40 ms"), seconds = 60,
            relay = NetProfile("relay", 40.0, jitterMs = 10.0, noiseMs = 3.0, tcp = true))
    }

    /** Ten minutes, for drift and wrap-arounds (the 16-bit ms wraps every 65 s). */
    val long = listOf(
        Scenario("long", Nets.TYPICAL, Motions.P3, Env(driftPpm = 300.0, label = "10 min, clock +300 ppm"), seconds = 600),
        Scenario("long", Nets.MOBILE, Motions.P3, Env(driftPpm = -300.0, label = "10 min, clock -300 ppm"), seconds = 600),
        Scenario("long", Nets.TYPICAL, Motions.P3, Env(turn = false, label = "10 min, relay only"), seconds = 600),
    )

    /** Stand still for 20 s: nothing should be drawn moving, and the sending slows to the still rate. */
    val idle = listOf(Nets.TYPICAL, Nets.HORRIBLE).map { Scenario("idle", it, Motions.STILL, seconds = 30) }

    /**
     * TURN alone drops out (a NAT that lost its mapping, Cloudflare's TURN having trouble) while the relay is fine: the view
     * should go over to the relay rather than wait TURN out.
     */
    val turnDrops = listOf(Motions.P3, Motions.STRAFE).map { m ->
        Scenario("TURN drops", Nets.OUTAGES.copy(name = "TURN outages"), m, seconds = 50, relay = Nets.TYPICAL.asRelay())
    }

    val all get() = network + relayOnly + games + flap + relayQuicker + long + idle + turnDrops

    /** Runs [list] on a few threads (each run is its own, nothing shared). */
    fun run(list: List<Scenario>, threads: Int = 6): List<Pair<Result, Long>> {
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val fs: List<Future<Pair<Result, Long>>> = list.map { sc -> pool.submit<Pair<Result, Long>> { val sim = PoseSim(sc); sim.run() to sim.relayDropped } }
            return fs.map { it.get() }
        } finally { pool.shutdown() }
    }

    private fun f(v: Double, d: Int = 0) = if (v.isNaN()) "-" else String.format(Locale.ROOT, "%.${d}f", v)

    val HEAD = listOf("group", "network", "movement", "setting", "pos delay p50", "p95", "max", "look delay p50", "p95", "behind p95 (blocks)",
        "shape err p95", "blank %", "longest blank ms", "longest freeze ms", "freeze ms/min", "outage cost ms", "first shown ms", "jumps", "backsteps", "pos rewinds", "look rewinds", "route switches", "on TURN %",
        "TURN up kB/s", "relay frames/s", "relay max/s", "relay dropped", "TURN lost %", "reordered dropped", "heard ns", "drawn ns", "sender ns/frame")

    fun row(r: Result, dropped: Long): List<String> {
        val s = r.scenario
        return listOf(s.group, s.net.name, s.motion.name, s.env.label, f(r.posDelay.p50), f(r.posDelay.p95), f(r.posDelay.max), f(r.lookDelay.p50), f(r.lookDelay.p95),
            f(r.behind.p95, 2), f(r.shapeErr.p95, 3), f(100.0 * r.blankFrames / maxOf(1, r.frames), 2), f(r.longestBlankMs), f(r.longestFreezeMs), f(r.freezeMsPerMin), r.outageCostMs.joinToString(" ") { f(it) }, f(r.firstShownMs), "${r.jumps}", "${r.backsteps}", "${r.posRewinds}", "${r.lookRewinds}",
            "${r.routeSwitches}", f(100 * r.turnShare), f(r.turnUpBytesPerS / 1000, 2), f(r.relayFramesPerS, 1), "${r.relayFramesMaxPerS}", "$dropped",
            f(if (r.turnFramesSent == 0L) 0.0 else 100.0 * r.turnFramesLost / r.turnFramesSent, 1), "${r.turnFramesReordered}",
            f(r.heardNs), f(r.drawnNs), f(r.sendNs))
    }

    /** build/pov-sim: summary.tsv, summary.md, and each run's seconds (series.tsv), for looking at over time. */
    fun write(results: List<Pair<Result, Long>>, dir: File = File("build/pov-sim")) {
        dir.mkdirs()
        File(dir, "summary.tsv").writeText((listOf(HEAD) + results.map { row(it.first, it.second) }).joinToString("\n") { it.joinToString("\t") } + "\n")
        File(dir, "summary.md").writeText(buildString {
            append("| ").append(HEAD.joinToString(" | ")).append(" |\n|").append(HEAD.joinToString("|") { "---" }).append("|\n")
            for ((r, d) in results) append("| ").append(row(r, d).joinToString(" | ")).append(" |\n")
        })
        File(dir, "series.tsv").writeText(buildString {
            append("scenario\tsecond\tpos delay ms\tlook delay ms\tbehind blocks\tblank frames\tframes\tbuffer ms\tpos buffer ms\tvia TURN\trelay frames\tTURN bytes\n")
            for ((r, _) in results) for (x in r.seconds) append(listOf(r.scenario.name, x.s, f(x.posDelayMs, 1), f(x.lookDelayMs, 1), f(x.behindBlocks, 3), x.blankFrames, x.frames,
                f(x.bufferMs, 1), f(x.posBufferMs, 1), if (x.viaTurn) 1 else 0, x.relayFrames, x.turnBytes).joinToString("\t")).append('\n')
        })
    }
}
