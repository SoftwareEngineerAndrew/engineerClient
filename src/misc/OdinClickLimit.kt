package com.engineerclient.misc

import com.engineerclient.EngineerClient
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.DropdownSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onSend
import com.odtheking.odin.features.impl.boss.TerminalSolver
import com.odtheking.odin.features.impl.boss.termsim.TermSimGUI
import com.odtheking.odin.utils.skyblock.dungeon.terminals.TerminalUtils
import com.odtheking.odin.utils.skyblock.dungeon.terminals.terminalhandler.TerminalHandler
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundPingPacket
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
import net.minecraft.sounds.SoundEvents
import kotlin.math.abs

/**
 * "Click Limit" for Odin's Terminal Solver: a terminal click Hypixel would drop is never sent.
 *
 * Hypixel takes at most 5 terminal clicks in any 500 ms and drops the rest without a word: no pling, no slot
 * update. Odin's Client Prediction has shown such a click as done all the same, so the solver is out of step
 * with the window until Hypixel's next update corrects it (in Click in Order every later click is refused as
 * out of order until then). Measured on 1847 clicks in 316 recorded Hypixel terminals (Oct 8-10):
 *  - a click is dropped when the 5th-last click Hypixel took was taken under 500 ms before it; dropped
 *    clicks don't count toward the 5;
 *  - the 500 ms is real time where Hypixel processes the clicks, not ticks: after a 2.3 s server freeze the 5
 *    clicks queued in it were all taken in one tick, 7 ticks after 4 others, and the 8 after them dropped.
 *
 * The client sees when Hypixel took a click: the pling it plays you that same tick (volume 8, pitch 4.05),
 * which comes [rtt] after the click went out. So a new click is counted against the plings of the clicks
 * taken before it, as if its own came as soon as any has lately (the least round trip of the last 8), and
 * against every click still waiting for its pling (in a freeze they all land together). Hypixel plays the same
 * pling with every completion line (any player's terminal, lever or device, the gate, the core), in that line's
 * tick and after it, so each such line takes the next pling of its tick for itself. A click whose pling has not
 * come 3 server ticks (Hypixel's per-tick pings) after it could have no longer waits: Hypixel refused it.
 * Replayed over the recordings as if live (a click kept back is never sent), it keeps back 54 of the 65 clicks the
 * limit dropped and 21 of the 1778 Hypixel took (1.2%). The rest are at the edge: Hypixel's ticks run a few ms
 * long or short, so a click 10 ticks after the 5th-last is taken or dropped either way; and in a server freeze
 * nothing tells the client when Hypixel will take the clicks queued in it.
 *
 * `TerminalClickLimitMixin` asks [blocks] at the start of Odin's TerminalHandler.click, before Odin predicts
 * or sends anything; TerminalTapMixin hands every packet to [tap] first, ahead of any mod that cancels
 * completion lines or the ding (blade-addons, Random Stuff's Mute Completion Ding).
 */
object OdinClickLimit {

    private const val LIMIT = 5
    private const val WINDOW = 500_000_000L
    /** Without Hypixel's per-tick pings (singleplayer, the P3 Sim): how long past the longest recent round trip a click may still be answered. */
    private const val NO_PING_GRACE = 150_000_000L
    /** A line's pling comes in its own tick, 0-40 ms after it (315 of 315 recorded). */
    private const val OWED_NO_PING = 60_000_000L

    private val clickLimit = BooleanSetting("Click Limit", true, desc = "Hypixel takes at most 5 terminal clicks in any 500 ms and silently drops the rest. A click it would drop isn't sent, so the solver doesn't show it done; click again. Added by engineerClient.")
        .withDependency { (TerminalSolver.settings["Solver Functionality"] as? DropdownSetting)?.value ?: true }

    private val COMPLETION = Regex("^(?:.{1,16} (?:activated a (?:terminal|lever)|completed a device)! \\(\\d+/\\d+\\)|The gate has been destroyed!|The Core entrance is opening!)$")
    private val CODES = Regex("§.")

    // All below under the object's lock: written on the network thread ([tap]) and the game thread.
    /** Send times of clicks with no pling yet, oldest first. */
    private val waiting = ArrayDeque<Long>()
    /** Pling times of the last clicks Hypixel took. */
    private val taken = ArrayDeque<Long>()
    /** Pling minus send of the last answered clicks. */
    private val rtt = ArrayDeque<Long>()
    /** Completion lines whose pling hasn't come yet. */
    private val owed = ArrayDeque<Long>()
    /** Arrival of the last server ticks (top-level pings). */
    private val pings = ArrayDeque<Long>()

    /** True: don't send this click. Odin's own checks first, so only a click Odin would send is held back. Game thread. */
    @JvmStatic
    fun blocks(handler: TerminalHandler, slot: Int, button: Int): Boolean {
        if (!clickLimit.value || EngineerClient.mc.gui.screen() is TermSimGUI) return false
        if (!handler.canClick(slot, button) || handler.shouldProtect()) return false
        val now = System.nanoTime()
        synchronized(this) {
            expire(now)
            val lands = now + (rtt.minOrNull() ?: 0L)
            return waiting.size + taken.count { lands - it < WINDOW } >= LIMIT
        }
    }

    /** Every inbound packet, first, on the network thread. Reads only. */
    @JvmStatic
    fun tap(packet: Packet<*>) {
        if (!clickLimit.value) return
        EngineerClient.safely("click limit tap") { read(packet, System.nanoTime(), false) }
    }

    private fun read(p: Packet<*>, now: Long, inBundle: Boolean) {
        when (p) {
            is ClientboundBundlePacket -> p.subPackets().forEach { read(it, now, true) }
            // Pings inside a bundle are Hypixel's anticheat around your own entity's flags, not ticks.
            is ClientboundPingPacket -> if (p.id != 0 && !inBundle) synchronized(this) {
                pings.addLast(now)
                while (pings.size > 16) pings.removeFirst()
                owed.clear()
            }
            is ClientboundSystemChatPacket -> if (!p.overlay() && COMPLETION.matches(p.content().string.replace(CODES, "").trim())) synchronized(this) {
                owed.addLast(now)
            }
            is ClientboundSoundPacket -> if (p.sound.value() == SoundEvents.NOTE_BLOCK_PLING.value() && p.volume >= 7.9f && abs(p.pitch - 4.047619f) < 0.01f) synchronized(this) {
                if (!pingsFlow()) while (owed.isNotEmpty() && now - owed.first() > OWED_NO_PING) owed.removeFirst()
                if (owed.isNotEmpty()) { owed.removeFirst(); return }
                val sent = waiting.removeFirstOrNull() ?: return
                taken.addLast(now); while (taken.size > 8) taken.removeFirst()
                rtt.addLast(now - sent); while (rtt.size > 8) rtt.removeFirst()
            }
            else -> {}
        }
    }

    /** Hypixel sends a ping every tick; none since joining this world: not on Hypixel. (A freeze stops them, and the clicks wait.) */
    private fun pingsFlow() = pings.isNotEmpty()

    /** Drops the clicks that have waited past any answer: Hypixel refused them. */
    private fun expire(now: Long) {
        val least = rtt.minOrNull() ?: 0L
        val most = rtt.maxOrNull() ?: 300_000_000L
        val flow = pingsFlow()
        while (waiting.isNotEmpty()) {
            val sent = waiting.first()
            val gone = if (flow) pings.count { it >= sent + least } >= 3 else now - sent > most + NO_PING_GRACE
            if (!gone) break
            waiting.removeFirst()
        }
    }

    private fun reset() = synchronized(this) { waiting.clear(); taken.clear(); rtt.clear(); owed.clear(); pings.clear() }

    /** Adds the setting to Odin's module; before OdinTermTimes.install, which re-reads Odin's config. */
    fun install() {
        TerminalSolver.registerSetting(clickLimit)
        // Every click that goes out while a terminal is open counts, whoever sent it.
        onSend<ServerboundContainerClickPacket> {
            if (!clickLimit.value || TerminalUtils.currentTerm == null || EngineerClient.mc.gui.screen() is TermSimGUI) return@onSend
            val now = System.nanoTime()
            synchronized(this@OdinClickLimit) { waiting.addLast(now); while (waiting.size > 16) waiting.removeFirst() }
        }
        on<LevelEvent.Load> { reset() }
        EventBus.subscribe(this)
    }
}
