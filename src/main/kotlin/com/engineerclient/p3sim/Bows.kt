package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.projectile.ProjectileDeflection
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.entity.projectile.arrow.Arrow
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Hypixel's shortbows as the main server fires them, arrow for arrow (tools/p3sim/research/terror-mosquito.md):
 * the Terminator, the Spirit Shortbow and the Mosquito Shortbow, with Terror armor's Hydra Strike ([Hydra]).
 *
 * A shot handled on server tick N: the main arrow leaves your eye - 0.1 (1.27 sneaking), 0.01745 to your right, at
 * 3.0 x (your look + 1.8's gaussian 0.0075 aim noise), and moves that tick: pos1, v0 = 0.99u - g. On N+1 its
 * velocity is k*v0 (k = 1 + 0.01 x Hydra stacks). At 10 stacks two more launch on N+1 at +-8 deg of that move.
 * The Terminator's side arrows launch on N+1 from 0.5 under pos1 at your clean look +-5.5 deg; the Mosquito's
 * Duplex replays the main arrow's N+1 move 3 or 4 ticks later. A Terminator volley is 5 entities (the side arrows
 * twice each, identical), a Duplex is 2 identical entities, Hydra Strike's two are distinct. Arrows are spawned at the end of a tick in the
 * state that makes their next vanilla tick exactly Hypixel's ([SimArrow]); every one goes on its first hit, and
 * every one counts for the i4 target.
 *
 * Clicks: right click shoots (left click too, but the Mosquito's left click is Nasty Bite), one shot per
 * Shortbow Cooldown; Nasty Bite has its own 10 ticks. A click inside a cooldown fires the tick it ends.
 */
object Bows {
    const val TERMINATOR = "TERMINATOR"
    const val SPIRIT = "ITEM_SPIRIT_BOW"
    const val MOSQUITO = "MOSQUITO_BOW"
    /** A vanilla bow (drawn and released) with Duplex, legendary. */
    const val LAST_BREATH = "LAST_BREATH"
    val SHORTBOWS = setOf(TERMINATOR, SPIRIT, MOSQUITO)

    private const val NASTY_BITE_COOLDOWN = 10

    /** What the server has of you when a click lands: where you stand, your look, sneaking. */
    private class Aim(val pos: Vec3, val yaw: Float, val pitch: Float, val crouch: Boolean)

    private fun aim(p: ServerPlayer) = Aim(p.position(), p.yRot, p.xRot, p.isShiftKeyDown)

    /** A click (or a drawn bow's release, at [power] 0.1-1 of a full draw). */
    private class Click(val bow: String, val left: Boolean, val aim: Aim, val power: Float = 1f)
    private val clicks = ArrayList<Click>()
    private class Later(val at: Int, val run: () -> Unit)
    private val later = ArrayList<Later>()
    /** Your aim at the end of each recent tick, newest last (a buffered click fires with the server's view then). */
    private val history = ArrayDeque<Aim>()

    private var shotReady = 0
    private var biteReady = 0
    /** A click made during the cooldown: the bow it was made with, fired the tick the cooldown ends. */
    private var pendingShot: String? = null
    private var pendingBite = false

    private val live = ArrayList<SimArrow>()

    fun reset() {
        clicks.clear(); later.clear(); history.clear()
        shotReady = 0; biteReady = 0; pendingShot = null; pendingBite = false
        live.clear()
        Hydra.reset()
    }

    /** A fresh start from the menu: stacks as the setting has them, nothing left over from the last run. */
    fun start() {
        clicks.clear(); later.clear(); pendingShot = null; pendingBite = false
        Hydra.start()
    }

    /** A click with shortbow [bow] ([left]: a swing). It lands after the simulated ping, aimed as you were when you clicked. */
    fun click(p: ServerPlayer, bow: String, left: Boolean) {
        val a = aim(p)
        Fight.afterPing("shortbow") { if (Sim.player === p && !p.isRemoved) clicks += Click(bow, left, a) }
    }

    /**
     * A drawn bow released at [power] (vanilla's: 0.1-1 by how long it was drawn): it fires then, at power x 3.0, a
     * crit at full draw, with no cooldown of its own. It lands after the simulated ping like a click.
     */
    fun release(p: ServerPlayer, bow: String, power: Float) {
        val a = aim(p)
        Fight.afterPing("bow") { if (Sim.player === p && !p.isRemoved) clicks += Click(bow, false, a, power) }
    }

    /** End of every server tick (after entities moved): clicks, buffered clicks, Duplex releases, Hydra Strike. */
    fun tick() {
        val p = Sim.player ?: return
        val now = Fight.serverTick
        Hydra.tick(p, now)
        val held = SimItems.idOf(p.mainHandItem)
        pendingShot?.let { bow -> if (now >= shotReady) { pendingShot = null; if (held == bow) fire(p, bow, false, past(p)) } }
        if (pendingBite && now >= biteReady) { pendingBite = false; if (held == MOSQUITO) fire(p, MOSQUITO, true, past(p)) }
        for (c in clicks) {
            if (c.bow !in SHORTBOWS) { fire(p, c.bow, false, c.aim, c.power); continue }
            if (c.bow == MOSQUITO && c.left) { if (now >= biteReady) fire(p, MOSQUITO, true, c.aim) else pendingBite = true }
            else if (now >= shotReady) fire(p, c.bow, false, c.aim) else pendingShot = c.bow
        }
        clicks.clear()
        if (later.isNotEmpty()) {
            val due = later.filter { it.at <= now }
            later.removeAll(due.toSet())
            due.forEach { EngineerClient.safely("p3sim bow") { it.run() } }
        }
        live.removeAll { it.isRemoved }
        live.filter { it.tickCount > 100 }.forEach { it.discard() }
        history.addLast(aim(p))
        while (history.size > 40) history.removeFirst()
    }

    /** The server's view of you now: you, [Fight.pingTicks] ticks ago. */
    private fun past(p: ServerPlayer): Aim {
        val d = Fight.pingTicks
        return if (d <= 0 || history.isEmpty()) aim(p) else history[(history.size - d).coerceAtLeast(0)]
    }

    private fun fire(p: ServerPlayer, bow: String, nasty: Boolean, a: Aim, power: Float = 1f) {
        val now = Fight.serverTick
        val r = java.util.Random()
        val noise = Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).scale(0.0075)
        // Duplex delay after the main arrow, recorded (island 2026-10-04): 39% 3 ticks, 51% 4, 9% 5 (rest folded into 4).
        val duplexRoll = Random.nextDouble()
        val duplex = if (duplexRoll < 0.39) 3 else if (duplexRoll < 0.91) 4 else 5
        // The two Duplex entities spawn on the same tick 42 of 64 times, otherwise one is +-1 tick off.
        var dupIdx = 0
        val dupShift = if (Random.nextDouble() < 42.0 / 64.0) 0 else if (Random.nextBoolean()) 1 else -1
        val plan = ShotPlan.plan(bow, a.pos, a.yaw, a.pitch, a.crouch, Hydra.stacks, noise, duplex, 3.0 * power)
        for (arrow in plan) {
            if (arrow.delay == 0) launch(arrow.from, arrow.at, arrow.v, owner = if (arrow.owned) p else null)?.let { if (arrow.owned && bow == LAST_BREATH && power >= 1f) it.isCritArrow = true }
            else {
                // Hypixel's Duplex entities each make a quieter shoot sound on the tick they spawn, at the centre of the
                // block holding that entity's spawn position (island recording: master, vol 0.5, pitch 0.698).
                val delay = arrow.delay + (if (bow in ShotPlan.DUPLEX && dupIdx++ == 1) dupShift else 0)
                later += Later(now + delay) {
                    launch(arrow.from, arrow.at, arrow.v)
                    if (bow in ShotPlan.DUPLEX) sound(SoundEvents.ARROW_SHOOT, SoundSource.MASTER, 0.5f, 0.7f,
                        Vec3(Mth.floor(arrow.at.x) + 0.5, Mth.floor(arrow.at.y) + 0.5, Mth.floor(arrow.at.z) + 0.5))
                }
            }
        }
        sound(SoundEvents.ARROW_SHOOT, SoundSource.NEUTRAL, 1f, 1f / (Random.nextFloat() * 0.4f + 1.2f) + 0.5f * power)
        if (bow !in SHORTBOWS) return
        val stack = p.mainHandItem
        if (nasty) { biteReady = now + NASTY_BITE_COOLDOWN; p.cooldowns.addCooldown(stack, NASTY_BITE_COOLDOWN) }
        else { shotReady = now + P3Sim.shortbowCooldown; p.cooldowns.addCooldown(stack, P3Sim.shortbowCooldown) }
    }

    /**
     * An arrow that left [from] and is at [at] at the end of this tick, moving [v] next tick. The leg from [from]
     * to [at] (the main arrow's first move) is checked here, as no vanilla tick saw it.
     */
    fun launch(from: Vec3, at: Vec3, v: Vec3, owner: ServerPlayer? = null, onHit: ((HitResult) -> Unit)? = null): SimArrow? {
        val level = Sim.level
        if (from != at) {
            val block = level.clip(ClipContext(from, at, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner ?: Sim.player!!))
            val end = if (block.type == HitResult.Type.MISS) at else block.location
            val boss = level.getEntitiesOfClass(WitherBoss::class.java, net.minecraft.world.phys.AABB(from, end).inflate(1.0)) { isBoss(it) }
                .filter { !immune(it) }
                .mapNotNull { w -> w.boundingBox.inflate(0.3).clip(from, end).map { w to it }.orElse(null) }
                .minByOrNull { it.second.distanceToSqr(from) }
            if (boss != null) { hit(EntityHitResult(boss.first, boss.second), onHit); return null }
            if (block.type != HitResult.Type.MISS) {
                // TARGET-04: at the arrow's last position (before this leg), pitch on 1/63 steps.
                val pitch = Mth.floor(1.2f / (Random.nextFloat() * 0.2f + 0.9f) * 63f) / 63f
                level.playSound(null, from.x, from.y, from.z, SoundEvents.ARROW_HIT, SoundSource.NEUTRAL, 1f, pitch)
                hit(block, onHit)
                return null
            }
        }
        val a = SimArrow(level, at.x, at.y, at.z) { _, h -> hit(h, onHit) }
        a.immune = ::immune
        if (owner != null) a.setOwner(owner)
        a.pickup = AbstractArrow.Pickup.DISALLOWED
        a.deltaMovement = v
        val yRot = Math.toDegrees(atan2(v.x, v.z)).toFloat()
        val xRot = Math.toDegrees(atan2(v.y, sqrt(v.x * v.x + v.z * v.z))).toFloat()
        a.yRot = yRot; a.xRot = xRot; a.yRotO = yRot; a.xRotO = xRot
        Sim.spawn(a)
        live += a
        return a
    }

    /**
     * Damage immunity of an arrow's target: vanilla's hurt window (invulnerableTime over half of 20). The sim's boss
     * withers are never damaged, so this never holds for them today; the rebound path in [SimArrow] is generic.
     */
    fun immune(e: Entity): Boolean = e is net.minecraft.world.entity.LivingEntity && e.invulnerableTime > 10

    private fun isBoss(e: Entity) = e is WitherBoss && e.isAlive && e.entityTags().contains(Sim.TAG)

    /** Any arrow of ours hit something: a block (the i4 target counts every arrow), or a boss (a Hydra Strike hit). */
    private fun hit(h: HitResult, onHit: ((HitResult) -> Unit)?) {
        when (h) {
            is BlockHitResult -> (Fight.phase as? GoldorPhase)?.devices?.target?.hit(h.blockPos)
            is EntityHitResult -> {
                Hydra.hit(Fight.serverTick)
                sound(SoundEvents.ARROW_HIT_PLAYER, SoundSource.NEUTRAL, 1f, 0.8f)
            }
        }
        onHit?.invoke(h)
    }

    /** A sound at you (or at [at]), its pitch on Hypixel's 1/63 steps. */
    private fun sound(s: SoundEvent, source: SoundSource, volume: Float, pitch: Float, at: Vec3? = null) {
        val p = Sim.player ?: return
        Sim.level.playSound(null, at?.x ?: p.x, at?.y ?: p.y, at?.z ?: p.z, s, source, volume, Mth.floor(pitch * 63f) / 63f)
    }

    /**
     * Terror armor's Hydra Strike (terror-mosquito.md §5): +1 stack (max 10) when an arrow hits a boss, at most every
     * 4 ticks (a hit at 10 still counts). A once-a-second task, on its own grid, counts seconds without a gain and
     * (replaced: a stack is lost every (T+1)*20 ticks after the last gain, see [periodNow]). +1% arrow
     * speed a stack; at 10, +2 arrows. Shown as Hypixel's action bar does: `§6N⁑`, bold at 10, every 10 ticks.
     */
    object Hydra {
        var stacks = 0
            private set
        private var lastGain = -100
        private var nextLoss = Int.MAX_VALUE
        private var period = 0
        private var grid = 0
        private var shown = 0
        private val pieces: Int get() = P3Sim.terrorPieces
        /** Ticks per lost stack: (T+1)*20, T = 4 s (1-2 pieces), 7 s (3), 10 s (4); sampled at the last gain (island recording). */
        private fun periodNow(): Int = ((if (pieces >= 4) 10 else if (pieces == 3) 7 else 4) + 1) * 20

        fun reset() { stacks = 0; lastGain = -100; nextLoss = Int.MAX_VALUE; shown = 0; grid = Random.nextInt(20); start() }

        fun start() { stacks = if (pieces > 0) P3Sim.hydraStart else 0; period = periodNow(); nextLoss = Fight.serverTick + period }

        fun hit(now: Int) {
            if (pieces == 0 || now - lastGain < 4) return
            lastGain = now; period = periodNow(); nextLoss = now + period
            if (stacks < 10) stacks++
        }

        fun tick(p: ServerPlayer, now: Int) {
            if (pieces == 0) stacks = 0
            val phase = (now - grid).mod(20)
            if (pieces > 0 && stacks > 0 && now >= nextLoss) { stacks--; nextLoss += period }
            if (phase % 10 != 0) return
            if (stacks > 0) p.sendSystemMessage(Component.literal(if (stacks >= 10) "§6§l10⁑§r" else "§6$stacks⁑"), true)
            else if (shown > 0) p.sendSystemMessage(Component.empty(), true)
            shown = stacks
        }
    }
}

/**
 * An arrow of the sim's: vanilla flight (move, x0.99, -0.05 a tick, as Hypixel's 1.8 server), but it hits only the
 * boss withers (with 1.8's 0.3 margin, never bouncing) and goes on its first hit, block or boss, as Hypixel's do.
 */
class SimArrow(level: Level, x: Double, y: Double, z: Double, private val whenHit: (SimArrow, HitResult) -> Unit) :
    Arrow(level, x, y, z, ItemStack(Items.ARROW), null) {

    /** Damage immunity of a target; an arrow that hits an immune one rebounds instead of dealing damage. */
    var immune: (Entity) -> Boolean = { false }
    private var removeAt = -1

    override fun tick() {
        if (removeAt >= 0 && tickCount >= removeAt) { discard(); return }
        super.tick()
    }

    override fun canHitEntity(entity: Entity): Boolean = removeAt < 0 && entity is WitherBoss && entity.isAlive && entity.entityTags().contains(Sim.TAG)

    override fun findHitEntities(from: Vec3, to: Vec3): Collection<EntityHitResult> =
        ProjectileUtil.getManyEntityHitResult(level(), this, from, to, boundingBox.expandTowards(deltaMovement).inflate(1.0), { canHitEntity(it) }, 0.3f, ClipContext.Block.COLLIDER, false)

    override fun hitTargetOrDeflectSelf(hitResult: HitResult): ProjectileDeflection {
        if (removeAt >= 0) return ProjectileDeflection.NONE   // already spent: ignore anything it still touches
        if (hitResult.type == HitResult.Type.ENTITY) {
            setNoGravity(true)
            if (immune((hitResult as EntityHitResult).entity)) {
                // Hypixel: no damage, no hit_player sound; flies back (yaw ~+-172 deg) at 0.29-0.32 b/t, gone 9-10 ticks later.
                val speed = 0.29 + Random.nextDouble() * 0.03
                val yaw = Math.toRadians(yRot.toDouble() + (if (Random.nextBoolean()) 172.0 else -172.0))
                deltaMovement = Vec3(Math.sin(yaw) * speed, 0.0, Math.cos(yaw) * speed)
                yRot = Math.toDegrees(yaw).toFloat(); yRotO = yRot
                removeAt = tickCount + 9 + Random.nextInt(2)
                return ProjectileDeflection.NONE
            }
            EngineerClient.safely("p3sim arrow hit") { whenHit(this, hitResult) }
            deltaMovement = Vec3.ZERO
            removeAt = tickCount + 1 + Random.nextInt(2)   // a damaging hit lingers 1-2 ticks
            return ProjectileDeflection.NONE
        }
        val d = super.hitTargetOrDeflectSelf(hitResult)   // a block: vanilla's hit sound
        if (hitResult.type == HitResult.Type.BLOCK) {
            EngineerClient.safely("p3sim arrow hit") { whenHit(this, hitResult) }
            discard()
        }
        return d
    }
}

/**
 * Every arrow of one shortbow shot, as Hypixel's server moves them (terror-mosquito.md §2-4, §8). Pure (no world),
 * so it tests against recorded shots. Fired on tick N by someone standing at [pos] looking [yaw]/[pitch] ([crouch]:
 * eye 1.27), with [stacks] Hydra Strike stacks; [noise]: the aim noise added to the unit look (1.8: three gaussians
 * x 0.0075); [duplexDelay]: 3 or 4.
 */
object ShotPlan {
    /** One arrow: it left [from] and is at [at] at the end of tick N + [delay], moving [v] on its next tick. */
    class Planned(val from: Vec3, val at: Vec3, val v: Vec3, val owned: Boolean, val delay: Int)

    private const val G = 0.05
    /** The main arrow starts this far to your right (measured 0.01742; pi/180). */
    private const val RIGHT = 0.01745

    fun look(yaw: Float, pitch: Float): Vec3 {
        val y = Math.toRadians(yaw.toDouble()); val p = Math.toRadians(pitch.toDouble())
        return Vec3(-sin(y) * cos(p), -sin(p), cos(y) * cos(p))
    }

    /** [v] turned [deg] degrees of yaw (the way yaw turns: from +z toward -x), its y kept. */
    fun roty(v: Vec3, deg: Double): Vec3 {
        val a = Math.toRadians(deg); val c = cos(a); val s = sin(a)
        return Vec3(v.x * c - v.z * s, v.y, v.x * s + v.z * c)
    }

    /** Bows with Duplex: an extra arrow replaying the main one's tick-2 move 3-4 ticks later. */
    val DUPLEX = setOf(Bows.MOSQUITO, Bows.LAST_BREATH)

    /** [speed]: the launch speed (3.0; a drawn bow's power x 3.0). */
    fun plan(bow: String, pos: Vec3, yaw: Float, pitch: Float, crouch: Boolean, stacks: Int, noise: Vec3, duplexDelay: Int, speed: Double = 3.0): List<Planned> {
        val k = 1 + 0.01 * stacks
        val y = Math.toRadians(yaw.toDouble())
        val dir = look(yaw, pitch)
        // The main arrow: from your eye - 0.1, a hair to your right, at 3 x (look + noise); it moves that tick (pos1,
        // v0 = 0.99u - g), and on the next its velocity is k*v0.
        val u = dir.add(noise).scale(speed)
        val from = pos.add(-cos(y) * RIGHT, (if (crouch) 1.27 else 1.62) - 0.1, -sin(y) * RIGHT)
        val pos1 = from.add(u)
        val move = Vec3(0.99 * u.x, 0.99 * u.y - G, 0.99 * u.z).scale(k)
        val out = arrayListOf(Planned(from, pos1, move, owned = true, delay = 0))
        // Hydra Strike at 10 stacks: that move turned +-8 deg.
        if (stacks >= 10) for (s in listOf(8.0, -8.0)) out += Planned(pos1, pos1, roty(move, s), owned = false, delay = 0)
        when (bow) {
            // The Terminator's side arrows: Hypixel spawns each twice, identical (BOWS-07: 1 main + 2 pairs = 5 entities,
            // 4 of 4 isolated Terminator shots without Terror, 60 of 69 volleys with exactly 4 side entities, never
            // Terror-dependent): from 0.5 under pos1, your clean look +-5.5 deg at the main arrow's speed, with the
            // tick's gravity given back.
            Bows.TERMINATOR -> {
                val side = pos1.add(0.0, -0.5, 0.0)
                for (s in listOf(5.5, -5.5)) repeat(2) { out += Planned(side, side, roty(dir.scale(move.length()), s).add(0.0, G, 0.0), owned = false, delay = 0) }
            }
        }
        // Duplex: the main arrow's tick-2 move again, [duplexDelay] ticks later: two identical entities (BOWS-07, 266 of
        // 272 volleys with Terror at 10 stacks have exactly 2, plus 84 of 86 at 9 stacks).
        if (bow in DUPLEX) repeat(2) { out += Planned(pos1, pos1, move, owned = false, delay = duplexDelay) }
        return out
    }
}
