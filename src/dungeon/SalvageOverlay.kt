package com.engineerclient.dungeon

import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.events.GuiEvent
import com.adm.events.core.on
import com.adm.features.Category
import com.adm.features.Module
import com.adm.utils.Color
import com.adm.utils.customData
import com.adm.utils.itemId
import com.adm.utils.skyblock.LocationUtils
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import java.util.IdentityHashMap

object SalvageOverlay : Module(
    name = "Salvage Overlay",
    category = Category.DUNGEON,
    description = "Highlights dungeon gear worth salvaging in your inventory and containers.",
    key = null,
) {
    private val lowColor by ColorSetting("Highlight Color", Color(0, 255, 255, 160 / 255f), true, desc = "Gear under 50% quality.")
    private val maxColor by ColorSetting("50% Stats Color", Color(255, 0, 0, 160 / 255f), true, desc = "Gear at 50% quality.")

    private val blacklist = setOf("ICE_SPRAY_WAND", "STARRED_ICE_SPRAY_WAND")
    private val ARMOR = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)

    private class Decision(val data: CustomData?, val name: Component, val boost: Int)
    private val decisions = IdentityHashMap<ItemStack, Decision>()

    // MIN_VALUE when the stack isn't highlighted; kept until the stack's data or name component changes.
    private fun boostOf(stack: ItemStack): Int {
        val data = stack.get(DataComponents.CUSTOM_DATA)
        val name = stack.hoverName
        decisions[stack]?.let { if (it.data === data && it.name === name) return it.boost }
        val boost = stack.customData.getInt("baseStatBoostPercentage").orElse(null)
            ?.takeUnless { stack.itemId in blacklist || name.string.contains('✪') } ?: Int.MIN_VALUE
        if (decisions.size >= 1024) decisions.clear()
        decisions[stack] = Decision(data, name, boost)
        return boost
    }

    init {
        on<GuiEvent.RenderSlot> {
            if (!LocationUtils.isInSkyblock) return@on
            val stack = slot.item.takeUnless { it.isEmpty } ?: return@on
            val boost = boostOf(stack)
            if (boost == Int.MIN_VALUE) return@on
            val me = mc.player ?: return@on
            if (ARMOR.any { me.getItemBySlot(it) === stack }) return@on
            val color = if (boost >= 50) maxColor else lowColor
            guiGraphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, color.rgba)
        }
    }
}
