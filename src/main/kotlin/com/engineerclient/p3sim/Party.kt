package com.engineerclient.p3sim

import net.minecraft.world.entity.EntityTypes
import com.mojang.authlib.GameProfile
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.Mannequin
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ResolvableProfile
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The other four of the party: one bot per class you're not, named after its class, in leap menu
 * slots 1-4 ([P3Plan.botOrder]).
 *
 * In P3 they play the skill preset's roles ([Roles]): every job that isn't yours, done at the
 * preset's time; the early enterer goes to its spot after its last job, the others pre-leap onto it
 * (onto you once you're at yours) and walk on to their next terminals; a section's last job waits
 * for you if you're early-entering the next one; at the core, everyone leaps in at once.
 */
object Party {
    /** Hypixel's five classes, in their usual order. */
    val CLASSES by lazy { listOf(DungeonClass.HEALER, DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.TANK, DungeonClass.MAGE) }

    /**
     * Bot walking speed, blocks a tick: teammates' sustained 1 s ground speed in P3 is 0.60 / 0.85 /
     * 1.21 (p10 / median / p90, 30 Better PF runs, analysis party/move.mjs).
     */
    private const val WALK = 0.85
    /**
     * A Hyperion blink, 10 blocks along the look. The few teleports teammates make in P3 that aren't
     * leaps are these (Hyperion held, ~10 blocks; party/leapers.mjs): nearly all their distance is
     * walked or leapt. A bot blinks only where it can't walk (a wall, a climb) or is late.
     */
    private const val BLINK = 10.0

    class Bot(val clazz: DungeonClass, val slot: Int) {
        val name = Roles.label(clazz)
        var entity: Mannequin? = null
        var pos: Vec3 = Vec3.ZERO
        var yaw = 0f
        var pitch = 0f
        /** Which way it turns once it's where it's going (an early-enter spot's), or null: as it walked. */
        var face: Pair<Float, Float>? = null
        /** Where it's walking to (null: standing), from n = [goAt]. */
        var to: Vec3? = null
        var goAt = 0
        /** When it must be there (n; -1: no hurry): it goes faster to make it, like etherwarping. */
        var due = -1
        /** At this terminal doing it (others see "already using"). */
        var working: Station? = null
        /** The section it's in (pre-leapt into the next one: that one). */
        var inSection = 0
        /** Stays where it is until its section starts ("hold"). */
        var hold = false
        /** Vertical speed while falling, blocks a tick. */
        var vy = 0.0
        /** The next tick it may blink; when it last blinked and leapt (what it holds follows them). */
        var blinkReady = 0
        var blinkAt = -100
        var leaptAt = -100
        /** What it holds ([setHeld]'s key): the stack is only replaced when that changes. */
        var heldKey = ""
        /** Playing your best run as this class instead of the bot's role (P3 from S1 only). */
        var ghost: GhostPlayer? = null
    }

    /** An early enter as this run has it: whose (a ghost's: as in its run, on its spot), where. [into] 6: the recore. */
    class Ee(val into: Int, val label: String, val spot: Vec3, val yaw: Float, val pitch: Float, val owner: DungeonClass?) {
        val byYou get() = owner != null && owner == P3Sim.myClass
    }
    private val ees = arrayOfNulls<Ee>(7)

    /** Who early-enters into [into] this run, and where (null: nobody). */
    fun eeOf(into: Int): Ee? = ees.getOrNull(into)

    /**
     * The plan's early enters, but a ghost's are its run's: a ghost that early-entered somewhere does
     * it again (unless it's yours now); a ghost whose class the plan has there but that didn't, doesn't.
     */
    private fun resolveEes() {
        val plan = P3Plan.plan()
        for (into in 2..6) {
            val k = if (into == 6) 5 else into
            val base = P3Plan.earlyEnters.firstOrNull { it.into == into }
            val owner = GhostPlayer.eeOwner(plan.ee[k]?.name, P3Sim.myClass?.name,
                bots.filter { it.ghost?.earlyEnters(k) == true }.map { it.clazz.name }, bots.filter { it.ghost != null }.map { it.clazz.name }.toSet())
            val c = CLASSES.firstOrNull { it.name == owner }
            // The preset's own spot for it, if it has one (else the menu's).
            val spot = base?.let { P3Plan.preset().spots[it.key] } ?: base?.spot
            val g = bots.firstOrNull { it.clazz == c }?.ghost
            // A ghost's spot is where it stood in its run (the recore: the plan's).
            val f = if (into == 6) null else g?.eeSpot(k)
            ees[into] = when {
                c == null -> null
                f != null -> Ee(into, base?.label ?: "EE$into", Vec3(f.p.x, f.p.y, f.p.z), f.yaw, f.pitch, c)
                base != null -> Ee(into, base.label, spot!!, base.yaw, base.pitch, c)
                else -> null
            }
        }
    }

    private val bots = ArrayList<Bot>()

    /** The tick of the section in progress, for debug lines. */
    private var dbgN = 0
    private var dbgS = 0
    private var curPhase: GoldorPhase? = null

    /** A debug line (the menu's Debug bots): section and seconds into it first. */
    private fun dbg(text: String) {
        val sec = (dbgN - sectionN[dbgS.coerceIn(0, 5)]) / 20.0
        val line = "§8[bots S$dbgS %.2f] §7$text".format(sec)
        if (P3Sim.debugBots) Sim.chat(line) else Recorder.event(line)
    }

    /** The bots' state for [Recorder]. */
    fun record(o: com.google.gson.JsonObject) {
        fun v(p: Vec3?) = p?.let { com.google.gson.JsonArray().apply { add(Recorder.r(it.x)); add(Recorder.r(it.y)); add(Recorder.r(it.z)) } }
        o.add("bots", com.google.gson.JsonArray().apply {
            for (b in bots) add(com.google.gson.JsonObject().apply {
                addProperty("name", b.name)
                add("pos", v(b.pos))
                b.to?.let { add("to", v(it)); addProperty("goAt", b.goAt); addProperty("due", b.due) }
                addProperty("in", b.inSection)
                if (b.hold) addProperty("hold", true)
                b.working?.let { addProperty("working", it.id) }
                add("jobs", com.google.gson.JsonArray().apply { jobs.filter { it.bot === b }.forEach { j -> add("${j.job}@${j.at}") } })
            })
        })
        o.addProperty("ee", "arrived=${eeArrived.toList()} youOn=${youOn.toList()} preleapAt=${preleapAt.toList()} spotBy=${eeSpotBy.toList()}")
        if (leaps.isNotEmpty()) o.addProperty("leaps", leaps.joinToString { "${it.bot.name}@${it.at}" })
    }

    private fun Vec3.short() = "%.1f %.1f %.1f".format(x, y, z)

    /** Off to [to] (from n = [at], there by [due]; -1: walking pace), for [why]. */
    private fun go(b: Bot, to: Vec3, at: Int, due: Int, why: String, face: Pair<Float, Float>? = null) {
        b.face = face
        if (b.to != to) dbg("§e${b.name}§7 -> $why (${to.short()}, ${"%.0f".format(b.pos.distanceTo(to))} blocks${if (due >= 0) ", due in ${(due - dbgN) / 20.0}s" else ""}${if (at > dbgN) ", leaves in ${(at - dbgN) / 20.0}s" else ""})")
        b.to = to; b.goAt = at; b.due = due
    }

    /** The bots, in leap slot order (rebuilt when your class or the order changes). */
    fun bots(): List<Bot> {
        val order = P3Plan.botOrder()
        if (bots.map { it.clazz } != order) { clear(); bots.clear(); order.forEachIndexed { i, c -> bots += Bot(c, i + 1) } }
        return bots
    }

    fun bot(slot: Int) = bots().getOrNull(slot - 1)
    private fun botOf(c: DungeonClass?) = bots.firstOrNull { it.clazz == c }

    /** Bumped by every clear: anything queued before a restart is dropped. */
    private var generation = 0

    fun clear() {
        generation++
        jobs.clear(); leaps.clear()
        bots.forEach {
            it.entity?.discard(); it.entity = null; it.to = null; it.due = -1; it.working = null; it.hold = false; it.inSection = 0
            it.vy = 0.0; it.blinkReady = 0; it.blinkAt = -100; it.leaptAt = -100; it.heldKey = ""; it.ghost = null
        }
    }

    fun busyAt(st: Station) = bots.any { it.working === st }

    /** Everyone (the bots) inside [box]. */
    fun allIn(box: AABB) = !P3Sim.bots || bots.all { it.entity == null || box.contains(it.pos) }

    // ------------------------------------------------------------------ P3

    /** A bot's job: [job] (a station id or "gate k") done [sec] s into section [timeSection]; [at]: that n, once the section has started. */
    private class Job(val job: String, val bot: Bot, val timeSection: Int, var sec: Double) { var at = -1 }

    private val jobs = ArrayList<Job>()
    /** Leaps queued: [bot] onto wherever [onto] is at n = [at]. */
    /** [youAt]: a leap onto you at your early-enter spot, which waits while you're not on it. */
    private class Leap(val bot: Bot, val at: Int, val youAt: Vec3? = null, val onto: () -> Vec3?)
    private val leaps = ArrayList<Leap>()
    private var planned = 0
    /** Per section entered early (5 = core): you're on your spot; the bot's on its; when the pre-leap is due; the job whose bot holds. */
    private val youArrived = BooleanArray(6)
    private val eeArrived = BooleanArray(6)
    private val preleapAt = IntArray(6)
    private val eeSpotBy = IntArray(6)
    private val holdJob = arrayOfNulls<String>(6)
    /** Whose leaps each early enterer waits for before moving on ("1" = that section's T1's doer, "ee3"...); null: everyone free. */
    private val waitsFor = arrayOfNulls<List<String>>(6)
    /** You've been on the early enterer (leapt onto it). */
    private val youOn = BooleanArray(6)
    private val holdNoted = BooleanArray(6)
    /** The early enterer into each section has moved on (everyone it waited for leapt). */
    private val released = BooleanArray(6)
    /** When everyone was first on each early enterer (-1: not yet). */
    private val readyAt = IntArray(6) { -1 }
    /** Ticks an early enterer waits after the last leap before moving on (0.3 s). */
    private const val RELEASE_DELAY = 6
    private var lastLeap = 0
    private val sectionN = IntArray(6)
    /** n each early enterer got on its spot (bot or ghost), and you on yours (-1: not yet); n you left yours. */
    private val eeArrivedN = IntArray(6) { -1 }
    private val youArrivedN = IntArray(6) { -1 }
    private val youLeftN = IntArray(6) { -1 }
    /** n the core early enterer stood on the recore in the core (you: were in the core), -1: not yet. */
    private var recoredN = -1
    /** n you were first in the core once it was open. */
    private var youInCoreN = -1
    /** Everyone's leaping onto the core early enterer by the core (the core open). */
    private var coreEeLeaps = false

    fun startP3(phase: GoldorPhase) {
        clear()
        youArrived.fill(false); eeArrived.fill(false); preleapAt.fill(-1); eeSpotBy.fill(-1); holdJob.fill(null); waitsFor.fill(null); youOn.fill(false); holdNoted.fill(false); released.fill(false); readyAt.fill(-1); coreIn = false
        eeArrivedN.fill(-1); youArrivedN.fill(-1); youLeftN.fill(-1); recoredN = -1; youInCoreN = -1; coreEeLeaps = false
        planned = 0
        lastLeap = 0
        if (P3Sim.bots) { bots(); if (phase.from == 1) startGhosts(phase) }
        resolveEes()
        if (!P3Sim.bots) return
        val from = phase.from.coerceIn(1, 5)
        // No early enter into the section you start in (or before).
        for (i in 0..from) eeArrived[i] = true
        val plan = P3Plan.plan()
        // A ghost does its run's jobs; the bots everything else that isn't yours.
        val ghostJobs = bots.flatMap { it.ghost?.jobs.orEmpty() }.toSet()
        val crew = bots.filter { it.ghost == null }
        // Every job that isn't yours: its role's bot (a stack: the first listed), or the least busy one.
        for (job in P3Plan.allJobs()) {
            if (P3Plan.isMine(job) || job in ghostJobs) continue
            val st = phase.stations.firstOrNull { it.id == job }
            if (st?.done == true) continue
            if (st == null && job.startsWith("gate") && phase.gateIsDown(job.removePrefix("gate ").toIntOrNull() ?: 0)) continue
            val s = st?.section ?: job.removePrefix("gate ").toIntOrNull() ?: continue
            if (s < from) continue
            var (ts, sec) = plan.times[job] ?: (s to 5.0)
            val bot = plan.owners[job]?.firstNotNullOfOrNull { c -> botOf(c)?.takeIf { it.ghost == null } }
                ?: crew.minByOrNull { b -> jobs.count { it.bot === b && it.timeSection == ts } } ?: continue
            if (P3Plan.skill == P3Plan.RANDOM) sec = P3Plan.botMin + Random.nextDouble() * (P3Plan.botMax - P3Plan.botMin).coerceAtLeast(0.0)
            if (ts < from) { ts = from; sec = 0.5 }
            jobs += Job(job, bot, ts, sec)
        }
        spread(plan)
        // Helper: bots help on your stacks (both try; whoever's first).
        if (P3Plan.helper) for (h in P3Plan.preset().helps) {
            if (h.section < from || !P3Plan.isMine(h.yours) || !h.jobs.all { P3Plan.isMine(it) }) continue
            val c = P3Plan.doer(h.by)?.takeIf { it != P3Sim.myClass } ?: continue
            val bot = botOf(c)?.takeIf { it.ghost == null } ?: continue
            for (j in h.jobs) if (phase.stations.firstOrNull { it.id == j }?.done != true) {
                jobs += Job(j, bot, h.section, h.at)
                dbg("helper: §e${bot.name}§7 gets $j by ${h.at}s into S${h.section}")
            }
        }
        for (b in bots) {
            b.inSection = from
            // A ghost: where you stood at Goldor's first line.
            val g = b.ghost
            if (g != null) { val f = g.run.frame(0); b.yaw = f.yaw; b.pitch = f.pitch; spawn(b, f.p.vec()); g.tick(phase.n); place(b); continue }
            val core = eeOf(5)?.owner == b.clazz && from >= 3
            // Starting from S3/S4: the core early enterer is already on its spot (by the core), holding.
            if (core) { b.inSection = 5; eeArrived[5] = true; b.hold = from < 5; eeOf(5)?.let { b.yaw = it.yaw; b.pitch = it.pitch } }
            val first = next(b)?.takeIf { sectionOf(it.job) == from }
            spawn(b, when {
                core -> CORE_EE
                from == 1 -> startPos(1)
                first != null -> spotOf(first.job)
                else -> eeOf(from)?.spot ?: startPos(from)
            })
        }
    }

    /** The bots that play your best runs instead (the menu's Plan tab), P3 from S1 with a run saved for this skill. */
    private fun startGhosts(phase: GoldorPhase) {
        val names = ArrayList<String>()
        for (b in bots) {
            if (!P3Plan.ghostOn(b.clazz)) continue
            val run = GhostStore.best(P3Plan.skillName(), b.clazz.name) ?: continue
            b.ghost = GhostPlayer(run, GhostWorld(b, phase), GhostBody(b, phase))
            names += "§d${b.name}§7 (${"%.2f".format(java.util.Locale.ROOT, run.time / 20.0)}s)"
        }
        if (names.isNotEmpty()) Sim.note("Your best runs play: ${names.joinToString(", ")}.")
    }

    private fun P.vec() = Vec3(x, y, z)
    private fun Vec3.p() = P(x, y, z)

    /** The live fight as a ghost sees it ([GhostPlayer.World]). */
    private class GhostWorld(val b: Bot, val phase: GoldorPhase) : GhostPlayer.World {
        override fun sectionStart(s: Int) = if (s <= phase.section) (if (s <= 1) 0 else sectionN[s]) else null
        override fun stationDone(id: String) = phase.stations.firstOrNull { it.id == id }?.done ?: true
        override fun canDo(id: String): Boolean {
            val st = phase.stations.firstOrNull { it.id == id } ?: return false
            if (st.done) return false
            if (st.section != phase.section && !(st.kind == Station.Kind.DEVICE && st.section > phase.section)) return false
            // A section's last job held for you on your way to your early enter (as the bots hold it).
            return !(st.section == phase.section && holding(phase) && phase.stations.count { it.section == phase.section && !it.done } == 1)
        }
        override fun gateDown(s: Int) = phase.gateIsDown(s)
        override fun canGate(s: Int) = phase.section >= s
        override fun eeArrivedAt(into: Int, who: String): Int? {
            val owner = eeOf(into)?.owner ?: return 0
            if (owner.name != who) return 0
            return (if (owner == P3Sim.myClass) youArrivedN[into] else eeArrivedN[into]).takeIf { it >= 0 }
        }
        override fun eeReadyAt(into: Int) = if (eeOf(into)?.owner == b.clazz) readyAt[into].takeIf { it >= 0 } else 0
        override fun recoredAt(who: String): Int? {
            val owner = eeOf(5)?.owner ?: return 0
            if (owner.name != who) return 0
            return (if (owner == P3Sim.myClass) youInCoreN else recoredN).takeIf { it >= 0 }
        }
        override fun positionOf(who: String): P? =
            if (who == P3Sim.myClass?.name) Sim.player?.position()?.p() else bots.firstOrNull { it.clazz.name == who }?.pos?.p()
    }

    /** What a ghost does, done as the bots do it ([GhostPlayer.Body]). */
    private class GhostBody(val b: Bot, val phase: GoldorPhase) : GhostPlayer.Body {
        override fun move(p: P, yaw: Float, pitch: Float, held: String) {
            b.pos = p.vec(); b.yaw = yaw; b.pitch = pitch; b.to = null
            setHeld(b, held)
        }
        override fun complete(id: String) {
            val st = phase.stations.firstOrNull { it.id == id } ?: return
            if (st.kind == Station.Kind.LEVER) phase.pullLever(st, b.name) else st.complete(b.name)
            if (st.kind == Station.Kind.DEVICE) phase.devices.shownDone(st.label)
            dbg("§d${b.name}§7 (ghost) did $id")
        }
        override fun gate(s: Int) { if (phase.blowGate(s, b.name)) dbg("§d${b.name}§7 (ghost) blew gate $s") }
        override fun leap(who: String, at: P) { leapTo(b, at.vec(), phase.n); dbg("§d${b.name}§7 (ghost) leaps onto $who") }
        override fun swing() = swing(b)
        override fun eeOn(into: Int) {
            if (eeOf(into)?.owner != b.clazz) return
            b.inSection = maxOf(b.inSection, into); b.hold = true
            arrivedOnEe(b, into, phase.n)
        }
        override fun eeLeft(into: Int) {
            if (eeOf(into)?.owner != b.clazz) return
            released[into] = true; b.hold = false
            dbg("§d${b.name}§7 (ghost) moves on from EE$into")
        }
        override fun recore() {
            if (eeOf(5)?.owner != b.clazz || recoredN >= 0) return
            recoredN = phase.n
            GhostCapture.event("recore", b.clazz.name)
        }
        override fun working(id: String?) { b.working = id?.let { i -> phase.stations.firstOrNull { it.id == i } } }
        override fun giveUp(job: String) {
            // The rest of the party takes it, now.
            val bot = bots.filter { it.ghost == null }.minByOrNull { o -> jobs.count { it.bot === o } } ?: return
            jobs += Job(job, bot, phase.section, 0.0).also { it.at = phase.n }
            dbg("§d${b.name}§7 (ghost) couldn't do $job: §e${bot.name}§7 does")
            walkOn(bot, phase.n)
        }
    }

    /** [b] (a bot or a ghost) is on its early-enter spot into [into]: the party can leap onto it. */
    private fun arrivedOnEe(b: Bot, into: Int, n: Int) {
        eeArrived[into] = true
        if (eeArrivedN[into] < 0) eeArrivedN[into] = n
        GhostCapture.event("ee", b.clazz.name, into)
        val ee = eeOf(into) ?: return
        if (P3Sim.debugBots) dbg("§a${b.name} is on its ${ee.label} spot§7, holding") else Sim.note("§e${b.name}§7 is on ${ee.label}.")
    }

    /**
     * Where you are, for the ghosts and your recording: on your early-enter spots (and off them
     * again), in the core once it's open.
     */
    private fun trackYou(phase: GoldorPhase) {
        val p = Sim.player?.position() ?: return
        val n = phase.n
        for (into in 2..5) {
            val ee = eeOf(into)?.takeIf { it.byYou } ?: continue
            val d = p.distanceTo(ee.spot)
            if (youArrivedN[into] < 0) {
                if (phase.section < into && d <= 3.0) { youArrivedN[into] = n; GhostCapture.event("ee", ee.owner!!.name, into) }
            } else if (youLeftN[into] < 0 && d > 3.0) { youLeftN[into] = n; GhostCapture.event("left", ee.owner!!.name, into) }
            // Off and straight back on (a step, a blink the wrong way): still on it.
            else if (youLeftN[into] >= 0 && d <= 3.0 && n - youLeftN[into] <= GhostPlayer.BACK) { youLeftN[into] = -1; GhostCapture.event("back", ee.owner!!.name, into) }
        }
        if (phase.section >= 5 && youInCoreN < 0 && GoldorPhase.CORE_BOX.contains(p)) {
            youInCoreN = n
            GhostCapture.event("core")
            P3Sim.myClass?.takeIf { eeOf(5)?.owner == it }?.let { GhostCapture.event("recore", it.name) }
        }
    }

    /** A bot with several jobs at one time does them evenly spread since its previous one (in role order). */
    private fun spread(plan: Roles.Plan) {
        for (b in bots) for (ts in 1..5) {
            val role = plan.jobsOf[b.clazz].orEmpty()
            val mine = jobs.filter { it.bot === b && it.timeSection == ts }
                .sortedWith(compareBy<Job> { it.sec }.thenBy { role.indexOf(it.job).let { i -> if (i < 0) 99 else i } })
            var prev = 0.0
            var i = 0
            while (i < mine.size) {
                val t = mine[i].sec
                val group = mine.drop(i).takeWhile { it.sec == t }
                group.forEachIndexed { k, j -> j.sec = prev + (t - prev) * (k + 1) / group.size }
                prev = t
                i += group.size
            }
        }
    }

    fun tickP3(phase: GoldorPhase) {
        trackYou(phase)
        if (!P3Sim.bots) return
        val n = phase.n
        val s = phase.section
        dbgN = n; dbgS = s; curPhase = phase
        if (P3Sim.debugBots && n % 40 == 0) dbgHolds(phase)
        if (s != planned) { planned = s; sectionN[s.coerceIn(0, 5)] = n; dbg("§bsection $s starts"); sectionStarted(phase, s) }
        // Jobs someone else (you, on a stack) already did.
        jobs.removeAll { j -> phase.stations.firstOrNull { it.id == j.job }?.done == true || (j.job.startsWith("gate") && phase.gateIsDown(sectionOf(j.job))) }
        youAtEarlyEnter(phase)
        // Leaps that are due.
        leaps.filter { n >= it.at }.forEach { l ->
            // Onto you at your early enter: only while you're in position.
            if (l.youAt != null && Sim.player?.position()?.let { it.distanceTo(l.youAt) <= 3.0 } != true) return@forEach
            leaps.remove(l)
            l.onto()?.let { dbg("§e${l.bot.name}§7 leaps (${it.short()})"); leapTo(l.bot, it, n); walkOn(l.bot, n) }
        }
        // Jobs that are due (a section's last held while you're on your way to your early enter).
        val held = holding(phase)
        val finished = ArrayList<Bot>()
        jobs.removeAll { j ->
            if (j.at < 0 || n < j.at) return@removeAll false
            // Done where it's done: not while holding (at an early enter, waiting), not before the bot is there.
            if (j.bot.hold || lateForEe(j.bot, s)) return@removeAll false
            if (j.bot.pos.distanceTo(spotOf(j.job)) > 2.5) {
                if (j.bot.to == null) go(j.bot, spotOf(j.job), n, n, "${j.job} (due now, not there)")
                return@removeAll false
            }
            val st = phase.stations.firstOrNull { it.id == j.job }
            if (st != null) {
                if (st.section == s && held && phase.stations.count { it.section == s && !it.done } == 1) return@removeAll false
                // A lever's line comes on the swing that pulls it (1 tick, party/leapers.mjs); a device's last click swings too.
                if (st.kind != Station.Kind.TERMINAL) swing(j.bot)
                if (st.kind == Station.Kind.LEVER) phase.pullLever(st, j.bot.name) else st.complete(j.bot.name)
                if (st.kind == Station.Kind.DEVICE) phase.devices.shownDone(st.label)
                if (!st.done) return@removeAll false
            } else {
                val k = sectionOf(j.job)
                if (!phase.gateIsDown(k)) { swing(j.bot); if (!phase.blowGate(k, j.bot.name)) return@removeAll false }
            }
            dbg("§e${j.bot.name}§7 did ${j.job}")
            j.bot.working = null
            finished += j.bot
            true
        }
        finished.forEach { walkOn(it, n) }
        ghosts(phase)
        earlyEnterBots(phase)
        preleaps(phase)
        releaseEarlyEnterers(phase)
        intoCoreWhenRecored(phase)
        for (b in bots) if (b.ghost == null) move(b, n) else place(b)
        // Working: at its terminal for the last 2 s before it's done (opening it swings: a right click on its stand).
        for (b in bots) {
            if (b.ghost != null) continue
            val was = b.working
            b.working = next(b)?.takeIf { it.at >= 0 && it.at - n <= 40 && b.pos.distanceTo(spotOf(it.job)) <= 2.5 }?.let { j -> phase.stations.firstOrNull { it.id == j.job && it.kind == Station.Kind.TERMINAL } }
            if (b.working != null && b.working !== was) swing(b)
            hold(b, n)
        }
    }

    /**
     * The ghosts play on. One whose run ended outside the core (it shouldn't: runs are kept once
     * you're in) walks in as a bot would; once the core's open, everyone leaps onto a core early
     * enterer ghost that's on its spot.
     */
    private fun ghosts(phase: GoldorPhase) {
        val n = phase.n
        for (b in bots) {
            val g = b.ghost ?: continue
            g.tick(n)
            if (g.finished && phase.section >= 5 && !GoldorPhase.CORE_BOX.contains(b.pos)) {
                b.ghost = null; b.hold = false; b.inSection = 5
                go(b, CORE_SPOT, n, -1, "the core (its run ended outside)")
            }
        }
        if (phase.section >= 5 && !coreEeLeaps && !coreIn) coreBot()?.takeIf { it.ghost != null && eeArrived[5] && !released[5] }?.let { leapOntoCoreEe(it, n) }
    }

    /** Section [s] began: its times start, its moves are set; anyone not in it leaps onto whoever early-entered it, or walks. */
    private fun sectionStarted(phase: GoldorPhase, s: Int) {
        val n = phase.n
        val plan = P3Plan.plan()
        jobs.filter { it.timeSection == s }.forEach { it.at = n + (it.sec * 20).roundToInt() }
        for (m in plan.moves.filter { it.section == s }) {
            val at = m.at?.let { n + (it * 20).roundToInt() }
            when (m.kind) {
                "spot" -> into(m.who)?.let { if (at != null) eeSpotBy[it] = at }
                "preleap" -> into(m.who)?.let { if (at != null) preleapAt[it] = at }
                "hold" -> holdJob[s + 1] = "S${s + 1} T${m.who}"
                "waits" -> into(m.who)?.let { waitsFor[it] = m.args }
                "leaps" -> botOf(Roles.whoIs(plan, m.who))?.takeIf { it.ghost == null }?.let { b ->
                    if (at != null) leaps += Leap(b, at) {
                        // Back into the section: onto someone still working in it (else you).
                        bots.firstOrNull { it !== b && it.inSection == s && busy(it) }?.pos ?: Sim.player?.position()
                    }
                }
            }
        }
        if (s >= 5) { core(phase); return }
        val ee = eeOf(s)
        val eeBot = ee?.takeIf { !it.byYou && s > phase.from }?.let { botOf(it.owner) }
        if (eeBot != null && !eeArrived[s]) dbg("§e${eeBot.name}§7 isn't on its ${ee.label} spot yet: going on there, the others leap to it")
        val onto = eeBot
        var i = 0
        for (b in bots) {
            // A ghost goes as you went.
            if (b.ghost != null) continue
            // Holding for a later early enter (the core bot by the core through S4): it keeps holding.
            if (b.hold && b.inSection > s && eeArrived[b.inSection.coerceAtMost(5)] && !released[b.inSection.coerceAtMost(5)]) continue
            if (b.hold && b !== onto) dbg("§e${b.name}§7 stops holding (section $s started)")
            b.hold = false
            // Holding (or on its way) unless it already moved on in the section before.
            if (b === onto) { b.hold = eeArrived[s] && !released[s]; continue }
            if (b.inSection >= s) { walkOn(b, n); continue }
            b.inSection = s
            if (onto != null) leaps += Leap(b, n + 2 + gapTicks() * i++) { onto.pos }
            else walkOn(b, n)
        }
    }

    /** [b] early-enters section [s] (in progress) and isn't on its spot yet. */
    private fun lateForEe(b: Bot, s: Int): Boolean {
        if (s > 4 || eeArrived[s]) return false
        val ee = eeOf(s)?.takeIf { !it.byYou } ?: return false
        return botOf(ee.owner) === b && b.ghost == null
    }

    /** "ee2" -> 2, "core" -> 5. */
    private fun into(token: String) = if (token == "core") 5 else token.removePrefix("ee").toIntOrNull()

    /** The bot early-entering into section [into] walks to its spot once its jobs before are done; it's in when it's there. */
    private fun earlyEnterBots(phase: GoldorPhase) {
        val s = phase.section
        // The section in progress too: an early enterer that didn't make it before its section started still goes and waits.
        for (into in s..5) {
            if (into == s && (into > 4 || eeArrived[into])) continue
            val ee = eeOf(into) ?: continue
            if (ee.byYou) continue
            val b = botOf(ee.owner) ?: continue
            // A ghost gets there as you did.
            if (b.ghost != null) continue
            if (into > s && (b.inSection >= into || busy(b) || b.hold)) continue
            // Not before its jobs in the sections before (the EE3 bot does its S2 ones first, not straight from S1).
            if (jobs.any { it.bot === b && (sectionOf(it.job) < into || it.timeSection < into) }) continue
            if (b.pos.distanceTo(ee.spot) < 0.5) {
                b.inSection = into; b.hold = true
                b.yaw = ee.yaw; b.pitch = ee.pitch
                arrivedOnEe(b, into, phase.n)
                continue
            }
            // Straight there once free, at etherwarp pace: on the spot (ready for leaps) as early as it can be.
            if (b.to != ee.spot) go(b, ee.spot, phase.n, phase.n, "its ${ee.label} spot", ee.yaw to ee.pitch)
            break
        }
    }

    /** The pre-leap onto the early enterer of the next section: everyone free, one after another; the busy ones after their last job. */
    private fun preleaps(phase: GoldorPhase) {
        val n = phase.n
        val into = phase.section + 1
        if (into > 5) return
        val ee = eeOf(into) ?: return
        val eeBot = if (ee.byYou) null else botOf(ee.owner)
        val open = if (ee.byYou) youArrived[into] else eeBot != null && eeArrived[into] &&
            n >= (if (preleapAt[into] >= 0) preleapAt[into] else 0)
        if (!open) return
        val target: () -> Vec3? = if (eeBot != null) ({ eeBot.pos }) else ({ Sim.player?.position() })
        for (b in bots) {
            if (b === eeBot || b.ghost != null || b.inSection >= into || busy(b)) continue
            b.inSection = into
            lastLeap = maxOf(n + 1, lastLeap + if (eeBot != null) 2 else gapTicks())
            if (holdJob[into] != null && jobs.any { it.bot === b && it.job == holdJob[into] }) b.hold = true
            leaps += Leap(b, lastLeap, if (ee.byYou) ee.spot else null, target)
        }
    }

    /**
     * An early enterer (a bot) moves on from its spot once everyone it waits for has leapt onto it
     * (the preset's "waits"; else everyone, you included); at the latest 10 s into the section it entered.
     */
    private fun releaseEarlyEnterers(phase: GoldorPhase) {
        val n = phase.n
        val s = phase.section
        // You're on an early enterer once you're within 3 blocks of it (by position: a leap lands
        // you on it) while it holds on its spot; on it before it got there doesn't count.
        for (into in s..(s + 1).coerceAtMost(5)) {
            val b = eeOf(into)?.takeIf { !it.byYou }?.let { botOf(it.owner) } ?: continue
            if (youOn[into] || !b.hold || !eeArrived[into] || released[into]) continue
            if (Sim.player?.position()?.let { it.distanceTo(b.pos) < 3.0 } == true) { youOn[into] = true; dbg("§ayou're on ${b.name}§7 (EE$into)") }
        }
        for (into in s..(s + 1).coerceAtMost(5)) {
            val ee = eeOf(into)?.takeIf { !it.byYou } ?: continue
            val b = botOf(ee.owner) ?: continue
            if (released[into] || !b.hold || !eeArrived[into] || b.inSection < into) continue
            val waits = waitsFor[into]
            // You're waited for if you're one who leaps onto it (on SS you leap to the 2nd term instead).
            val needYou = waits == null || waits.any { whoFor(it, into) == P3Sim.myClass }
            val ok = when {
                // Never without you (a safety net at 30 s); the bots get 10 s.
                into == s && n - sectionN[s] >= 600 -> true
                needYou && !leapt(P3Sim.myClass, b, into) -> false
                into == s && n - sectionN[s] >= 200 -> true
                waits != null -> waits.all { t -> leapt(whoFor(t, into), b, into) }
                // No list: everyone - every bot (busy ones too, once they're done and leapt) and you.
                else -> (into == s || preleapOpen(into, n)) &&
                    bots.all { it === b || leapt(it.clazz, b, into) } && leapt(P3Sim.myClass, b, into)
            }
            // Everyone's on: it moves 0.3 s later (as a player reacts).
            if (!ok) { readyAt[into] = -1; continue }
            if (readyAt[into] < 0) { readyAt[into] = n; dbg("§e${b.name}§7: everyone's on EE$into${if (b.ghost == null) ", moving in ${RELEASE_DELAY / 20.0}s" else ""}") }
            // A ghost moves on when you did after the last leap onto you ([GhostPlayer]: its eeLeft).
            if (b.ghost != null) continue
            if (n - readyAt[into] < RELEASE_DELAY) continue
            released[into] = true
            dbg("§c${b.name} moves on from EE$into§7: ${waitStatus(b, into)}${if (into == s) ", ${(n - sectionN[s]) / 20.0}s into S$s" else ""}")
            // The core early enterer goes into the core (recore) and waits there: everyone leaps onto it in.
            val recore = if (into == 5) eeOf(6) else null
            if (recore != null) go(b, recore.spot, n, -1, "the recore (in the core)", recore.yaw to recore.pitch)
            else { b.hold = false; walkOn(b, n) }
        }
    }

    /** Debug: who an early enterer has (+) and hasn't (-) had leap onto it. */
    private fun waitStatus(b: Bot, into: Int): String {
        val waits = waitsFor[into]
        val who = (waits?.mapNotNull { whoFor(it, into) } ?: bots.filter { it !== b }.map { it.clazz }) + listOfNotNull(P3Sim.myClass)
        return who.distinct().filter { it != b.clazz }.joinToString(" ") { c ->
            val name = if (c == P3Sim.myClass) "you" else Roles.label(c)
            if (leapt(c, b, into)) "§a+$name§7" else "§c-$name§7"
        } + if (waits != null) " (list: ${waits.joinToString(" ")})" else " (everyone)"
    }

    /** Debug, every 2 s: what each bot is doing, and who the holding early enterers wait for. */
    private fun dbgHolds(phase: GoldorPhase) {
        for (b in bots) {
            val ee = (1..5).firstOrNull { eeOf(it)?.owner == b.clazz }
            val state = when {
                b.ghost != null -> "§dghost§7: ${b.ghost!!.status}"
                b.hold -> "§6holding§7 (EE$ee: ${ee?.let { waitStatus(b, it) } ?: "?"})"
                b.to != null -> "moving to ${b.to!!.short()}"
                else -> "standing"
            }
            dbg("§e${b.name}§7 in S${b.inSection}: $state; next ${next(b)?.job ?: "none"}")
        }
    }

    /** "3" -> who does S[into] T3; "ee3" / "core" -> who early-enters there. */
    private fun whoFor(token: String, into: Int): DungeonClass? =
        if (token.all { it.isDigit() }) P3Plan.doer("S$into T$token") else eeOf(into(token) ?: return null)?.owner

    /**
     * [c] has leapt onto [onto] (you: been within 3 blocks of it). A ghost: it's on it, or leapt onto
     * it since it got there, or its run has no leap onto it there to wait for.
     */
    private fun leapt(c: DungeonClass?, onto: Bot, into: Int): Boolean = when {
        c == null || c == onto.clazz -> true
        c == P3Sim.myClass -> youOn[into]
        else -> botOf(c)?.let { o ->
            val g = o.ghost
            if (g != null) o.pos.distanceTo(onto.pos) < 3.0 || g.leaptOnto(onto.clazz.name, eeArrivedN[into].coerceAtLeast(0)) || !g.expectsLeap(onto.clazz.name, into)
            else o.inSection >= into && leaps.none { l -> l.bot === o }
        } ?: true
    }

    private fun preleapOpen(into: Int, n: Int) = n >= (if (preleapAt[into] >= 0) preleapAt[into] else 0)

    /** You're at your early enter: the party leaps onto you (pre-leaps() does it). */
    private fun youAtEarlyEnter(phase: GoldorPhase) {
        val into = phase.section + 1
        if (into > 4) return
        val ee = eeOf(into)?.takeIf { it.byYou } ?: return
        if (youArrived[into]) return
        val p = Sim.player ?: return
        if (p.position().distanceTo(ee.spot) > 3.0) return
        youArrived[into] = true
        Sim.note("§aAt your ${ee.label}§7: the party leaps to you.")
    }

    /** Is a section's last job held for you (you early-enter the next one and aren't there yet). */
    private fun holding(phase: GoldorPhase): Boolean {
        if (!P3Plan.waitForYou) return false
        val ee = eeOf(phase.section + 1)?.takeIf { it.byYou && it.into <= 4 } ?: return false
        if (youArrived[ee.into]) return false
        if (!holdNoted[ee.into] && phase.stations.count { it.section == phase.section && !it.done } == 1) {
            holdNoted[ee.into] = true
            Sim.note("§7The party holds S${phase.section}'s last job until you're at your §e${ee.label}§7 spot (Wait for you, in the menu's Early Enters).")
        }
        return true
    }

    /** Everyone leapt into the core (onto the recore). */
    private var coreIn = false

    /** The core early enterer is a bot (null: you, or nobody). */
    private fun coreBot() = eeOf(5)?.takeIf { !it.byYou }?.let { botOf(it.owner) }

    /**
     * The core opened. The core early enterer (a bot) still holding by the core: everyone not on it
     * leaps onto it first; once all are (you too) it goes in (recore, [releaseEarlyEnterers]) and then
     * they leap in onto it ([intoCoreWhenRecored]). Else straight in. A ghost there: as above once
     * it's on its spot ([ghosts]); in once it's in (it recored).
     */
    private fun core(phase: GoldorPhase) {
        val n = phase.n
        coreIn = false
        val cb = coreBot()
        if (cb != null && eeArrived[5] && !released[5]) { leapOntoCoreEe(cb, n); return }
        if (cb?.ghost != null) { if (recoredN >= 0) leapIntoCore(phase); return }
        // Not on its spot yet (straight in onto it), or already in (on the recore): in now; walking in: when it gets there.
        if (cb == null || !released[5] || cb.to == null) leapIntoCore(phase)
    }

    /** Everyone not on the core early enterer [cb] leaps onto it, by the core. */
    private fun leapOntoCoreEe(cb: Bot, n: Int) {
        coreEeLeaps = true
        var i = 0
        for (b in bots) {
            if (b === cb || b.ghost != null) continue
            b.hold = false
            if (b.inSection < 5) { b.inSection = 5; leaps += Leap(b, n + 2 + gapTicks() * i++) { cb.pos } }
        }
        dbg("core open: everyone leaps onto §e${cb.name}§7 by the core first, then it recores")
    }

    /**
     * In the core (section 5): once the core bot has gone in and stands on the recore, everyone leaps
     * onto it (a ghost: once it was in the core, as you were; 20 s on, in anyway).
     */
    private fun intoCoreWhenRecored(phase: GoldorPhase) {
        if (coreIn || phase.section < 5) return
        val cb = coreBot() ?: return
        if (cb.ghost != null) { if (recoredN >= 0 || phase.n - sectionN[5] >= 400) leapIntoCore(phase); return }
        if (released[5] && cb.to == null) {
            if (recoredN < 0) { recoredN = phase.n; GhostCapture.event("recore", cb.clazz.name) }
            leapIntoCore(phase)
        }
    }

    /** Everyone leaps in at once onto whoever's in the core (the core early enterer), or walks in. Ghosts go as you did. */
    private fun leapIntoCore(phase: GoldorPhase) {
        coreIn = true
        val n = phase.n
        dbg("everyone leaps into the core")
        val ee = eeOf(5)
        val onto: (() -> Vec3?)? = when {
            ee == null -> null
            ee.byYou -> Sim.player?.takeIf { GoldorPhase.CORE_BOX.inflate(4.0).contains(it.position()) }?.let { p -> { p.position() } }
            else -> botOf(ee.owner)?.let { b -> { b.pos } }
        }
        bots.forEachIndexed { i, b ->
            if (b.ghost != null) return@forEachIndexed
            b.hold = false
            b.inSection = 5
            val spot = CORE_SPOT.add((i - 1.5) * 1.5, 0.0, 2.0 + Random.nextDouble())
            if (onto != null && botOf(ee?.owner) !== b) leaps += Leap(b, n + 1 + i * 2) { onto() }
            val gen = generation
            Fight.later(if (onto != null) 12 + i * 2 else 0, "bots into core") { if (gen == generation) go(b, spot, 0, -1, "the core") }
        }
    }

    private fun gapTicks() = (P3Plan.leapGap * 20).toInt().coerceAtLeast(1)

    private fun next(b: Bot) = jobs.filter { it.bot === b }.minWithOrNull(compareBy<Job> { it.timeSection }.thenBy { it.sec })

    /** Has a job due in the section in progress (or earlier). */
    private fun busy(b: Bot) = jobs.any { it.bot === b && it.at >= 0 }

    /** Off to the next job if it can be got to now (its time has started, or its section is where the bot is), unless holding. */
    private fun walkOn(b: Bot, n: Int) {
        if (b.ghost != null || b.hold || lateForEe(b, planned)) return
        val j = next(b) ?: return
        if (j.at >= 0 || sectionOf(j.job) <= b.inSection) go(b, spotOf(j.job), n, j.at, "${j.job}${if (j.at < 0) " (its section not started)" else ""}")
    }

    private fun sectionOf(job: String) = job.removePrefix("gate ").toIntOrNull() ?: job.substring(1, 2).toInt()

    private fun spotOf(job: String): Vec3 = STANDS[job] ?: job.removePrefix("gate ").toIntOrNull()?.let { GATES.getOrNull(it) } ?: CORE_SPOT

    /**
     * A tick of getting there, as teammates do (party/move.mjs, leapers.mjs): walking on the
     * blocks at [WALK] (up steps and 1-block climbs, falling off edges), and a Hyperion [BLINK]
     * toward it where walking can't (a wall, somewhere higher, lava) - every 8 ticks - or won't make
     * it in time - every 4, every 2 (Hyperion's own limit) once it's due already. P3's floors are
     * islands over lava that players cross with jumps and Bonzo/Jerry boosts (rises of 1-1.6 a tick,
     * move.mjs); the blinks stand in for those (no walking path exists between most job spots on the
     * sim's arena: party/path.mjs).
     */
    private fun move(b: Bot, n: Int) {
        val to = b.to
        if (to != null && n >= b.goAt) {
            val d = to.subtract(b.pos)
            val len = d.length()
            val flat = Math.hypot(d.x, d.z)
            if (flat > 0.01) { b.yaw = Math.toDegrees(Math.atan2(-d.x, d.z)).toFloat(); b.pitch = 0f }
            val late = b.due >= 0 && (b.due <= n || len / (b.due - n) > WALK * 1.15)
            if (len <= WALK && Math.abs(d.y) <= 1.25) arrive(b, to)
            else {
                // The walk this tick: where it'd stand (null: it can't - a wall, or no floor it should drop to).
                val step = if (flat > 0.01) d.multiply(1.0, 0.0, 1.0).scale(minOf(WALK, flat) / flat) else Vec3.ZERO
                val next = b.pos.add(step)
                val g = ground(next.x, next.z, b.pos.y)
                // Not: into a wall, under somewhere higher, off a drop it would have to climb back, or nowhere nearer.
                val walkable = g != null && !(to.y - b.pos.y > 1.25 && flat < 2.0) && !(g < b.pos.y - 4 && to.y > g + 3) &&
                    (flat > 0.01 || g < b.pos.y - 0.01)
                when {
                    (late || !walkable) && n >= b.blinkReady -> blink(b, to, n, if (b.due in 0..n) 2 else if (late) 4 else 8)
                    walkable -> fall(b, next.x, next.z, g!!)
                    else -> ground(b.pos.x, b.pos.z, b.pos.y)?.let { fall(b, b.pos.x, b.pos.z, it) }
                }
            }
        } else if (to == null && !b.hold) {
            // Standing in the air (leapt onto someone mid-jump): it drops, as anyone does.
            ground(b.pos.x, b.pos.z, b.pos.y)?.takeIf { it < b.pos.y - 0.01 }?.let { fall(b, b.pos.x, b.pos.z, it) }
        }
        place(b)
    }

    /** To ([x], [z]) on floor [g]: up onto it at once (a step or jump), down to it at vanilla gravity. */
    private fun fall(b: Bot, x: Double, z: Double, g: Double) {
        if (g >= b.pos.y - 0.01) { b.pos = Vec3(x, g, z); b.vy = 0.0; return }
        b.vy = (b.vy - 0.08) * 0.98
        b.pos = Vec3(x, maxOf(g, b.pos.y + b.vy), z)
        if (b.pos.y <= g) b.vy = 0.0
    }

    private fun arrive(b: Bot, to: Vec3) {
        b.pos = to; b.to = null; b.vy = 0.0
        b.face?.let { b.yaw = it.first; b.pitch = it.second }
    }

    /** A Hyperion blink toward [to]: 10 blocks, onto the floor if one's just under where it ends (else it falls from there). */
    private fun blink(b: Bot, to: Vec3, n: Int, gap: Int) {
        b.blinkReady = n + gap
        b.blinkAt = n
        b.vy = 0.0
        val d = to.subtract(b.pos)
        if (d.length() <= BLINK) { arrive(b, to); return }
        val q = b.pos.add(d.normalize().scale(BLINK))
        val g = ground(q.x, q.z, q.y)
        b.pos = if (g != null && g >= q.y - 3) Vec3(q.x, g, q.z) else q
    }

    /**
     * The floor a body at height [y] would stand on at ([x], [z]): stepping up to 1.25 (a jump) or
     * dropping any way down, with 1.8 of room above it, under any part of its feet (a body is 0.6
     * wide: the terminal spots are on stair edges). Null: a wall (no room at that height) or nothing
     * to stand on within 40 blocks.
     */
    private fun ground(x: Double, z: Double, y: Double): Double? {
        var best: Double? = null
        for (cx in setOf(Math.floor(x - 0.29).toInt(), Math.floor(x + 0.29).toInt()))
            for (cz in setOf(Math.floor(z - 0.29).toInt(), Math.floor(z + 0.29).toInt())) {
                val g = column(cx, cz, y)
                if (g == WALL) return null
                if (g != null && (best == null || g > best)) best = g
            }
        return best
    }

    /** [ground]'s answer for "a wall here". */
    private const val WALL = Double.MAX_VALUE

    /** One block column's floor for a body at [y] ([WALL]: no room there; null: nothing below). */
    private fun column(bx: Int, bz: Int, y: Double): Double? {
        val level = Sim.level
        var cy = Math.floor(y + 1.25).toInt()
        val bottom = cy - 40
        while (cy > bottom) {
            val pos = net.minecraft.core.BlockPos(bx, cy, bz)
            val state = level.getBlockState(pos)
            // Nobody walks into the lava under P3's floors: it's a blink across.
            if (state.fluidState.`is`(net.minecraft.tags.FluidTags.LAVA)) return WALL
            val shape = state.getCollisionShape(level, pos)
            if (!shape.isEmpty) {
                val top = cy + shape.max(net.minecraft.core.Direction.Axis.Y)
                if (top > y + 1.25) return WALL
                // Room for the body above it.
                for (hy in Math.floor(top + 0.01).toInt()..Math.floor(top + 1.79).toInt()) {
                    if (hy == cy) continue
                    val hp = net.minecraft.core.BlockPos(bx, hy, bz)
                    val hs = level.getBlockState(hp).getCollisionShape(level, hp)
                    if (!hs.isEmpty && hy + hs.min(net.minecraft.core.Direction.Axis.Y) < top + 1.8) return WALL
                }
                return top
            }
            cy--
        }
        return null
    }

    /** [b] leaps onto [at]: lands there facing as whoever it leapt to; their mod says so in party chat a tick later. */
    private fun leapTo(b: Bot, at: Vec3, n: Int) {
        val p = Sim.player
        val onto: Pair<String, Float>? = when {
            p != null && p.position().distanceTo(at) < 1.0 -> Sim.me to p.yRot
            else -> bots.firstOrNull { it !== b && it.pos.distanceTo(at) < 1.0 }?.let { it.name to it.yaw }
        }
        b.pos = at; b.to = null; b.vy = 0.0; b.leaptAt = n
        onto?.let { b.yaw = it.second }
        Sim.sound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, 1f, 1f, at)
        // 103 of 146 teammates' leaps were announced (all 81 in older runs), median 1 tick after the tp (party/leapers.mjs).
        val name = onto?.first ?: return
        // On you: what an early enterer (you, recording) waits for.
        if (name == Sim.me) GhostCapture.event("landed", b.clazz.name)
        val gen = generation
        Fight.later(1, "leap announce") { if (gen == generation) Sim.chat(partyLine(b, "Leaped to $name!")) }
    }

    /** A bot's party chat line, as Hypixel shows one (the bots are MVP+, as the leap lines colour them). */
    fun partyLine(b: Bot, text: String) = "§9Party §8> §b[MVP§c+§b] ${b.name}§f: $text"

    private fun swing(b: Bot) { b.entity?.swing(net.minecraft.world.InteractionHand.MAIN_HAND) }

    /**
     * What a bot holds (party/measure.mjs, leapers.mjs): in P3 the Dungeonbreaker (teammates' rest
     * slot: 37-67% of P3 by class), the Infinileap from ~0.5 s before a leap to 1 s after (the head
     * comes out a median 8-19 ticks before), a Hyperion after a blink.
     */
    private fun hold(b: Bot, n: Int) {
        val key = when {
            leaps.any { it.bot === b && it.at - n in 0..10 } || n - b.leaptAt < 20 -> "leap"
            n - b.blinkAt < 15 -> "hyperion"
            else -> "breaker"
        }
        setHeld(b, key)
    }

    private fun setHeld(b: Bot, key: String) {
        val e = b.entity ?: return
        if (b.heldKey == key) return
        b.heldKey = key
        // The bots' keys, or a ghost's: what you held (the sim item's id).
        e.setItemSlot(EquipmentSlot.MAINHAND, when (key) {
            "leap", "INFINITE_SPIRIT_LEAP" -> SimItems.LEAP
            "hyperion", "HYPERION" -> SimItems.HYPERION
            "terminator", "TERMINATOR" -> SimItems.TERMINATOR
            "SUPERBOOM_TNT" -> SimItems.SUPERBOOM
            "ASPECT_OF_THE_VOID" -> SimItems.AOTV
            "STARRED_BONZO_STAFF" -> SimItems.BONZO
            "JERRY_STAFF" -> SimItems.JERRY
            "ITEM_SPIRIT_BOW" -> SimItems.SPIRIT_BOW
            "MOSQUITO_BOW" -> SimItems.MOSQUITO
            "ENDER_PEARL" -> SimItems.PEARLS
            "WITHER_CLOAK" -> SimItems.CLOAK
            "PET_ROD" -> SimItems.PET_ROD
            else -> SimItems.DUNGEONBREAKER
        })
    }

    /**
     * What each class holds standing about outside P3, its most-held item there (party/measure.mjs,
     * 40 runs): P1 the Archer's Terminator, the Healer's Dungeonbreaker, everyone else's Hyperion;
     * P2 the same; P4 Hyperion but the Healer's Dungeonbreaker.
     */
    private fun restingItem(c: DungeonClass): String = when {
        c == DungeonClass.HEALER -> "breaker"
        c == DungeonClass.ARCHER && Fight.phase !is P4Necron -> "terminator"
        else -> "hyperion"
    }

    // ------------------------------------------------------------------ outside P3

    /** The bots standing still at [spots] (P1, P2: leap targets). */
    fun standAt(spots: List<Vec3>) {
        clear()
        if (!P3Sim.bots) return
        bots().forEachIndexed { i, b -> spawn(b, spots[i % spots.size]); place(b) }
    }

    /** Called every server tick by the fight (outside P3, the bots just stand). */
    fun tick() {}

    /**
     * P4: where teammates stand once they're down at Necron (party/where.mjs, 30 runs, 10 s in):
     * on the floor at y 64 around (45-62, 104-120), the Mage often right at (54, 101). From P3 they
     * drop in from the core over ~3 s (at 3 s most are still at y 69-75), so they land one by one.
     */
    fun startP4(fromP3: Boolean) {
        if (!P3Sim.bots) return
        leaps.clear(); jobs.clear(); bots.forEach { it.ghost = null }
        val gen = generation
        bots().forEachIndexed { i, b ->
            val spot = P4_SPOTS.getValue(b.clazz)
            val land = {
                if (gen == generation) {
                    if (b.entity == null || b.entity!!.isRemoved) spawn(b, spot)
                    b.pos = spot; b.to = null; b.hold = false; b.yaw = 180f; b.pitch = 0f
                    b.heldKey = ""; setHeld(b, restingItem(b.clazz))
                    place(b)
                }
            }
            if (fromP3 && b.entity?.isRemoved == false) Fight.later(40 + 6 * i, "bot into P4") { land() } else land()
        }
    }

    private val P4_SPOTS = mapOf(
        DungeonClass.MAGE to Vec3(54.5, 64.0, 101.5), DungeonClass.HEALER to Vec3(59.5, 64.0, 111.5),
        DungeonClass.BERSERK to Vec3(50.5, 64.0, 110.5), DungeonClass.ARCHER to Vec3(47.5, 64.0, 107.5),
        DungeonClass.TANK to Vec3(61.5, 64.0, 117.5),
    )

    private fun spawn(b: Bot, at: Vec3) {
        val m = Mannequin(EntityTypes.MANNEQUIN, Sim.level)
        m.setComponent(DataComponents.PROFILE, ResolvableProfile.createResolved(profile(b)))
        m.setCustomName(Component.literal("§a${b.name}"))
        m.isCustomNameVisible = true
        m.isInvulnerable = true
        m.setNoGravity(true)
        dress(m, b.clazz)
        hideDescription(m)
        b.pos = at
        m.snapTo(at.x, at.y, at.z, 0f, 0f)
        b.entity = Sim.spawn(m)
        b.heldKey = ""
        setHeld(b, if (Fight.phase is GoldorPhase || Fight.phase is StormEnd) "breaker" else restingItem(b.clazz))
    }

    /**
     * Teammates' gear in P3 (party/gear.mjs, gear-sets.mjs: 57 players, 120 runs): a mask on the head
     * (Spirit Mask 36, Bonzo's Mask 11, other heads 10) and dyed leather - each player's own dyes,
     * mostly Storm's blues (chest #1793c4 / legs #17a8c4) with #8969c8 or #1cd4e4 boots. Each class
     * wears one set seen on that class.
     */
    private fun dress(m: Mannequin, c: DungeonClass) {
        m.setItemSlot(EquipmentSlot.HEAD, if (c == DungeonClass.BERSERK) Masks.BONZO_MASK else Masks.SPIRIT_MASK)
        val (chest, legs, boots) = when (c) {
            DungeonClass.HEALER -> Triple(0x1793c4, 0x17a8c4, 0x8969c8)
            DungeonClass.MAGE -> Triple(0x1793c4, 0x17a8c4, 0x1cd4e4)
            DungeonClass.TANK -> Triple(-1, 0x5d2fb9, 0x8969c8)
            DungeonClass.ARCHER -> Triple(0xe7413c, 0xe75c3c, 0x8969c8)
            else -> Triple(0x3e05af, 0x5d23d1, 0x7c44ec)
        }
        fun dyed(item: net.minecraft.world.item.Item, rgb: Int) =
            if (rgb < 0) ItemStack(Items.CHAINMAIL_CHESTPLATE) else ItemStack(item).also { it.set(DataComponents.DYED_COLOR, net.minecraft.world.item.component.DyedItemColor(rgb)) }
        m.setItemSlot(EquipmentSlot.CHEST, dyed(Items.LEATHER_CHESTPLATE, chest))
        m.setItemSlot(EquipmentSlot.LEGS, dyed(Items.LEATHER_LEGGINGS, legs))
        m.setItemSlot(EquipmentSlot.FEET, dyed(Items.LEATHER_BOOTS, boots))
    }

    /** A bot's profile: its skin (and its head in the leap menu). */
    fun profile(b: Bot) = GameProfile(UUID.nameUUIDFromBytes("p3sim:${b.name}".toByteArray()), b.name)

    private val hideMethod by lazy { Mannequin::class.java.getDeclaredMethod("setHideDescription", Boolean::class.javaPrimitiveType).apply { isAccessible = true } }
    private fun hideDescription(m: Mannequin) { runCatching { hideMethod.invoke(m, true) } }

    private fun place(b: Bot) {
        val e = b.entity ?: return
        e.snapTo(b.pos.x, b.pos.y, b.pos.z, b.yaw, b.pitch)
        e.yHeadRot = b.yaw; e.yBodyRot = b.yaw
    }

    private fun startPos(from: Int): Vec3 = when (from) {
        1 -> STANDS.getValue("S1 SS").add(Random.nextDouble(-1.5, 0.0), 0.0, Random.nextDouble(-1.5, 1.5))
        2 -> GoldorPhase.GATE_CENTRES[1].add(Random.nextDouble(-2.0, 2.0), -3.0, 3.0)
        3 -> GoldorPhase.GATE_CENTRES[2].add(-3.0, -3.0, Random.nextDouble(-2.0, 2.0))
        else -> STRIP.add(Random.nextDouble(-4.0, 4.0), 0.0, 0.0)
    }

    // ------------------------------------------------------------------ places

    /** Where a player stands to do each job (median from the recordings, terminal-roles.md). */
    val STANDS: Map<String, Vec3> = mapOf(
        "S1 T1" to Vec3(110.3, 113.0, 73.8), "S1 T2" to Vec3(109.1, 119.0, 79.6), "S1 T3" to Vec3(92.1, 112.0, 92.7), "S1 T4" to Vec3(92.5, 122.0, 100.5),
        "S1 east lever" to Vec3(106.9, 122.0, 111.7), "S1 west lever" to Vec3(95.4, 123.0625, 113.6), "S1 SS" to Vec3(108.3, 120.0, 94.0),
        "S2 T1" to Vec3(69.0, 109.0, 124.7), "S2 T2" to Vec3(59.7, 120.0, 125.3), "S2 T3" to Vec3(46.4, 109.0, 122.6), "S2 T4" to Vec3(39.2, 109.0, 140.5), "S2 T5" to Vec3(40.5, 124.0, 125.5),
        "S2 low lever" to Vec3(28.3, 123.0625, 128.7), "S2 high lever" to Vec3(24.5, 131.0625, 137.5), "S2 Lights" to Vec3(60.6, 132.0, 139.0),
        "S3 T1" to Vec3(0.0, 109.0, 112.2), "S3 T2" to Vec3(1.0, 119.0, 93.6), "S3 T3" to Vec3(16.5, 123.0, 93.7), "S3 T4" to Vec3(0.8, 109.0, 77.5),
        "S3 west lever" to Vec3(2.5, 122.0, 55.5), "S3 east lever" to Vec3(13.0, 121.0625, 55.7), "S3 Arrows" to Vec3(0.5, 120.0, 77.5),
        "S4 T1" to Vec3(41.3, 109.0, 32.6), "S4 T2" to Vec3(45.1, 121.0, 31.2), "S4 T3" to Vec3(67.1, 109.0, 33.1), "S4 T4" to Vec3(72.6, 115.0, 45.5),
        "S4 low lever" to Vec3(84.4, 121.0, 34.9), "S4 high lever" to Vec3(85.5, 127.0, 45.5), "S4 Target" to Vec3(63.5, 127.0, 35.5),
    )
    val GATES = arrayOf(Vec3.ZERO, Vec3(95.8, 123.9, 121.0), Vec3(19.3, 123.6, 127.9), Vec3(12.4, 116.8, 52.7))
    val STRIP = Vec3(54.6, 115.0, 51.5)
    val CORE_SPOT = Vec3(54.5, 115.0, 58.0)
    private val CORE_EE get() = eeOf(5)?.spot ?: STRIP
}
