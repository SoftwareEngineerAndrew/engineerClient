package com.engineerclient.p3sim

import com.engineerclient.p3sim.clear.damage.DamageModule
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.animal.sheep.Sheep
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.Mannequin
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Mage's two in the sim (playing as Mage), as measured on Hypixel:
 *
 * Mage Beam: a left click with a sword fires a beam along your look (FIREWORK particles; up to ~40 blocks, stopped
 * by blocks). It lands at most once every 4 ticks; a hit plays the victim's hurt sound at your feet a tick later.
 *
 * Guided Sheep (the regular ability): Ctrl+Q ("Used Guided Sheep!"), or a burst of 3 beam clicks 2-5 ticks apart
 * (a tick after the third, no line). A white sheep from your feet (+0.78, ghast.shoot 0.3 at you) flies exactly
 * 1 block a tick along your look (its positions; the motion Hypixel sends with it, ~0.9 a little below the look,
 * isn't what it does), with a 3-block surge at ticks 4-6 (13 blocks out at 10 ticks, 28 at 25); it explodes on
 * a block or a boss (generic.explode, the hit line)
 * or after 60 ticks, and is gone a tick later. Ready again 139 ticks on ("Guided Sheep is now available!").
 */
object Mage {
    /** What fires a beam: the swords and the Bonzo Staff. */
    private val BEAM_ITEMS = setOf("HYPERION", "WITHER_CLOAK", "STARRED_BONZO_STAFF", "BONZO_STAFF")

    const val BEAM_RANGE = 40.0
    private const val BEAM_GAP = 4
    private const val SHEEP_COOLDOWN = 139
    private const val SHEEP_SPEED = 1.0
    /** The ticks of the sheep's life it covers 2 blocks instead of 1. */
    private val SHEEP_SURGE = 4..6
    private const val SHEEP_LIFE = 60

    // Each player's own ([perPlayer]): their beam, sheep and cooldowns.
    private var beamReady by perPlayer { 0 }
    /** The server ticks of the last beam clicks (for the sheep's burst). */
    private val clicks: ArrayDeque<Int> by perPlayer { ArrayDeque() }
    private var sheepReady by perPlayer { 0 }
    private var readyNoted by perPlayer { true }

    /** A sheep that can't push you, be pushed or be aimed at. */
    class GhostSheep(level: net.minecraft.world.level.Level) : Sheep(EntityTypes.SHEEP, level) {
        override fun isPickable() = false
        override fun isPushable() = false
        override fun canBeCollidedWith(other: net.minecraft.world.entity.Entity?) = false
        override fun pushEntities() {}
    }

    private class Flying(val e: Sheep, val at: Int) { var boomAt = -1 }
    private val sheep: ArrayList<Flying> by perPlayer { ArrayList() }

    /** Mage, or any class with Mage Beam On All Classes: the beam and Guided Sheep are yours. */
    fun isMage() = P3Sim.myClass == DungeonClass.MAGE || P3Sim.mageAll

    fun beamItem(id: String?) = id in BEAM_ITEMS

    fun reset() {
        beamReady = 0; clicks.clear(); sheepReady = 0; readyNoted = true
        sheep.forEach { it.e.discard() }; sheep.clear()
    }

    /** A left click with a beam item (server thread, after the ping). */
    fun click(p: ServerPlayer) {
        if (!isMage()) return
        val now = Fight.serverTick
        if (now >= beamReady) { beamReady = now + BEAM_GAP; beam(p) }
        // The sheep's burst: three clicks, each 2-5 ticks after the one before.
        clicks.addLast(now)
        while (clicks.size > 3) clicks.removeFirst()
        if (clicks.size == 3 && clicks.zipWithNext().all { (a, b) -> b - a in 2..5 } && now >= sheepReady) {
            clicks.clear()
            Fight.later(1, "guided sheep") { if (!p.isRemoved && !com.engineerclient.p3sim.clear.damage.ClassAbilities.burst(p)) launchSheep(p) }
        }
    }

    /** Ctrl+Q as Mage. */
    fun ability(p: ServerPlayer) {
        if (!isMage()) return
        val now = Fight.serverTick
        if (now < sheepReady) { Sim.chat("§cYour Regular Ability is currently on cooldown for ${(sheepReady - now + 19) / 20} more seconds."); return }
        Sim.chat("§aUsed §6Guided Sheep§a!")
        launchSheep(p)
    }

    private fun look(p: ServerPlayer, down: Double = 0.0): Vec3 {
        val yaw = Math.toRadians(p.yRot.toDouble()); val pitch = Math.toRadians(p.xRot.toDouble() + down)
        return Vec3(-sin(yaw) * cos(pitch), -sin(pitch), cos(yaw) * cos(pitch))
    }

    /** What a ray from [from] to [to] hits first: a boss or mob (not you, the bots, stands). */
    private fun firstMob(from: Vec3, to: Vec3, p: ServerPlayer): Pair<LivingEntity, Vec3>? {
        val box = AABB(from, to).inflate(1.0)
        return Sim.level.getEntitiesOfClass(LivingEntity::class.java, box) {
            // In a full run a dungeon mob is a target whatever its body is (a disguised or sleeping one included).
            it !== p && it.isAlive && (DamageModule.mobFor(it) != null ||
                it !is net.minecraft.world.entity.player.Player && it !is Mannequin && it !is ArmorStand && it !is Sheep && !it.isInvisible)
        }.mapNotNull { e -> e.boundingBox.inflate(0.3).clip(from, to).orElse(null)?.let { e to it } }.minByOrNull { it.second.distanceToSqr(from) }
    }

    private fun beam(p: ServerPlayer) {
        val eye = p.eyePosition
        val dir = look(p)
        val far = eye.add(dir.scale(BEAM_RANGE))
        val block = Sim.level.clip(ClipContext(eye, far, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p))
        var end = if (block.type == HitResult.Type.BLOCK) block.location else far
        val mob = firstMob(eye, end, p)
        if (mob != null) end = mob.second
        // The beam: firework sparks every half block, from just in front of you to where it stops.
        val start = eye.add(0.0, -0.2, 0.0).add(dir.scale(0.6))
        val len = end.distanceTo(start)
        var d = 0.0
        while (d <= len) {
            val at = start.add(dir.scale(d))
            Sim.level.sendParticles(ParticleTypes.FIREWORK, at.x, at.y, at.z, 1, 0.0, 0.0, 0.0, 0.0)
            d += 0.5
        }
        // P2: a beam at Storm frees him from a pin.
        (Fight.phase as? P2Storm)?.beam()
        mob?.first?.let { e ->
            DamageModule.beamHit(e, p)
            p.connection.send(net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket(e))
            // Hypixel's hit sound: the victim's hurt sound at your feet, a tick later.
            Fight.later(1, "beam hit") {
                val s = if (e is net.minecraft.world.entity.boss.wither.WitherBoss) SoundEvents.WITHER_HURT else SoundEvents.GENERIC_HURT
                Sim.sound(s, 1f, 1f, p.position(), SoundSource.HOSTILE)
            }
        }
    }

    private fun launchSheep(p: ServerPlayer) {
        val now = Fight.serverTick
        if (now < sheepReady) return
        sheepReady = now + SHEEP_COOLDOWN
        readyNoted = false
        spawnSheep(p)
    }

    /** The sheep itself, with no cooldown of its own: the clear's class abilities ([com.engineerclient.p3sim.clear.damage.ClassAbilities]) keep theirs. */
    internal fun spawnSheep(p: ServerPlayer) {
        val e = GhostSheep(Sim.level)
        e.setNoAi(true); e.setNoGravity(true); e.isPermanentlyInvulnerable = true; e.isSilent = true
        e.snapTo(p.x, p.y + 0.781, p.z, p.yRot, 0f)
        e.yHeadRot = p.yRot - 10f
        sheep += Flying(Sim.spawn(e), Fight.serverTick)
        Sim.sound(SoundEvents.GHAST_SHOOT, 0.3f, 1f, p.position(), SoundSource.HOSTILE)
    }

    fun tick() {
        val now = Fight.serverTick
        if (!readyNoted && now >= sheepReady) { readyNoted = true; if (isMage()) Sim.chat("§6Guided Sheep §r§ais now available!") }
        if (sheep.isEmpty()) return
        val p = Sim.player
        val it = sheep.iterator()
        while (it.hasNext()) {
            val f = it.next()
            val e = f.e
            if (e.isRemoved) { it.remove(); continue }
            if (f.boomAt >= 0) { if (now > f.boomAt) { e.discard(); it.remove() }; continue }
            // Steered along your look, 1 a tick (2 in the surge).
            val v = (p?.let { look(it) } ?: e.deltaMovement.normalize()).scale(SHEEP_SPEED * (if (now - f.at in SHEEP_SURGE) 2 else 1))
            val from = e.position()
            val to = from.add(v)
            val block = Sim.level.clip(ClipContext(from.add(0.0, 0.4, 0.0), to.add(0.0, 0.4, 0.0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, e))
            val mob = p?.let { firstMob(from.add(0.0, 0.4, 0.0), to.add(0.0, 0.4, 0.0), it) }
            val stop = when {
                mob != null -> mob.second.subtract(0.0, 0.4, 0.0)
                block.type == HitResult.Type.BLOCK -> block.location.subtract(0.0, 0.4, 0.0)
                else -> null
            }
            val at = stop ?: to
            e.deltaMovement = v
            e.snapTo(at.x, at.y, at.z, Math.toDegrees(Math.atan2(-v.x, v.z)).toFloat(), 0f)
            if (stop != null || now - f.at >= SHEEP_LIFE) {
                f.boomAt = now
                Sim.sound(SoundEvents.GENERIC_EXPLODE, 1f, 0.8f + kotlin.random.Random.nextFloat() * 0.18f, at, SoundSource.MASTER)
                Sim.level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.5, at.z, 1, 0.0, 0.0, 0.0, 0.0)
                // A full run: the blast takes HP off every mob within reach, and the line says how many and how much.
                val hit = DamageModule.blast(at.x, at.y, at.z, com.engineerclient.p3sim.run.DamageSource.MAGE_BEAM, p?.uuid)
                if (hit > 0) {
                    val total = "%,.1f".format(java.util.Locale.ROOT, hit * DamageModule.blastDamage).removeSuffix(".0")
                    Sim.chat("§7Your Guided Sheep hit §c$hit §7${if (hit == 1) "enemy" else "enemies"} for §c$total §7damage.")
                }
                else if (mob != null && DamageModule.activeRun() == null) Sim.chat("§7Your Guided Sheep hit §c1 §7enemy for §c0 §7damage.")
                // Like Explosive Shot: a blast within 2 blocks of a gate blows it.
                (Fight.phase as? GoldorPhase)?.let { g -> g.gateNear(at, 2.0).takeIf { it > 0 }?.let { g.blowGate(it, Sim.me) } }
            }
        }
    }
}
