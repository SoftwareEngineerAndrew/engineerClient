package com.adm.features.impl.dungeon.puzzlesolvers

import com.adm.AdmMod.mc
import com.adm.events.RenderExtractEvent
import com.adm.events.RoomEnterEvent
import com.adm.utils.Color
import com.adm.utils.render.BoxBatch
import com.adm.utils.render.drawBoxes
import com.adm.utils.render.drawTracer
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// Method names are kept: a mixin elsewhere switches this solver off by name.
object TPMazeSolver {
    private const val ROOM = "Teleport Maze"
    private const val PADS = 30

    // Room-relative pad frames, seven cells of four, then the end and start pads.
    private val frameX = intArrayOf(4, 4, 10, 10, 4, 4, 10, 10, 4, 4, 10, 10, 12, 12, 18, 18, 20, 20, 26, 26, 26, 26, 20, 20, 26, 26, 20, 20, 15, 15)
    private val frameZ = intArrayOf(12, 6, 12, 6, 20, 14, 20, 14, 28, 22, 28, 22, 28, 22, 28, 22, 28, 22, 28, 22, 20, 14, 20, 14, 12, 6, 12, 6, 14, 12)

    private val idle = Color(255, 255, 255, 0.5f)

    @Volatile private var pads: Array<BlockPos>? = null
    private val visited = BooleanArray(PADS)
    private val possible = BooleanArray(PADS)
    @Volatile private var possibleCount = 0
    @Volatile private var target = -1

    private val shapeState = arrayOfNulls<BlockState>(PADS)
    private val shapeBox = arrayOfNulls<AABB>(PADS)
    private val boxes = BoxBatch()

    fun onRoomEnter(event: RoomEnterEvent) {
        val room = event.room ?: return
        if (room.data?.name != ROOM) return
        pads = Array(PADS) { room.getRealCoords(BlockPos(frameX[it], 69, frameZ[it])) }
        shapeState.fill(null)
    }

    fun tpPacket(event: ClientboundPlayerPositionPacket) {
        val p = event.change.position
        if (p.y != 69.5 || p.x % 0.5 != 0.0 || p.z % 0.5 != 0.0) return
        val pads = pads ?: return
        if (DungeonUtils.currentRoomName != ROOM) return
        val body = mc.player?.boundingBox

        var landed = -1
        for (i in 0 until PADS) {
            val pad = pads[i]
            val near = touches(pad, p.x - 1, p.y, p.z - 1, p.x + 2, p.y + 1, p.z + 2)
            if (near && landed < 0) landed = i
            if (near || (body != null && touches(pad, body.minX - 1, body.minY, body.minZ - 1, body.maxX + 1, body.maxY, body.maxZ + 1))) visited[i] = true
        }
        narrow(pads, p, event.change.yRot, event.change.xRot, body)

        if (landed < 0) return
        if (landed >= 28) {
            target = -1
            return
        }
        val cell = landed / 4 * 4
        var pick = -1
        var pickByLook = -1
        var bestTurn = Float.MAX_VALUE
        val facing = Mth.wrapDegrees(event.change.yRot)
        for (i in cell until cell + 4) {
            if (i == landed || visited[i]) continue
            if (possible[i]) {
                pick = i
                break
            }
            val pad = pads[i]
            val yaw = (atan2(pad.z + 0.5 - p.z, pad.x + 0.5 - p.x) * 180.0 / Math.PI).toFloat() - 90f
            val turn = abs(Mth.wrapDegrees(yaw) - facing)
            if (turn < bestTurn) {
                bestTurn = turn
                pickByLook = i
            }
        }
        target = if (pick >= 0) pick else pickByLook
    }

    private fun narrow(pads: Array<BlockPos>, p: Vec3, yaw: Float, pitch: Float, body: AABB?) {
        if (possibleCount == 0) possible.fill(true)
        var count = 0
        for (i in 0 until PADS) {
            if (!possible[i]) continue
            val pad = pads[i]
            val keep = !visited[i] && body != null &&
                lookCrosses(pad.x - 0.75, pad.z - 0.75, pad.x + 1.75, pad.z + 1.75, p, yaw, pitch) &&
                !touches(pad, body.minX, body.minY, body.minZ, body.maxX, body.maxY, body.maxZ, grow = 0.5)
            possible[i] = keep
            if (keep) count++
        }
        possibleCount = count
    }

    private fun touches(pad: BlockPos, minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double, grow: Double = 0.0): Boolean {
        val x0 = pad.x - grow
        val z0 = pad.z - grow
        return minX < pad.x + 1 + grow && maxX > x0 && minY < pad.y + 1.0 && maxY > pad.y.toDouble() && minZ < pad.z + 1 + grow && maxZ > z0
    }

    // Whether a 32 block look ray from the landing spot crosses one of the box's four sides, in X/Z only.
    private fun lookCrosses(minX: Double, minZ: Double, maxX: Double, maxZ: Double, p: Vec3, yaw: Float, pitch: Float): Boolean {
        val flat = -cos(-pitch * 0.017453292f).toDouble()
        val lx = sin(-yaw * 0.017453292f - 3.1415927f) * flat
        val lz = cos(-yaw * 0.017453292f - 3.1415927f) * flat
        val sx = p.x
        val sz = p.z
        val dx = (sx + lx * 32.0) - sx
        val dz = (sz + lz * 32.0) - sz
        return alongX(sx, sz, dx, dz, minX, minZ, maxZ) || alongX(sx, sz, dx, dz, maxX, minZ, maxZ) ||
            alongX(sz, sx, dz, dx, minZ, minX, maxX) || alongX(sz, sx, dz, dx, maxZ, minX, maxX)
    }

    private fun alongX(s: Double, o: Double, d: Double, od: Double, at: Double, lo: Double, hi: Double): Boolean {
        if (d * d < 1e-8) return false
        val t = (at - s) / d
        if (t < 0.0 || t > 1.0) return false
        val cross = o + od * t
        return cross >= lo && cross <= hi
    }

    fun onRenderWorld(event: RenderExtractEvent, mazeColorOne: Color, mazeColorMultiple: Color, mazeColorVisited: Color, showTracer: Boolean, tracerColor: Color) {
        val pads = pads ?: return
        if (DungeonUtils.currentRoomName != ROOM) return
        val level = mc.level ?: return
        val possibleColor = if (possibleCount == 1) mazeColorOne else mazeColorMultiple
        boxes.clear()
        for (i in 0 until PADS) {
            val pad = pads[i]
            val box = padBox(level, i, pad)
            when {
                possible[i] && possibleCount > 0 -> boxes.filled(box, possibleColor, false)
                visited[i] -> boxes.filled(box, mazeColorVisited, true)
                else -> boxes.filled(box, idle, true)
            }
        }
        event.drawBoxes(boxes)

        if (showTracer) {
            val t = target
            if (t < 0) return
            val pad = pads[t]
            event.drawTracer(Vec3(pad.x + 0.5, pad.y + 0.8, pad.z + 0.5), tracerColor, false)
        }
    }

    private fun padBox(level: Level, i: Int, pad: BlockPos): AABB {
        val state = level.getBlockState(pad)
        if (state !== shapeState[i]) {
            shapeState[i] = state
            val shape = state.getShape(level, pad).singleEncompassing()
            shapeBox[i] = if (shape.isEmpty) AABB(pad) else shape.bounds().move(pad)
        }
        return shapeBox[i]!!
    }

    fun reset() {
        possible.fill(false)
        possibleCount = 0
        visited.fill(false)
        target = -1
    }
}
