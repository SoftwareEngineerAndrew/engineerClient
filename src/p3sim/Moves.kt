package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import com.google.gson.GsonBuilder
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import java.io.File
import java.util.Locale

/**
 * Your own recordings of the bots' moves: the bots play them (lined up with their jobs, sped up to the plan's
 * times) instead of the recorded-runs graph ([Routes]).
 *
 * 1. **The list fills itself**: every move a bot sets off on in a P3 ([seen]: who, section, from where to where) is
 *    listed, per skill, in the menu's Moves tab.
 * 2. **Record**: a move's button (or Record Missing, which goes through every unrecorded one) puts you on its start
 *    in a practice of its section, facing where it goes. Recording starts as you move and stops as you get there
 *    (within 1.2 blocks across, 1.5 up or down); the next missing one starts a second later. The Infinileap or the
 *    Restart keybind takes it again; Stop ends the chain.
 * 3. **Played**: a bot setting off within [FROM_NEAR] of a recording's start, to within [TO_NEAR] of its end, plays it
 *    (getting on and off it in a straight line), at the recording's own speed or as much faster as its job's time needs.
 *
 * Saved in `config/engineerclient/p3sim-moves.json`.
 */
object Moves {
    /** One move: [frames] are x, y, z, yaw, pitch a tick; [held] the item id each tick (null: nothing of note). */
    class Move(
        val skill: String, val who: String, val section: Int, val fromName: String, val toName: String,
        val from: List<Double>, val to: List<Double>,
        var frames: List<List<Double>>? = null, var held: List<String?>? = null, var recordedAt: Long = 0,
    ) {
        val id get() = "$skill|$section|$who|$fromName|$toName"
        val fromV get() = Vec3(from[0], from[1], from[2])
        val toV get() = Vec3(to[0], to[1], to[2])
        val recorded get() = (frames?.size ?: 0) >= 2
        val ticks get() = (frames?.size ?: 1) - 1
        val label get() = "$who: $fromName → $toName"
    }

    private class Saved(val moves: List<Move>? = null)

    private const val FROM_NEAR = 5.0
    private const val TO_NEAR = 2.0
    private val gson = GsonBuilder().create()
    private val file get() = File(Minecraft.getInstance().gameDirectory, "config/engineerclient/p3sim-moves.json")

    @Volatile private var all: MutableList<Move>? = null
    private fun moves(): MutableList<Move> = all ?: run {
        val l = runCatching { if (file.exists()) gson.fromJson(file.readText(), Saved::class.java)?.moves?.toMutableList() else null }
            .onFailure { EngineerClient.logger.warn("[p3sim] moves: {}", it.toString()) }.getOrNull() ?: mutableListOf()
        all = l
        l
    }

    private fun save() = EngineerClient.safely("p3sim moves save") {
        file.parentFile.mkdirs()
        file.writeText(gson.toJson(Saved(moves().toList())))
    }

    /** This skill's moves, in the order they come in a run (section, then as first seen). */
    fun list(skill: String = P3Plan.skillName()): List<Move> = moves().filter { it.skill == skill }

    // ------------------------------------------------------------------ the list

    /** What to call a place: the job stand, early-enter spot or teleport it's on, else its coordinates. */
    fun nameOf(p: Vec3): String {
        Party.STANDS.entries.minByOrNull { it.value.distanceTo(p) }?.takeIf { it.value.distanceTo(p) <= 2.5 }?.let { return it.key.removePrefix("S1 ").removePrefix("S2 ").removePrefix("S3 ").removePrefix("S4 ") }
        P3Plan.earlyEnters.map { P3Plan.eeSpot(it) }.minByOrNull { Vec3(it.x, it.y, it.z).distanceTo(p) }
            ?.takeIf { Vec3(it.x, it.y, it.z).distanceTo(p) <= 2.5 }?.let { return it.name }
        if (Vec3(36.0, 131.0, 139.0).distanceTo(p) <= 2.5) return "i2"
        if (Vec3(18.7, 121.5, 91.3).distanceTo(p) <= 2.5) return "high EE3"
        return String.format(Locale.ROOT, "%.0f %.0f %.0f", p.x, p.y, p.z)
    }

    /** A bot ([who]) sets off in [section] from [from] to [to]: listed if this skill hasn't got it yet. Server thread. */
    fun seen(who: String, section: Int, from: Vec3, to: Vec3) {
        if (section !in 1..4 || Practice.active || from.distanceTo(to) < 3.0) return
        val skill = P3Plan.skillName()
        val fromName = nameOf(from); val toName = nameOf(to)
        val l = moves()
        // The same move (a leap's landing differs a little run to run): once.
        if (l.any { it.skill == skill && it.who == who && it.section == section &&
                (it.fromName == fromName || it.fromV.distanceTo(from) <= 3.0) && (it.toName == toName || it.toV.distanceTo(to) <= 1.5) }) return
        l += Move(skill, who, section, fromName, toName, listOf(from.x, from.y, from.z), listOf(to.x, to.y, to.z))
        l.sortWith(compareBy({ it.skill }, { it.section }))
        save()
    }

    // ------------------------------------------------------------------ the bots

    /**
     * A bot at [from] going to [to]: your recording of it (this skill's first, any skill's else), as a route from
     * where the bot is, along the recording, to the spot. Null: none recorded.
     */
    fun route(from: Vec3, to: Vec3): Routes.Route? {
        val skill = P3Plan.skillName()
        val m = moves().filter { it.recorded && it.toV.distanceTo(to) <= TO_NEAR && it.fromV.distanceTo(from) <= FROM_NEAR }
            .minWithOrNull(compareBy({ if (it.skill == skill) 0 else 1 }, { it.fromV.distanceTo(from) + it.toV.distanceTo(to) })) ?: return null
        val f = m.frames ?: return null
        val k = f.size + 2
        val x = DoubleArray(k); val y = DoubleArray(k); val z = DoubleArray(k); val t = DoubleArray(k)
        val yaw = FloatArray(k) { Float.NaN }; val pitch = FloatArray(k) { Float.NaN }; val held = arrayOfNulls<String>(k)
        x[0] = from.x; y[0] = from.y; z[0] = from.z
        val on = Math.sqrt((f[0][0] - from.x).let { it * it } + (f[0][1] - from.y).let { it * it } + (f[0][2] - from.z).let { it * it }) / 0.9
        for ((i, fr) in f.withIndex()) {
            val q = i + 1
            x[q] = fr[0]; y[q] = fr[1]; z[q] = fr[2]; yaw[q] = fr[3].toFloat(); pitch[q] = fr[4].toFloat()
            held[q] = m.held?.getOrNull(i)
            t[q] = on + i
        }
        x[k - 1] = to.x; y[k - 1] = to.y; z[k - 1] = to.z
        t[k - 1] = t[k - 2] + Math.sqrt((to.x - x[k - 2]).let { it * it } + (to.y - y[k - 2]).let { it * it } + (to.z - z[k - 2]).let { it * it }) / 0.9
        return Routes.Route(x, y, z, t, yaw, pitch, held)
    }

    // ------------------------------------------------------------------ recording

    /** The move being recorded (null: none), and whether the rest of the missing ones follow. */
    @Volatile var recording: Move? = null
        private set
    @Volatile private var chain = false
    private var frames = ArrayList<List<Double>>()
    private var held = ArrayList<String?>()
    private var started = false
    private var waitTicks = 0

    /** Records [id] (again), or with null the first one not recorded yet; [chainOn]: then the next missing one, and so on. */
    fun record(id: String?, chainOn: Boolean) {
        val m = (if (id != null) moves().firstOrNull { it.id == id } else list().firstOrNull { !it.recorded })
        if (m == null) { Sim.note(if (list().isEmpty()) "No moves listed for §f${P3Plan.skillName()}§7 yet: run a P3 with the bots once and every move they make is listed." else "Every §f${P3Plan.skillName()}§7 move is recorded."); chain = false; return }
        chain = chainOn
        arm(m)
    }

    private fun arm(m: Move) {
        recording = m
        frames = ArrayList(); held = ArrayList(); started = false; waitTicks = 0
        // Facing where it goes.
        val d = m.toV.subtract(m.fromV)
        val yaw = Math.toDegrees(Math.atan2(-d.x, d.z)).toFloat()
        val jobs = P3Plan.jobsIn(m.section).filter { !it.startsWith("gate ") }
        Practice.startSetup(Practice.Setup("Move", m.section, Spots.Spot("Start", m.from[0], m.from[1], m.from[2], yaw, 0f), jobs, delay = 0.0))
        val left = list().count { !it.recorded }
        Sim.note("§eRecording§7 §f${m.label}§7 (S${m.section}): go when ready, it stops as you get there. ${if (left > 0) "$left left." else ""}")
    }

    /** A practice restarted (the Infinileap, the keybind): the same move again. */
    fun restarted() { recording?.let { frames = ArrayList(); held = ArrayList(); started = false; waitTicks = 0 } }

    /** Anything else started, or Stop: no more recording. */
    fun cancel() { if (recording != null) { recording = null; chain = false } }

    /** Every server tick of a practice (GoldorPhase): the host's position into the recording, and its end. */
    fun tick() {
        val m = recording ?: return
        if (waitTicks > 0) { if (--waitTicks == 0) next(); return }
        val p = Sim.host ?: return
        val pos = p.position()
        val to = m.toV
        // Where to go: a column of end rods on it, and how far and how long on the action bar.
        if ((frames.size + (if (started) 0 else 1)) % 3 == 0) for (dy in 0..4)
            Sim.level.sendParticles(p, net.minecraft.core.particles.ParticleTypes.END_ROD, true, true, to.x, to.y + 0.2 + dy * 0.5, to.z, 1, 0.0, 0.0, 0.0, 0.0)
        p.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            "§eRecording §f${m.toName}§7 · ${String.format(Locale.ROOT, "%.1f", pos.distanceTo(to))} blocks · " +
                if (started) "§f${String.format(Locale.ROOT, "%.2f", frames.size / 20.0)}s" else "§7go when ready"), true)
        if (!started) {
            if (pos.distanceTo(m.fromV) < 0.15) return
            started = true
        }
        frames += listOf(round(pos.x), round(pos.y), round(pos.z), round(p.yRot.toDouble()), round(p.xRot.toDouble()))
        held += SimItems.idOf(p.mainHandItem)
        val arrived = Math.hypot(pos.x - to.x, pos.z - to.z) <= 1.2 && Math.abs(pos.y - to.y) <= 1.5
        if (arrived) {
            m.frames = frames.toList(); m.held = held.toList(); m.recordedAt = System.currentTimeMillis()
            save()
            val left = list().count { !it.recorded }
            Sim.note("§aRecorded§7 §f${m.label}§7: §f${String.format(Locale.ROOT, "%.2f", m.ticks / 20.0)}s§7. ${if (left == 0) "§aEvery ${P3Plan.skillName()} move is recorded." else "$left left."}")
            if (chain && left > 0) waitTicks = 20 else recording = null
        } else if (frames.size > 30 * 20) {
            Sim.note("§cNot recorded§7 (over 30 s): the Infinileap or Restart takes it again.")
            frames = ArrayList(); held = ArrayList(); started = false
        }
    }

    private fun next() {
        val m = list().firstOrNull { !it.recorded }
        if (m == null) { recording = null; chain = false; return }
        arm(m)
    }

    /** Forgets [id]'s recording (the bots go back to the recorded runs there). */
    fun forget(id: String) { moves().firstOrNull { it.id == id }?.let { it.frames = null; it.held = null; save() } }

    /** Takes [id] off the list (a move the bots no longer make). */
    fun remove(id: String) { moves().removeAll { it.id == id }; save() }

    /** Clears this skill's list and recordings. */
    fun clearSkill() { moves().removeAll { it.skill == P3Plan.skillName() }; save() }

    private fun round(v: Double) = Math.round(v * 1000) / 1000.0
}
