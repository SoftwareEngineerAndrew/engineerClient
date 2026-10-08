package com.engineerclient.leap

import com.odtheking.odin.OdinMod.mc
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.features.impl.dungeon.map.fillRoom
import com.odtheking.odin.features.impl.dungeon.map.tile.DoorType
import com.odtheking.odin.features.impl.dungeon.map.tile.DungeonRoom
import com.odtheking.odin.features.impl.dungeon.map.tile.MapCheckmark
import com.odtheking.odin.features.impl.dungeon.map.tile.RoomType
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import kotlin.math.hypot
import kotlin.math.min

/**
 * The Leap Menu's "Map Leap" view: the dungeon map drawn big in the middle of the screen, with
 * every teammate's head where they are (from their entity when loaded, else Odin's map marker).
 * The head nearest the cursor is enlarged and outlined; clicking leaps to that player.
 *
 * Map space is Odin's: 20 units per room tile (16 room + 4 gap), 116 across for the 6x6 grid.
 */
object LeapMap {
    /** The colours the map is drawn with (Leap Menu settings, importable from other map mods). */
    class Palette(
        val background: Color, val unknownRoom: Color,
        val rooms: Map<RoomType, Color>, val doors: Map<DoorType, Color>, val unopenedDoor: Color,
    )

    private const val TILE = 20f
    private const val GRID = 6 * 20 - 4
    private const val PAD = 6f
    private const val HEAD = 16f            // gui pixels
    private const val HEAD_HOVER = 30f
    private const val PICK_RANGE = 40f      // how far from a head the cursor can be and still pick it

    private val white = Identifier.fromNamespaceAndPath("odin", "map/white_check.png")
    private val green = Identifier.fromNamespaceAndPath("odin", "map/green_check.png")
    private val cross = Identifier.fromNamespaceAndPath("odin", "map/cross.png")

    private class Layout(val x: Float, val y: Float, val scale: Float)

    private fun layout(sizeFraction: Float): Layout {
        val sw = mc.window.guiScaledWidth.toFloat(); val sh = mc.window.guiScaledHeight.toFloat()
        val size = min(sw, sh) * sizeFraction
        val scale = size / (GRID + PAD * 2)
        return Layout((sw - size) / 2f + PAD * scale, (sh - size) / 2f + PAD * scale, scale)
    }

    /** Where a player is on screen: their entity if the game has it, otherwise their map marker. */
    private fun screenPos(player: DungeonPlayer, l: Layout): Pair<Float, Float> {
        val entity = player.entity ?: mc.level?.players()?.firstOrNull { it.name.string == player.name }
        val (mx, mz) = if (entity != null) ((entity.x.toFloat() + 200f) * TILE / 32f) to ((entity.z.toFloat() + 200f) * TILE / 32f)
        else ((player.mapPos.x + 128) / 2f - DungeonScan.startX) to ((player.mapPos.z + 128) / 2f - DungeonScan.startY)
        return (l.x + mx * l.scale) to (l.y + mz * l.scale)
    }

    /** The leap target nearest the cursor (alive, within reach), if any. */
    fun targetAt(targets: List<DungeonPlayer>, mouseX: Float, mouseY: Float, sizeFraction: Float): DungeonPlayer? {
        val l = layout(sizeFraction)
        return targets.filter { !it.isDead }
            .map { it to screenPos(it, l).let { (x, y) -> hypot(x - mouseX, y - mouseY) } }
            .filter { it.second <= PICK_RANGE }
            .minByOrNull { it.second }?.first
    }

    fun render(g: GuiGraphicsExtractor, targets: List<DungeonPlayer>, mouseX: Float, mouseY: Float, sizeFraction: Float, palette: Palette) {
        val l = layout(sizeFraction)
        val pose = g.pose()

        // The map, in map space.
        pose.pushMatrix()
        pose.translate(l.x, l.y)
        pose.scale(l.scale)
        g.fill((-PAD).toInt(), (-PAD).toInt(), (GRID + PAD).toInt(), (GRID + PAD).toInt(), palette.background.rgba)
        for (door in DungeonScan.doors.values) {
            val o = door.rotation.offset
            val half = (16 - 8) / 2f
            pose.pushMatrix()
            pose.translate(door.position.x * TILE + o.x * 16 + o.z * half, door.position.z * TILE + o.z * 16 + o.x * half)
            val color = palette.doors[door.type] ?: palette.unopenedDoor
            g.fill(0, 0, 4 + 4 * o.z, 4 + 4 * o.x, color.rgba)
            pose.popMatrix()
        }
        for (room in DungeonScan.rooms) {
            val color = if (room.isViewable) palette.rooms[room.type] ?: palette.unknownRoom else palette.unknownRoom
            g.fillRoom(room, color.rgba)
        }
        for (room in DungeonScan.rooms) checkmark(g, room)
        pose.popMatrix()

        // Heads on top, in gui pixels so they stay crisp: the hovered one big and outlined.
        val hovered = targetAt(targets, mouseX, mouseY, sizeFraction)
        mc.player?.let { self ->
            val x = l.x + (self.x.toFloat() + 200f) * TILE / 32f * l.scale
            val y = l.y + (self.z.toFloat() + 200f) * TILE / 32f * l.scale
            head(g, self.skin, x, y, HEAD * 0.75f, Colors.WHITE.withAlpha(0.6f), dim = true)
        }
        for (p in targets.sortedBy { it == hovered }) {
            val (x, y) = screenPos(p, l)
            val big = p == hovered
            head(g, p.playerSkin ?: mc.player?.skin, x, y, if (big) HEAD_HOVER else HEAD, p.clazz.color, dim = p.isDead, outline = big)
            if (big || p.isDead) {
                val label = if (p.isDead) "${p.name} (dead)" else p.name
                g.text(label, (x - mc.font.width(label) / 2f).toInt(), (y + HEAD_HOVER / 2f + 3).toInt(), if (p.isDead) Colors.MINECRAFT_RED else p.clazz.color)
            }
        }
    }

    private fun Color.withAlpha(a: Float) = Color(rgba, a)

    private fun head(g: GuiGraphicsExtractor, skin: net.minecraft.world.entity.player.PlayerSkin?, x: Float, y: Float, size: Float, border: Color, dim: Boolean = false, outline: Boolean = false) {
        val s = size.toInt(); val x0 = (x - size / 2f).toInt(); val y0 = (y - size / 2f).toInt()
        val b = if (outline) 3 else 2
        if (outline) g.fill(x0 - b - 1, y0 - b - 1, x0 + s + b + 1, y0 + s + b + 1, Colors.WHITE.rgba)
        g.fill(x0 - b, y0 - b, x0 + s + b, y0 + s + b, border.rgba)
        skin?.let { PlayerFaceExtractor.extractRenderState(g, it, x0, y0, s) }
        if (dim) g.fill(x0, y0, x0 + s, y0 + s, 0x99000000.toInt())
    }

    private fun checkmark(g: GuiGraphicsExtractor, room: DungeonRoom) {
        if (!room.isViewable || room.type == RoomType.ENTRANCE) return
        val icon = when (room.checkmark) {
            MapCheckmark.GREEN -> green
            MapCheckmark.WHITE -> white
            MapCheckmark.RED -> cross
            else -> return
        }
        val size = 12
        val cx = room.center?.x ?: (room.topLeft.x * TILE.toInt() + 8)
        val cz = room.center?.z ?: (room.topLeft.z * TILE.toInt() + 8)
        g.blit(RenderPipelines.GUI_TEXTURED, icon, cx - size / 2, cz - size / 2, size.toFloat(), size.toFloat(), size, size, size, size)
    }
}
