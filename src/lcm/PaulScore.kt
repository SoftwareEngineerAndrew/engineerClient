package com.engineerclient.lcm

import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.features.impl.dungeon.MapInfo

/**
 * Map Info's "Paul Score": whether Paul's +10 bonus counts. Odin (0.3.6+) goes by the mayor alone with no way to
 * force it; Auto keeps that, On / Off override it. `PaulScoreMixin` applies it at Odin's DungeonListener.getPaul,
 * which every Paul check goes through: Odin's score, its needed secrets, its Full Hud, and LCM Style's score.
 */
object PaulScore {

    enum class Mode(private val label: String) {
        AUTO("Auto"), ON("On"), OFF("Off");
        override fun toString() = label
    }

    private val mode = SelectorSetting("Paul Score", Mode.AUTO,
        desc = "Whether Paul's +10 bonus score counts in Map Info's score and secrets needed for 300. Auto: when Paul is mayor (Odin's own check). On / Off: always / never. Added by engineerClient.")

    fun install() {
        MapInfo.registerSetting(mode)
    }

    /** The forced Paul value, or null to leave Odin's mayor check. */
    @JvmStatic
    fun forced(): Boolean? = when (mode.value) {
        Mode.ON -> true
        Mode.OFF -> false
        else -> null
    }
}
