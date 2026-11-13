package com.engineerclient.skyblock

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback

object ItemTweaks : Module(
    name = "Item Tweaks",
    category = Category.SKYBLOCK,
    description = "Small item and tooltip tweaks.",
    key = null,
) {
    private val removeGearScore by BooleanSetting("Remove Gear Score", false, desc = "Drops the Gear Score line from dungeon item tooltips.")
    private val noPearlCooldown by BooleanSetting("Disable Ender Pearl Cooldown", false, desc = "Ender pearls never show or wait on the vanilla cooldown.")

    init {
        ItemTooltipCallback.EVENT.register { _, _, _, lines ->
            if (enabled && removeGearScore) lines.removeIf { it.string.startsWith("Gear Score: ") }
        }
    }

    fun noPearlCooldown(): Boolean = enabled && noPearlCooldown
}
