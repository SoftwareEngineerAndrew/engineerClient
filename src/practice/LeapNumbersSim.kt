package com.engineerclient.practice

import com.engineerclient.EngineerClient
import com.odtheking.odin.utils.itemId

/** SS Practice's keybind with Infinileap in your hand: /termsim inf ([InfNumbersSim]), the numbers that never ends. */
object LeapNumbersSim {

    /** Opens /termsim inf; Escape to stop. False: not holding Infinileap (or a screen is open). */
    @JvmStatic
    fun open(): Boolean {
        val player = EngineerClient.mc.player ?: return false
        if (EngineerClient.mc.screen != null || player.mainHandItem.itemId != "INFINITE_SPIRIT_LEAP") return false
        InfNumbersSim.open(0L)
        return true
    }
}
