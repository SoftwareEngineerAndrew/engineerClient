package com.engineerclient.p3sim

import net.minecraft.core.particles.ParticleTypes
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.item.PrimedTnt
import net.minecraft.world.phys.Vec3

/**
 * Goldor's TNT Traps (chat-attacks.md §3, P3): a cube of TNT blocks in the arena goes off as 27
 * primed TNT on one tick (3x3x3, block centres), gone ~21 ticks later. Which cube and when is
 * GoldorPhase.tnt's (the block changes measured in the recordings: one every 200 ticks from 237,
 * the section's cubes in order). In the blast: `Goldor's TNT Trap 60,000`, lethal, so through
 * [Masks]. The TNT is only visual (TNT_EXPLODES is off and we discard it before its fuse runs out).
 */
object P3Traps {
    /** Measured lifetime ~21 ticks (fuse ~20, not vanilla 80). */
    private const val FUSE = 21
    /** Hit: within this of the centre horizontally and below/at the cube when it goes. Conjecture (vanilla TNT reaches ~8). */
    private const val BLAST = 4.5

    private class Live(val centre: Vec3, val at: Int, val tnt: List<PrimedTnt>)

    private val live = ArrayList<Live>()

    fun clear() { live.forEach { l -> l.tnt.forEach { it.discard() } }; live.clear() }

    fun tick(p: GoldorPhase) {
        val n = Fight.serverTick
        val it = live.iterator()
        while (it.hasNext()) {
            val l = it.next()
            if (n - l.at < FUSE) continue
            it.remove()
            explode(l)
        }
    }

    private fun centre(corner: Vec3) = corner.add(1.0, 1.0, 1.0)

    private fun horiz(a: Vec3, b: Vec3) = Math.hypot(a.x - b.x, a.z - b.z)

    /** The cube whose lowest block is [x], [y], [z] goes off. */
    fun arm(x: Int, y: Int, z: Int) {
        val c = Vec3(x + 0.5, y + 0.5, z + 0.5)
        val n = Fight.serverTick
        val tnt = ArrayList<PrimedTnt>(27)
        for (dx in 0..2) for (dy in 0..2) for (dz in 0..2) {
            val e = PrimedTnt(Sim.level, c.x + dx, c.y + dy, c.z + dz, null)
            e.setNoGravity(true)
            e.deltaMovement = Vec3.ZERO
            // Longer than we keep it: we discard it at FUSE ourselves, so it never explodes for real.
            e.fuse = FUSE + 20
            tnt += Sim.spawn(e)
        }
        Sim.sound(SoundEvents.TNT_PRIMED, 1f, 1f, centre(c))
        live += Live(centre(c), n, tnt)
    }

    private fun explode(l: Live) {
        l.tnt.forEach { it.discard() }
        val c = l.centre
        Sim.level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y, c.z, 1, 0.0, 0.0, 0.0, 0.0)
        Sim.level.sendParticles(ParticleTypes.EXPLOSION, c.x, c.y, c.z, 12, 1.5, 1.5, 1.5, 0.0)
        Sim.sound(SoundEvents.GENERIC_EXPLODE, 4f, 0.7f, c)
        val p = Sim.player ?: return
        if (p.isSpectator || p.isCreative || SimItems.cloaked) return
        if (horiz(p.position(), c) > BLAST || p.y > c.y + 2.0 || p.y < c.y - 14.0) return
        Sim.chat("§cGoldor's§r§7 TNT Trap hit you for §r§c60,000§r§7 damage.")
        Masks.hit(p, "Goldor")
    }
}
