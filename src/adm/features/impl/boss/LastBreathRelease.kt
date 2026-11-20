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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// A full draw's arrow as Hypixel flies it: from your eye - 0.1, a hair right, at 3 x your look; it moves that tick,
// then x0.99 and -0.05 a tick. Duplex's two follow the same path 3 and 4 ticks behind. A shot is timed for the most
// of its arrows inside the dragon's box (vanilla's, 16 x 8) on the tick it spawns.
object LastBreathRelease {
    private const val SPEED = 3.0
    private const val G = 0.05
    private const val RIGHT = 0.01745
    private const val MAX_TICKS = 160
    private val DUPLEX = intArrayOf(3, 4)

    /** Ticks until the best release for your current aim; null: no dragon coming or no arrow gets there. */
    private var releaseIn: Double? = null
    private var arrows = 0
    /** How many ticks either side of the best release still get [arrows] in. */
    private var slack = 0
    private var aimYaw = 0f
    private var aimPitch: Float? = null
    private var target: WitherDragonsEnum? = null

    private val xs = DoubleArray(MAX_TICKS + 1)
    private val ys = DoubleArray(MAX_TICKS + 1)
    private val zs = DoubleArray(MAX_TICKS + 1)
    private var flown = 0

    fun tick(ding: Boolean, bunch: Boolean) {
        if (DungeonUtils.getF7Phase() != M7Phases.P5) {
            releaseIn = null; aimPitch = null; target = null
            return
        }
        val player = mc.player
        val dragon = WitherDragons.spawnOrder.firstOrNull { it.state == WitherDragonState.SPAWNING && it.timeToSpawn > 0 }
            ?: WitherDragonsEnum.entries.filter { it.state == WitherDragonState.SPAWNING && it.timeToSpawn > 0 }.minByOrNull { it.timeToSpawn }
        if (player == null || dragon == null || player.mainHandItem.itemId != "LAST_BREATH") {
            releaseIn = null; aimPitch = null; target = null
            return
        }
        target = dragon
        val box = box(dragon)
        val eye = player.y + if (player.isCrouching) 1.27 else 1.62
        // a release now reaches the server half a ping on, and the timer here is half a ping behind it
        val need = dragon.timeToSpawn - ServerUtils.averagePing / 50.0
        val before = releaseIn
        fly(player.x, eye, player.z, player.yRot, player.xRot, box.minY)
        val best = best(box)
        releaseIn = best?.let { need - it.first }
        arrows = best?.second ?: 0
        slack = best?.third ?: 0
        val now = releaseIn
        if (ding && before != null && now != null && before > 0 && now <= 0) playSoundAtPlayer(SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 2f)

        aimPitch = null
        if (!bunch) return
        val c = dragon.spawnPos
        aimYaw = Math.toDegrees(atan2(-(c.x - player.x), c.z - player.z)).toFloat()
        // the aim that, released now, gets the most arrows in; of those the one nearest the middle of its window
        val r = Math.round(need).toInt()
        var most = 0; var off = Double.MAX_VALUE
        var pitch = -89f
        while (pitch <= 30f) {
            fly(player.x, eye, player.z, aimYaw, pitch, box.minY)
            val n = inside(box, r)
            if (n > 0) {
                val o = best(box)?.let { abs(it.first - need) } ?: Double.MAX_VALUE
                if (n > most || n == most && o < off) { most = n; off = o; aimPitch = pitch }
            }
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
        return when {
            left > slack -> "$name §frelease in §e${(left / 20).toFixed(2)}s §7($arrows/3, ±${(slack / 20f).toFixed(2)})" + aimText()
            left >= -slack -> "$name §a§lRELEASE §7($arrows/3)"
            else -> "$name §clate" + aimText()
        }
    }

    private fun aimText(): String = if (aimPitch != null) " §7(aim at the marker)" else ""

    /** The ender dragon's box as it spawns. */
    private fun box(dragon: WitherDragonsEnum): AABB {
        val s = dragon.spawnPos
        return AABB(s.x - 8.0, s.y.toDouble(), s.z - 8.0, s.x + 8.0, s.y + 8.0, s.z + 8.0)
    }

    /** Released [r] ticks before the spawn: how many of the shot's arrows are in [box] on the spawn tick. */
    private fun inside(box: AABB, r: Int): Int {
        var n = 0
        if (r in 1..flown && box.contains(xs[r], ys[r], zs[r])) n++
        for (d in DUPLEX) { val k = r - d; if (k in 1..flown && box.contains(xs[k], ys[k], zs[k])) n++ }
        return n
    }

    /** The release (ticks before the spawn) with the most arrows in, the middle of the best run of them: (r, arrows, slack). */
    private fun best(box: AABB): Triple<Int, Int, Int>? {
        var most = 0; var from = -1; var to = -1
        var r = 1
        while (r <= flown + DUPLEX.last()) {
            val n = inside(box, r)
            if (n > most) { most = n; from = r; to = r }
            else if (n == most && n > 0 && to == r - 1) to = r
            r++
        }
        return if (most == 0) null else Triple((from + to) / 2, most, (to - from) / 2)
    }

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
