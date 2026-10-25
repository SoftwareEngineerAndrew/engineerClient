package com.devgineerclient.party

import com.devgineerclient.DevgineerClient
import com.devgineerclient.posepov.PoseShare
import com.engineerclient.p3sim.Station
import com.engineerclient.practice.OdinSimonSays
import com.engineerclient.practice.TermInfo
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.drawText
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.render.textDim
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.M7Phases
import com.odtheking.odin.utils.skyblock.dungeon.terminals.TerminalTypes
import com.odtheking.odin.utils.skyblock.dungeon.terminals.TerminalUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.phys.Vec3
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Teammates' P3 terminals and Simon Says as they do them, over Pose POV's party link ([PoseShare.shareExtra]): which
 * terminal each has open, how many clicks it has left, and how far Simon Says has got, with a countdown to when each should
 * be done. A label over each terminal (seen through walls) and two HUDs: a name, a bar and the countdown.
 *
 * The countdown goes through the task a step at a time (a terminal's first click, then each click after; Simon Says' shows
 * and presses), each step as long as that player usually takes over it ([PartySkill]). It runs down in real time through
 * the step in progress and waits at that step's end until the step is done: a click that comes early skips it down, one
 * that comes late holds it. It never goes up, unless the task goes back (a Simon Says fail).
 *
 * Every game in P3 sends its own (this on or not, as Pose POV's sharing does), small frames on each change and once a
 * second while it lasts:
 *  - kind 7, a terminal: [7][terminal 0-16, 255 closed][Odin's type][clicks left][clicks in all][tenths of a second open]
 *    (Melody: 0 and 0, it isn't clicks);
 *  - kind 8, Simon Says: [8][rounds done 0-4][presses into the next][tenths of a second since the first start, u16].
 * What arrives is kept only as far as it could be real: a terminal of the 17, a type Odin has, no more clicks than a
 * terminal takes, a round and press count Simon Says can have; it is never shown as text a sender wrote.
 */
object PartyProgress : Module(
    name = "Party Progress",
    category = Category.custom("Devgineer Client", 860, 10),
    description = "Teammates' terminals and Simon Says as they do them (shared over Pose POV's party link), with a countdown to when each should be done.",
    key = null,
    toggled = true,
) {
    private val labels by BooleanSetting("Terminal Labels", true, desc = "Over each terminal a teammate has open: who, and the countdown to when it should be done. Seen through walls.")
    private val labelSize by NumberSetting("Label Size", 1.5f, 0.5..4.0, 0.1f, desc = "How big the terminal labels are.")
    private val predictions by BooleanSetting("Countdown", true, desc = "A countdown to when each terminal and Simon Says should be done, from that player's own history in ADR runs (config/devgineerclient/party-skill.json), or everyone's. It skips down when they're quicker than that and holds when they're slower.")
    private val showSelf by BooleanSetting("Show Yourself", false, desc = "Your own terminal and Simon Says too.")

    private val termHud by HUD("Terminal Progress", "Each teammate on a terminal: their name, how far it has got and the countdown.", true, 10, 200, 1f) { example ->
        drawTerms(this, example)
    }
    private val ssHud by HUD("Simon Says Progress", "Who's on Simon Says, how far it has got and the countdown.", true, 10, 250, 1f) { example ->
        drawSs(this, example)
    }

    private const val STALE_NS = 2_500_000_000L
    private const val RESEND_MS = 1000L
    private const val REACH = 6.0
    private const val SS_RANGE = 10.0
    private val SS_DEVICE = Vec3(110.5, 121.5, 93.5)
    private const val SS_ROUNDS = 4
    private const val SS_PRESSES = 10  // 1 + 2 + 3 + 4
    private const val TICK_MS = 50.0
    /** Everyone's gap between Simon Says presses when the table has none. */
    private const val PRESS_MS = 300.0

    /** The 17 terminals, in Station's order (section by section): where each is. */
    private val TERMINALS by lazy { Station.all().filter { it.kind == Station.Kind.TERMINAL }.map { it.at } }

    /** A teammate's open terminal, as last heard; [openedAt] is when they opened it and [stepAt] when it last moved on, on our clock. */
    private class Term(val terminal: Int, val type: TerminalTypes, val left: Int, val total: Int, val openedAt: Long, val stepAt: Long, val at: Long) {
        val done get() = total - left
    }
    /** A teammate on Simon Says: rounds done, presses into the next, since when, and when it last moved on (our clock). */
    private class Ss(val rounds: Int, val pressed: Int, val startedAt: Long, val stepAt: Long, val at: Long) {
        val done get() = rounds * (rounds + 1) / 2 + pressed
    }

    private val terms = ConcurrentHashMap<String, Term>()
    private val sses = ConcurrentHashMap<String, Ss>()

    init {
        ClientTickEvents.END_CLIENT_TICK.register { DevgineerClient.safely("party progress") { tick() } }
        on<RenderExtractEvent> { DevgineerClient.safely("party progress labels") { if (labels) drawLabels(this) } }
    }

    // ------------------------------------------------------------------ what's sent

    private var sentTerm: Any? = null
    private var termMax = 0
    private var termFrame: ByteArray? = null
    private var termAt = 0L
    private var ssStart = 0L
    private var ssRounds = 0
    private var ssShown = 0
    private var ssPressed = 0
    private var ssFrame: ByteArray? = null
    private var ssAt = 0L

    // For the log line at P3's end: whether this works with a party (frames out, the most teammates one reached directly,
    // the most linked, and what came in from whom).
    private var inP3 = false
    private var framesOut = 0
    private var mostDirect = 0
    private var mostLinked = 0
    private val heardFrom = ConcurrentHashMap<String, Int>()

    private fun share(frame: ByteArray) {
        framesOut++
        mostDirect = maxOf(mostDirect, PoseShare.shareExtra(frame))
        mostLinked = maxOf(mostLinked, PoseShare.linkedCount)
    }

    private fun report() {
        val heard = heardFrom.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.value} from ${it.key}" }.ifEmpty { "nothing" }
        val relay = if (PoseShare.relayTakes(7)) "the relay takes them too" else "the relay doesn't take them yet, peer-to-peer only"
        DevgineerClient.logger.info("[party progress] P3: $framesOut frames out, peer-to-peer reached up to $mostDirect of up to $mostLinked linked teammates ($relay); heard $heard")
    }

    private fun tick() {
        val me = mc.player
        if (me == null || !DungeonUtils.inBoss || DungeonUtils.getF7Phase() != M7Phases.P3) {
            if (inP3) { inP3 = false; DevgineerClient.safely("party progress report") { report() } }
            terms.clear(); sses.clear(); countdowns.clear()
            sentTerm = null; termFrame = null; ssStart = 0L; ssRounds = 0; ssShown = 0; ssFrame = null
            return
        }
        if (!inP3) { inP3 = true; framesOut = 0; mostDirect = 0; mostLinked = 0; heardFrom.clear() }
        val now = System.nanoTime()
        val nowMs = System.currentTimeMillis()
        terms.values.removeIf { now - it.at > STALE_NS }
        sses.values.removeIf { now - it.at > STALE_NS }
        if (TermInfo.deviceDone(1)) sses.clear()
        countdowns.keys.removeIf { k -> if (k.startsWith("t:")) !terms.containsKey(k.substring(2)) else !sses.containsKey(k.substring(2)) }
        val myName = me.name.string.lowercase()

        // Your terminal: the one you're by while Odin has one open.
        val term = TerminalUtils.currentTerm
        val near = TERMINALS.indices.minByOrNull { TERMINALS[it].distanceToSqr(me.position()) }?.takeIf { TERMINALS[it].distanceTo(me.position()) <= REACH }
        if (term != null && near != null && me.containerMenu !== me.inventoryMenu) {
            val melody = term.type == TerminalTypes.MELODY
            val left = if (melody) 0 else term.solution.size.coerceIn(0, 40)
            if (term !== sentTerm) { sentTerm = term; termMax = 0 }
            termMax = maxOf(termMax, left)
            val total = if (melody) 0 else termMax
            val open = ((nowMs - term.timeOpened) / 100).coerceIn(0, 255)
            val f = byteArrayOf(7, near.toByte(), term.type.ordinal.toByte(), left.toByte(), total.toByte(), open.toByte())
            if (!f.copyOf(5).contentEquals(termFrame?.copyOf(5)) || nowMs - termAt > RESEND_MS) { termFrame = f; termAt = nowMs; share(f) }
            if (showSelf) put(terms, myName) { was -> termOf(near, term.type, left, total, now - (nowMs - term.timeOpened) * 1_000_000L, was, now) } else terms.remove(myName)
        } else if (sentTerm != null) {
            sentTerm = null; termFrame = null
            share(byteArrayOf(7, 255.toByte(), 0, 0, 0, 0))
            terms.remove(myName)
        }

        // Simon Says, while you're at it and section 1's device isn't done: rounds done and presses into the next, from
        // engineerClient's solver.
        if (!TermInfo.deviceDone(1) && OdinSimonSays.active() && me.position().distanceTo(SS_DEVICE) <= SS_RANGE) {
            val shown = OdinSimonSays.shownRound
            val pressed = OdinSimonSays.pressedInRound
            // The round's lights went (or a new show began) before all of it was pressed: a fail or a restart.
            if (shown < ssShown && ssPressed < ssShown) ssRounds = 0
            if (shown > 0) {
                if (ssStart == 0L) ssStart = nowMs
                if (pressed >= shown && shown > ssRounds) ssRounds = shown.coerceAtMost(SS_ROUNDS)
            }
            ssShown = shown; ssPressed = pressed
            if (ssStart != 0L) {
                val into = if (shown > ssRounds) pressed.coerceIn(0, shown) else 0
                val since = ((nowMs - ssStart) / 100).coerceIn(0, 65535)
                val f = byteArrayOf(8, ssRounds.toByte(), into.toByte(), (since shr 8).toByte(), since.toByte())
                if (!f.copyOf(3).contentEquals(ssFrame?.copyOf(3)) || nowMs - ssAt > RESEND_MS) { ssFrame = f; ssAt = nowMs; share(f) }
                if (showSelf) put(sses, myName) { was -> ssOf(ssRounds, into, now - (nowMs - ssStart) * 1_000_000L, was, now) } else sses.remove(myName)
            }
        } else sses.remove(myName)
    }

    // ------------------------------------------------------------------ what's heard

    /** A kind 7 or 8 frame from linked teammate [sender] (lower case), [b] just past the kind; from PoseShare's threads. */
    fun heard(sender: String, kind: Int, b: ByteBuffer) {
        val now = System.nanoTime()
        heardFrom.merge(sender, 1, Int::plus)
        when (kind) {
            7 -> {
                if (b.remaining() != 5) return
                val terminal = b.get().toInt() and 255
                if (terminal == 255) { terms.remove(sender); return }
                val type = TerminalTypes.entries.getOrNull(b.get().toInt() and 255) ?: return
                val left = b.get().toInt() and 255
                val total = b.get().toInt() and 255
                val open = b.get().toInt() and 255
                if (terminal >= TERMINALS.size || total > 40 || left > total || (type == TerminalTypes.MELODY && total != 0)) return
                put(terms, sender) { was -> termOf(terminal, type, left, total, now - open * 100_000_000L, was, now) }
            }
            8 -> {
                if (b.remaining() != 4) return
                val rounds = b.get().toInt() and 255
                val pressed = b.get().toInt() and 255
                val since = ((b.get().toInt() and 255) shl 8) or (b.get().toInt() and 255)
                if (rounds > SS_ROUNDS || pressed > SS_ROUNDS || (rounds < SS_ROUNDS && pressed > rounds + 1)) return
                put(sses, sender) { was -> ssOf(rounds, pressed, now - since * 100_000_000L, was, now) }
            }
        }
    }

    /** [name]'s entry made again from what it was. */
    private inline fun <T : Any> put(map: ConcurrentHashMap<String, T>, name: String, make: (T?) -> T) { map[name] = make(map[name]) }

    /** The terminal as now heard: the same one as before keeps its open time (the first frame's, as later ones come late by however long they took) and when it last moved on. */
    private fun termOf(terminal: Int, type: TerminalTypes, left: Int, total: Int, openedAt: Long, was: Term?, now: Long): Term {
        val same = was?.takeIf { it.terminal == terminal && it.type == type && kotlin.math.abs(it.openedAt - openedAt) < 1_500_000_000L }
        val opened = same?.openedAt ?: openedAt
        val stepAt = when {
            same == null -> if (total - left > 0) now else opened
            total - left != same.done -> now
            else -> same.stepAt
        }
        return Term(terminal, type, left, total, opened, stepAt, now)
    }

    private fun ssOf(rounds: Int, pressed: Int, startedAt: Long, was: Ss?, now: Long): Ss {
        val same = was?.takeIf { kotlin.math.abs(it.startedAt - startedAt) < 1_500_000_000L }
        val started = same?.startedAt ?: startedAt
        val done = rounds * (rounds + 1) / 2 + pressed
        val stepAt = when {
            same == null -> if (done > 0) now else started
            done != same.done -> now
            else -> same.stepAt
        }
        return Ss(rounds, pressed, started, stepAt, now)
    }

    // ------------------------------------------------------------------ the countdown

    /**
     * One task's countdown: its steps (ms each, worked out once from [PartySkill]) and what it shows. [id] tells a new task
     * from the one before (another terminal, or Simon Says started over).
     */
    private class Countdown(val id: String, val steps: DoubleArray) {
        var shownMs = Double.MAX_VALUE
        var done = 0
    }
    private val countdowns = ConcurrentHashMap<String, Countdown>()

    /**
     * Ms to show for [key]: through the step in progress (from [stepAt]) and the steps after it, waiting at the step's end
     * until it's done; never more than shown before unless the task went back.
     */
    private fun countdown(key: String, id: String, done: Int, stepAt: Long, now: Long, steps: () -> DoubleArray): Pair<Double, Boolean> {
        val c = countdowns[key]?.takeIf { it.id == id } ?: Countdown(id, steps()).also { countdowns[key] = it }
        val s = c.steps
        var after = 0.0
        for (i in done + 1 until s.size) after += s[i]
        val inStep = if (done < s.size) s[done] - (now - stepAt) / 1_000_000.0 else 0.0
        val waiting = inStep <= 0.0 && done < s.size
        val left = after + inStep.coerceAtLeast(0.0)
        c.shownMs = if (done < c.done) left else minOf(c.shownMs, left)
        c.done = done
        return c.shownMs to waiting
    }

    /** A terminal's steps: the first click, then each click after (Melody, which isn't clicks, as one step). */
    private fun termSteps(name: String, t: Term): DoubleArray {
        val solve = PartySkill.solveMs(name, t.type) ?: 2000.0
        if (t.type == TerminalTypes.MELODY || t.total == 0) return doubleArrayOf(solve)
        val first = PartySkill.firstMs(name, t.type) ?: (solve * 0.3)
        val click = PartySkill.clickMs(name, t.type) ?: (solve * 0.7 / maxOf(1, t.total - 1))
        return DoubleArray(t.total) { if (it == 0) first else click }
    }

    /**
     * Simon Says' steps, as the device runs: each round's show (round 1 four ticks after the start press, each later one six
     * after its last press; a lamp every 8 ticks; the buttons back 10 after the last) and its first press as one step, then
     * each press after; presses as quick as that player usually is.
     */
    private fun ssSteps(name: String): DoubleArray {
        val press = PartySkill.pressMs(name) ?: PRESS_MS
        val out = ArrayList<Double>(SS_PRESSES)
        for (r in 1..SS_ROUNDS) {
            val show = ((if (r == 1) 4 else 6) + 8 * (r - 1) + 10) * TICK_MS
            out += show + press
            repeat(r - 1) { out += press }
        }
        return out.toDoubleArray()
    }

    private fun termCountdown(name: String, t: Term, now: Long) =
        countdown("t:$name", "${t.terminal}@${t.openedAt}/${t.total}", if (t.total == 0) 0 else t.done, t.stepAt, now) { termSteps(name, t) }

    private fun ssCountdown(name: String, s: Ss, now: Long) =
        countdown("s:$name", "${s.startedAt}", s.done, s.stepAt, now) { ssSteps(name) }

    /** "1.4", yellow while counting, orange while it waits on a step that's late. */
    private fun clock(c: Pair<Double, Boolean>) = (if (c.second) "§6" else "§e") + String.format(java.util.Locale.ROOT, "%.1f", c.first / 1000.0)

    // ------------------------------------------------------------------ drawing

    /** Their name as the game spells it (what's heard is lower case). */
    private fun shown(name: String): String = DungeonUtils.dungeonTeammates.firstOrNull { it.name.equals(name, true) }?.name ?: name

    private fun drawLabels(ev: RenderExtractEvent) {
        if (terms.isEmpty()) return
        val now = System.nanoTime()
        for ((name, t) in terms) {
            val at = TERMINALS.getOrNull(t.terminal) ?: continue
            val text = if (predictions) "§b${shown(name)} ${clock(termCountdown(name, t, now))}" else "§b${shown(name)}"
            ev.drawText(text, Vec3(at.x, at.y + 2.9, at.z), labelSize, false)
        }
    }

    private const val BAR_W = 40
    private const val ROW_H = 10

    /** Rows of a name, a bar ([fraction] filled) and an optional clock, the names in one column. */
    private fun rows(gfx: GuiGraphicsExtractor, rows: List<Triple<String, Double, String?>>): Pair<Int, Int> {
        if (rows.isEmpty()) return 0 to 0
        val nameW = rows.maxOf { gfx.textDim(it.first, 0, 0, Colors.WHITE).first }
        var w = 0
        rows.forEachIndexed { i, (name, fraction, clock) ->
            val y = i * ROW_H
            gfx.text(name, 0, y, Colors.WHITE)
            val bx = nameW + 4
            gfx.fill(bx, y + 2, bx + BAR_W, y + 7, 0x80000000.toInt())
            gfx.fill(bx, y + 2, bx + (BAR_W * fraction.coerceIn(0.0, 1.0)).toInt(), y + 7, 0xFF55FF55.toInt())
            var end = bx + BAR_W
            if (clock != null) {
                gfx.text(clock, end + 4, y, Colors.WHITE)
                end += 4 + gfx.textDim(clock, end + 4, y, Colors.WHITE).first
            }
            w = maxOf(w, end)
        }
        return w to rows.size * ROW_H
    }

    private fun drawTerms(gfx: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int> {
        if (example) return rows(gfx, listOf(Triple("§bSoloF7", 0.6, "§e0.9"), Triple("§bTheBadOne", 0.3, "§63.2")))
        if (terms.isEmpty()) return 0 to 0
        val now = System.nanoTime()
        return rows(gfx, terms.entries.sortedBy { it.value.terminal }.map { (name, t) ->
            val c = if (predictions) termCountdown(name, t, now) else null
            val fraction = when {
                t.total > 0 -> t.done.toDouble() / t.total
                // Melody isn't clicks: how far through the time it usually takes.
                else -> (now - t.openedAt) / 1e6 / (PartySkill.solveMs(name, t.type) ?: 5000.0)
            }
            Triple("§b${shown(name)}", fraction, c?.let { clock(it) })
        })
    }

    private fun drawSs(gfx: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int> {
        if (example) return rows(gfx, listOf(Triple("§7SS §bp3wr", 0.6, "§e3.1")))
        val (name, s) = sses.entries.maxByOrNull { it.value.done } ?: return 0 to 0
        val c = if (predictions) ssCountdown(name, s, System.nanoTime()) else null
        return rows(gfx, listOf(Triple("§7SS §b${shown(name)}", s.done.toDouble() / SS_PRESSES, c?.let { clock(it) })))
    }
}
