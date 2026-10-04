package com.engineerclient.p3sim

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

    /** Bot walking speed, blocks a tick (sprinting at speed ~400 with turns and climbs). */
    private const val WALK = 0.95
    /** The fastest a bot gets anywhere it is late for (etherwarps and leaps), blocks a tick. */
    private const val FAST = 4.0

    class Bot(val clazz: DungeonClass, val slot: Int) {
        val name = Roles.label(clazz)
        var entity: Mannequin? = null
        var pos: Vec3 = Vec3.ZERO
        var yaw = 0f
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
    }

    private val bots = ArrayList<Bot>()

    /** The tick of the section in progress, for debug lines. */
    private var dbgN = 0
    private var dbgS = 0

    /** A debug line (the menu's Debug bots): section and seconds into it first. */
    private fun dbg(text: String) {
        if (!P3Sim.debugBots) return
        val sec = (dbgN - sectionN[dbgS.coerceIn(0, 5)]) / 20.0
        Sim.chat("§8[bots S$dbgS %.2f] §7$text".format(sec))
    }

    private fun Vec3.short() = "%.1f %.1f %.1f".format(x, y, z)

    /** Off to [to] (from n = [at], there by [due]; -1: walking pace), for [why]. */
    private fun go(b: Bot, to: Vec3, at: Int, due: Int, why: String) {
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
        bots.forEach { it.entity?.discard(); it.entity = null; it.to = null; it.due = -1; it.working = null; it.hold = false; it.inSection = 0 }
    }

    fun busyAt(st: Station) = bots.any { it.working === st }

    /** Everyone (the bots) inside [box]. */
    fun allIn(box: AABB) = !P3Sim.bots || bots.all { it.entity == null || box.contains(it.pos) }

    // ------------------------------------------------------------------ P3

    /** A bot's job: [job] (a station id or "gate k") done [sec] s into section [timeSection]; [at]: that n, once the section has started. */
    private class Job(val job: String, val bot: Bot, val timeSection: Int, var sec: Double) { var at = -1 }

    private val jobs = ArrayList<Job>()
    /** Leaps queued: [bot] onto wherever [onto] is at n = [at]. */
    private class Leap(val bot: Bot, val at: Int, val onto: () -> Vec3?)
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
    private var lastLeap = 0
    private val sectionN = IntArray(6)

    fun startP3(phase: GoldorPhase) {
        clear()
        youArrived.fill(false); eeArrived.fill(false); preleapAt.fill(-1); eeSpotBy.fill(-1); holdJob.fill(null); waitsFor.fill(null); youOn.fill(false); holdNoted.fill(false)
        planned = 0
        lastLeap = 0
        if (!P3Sim.bots) return
        val from = phase.from.coerceIn(1, 5)
        // No early enter into the section you start in (or before).
        for (i in 0..from) eeArrived[i] = true
        val plan = P3Plan.plan()
        bots()
        // Every job that isn't yours: its role's bot (a stack: the first listed), or the least busy one.
        for (job in P3Plan.allJobs()) {
            if (P3Plan.isMine(job)) continue
            val st = phase.stations.firstOrNull { it.id == job }
            if (st?.done == true) continue
            val s = st?.section ?: job.removePrefix("gate ").toIntOrNull() ?: continue
            if (s < from) continue
            var (ts, sec) = plan.times[job] ?: (s to 5.0)
            val bot = plan.owners[job]?.firstNotNullOfOrNull { c -> botOf(c) }
                ?: bots.minBy { b -> jobs.count { it.bot === b && it.timeSection == ts } }
            if (P3Plan.skill == P3Plan.RANDOM) sec = P3Plan.botMin + Random.nextDouble() * (P3Plan.botMax - P3Plan.botMin).coerceAtLeast(0.0)
            if (ts < from) { ts = from; sec = 0.5 }
            jobs += Job(job, bot, ts, sec)
        }
        spread(plan)
        for (b in bots) {
            b.inSection = from
            val core = plan.ee[5] == b.clazz && from >= 3
            if (core) b.inSection = 5
            val first = next(b)?.takeIf { sectionOf(it.job) == from }
            spawn(b, when {
                core -> CORE_EE.add(Random.nextDouble(-1.0, 1.0), 0.0, 0.0)
                from == 1 -> startPos(1)
                first != null -> spotOf(first.job)
                else -> P3Plan.ee(from)?.spot ?: startPos(from)
            })
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
        if (!P3Sim.bots) return
        val n = phase.n
        val s = phase.section
        dbgN = n; dbgS = s
        if (P3Sim.debugBots && n % 40 == 0) dbgHolds(phase)
        if (s != planned) { planned = s; sectionN[s.coerceIn(0, 5)] = n; dbg("§bsection $s starts"); sectionStarted(phase, s) }
        // Jobs someone else (you, on a stack) already did.
        jobs.removeAll { j -> phase.stations.firstOrNull { it.id == j.job }?.done == true || (j.job.startsWith("gate") && phase.gateIsDown(sectionOf(j.job))) }
        youAtEarlyEnter(phase)
        // Leaps that are due.
        leaps.filter { n >= it.at }.forEach { l ->
            leaps.remove(l)
            l.onto()?.let { dbg("§e${l.bot.name}§7 leaps (${it.short()})"); l.bot.pos = it; l.bot.to = null; walkOn(l.bot, n) }
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
                if (st.kind == Station.Kind.TERMINAL && Terminals.inUse(st)) return@removeAll false
                if (st.kind == Station.Kind.LEVER) phase.pullLever(st, j.bot.name) else st.complete(j.bot.name)
                if (st.kind == Station.Kind.DEVICE) phase.devices.shownDone(st.label)
                if (!st.done) return@removeAll false
            } else {
                val k = sectionOf(j.job)
                if (!phase.gateIsDown(k) && !phase.blowGate(k, j.bot.name)) return@removeAll false
            }
            dbg("§e${j.bot.name}§7 did ${j.job}")
            j.bot.working = null
            finished += j.bot
            true
        }
        finished.forEach { walkOn(it, n) }
        earlyEnterBots(phase)
        preleaps(phase)
        releaseEarlyEnterers(phase)
        for (b in bots) move(b, n)
        // Working: at its terminal for the last 2 s before it's done.
        for (b in bots) b.working = next(b)?.takeIf { it.at >= 0 && it.at - n <= 40 }?.let { j -> phase.stations.firstOrNull { it.id == j.job && it.kind == Station.Kind.TERMINAL } }
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
                "leaps" -> botOf(Roles.whoIs(plan, m.who))?.let { b ->
                    if (at != null) leaps += Leap(b, at) {
                        // Back into the section: onto someone still working in it (else you).
                        bots.firstOrNull { it !== b && it.inSection == s && busy(it) }?.pos ?: Sim.player?.position()
                    }
                }
            }
        }
        if (s >= 5) { core(phase); return }
        val ee = P3Plan.ee(s)
        val eeBot = ee?.takeIf { !it.byYou && s > phase.from }?.let { botOf(it.owner) }
        if (eeBot != null && !eeArrived[s]) dbg("§e${eeBot.name}§7 isn't on its ${ee.label} spot yet: going on there, the others leap to it")
        val onto = eeBot
        var i = 0
        for (b in bots) {
            if (b.hold && b !== onto) dbg("§e${b.name}§7 stops holding (section $s started)")
            b.hold = false
            if (b === onto) { b.hold = eeArrived[s]; continue }
            if (b.inSection >= s) { walkOn(b, n); continue }
            b.inSection = s
            if (onto != null) leaps += Leap(b, n + 2 + gapTicks() * i++) { onto.pos }
            else walkOn(b, n)
        }
    }

    /** [b] early-enters section [s] (in progress) and isn't on its spot yet. */
    private fun lateForEe(b: Bot, s: Int): Boolean {
        if (s > 4 || eeArrived[s]) return false
        val ee = P3Plan.ee(s)?.takeIf { !it.byYou } ?: return false
        return botOf(ee.owner) === b
    }

    /** "ee2" -> 2, "core" -> 5. */
    private fun into(token: String) = if (token == "core") 5 else token.removePrefix("ee").toIntOrNull()

    /** The bot early-entering into section [into] walks to its spot once its jobs before are done; it's in when it's there. */
    private fun earlyEnterBots(phase: GoldorPhase) {
        val s = phase.section
        // The section in progress too: an early enterer that didn't make it before its section started still goes and waits.
        for (into in s..5) {
            if (into == s && (into > 4 || eeArrived[into])) continue
            val ee = P3Plan.ee(into) ?: continue
            if (ee.byYou) continue
            val b = botOf(ee.owner) ?: continue
            if (into > s && (b.inSection >= into || busy(b) || b.hold)) continue
            // Not before its jobs in the sections before (the EE3 bot does its S2 ones first, not straight from S1).
            if (jobs.any { it.bot === b && (sectionOf(it.job) < into || it.timeSection < into) }) continue
            if (b.pos.distanceTo(ee.spot) < 0.5) {
                b.inSection = into; eeArrived[into] = true; b.hold = into <= 4
                if (P3Sim.debugBots) dbg("§a${b.name} is on its ${ee.label} spot§7${if (b.hold) ", holding" else ""}") else Sim.note("§e${b.name}§7 is on ${ee.label}.")
                continue
            }
            if (b.to != ee.spot) {
                // On the spot by the preset's time (no earlier than walking there takes).
                val walk = (b.pos.distanceTo(ee.spot) / WALK).toInt()
                go(b, ee.spot, if (eeSpotBy[into] >= 0) (eeSpotBy[into] - walk).coerceAtLeast(phase.n) else phase.n, eeSpotBy[into], "its ${ee.label} spot")
            }
            break
        }
    }

    /** The pre-leap onto the early enterer of the next section: everyone free, one after another; the busy ones after their last job. */
    private fun preleaps(phase: GoldorPhase) {
        val n = phase.n
        val into = phase.section + 1
        if (into > 4) return
        val ee = P3Plan.ee(into) ?: return
        val eeBot = if (ee.byYou) null else botOf(ee.owner)
        val open = if (ee.byYou) youArrived[into] else eeBot != null && eeArrived[into] &&
            n >= (if (preleapAt[into] >= 0) preleapAt[into] else 0)
        if (!open) return
        val target: () -> Vec3? = if (eeBot != null) ({ eeBot.pos }) else ({ Sim.player?.position() })
        for (b in bots) {
            if (b === eeBot || b.inSection >= into || busy(b)) continue
            b.inSection = into
            lastLeap = maxOf(n + 1, lastLeap + if (eeBot != null) 2 else gapTicks())
            if (holdJob[into] != null && jobs.any { it.bot === b && it.job == holdJob[into] }) b.hold = true
            leaps += Leap(b, lastLeap, target)
        }
    }

    /**
     * An early enterer (a bot) moves on from its spot once everyone it waits for has leapt onto it
     * (the preset's "waits"; else everyone, you included); at the latest 10 s into the section it entered.
     */
    private fun releaseEarlyEnterers(phase: GoldorPhase) {
        val n = phase.n
        val s = phase.section
        // You're on an early enterer once you've been on it after its jobs before (on its way there counts).
        for (into in s..(s + 1).coerceAtMost(4)) {
            val b = P3Plan.ee(into)?.takeIf { !it.byYou }?.let { botOf(it.owner) } ?: continue
            if (youOn[into] || jobs.any { it.bot === b && (sectionOf(it.job) < into || it.timeSection < into) }) continue
            if (Sim.player?.position()?.let { it.distanceTo(b.pos) < 3.0 } == true) { youOn[into] = true; dbg("§ayou're on ${b.name}§7 (EE$into)") }
        }
        for (into in s..(s + 1).coerceAtMost(4)) {
            val ee = P3Plan.ee(into)?.takeIf { !it.byYou } ?: continue
            val b = botOf(ee.owner) ?: continue
            if (!b.hold || !eeArrived[into] || b.inSection < into) continue
            val waits = waitsFor[into]
            val ok = when {
                // Never without you (a safety net at 30 s); the bots get 10 s.
                into == s && n - sectionN[s] >= 600 -> true
                !leapt(P3Sim.myClass, b, into) -> false
                into == s && n - sectionN[s] >= 200 -> true
                waits != null -> waits.all { t -> leapt(whoFor(t, into), b, into) }
                // No list: everyone - every bot (busy ones too, once they're done and leapt) and you.
                else -> (into == s || preleapOpen(into, n)) &&
                    bots.all { it === b || leapt(it.clazz, b, into) } && leapt(P3Sim.myClass, b, into)
            }
            if (ok) { dbg("§c${b.name} moves on from EE$into§7: ${waitStatus(b, into)}${if (into == s) ", ${(n - sectionN[s]) / 20.0}s into S$s" else ""}"); b.hold = false; walkOn(b, n) }
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
            val ee = (1..5).firstOrNull { P3Plan.ee(it)?.owner == b.clazz }
            val state = when {
                b.hold -> "§6holding§7 (EE$ee: ${ee?.let { waitStatus(b, it) } ?: "?"})"
                b.to != null -> "moving to ${b.to!!.short()}"
                else -> "standing"
            }
            dbg("§e${b.name}§7 in S${b.inSection}: $state; next ${next(b)?.job ?: "none"}")
        }
    }

    /** "3" -> who does S[into] T3; "ee3" / "core" -> who early-enters there. */
    private fun whoFor(token: String, into: Int): DungeonClass? =
        if (token.all { it.isDigit() }) P3Plan.doer("S$into T$token") else P3Plan.plan().ee[into(token) ?: return null]

    /** [c] has leapt onto [onto] (you: been within 3 blocks of it). */
    private fun leapt(c: DungeonClass?, onto: Bot, into: Int): Boolean = when {
        c == null || c == onto.clazz -> true
        c == P3Sim.myClass -> youOn[into]
        else -> botOf(c)?.let { it.inSection >= into && leaps.none { l -> l.bot === it } } ?: true
    }

    private fun preleapOpen(into: Int, n: Int) = n >= (if (preleapAt[into] >= 0) preleapAt[into] else 0)

    /** You're at your early enter: the party leaps onto you (pre-leaps() does it). */
    private fun youAtEarlyEnter(phase: GoldorPhase) {
        val into = phase.section + 1
        if (into > 4) return
        val ee = P3Plan.ee(into)?.takeIf { it.byYou } ?: return
        if (youArrived[into]) return
        val p = Sim.player ?: return
        if (p.position().distanceTo(ee.spot) > 3.0) return
        youArrived[into] = true
        Sim.note("§aAt your ${ee.label}§7: the party leaps to you.")
    }

    /** Is a section's last job held for you (you early-enter the next one and aren't there yet). */
    private fun holding(phase: GoldorPhase): Boolean {
        if (!P3Plan.waitForYou) return false
        val ee = P3Plan.ee(phase.section + 1)?.takeIf { it.byYou && it.into <= 4 } ?: return false
        if (youArrived[ee.into]) return false
        if (!holdNoted[ee.into] && phase.stations.count { it.section == phase.section && !it.done } == 1) {
            holdNoted[ee.into] = true
            Sim.note("§7The party holds S${phase.section}'s last job until you're at your §e${ee.label}§7 spot (Wait for you, in the menu's Early Enters).")
        }
        return true
    }

    /** The core: everyone leaps in at once onto whoever's in it (the core early enterer), or walks in. */
    private fun core(phase: GoldorPhase) {
        val n = phase.n
        val ee = P3Plan.ee(5)
        val onto: (() -> Vec3?)? = when {
            ee == null -> null
            ee.byYou -> Sim.player?.takeIf { GoldorPhase.CORE_BOX.inflate(4.0).contains(it.position()) }?.let { p -> { p.position() } }
            else -> botOf(ee.owner)?.let { b -> { b.pos } }
        }
        bots.forEachIndexed { i, b ->
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
        if (b.hold || lateForEe(b, planned)) return
        val j = next(b) ?: return
        if (j.at >= 0 || sectionOf(j.job) <= b.inSection) go(b, spotOf(j.job), n, j.at, "${j.job}${if (j.at < 0) " (its section not started)" else ""}")
    }

    private fun sectionOf(job: String) = job.removePrefix("gate ").toIntOrNull() ?: job.substring(1, 2).toInt()

    private fun spotOf(job: String): Vec3 = STANDS[job] ?: job.removePrefix("gate ").toIntOrNull()?.let { GATES.getOrNull(it) } ?: CORE_SPOT

    private fun move(b: Bot, n: Int) {
        val to = b.to
        if (to != null && n >= b.goAt) {
            val d = to.subtract(b.pos)
            val len = d.length()
            // Walking, unless it has to be there sooner (then as fast as that takes, up to etherwarp pace).
            val step = (if (b.due > n) maxOf(WALK, len / (b.due - n)) else if (b.due >= 0) FAST else WALK).coerceAtMost(FAST)
            if (len <= step) { b.pos = to; b.to = null } else b.pos = b.pos.add(d.scale(step / len))
            if (Math.abs(d.x) + Math.abs(d.z) > 0.01) b.yaw = Math.toDegrees(Math.atan2(-d.x, d.z)).toFloat()
        }
        place(b)
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

    private fun spawn(b: Bot, at: Vec3) {
        val m = Mannequin(EntityType.MANNEQUIN, Sim.level)
        m.setComponent(DataComponents.PROFILE, ResolvableProfile.createResolved(GameProfile(UUID.nameUUIDFromBytes("p3sim:${b.name}".toByteArray()), b.name)))
        m.setCustomName(Component.literal("§a${b.name}"))
        m.isCustomNameVisible = true
        m.isInvulnerable = true
        m.setNoGravity(true)
        m.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(if (b.clazz == DungeonClass.ARCHER || b.clazz == DungeonClass.BERSERK) Items.BOW else Items.DIAMOND_PICKAXE))
        hideDescription(m)
        b.pos = at
        m.snapTo(at.x, at.y, at.z, 0f, 0f)
        b.entity = Sim.spawn(m)
    }

    private val hideMethod by lazy { Mannequin::class.java.getDeclaredMethod("setHideDescription", Boolean::class.javaPrimitiveType).apply { isAccessible = true } }
    private fun hideDescription(m: Mannequin) { runCatching { hideMethod.invoke(m, true) } }

    private fun place(b: Bot) {
        val e = b.entity ?: return
        e.snapTo(b.pos.x, b.pos.y, b.pos.z, b.yaw, 0f)
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
        "S1 T1" to Vec3(110.3, 113.0, 73.8), "S1 T2" to Vec3(109.1, 119.0, 79.6), "S1 T3" to Vec3(92.1, 112.0, 92.7), "S1 T4" to Vec3(92.5, 121.5, 100.5),
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
    private val CORE_EE get() = P3Plan.ee(5)?.spot ?: STRIP
}
