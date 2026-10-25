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
 * terminal each has open, how many clicks it has left, and how far Simon Says has got, with when each should be done
 * from that player's own history ([PartySkill]). A label over each terminal (seen through walls) and two HUDs.
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
    description = "Teammates' terminals and Simon Says as they do them (shared over Pose POV's party link), with when each should be done from that player's history.",
    key = null,
    toggled = true,
) {
    private val labels by BooleanSetting("Terminal Labels", true, desc = "Over each terminal a teammate has open: who, the clicks left and when it should be done. Seen through walls.")
    private val labelSize by NumberSetting("Label Size", 1.5f, 0.5..4.0, 0.1f, desc = "How big the terminal labels are.")
    private val predictions by BooleanSetting("Predictions", true, desc = "When each terminal and Simon Says should be done: that player's own history from ADR runs (config/devgineerclient/party-skill.json), or everyone's, then their pace so far.")
    private val showSelf by BooleanSetting("Show Yourself", false, desc = "Your own terminal and Simon Says too.")

    private val termHud by HUD("Terminal Progress", "Each teammate's open terminal: which, its type, the clicks left and when it should be done.", true, 10, 200, 1f) { example ->
        drawTerms(this, example)
    }
    private val ssHud by HUD("Simon Says Progress", "How far Simon Says has got: who's on it, the round, the presses into it and when it should be done.", true, 10, 250, 1f) { example ->
        drawSs(this, example)
    }

    private const val STALE_NS = 2_500_000_000L
    private const val RESEND_MS = 1000L
    private const val REACH = 6.0
    private const val SS_RANGE = 10.0
    private val SS_DEVICE = Vec3(110.5, 121.5, 93.5)
    private const val SS_ROUNDS = 4
    private const val SS_PRESSES = 10  // 1 + 2 + 3 + 4

    /** The 17 terminals, in Station's order (section by section): where each is, and its name ("S2 T4"). */
    private class Terminal(val at: Vec3, val label: String)
    private val TERMINALS by lazy { Station.all().filter { it.kind == Station.Kind.TERMINAL }.map { Terminal(it.at, "S${it.section} ${it.label}") } }

    /** A teammate's open terminal, as last heard; [openedAt] is when they opened it, on our clock. */
    private class Term(val terminal: Int, val type: TerminalTypes, val left: Int, val total: Int, val openedAt: Long, val at: Long)
    /** A teammate on Simon Says: rounds done, presses into the next, and since when (our clock). */
    private class Ss(val rounds: Int, val pressed: Int, val startedAt: Long, val at: Long)

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
            terms.clear(); sses.clear()
            sentTerm = null; termFrame = null; ssStart = 0L; ssRounds = 0; ssShown = 0; ssFrame = null
            return
        }
        if (!inP3) { inP3 = true; framesOut = 0; mostDirect = 0; mostLinked = 0; heardFrom.clear() }
        val now = System.nanoTime()
        val nowMs = System.currentTimeMillis()
        terms.values.removeIf { now - it.at > STALE_NS }
        sses.values.removeIf { now - it.at > STALE_NS }
        if (TermInfo.deviceDone(1)) sses.clear()
        val myName = me.name.string.lowercase()

        // Your terminal: the one you're by while Odin has one open.
        val term = TerminalUtils.currentTerm
        val near = TERMINALS.indices.minByOrNull { TERMINALS[it].at.distanceToSqr(me.position()) }?.takeIf { TERMINALS[it].at.distanceTo(me.position()) <= REACH }
        if (term != null && near != null && me.containerMenu !== me.inventoryMenu) {
            val melody = term.type == TerminalTypes.MELODY
            val left = if (melody) 0 else term.solution.size.coerceIn(0, 40)
            if (term !== sentTerm) { sentTerm = term; termMax = 0 }
            termMax = maxOf(termMax, left)
            val total = if (melody) 0 else termMax
            val open = ((nowMs - term.timeOpened) / 100).coerceIn(0, 255)
            val f = byteArrayOf(7, near.toByte(), term.type.ordinal.toByte(), left.toByte(), total.toByte(), open.toByte())
            if (!f.copyOf(5).contentEquals(termFrame?.copyOf(5)) || nowMs - termAt > RESEND_MS) { termFrame = f; termAt = nowMs; share(f) }
            if (showSelf) terms[myName] = Term(near, term.type, left, total, now - open * 100_000_000L, now) else terms.remove(myName)
        } else if (sentTerm != null) {
            sentTerm = null; termFrame = null
            share(byteArrayOf(7, 255.toByte(), 0, 0, 0, 0))
            terms.remove(myName)
        }

        // Simon Says, while you're at it and section 1's device isn't done: rounds done and presses into the next, from
        // engineerClient's solver. A round whose lights go before all of it was pressed was a fail or a restart.
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
                if (showSelf) sses[myName] = Ss(ssRounds, into, now - since * 100_000_000L, now) else sses.remove(myName)
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
                val openedAt = now - open * 100_000_000L
                // The first frame's open time stands for the rest of it (frames come late by however long they took).
                val was = terms[sender]?.takeIf { it.terminal == terminal && it.type == type && kotlin.math.abs(it.openedAt - openedAt) < 1_500_000_000L }
                terms[sender] = Term(terminal, type, left, total, was?.openedAt ?: openedAt, now)
            }
            8 -> {
                if (b.remaining() != 4) return
                val rounds = b.get().toInt() and 255
                val pressed = b.get().toInt() and 255
                val since = ((b.get().toInt() and 255) shl 8) or (b.get().toInt() and 255)
                if (rounds > SS_ROUNDS || pressed > SS_ROUNDS || (rounds < SS_ROUNDS && pressed > rounds + 1)) return
                val startedAt = now - since * 100_000_000L
                val was = sses[sender]?.takeIf { kotlin.math.abs(it.startedAt - startedAt) < 1_500_000_000L }
                sses[sender] = Ss(rounds, pressed, was?.startedAt ?: startedAt, now)
            }
        }
    }

    // ------------------------------------------------------------------ when it'll be done

    /** Ms [name] still has on [t]: the history's time for the clicks left, then (as clicks go in) their pace on this one. */
    private fun termLeftMs(name: String, t: Term, now: Long): Long? {
        val elapsed = (now - t.openedAt) / 1_000_000.0
        val prior = PartySkill.term(name, t.type)?.first?.toDouble()
        if (t.type == TerminalTypes.MELODY || t.total == 0) return prior?.let { (it - elapsed).coerceAtLeast(0.0).toLong() }
        val done = t.total - t.left
        val fromPrior = prior?.let { it * t.left / t.total }
        if (done < 2 || elapsed < 200) return fromPrior?.toLong()
        val live = t.left * elapsed / done
        val w = done.toDouble() / t.total
        return (if (fromPrior == null) live else w * live + (1 - w) * fromPrior).toLong().coerceAtLeast(0)
    }

    /** Ms [name] still has on Simon Says, by the presses done of its 10, the same way. */
    private fun ssLeftMs(name: String, s: Ss, now: Long): Long? {
        val elapsed = (now - s.startedAt) / 1_000_000.0
        val p = (s.rounds * (s.rounds + 1) / 2 + s.pressed).toDouble() / SS_PRESSES
        val prior = PartySkill.ss(name)?.first?.toDouble()
        val fromPrior = prior?.let { maxOf(it * (1 - p), it - elapsed) }
        if (p < 0.15 || elapsed < 500) return fromPrior?.coerceAtLeast(0.0)?.toLong()
        val live = elapsed / p * (1 - p)
        return (if (fromPrior == null) live else p * live + (1 - p) * fromPrior).toLong().coerceAtLeast(0)
    }

    private fun eta(ms: Long?): String = when {
        !predictions || ms == null -> ""
        ms < 100 -> " §anow"
        else -> " §e~%.1fs".format(ms / 1000.0)
    }

    // ------------------------------------------------------------------ drawing

    /** Their name as the game spells it (what's heard is lower case). */
    private fun shown(name: String): String = DungeonUtils.dungeonTeammates.firstOrNull { it.name.equals(name, true) }?.name ?: name

    private fun typeName(t: TerminalTypes) = when (t) {
        TerminalTypes.PANES -> "Panes"; TerminalTypes.RUBIX -> "Rubix"; TerminalTypes.NUMBERS -> "Numbers"
        TerminalTypes.STARTS_WITH -> "Starts With"; TerminalTypes.SELECT -> "Select"; TerminalTypes.MELODY -> "Melody"
        else -> t.name
    }

    private fun clicks(t: Term) = if (t.total == 0) "" else " §f${t.left}§7 left"

    private fun drawLabels(ev: RenderExtractEvent) {
        if (terms.isEmpty()) return
        val now = System.nanoTime()
        for ((name, t) in terms) {
            val at = TERMINALS.getOrNull(t.terminal)?.at ?: continue
            ev.drawText("§b${shown(name)}§7:${clicks(t)}${eta(termLeftMs(name, t, now))}", Vec3(at.x, at.y + 2.9, at.z), labelSize, false)
        }
    }

    private fun lines(gfx: GuiGraphicsExtractor, lines: List<String>): Pair<Int, Int> {
        var w = 0
        lines.forEachIndexed { i, s ->
            w = maxOf(w, gfx.textDim(s, 0, i * 10, Colors.WHITE).first)
            gfx.text(s, 0, i * 10, Colors.WHITE)
        }
        return w to lines.size * 10
    }

    private fun drawTerms(gfx: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int> {
        if (example) return lines(gfx, listOf("§6Terminals", "§bSoloF7 §7S2 T3 §fPanes §f6§7 left §e~0.9s", "§bTheBadOne §7S2 T1 §fMelody §e~3.2s"))
        if (terms.isEmpty()) return 0 to 0
        val now = System.nanoTime()
        val rows = terms.entries.sortedBy { it.value.terminal }.map { (name, t) ->
            "§b${shown(name)} §7${TERMINALS.getOrNull(t.terminal)?.label ?: "?"} §f${typeName(t.type)}${clicks(t)}${eta(termLeftMs(name, t, now))}"
        }
        return lines(gfx, listOf("§6Terminals") + rows)
    }

    private fun drawSs(gfx: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int> {
        if (example) return lines(gfx, listOf("§6Simon Says §bp3wr §fround 3§7/4 §f2§7/3 §e~3.1s"))
        if (sses.isEmpty()) return 0 to 0
        val now = System.nanoTime()
        val (name, s) = sses.entries.maxByOrNull { it.value.rounds * 10 + it.value.pressed } ?: return 0 to 0
        val round = (s.rounds + 1).coerceAtMost(SS_ROUNDS)
        val into = if (s.rounds >= SS_ROUNDS) "" else " §f${s.pressed}§7/$round"
        return lines(gfx, listOf("§6Simon Says §b${shown(name)} §fround $round§7/$SS_ROUNDS$into${eta(ssLeftMs(name, s, now))}"))
    }
}
