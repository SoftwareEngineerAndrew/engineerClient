package com.engineerclient.p3sim

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LightningBolt
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull
import net.minecraft.world.level.Level
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * A P3 start's lead-in: the 102 ticks (5.1 s) from Storm's death line to Goldor's "Who dares
 * trespass" (death + 102 in 140 of 150 runs; p2storm/deathgap.mjs). You're on your P3 spot with the
 * P3 hotbar; the party has already leapt down to the SS (dropspot.mjs: they leave Yellow 5-20 ticks
 * after the line and land on (108, 120, 94)). Storm's body spins at Yellow, where he nearly always
 * dies, under its lightning storm ([StormCorpse]), and his last lines play.
 */
class StormEnd : Fight.Phase("Storm end") {
    override val restart get() = Fight.Start.P3
    private var body: BossWither? = null

    override fun start() {
        Sim.player?.let { p ->
            val spot = Spots.p3Start(1)
            Sim.tp(p, spot.x, spot.y, spot.z, spot.yaw, spot.pitch)
            SimItems.giveHotbar(p, p3 = true)
        }
        Party.standAt(SS_LANDING)
        val b = BossWither("Storm", DEATH_AT, armoured = false)
        body = b
        StormFx.line(b, "I should have known that I stood no chance.")
        // Hypixel: the bar still shows 0.45 at the line (30/37 runs) and drops to 0 about 4 ticks later.
        BossBar.show("§c§lStorm", 0.45f)
        StormCorpse(b).start()
    }

    override fun tick() {
        when (t) {
            4 -> BossBar.progress(0f)
            62 -> StormFx.line(body, "At least my son died by your hands.")
            // 3 ticks early: the first lever credit can land 1-2 ticks before "Who dares" (LEV-06); the line itself still lands at LEAD.
            LEAD - 3 -> Fight.begin(GoldorPhase(1, arrived = true))
        }
    }

    companion object {
        const val LEAD = 102
        /** Where he dies: pinned under Yellow (postdeath.mjs, the median of 50 bodies). */
        val DEATH_AT = Vec3(44.6, 172.9, 65.2)
        /** The party after its leap onto the SS player (dropspot.mjs). */
        val SS_LANDING = listOf(Vec3(108.3, 120.0, 94.0), Vec3(107.4, 120.0, 92.8), Vec3(108.3, 120.0, 95.4), Vec3(107.0, 120.0, 94.6))
    }
}

/**
 * What Storm does after his death line, into P3 (BossRecorder, p2storm/corpseend.mjs,
 * postdeath.mjs, deathsnd.mjs, 15-50 fights). His body never falls over at the line: it stays where
 * the crush pinned it, spinning 40° a tick, with a wither.hurt (hostile, 15, 1.0) every 10-13 ticks;
 * explode (2, ~0.6) at +0 and +4, a wooden-door break (3, ~0.9) at ~+12. Health 0 (the vanilla
 * death fall) at +226 (215-236), removed 20-28 later (+249). Meanwhile 480 lightning bolts: 24
 * spirals from his body on a fixed schedule ([SPIRALS], the same in every fight to a tick or two),
 * each 20 bolts 2 blocks apart walking out to 38 blocks, one every ~4.5 ticks, turning 90° a bolt;
 * the last strikes ~+410 (Goldor's n ~ 310). Runs on [Fight.later], so it outlives the phase.
 */
internal class StormCorpse(private val body: BossWither) {
    private val at = body.pos
    private var n = 0
    private var yaw = body.e.yRot.toDouble()
    private var nextHurt = 0
    private val bolts = HashMap<Int, MutableList<Vec3>>()

    init {
        for (s in SPIRALS) {
            val a0 = Random.nextDouble(360.0)
            for (j in 0 until 20) {
                val a = Math.toRadians(a0 + 90.0 * j)
                bolts.getOrPut(s + (j * 4.53).roundToInt()) { ArrayList() } += Vec3(at.x + 2.0 * j * cos(a), at.y, at.z + 2.0 * j * sin(a))
            }
        }
    }

    fun start() = step()

    private fun step() {
        tick()
        n++
        if (n <= LAST) Fight.later(1, "storm corpse") { step() }
    }

    private fun tick() {
        if (n < DEAD) {
            yaw += 40.0
            val r = Math.toRadians(yaw)
            body.moveTo(at, at.add(-sin(r), 0.0, cos(r)))
            if (n == nextHurt) { Sim.sound(SoundEvents.WITHER_HURT, 15f, 1f, at, net.minecraft.sounds.SoundSource.HOSTILE); nextHurt += Random.nextInt(10, 14) }
        }
        when (n) {
            0, 4 -> Sim.sound(SoundEvents.GENERIC_EXPLODE, 2f, 0.6f, at, net.minecraft.sounds.SoundSource.BLOCKS)
            12 -> Sim.sound(SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, 3f, 0.9f, at)
            DEAD -> body.dieAnim()
            GONE -> body.remove()
        }
        bolts[n]?.forEach { StormFx.bolt(it.x, it.z, it.y, thunder = true) }
    }

    companion object {
        const val DEAD = 226
        const val GONE = 249
        /** Spiral starts after the death line (two fights' bolts on his body: 21-00-45 and 21-05-29). */
        val SPIRALS = listOf(0, 22, 46, 70, 85, 100, 116, 132, 147, 162, 173, 186, 200, 210, 220, 231, 242, 257, 270, 282, 292, 302, 312, 323)
        val LAST = SPIRALS.last() + 87
    }
}

/** Storm's own effects: his lines, the bolts, the projectiles; used by [P2Storm] and [StormEnd]. */
internal object StormFx {
    /**
     * A `[BOSS] Storm` line: the chat line, wither.ambient (5, 1.19) at him (deathsnd.mjs: at his
     * body, not at you), and the speech stand: `§4§l<line>` 4.1 above him, following him, ~40
     * ticks (speech.mjs: 65 stands in 20 runs, spawned the next tick, life p90 43).
     */
    fun line(w: BossWither?, text: String) {
        Sim.chat("§4[BOSS] Storm§r§c: $text")
        Sim.sound(SoundEvents.WITHER_AMBIENT, 5f, 1.19f, w?.pos)
        w ?: return
        val s = ArmorStand(EntityType.ARMOR_STAND, Sim.level)
        s.isInvisible = true; s.setNoGravity(true); s.isInvulnerable = true; s.isSilent = true
        Station.setMarker(s)
        s.setCustomName(Sim.legacy("§4§l$text"))
        s.isCustomNameVisible = true
        s.snapTo(w.pos.x, w.pos.y + SPEECH_Y, w.pos.z, 0f, 0f)
        Sim.spawn(s)
        speech += s to w
        Fight.later(40, "storm speech") { s.discard(); speech.removeIf { it.first === s } }
    }

    private val speech = ArrayList<Pair<ArmorStand, BossWither>>()
    private const val SPEECH_Y = 4.1

    /** Speech stands keep up with him. */
    fun follow() {
        speech.removeIf { it.first.isRemoved }
        speech.forEach { (s, w) -> s.snapTo(w.pos.x, w.pos.y + SPEECH_Y, w.pos.z, 0f, 0f) }
    }

    fun clearSpeech() { speech.forEach { it.first.discard() }; speech.clear() }

    /** A visual-only bolt (vanilla's flash and thunder) on the ground at (x, z), looking down from [fromY]. */
    fun bolt(x: Double, z: Double, fromY: Double, thunder: Boolean = false): Vec3 {
        val p = Vec3(x, ground(x, z, fromY), z)
        val b = LightningBolt(EntityType.LIGHTNING_BOLT, Sim.level)
        b.setVisualOnly(true)
        b.snapTo(p.x, p.y, p.z, 0f, 0f)
        Sim.spawn(b)
        // A visual-only bolt plays no sound of its own here: in the lead-in Hypixel sends the thunder per bolt, at it (vol 10000, pitch 0.8-1.0, WEATHER).
        if (thunder) Sim.sound(SoundEvents.LIGHTNING_BOLT_THUNDER, 10000f, 0.8f + Random.nextFloat() * 0.2f, p, net.minecraft.sounds.SoundSource.WEATHER)
        return p
    }

    /** The top of the first solid block at or below [fromY] (14 down at most), else [fromY]. */
    fun ground(x: Double, z: Double, fromY: Double): Double {
        val bx = floor(x).toInt(); val bz = floor(z).toInt()
        var y = floor(fromY).toInt()
        repeat(15) {
            if (Sim.level.getBlockState(BlockPos(bx, y, bz)).blocksMotion()) return y + 1.0
            y--
        }
        return fromY
    }

    /** Hypixel's damage numbers: thousands commas, one decimal, none when it's .0 (hits.mjs: "10,047", "9,292.1"). */
    fun dmg(v: Double): String = String.format(Locale.US, "%,.1f", v).removeSuffix(".0")

    /** A hit line of Storm's (hits.mjs: exact colours). */
    fun hit(what: String, v: Double, trueDamage: Boolean = false) =
        Sim.chat("§cStorm's§r§7 $what hit you for §r§c${dmg(v)}§r§7 ${if (trueDamage) "true " else ""}damage.")

    /** The particles where a projectile of his bursts (no block damage: the arena stays as built). */
    fun burst(at: Vec3) = Sim.level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 1, 0.0, 0.0, 0.0, 0.0)
}

/**
 * Storm's Lightning Fireball: a vanilla large fireball (the fire-charge sprite) with Hypixel's push
 * of 0.15 a tick (fireballs.mjs: launch speed 0.15, then 0.74 / 1.55 / 2.0 blocks a tick averaged over
 * ticks 1-10 / 10-20 / 20-30, vanilla's 0.95 inertia with power 0.15). It never explodes on its own:
 * the impact is [onImpact]'s.
 */
internal class StormFireball(level: Level, private val onImpact: (Vec3) -> Unit) : LargeFireball(EntityType.FIREBALL, level) {
    override fun onHit(result: HitResult) {
        if (level().isClientSide || isRemoved) return
        onImpact(result.location)
        discard()
    }

    override fun tick() { super.tick(); if (tickCount > 200) discard() }
}

/** One of his wither skulls (power 0.1, skulls.mjs); bursts harmlessly. */
internal class StormSkull(level: Level) : WitherSkull(EntityType.WITHER_SKULL, level) {
    override fun onHit(result: HitResult) {
        if (level().isClientSide || isRemoved) return
        StormFx.burst(result.location)
        discard()
    }

    override fun tick() { super.tick(); if (tickCount > 200) discard() }
}
