package com.engineerclient.waypoints

import com.odtheking.odin.clickgui.settings.impl.ActionSetting
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.StringSetting
import com.odtheking.odin.events.RenderEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.itemUUID
import com.odtheking.odin.utils.modMessage
import com.odtheking.odin.utils.render.drawStyledBox
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB

/**
 * An in-game box editor, driven by a "wand": any item you pick with "Make Held Item Wand". With
 * Edit Mode on, dropping the wand doesn't drop it — it places a 1x1x1 box at your feet instead.
 *
 * The first step of the editor. Boxes live in memory for now; nothing saves them yet.
 */
object BrWaypoints2 : Module(
    name = "BR Waypoints 2",
    category = Category.custom("Engineer Client"),
    description = "In-game box editor. Pick a wand, turn on Edit Mode, and drop the wand to place a box at your feet.",
) {

    private val editMode by BooleanSetting("Edit Mode", false, desc = "Edits only happen while this is on. Off, the wand drops like any other item.")

    private val makeWand by ActionSetting("Make Held Item Wand", desc = "Makes the item in your hand the wand, the tool the editor is used with.") {
        val held = mc.player?.mainHandItem
        if (held == null || held.isEmpty) return@ActionSetting modMessage("§cHold the item you want as the wand first.")
        wand = identity(held)
        modMessage("§aWand set: §f${held.hoverName.string}")
    }

    /** Which item is the wand, saved with the config so it survives a restart. */
    private var wand by StringSetting("Wand", "", 256, desc = "The wand's identity.").hide()

    private val boxes = LinkedHashSet<BlockPos>()
    private val BOX_COLOUR = Color(0, 255, 221, 1f)

    init {
        on<RenderEvent.Extract> {
            for (pos in boxes) drawStyledBox(AABB(pos), BOX_COLOUR, 1, true)
        }
    }

    /**
     * Called from the player's drop, before anything is sent. True means the drop was the wand
     * being used, and the item stays in your hand.
     */
    @JvmStatic
    fun onDrop(): Boolean {
        if (!enabled || !editMode || wand.isEmpty()) return false
        val player = mc.player ?: return false
        if (identity(player.mainHandItem) != wand) return false
        boxes += BlockPos.containing(player.x, player.y, player.z)
        return true
    }

    /**
     * What makes an item this item: a Skyblock item's own uuid when it has one (that exact item),
     * else its Skyblock id, else the vanilla item and its name.
     */
    private fun identity(stack: ItemStack): String = when {
        stack.isEmpty -> ""
        stack.itemUUID.isNotEmpty() -> "uuid:" + stack.itemUUID
        stack.itemId.isNotEmpty() -> "id:" + stack.itemId
        else -> "item:" + BuiltInRegistries.ITEM.getKey(stack.item) + "|" + stack.hoverName.string
    }
}
