package com.engineerclient.p3sim

import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.tags.FluidTags
import net.minecraft.world.phys.Vec3
import kotlin.random.Random

/**
 * Hypixel's lava bounce, as recorded (boss fights with their packets, and dungeon runs tick by tick):
 *
 * - The server checks every 2 ticks whether it sees you in lava, by the old (1.8) rule: your box
 *   shrunk 0.1 at the sides and 0.4 top and bottom (x/z ±0.2, feet +0.4 to +1.4) overlaps a lava
 *   block, of any level. On the P3 floor that's feet below 106.6.
 * - If so, and it hasn't bounced you in the last 4 ticks, it sets your motion to exactly (0, 2.25, 0),
 *   or (0, 3.0375, 0) if the look it has for you is pitch [HIGH_PITCH] or further up. Any lava.
 * - No random delay: contact -> bounce is your round trip plus the wait for the next check (on
 *   Hypixel's ~33 ms that averages round trip + 1 tick). Here the server sees you half a ping late
 *   and the bounce reaches you half a ping later, so the Simulated Ping setting is the round trip.
 * - The client's own physics does the rest, the same every time: from 106.00 it's 108.25, 110.03
 *   (110.025 not sprinting), 111.69 ...; jump held in the lava adds 0.04 (108.29).
 * - No health lost. Whether the hurt tilt and sound come with it isn't decided by anything in the
 *   packets, only how often: [silentChance].
 */
object Lava {
    private const val HIGH_PITCH = -40.7f
    private const val VY = 2.25
    private const val VY_HIGH = 3.0375
    private const val COOLDOWN = 4

    private class Seen(val ns: Long, val inLava: Boolean, val pitch: Float)
    private val seen = java.util.ArrayDeque<Seen>()
    private var bouncedAt = -1000
    private var hurtAt = -1000
    fun reset() { seen.clear(); bouncedAt = -1000; hurtAt = -1000 }

    fun tick(p: ServerPlayer) {
        if (p.isSpectator || p.isCreative) { reset(); return }
        val now = Fight.serverTick
        // Never on fire in the sim (vanilla lava lights you every tick you touch it).
        if (p.remainingFireTicks > 0) p.clearFire()
        val nowNs = System.nanoTime()
        seen.addLast(Seen(nowNs, inLava(p), p.xRot))
        while (seen.size > 40) seen.removeFirst()
        if (now % 2 != 0) return
        // What the server has for you: where you were and how you looked half a round trip ago.
        val half = P3Sim.ping / 2.0
        val target = nowNs - (half * 1e6).toLong()
        val s = seen.lastOrNull { it.ns <= target } ?: seen.first()
        if (!s.inLava) return
        // A bounce on its way still shows you in the lava for a round trip: the cooldown counts from when it lands.
        if (now - bouncedAt < COOLDOWN + 2 * Math.round(half / 50.0).toInt()) return
        val sinceLast = now - bouncedAt
        bouncedAt = now
        val vy = if (s.pitch <= HIGH_PITCH) VY_HIGH else VY
        Fight.afterMs(if (half > 0) Fight.pingMs() / 2 else 0.0, "lava bounce") { if (!p.isRemoved) bounce(p, vy, sinceLast) }
    }

    /** The 1.8 in-lava check: box x/z ±0.2, feet +0.4 to +1.4, any lava block it overlaps. */
    private fun inLava(p: ServerPlayer): Boolean {
        val l = Sim.level
        for (x in Math.floor(p.x - 0.2).toInt()..Math.floor(p.x + 0.2).toInt())
            for (y in Math.floor(p.y + 0.4).toInt()..Math.floor(p.y + 1.4).toInt())
                for (z in Math.floor(p.z - 0.2).toInt()..Math.floor(p.z + 0.2).toInt())
                    if (l.getFluidState(BlockPos(x, y, z)).`is`(FluidTags.LAVA)) return true
        return false
    }

    /**
     * How often a bounce comes without the hurt tilt and sound, as recorded: 3 in 4 within 10 ticks
     * of the last hurt; otherwise by the time since your last bounce (on Hypixel you burn for 300
     * ticks after one, and bounces while burning are mostly loud): 7% within 50 ticks, 16% within
     * 150, 20% within 200, 32% within 250, about half after that or on a first touch.
     */
    private fun silentChance(sinceLast: Int, sinceHurt: Int) = when {
        sinceHurt < 10 -> 0.75
        sinceLast < 50 -> 0.07
        sinceLast < 150 -> 0.16
        sinceLast < 200 -> 0.20
        sinceLast < 250 -> 0.32
        else -> 0.47
    }

    private fun bounce(p: ServerPlayer, vy: Double, sinceLast: Int) {
        p.deltaMovement = Vec3(0.0, vy, 0.0)
        p.syncVelocity = true
        p.fallDistance = 0.0
        p.clearFire()
        val now = Fight.serverTick
        if (Random.nextDouble() < silentChance(sinceLast, now - hurtAt)) return
        hurtAt = now
        // The tilt and sound come with the bounce 60% of the time, a tick after it the rest.
        val hurt = {
            if (!p.isRemoved) {
                p.connection.send(ClientboundHurtAnimationPacket(p))
                Sim.sound(SoundEvents.PLAYER_HURT, 1f, 1f, p.position(), net.minecraft.sounds.SoundSource.PLAYERS)
            }
        }
        if (Random.nextDouble() < 0.4) Fight.later(1, "lava hurt") { hurt() } else hurt()
    }
}
