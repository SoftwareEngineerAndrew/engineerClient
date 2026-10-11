package com.devgineerclient.waypoints

import com.mojang.blaze3d.platform.InputConstants
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.KeybindSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module

/**
 * Minecraft 26.1.2 build: Odin 0.3.4 still has Positional Messages (and /posmsg) itself, so the
 * module isn't carried here; only what Devgineer Client adds to it on newer builds - the re-trigger
 * and the box-here key - is, as its own small module.
 */
object PosMsgExtras : Module(
    name = "Posmsg Extras",
    category = Category.custom("Devgineer Client"),
    description = "Additions to Odin's Positional Messages: re-trigger, a box-here key, /posmsg edit and /posmsg wand.",
) {
    /** Read by PosMsgEditor every tick. */
    val retrigger by BooleanSetting("Re-trigger", false, desc = "Odin sends each /posmsg once per world. On: leaving a box (or its radius) re-arms it, so walking back in sends it again. Only your own client.")

    private val hereKey by KeybindSetting("Box Here Keybind", InputConstants.UNKNOWN, "Adds an Odin /posmsg box, 1x1x1 on the block you stand in, that sends \"entered box\".").onPress {
        PosMsgEditor.addHere("entered box")
    }
}
