package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import net.minecraft.client.Minecraft
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * One run being recorded, tick by tick, into a [GhostRun] (no Minecraft in here: the tests build
 * runs with it). Frames are kept per n from 0; a tick missed repeats the one before.
 */
class GhostRecording(val clazz: String, val skill: String) {
    val frames = ArrayList<GhostRun.Frame>()
    val events = ArrayList<GhostRun.Ev>()
    val sectionN = IntArray(6) { -1 }.also { it[1] = 0 }

    fun frame(n: Int, f: GhostRun.Frame) {
        if (n < 0) return
        while (frames.size < n) frames += frames.lastOrNull() ?: f
        if (frames.size == n) frames += f else frames[n] = f
    }

    fun event(n: Int, type: String, a: String = "", k: Int = 0) { if (n >= 0) events += GhostRun.Ev(n, type, a, k) }

    fun section(s: Int, n: Int) { if (s in 1..5 && sectionN[s] < 0) sectionN[s] = n }

    /** The run, its time the core's opening; null without one (it never got there, nothing recorded). */
    fun finish(date: Long = System.currentTimeMillis()): GhostRun? {
        val time = sectionN[5]
        if (time < 0 || frames.isEmpty() || time >= frames.size) return null
        val end = frames.size - 1
        val evs = events.filter { it.n in 0..end }.map { e ->
            // A leap's tick is where you landed: the teleport may show a tick or two after the click.
            if (e.type != "leap") e else {
                val m = (e.n..minOf(e.n + 2, end)).filter { it > 0 }.maxByOrNull { frames[it].p.dist(frames[it - 1].p) }
                if (m != null && frames[m].p.dist(frames[m - 1].p) > LEAP_JUMP) e.copy(n = m) else e
            }
        }.withIndex().sortedWith(compareBy({ it.value.n }, { it.index })).map { it.value }
        return GhostRun(clazz, skill, time, frames.toList(), evs, sectionN.copyOf(), date)
    }

    companion object {
        /** A move this far in a tick is a teleport. */
        const val LEAP_JUMP = 4.0
    }
}

/** Your best run per skill preset and class: `config/engineerclient/p3sim-best/<skill>/<CLASS>.json.gz`. */
object GhostStore {
    private val cache = HashMap<String, GhostRun?>()
    var root: File? = null
    private val dir get() = root ?: File(Minecraft.getInstance().gameDirectory, "config/engineerclient/p3sim-best")

    private fun file(skill: String, clazz: String) = File(File(dir, skill.replace(Regex("[^A-Za-z0-9 _-]"), "_")), "$clazz.json.gz")

    @Synchronized fun best(skill: String, clazz: String): GhostRun? = cache.getOrPut("$skill/$clazz") {
        // The file stream gets its own use: GZIPInputStream's constructor throws on a broken file, and
        // a stream left open keeps Windows from replacing that file in offer().
        runCatching { file(skill, clazz).takeIf { it.exists() }?.let { f -> f.inputStream().use { raw -> GZIPInputStream(raw).use { GhostRun.fromJson(it.readBytes().decodeToString()) } } } }.getOrNull()
    }

    /** [new] beats [old]: a faster core (a tie: the newer). */
    fun better(new: GhostRun, old: GhostRun?) = old == null || new.time <= old.time

    /** Keeps [run] if it's your best for its skill and class; the best before (null: none). */
    @Synchronized fun offer(run: GhostRun): Pair<Boolean, GhostRun?> {
        val old = best(run.skill, run.clazz)
        if (!better(run, old)) return false to old
        val f = file(run.skill, run.clazz)
        f.parentFile.mkdirs()
        val tmp = File(f.path + ".tmp")
        GZIPOutputStream(tmp.outputStream()).use { it.write(run.toJson().encodeToByteArray()) }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        cache["${run.skill}/${run.clazz}"] = run
        return true to old
    }

    @Synchronized fun forget(skill: String, clazz: String) { file(skill, clazz).delete(); cache.remove("$skill/$clazz") }

    @Synchronized fun clearCache() = cache.clear()
}

/**
 * Records your P3 runs (from Goldor's first line, S1 starts only) for [GhostStore]: where you are
 * every tick and what you do, and what the party does that you'd wait for (early enterers on their
 * spots, leaps onto you). Kept once the core is open and you've been in it a while (or 20 s after).
 */
object GhostCapture {
    private var rec: GhostRecording? = null
    private var phase: GoldorPhase? = null
    private var wasSwinging = false
    private var inCoreFor = 0

    fun start(p: GoldorPhase) {
        rec = null
        phase = null
        if (p.from != 1) return
        val c = P3Sim.myClass ?: return
        rec = GhostRecording(c.name, P3Plan.skillName())
        phase = p
        inCoreFor = 0
    }

    /** The run ended before the core (a restart, Stop...): nothing kept. */
    fun drop() { rec = null; phase = null }

    /** Something no real run has ([why]: a menu teleport, creative...): this run can't be a best. */
    fun invalidate(why: String) {
        if (rec == null) return
        drop()
        Sim.note("§7This run won't count as a best ($why).")
    }

    /** P3 ended (P4, Stop, a restart): kept if the core was open, else dropped. */
    fun stopped(p: GoldorPhase) {
        if (p !== phase) return
        if (p.section >= 5) keep() else drop()
    }

    fun event(type: String, a: String = "", k: Int = 0) {
        val p = phase ?: return
        rec?.event(p.n, type, a, k)
    }

    /** End of each P3 tick: your frame, the section, the end. */
    fun tick(p: GoldorPhase) {
        val r = rec ?: return
        if (p !== phase) return
        val player = Sim.player ?: return
        if (player.isCreative || player.isSpectator || player.abilities.flying) { invalidate("creative, spectator or flying"); return }
        val n = p.n
        r.section(p.section, n)
        val pos = player.position()
        r.frame(n, GhostRun.Frame(P(pos.x, pos.y, pos.z), player.yRot, player.xRot, heldKey(SimItems.idOf(player.mainHandItem))))
        if (player.isSwinging && !wasSwinging) r.event(n, "swing")
        wasSwinging = player.isSwinging
        if (p.section >= 5) {
            if (GoldorPhase.CORE_BOX.contains(pos)) inCoreFor++
            if (inCoreFor >= KEEP_IN_CORE || n - r.sectionN[5] >= KEEP_MAX) keep()
        }
    }

    private fun keep() {
        val r = rec ?: return
        rec = null
        phase = null
        val run = r.finish() ?: return
        EngineerClient.safely("p3sim ghost save") {
            val (saved, old) = GhostStore.offer(run)
            val label = Roles.label(Party.CLASSES.first { it.name == run.clazz })
            if (saved) Sim.note("§dNew best as $label§7 (${run.skill}): §f${secs(run.time)}§7${old?.let { " (was ${secs(it.time)})" } ?: ""}. The $label bot can play it (menu > Plan).")
        }
    }

    private fun secs(n: Int) = "%.2fs".format(java.util.Locale.ROOT, n / 20.0)

    /** What you hold: the sim item's id (a bot shows it, [Party.setHeld]), "" for anything else. */
    fun heldKey(id: String?): String = id?.takeIf { it != "SKYBLOCK_MENU" } ?: ""

    /** In the core this long: done (the ghost stands there after). */
    private const val KEEP_IN_CORE = 40
    private const val KEEP_MAX = 400
}
