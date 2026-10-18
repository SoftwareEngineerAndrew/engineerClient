package com.engineerclient.p3sim.run

import com.engineerclient.p3sim.pack.Packs
import net.minecraft.server.MinecraftServer
import net.minecraft.util.Util
import org.slf4j.LoggerFactory
import java.lang.reflect.Field

/**
 * The lag layer on the sim server's tick: with a preset on, the server really stalls for a freeze and then really runs a catch-up burst,
 * so the player (and every mod on their game) sees what Hypixel's lag looks like: nothing for a moment, then the world rushing on, with
 * part of the lost time never coming back. It works in the boss practice as well as in a run.
 *
 * How, once per server tick (called from the end of the sim's tick, [SimServer]): [LagGenerator] says whether a freeze starts. If so the server
 * thread sleeps for it; the ticks the stall made the server fall behind by are then split by the model into the ones the burst recovers and
 * the ones that are forgotten for good (the server's next-tick time is moved forward over them, so vanilla does not run them all). The
 * recovered ones run at [burstRate] times the normal tick rate.
 *
 * Lag is wall-time only: `Run.t`, pack durations and everything scheduled on server ticks are untouched (they simply happen later).
 * Sub-100 ms tick jitter is not simulated. Server thread only.
 */
object LagRuntime {
    private val log = LoggerFactory.getLogger("engineerclient")

    /** The preset the player chose in the settings; a run's [RunConfig.lag] and a dev command's override take priority, in that order. */
    @Volatile var settingPreset: LagPreset = LagPreset.OFF
    @Volatile var runPreset: LagPreset = LagPreset.OFF
    @Volatile var devPreset: LagPreset? = null
    /** UNMEASURED catch-up pace (times the tick rate); the "Lag Burst Rate" setting writes it. */
    @Volatile var burstRate: Double = LagBurst.DEFAULT_RATE

    /** The preset in force now: the dev override, else the run's, else the settings'. */
    val effective: LagPreset get() = devPreset ?: runPreset.takeIf { it != LagPreset.OFF } ?: settingPreset

    /** What the ticks looked like (always recorded while a sim server runs; cheap). */
    val stats = LagStats()

    private var model: LagGenerator? = null
    private var modelFor: LagPreset? = null
    private var modelPack: Any? = null
    private var burstLeft = 0
    private var lastTickNs = 0L
    private var nextTickField: Field? = null
    private var fieldLooked = false

    /** Whether the server's next-tick time could be reached: without it freezes still happen but vanilla recovers every missed tick. */
    val canForget: Boolean get() { lookField(); return nextTickField != null }

    private fun lookField() {
        if (fieldLooked) return
        fieldLooked = true
        nextTickField = try { MinecraftServer::class.java.getDeclaredField("nextTickTimeNanos").apply { isAccessible = true } }
        catch (t: Throwable) { log.warn("[ads] the lag layer cannot reach the server's tick clock (${t.javaClass.simpleName}); lost ticks will be caught up in full"); null }
    }

    /** A new world: forget the model and the record. */
    fun reset() { model = null; modelFor = null; burstLeft = 0; lastTickNs = 0; stats.clear(); devPreset = null; runPreset = LagPreset.OFF }

    private fun modelFor(p: LagPreset): LagGenerator? {
        if (p == LagPreset.OFF) return null
        val pack = Packs.current?.runstate
        if (model != null && modelFor == p && modelPack === pack) return model
        val loss = LagPresets.lossPerMinute(p, pack?.lag?.tiers)
        model = LagGenerator(LagParams.fromPack(pack?.lag?.raw), loss)
        modelFor = p; modelPack = pack
        log.info("[ads] lag layer: ${p.label}, ${"%.1f".format(loss)} ticks lost a minute, ${"%.2f".format(model!!.freezesPerMinute)} freezes a minute")
        return model
    }

    /** Called at the end of every sim server tick. */
    fun afterTick(server: MinecraftServer) {
        val now = System.nanoTime()
        stats.record(now)
        val preset = effective
        val m = modelFor(preset)
        if (m == null) { burstLeft = 0; lastTickNs = now; return }
        // Burst: the recovered ticks run closer together than normal ones.
        if (burstLeft > 0) {
            burstLeft--
            val wait = LagBurst.sleepMs((now - lastTickNs) / 1e6, burstRate)
            if (wait >= 1.0) sleep(wait)
            lastTickNs = System.nanoTime()
            return
        }
        val ev = m.nextTick()
        if (ev == null) { lastTickNs = now; return }
        // Freeze: the gap is this tick's end to the next tick's start, so the stall is the gap less the tick the server would wait anyway.
        sleep(ev.gapMs - LagGenerator.TICK_MS)
        val behind = behindTicks(server)
        if (behind == null) { burstLeft = LagBurst.ticksFor(ev.recoveredTicks, burstRate); lastTickNs = System.nanoTime(); return }
        // The server fell [behind] ticks behind; the burst recovers [recoveredTicks] of them and the rest are forgotten.
        val keep = ev.recoveredTicks.coerceAtMost(behind)
        val forget = behind - keep
        // Never past now: the forgotten ticks only stop vanilla from running them, they must not make it wait.
        if (forget > 0) nextTickField?.let { f -> f.setLong(server, minOf(f.getLong(server) + forget * (LagGenerator.TICK_MS * 1e6).toLong(), Util.getNanos())) }
        burstLeft = LagBurst.ticksFor(keep, burstRate)
        lastTickNs = System.nanoTime()
    }

    /** Whole ticks the server is behind its own schedule right now, or null when the clock cannot be read. */
    private fun behindTicks(server: MinecraftServer): Int? {
        lookField()
        val f = nextTickField ?: return null
        // The server's own clock: its next-tick time is in Util.getNanos' frame, which is not System.nanoTime's (comparing the two once
        // pushed the next tick days ahead, and the server, waiting for it, never ticked or stopped again).
        val behindNs = Util.getNanos() - f.getLong(server)
        return if (behindNs <= 0) 0 else (behindNs / (LagGenerator.TICK_MS * 1e6)).toInt()
    }

    private fun sleep(ms: Double) {
        if (ms < 1.0) return
        try { Thread.sleep(ms.toLong(), ((ms - ms.toLong()) * 1e6).toInt()) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
    }
}
