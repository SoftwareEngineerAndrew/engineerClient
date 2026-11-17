package com.engineerclient.terminals.sim

import com.adm.utils.hasGlint
import com.adm.utils.modMessage
import com.engineerclient.terminals.TerminalTypes
import com.engineerclient.terminals.TerminalUtils
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.locale.Language
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

class StartsWithSim(private val letter: String = letters.random()) : TermSimGUI(
    "What starts with: '$letter'?",
    TerminalTypes.STARTS_WITH.windowSize
) {
    // Items that always glint can't show being picked, so those picks are remembered by slot.
    private val pickedGlinting = HashSet<Int>()
    private val first = letter[0]

    override fun create() {
        pickedGlinting.clear()
        val (matching, other) = pools(first)
        setSlots {
            val i = it.index
            when {
                i / 9 !in 1..3 || i % 9 !in 1..7 -> blackPane
                i == (10..16).random() || Math.random() > .7f -> stackOf(matching)
                else -> stackOf(other)
            }
        }
    }

    private fun stackOf(pool: List<Item>) = pool.randomOrNull()?.let(::ItemStack) ?: ItemStack.EMPTY

    private fun matches(stack: ItemStack) = stack.hoverName.string.startsWith(letter, true)
    private fun picked(slot: Slot) = (slot.item.hasGlint() && slot.item.item !in alwaysGlint) || slot.index in pickedGlinting

    override fun slotClick(slot: Slot, button: Int) {
        val stack = slot.item
        if (!matches(stack)) return modMessage("§cThat item does not start with: '$letter'!")
        if (picked(slot)) return modMessage("§cAlready selected!")
        if (stack.item in alwaysGlint) pickedGlinting += slot.index
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        slot.setSlot(stack)
        if (guiInventorySlots.all { !matches(it.item) || picked(it) }) TerminalUtils.lastTermOpened?.onComplete()
        super.slotClick(slot, button)
    }

    private companion object {
        val letters = listOf("A", "B", "C", "G", "D", "M", "N", "R", "S", "T", "W")

        val alwaysGlint: Set<Item> by lazy {
            BuiltInRegistries.ITEM.filterTo(HashSet()) { it.components().has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE) }.apply { add(Items.GOLDEN_APPLE) }
        }

        private val pools = HashMap<Char, Pair<List<Item>, List<Item>>>()
        private var poolsLanguage: Language? = null

        // Split by the name the player reads, which is what the terminal asks about; panes are left out as they fill the frame.
        fun pools(letter: Char): Pair<List<Item>, List<Item>> {
            val language = Language.getInstance()
            if (language !== poolsLanguage) {
                pools.clear()
                poolsLanguage = language
            }
            return pools.getOrPut(letter.uppercaseChar()) { split(letter) }
        }

        private fun split(letter: Char): Pair<List<Item>, List<Item>> {
            val matching = ArrayList<Item>()
            val other = ArrayList<Item>()
            for (item in BuiltInRegistries.ITEM) {
                if (item === Items.AIR || BuiltInRegistries.ITEM.getKey(item).path.contains("pane")) continue
                if (ItemStack(item).hoverName.string.startsWith(letter, true)) matching += item else other += item
            }
            return matching to other
        }
    }
}
