package com.coffeeclient.misc

import com.coffeeclient.CoffeeClient
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onSend
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.dungeon.map.tile.RoomType
import com.odtheking.odin.utils.customData
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Termsim (the name only - nothing to do with terminals): where new things go, each its own
 * toggle with its settings under it.
 *
 *  - No Rotate (devoniansolo's, DocilElm): for a moment after you teleport with an etherwarp /
 *    AOTV / Instant Transmission or a Hype, the server turning your camera with the teleport is
 *    ignored - you keep looking where you were, and the server is told you took its rotation
 *    (NoRotateMixin). Never in F7/M7's boss, trap rooms, Teleport Maze or Boulder, where the
 *    server's facing matters.
 */
object Termsim : Module(
    name = "Termsim",
    key = null,
    category = Category.custom("Coffee Client", 860, 10),
    description = "Where new things go: No Rotate.",
) {
    private val noRotate by BooleanSetting("No Rotate", true, desc = "A teleport item's teleport (etherwarp, AOTV, Hype) doesn't turn your camera. Not in the F7 boss, traps, Teleport Maze or Boulder.")
    private val timeLimit by NumberSetting("Time Limit", 300.0, 0.0..3000.0, 10.0, unit = "ms", desc = "How long after the right click a teleport's rotation is still ignored.")
        .withDependency { noRotate }
    private val allowHype by BooleanSetting("Allow Hype", true, desc = "Wither blades with 2+ scrolls count too.")
        .withDependency { noRotate }
    private val allowInstant by BooleanSetting("Allow Instant Transmission", true, desc = "AOTE/AOTV right clicks count, not only etherwarps (sneaking with Etherwarp Merger).")
        .withDependency { noRotate }

    private var switchedOn by BooleanSetting("Switched On", false, desc = "").hide()

    /** On by default, once (turning it off sticks). */
    fun enableByDefault() {
        if (switchedOn) return
        switchedOn = true
        if (!enabled) toggle()
        com.odtheking.odin.features.ModuleManager.saveConfigurations()
    }

    // --- No Rotate --------------------------------------------------------------------------------

    private val WITHER_BLADES = setOf("HYPERION", "VALKYRIE", "SCYLLA", "ASTRAEA")
    private val ETHERWARPS = setOf("ASPECT_OF_THE_END", "ASPECT_OF_THE_VOID", "ETHERWARP_CONDUIT")
    private val NO_ROTATE_ROOMS = setOf("Teleport Maze", "Boulder")

    private var lastClick = -1L

    init {
        onSend<ServerboundUseItemPacket> { onRightClick() }
        onSend<ServerboundUseItemOnPacket> { onRightClick() }
        on<LevelEvent.Load> { lastClick = -1L }
    }

    private fun onRightClick() {
        if (!noRotate) return
        if (!noRotateHere()) { lastClick = -1L; return }
        val held = CoffeeClient.mc.player?.mainHandItem ?: return
        if (counts(held)) lastClick = System.currentTimeMillis()
    }

    private fun noRotateHere(): Boolean {
        if (DungeonUtils.isFloor(7) && DungeonUtils.inBoss) return false
        val room = DungeonUtils.currentRoom ?: return true
        if (room.type == RoomType.TRAP) return false
        return room.data?.name !in NO_ROTATE_ROOMS
    }

    /** A teleport's rotation is ignored right now (NoRotateMixin). */
    @JvmStatic
    fun noRotateActive(): Boolean = enabled && noRotate && noRotateHere() && System.currentTimeMillis() - lastClick < timeLimit

    private fun counts(item: ItemStack): Boolean {
        val id = item.itemId.ifEmpty { return false }
        val attributes = item.customData
        val isEther = id in ETHERWARPS
        if (isEther && !allowInstant && (item.item == Items.DIAMOND_SHOVEL || item.item == Items.DIAMOND_SWORD)) {
            if (!attributes.contains("ethermerge")) return false
            if (CoffeeClient.mc.player?.isSteppingCarefully != true) return false
        }
        val isHype = id in WITHER_BLADES
        if (isHype && (attributes.getList("ability_scroll").orElse(null) ?: return false).size < 2) return false
        return isEther || (isHype && allowHype)
    }
}
