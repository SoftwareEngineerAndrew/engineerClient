package com.engineerclient.p3sim

import net.minecraft.core.particles.ParticleTypes
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.item.PrimedTnt
import net.minecraft.world.phys.Vec3
import kotlin.random.Random

/**
 * Goldor's TNT Traps (chat-attacks.md §3, P3): 27 TNT on one tick in a 3x3x3 cube (block centres,
 * 2 blocks across) at nine fixed spots, two or three a side, gone ~21 ticks later. Each goes once a
 * run: one of S1's at n 250-290 in every run, the rest (C) when you stand under one while its
 * section is in progress. In the blast: `Goldor's TNT Trap 60,000`, lethal, so through [Masks].
 * The TNT is only visual (TNT_EXPLODES is off and we discard it before its fuse runs out).
 */
object P3Traps {
    /** Lower corners of the cubes, by section (S1 x 99.5, S2 z 131.5, S3 x 7.5, S4 z 39.5). */
    private val SPOTS = listOf(
        1 to Vec3(99.5, 127.5, 85.5), 1 to Vec3(99.5, 127.5, 103.5),
        2 to Vec3(53.5, 127.5, 131.5), 2 to Vec3(35.5, 127.5, 131.5),
        3 to Vec3(7.5, 127.5, 85.5), 3 to Vec3(7.5, 127.5, 67.5),
        4 to Vec3(71.5, 129.5, 39.5), 4 to Vec3(53.5, 127.5, 39.5), 4 to Vec3(35.5, 129.5, 39.5),
    )
    /** Measured lifetime ~21 ticks (fuse ~20, not vanilla 80). */
    private const val FUSE = 21
    /** Trigger: you within this (horizontal) of a cube's centre. Conjecture. */
    private const val TRIGGER = 3.0
    /** Hit: within this of the centre horizontally and below/at the cube when it goes. Conjecture (vanilla TNT reaches ~8). */
    private const val BLAST = 4.5

    private class Live(val centre: Vec3, val at: Int, val tnt: List<PrimedTnt>)

    private var phase: GoldorPhase? = null
    private val used = BooleanArray(SPOTS.size)
    private val live = ArrayList<Live>()
    private var firstAt = -1
    private var firstSpot = 0

    private fun reset(p: GoldorPhase) {
        phase = p
        used.fill(false)
        live.forEach { l -> l.tnt.forEach { it.discard() } }
        live.clear()
        // Started past S1: its traps are spent.
        if (p.from >= 2) { used[0] = true; used[1] = true }
        firstAt = if (p.from == 1) 250 + Random.nextInt(41) else -1
        firstSpot = Random.nextInt(2)
    }

    fun tick(p: GoldorPhase) {
        if (phase !== p) reset(p)
        val n = p.n
        if (n == firstAt && !used[firstSpot] && p.section == 1) arm(firstSpot, n)
        val me = Sim.player
        if (me != null && n >= 250 && p.section <= 4) SPOTS.forEachIndexed { i, (sec, corner) ->
            if (!used[i] && sec == p.section && horiz(me.position(), centre(corner)) <= TRIGGER) arm(i, n)
        }
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

    private fun arm(i: Int, n: Int) {
        used[i] = true
        val c = SPOTS[i].second
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
