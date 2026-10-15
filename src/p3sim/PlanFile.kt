package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import java.io.File
import kotlin.math.roundToInt

/**
 * A skill's P3 written out step by step, in a text file you edit (`config/engineerclient/p3sim-plans/<skill>.txt`;
 * the shipped ones are written there the first time): for each class, what it does and when, in order.
 *
 * It is the skill's plan ([Roles.plan]: your jobs, who does the rest, the early enters) and the bots play it exactly
 * ([ghostRuns]: each bot's whole P3 worked out tick by tick before it starts, played as a ghost [GhostPlayer]). Every
 * move between two steps is the one fixed way ([Moves.route]: your recording, else built in, else the recorded runs),
 * leaving as late as it can and sped up only where the plan's times are tighter than it. Live, only what involves you
 * moves things: a section starting later than planned, a leap onto you waiting until you're on your spot.
 *
 * Read again on every start (and whenever it changes).
 */
object PlanFile {
    /** One step: at [sec] s into [section] (5: the core open), a job ([arg] its id; [spot] its stand, null: from where it is), on a spot ("at" / "ee" into [into]), or a leap onto [arg] (a class). */
    class Step(val section: Int, val sec: Double, val kind: String, val arg: String, val spot: Vec3?, val into: Int, val line: Int)

    class Script(
        val name: String,
        val starts: Map<DungeonClass, Vec3>,
        val steps: Map<DungeonClass, List<Step>>,
        val plan: Roles.Plan,
        /** n each section starts if everyone's on time (1-5). */
        val sectionN: IntArray,
        val errors: List<String>,
    )

    /** Skills that ship with a plan file. */
    private val SHIPPED = setOf("Theoretical")

    private val dir get() = File(Minecraft.getInstance().gameDirectory, "config/engineerclient/p3sim-plans")
    fun file(name: String) = File(dir, "$name.txt")

    private class Cached(val modified: Long, val script: Script?, val checked: Long)
    private val cache = HashMap<String, Cached>()
    /** Changes whenever a plan file is (re)read: what depends on the plan starts again from it. */
    @Volatile var version = 0
        private set

    /** [name]'s plan file, read (null: none, or it doesn't read). Checked for changes at most once a second. */
    fun of(name: String): Script? = synchronized(this) {
        val now = System.currentTimeMillis()
        val c = cache[name]
        if (c != null && now - c.checked < 1000) return c.script
        val f = file(name)
        if (!f.exists() && name in SHIPPED) runCatching {
            f.parentFile.mkdirs()
            PlanFile::class.java.getResourceAsStream("/assets/engineerclient/p3sim/plans/$name.txt")?.use { f.writeBytes(it.readBytes()) }
        }.onFailure { EngineerClient.logger.warn("[p3sim] plan file {}: {}", name, it.toString()) }
        val m = if (f.exists()) f.lastModified() else -1L
        if (c != null && c.modified == m) { cache[name] = Cached(m, c.script, now); return c.script }
        val s = if (m < 0) null else runCatching { parse(name, f.readText()) }
            .onFailure { EngineerClient.logger.warn("[p3sim] plan file {}: {}", name, it.toString()) }.getOrNull()
        s?.errors?.forEach { EngineerClient.logger.warn("[p3sim] plan file {}: {}", name, it) }
        cache[name] = Cached(m, s, now)
        version++
        return s
    }

    /** Opens [name]'s plan file in your editor (written out first if it's a shipped one). */
    fun open(name: String) {
        of(name)
        val f = file(name)
        if (!f.exists()) return
        val os = System.getProperty("os.name").lowercase()
        val cmd = when {
            os.contains("win") -> listOf("cmd", "/c", "start", "", f.absolutePath)
            os.contains("mac") -> listOf("open", f.absolutePath)
            else -> listOf("xdg-open", f.absolutePath)
        }
        runCatching { ProcessBuilder(cmd).start() }.onFailure { EngineerClient.logger.warn("[p3sim] open plan file: {}", it.toString()) }
    }

    // ------------------------------------------------------------------ reading

    /** Spots by name (besides the job stands). */
    private val SPOTS = mapOf(
        "i2" to Vec3(35.99, 131.0, 138.96),
        "lights" to Vec3(60.5, 132.0, 139.0),
        "high ee3" to Vec3(18.7, 121.5, 91.3), "cee3" to Vec3(18.7, 121.5, 91.3),
        "ee3" to Vec3(2.0, 109.0, 102.0), "low ee3" to Vec3(2.0, 109.0, 102.0),
        "core" to Vec3(54.5, 115.06, 50.5), "core front" to Vec3(54.5, 115.06, 50.5), "ee4" to Vec3(54.5, 115.06, 50.5),
        "recore" to Vec3(52.5, 115.0, 57.6),
    )

    private fun classOf(s: String): DungeonClass? = when (s.lowercase()) {
        "mage" -> DungeonClass.MAGE
        "archer", "arch" -> DungeonClass.ARCHER
        "healer" -> DungeonClass.HEALER
        "tank" -> DungeonClass.TANK
        "berserk", "berserker", "bers" -> DungeonClass.BERSERK
        else -> null
    }

    /** A job as written, in section [s] -> its id ("S2 T4", "S3 west lever", "S2 Lights", "gate 1"); null: not a job. */
    fun jobOf(text: String, s: Int): String? {
        val t = text.trim().lowercase().replace(Regex("\\s+"), " ")
        Regex("^(?:s([1-4]) )?t([1-5])$").find(t)?.let { m ->
            val sec = m.groupValues[1].toIntOrNull() ?: s
            return "S$sec T${m.groupValues[2]}".takeIf { it in Party.STANDS }
        }
        when (t) {
            "ll", "left lever" -> return Roles.LEFT[s]
            "rl", "right lever" -> return Roles.RIGHT[s]
            "ss", "simon says", "i1" -> return "S1 SS"
            "lights", "i2" -> return "S2 Lights"
            "arrows", "i3" -> return "S3 Arrows"
            "i4", "target" -> return "S4 Target"
            "gate" -> return if (s <= 3) "gate $s" else null
        }
        Regex("^gate ([1-3])$").find(t)?.let { return "gate ${it.groupValues[1]}" }
        val full = Regex("^s([1-4]) (.+)$").find(t)
        val (sec, rest) = if (full != null) full.groupValues[1].toInt() to full.groupValues[2] else s to t
        return Party.STANDS.keys.firstOrNull { it.equals("S$sec $rest", ignoreCase = true) }
    }

    /** A spot as written (a name, a job's stand, or "x y z") in section [s]. */
    private fun spotOf(text: String, s: Int, preset: Roles.Preset?): Vec3? {
        val t = text.trim().lowercase().replace(Regex("\\s+"), " ")
        val nums = t.split(" ").mapNotNull { it.toDoubleOrNull() }
        if (nums.size == 3 && t.split(" ").size == 3) return Vec3(nums[0], nums[1], nums[2])
        SPOTS[t]?.let { return it }
        return jobOf(t, s)?.let { standOf(it, preset) }
    }

    /** Where [job] is done from (null: a gate, from wherever it is). */
    fun standOf(job: String, preset: Roles.Preset?): Vec3? {
        if (job.startsWith("gate")) return null
        preset?.defaults?.get("stand/$job")?.let { return Vec3(it.x, it.y, it.z) }
        return Party.STANDS[job]
    }

    fun parse(name: String, text: String): Script {
        val preset = Roles.PRESETS.firstOrNull { it.name == name }
        val errors = ArrayList<String>()
        val starts = LinkedHashMap<DungeonClass, Vec3>()
        val steps = LinkedHashMap<DungeonClass, MutableList<Step>>()
        val ee = HashMap<Int, DungeonClass>()
        var cur: DungeonClass? = null
        for ((i, raw) in text.lines().withIndex()) {
            val ln = i + 1
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                cur = classOf(line.drop(1).dropLast(1).trim())
                if (cur == null) errors += "line $ln: no class called ${line}"
                else steps.getOrPut(cur) { ArrayList() }
                continue
            }
            val c = cur ?: run { errors += "line $ln: before any [Class]"; null } ?: continue
            val t = line.split(Regex("\\s+"))
            if (t[0].equals("start", true)) {
                spotOf(t.drop(1).joinToString(" "), 1, preset)?.let { starts[c] = it } ?: run { errors += "line $ln: no spot \"${t.drop(1).joinToString(" ")}\"" }
                continue
            }
            val s = t[0].uppercase().removePrefix("S").toIntOrNull()?.takeIf { it in 1..5 }
            val sec = t.getOrNull(1)?.toDoubleOrNull()
            val rest = t.drop(2)
            if (s == null || sec == null || rest.isEmpty()) { errors += "line $ln: should be <section> <seconds> <what> (like \"S2 1.8 T2\")"; continue }
            val word = rest[0].lowercase()
            val list = steps.getValue(c)
            when {
                word == "leap" -> {
                    val to = rest.getOrNull(1)?.let(::classOf)
                    if (to == null || to == c) errors += "line $ln: leap onto whom? (Mage, Archer, Healer, Tank, Berserk)"
                    else list += Step(s, sec, "leap", to.name, null, 0, ln)
                }
                word == "at" || Regex("^ee[2-5]$").matches(word) -> {
                    val into = if (word == "at") 0 else word.drop(2).toInt()
                    val spot = spotOf(rest.drop(1).joinToString(" "), s, preset)
                    if (spot == null) errors += "line $ln: no spot \"${rest.drop(1).joinToString(" ")}\""
                    else {
                        list += Step(s, sec, if (into > 0) "ee" else "at", rest.drop(1).joinToString(" "), spot, into, ln)
                        if (into > 0) ee[into]?.takeIf { it != c }?.let { errors += "line $ln: ee$into is ${Roles.label(it)}'s already" } ?: run { ee[into] = c }
                    }
                }
                else -> {
                    val job = jobOf(rest.joinToString(" "), s)
                    if (job == null) errors += "line $ln: no job \"${rest.joinToString(" ")}\" in S$s"
                    else list += Step(s, sec, "job", job, standOf(job, preset), 0, ln)
                }
            }
        }
        // The plan: who does what, when.
        val owners = LinkedHashMap<String, List<DungeonClass>>()
        val jobsOf = LinkedHashMap<DungeonClass, List<String>>()
        val times = HashMap<String, Pair<Int, Double>>()
        for ((c, l) in steps) {
            val js = l.filter { it.kind == "job" }
            jobsOf[c] = js.map { it.arg }
            for (st in js) {
                owners[st.arg]?.let { errors += "line ${st.line}: ${st.arg} is ${Roles.label(it.first())}'s already" }
                owners[st.arg] = listOf(c)
                times[st.arg] = st.section to st.sec
                val own = GhostPlayer.sectionOf(st.arg)
                if (st.section > own) errors += "line ${st.line}: ${st.arg} is S$own's, timed in S${st.section} (after it's over)"
                if (st.section < own && !GhostPlayer.isDevice(st.arg)) errors += "line ${st.line}: ${st.arg} can't be done before S$own starts"
            }
        }
        for (s in 1..4) for (job in Party.STANDS.keys.filter { it.startsWith("S$s ") }) if (job !in owners) errors += "nobody does $job"
        // When each section starts with everyone on time: its stations all done and its gate down (else the gate goes
        // by itself 5 s later).
        val sectionN = IntArray(6)
        for (k in 1..4) {
            fun at(job: String) = times[job]?.let { (ts, sec) -> sectionN[ts.coerceAtMost(k)] + (sec * 20).roundToInt() }
            val done = Party.STANDS.keys.filter { it.startsWith("S$k ") }.maxOfOrNull { at(it) ?: sectionN[k] } ?: sectionN[k]
            sectionN[k + 1] = if (k <= 3) maxOf(done, at("gate $k") ?: (done + 100)) else done
        }
        return Script(name, starts, steps, Roles.Plan(owners, jobsOf, ee, times, emptyList()), sectionN, errors)
    }

    // ------------------------------------------------------------------ the bots' runs

    /** A class's P3 worked out: where it is every tick, and what it does when. */
    private class Line(val c: DungeonClass, val n: Int) {
        val pos = arrayOfNulls<Vec3>(n)
        val yaw = FloatArray(n)
        val pitch = FloatArray(n)
        val held = arrayOfNulls<String>(n)
        val events = ArrayList<GhostRun.Ev>()
        /** Moving at n (not standing). */
        val moving = BooleanArray(n)
    }

    /** The tick a step is due at with everyone on time. */
    private fun tickOf(s: Script, st: Step) = s.sectionN[st.section] + (st.sec * 20).roundToInt()

    /**
     * Every class's whole P3 as a run a ghost plays ([GhostPlayer]), worked out from the plan. Several passes, so a
     * leap lands where its target is by then.
     */
    fun ghostRuns(s: Script, warnings: MutableList<String>? = null): Map<DungeonClass, GhostRun> {
        val end = s.sectionN[5] + 20 * 12 + (s.steps.values.flatten().filter { it.section == 5 }.maxOfOrNull { (it.sec * 20).roundToInt() } ?: 0)
        var lines: Map<DungeonClass, Line> = emptyMap()
        repeat(3) { pass -> lines = s.steps.keys.associateWith { line(s, it, end, lines, if (pass == 2) warnings else null) } }
        // Everyone's early enters and recores in every run: what the leaps onto them wait for.
        val shared = lines.values.flatMap { l -> l.events.filter { it.type == "ee" || it.type == "recore" } }
        return lines.mapValues { (c, l) ->
            val evs = (l.events + shared.filter { it.a != c.name }).sortedBy { it.n }
            val frames = (0 until l.n).map { i ->
                val p = l.pos[i] ?: Vec3.ZERO
                GhostRun.Frame(P(p.x, p.y, p.z), l.yaw[i], l.pitch[i], l.held[i] ?: "")
            }
            GhostRun(c.name, s.name, s.sectionN[5], frames, evs, intArrayOf(-1, 0, s.sectionN[2], s.sectionN[3], s.sectionN[4], s.sectionN[5]))
        }
    }

    /** How long before its time a bot is at a job (a terminal or device: solving it; at most this, less if it can't be). */
    private fun leadOf(job: String) = if (GhostPlayer.isTerminal(job) || GhostPlayer.isDevice(job)) 40 else 2

    private fun line(s: Script, c: DungeonClass, n: Int, others: Map<DungeonClass, Line>, warn: MutableList<String>?): Line {
        val l = Line(c, n)
        val preset = Roles.PRESETS.firstOrNull { it.name == s.name }
        val steps = s.steps[c].orEmpty()
        var cur = s.starts[c] ?: steps.firstNotNullOfOrNull { it.spot } ?: Party.STANDS.getValue("S1 SS")
        var t = 0
        var yaw = 0f; var pitch = 0f
        var onEe = 0
        var coreSaid = false
        // The leaps onto it (their due ticks): it stays where it is until the ones due while it's there have landed.
        val ontoMe = s.steps.filterKeys { it != c }.values.flatten().filter { it.kind == "leap" && it.arg == c.name }.map { tickOf(s, it) }
        var since = 0
        fun stand(until: Int) {
            while (t < until.coerceAtMost(n)) { l.pos[t] = cur; l.yaw[t] = yaw; l.pitch[t] = pitch; t++ }
        }
        fun ev(at: Int, type: String, a: String = "", k: Int = 0) { l.events += GhostRun.Ev(at.coerceIn(0, n - 1), type, a, k) }
        /** Walks to [to], there by [by] (as late as it can leave, faster only if it must). */
        fun walk(to: Vec3, by: Int, lead: Int, st: Step) {
            if (cur.distanceTo(to) < 0.6) return
            val r = Moves.route(cur, to) ?: Routes.find(cur, to) { false }
            val d = r?.ticks ?: (cur.distanceTo(to) / 0.9)
            val window = by - t
            val arrive = by - minOf(lead, maxOf(0, (window - Math.ceil(d)).toInt()))
            // Leaps onto it that would land mid-way: it goes once they've landed (the ones after land where it's going).
            val natural = maxOf(t, arrive - Math.ceil(d).toInt())
            val stay = ontoMe.filter { it >= since && it in natural until arrive }.maxOfOrNull { it + 3 } ?: 0
            val leave = maxOf(natural, stay).coerceAtMost(maxOf(t, by - 1))
            if (onEe > 0) { ev(leave, "left", c.name, onEe); onEe = 0 }
            // Into the core: not before it opens.
            if (!coreSaid && st.section == 5) { coreSaid = true; ev(leave, "core") }
            if (warn != null && Math.ceil(d) > (arrive - leave) * 1.5 + 2)
                warn += "${Roles.label(c)}, line ${st.line}: ${"%.1f".format(java.util.Locale.ROOT, d / 20)}s move in ${"%.1f".format(java.util.Locale.ROOT, (arrive - leave) / 20.0)}s (sped up ${"%.1f".format(java.util.Locale.ROOT, d / maxOf(1, arrive - leave))}x)"
            stand(leave)
            val span = maxOf(1, arrive - leave)
            val from = cur
            for (i in 1..span) {
                if (t >= n) break
                val f = i.toDouble() / span
                val p = if (r != null) r.pos(f * r.ticks) else from.add(to.subtract(from).scale(f))
                val q = l.pos.getOrNull(t - 1) ?: cur
                if (r != null) { val k = r.frameAt(f * r.ticks); if (!r.yaw[k].isNaN()) { yaw = r.yaw[k]; pitch = r.pitch[k] } else if (Math.hypot(p.x - q.x, p.z - q.z) > 0.05) yaw = Math.toDegrees(Math.atan2(-(p.x - q.x), p.z - q.z)).toFloat() }
                else if (Math.hypot(p.x - q.x, p.z - q.z) > 0.05) { yaw = Math.toDegrees(Math.atan2(-(p.x - q.x), p.z - q.z)).toFloat(); pitch = 0f }
                l.pos[t] = p; l.yaw[t] = yaw; l.pitch[t] = pitch; l.moving[t] = true
                r?.let { rr -> rr.held[rr.frameAt(f * rr.ticks)]?.let { h -> l.held[t] = h } }
                t++
            }
            cur = to
            since = t
        }
        for (st in steps) {
            val due = tickOf(s, st).coerceAtLeast(t)
            when (st.kind) {
                "job" -> {
                    st.spot?.let { walk(it, due, leadOf(st.arg), st) }
                    preset?.defaults?.get("stand/${st.arg}")?.let { yaw = it.yaw; pitch = it.pitch }
                    stand(due)
                    if (st.arg.startsWith("gate")) ev(due, "gate", "", st.arg.removePrefix("gate ").toInt()) else ev(due, "done", st.arg)
                    ev(due, "swing")
                }
                "at", "ee" -> {
                    walk(st.spot!!, due, 0, st)
                    if (st.kind == "ee") { ev(t, "ee", c.name, st.into); onEe = st.into }
                    if (st.arg.trim().equals("recore", true)) ev(t, "recore", c.name)
                }
                "leap" -> {
                    // Onto them where they are; on the move then: once they stop (a second at most).
                    val o = others[DungeonClass.valueOf(st.arg)]
                    var at = due
                    if (o != null) while (at < due + 20 && at < n - 1 && o.moving[at]) at++
                    stand(at)
                    if (onEe > 0) { ev(at, "left", c.name, onEe); onEe = 0 }
                    cur = o?.pos?.getOrNull(at) ?: cur
                    since = at
                    ev(at, "leap", st.arg)
                    for (k in (at - 8).coerceAtLeast(0)..(at + 15).coerceAtMost(n - 1)) if (l.held[k] == null) l.held[k] = "leap"
                    if (t < n) { l.pos[t] = cur; l.yaw[t] = yaw; l.pitch[t] = pitch; t++ }
                }
            }
        }
        stand(n)
        return l
    }
}
