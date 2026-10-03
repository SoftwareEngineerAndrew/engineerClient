package com.engineerclient.p3sim

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

// ====================================================================== P4

/**
 * P4, Necron (docs/mechanics/necron.md), the fastest script (§9): lines 62 apart from 0, off mid
 * at L1 = 159 (a 7-tick 0.25 b/t sidestep 45-75° off south, 3 held, then 0.49 b/t at the closest
 * player; §3) and back at B1 = 177. ARGH is the first n ≡ 5 (mod 20) at least B + 141 (§4), the
 * first held to 248 + 82 = 330 by the taunt; Nuclear Frenzy pulses on that grid while he waits at
 * mid (§5). Volley 2 starts ARGH 1 + 8 (median) at the platform that goes at volley 2 + 98-99
 * (the recorded "p4" break, line + 45 = 437); L2 = volley 2 + 60 = 398, trip 2 at 0.63 b/t, B2 =
 * L2 + 5 = 403, so ARGH 2 = 545 and "All this, for nothing..." 607; TNT +38..45, gone +61, the
 * 14% "my master" +62, stats +86 (§7).
 */
class P4Necron(val fromP3: Boolean = false) : Fight.Phase("P4") {
    override val restart get() = Fight.Start.P4
    private lateinit var necron: BossWither
    private val MID = Vec3(54.0, 66.0, 76.0)

    private companion object {
        const val L1 = 159; const val B1 = L1 + 18
        /** First tick on the 20-tick grid (n ≡ 5) at least 141 after [b] (§4). */
        fun argh(b: Int) = (b + 141 - 5 + 19) / 20 * 20 + 5
        val ARGH1 = max(argh(B1), 248 + 82)
        val SPACE = ARGH1 + 62
        val V1 = 60
        val V2 = ARGH1 + 8
        val L2 = V2 + 60; val B2 = L2 + 5
        val TAUNT1 = max(186 + 62, B1); val TAUNT2 = max(SPACE + 62, B2)
        val ARGH2 = argh(B2)
        val END = ARGH2 + 62
        val GONE = END + 61
        /** The platform the recorded "p4" break removes (SW, x 17-41, z 90-115): volley 2's target. */
        val SW = Vec3(29.0, 64.0, 102.0)
        /** Conjecture (§5): only players near mid report Nuclear Frenzy. */
        const val FRENZY_RANGE = 12.0
        val TAUNTS = listOf("Sometimes when you have a problem, you just need to destroy it all and start again.", "WITNESS MY RAW NUCLEAR POWER!")
    }

    /** The sidestep's direction: 45-75° left or right of south (§3). */
    private val side = Random.nextDouble(45.0, 75.0).let { if (Random.nextBoolean()) it else -it }.let { Math.toRadians(it) }.let { Vec3(-sin(it), 0.0, cos(it)) }
    private val master = Random.nextDouble() < 0.14
    private val taunt1 = TAUNTS.random(); private val taunt2 = TAUNTS.random()

    /** Fireballs are drawn, not spawned (no damage): 0.8 b/t from his centre head (§5). */
    private class Ball(var pos: Vec3, val vel: Vec3, var life: Int = 60)
    private val balls = ArrayList<Ball>()

    override fun start() {
        val p = Sim.player
        if (!fromP3 && p != null) {
            Blocks.finish("p3end")
            val s = Spots.P4
            Sim.tp(p, s.x, s.y, s.z, s.yaw, s.pitch)
        }
        necron = BossWither("Necron", MID)
        Sim.boss("Necron", "You went further than any human before, congratulations.")
        // Hypixel's bar: empty through the intro, ~0.82 when he starts, down at each ARGH.
        BossBar.show("§c§lNecron", 0f)
        // Frames are timed from "Let's make some space!": the lava fall from ~167, the platform at +45.
        Blocks.play("p4", skip = -SPACE)
    }

    override fun stop() { balls.clear(); if (::necron.isInitialized) necron.remove() }

    override fun tick() {
        when (t) {
            62 -> Sim.boss("Necron", "I'm afraid, your journey ends now.")
            124 -> Sim.boss("Necron", "Goodbye.")
            186 -> { Sim.boss("Necron", "That's a very impressive trick. I guess I'll have to handle this myself."); BossBar.progress(0.82f) }
            TAUNT1 -> Sim.boss("Necron", taunt1)
            ARGH1 -> { Sim.boss("Necron", "ARGH!"); BossBar.progress(0.25f) }
            SPACE -> Sim.boss("Necron", "Let's make some space!")
            TAUNT2 -> Sim.boss("Necron", taunt2)
            ARGH2 -> { Sim.boss("Necron", "ARGH!"); BossBar.progress(0.06f) }
            END -> { Sim.boss("Necron", "All this, for nothing..."); BossBar.progress(0f) }
            GONE - 20 -> necron.dieAnim()
            GONE -> necron.remove()
            GONE + 1 -> if (master) Sim.boss("Necron", "I understand your words now, my master.")
            END + 86 -> end()
        }
        move()
        if (t >= V1 && t < V1 + 80 && (t - V1) % 10 == 0) fire(Vec3(0.0, 0.0, 1.0))
        if (t >= V2 && t < V2 + 80 && (t - V2) % 10 == 0) fire(SW.subtract(head(SW)))
        if (t % 20 == 5 && (t in B1 + 1..argh(B1) || t in B2 + 1..ARGH2)) frenzy()
        if (t in END + 38..END + 45) {
            val at = necron.pos.add(0.0, 1.5, 0.0)
            Sim.level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y, at.z, 1, 0.0, 0.0, 0.0, 0.0)
            Sim.sound(SoundEvents.GENERIC_EXPLODE, 0.8f, 1f, at)
        }
        balls.removeAll { !it.fly() }
    }

    /** §3: sidestep, hold, fly at the closest player; teleported back to exactly mid at B. Faces south for volley 1 and the platform for volley 2. */
    private fun move() {
        when {
            t in L1 until L1 + 7 -> necron.moveTo(necron.pos.add(side.scale(0.25)), necron.pos.add(side))
            t in L1 + 7 until L1 + 10 -> {}
            t in L1 + 10 until B1 -> necron.step(chase(), 0.49)
            t in L2 until B2 -> necron.step(chase(), 0.63)
            t == B1 || t == B2 -> necron.moveTo(MID, closest(MID))
            t in V1 until V1 + 80 -> necron.moveTo(MID, MID.add(0.0, 0.0, 10.0))
            t in V2 until L2 -> necron.moveTo(MID, SW)
            t < GONE && t % 5 == 0 -> necron.moveTo(necron.pos, closest(necron.pos))
        }
    }

    private fun chase(): Vec3 = closest(necron.pos).add(0.0, 2.0, 0.0)

    /** His centre head: 2.8 up, 0.5 toward [to] (§5). */
    private fun head(to: Vec3): Vec3 {
        val d = Vec3(to.x - necron.pos.x, 0.0, to.z - necron.pos.z)
        return necron.pos.add(0.0, 2.8, 0.0).add(if (d.lengthSqr() < 1e-4) Vec3.ZERO else d.normalize().scale(0.5))
    }

    private fun fire(dir: Vec3) {
        val from = head(necron.pos.add(dir))
        balls += Ball(from, dir.normalize().scale(0.8))
        Sim.sound(SoundEvents.GHAST_SHOOT, 0.5f, 1f, from)
    }

    /** One tick of a drawn fireball; false when it hits a block or burns out. */
    private fun Ball.fly(): Boolean {
        pos = pos.add(vel)
        if (--life <= 0) return false
        if (!Sim.level.getBlockState(BlockPos.containing(pos)).isAir) {
            Sim.level.sendParticles(ParticleTypes.EXPLOSION, pos.x, pos.y, pos.z, 1, 0.0, 0.0, 0.0, 0.0)
            return false
        }
        Sim.level.sendParticles(ParticleTypes.FLAME, pos.x, pos.y, pos.z, 3, 0.15, 0.15, 0.15, 0.01)
        Sim.level.sendParticles(ParticleTypes.LARGE_SMOKE, pos.x, pos.y, pos.z, 1, 0.1, 0.1, 0.1, 0.0)
        return true
    }

    /** Nuclear Frenzy (§5): 57,600 a pulse near mid. Chat only: it isn't lethal on its own, so it doesn't go through Masks. */
    private fun frenzy() {
        val p = Sim.player ?: return
        if (p.position().distanceTo(MID) > FRENZY_RANGE || SimItems.cloaked) return
        // Line and sounds as measured (chat-attacks.md §1.2, §2: explode v30 p0.49 + wither.ambient v30 p0.70).
        Sim.chat("§cNecron's§r§7 Nuclear Frenzy hit you for §r§c57,600§r§7 damage.")
        Sim.sound(SoundEvents.GENERIC_EXPLODE, 30f, 0.49f)
        Sim.sound(SoundEvents.WITHER_AMBIENT, 30f, 0.7f)
    }

    private fun end() {
        val total = Stats.runTicks()
        Sim.chat("§a§l▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬")
        Sim.chat("§f                        §r§cThe Catacombs §r§8- §r§eFloor VII")
        // Order and padding as measured (chat-attacks.md §1.3).
        Sim.chat("")
        Sim.chat("§f                           Team Score: §r§a317 §r§f(§r§b§lS+§r§f)")
        if (total > 0) Sim.chat("§f     §r§c☠ §r§eDefeated §r§cMaxor, Storm, Goldor, and Necron §r§ein §r§a%02dm %02ds".format(total / 1200, total / 20 % 60))
        Sim.chat("§f                             §6> §e§lEXTRA STATS §6<")
        Sim.chat("§a§l▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬")
        Sim.note("Done. §fMenu§7 to go again.")
    }

    fun status() = if (t < GONE) "Necron ${t / 20}s" else "Necron dead"
}
