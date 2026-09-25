package com.engineerclient.waypoints

import com.odtheking.odin.clickgui.settings.impl.ActionSetting
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.StringSetting
import com.odtheking.odin.events.RenderEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.itemUUID
import com.odtheking.odin.utils.modMessage
import com.odtheking.odin.utils.render.drawFilledBox
import com.odtheking.odin.utils.render.drawStyledBox
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB

/**
 * An in-game box editor, driven by a "wand": any item you pick with "Make Held Item Wand". With
 * Edit Mode on and the wand in hand:
 *
 *  - Drop places a 1x1x1 box on the block at your feet.
 *  - Look at a box to select one of its faces, then left click or scroll up to push that face out
 *    a block, right click or scroll down to pull it back in a block.
 *
 * A box is the block it was placed on plus how far each face has been pushed out from that block.
 * None of those can go below zero, so a box never shrinks past its own block — which is also what
 * keeps it at least 1x1x1. The bottom never moves.
 *
 * Which face is selected is [BoxFaces]' job: the far one, or the top from any angle.
 *
 * Boxes live in memory for now; nothing saves them yet.
 */
object BrWaypoints2 : Module(
    name = "BR Waypoints 2",
    category = Category.custom("Engineer Client"),
    description = "In-game box editor. Pick a wand, turn on Edit Mode, drop the wand to place a box, click or scroll to size it.",
) {

    private val editMode by BooleanSetting("Edit Mode", false, desc = "Edits only happen while this is on. Off, the wand is just an item.")

    private val makeWand by ActionSetting("Make Held Item Wand", desc = "Makes the item in your hand the wand, the tool the editor is used with.") {
        val held = mc.player?.mainHandItem
        if (held == null || held.isEmpty) return@ActionSetting modMessage("§cHold the item you want as the wand first.")
        wand = identity(held)
        modMessage("§aWand set: §f${held.hoverName.string}")
    }

    /** Which item is the wand, saved with the config so it survives a restart. */
    private var wand by StringSetting("Wand", "", 256, desc = "The wand's identity.").hide()

    /** A box: the block it was placed on, and how many blocks each face is pushed out from it. */
    class Box(val origin: BlockPos) {
        val out = IntArray(Face.entries.size)

        fun aabb() = AABB(
            (origin.x - out[Face.WEST.ordinal]).toDouble(), origin.y.toDouble(), (origin.z - out[Face.NORTH.ordinal]).toDouble(),
            (origin.x + 1 + out[Face.EAST.ordinal]).toDouble(), (origin.y + 1 + out[Face.UP.ordinal]).toDouble(), (origin.z + 1 + out[Face.SOUTH.ordinal]).toDouble(),
        )
    }

    private val boxes = mutableListOf<Box>()
    private var useHeld = false

    private val BOX_COLOUR = Color(0, 255, 221, 1f)
    private val FACE_COLOUR = Color(0, 255, 221, 0.35f)

    /** How far away a box can be selected from. */
    private const val REACH = 48.0

    init {
        on<RenderEvent.Extract> {
            for (box in boxes) drawStyledBox(box.aabb(), BOX_COLOUR, 1, true)
            val (box, face) = target(mc.deltaTracker.getGameTimeDeltaPartialTick(true)) ?: return@on
            drawFilledBox(faceSlab(box.aabb(), face), FACE_COLOUR, true)
        }

        // Right click repeats every few ticks while held; a pull is one per press.
        on<TickEvent.End> { if (!mc.options.keyUse.isDown) useHeld = false }
    }

    // --- input, called from the mixins -----------------------------------------------------------

    /** Drop: place a box. True means the drop was the wand being used and must not happen. */
    @JvmStatic
    fun onDrop(): Boolean {
        if (!editing()) return false
        val player = mc.player ?: return false
        val feet = BlockPos.containing(player.x, player.y, player.z)
        if (boxes.none { it.origin == feet }) boxes += Box(feet)
        return true
    }

    /** Left click: push the selected face out. True cancels the swing. */
    @JvmStatic
    fun onAttack(): Boolean = move(+1)

    /** Holding left click: swallowed while a face is selected, so the block behind is not mined. */
    @JvmStatic
    fun blocksContinueAttack(): Boolean = editing() && target(1f) != null

    /** Right click: pull the selected face in, once per press. True cancels using the wand. */
    @JvmStatic
    fun onUse(): Boolean {
        if (!editing() || target(1f) == null) return false
        if (!useHeld) { useHeld = true; move(-1) }
        return true
    }

    /** Scroll: up pushes out, down pulls in. True keeps the hotbar from switching off the wand. */
    @JvmStatic
    fun onScroll(y: Double): Boolean = if (y == 0.0) false else move(if (y > 0) +1 else -1)

    private fun move(by: Int): Boolean {
        if (!editing()) return false
        val (box, face) = target(1f) ?: return false
        box.out[face.ordinal] = (box.out[face.ordinal] + by).coerceAtLeast(0)
        return true
    }

    private fun editing(): Boolean {
        if (!enabled || !editMode || wand.isEmpty() || mc.screen != null) return false
        return identity(mc.player?.mainHandItem ?: return false) == wand
    }

    // --- selection -------------------------------------------------------------------------------

    /** The box under the crosshair, nearest first, and the face of it that is selected ([BoxFaces]). */
    private fun target(partial: Float): Pair<Box, Face>? {
        val player = mc.player ?: return null
        val e = player.getEyePosition(partial)
        val v = player.getViewVector(partial)
        val eye = doubleArrayOf(e.x, e.y, e.z)
        val dir = doubleArrayOf(v.x, v.y, v.z)
        var best: Pair<Box, Face>? = null
        var bestT = REACH
        for (box in boxes) {
            val bb = box.aabb()
            val min = doubleArrayOf(bb.minX, bb.minY, bb.minZ)
            val max = doubleArrayOf(bb.maxX, bb.maxY, bb.maxZ)
            val t = BoxFaces.distance(eye, dir, min, max) ?: continue
            if (t > bestT) continue
            val face = BoxFaces.select(eye, dir, min, max) ?: continue
            best = box to face
            bestT = t
        }
        return best
    }

    /** A thin slab lying on one face of a box, to show which face is selected. */
    private fun faceSlab(bb: AABB, face: Face): AABB {
        val e = 0.02
        return when (face) {
            Face.EAST -> AABB(bb.maxX - e, bb.minY, bb.minZ, bb.maxX + e, bb.maxY, bb.maxZ)
            Face.WEST -> AABB(bb.minX - e, bb.minY, bb.minZ, bb.minX + e, bb.maxY, bb.maxZ)
            Face.SOUTH -> AABB(bb.minX, bb.minY, bb.maxZ - e, bb.maxX, bb.maxY, bb.maxZ + e)
            Face.NORTH -> AABB(bb.minX, bb.minY, bb.minZ - e, bb.maxX, bb.maxY, bb.minZ + e)
            Face.UP -> AABB(bb.minX, bb.maxY - e, bb.minZ, bb.maxX, bb.maxY + e, bb.maxZ)
        }
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
