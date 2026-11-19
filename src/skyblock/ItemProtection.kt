package com.engineerclient.skyblock

import com.engineerclient.EngineerClient
import com.mojang.blaze3d.platform.InputConstants
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.clickgui.settings.impl.KeybindSetting
import com.adm.clickgui.settings.impl.ListSetting
import com.adm.events.ScreenEvent
import com.adm.events.core.on
import com.adm.features.Category
import com.adm.features.Module
import com.adm.features.ModuleManager
import com.adm.utils.Color
import com.adm.utils.customData
import com.adm.utils.itemId
import com.adm.utils.itemUUID
import com.adm.utils.lore
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import java.util.IdentityHashMap

object ItemProtection : Module(
    name = "Item Protection",
    category = Category.SKYBLOCK,
    description = "Stops you dropping, selling, salvaging, auctioning or trading the items you protect.",
    key = null,
    toggled = true,
) {
    private val protectKey by KeybindSetting("Protect Key", InputConstants.UNKNOWN, "Protects or unprotects the held or hovered item.").onPress { toggleHeld() }
    private val dropHeldInRun by BooleanSetting("Don't Protect Held Item In Run", true, desc = "In a dungeon, the drop key still works on the held item.")
    private val protectedItems by ListSetting("Protected Items", mutableSetOf<String>())
    private val protectStarred by BooleanSetting("Protect Starred Items", false, desc = "Protects every starred dungeon item.")
    private val protectRecombed by BooleanSetting("Protect Recombed Items", false, desc = "Protects every recombobulated item.")
    private val protectArmor by BooleanSetting("Protect Current Armor", false, desc = "Protects the armor you are wearing.")
    private val showMarker by BooleanSetting("Show Protected", true, desc = "Outlines protected items and adds a tooltip line.")
    private val markerColor by ColorSetting("Protected Color", Color(0xFF, 0x55, 0x55, 0.8f), true, desc = "Outline color of protected items.")

    private val ARMOR = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)

    private fun key(stack: ItemStack): String? =
        stack.itemUUID.ifEmpty { stack.itemId }.ifEmpty { null }

    private fun isProtected(stack: ItemStack): Boolean {
        if (!enabled || stack.isEmpty) return false
        val key = key(stack)
        if (key != null && key in protectedItems) return true
        val data = stack.customData
        if (protectStarred && (data.getInt("upgrade_level").orElse(0) > 0 || data.getInt("dungeon_item_level").orElse(0) > 0)) return true
        if (protectRecombed && data.getInt("rarity_upgrades").orElse(0) > 0) return true
        if (protectArmor) {
            val uuid = stack.itemUUID.ifEmpty { return false }
            val player = mc.player ?: return false
            if (ARMOR.any { player.getItemBySlot(it).itemUUID == uuid }) return true
        }
        return false
    }

    private fun toggleHeld() {
        toggle(mc.player?.mainHandItem ?: return)
    }

    private fun toggle(stack: ItemStack) {
        if (stack.isEmpty) return
        val key = key(stack) ?: return EngineerClient.msg("§cThis item has no uuid or id, it can't be protected.")
        if (protectedItems.remove(key)) {
            EngineerClient.msg(Component.literal("§7No longer protected: ").append(stack.hoverName))
        } else {
            protectedItems.add(key)
            EngineerClient.msg(Component.literal("§aProtected: ").append(stack.hoverName))
        }
        shownProtected.clear()
        ModuleManager.saveConfigurations()
    }

    private val shownProtected = IdentityHashMap<ItemStack, Boolean>()
    private var shownAt = 0L

    private fun protectedCached(stack: ItemStack): Boolean {
        val now = System.currentTimeMillis()
        // Starred, recombed and worn armor can change without the stack changing.
        if (now - shownAt > 1000 || shownProtected.size >= 1024) { shownProtected.clear(); shownAt = now }
        return shownProtected[stack] ?: isProtected(stack).also { shownProtected[stack] = it }
    }

    @JvmStatic
    fun markSlot(graphics: GuiGraphicsExtractor, slot: Slot) {
        if (!enabled || !showMarker || slot.item.isEmpty || !protectedCached(slot.item)) return
        val x = slot.x
        val y = slot.y
        val c = markerColor.rgba
        graphics.fill(x, y, x + 16, y + 1, c)
        graphics.fill(x, y + 15, x + 16, y + 16, c)
        graphics.fill(x, y + 1, x + 1, y + 15, c)
        graphics.fill(x + 15, y + 1, x + 16, y + 15, c)
    }

    init {
        on<ScreenEvent.KeyPress> {
            val screen = screen as? AbstractContainerScreen<*> ?: return@on
            if (protectKey == InputConstants.UNKNOWN || InputConstants.getKey(input) != protectKey) return@on
            toggle(screen.hoveredSlot?.item ?: return@on)
            cancel()
        }

        ItemTooltipCallback.EVENT.register { stack, _, _, lines ->
            if (!enabled || !showMarker || mc.gui.screen() !is AbstractContainerScreen<*> || !protectedCached(stack)) return@register
            lines.add(PROTECTED_LINE)
        }
    }

    private val PROTECTED_LINE = Component.literal("§cProtected")

    @JvmStatic
    fun blocksDrop(player: LocalPlayer): Boolean {
        if (dropHeldInRun && DungeonUtils.inDungeons) return false
        val stack = player.inventory.selectedItem
        if (!isProtected(stack)) return false
        dropAlert(stack)
        return true
    }

    private var lastAlert = 0L

    private fun dropAlert(stack: ItemStack) {
        val now = System.currentTimeMillis()
        if (now - lastAlert < 1000) return
        lastAlert = now
        EngineerClient.msg(Component.literal("§cStopped ").append(stack.hoverName).append("§c from being dropped."))
    }

    @JvmStatic
    fun blocksClick(screen: AbstractContainerScreen<*>, slot: Slot?, slotId: Int, input: ContainerInput): Boolean =
        blocksClick(screen, slot, slotId, 0, input)

    @JvmStatic
    fun blocksClick(screen: AbstractContainerScreen<*>, slot: Slot?, slotId: Int, button: Int, input: ContainerInput): Boolean {
        if (!enabled) return false
        if (slotId == AbstractContainerMenu.SLOT_CLICKED_OUTSIDE && isProtected(screen.menu.carried)) { dropAlert(screen.menu.carried); return true }
        // A hotbar key over a menu slot moves the hotbar item into the menu.
        if (input == ContainerInput.SWAP && slot != null && slot.container !is Inventory) {
            val moved = mc.player?.inventory?.getItem(button) ?: return false
            if (!isProtected(moved)) return false
            val what = blockedAction(screen) ?: return false
            EngineerClient.msg(Component.literal("§cStopped ").append(moved.hoverName).append("§c from being $what."))
            return true
        }
        val stack = slot?.item ?: return false
        if (!isProtected(stack)) return false
        if (input == ContainerInput.THROW) { dropAlert(stack); return true }
        // what the menu shows (a sold auction to claim, a trade's other side) is never yours to lose by clicking
        if (slot.container !is Inventory) return false
        val what = blockedAction(screen) ?: return false
        EngineerClient.msg(Component.literal("§cStopped ").append(stack.hoverName).append("§c from being $what."))
        return true
    }

    private fun blockedAction(screen: AbstractContainerScreen<*>): String? {
        val title = screen.title.string
        if (title.startsWith("Create") && "Auction" in title) return "auctioned"
        if ("Salvage" in title) return "salvaged"
        val slots = screen.menu.slots
        if (slots.size <= 49) return null
        val sell = slots[49].item
        if ("Sell Item" in sell.hoverName.string || sell.lore.any { "Click to buyback!" in it.string }) return "sold"
        if ("⇦ Your stuff" in slots[4].item.hoverName.string) return "traded"
        return null
    }
}
