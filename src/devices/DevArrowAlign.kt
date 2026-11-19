package com.engineerclient.devices

import com.engineerclient.EngineerClient.mc
import com.adm.clickgui.settings.impl.KeybindSetting.Companion.isDown
import com.adm.events.EntityEvent
import com.adm.events.EntityInteractEvent
import com.adm.events.RenderExtractEvent
import com.adm.events.TickEvent
import com.adm.events.core.on
import com.adm.utils.render.drawText
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.adm.utils.skyblock.dungeon.M7Phases
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/** Arrow Align: how many clicks each frame needs, and wrong clicks blocked. */
object DevArrowAlign {
    private const val WALL_X = -2
    private const val BOTTOM_Y = 120
    private const val LEFT_Z = 75
    private const val SIDE = 5
    private const val FRAMES = SIDE * SIDE

    // The dev recorder reads these four by name.
    private val recentClickTimestamps = LongArray(FRAMES)
    private val clicksRemaining = IntArray(FRAMES)
    private var currentFrameRotations: IntArray? = null
    private var targetSolution: IntArray? = null

    private val rotations = IntArray(FRAMES)
    /** Each frame's rotation as the server last sent it (-1 for no arrow), and our clicks it hasn't echoed yet. */
    private val serverRot = IntArray(FRAMES) { -1 }
    private val pending = IntArray(FRAMES)
    private val frames = arrayOfNulls<ItemFrame>(FRAMES)
    private var cached = false
    private var framesToClick = 0
    private val deviceCenter = BlockPos(0, 120, 77)
    private val grid = AABB(WALL_X - 1.0, BOTTOM_Y.toDouble(), LEFT_Z.toDouble(), WALL_X + 2.0, (BOTTOM_Y + SIDE).toDouble(), (LEFT_Z + SIDE).toDouble())

    private val labelAt = Array(FRAMES) { Vec3.atCenterOf(BlockPos(WALL_X, BOTTOM_Y + it % SIDE, LEFT_Z + it / SIDE)).add(-0.3, 0.1, 0.0) }
    private val label = Array(8) { n -> "§" + (if (n < 3) 'a' else if (n < 5) '6' else 'c') + n }

    private val active get() = Devices.arrowAlignOn && DungeonUtils.getF7Phase() == M7Phases.P3

    private val refresh = Runnable { if (active) refresh() }

    fun install() {
        on<TickEvent.Server> { if (active) mc.execute(refresh) }

        on<EntityEvent.SetData> {
            val frame = entity as? ItemFrame ?: return@on
            if (!cached) return@on
            val pos = frame.blockPosition()
            val i = slot(pos.x, pos.y, pos.z)
            if (i < 0 || !active) return@on
            frames[i] = frame
            val now = if (frame.item.item == Items.ARROW) frame.rotation else -1
            val was = serverRot[i]
            // Any turn the server shows uses up that many of our clicks still on their way.
            if (was >= 0 && now >= 0) pending[i] = maxOf(0, pending[i] - (now - was + 8) % 8) else pending[i] = 0
            serverRot[i] = now
            solve()
        }

        on<EntityInteractEvent> {
            val frame = entity as? ItemFrame ?: return@on
            if (frame.item.item != Items.ARROW || DungeonUtils.getF7Phase() != M7Phases.P3) return@on
            val pos = frame.blockPosition()
            val i = slot(pos.x, pos.y, pos.z)
            if (i < 0) return@on
            val rot = currentFrameRotations
            if (rot != null && rot[i] == -1) return@on

            if (clicksRemaining[i] == 0 && Devices.alignBlockWrong && !(Devices.preventKey.isDown() xor Devices.invertKey)) {
                cancel()
                return@on
            }

            recentClickTimestamps[i] = System.currentTimeMillis()
            if (rot == null) return@on
            pending[i]++
            solve()
        }

        on<RenderExtractEvent> {
            if (framesToClick == 0 || !active) return@on
            for (i in 0 until FRAMES) {
                val n = clicksRemaining[i]
                if (n != 0) drawText(label[n], labelAt[i], 1f, false)
            }
        }
    }

    fun forget() {
        cached = false
        frames.fill(null)
        serverRot.fill(-1)
        pending.fill(0)
        clicksRemaining.fill(0)
        framesToClick = 0
        currentFrameRotations = null
        targetSolution = null
    }

    private fun refresh() {
        val player = mc.player ?: return
        if (player.blockPosition().distSqr(deviceCenter) > 200) {
            if (cached || currentFrameRotations != null) forget()
            return
        }
        if (cached && frames.none { it != null && it.isRemoved }) return
        readFrames()
        solve()
    }

    private fun readFrames() {
        frames.fill(null)
        serverRot.fill(-1)
        pending.fill(0)
        val level = mc.level ?: return
        for (e in level.getEntitiesOfClass(ItemFrame::class.java, grid)) {
            val pos = e.blockPosition()
            val i = slot(pos.x, pos.y, pos.z)
            if (i < 0 || frames[i] != null) continue
            frames[i] = e
            if (e.item.item == Items.ARROW) serverRot[i] = e.rotation
        }
        cached = frames.any { it != null }
    }

    private fun solve() {
        clicksRemaining.fill(0)
        framesToClick = 0
        var anyArrow = false
        for (i in 0 until FRAMES) if (serverRot[i] >= 0) anyArrow = true
        for (i in 0 until FRAMES) rotations[i] = if (!anyArrow || serverRot[i] < 0) -1 else (serverRot[i] + pending[i]) % 8
        currentFrameRotations = rotations

        for (solution in SOLUTIONS) {
            if (!fits(solution)) continue
            targetSolution = solution
            for (i in 0 until FRAMES) {
                val n = clicks(rotations[i], solution[i])
                if (n == 0) continue
                if (clicksRemaining[i] == 0) framesToClick++
                clicksRemaining[i] = n
            }
        }
    }

    private fun fits(solution: IntArray): Boolean {
        for (i in 0 until FRAMES) if ((solution[i] == -1 || rotations[i] == -1) && solution[i] != rotations[i]) return false
        return true
    }

    private fun slot(x: Int, y: Int, z: Int): Int {
        val row = y - BOTTOM_Y
        val col = z - LEFT_Z
        if (x != WALL_X || row !in 0 until SIDE || col !in 0 until SIDE) return -1
        return row + col * SIDE
    }

    private fun clicks(from: Int, to: Int): Int = (8 - from + to) % 8

    private val SOLUTIONS = arrayOf(
        intArrayOf(7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, -1, -1, -1, 7, 1),
        intArrayOf(-1, -1, 7, 7, 5, -1, 7, 1, -1, 5, -1, -1, -1, -1, -1, -1, 7, 5, -1, 1, -1, -1, 7, 7, 1),
        intArrayOf(7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, -1, 7, 5, -1, -1, -1, -1, 5, -1, -1, -1, 3, 3),
        intArrayOf(5, 3, 3, 3, -1, 5, -1, -1, -1, -1, 7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, -1),
        intArrayOf(5, 3, 3, 3, 3, 5, -1, -1, -1, 1, 7, 7, -1, -1, 1, -1, -1, -1, -1, 1, -1, 7, 7, 7, 1),
        intArrayOf(7, 7, 7, 7, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, -1, 7, 7, 7, 1),
        intArrayOf(-1, -1, -1, -1, -1, 1, -1, 1, -1, 1, 1, -1, 1, -1, 1, 1, -1, 1, -1, 1, -1, -1, -1, -1, -1),
        intArrayOf(-1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, 7, 7, 7, 7, 1, -1, -1, -1, -1, -1),
        intArrayOf(-1, -1, -1, -1, -1, -1, 1, -1, 1, -1, 7, 1, 7, 1, 3, 1, -1, 1, -1, 1, -1, -1, -1, -1, -1),
    )
}
