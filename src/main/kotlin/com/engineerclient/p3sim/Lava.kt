package com.engineerclient.p3sim

import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.phys.Vec3
import kotlin.random.Random

/**
 * Hypixel's lava bounce (tools/p3sim/research/physics.md §1): 1-6 ticks after your box first
 * touches lava (mostly 2-3) the server sets your motion to straight up, vy 2.25 (3.038 in the P3
 * lava when you're looking up: [HIGH_PITCH]). The client's own physics carries it (+2.25 on the first tick, apex +18.5; 3.038: apex +30.5). No health lost, but the hurt
 * sound and tilt, and you burn for 100-125 ticks. Again each time you come back down into it; never
 * while you're still rising out.
 */
object Lava {
    private var touchedAt = -1
    private var bounceAt = -1
    private var bouncedAt = -1000
    private var fireTicks = 0

    fun reset() { touchedAt = -1; bounceAt = -1; bouncedAt = -1000 }

    fun tick(p: ServerPlayer) {
        if (p.isSpectator || p.isCreative) { reset(); return }
        val now = Fight.serverTick
        pitches[now.mod(pitches.size)] = p.xRot
        // Burning: Hypixel's 100-125 ticks, not vanilla lava's 15 s.
        if (now - bouncedAt in 1..3) p.remainingFireTicks = fireTicks - (now - bouncedAt)
        val inLava = touches(p)
        if (!inLava) { touchedAt = -1; bounceAt = -1; return }
        if (touchedAt < 0) {
            // Rising out of the last bounce: no second one in the air.
            if (now - bouncedAt < 4) return
            touchedAt = now
            bounceAt = now + delay()
        }
        if (now < bounceAt) return
        bounce(p, now)
    }

    /** Your box in lava (a lava block's fluid up to its height). */
    private fun touches(p: ServerPlayer): Boolean {
        val b = p.boundingBox.deflate(0.001)
        val l = Sim.level
        for (x in Math.floor(b.minX).toInt()..Math.floor(b.maxX).toInt())
            for (y in Math.floor(b.minY).toInt()..Math.floor(b.maxY).toInt())
                for (z in Math.floor(b.minZ).toInt()..Math.floor(b.maxZ).toInt()) {
                    val pos = net.minecraft.core.BlockPos(x, y, z)
                    val f = l.getFluidState(pos)
                    if (f.`is`(net.minecraft.tags.FluidTags.LAVA) && y + f.getHeight(l, pos) >= b.minY) return true
                }
        return false
    }

    /** Contact -> bounce: mode 2, mostly 1-6. */
    private fun delay(): Int {
        val r = Random.nextDouble()
        return when {
            r < 0.12 -> 1
            r < 0.52 -> 2
            r < 0.80 -> 3
            r < 0.90 -> 4
            r < 0.96 -> 5
            else -> 6
        }
    }

    /**
     * The 3.038 bounce is the one you get looking up: pitch -41 or further up, as you were 2 ticks
     * before the bounce (Hypixel decides on the look it has, a round trip behind). Better PF's 217
     * runs: 2 misses in 515 of the recorder's own bounces at that lag (both flicks across it).
     */
    private const val HIGH_PITCH = -41f
    private const val PITCH_LAG = 2
    private val pitches = FloatArray(PITCH_LAG + 1)

    private fun bounce(p: ServerPlayer, now: Int) {
        val p3Lava = p.y < 108.5 && p.y > 104.0
        val vy = if (p3Lava && pitches[(now - PITCH_LAG).mod(pitches.size)] <= HIGH_PITCH) 3.038 else 2.25
        p.deltaMovement = Vec3(0.0, vy, 0.0)
        p.hurtMarked = true
        p.fallDistance = 0.0
        p.connection.send(ClientboundHurtAnimationPacket(p))
        Sim.sound(SoundEvents.PLAYER_HURT, 1f, 1f, p.position(), net.minecraft.sounds.SoundSource.PLAYERS)
        fireTicks = 100 + Random.nextInt(26)
        p.remainingFireTicks = fireTicks
        bouncedAt = now
        touchedAt = -1; bounceAt = -1
    }
}
