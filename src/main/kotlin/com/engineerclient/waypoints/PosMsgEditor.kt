package com.engineerclient.waypoints

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.ModuleManager
import com.odtheking.odin.features.impl.render.waypoints.Trigger
import com.odtheking.odin.features.impl.render.waypoints.Waypoint
import com.odtheking.odin.features.impl.render.waypoints.WaypointAreas
import com.odtheking.odin.features.impl.render.waypoints.Waypoints
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.render.drawCylinder
import com.odtheking.odin.utils.render.drawFilledBox
import com.odtheking.odin.utils.render.drawWireFrameBox
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Edits Odin's positional-message shapes in game with the same wand and feel as [BrWaypoints2]'s
 * role boxes. Since Odin 0.3.6 they are its Waypoints (a box or a radius, an area such as F7
 * Terminals, and a command such as "pc ee2"); /posmsg is gone. Toggled with `/ec posmsg edit`; the
 * wand is BR Roles' ("Make Held Item Wand"). With it on and the wand in hand, for the waypoints of
 * the area you are in:
 *
 *  - A box: look through it to select the side behind, stand inside and look up to select its top
 *    ([BoxFaces]). Left click or scroll up pushes the face out a block, right click or scroll down
 *    pulls it in; never thinner than a block, bottom fixed. Drop deletes it.
 *  - A radius (a block and a radius, no corners): select it by looking at it; it has one "face",
 *    its rim, so every push/pull grows/shrinks the radius a block (min 1). Drop deletes.
 *
 * Edits replace the waypoint through Odin's own [Waypoints.submit] (Waypoint is immutable; label,
 * colour, trigger and command are kept) and call Odin's config save, so the stored format is
 * Odin's, unchanged. Creating them stays with Odin's Waypoint Manager, or [addHere].
 */
object PosMsgEditor {

    private var editMode = false
    private var useHeld = false
    private const val REACH = 48.0
    /** Odin draws a radius waypoint as a cylinder this tall. */
    private const val CYLINDER_HEIGHT = 0.2
    private val PURPLE = Color(170, 0, 170, 0.35f)

    private class Shape(val wp: Waypoint) {
        val isBox get() = wp.radius == null
        val radius get() = wp.radius ?: 1
        val min: DoubleArray get() = wp.box.let { doubleArrayOf(it.minX, it.minY, it.minZ) }
        val max: DoubleArray get() = wp.box.let {
            doubleArrayOf(it.maxX, if (isBox) it.maxY else it.minY + CYLINDER_HEIGHT, it.maxZ)
        }
    }

    fun toggle() {
        editMode = !editMode
        EngineerClient.msg("§dPosMsg §7edit mode " + if (editMode) "§aon§7 (wand in hand; BR Roles' wand)" else "§coff")
        if (editMode && !BrWaypoints2.wandInHand()) EngineerClient.msg("§7Set the wand first: BR Roles > Edit Mode > Make Held Item Wand.")
    }

    fun tick() {
        if (!mc.options.keyUse.isDown) useHeld = false
    }

    /** Dungeon proximity waypoints sent this world (by id), cleared on a world load. */
    private val sent = HashSet<String>()

    /**
     * Whether Odin may run this waypoint's command now. Odin 0.3.6 runs a proximity waypoint every
     * time you walk in; /posmsg sent each once per world, which the rotation's arrival texts rely
     * on. So dungeon ones stay once per world unless BR Roles' Posmsg Re-trigger is on.
     */
    @JvmStatic
    fun allowFire(waypoint: Any): Boolean {
        val wp = waypoint as? Waypoint ?: return true
        if (wp.trigger != Trigger.PROXIMITY || BrWaypoints2.posmsgRetrigger || !isDungeonArea(wp.area)) return true
        return sent.add(wp.id)
    }

    /** Odin's dungeon areas: FLOOR_1..5, and F6_/F7_ boss phases. */
    private fun isDungeonArea(area: String) = area.startsWith("FLOOR_") || area.startsWith("F6_") || area.startsWith("F7_")

    private fun editing() = editMode && mc.screen == null && BrWaypoints2.wandInHand()

    init {
        on<LevelEvent.Load> { sent.clear() }

        on<RenderExtractEvent> {
            if (!editMode) return@on
            // Odin draws the waypoints itself while its Waypoints module is on; if it is off, show them here.
            if (!Waypoints.enabled) for (s in shapes()) {
                if (s.isBox) drawWireFrameBox(s.wp.box, s.wp.color, 1f, false)
                else drawCylinder(s.wp.cylinderBase, s.radius.toFloat(), CYLINDER_HEIGHT.toFloat(), s.wp.color)
            }
            if (editing()) target(mc.deltaTracker.getGameTimeDeltaPartialTick(true))?.let { (s, face) ->
                if (s.isBox) drawFilledBox(BrWaypoints2.faceSlab(s.wp.box, face), PURPLE, false)
                else drawCylinder(s.wp.cylinderBase, s.radius.toFloat(), CYLINDER_HEIGHT.toFloat(), PURPLE)
            }
        }
    }

    /** The waypoints Odin shows here: enabled ones of the area you are in. */
    private fun shapes(): List<Shape> {
        val area = WaypointAreas.current()?.key ?: return emptyList()
        return Waypoints.waypoints.filter { it.enabled && it.area == area }.map(::Shape)
    }

    private fun target(partial: Float): Pair<Shape, Face>? {
        val player = mc.player ?: return null
        val e = player.getEyePosition(partial)
        val v = player.getViewVector(partial)
        val eye = doubleArrayOf(e.x, e.y, e.z)
        val dir = doubleArrayOf(v.x, v.y, v.z)
        val feet = doubleArrayOf(player.x, player.y, player.z)
        val pitch = player.getViewXRot(partial)
        val all = shapes()
        for (s in all) if (s.isBox && BoxFaces.editsTop(feet, pitch, s.min, s.max)) return s to Face.UP
        var best: Pair<Shape, Face>? = null
        var bestT = REACH
        for (s in all) {
            val t = BoxFaces.distance(eye, dir, s.min, s.max) ?: continue
            if (t > bestT) continue
            // A radius has one editable thing, its size; EAST is a stand-in for it.
            val face = if (s.isBox) BoxFaces.select(eye, dir, s.min, s.max) ?: continue else Face.EAST
            best = s to face
            bestT = t
        }
        return best
    }

    private fun Waypoint.with(blockPos: BlockPos = this.blockPos, endPos: BlockPos = this.endPos, radius: Int? = this.radius) =
        Waypoint(area, blockPos, endPos, label, color, trigger, command, radius, enabled)

    private fun replace(old: Waypoint, new: Waypoint) {
        Waypoints.submit(new, old)?.let { EngineerClient.msg("§dPosMsg §c$it") } ?: ModuleManager.saveConfigurations()
    }

    /** Scroll up / left click (+1) or scroll down / right click (-1). True swallows the input. */
    @JvmStatic
    fun onMove(by: Int): Boolean {
        if (!editing()) return false
        val (s, face) = target(1f) ?: return false
        val wp = s.wp
        // Sneaking: the whole box (or the radius's centre) moves up or down a block instead.
        if (mc.player?.isShiftKeyDown == true) {
            replace(wp, wp.with(blockPos = wp.blockPos.above(by), endPos = wp.endPos.above(by)))
            return true
        }
        val next = if (s.isBox) {
            val c = doubleArrayOf(s.min[0], s.min[1], s.min[2], s.max[0], s.max[1], s.max[2])
            if (!BoxFaces.move(c, face, by)) return true
            // The box covers whole blocks from blockPos to endPos, both included.
            wp.with(
                blockPos = BlockPos(c[0].toInt(), c[1].toInt(), c[2].toInt()),
                endPos = BlockPos(c[3].toInt() - 1, c[4].toInt() - 1, c[5].toInt() - 1)
            )
        } else {
            val r = s.radius + by
            if (r < 1) return true
            wp.with(radius = r)
        }
        replace(wp, next)
        return true
    }

    @JvmStatic
    fun blocksContinueAttack(): Boolean = editing() && target(1f) != null

    @JvmStatic
    fun onUse(): Boolean {
        if (!editing() || target(1f) == null) return false
        if (!useHeld) { useHeld = true; onMove(-1) }
        return true
    }

    /** Drop: delete the shape you are looking at. True means the drop must not happen. */
    @JvmStatic
    fun onDrop(): Boolean {
        if (!editing()) return false
        val (s, _) = target(1f) ?: return false
        Waypoints.remove(s.wp)
        ModuleManager.saveConfigurations()
        EngineerClient.msg("§dPosMsg §7deleted: §f${s.wp.label.ifEmpty { s.wp.command ?: "" }}")
        return true
    }

    /**
     * A 1x1x1 Odin waypoint on the block your feet are in, for the area you are in, that sends
     * [text] to party chat when you walk in (white, labelled with the text), saved in Odin's config.
     */
    fun addHere(text: String) {
        val p = mc.player ?: return
        val area = WaypointAreas.current() ?: run {
            EngineerClient.msg("§dPosmsg §cNot in an area Odin's waypoints know (a dungeon boss, Kuudra or an island).")
            return
        }
        val pos = p.blockPosition()
        val wp = Waypoint(area.key, pos, pos, text, com.odtheking.odin.utils.Colors.WHITE, Trigger.PROXIMITY, "pc $text")
        Waypoints.submit(wp, null)?.let { EngineerClient.msg("§dPosmsg §c$it"); return }
        ModuleManager.saveConfigurations()
        EngineerClient.msg("§dPosmsg §7added §f\"$text\" §7at ${pos.x}, ${pos.y}, ${pos.z} §8(${area.label})")
    }
}
