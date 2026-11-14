package com.engineerclient.skyblock

import com.engineerclient.EngineerClient
import com.mojang.blaze3d.platform.InputConstants
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.KeybindSetting
import com.odtheking.odin.clickgui.settings.impl.ListSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.ModuleManager
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.itemUUID
import com.odtheking.odin.utils.lore
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

object ItemProtection : Module(
    name = "Item Protection",
    category = Category.SKYBLOCK,
    description = "Stops you dropping, selling, salvaging, auctioning or trading the items you protect.",
    key = null,
    toggled = true,
) {
    private val protectKey by KeybindSetting("Protect Key", InputConstants.UNKNOWN, "Protects or unprotects the held item.").onPress { toggleHeld() }
    private val dropHeldInRun by BooleanSetting("Don't Protect Held Item In Run", true, desc = "In a dungeon, the drop key still works on the held item.")
    private val protectedItems by ListSetting("Protected Items", mutableSetOf<String>())

    private fun key(stack: ItemStack): String? =
        stack.itemUUID.ifEmpty { stack.itemId }.ifEmpty { null }

    private fun isProtected(stack: ItemStack): Boolean =
        enabled && !stack.isEmpty && key(stack)?.let { it in protectedItems } == true

    private fun toggleHeld() {
        val stack = mc.player?.mainHandItem ?: return
        val key = key(stack) ?: return EngineerClient.msg("§cThis item has no uuid or id, it can't be protected.")
        if (protectedItems.remove(key)) {
            EngineerClient.msg(Component.literal("§7No longer protected: ").append(stack.hoverName))
        } else {
            protectedItems.add(key)
            EngineerClient.msg(Component.literal("§aProtected: ").append(stack.hoverName))
        }
        ModuleManager.saveConfigurations()
    }

    @JvmStatic
    fun blocksDrop(player: LocalPlayer): Boolean {
        if (dropHeldInRun && DungeonUtils.inDungeons) return false
        return isProtected(player.inventory.selectedItem)
    }

    @JvmStatic
    fun blocksClick(screen: AbstractContainerScreen<*>, slot: Slot?, slotId: Int, input: ContainerInput): Boolean {
        if (!enabled) return false
        if (slotId == AbstractContainerMenu.SLOT_CLICKED_OUTSIDE && isProtected(screen.menu.carried)) return true
        val stack = slot?.item ?: return false
        if (!isProtected(stack)) return false
        if (input == ContainerInput.THROW) return true
        val what = blockedAction(screen) ?: return false
        EngineerClient.msg(Component.literal("§cStopped ").append(stack.hoverName).append("§c from being $what."))
        return true
    }

    private fun blockedAction(screen: AbstractContainerScreen<*>): String? {
        val title = screen.title.string
        if ("Auction" in title) return "auctioned"
        if ("Salvage" in title) return "salvaged"
        val slots = screen.menu.slots
        if (slots.size <= 49) return null
        val sell = slots[49].item
        if ("Sell Item" in sell.hoverName.string || sell.lore.any { "Click to buyback!" in it.string }) return "sold"
        if ("⇦ Your stuff" in slots[4].item.hoverName.string) return "traded"
        return null
    }
}
