package com.engineerclient.terminals

import com.engineerclient.EngineerClient
import com.engineerclient.terminals.sim.TermSimGUI
import com.adm.events.LevelEvent
import com.adm.events.core.EventBus
import com.adm.events.core.on
import com.adm.events.core.onSend
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundPingPacket
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
import net.minecraft.sounds.SoundEvents
import kotlin.math.abs

/**
 * Click Limit: Hypixel takes at most 5 terminal clicks in any 500 ms (real time, where it processes them) and drops the
 * rest silently, so one it would drop isn't sent. A click counts as taken when the pling Hypixel plays for it arrives;
 * completion lines play the same pling, so each takes the next one of its tick for itself.
 */
object ClickLimit {

    private const val LIMIT = 5
    private const val WINDOW = 500_000_000L
    /** Without Hypixel's per-tick pings (singleplayer, the P3 Sim): how long past the longest recent round trip a click may still be answered. */
    private const val NO_PING_GRACE = 150_000_000L
    /** A line's pling comes in its own tick, 0-40 ms after it (315 of 315 recorded). */
    private const val OWED_NO_PING = 60_000_000L

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

    /** True: don't send this click. Game thread. */
    fun blocks(): Boolean {
        if (!Terminals.clickLimit || EngineerClient.mc.gui.screen() is TermSimGUI) return false
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
        if (!Terminals.clickLimit) return
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

    fun install() {
        // Every click that goes out while a terminal is open counts, whoever sent it.
        onSend<ServerboundContainerClickPacket> {
            if (!Terminals.clickLimit || TerminalUtils.currentTerm == null || EngineerClient.mc.gui.screen() is TermSimGUI) return@onSend
            val now = System.nanoTime()
            synchronized(this@ClickLimit) { waiting.addLast(now); while (waiting.size > 16) waiting.removeFirst() }
        }
        on<LevelEvent.Load> { reset() }
        EventBus.subscribe(this)
    }
}
