package com.adm.features.impl.dungeon

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.clickgui.settings.impl.NumberSetting
import com.adm.events.LevelEvent
import com.adm.events.RenderExtractEvent
import com.adm.events.core.on
import com.adm.events.core.onReceive
import com.adm.features.Module
import com.adm.utils.Color
import com.adm.utils.render.drawLine
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.protocol.common.ClientboundPingPacket
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt

object MageBeam : Module(
    name = "Mage Beam",
    description = "Replaces the mage beam particles with a custom line."
) {
    private val onlyCancelParticles by BooleanSetting("Only Cancel Particles", false, desc = "Only hide the particles, without drawing a line.")
    private val lineWidth by NumberSetting("Line Width", 2.0, 1.0..10.0, 0.5, desc = "Width of the line.").withDependency { !onlyCancelParticles }
    private val startColor by ColorSetting("Beam Start Color", Color(0, 255, 255), true, desc = "The starting color for the line.").withDependency { !onlyCancelParticles }
    private val endColor by ColorSetting("Beam End Color", Color(0, 255, 255), true, desc = "The ending color for the line.").withDependency { !onlyCancelParticles }
    private val timeToFade by NumberSetting("Beam Time To Fade", 3.0, 0.0..5.0, 0.1, desc = "Time until the line disappears.", unit = "s")

    private const val QUEUE = 1024
    private const val QMASK = QUEUE - 1
    private const val MARKS = 64
    private const val MMASK = MARKS - 1
    private const val MAX_STRIDE = 9
    private const val MIN_POINTS = 9
    private const val RUN_SLOTS = 32
    private const val LINES = 32
    private const val MERGE_TICKS = 5

    // Network thread fills the queue and marks where each server tick ends; the game thread drains it.
    private val queued = arrayOfNulls<ClientboundLevelParticlesPacket>(QUEUE)
    @Volatile private var queueHead = 0
    @Volatile private var queueTail = 0
    private val tickEnd = IntArray(MARKS)
    @Volatile private var markHead = 0
    @Volatile private var markTail = 0
    private val drainTask = Runnable { drain() }

    // One tick's particles, by position in the tick.
    private val px = DoubleArray(QUEUE)
    private val py = DoubleArray(QUEUE)
    private val pz = DoubleArray(QUEUE)
    private val owner = IntArray(QUEUE)

    private val runFirst = IntArray(RUN_SLOTS)
    private val runSecond = IntArray(RUN_SLOTS)
    private val runLast = IntArray(RUN_SLOTS)
    private val runSize = IntArray(RUN_SLOTS)
    private var runs = 0

    // Lines on screen: from (x0,y0,z0) to (x1,y1,z1), at line*6.
    private val line = DoubleArray(LINES * 6)
    private val lineTick = IntArray(LINES)
    private val linePoints = arrayOfNulls<List<Vec3>>(LINES)
    private var lines = 0

    private var tick = 0

    init {
        onReceive<ClientboundLevelParticlesPacket> {
            if (particle.type != ParticleTypes.FIREWORK || count != 1 || !DungeonUtils.inDungeons) return@onReceive
            if (xDist != 0f || yDist != 0f || zDist != 0f || xMaxSpeed != 0f || !overrideLimiter || !alwaysShow()) return@onReceive
            val head = queueHead
            if (head - queueTail >= QUEUE) return@onReceive
            queued[head and QMASK] = this
            queueHead = head + 1
            it.cancel()
        }

        onReceive<ClientboundPingPacket> {
            if (id >= 0) return@onReceive
            val m = markHead
            if (m - markTail >= MARKS) tickEnd[(m - 1) and MMASK] = queueHead
            else {
                tickEnd[m and MMASK] = queueHead
                markHead = m + 1
            }
            mc.execute(drainTask)
        }

        on<RenderExtractEvent> {
            if (onlyCancelParticles || lines == 0) return@on
            val from = startColor
            val to = endColor
            val width = lineWidth.toFloat()
            for (i in 0 until lines) drawLine(linePoints[i]!!, from, to, true, width)
        }

        on<LevelEvent.Load> { forget() }
    }

    override fun onDisable() {
        super.onDisable()
        forget()
    }

    private fun forget() {
        val head = queueHead
        while (queueTail != head) {
            queued[queueTail and QMASK] = null
            queueTail++
        }
        markTail = markHead
        for (i in 0 until lines) linePoints[i] = null
        lines = 0
    }

    private fun drain() {
        while (markTail != markHead) {
            val end = tickEnd[markTail and MMASK]
            tick++
            fade()
            val start = queueTail
            if (end - start > 0) {
                handleTick(start, end - start)
                queueTail = end
            }
            markTail++
        }
    }

    private fun fade() {
        val limit = timeToFade / 0.05
        var i = 0
        while (i < lines) {
            if (tick - lineTick[i] > limit) removeLine(i) else i++
        }
    }

    private fun removeLine(i: Int) {
        val last = --lines
        if (i != last) {
            System.arraycopy(line, last * 6, line, i * 6, 6)
            lineTick[i] = lineTick[last]
            linePoints[i] = linePoints[last]
        }
        linePoints[last] = null
    }

    private fun handleTick(start: Int, n: Int) {
        for (k in 0 until n) {
            val p = queued[(start + k) and QMASK]!!
            px[k] = p.x; py[k] = p.y; pz[k] = p.z
            owner[k] = -1
        }

        runs = 0
        for (k in 0 until n) if (!extendRun(k)) openRun(k)

        for (r in 0 until runs) if (runSize[r] >= MIN_POINTS) addSegment(runFirst[r], runLast[r])

        val conn = mc.connection
        for (k in 0 until n) {
            val slot = (start + k) and QMASK
            val p = queued[slot]!!
            queued[slot] = null
            val r = owner[k]
            if (r >= 0 && runSize[r] >= MIN_POINTS) continue
            if (conn != null && !nearLine(px[k], py[k], pz[k])) p.handle(conn)
        }
    }

    private fun dist(a: Int, b: Int): Double {
        val dx = px[a] - px[b]; val dy = py[a] - py[b]; val dz = pz[a] - pz[b]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    // b lies on the segment a..c when going through it adds no length.
    private fun between(a: Int, b: Int, c: Int, slack: Double): Boolean =
        dist(a, b) + dist(b, c) <= dist(a, c) + slack

    private fun extendRun(k: Int): Boolean {
        for (r in 0 until runs) {
            if (!between(runFirst[r], runSecond[r], k, 1e-2)) continue
            runLast[r] = k
            runSize[r]++
            owner[k] = r
            return true
        }
        return false
    }

    // Beams can come interleaved, so look for a free pair behind this point at any stride up to MAX_STRIDE.
    private fun openRun(k: Int) {
        if (runs == RUN_SLOTS) return
        for (s in 1..MAX_STRIDE) {
            val b = k - s
            val a = b - s
            if (a < 0) return
            if (owner[a] != -1 || owner[b] != -1 || dist(a, b) < 1e-4 || !between(a, b, k, 1e-3)) continue
            val r = runs++
            runFirst[r] = a; runSecond[r] = b; runLast[r] = k; runSize[r] = 3
            owner[a] = r; owner[b] = r; owner[k] = r
            return
        }
    }

    private fun addSegment(a: Int, b: Int) {
        val ax = px[a]; val ay = py[a]; val az = pz[a]
        val bx = px[b]; val by = py[b]; val bz = pz[b]

        for (i in 0 until lines) {
            if (tick - lineTick[i] > MERGE_TICKS) continue
            val o = i * 6
            val vx = line[o + 3] - line[o]; val vy = line[o + 4] - line[o + 1]; val vz = line[o + 5] - line[o + 2]
            if ((bx - ax) * vx + (by - ay) * vy + (bz - az) * vz <= 0.0) continue
            if (offLine(o, ax, ay, az) >= 0.01 || offLine(o, bx, by, bz) >= 0.01) continue
            setLine(i, line[o], line[o + 1], line[o + 2], bx, by, bz)
            return
        }

        if (lines == LINES) {
            var oldest = 0
            for (i in 1 until lines) if (lineTick[i] < lineTick[oldest]) oldest = i
            removeLine(oldest)
        }
        setLine(lines++, ax, ay, az, bx, by, bz)
    }

    private fun setLine(i: Int, x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double) {
        val o = i * 6
        line[o] = x0; line[o + 1] = y0; line[o + 2] = z0
        line[o + 3] = x1; line[o + 4] = y1; line[o + 5] = z1
        lineTick[i] = tick
        linePoints[i] = listOf(Vec3(x0, y0, z0), Vec3(x1, y1, z1))
    }

    // Squared distance from a point to the whole (unbounded) line at offset o.
    private fun offLine(o: Int, x: Double, y: Double, z: Double): Double {
        val vx = line[o + 3] - line[o]; val vy = line[o + 4] - line[o + 1]; val vz = line[o + 5] - line[o + 2]
        val wx = x - line[o]; val wy = y - line[o + 1]; val wz = z - line[o + 2]
        val cx = wy * vz - wz * vy; val cy = wz * vx - wx * vz; val cz = wx * vy - wy * vx
        return (cx * cx + cy * cy + cz * cz) / (vx * vx + vy * vy + vz * vz)
    }

    private fun nearLine(x: Double, y: Double, z: Double): Boolean {
        for (i in 0 until lines) if (offLine(i * 6, x, y, z) < 1.0) return true
        return false
    }
}
