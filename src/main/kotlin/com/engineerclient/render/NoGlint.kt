package com.engineerclient.render

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.boss.termsim.TermSimGUI
import com.odtheking.odin.utils.skyblock.dungeon.terminals.TerminalUtils
import net.minecraft.world.item.ItemDisplayContext

/**
 * Drops the enchantment glint off item rendering. Every Skyblock weapon, piece of armour and
 * most of the junk in a dungeon inventory carries enchantments, so the glint is on nearly
 * everything at once — it washes out item colours in the hotbar and turns a full inventory into
 * a moving surface, which is exactly the wrong thing to be reading a chest through at speed.
 *
 * Purely a render-side change: nothing here touches the stack, its components, or
 * [net.minecraft.world.item.ItemStack.hasFoil] itself. That matters, because Odin's terminal
 * solvers decide what has been clicked from the `ENCHANTMENT_GLINT_OVERRIDE` *component*
 * (`ItemUtils.hasGlint`), not from what is drawn — so Select All and Starts With keep solving
 * correctly no matter what this module hides.
 *
 * What they cannot do is tell *you* which items you have already clicked, since for a human that
 * cue is the glint on the screen. Hence [keepInTerminals]: while a real terminal or a term sim is
 * open, inventory glint is left alone.
 *
 * Hooked at the two `ItemModel.update` implementations that set a foil type
 * (see ItemFoilMixin) and at worn-equipment rendering (see ArmorFoilMixin).
 */
object NoGlint : Module(
    name = "No Enchant Glint",
    category = Category.custom("Blood Rush"),
    description = "Removes the enchantment glint from items, so colours and textures stay readable.",
    toggled = true, // existing installs have no saved state for a new module; on by default
) {
    private val inInventory by BooleanSetting("Inventory", true, desc = "Hide the glint on items drawn in a GUI — inventory, chests, the hotbar.")
    private val inWorld by BooleanSetting("Held & Dropped", true, desc = "Hide the glint on items held in hand, dropped on the ground, or in item frames.")
    private val onArmor by BooleanSetting("Worn Armor", true, desc = "Hide the glint on armour worn by you and by other players.")
    private val keepInTerminals by BooleanSetting("Keep In Terminals", true, desc = "Leave inventory glint alone while a terminal is open — it is how you see which items you have already clicked.")

    /**
     * Whether the glint should be dropped for an item about to be drawn in [context].
     *
     * Called once per item layer whenever a model is resolved — every frame for items in the
     * world, and on every miss of the GUI item atlas — so it stays a handful of field reads: no
     * allocation, no screen walk beyond the identity check Odin's terminal state already gives us.
     */
    fun hidesGlint(context: ItemDisplayContext): Boolean {
        if (!enabled) return false
        return if (context == ItemDisplayContext.GUI) inInventory && !(keepInTerminals && inTerminal())
        else inWorld
    }

    /** Whether the glint should be dropped for a piece of worn equipment. */
    fun hidesArmorGlint(): Boolean = enabled && onArmor

    /** A live terminal (Odin tracks the open one) or a practice term sim. */
    private fun inTerminal(): Boolean =
        TerminalUtils.currentTerm != null || mc.screen is TermSimGUI
}
