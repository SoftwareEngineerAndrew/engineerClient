package com.adm.features.impl.boss

import com.adm.AdmMod.mc
import com.adm.events.RenderExtractEvent
import com.adm.utils.Color
import com.adm.utils.ServerUtils
import com.adm.utils.center
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// A full draw's arrow as Hypixel flies it: from your eye - 0.1, a hair right, at 3 x your look; it moves that tick,
// then x0.99 and -0.05 a tick. Released [flight] + ping ticks before the spawn, it reaches the dragon as it appears.
object LastBreathRelease {
    private const val SPEED = 3.0
    private const val G = 0.05
    private const val RIGHT = 0.01745
    private const val MAX_TICKS = 160

    /** Ticks until releasing with your current aim lands at the spawn; null: no dragon coming or it doesn't reach. */
    var releaseIn: Double? = null
        private set
    /** Where to look so a shot released now lands as the dragon spawns. */
    private var aimYaw = 0f
    private var aimPitch: Float? = null
    private var target: WitherDragonsEnum? = null

    fun tick(ding: Boolean, bunch: Boolean) {
        val player = mc.player
        val dragon = WitherDragons.spawnOrder.firstOrNull { it.state == WitherDragonState.SPAWNING && it.timeToSpawn > 0 }
            ?: WitherDragonsEnum.entries.filter { it.state == WitherDragonState.SPAWNING && it.timeToSpawn > 0 }.minByOrNull { it.timeToSpawn }
        if (player == null || dragon == null || DungeonUtils.getF7Phase() != M7Phases.P5 || player.mainHandItem.itemId != "LAST_BREATH") {
            releaseIn = null; aimPitch = null; target = null
            return
        }
        target = dragon
        val box = hitBox(dragon)
        val eye = player.y + if (player.isCrouching) 1.27 else 1.62
        val ping = ServerUtils.averagePing / 50.0
        val before = releaseIn
        releaseIn = flight(player.x, eye, player.z, player.yRot, player.xRot, box)?.let { dragon.timeToSpawn - it - ping }
        val now = releaseIn
        if (ding && before != null && now != null && before > 0 && now <= 0 && now > -2) playSoundAtPlayer(SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 2f)

        aimPitch = null
        if (!bunch) return
        val c = box.center
        aimYaw = Math.toDegrees(atan2(-(c.x - player.x), c.z - player.z)).toFloat()
        val need = dragon.timeToSpawn - ping
        var best = Double.MAX_VALUE
        var pitch = -89f
        while (pitch <= 30f) {
            flight(player.x, eye, player.z, aimYaw, pitch, box)?.let {
                val off = kotlin.math.abs(it - need)
                if (off < best) { best = off; aimPitch = pitch }
            }
            pitch += 0.5f
        }
        if (best > 1.0) aimPitch = null
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
            left > 0 -> "$name §frelease in §e${(left / 20).toFixed(2)}s" + aimText()
            left > -2 -> "$name §a§lRELEASE"
            else -> "$name §clate" + aimText()
        }
    }

    private fun aimText(): String = if (aimPitch != null) " §7(aim at the marker)" else ""

    // Where the dragon's body comes in around its spawn point.
    private fun hitBox(dragon: WitherDragonsEnum): AABB {
        val c = dragon.spawnPos.center
        return AABB(c.x - 3.0, c.y - 1.0, c.z - 3.0, c.x + 3.0, c.y + 4.0, c.z + 3.0)
    }

    /** The tick (1 = the release tick) the arrow reaches [box], or null if it never does. */
    private fun flight(px: Double, eye: Double, pz: Double, yaw: Float, pitch: Float, box: AABB): Int? {
        val yr = Math.toRadians(yaw.toDouble()); val pr = Math.toRadians(pitch.toDouble())
        val cy = cos(yr); val sy = sin(yr); val cp = cos(pr)
        var vx = -sy * cp * SPEED; var vy = -sin(pr) * SPEED; var vz = cy * cp * SPEED
        var x = px - cy * RIGHT; var y = eye - 0.1; var z = pz - sy * RIGHT
        for (tick in 1..MAX_TICKS) {
            val nx = x + vx; val ny = y + vy; val nz = z + vz
            if (box.clip(Vec3(x, y, z), Vec3(nx, ny, nz)).isPresent || box.contains(nx, ny, nz)) return tick
            x = nx; y = ny; z = nz
            vx *= 0.99; vy = vy * 0.99 - G; vz *= 0.99
            // falling and below the box: it won't come back up
            if (vy < 0 && y < box.minY) return null
        }
        return null
    }
}
