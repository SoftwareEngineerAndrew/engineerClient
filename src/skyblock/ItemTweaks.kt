package com.engineerclient.skyblock

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.customData
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

object ItemTweaks : Module(
    name = "Item Tweaks",
    category = Category.SKYBLOCK,
    description = "Small item and tooltip tweaks.",
    key = null,
) {
    private val removeGearScore by BooleanSetting("Remove Gear Score", false, desc = "Drops the Gear Score line from dungeon item tooltips.")
    private val noPearlCooldown by BooleanSetting("Disable Ender Pearl Cooldown", false, desc = "Ender pearls never show or wait on the vanilla cooldown.")
    private val itemQuality by BooleanSetting("Item Quality", true, desc = "Adds a dungeon item's stat boost and floor to its tooltip.")
    private val oldPlayerHead by BooleanSetting("Old Player Head Size", true, desc = "Draws player heads in slots at their smaller 1.8 size.")
    private val noHotbarScroll by BooleanSetting("Disable Hotbar Scroll", true, desc = "The scroll wheel no longer changes your hotbar slot.")

    private val floors = listOf("E", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "M5", "M6", "M7")

    init {
        ItemTooltipCallback.EVENT.register { stack, _, _, lines ->
            if (!enabled) return@register
            if (removeGearScore) lines.removeIf { it.string.startsWith("Gear Score: ") }
            if (itemQuality) {
                val data = stack.customData
                val boost = data.getInt("baseStatBoostPercentage").orElse(null) ?: return@register
                val tier = data.getInt("item_tier").orElse(null) ?: return@register
                lines.add(Component.literal("§4Stat boost: $boost/50 req: ${floors.getOrElse(tier) { "?" }}"))
            }
        }
    }

    fun noPearlCooldown(): Boolean = enabled && noPearlCooldown

    fun smallHead(stack: ItemStack): Boolean = enabled && oldPlayerHead && stack.`is`(Items.PLAYER_HEAD)

    fun noHotbarScroll(): Boolean = enabled && noHotbarScroll
}
