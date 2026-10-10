package com.devgineerclient.waypoints

import com.engineerclient.waypoints.BoxFaces
import com.engineerclient.waypoints.BrWaypoints2
import com.engineerclient.waypoints.Face

import com.devgineerclient.DevgineerClient
import com.devgineerclient.DevgineerClient.mc
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.ModuleManager
import com.devgineerclient.waypoints.PositionalMessages.PosMessage
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.render.drawCylinder
import com.odtheking.odin.utils.render.drawFilledBox
import com.odtheking.odin.utils.render.drawWireFrameBox
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Edits the positional-message shapes (/posmsg) in game with the same wand and feel as
 * [BrWaypoints2]'s role boxes. Toggled with `/posmsg edit`; the wand is BR Roles' ("Make Held
 * Item Wand"). With it on and the wand in hand:
 *
 *  - A box (/posmsg in): look through it to select the side behind, stand inside and look up to
 *    select its top ([BoxFaces]). Left click or scroll up pushes the face out a block, right click
 *    or scroll down pulls it in; never thinner than a block, bottom fixed. Drop deletes it.
 *  - A cylinder (/posmsg at: a point and a radius, no corners): select it by looking at it; it has
 *    one "face", its rim, so every push/pull grows/shrinks the radius a block (min 1). Drop deletes.
 *
 * Edits replace the entry in [PositionalMessages.posMessageStrings] (PosMessage is
 * immutable; message, delay, colour and send flag are kept) and call Odin's config save, so the
 * stored format is the one Odin's module had, unchanged. Creating boxes stays with /posmsg (they need a message).
 */
object PosMsgEditor : BrWaypoints2.WandUser {

    private var editMode = false
    private var useHeld = false
    private const val REACH = 48.0
    private val PURPLE = Color(170, 0, 170, 0.35f)

    private class Shape(val i: Int, val msg: PosMessage) {
        val isBox get() = msg.x2 != null && msg.y2 != null && msg.z2 != null
        val radius get() = msg.distance ?: 1.0
        val min: DoubleArray get() = if (isBox) doubleArrayOf(minOf(msg.x, msg.x2!!), minOf(msg.y, msg.y2!!), minOf(msg.z, msg.z2!!))
            else doubleArrayOf(msg.x - radius, msg.y, msg.z - radius)
        val max: DoubleArray get() = if (isBox) doubleArrayOf(maxOf(msg.x, msg.x2!!), maxOf(msg.y, msg.y2!!), maxOf(msg.z, msg.z2!!))
            else doubleArrayOf(msg.x + radius, msg.y + cylHeight(), msg.z + radius)
    }

    private fun cylHeight(): Double = (PositionalMessages.settings["Height"]?.value as? Number)?.toDouble() ?: 1.0

    @Suppress("UNCHECKED_CAST")
    private fun list(): MutableList<PosMessage> = PositionalMessages.posMessageStrings as MutableList<PosMessage>

    fun toggle() {
        editMode = !editMode
        DevgineerClient.msg("§dPosMsg §7edit mode " + if (editMode) "§aon§7 (wand in hand; BR Roles' wand)" else "§coff")
        if (editMode && !BrWaypoints2.wandInHand()) DevgineerClient.msg("§7Set the wand first: BR Roles > Edit Mode > Make Held Item Wand.")
    }

    override fun tick() {
        if (!mc.options.keyUse.isDown) useHeld = false
        if (PositionalMessages.retrigger) rearm()
    }

    /** Re-arms every sent posmsg you are no longer in (the module's own tests: box contains your position, radius by 3D distance). */
    private fun rearm() {
        val p = mc.player ?: return
        val sent = PositionalMessages.sentMessages
        if (sent.isEmpty()) return
        val pos = p.position()
        sent.removeIf { m ->
            val box = m.box
            if (box != null) !box.contains(pos)
            else { val r2 = m.radiusSquared; r2 != null && p.distanceToSqr(m.x, m.y, m.z) > r2 }
        }
    }

    private fun editing() = editMode && mc.gui.screen() == null && BrWaypoints2.wandInHand()

    init {
        on<RenderExtractEvent> {
            if (!editMode) return@on
            // The module draws the shapes itself while Show Positions is on; if it is off, show them here.
            val odinDraws = PositionalMessages.settings["Show Positions"]?.value == true
            if (!odinDraws) for (s in shapes()) {
                if (s.isBox) drawWireFrameBox(AABB(s.min[0], s.min[1], s.min[2], s.max[0], s.max[1], s.max[2]), s.msg.color, 1f, false)
                else drawCylinder(Vec3(s.msg.x, s.msg.y, s.msg.z), s.radius.toFloat(), cylHeight().toFloat(), s.msg.color)
            }
            if (editing()) target(mc.deltaTracker.getGameTimeDeltaPartialTick(true))?.let { (s, face) ->
                if (s.isBox) drawFilledBox(BrWaypoints2.faceSlab(AABB(s.min[0], s.min[1], s.min[2], s.max[0], s.max[1], s.max[2]), face), PURPLE, false)
                else drawCylinder(Vec3(s.msg.x, s.msg.y, s.msg.z), s.radius.toFloat(), cylHeight().toFloat(), PURPLE)
            }
        }
    }

    private fun shapes(): List<Shape> = list().mapIndexed { i, m -> Shape(i, m) }

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
            // A cylinder has one editable thing, its radius; EAST is a stand-in for it.
            val face = if (s.isBox) BoxFaces.select(eye, dir, s.min, s.max) ?: continue else Face.EAST
            best = s to face
            bestT = t
        }
        return best
    }

    /** Scroll up / left click (+1) or scroll down / right click (-1). True swallows the input. */
    override fun onMove(by: Int): Boolean {
        if (!editing()) return false
        val (s, face) = target(1f) ?: return false
        val m = s.msg
        // Sneaking: the whole box (or the radius's centre) moves up or down a block instead.
        if (mc.player?.isShiftKeyDown == true) {
            list()[s.i] = m.copy(y = m.y + by, y2 = m.y2?.plus(by))
            ModuleManager.saveConfigurations()
            return true
        }
        val next = if (s.isBox) {
            val c = doubleArrayOf(s.min[0], s.min[1], s.min[2], s.max[0], s.max[1], s.max[2])
            if (!BoxFaces.move(c, face, by)) return true
            m.copy(x = c[0], y = c[1], z = c[2], x2 = c[3], y2 = c[4], z2 = c[5])
        } else {
            val r = s.radius + by
            if (r < 1.0) return true
            m.copy(distance = r)
        }
        list()[s.i] = next
        ModuleManager.saveConfigurations()
        return true
    }

    override fun blocksContinueAttack(): Boolean = editing() && target(1f) != null

    override fun onUse(): Boolean {
        if (!editing() || target(1f) == null) return false
        if (!useHeld) { useHeld = true; onMove(-1) }
        return true
    }

    /** Drop: delete the shape you are looking at. True means the drop must not happen. */
    override fun onDrop(): Boolean {
        if (!editing()) return false
        val (s, _) = target(1f) ?: return false
        list().removeAt(s.i)
        ModuleManager.saveConfigurations()
        DevgineerClient.msg("§dPosMsg §7deleted: §f${s.msg.message}")
        return true
    }

    /** A 1x1x1 posmsg box on the block your feet are in, sending [text] (no delay, white, sent), saved with the module. */
    fun addHere(text: String) {
        val p = mc.player ?: return
        val x = Math.floor(p.x); val y = Math.floor(p.y); val z = Math.floor(p.z)
        list().add(PosMessage(x, y, z, x + 1, y + 1, z + 1, 0, null, com.odtheking.odin.utils.Colors.WHITE, text, false))
        ModuleManager.saveConfigurations()
        DevgineerClient.msg("§dPosmsg §7added §f\"$text\" §7at ${x.toInt()}, ${y.toInt()}, ${z.toInt()}")
    }
}
