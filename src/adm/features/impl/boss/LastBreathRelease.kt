package com.adm.features.impl.boss

import com.adm.AdmMod.mc
import com.adm.events.RenderExtractEvent
import com.adm.utils.Color
import com.adm.utils.ServerUtils
import com.adm.utils.itemId
import com.adm.utils.playSoundAtPlayer
import com.adm.utils.render.BoxStyle
import com.adm.utils.render.drawStyledBox
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.adm.utils.skyblock.dungeon.M7Phases
import com.adm.utils.toFixed
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// A full draw's arrow as Hypixel flies it: from your eye - 0.1, a hair right, at 3 x your look; it moves that tick,
// then x0.99 and -0.05 a tick. It lands as the dragon spawns when it's inside the dragon's box on the spawn tick.
object LastBreathRelease {
    private const val SPEED = 3.0
    private const val G = 0.05
    private const val RIGHT = 0.01745
    private const val MAX_TICKS = 160

    // Each dragon's way out of its spawn, the same every run: offsets from the spawn every 5 ticks, then it rises 0.1 a tick.
    private const val PATH_STEP = 5
    private val PATHS = mapOf(
        WitherDragonsEnum.Red to doubleArrayOf(0.0, 0.0, 0.0, 0.7, 1.0, -0.5, 1.7, 2.1, -0.8, 2.8, 3.7, -1.3, 3.5, 4.5, -1.6, 4.0, 4.9, -2.0, 4.0, 5.5, -2.0, 4.0, 5.8, -2.0, 4.0, 6.3, -2.0),
        WitherDragonsEnum.Orange to doubleArrayOf(0.0, 0.0, 0.0, -0.8, 0.8, 0.5, -2.3, 1.7, 1.1, -3.8, 2.7, 1.5, -5.2, 3.8, 2.3, -6.0, 4.6, 2.5, -5.7, 4.9, 2.5, -5.5, 5.5, 2.5, -5.5, 5.9, 2.5),
        WitherDragonsEnum.Green to doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.5, -1.0, -0.5, 0.8, -2.7, -1.0, 1.7, -4.3, -1.0, 2.5, -5.0, -1.0, 2.9, -5.0, -1.0, 3.5, -5.0, -1.0, 3.9, -5.0, -1.0, 4.4, -5.0),
        WitherDragonsEnum.Blue to doubleArrayOf(0.0, 0.0, 0.0, -0.5, 0.5, 1.2, -1.2, 1.2, 2.8, -1.8, 1.8, 4.5, -2.0, 2.6, 5.0, -2.0, 3.1, 5.0, -2.0, 3.6, 5.0, -2.0, 4.0, 5.0, -2.0, 4.6, 5.0),
        WitherDragonsEnum.Purple to doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.1, 0.0, 0.0, 0.5, 0.0, 0.0, 0.7, 0.0, 0.0, 1.3, 0.0, 0.0, 1.7, 0.0, 0.0, 2.2, 0.0),
    )

    /** Ticks until the middle of the release window for your current aim; null: no dragon coming or it doesn't reach. */
    private var releaseIn: Double? = null
    private var window = 0
    /** Released now: ticks after the spawn it hits (-1: it misses); only when past the window. */
    private var lateBy = -1
    private var aimYaw = 0f
    private var aimPitch: Float? = null
    private var target: WitherDragonsEnum? = null

    private val xs = DoubleArray(MAX_TICKS + 1)
    private val ys = DoubleArray(MAX_TICKS + 1)
    private val zs = DoubleArray(MAX_TICKS + 1)
    private var flown = 0

    fun tick(ding: Boolean, bunch: Boolean) {
        val player = mc.player
        val dragon = WitherDragons.spawnOrder.firstOrNull { it.state == WitherDragonState.SPAWNING && it.timeToSpawn > 0 }
            ?: WitherDragonsEnum.entries.filter { it.state == WitherDragonState.SPAWNING && it.timeToSpawn > 0 }.minByOrNull { it.timeToSpawn }
        if (player == null || dragon == null || DungeonUtils.getF7Phase() != M7Phases.P5 || player.mainHandItem.itemId != "LAST_BREATH") {
            releaseIn = null; aimPitch = null; target = null
            return
        }
        target = dragon
        val eye = player.y + if (player.isCrouching) 1.27 else 1.62
        // a release now reaches the server half a ping on, and the timer here is half a ping behind it
        val need = dragon.timeToSpawn - ServerUtils.averagePing / 50.0
        val before = releaseIn
        fly(player.x, eye, player.z, player.yRot, player.xRot, dragon.spawnPos.y.toDouble())
        val w = onTime(dragon)
        releaseIn = w?.let { need - (it.first + it.last) / 2.0 }
        window = w?.let { it.last - it.first } ?: 0
        lateBy = if (w != null && need < w.first) firstHit(dragon, need.toInt()) else -1
        val now = releaseIn
        if (ding && before != null && now != null && before > 0 && now <= 0) playSoundAtPlayer(SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 2f)

        aimPitch = null
        if (!bunch) return
        val c = dragon.spawnPos
        aimYaw = Math.toDegrees(atan2(-(c.x + 0.5 - player.x), c.z + 0.5 - player.z)).toFloat()
        var best = Double.MAX_VALUE
        var pitch = -89f
        while (pitch <= 30f) {
            fly(player.x, eye, player.z, aimYaw, pitch, c.y.toDouble())
            onTime(dragon)?.let { if (need >= it.first && need <= it.last) { val off = abs(need - (it.first + it.last) / 2.0); if (off < best) { best = off; aimPitch = pitch } } }
            pitch += 0.5f
        }
    }

    fun render(event: RenderExtractEvent, color: Color) {
        val pitch = aimPitch ?: return
        val player = mc.player ?: return
        val yr = Math.toRadians(aimYaw.toDouble()); val pr = Math.toRadians(pitch.toDouble())
        val d = 10.0
        val x = player.x - sin(yr) * cos(pr) * d
        val y = player.eyeY - sin(pr) * d
        val z = player.z + cos(yr) * cos(pr) * d
        event.drawStyledBox(AABB(x - 0.15, y - 0.15, z - 0.15, x + 0.15, y + 0.15, z + 0.15), color, BoxStyle.FILLED, false)
    }

    fun hudText(): String? {
        val dragon = target ?: return null
        val name = "§${dragon.colorCode}${dragon.name}"
        val left = releaseIn ?: return "$name §7out of reach" + aimText()
        val half = window / 2.0
        return when {
            left > half -> "$name §frelease in §e${(left / 20).toFixed(2)}s §7(±${(half / 20).toFixed(2)})" + aimText()
            left >= -half -> "$name §a§lRELEASE"
            lateBy >= 0 -> "$name §clate: hits ${(lateBy / 20f).toFixed(2)}s after it spawns" + aimText()
            else -> "$name §clate: misses" + aimText()
        }
    }

    private fun aimText(): String = if (aimPitch != null) " §7(aim at the marker)" else ""

    /** The ender dragon's box [t] ticks after it spawned. */
    private fun box(dragon: WitherDragonsEnum, t: Int): AABB {
        val p = PATHS.getValue(dragon)
        val last = p.size / 3 - 1
        val f = (t.toDouble() / PATH_STEP).coerceAtMost(last.toDouble())
        val i = f.toInt().coerceAtMost(last - 1)
        val a = f - i
        fun at(axis: Int) = p[3 * i + axis] + (p[3 * (i + 1) + axis] - p[3 * i + axis]) * a
        val rise = if (t > last * PATH_STEP) (t - last * PATH_STEP) * 0.1 else 0.0
        val s = dragon.spawnPos
        val x = s.x + at(0); val y = s.y + at(1) + rise; val z = s.z + at(2)
        return AABB(x - 8.0, y, z - 8.0, x + 8.0, y + 8.0, z + 8.0)
    }

    /** Releases ([first]..[last] ticks before the spawn) that put the arrow in the dragon's box on its spawn tick. */
    private fun onTime(dragon: WitherDragonsEnum): IntRange? {
        val b = box(dragon, 0)
        var first = -1; var last = -1
        for (k in 1..flown) if (crosses(b, k)) { if (first < 0) first = k; last = k } else if (first >= 0) break
        return if (first < 0) null else first..last
    }

    /** Released [r] ticks before the spawn: ticks after the spawn the arrow hits, or -1. */
    private fun firstHit(dragon: WitherDragonsEnum, r: Int): Int {
        for (k in maxOf(1, r)..flown) if (crosses(box(dragon, k - r), k)) return k - r
        return -1
    }

    private fun crosses(b: AABB, k: Int): Boolean =
        b.contains(xs[k], ys[k], zs[k]) || b.clip(Vec3(xs[k - 1], ys[k - 1], zs[k - 1]), Vec3(xs[k], ys[k], zs[k])).isPresent

    /** The arrow's place at the end of each tick (0: where it leaves), till it falls below [floor]. */
    private fun fly(px: Double, eye: Double, pz: Double, yaw: Float, pitch: Float, floor: Double) {
        val yr = Math.toRadians(yaw.toDouble()); val pr = Math.toRadians(pitch.toDouble())
        val cy = cos(yr); val sy = sin(yr); val cp = cos(pr)
        var vx = -sy * cp * SPEED; var vy = -sin(pr) * SPEED; var vz = cy * cp * SPEED
        xs[0] = px - cy * RIGHT; ys[0] = eye - 0.1; zs[0] = pz - sy * RIGHT
        flown = 0
        for (k in 1..MAX_TICKS) {
            xs[k] = xs[k - 1] + vx; ys[k] = ys[k - 1] + vy; zs[k] = zs[k - 1] + vz
            flown = k
            vx *= 0.99; vy = vy * 0.99 - G; vz *= 0.99
            if (vy < 0 && ys[k] < floor) break
        }
    }
}
