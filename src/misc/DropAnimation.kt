package com.engineerclient.misc

import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * State behind the "No Drop Animation" toggle in [RandomStuff].
 *
 * Hypixel rejects a dropped dungeon item, so vanilla shows the held item vanish and then rise
 * again. While a drop is remembered here the first-person renderer keeps drawing that item fully
 * raised ([com.engineerclient.mixin.FirstPersonDropAnimationMixin]), until you change item or slot,
 * attack, or [FIX_IN_TICKS] pass.
 */
object DropAnimation {
    private const val FIX_IN_TICKS = 20

    private var scheduledFix = 0

    /** The stack that was in hand when drop was pressed, or null when nothing is remembered. */
    @JvmStatic
    var droppedItem: ItemStack? = null
        private set

    /** The hotbar slot the drop was pressed in, or -1. */
    @JvmStatic
    var selectedSlot = -1
        private set

    /** True while the module is on, the toggle is on and you are in a dungeon. */
    @JvmStatic
    fun shouldProceed(): Boolean = RandomStuff.enabled && RandomStuff.noDropAnimation && DungeonUtils.inDungeons

    /** Remembers the dropped stack. Air is ignored, as a drop of nothing has no animation to hide. */
    @JvmStatic
    fun setDroppedData(stack: ItemStack, slot: Int) {
        if (stack.item == Items.AIR) return
        droppedItem = stack
        selectedSlot = slot
        scheduledFix = FIX_IN_TICKS
    }

    @JvmStatic
    fun clearData() {
        droppedItem = null
        selectedSlot = -1
        scheduledFix = 0
    }

    /** Called once per client tick; forgets the drop when its time is up. */
    @JvmStatic
    fun tick() {
        if (droppedItem == null) return
        if (scheduledFix <= 0) clearData() else scheduledFix--
    }
}
