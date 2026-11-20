package com.engineerclient.dungeon

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.events.GuiEvent
import com.adm.events.core.on
import com.adm.features.Category
import com.adm.features.Module
import com.adm.utils.Color
import com.adm.utils.customData
import com.adm.utils.itemId
import com.adm.utils.skyblock.LocationUtils
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Inventory

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
    private val junkOn by BooleanSetting("Highlight Junk", false, desc = "Highlights common dungeon junk in the Trades, Booster Cookie and Ophelia menus.")
    private val junkColor by ColorSetting("Junk Color", Color(255, 170, 0, 160 / 255f), true, desc = "Color of junk items.").withDependency { junkOn }

    private val blacklist = setOf("ICE_SPRAY_WAND", "STARRED_ICE_SPRAY_WAND")
    private val ARMOR = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)

    private val JUNK = setOf(
        "Defuse Kit", "Lever", "Torch", "Stone Button", "Tripwire Hook", "Journal Entry", "Training Weights",
        "Mimic Fragment", "Healing VIII Splash Potion", "Premium Flesh", "Decoy", "Trap", "Inflatable Jerry",
    )
    private val JUNK_MENUS = setOf("Trades", "Booster Cookie", "Ophelia")

    private class Decision(val data: CustomData?, val name: Component, val boost: Int, val junk: Boolean)
    private val decisions = IdentityHashMap<ItemStack, Decision>()

    // Boost is MIN_VALUE when the stack isn't highlighted; kept until the stack's data or name component changes.
    private fun decisionOf(stack: ItemStack): Decision {
        val data = stack.get(DataComponents.CUSTOM_DATA)
        val name = stack.hoverName
        decisions[stack]?.let { if (it.data === data && it.name === name) return it }
        val text = name.string
        val boost = stack.customData.getInt("baseStatBoostPercentage").orElse(null)
            ?.takeUnless { stack.itemId in blacklist || text.contains('✪') } ?: Int.MIN_VALUE
        if (decisions.size >= 1024) decisions.clear()
        return Decision(data, name, boost, junkName(text) in JUNK).also { decisions[stack] = it }
    }

    private fun junkName(name: String): String {
        val x = name.lastIndexOf(" x")
        if (x < 0 || x + 2 == name.length) return name
        for (i in x + 2 until name.length) if (!name[i].isDigit()) return name
        return name.substring(0, x)
    }

    private var menuScreen: Screen? = null
    private var junkMenu = false

    private fun junkMenu(screen: Screen): Boolean {
        if (screen !== menuScreen) {
            menuScreen = screen
            junkMenu = screen.title.string in JUNK_MENUS
        }
        return junkMenu
    }

    init {
        on<GuiEvent.RenderSlot> {
            if (!LocationUtils.isInSkyblock) return@on
            val stack = slot.item.takeUnless { it.isEmpty } ?: return@on
            val decision = decisionOf(stack)
            if (decision.junk && junkOn && slot.container is Inventory && junkMenu(screen)) {
                guiGraphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, junkColor.rgba)
                return@on
            }
            val boost = decision.boost
            if (boost == Int.MIN_VALUE) return@on
            val me = mc.player ?: return@on
            if (ARMOR.any { me.getItemBySlot(it) === stack }) return@on
            val color = if (boost >= 50) maxColor else lowColor
            guiGraphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, color.rgba)
        }
    }
}
