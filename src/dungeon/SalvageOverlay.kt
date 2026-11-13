package com.engineerclient.dungeon

import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.events.GuiEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.customData
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.skyblock.LocationUtils
import net.minecraft.world.entity.EquipmentSlot

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

    init {
        on<GuiEvent.RenderSlot> {
            if (!LocationUtils.isInSkyblock) return@on
            val stack = slot.item.takeUnless { it.isEmpty } ?: return@on
            val me = mc.player ?: return@on
            if (ARMOR.any { me.getItemBySlot(it) === stack }) return@on
            if (stack.itemId in blacklist || stack.hoverName.string.contains('✪')) return@on
            val boost = stack.customData.getInt("baseStatBoostPercentage").orElse(null) ?: return@on
            val color = if (boost >= 50) maxColor else lowColor
            guiGraphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, color.rgba)
        }
    }
}
