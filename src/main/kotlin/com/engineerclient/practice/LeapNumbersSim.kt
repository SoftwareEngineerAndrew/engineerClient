package com.engineerclient.practice

import com.engineerclient.EngineerClient
import com.odtheking.odin.features.impl.boss.termsim.NumbersSim
import com.odtheking.odin.features.impl.boss.termsim.StartGUI
import com.odtheking.odin.features.impl.boss.termsim.TermSimGUI
import com.odtheking.odin.utils.itemId
import net.minecraft.client.gui.screens.Screen

/** SS Practice's keybind with Infinileap in your hand: Odin's numbers terminal simulator, one after another. */
object LeapNumbersSim {

    /** The simulator on screen was opened by [open]: finishing it starts the next one instead of Odin's termsim menu. */
    private var simFromLeap = false
    /** Bumped by every finish and every stop, so a pending restart knows whether it's still wanted. */
    private var simRound = 0

    /**
     * Opens Odin's numbers terminal simulator. Finishing one starts another straight away, in the
     * same menu; Escape to stop. False: not holding Infinileap.
     */
    @JvmStatic
    fun open(): Boolean {
        val player = EngineerClient.mc.player ?: return false
        if (EngineerClient.mc.gui.screen() != null || player.mainHandItem.itemId != "INFINITE_SPIRIT_LEAP") return false
        simFromLeap = true
        NumbersSim.open(0L)
        return true
    }

    /**
     * Every screen the game is told to open (from the mixin). True: don't. Odin's termsim menu,
     * which a finished simulator opens, is kept off when that simulator came from [open]: the
     * finished one is replaced by a new one on the next frame (not inside the finish, which is
     * still running), opened again rather than refilled so Odin starts a new terminal (its solver
     * and its finish) as it would for any. Anything but a simulator (Escape, say) ends it.
     */
    @JvmStatic
    fun cancelScreen(screen: Screen?): Boolean {
        if (!simFromLeap) return false
        if (screen === StartGUI && EngineerClient.mc.gui.screen() === NumbersSim) {
            val round = ++simRound
            // From another thread, so it's queued for the next frame rather than run right here.
            Thread.ofVirtual().start {
                EngineerClient.mc.execute {
                    if (simFromLeap && round == simRound && EngineerClient.mc.gui.screen() === NumbersSim) NumbersSim.open(0L)
                }
            }
            return true
        }
        if (screen !is TermSimGUI) { simFromLeap = false; simRound++ }
        return false
    }

    /** A simulator from [open] is on screen (no first click protection on it). */
    @JvmStatic
    fun simActive(): Boolean = simFromLeap && EngineerClient.mc.gui.screen() is TermSimGUI
}
