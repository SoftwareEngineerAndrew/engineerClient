package com.odtheking.odin.features.impl.dungeon

import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.render.drawLine
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.protocol.common.ClientboundPingPacket
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.world.phys.Vec3
import java.util.BitSet
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
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

    private class Beam(
        val x0: Double, val y0: Double, val z0: Double,
        var x1: Double, var y1: Double, var z1: Double,
        var tick: Int,
    )

    private val bucket = AtomicReference(ConcurrentLinkedQueue<ClientboundLevelParticlesPacket>())
    private val renderedBeams = mutableListOf<Beam>()
    private var serverTicks = 0

    init {
        onReceive<ClientboundPingPacket> {
            if (id >= 0) return@onReceive
            val parts = bucket.getAndSet(ConcurrentLinkedQueue())
            mc.execute { onServerTick(parts.toList()) }
        }

        onReceive<ClientboundLevelParticlesPacket> {
            if (!DungeonUtils.inDungeons || particle.type != ParticleTypes.FIREWORK) return@onReceive
            if (count != 1 || xDist != 0f || yDist != 0f || zDist != 0f || !overrideLimiter || !alwaysShow() || xMaxSpeed != 0f) return@onReceive
            bucket.get().add(this)
            it.cancel()
        }

        on<RenderExtractEvent> {
            if (onlyCancelParticles) return@on
            for (beam in renderedBeams)
                drawLine(listOf(Vec3(beam.x0, beam.y0, beam.z0), Vec3(beam.x1, beam.y1, beam.z1)), startColor, endColor, true, lineWidth.toFloat())
        }

        on<LevelEvent.Load> {
            bucket.get().clear()
            renderedBeams.clear()
        }
    }

    override fun onDisable() {
        super.onDisable()
        bucket.get().clear()
        renderedBeams.clear()
    }

    private fun dist(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double): Double =
        sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by) + (az - bz) * (az - bz))

    private fun onServerTick(parts: List<ClientboundLevelParticlesPacket>) {
        val tick = ++serverTicks
        renderedBeams.removeIf { tick - it.tick > timeToFade / 0.05 }
        if (parts.isEmpty()) return

        val newBeams = mutableListOf<Beam>()
        val dontSend = BitSet(parts.size)
        for (step in intArrayOf(1, 9)) {
            var i = 0
            while (i < parts.size - step * 2) {
                if (dontSend.get(i) || dontSend.get(i + step) || dontSend.get(i + step * 2)) {
                    i++
                    continue
                }
                val p0 = parts[i]
                val p1 = parts[i + step]
                val p2 = parts[i + step * 2]
                val d01 = dist(p0.x, p0.y, p0.z, p1.x, p1.y, p1.z)
                if (d01 + dist(p1.x, p1.y, p1.z, p2.x, p2.y, p2.z) > dist(p0.x, p0.y, p0.z, p2.x, p2.y, p2.z) + 1e-3) {
                    i++
                    continue
                }

                val xs = mutableListOf(p0.x, p1.x, p2.x)
                val ys = mutableListOf(p0.y, p1.y, p2.y)
                val zs = mutableListOf(p0.z, p1.z, p2.z)

                val start = i
                i += step * 3
                var count = 0
                while (i < parts.size) {
                    if (dontSend.get(i)) break
                    val p = parts[i]
                    if (d01 + dist(p1.x, p1.y, p1.z, p.x, p.y, p.z) > dist(p0.x, p0.y, p0.z, p.x, p.y, p.z) + 1e-2) break
                    xs.add(p.x)
                    ys.add(p.y)
                    zs.add(p.z)
                    count++
                    i += step
                }

                val end = i
                if (step != 1) i = start + 1
                if (count < 6) continue

                if (step == 1) dontSend.set(start, end)
                else for (j in start until end step step) dontSend.set(j)

                val (m, v) = fitLine(xs, ys, zs)
                val vv = v[0] * v[0] + v[1] * v[1] + v[2] * v[2]
                val first = parts[start]
                val last = parts[end - step]
                val u0 = -((m[0] - first.x) * v[0] + (m[1] - first.y) * v[1] + (m[2] - first.z) * v[2]) / vv
                val uf = -((m[0] - last.x) * v[0] + (m[1] - last.y) * v[1] + (m[2] - last.z) * v[2]) / vv
                newBeams.add(Beam(
                    m[0] + v[0] * u0, m[1] + v[1] * u0, m[2] + v[2] * u0,
                    m[0] + v[0] * uf, m[1] + v[1] * uf, m[2] + v[2] * uf,
                    tick
                ))
            }
        }

        for (beam in newBeams) {
            val ux = beam.x1 - beam.x0
            val uy = beam.y1 - beam.y0
            val uz = beam.z1 - beam.z0
            val matching = renderedBeams.find {
                val vx = it.x1 - it.x0
                val vy = it.y1 - it.y0
                val vz = it.z1 - it.z0
                if (abs(uy * vz - uz * vy) + abs(uz * vx - ux * vz) + abs(ux * vy - uy * vx) > 1e-2) return@find false
                if (ux * vx + uy * vy + uz * vz < 0.0) return@find false
                val u = -((beam.x0 - it.x0) * ux + (beam.y0 - it.y0) * uy + (beam.z0 - it.z0) * uz) / (ux * ux + uy * uy + uz * uz)
                abs(beam.x0 + ux * u - it.x0) + abs(beam.y0 + uy * u - it.y0) + abs(beam.z0 + uz * u - it.z0) < 1e-1
            }

            if (matching == null || tick - matching.tick > 5) renderedBeams.add(beam)
            else {
                matching.x1 = beam.x1
                matching.y1 = beam.y1
                matching.z1 = beam.z1
                matching.tick = tick
            }
        }

        val conn = mc.connection ?: return
        parts.forEachIndexed { i, p ->
            if (dontSend.get(i)) return@forEachIndexed
            val onBeam = renderedBeams.any {
                val vx = it.x1 - it.x0
                val vy = it.y1 - it.y0
                val vz = it.z1 - it.z0
                val u = -((it.x0 - p.x) * vx + (it.y0 - p.y) * vy + (it.z0 - p.z) * vz) / (vx * vx + vy * vy + vz * vz)
                val gx = it.x0 + vx * u - p.x
                val gy = it.y0 + vy * u - p.y
                val gz = it.z0 + vz * u - p.z
                gx * gx + gy * gy + gz * gz < 1.0
            }
            if (!onBeam) p.handle(conn)
        }
    }

    private fun fitLine(x: List<Double>, y: List<Double>, z: List<Double>): Pair<DoubleArray, DoubleArray> {
        val mean = doubleArrayOf(x.average(), y.average(), z.average())
        val cols = arrayOf(x, y, z)
        val m = Array(3) { r -> DoubleArray(3) { c -> x.indices.sumOf { cols[r][it] * (cols[c][it] - mean[c]) } } }

        var b = doubleArrayOf(1.0, 0.0, 0.0)
        repeat(50) {
            val v = DoubleArray(3) { r -> m[r][0] * b[0] + m[r][1] * b[1] + m[r][2] * b[2] }
            val norm = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            if (norm < 1e-12) return mean to b
            val next = DoubleArray(3) { v[it] / norm }
            val diff = max(abs(b[0] - next[0]), max(abs(b[1] - next[1]), abs(b[2] - next[2])))
            b = next
            if (diff < 1e-10) return mean to b
        }
        return mean to b
    }
}
