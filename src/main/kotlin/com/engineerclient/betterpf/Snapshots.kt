package com.engineerclient.betterpf

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Rotations
import net.minecraft.core.component.DataComponents
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState

/** What [BetterPF] drives: the recorder, or (verify mode) the recorder and the old one side by side. */
interface RecorderApi {
    val abandoned: Boolean
    fun onTick(level: ClientLevel)
    fun onFrame(partialTick: Float, yaw: Float, pitch: Float)
    fun onBlockUpdate(pos: BlockPos, state: BlockState)
    fun onServerTick()
    fun onSlotClick(slot: Int, button: Int, type: String)
    fun onBlockUse(pos: BlockPos)
    fun onTeleport(x: Double, y: Double, z: Double, yaw: Float, pitch: Float, rel: List<String>)
    fun onBatSound(x: Double, y: Double, z: Double, volume: Float)
    fun onPickup(itemId: Int, collectorId: Int)
    fun onChat(message: String, colored: String?)
    fun onChestEvent(pos: BlockPos, openCount: Int)
    fun onRoomEnter(name: String?)
    fun onGuiClose()
    fun onContainerOpen(title: String, menu: String, w: Int, h: Int, slots: List<IntArray>)
    fun onContainerTick(items: List<ItemStack>, carried: ItemStack)
    fun onContainerMouse(partialTick: Float, x: Float, y: Float)
    fun onContainerClick(x: Float, y: Float, button: Int)
    fun finish()
}

// What the game thread copies out of the game for the writer thread ([RunWriter]): numbers, strings,
// and references to objects the game never changes once made (items, block states, identifiers,
// armour stand poses), so lines can be built off the game thread without reading live game state.

/** The parts of an item stack [RunWriter.vanillaId] reads. */
internal class ItemSnap private constructor(val item: Item?, val model: Identifier?, val dyed: Boolean, val dye: Int) {
    companion object {
        val EMPTY = ItemSnap(null, null, false, 0)

        fun of(stack: ItemStack): ItemSnap {
            if (stack.isEmpty) return EMPTY
            val dye = stack.get(DataComponents.DYED_COLOR)
            return ItemSnap(stack.item, stack.get(DataComponents.ITEM_MODEL), dye != null, dye?.rgb() ?: 0)
        }
    }
}

/** Held item and armour - main hand, head, chest, legs, feet - and the head's skin when it's a player head. */
internal class EquipSnap(val items: Array<ItemSnap>, val headTex: String?)

/** An armour stand's flags (small, invisible, arms, no base plate, marker) and six poses. */
internal class StandSnap(val flags: Int, val poses: Array<Rotations>)

internal class EntitySnap(
    val id: Int,
    val type: EntityType<*>,
    val x: Double, val y: Double, val z: Double,
    val yaw: Float, val headYaw: Float,
    val living: Boolean,
    val baby: Boolean,
    val name: String,
    val colored: String,
    val block: BlockState?,
    val isItem: Boolean,
    val itemName: String,
    val item: ItemSnap,
    val stand: StandSnap?,
    val frame: ItemSnap?,
    val frameRot: Int,
    val equipment: EquipSnap?,
)

internal class PlayerSnap(
    val name: String,
    val skin: String?,
    val equipment: EquipSnap,
    val x: Double, val y: Double, val z: Double,
    val yaw: Float, val pitch: Float,
    val skyblockId: String,
    val uuidVersion: Int,
    val crouching: Boolean,
    val heldHeadTex: String,
)

/** A teammate's dungeon map marker in world coordinates; [shown] false when the game renders them (or they're dead). */
internal class MapSnap(val name: String, val shown: Boolean, val x: Double, val z: Double, val yaw: Float)

internal class RoomSnap(
    val name: String, val type: String, val shape: String, val rotation: String, val checkmark: String,
    /** x, z of each tile. */
    val tiles: IntArray,
    val found: Int, val max: Int, val key: String?,
)

internal class SlotSnap(val item: ItemSnap, val count: Int, val tex: String?, val name: String, val glint: Int) {
    companion object {
        val EMPTY = SlotSnap(ItemSnap.EMPTY, 0, null, "", 0)
    }
}

internal class SkullSnap(val x: Int, val y: Int, val z: Int, val tex: String)
