package com.engineerclient.p3sim.clear.damage

import com.engineerclient.EngineerClient
import com.engineerclient.p3sim.Bows
import com.engineerclient.p3sim.Mage
import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.SimItems
import com.engineerclient.p3sim.run.DamageSource
import com.engineerclient.p3sim.run.Run
import com.engineerclient.p3sim.run.RunStage
import com.engineerclient.p3sim.run.api.Services
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/**
 * The class Ability (Ctrl+Q) and Ultimate (Q) of each human in a run's dungeon ([ClassKit] has the rules and lines). Both start cooling at T0
 * and come up as recorded; a press while one cools says how long is left; the Ultimate's "ready to use" reminder repeats while it waits.
 *
 * What each does here:
 *  - Healer: Healing Circle heals everyone within 8 blocks by 10 percent of their health (a ring of hearts); Wish heals the whole party to full.
 *  - Mage: Guided Sheep (the P3 sim's sheep, which blows up on the mobs it meets); Thunderstorm strikes up to 3 mobs within 12 blocks every
 *    half second for 5 seconds, then says what it hit.
 *  - Berserk: Throwing Axe hurts every mob along 12 blocks of your look; Ragnarok is its sound and flash only.
 *  - Archer: Explosive Shot (the P3 sim's three exploding arrows); Rapid Fire shoots an arrow along your look every 2 ticks for 4 seconds.
 *  - Tank: Seismic Wave hurts the mobs in three bursts along the ground ahead (3, 6 and 9 blocks); Castle of Stone is its sound and look only.
 * The damage of every hit is the clear's blast damage ([DamageModule.blastDamage]).
 *
 * UNMEASURED: what Healing Circle, Thunderstorm, Throwing Axe, Seismic Wave, Rapid Fire, Ragnarok and Castle of Stone do in numbers (their
 * ranges, ticks and damage), the Healer's Wish shield, and whether an Ultimate's recharge is followed by reminders the way the first one is.
 * Server thread.
 */
object ClassAbilities {
    private class Timers(var abilityAt: Int, var ultAt: Int, var abilityNoted: Boolean = false, var ultNoted: Boolean = false, var reminderAt: Int = Int.MAX_VALUE, var ultUsed: Boolean = false)

    private val timers = HashMap<UUID, Timers>()
    private var t0: Int? = null

    /** Something that runs for a while after a press (Thunderstorm, Rapid Fire). */
    private class Effect(val player: UUID, val until: Int, val every: Int, val step: (ServerPlayer, Int) -> Unit, val end: (ServerPlayer) -> Unit = {})
    private val effects = ArrayList<Effect>()

    fun reset() { timers.clear(); effects.clear(); t0 = null }

    private fun timersOf(run: Run, p: ServerPlayer): Timers? {
        val start = run.t0Tick ?: return null
        if (t0 != start) { timers.clear(); t0 = start }
        // The Ability's first readiness is not announced: "is now available!" follows a use (139 DR runs: no such line in 66 Archer, Berserk
        // and Tank runs unless the Ability had been used; a Mage's every one follows a sheep, the three-click burst's too). The Mage's sheep
        // is ready from T0 (first sheep at T0 + 0, 4, 12 ... in 71 Mage runs; no "on cooldown" line before T0 + 420).
        val mage = kitOf(run, p)?.cls == "Mage"
        return timers.getOrPut(p.uuid) { Timers(if (mage) 0 else ClassKit.ABILITY_FIRST_READY, ClassKit.ULT_FIRST_READY, abilityNoted = true) }
    }

    private fun kitOf(run: Run, p: ServerPlayer): ClassKit.Kit? = ClassKit.of(run.state.players[p.uuid]?.dungeonClass)

    /** Doubled stats: nobody else in the party plays the class. */
    private fun doubled(run: Run, cls: String): Boolean = run.state.players.values.count { it.dungeonClass.equals(cls, ignoreCase = true) } <= 1

    private fun say(p: ServerPlayer, line: String) = Sim.asPlayer(p) { Sim.chat(line) }

    /** The drop key from [p]: true when a run's dungeon has it (so P3's own handling stays out). */
    fun press(p: ServerPlayer, ultimate: Boolean): Boolean {
        val run = DamageModule.activeRun() ?: return false
        if (run.stage == RunStage.COUNTDOWN) return true
        val kit = kitOf(run, p) ?: return true
        val tm = timersOf(run, p) ?: return true
        val rec = run.state.players[p.uuid]
        if (rec?.ghost == true) return true
        val t = run.t
        if (ultimate) {
            if (t < tm.ultAt) { say(p, ClassKit.ultimateCooling(tm.ultAt - t)); return true }
            say(p, ClassKit.used(kit.ultimate))
            tm.ultAt = t + ClassKit.ultimateCd(kit, doubled(run, kit.cls)); tm.ultNoted = false; tm.ultUsed = true; tm.reminderAt = Int.MAX_VALUE
            EngineerClient.safely("ads ultimate") { ultimateOf(run, p, kit) }
        } else {
            if (t < tm.abilityAt) { say(p, ClassKit.abilityCooling(tm.abilityAt - t)); return true }
            say(p, ClassKit.used(kit.ability))
            tm.abilityAt = t + ClassKit.abilityCd(kit, doubled(run, kit.cls)); tm.abilityNoted = false
            EngineerClient.safely("ads ability") { abilityOf(run, p, kit) }
        }
        return true
    }

    /**
     * A Mage's three-click burst in a run's dungeon: the Guided Sheep on the Ability's own cooldown (one cooldown for both ways), with no
     * "Used" line, nothing while it cools. False when no run's dungeon has it (P3 keeps [Mage]'s own).
     */
    fun burst(p: ServerPlayer): Boolean {
        val run = DamageModule.activeRun() ?: return false
        if (replayBursts?.invoke(p) == true) return true
        return sheep(run, p)
    }

    /** Set by a replay: true for a player whose sheep come from the recording ([recordedBurst]), not from their clicks. */
    @Volatile var replayBursts: ((ServerPlayer) -> Boolean)? = null

    /** A recorded sheep of [p]'s: the burst, on the same cooldown. */
    fun recordedBurst(p: ServerPlayer) { DamageModule.activeRun()?.let { sheep(it, p) } }

    private fun sheep(run: Run, p: ServerPlayer): Boolean {
        if (run.stage == RunStage.COUNTDOWN) return true
        val kit = kitOf(run, p)?.takeIf { it.ability == "Guided Sheep" } ?: return true
        val tm = timersOf(run, p) ?: return true
        if (run.state.players[p.uuid]?.ghost == true || run.t < tm.abilityAt) return true
        tm.abilityAt = run.t + ClassKit.abilityCd(kit, doubled(run, kit.cls)); tm.abilityNoted = false
        EngineerClient.safely("ads ability") { Mage.spawnSheep(p) }
        return true
    }

    /** Each tick of a dungeon-stage run: the "now available" and "ready to use" lines, and the effects that last. */
    fun tick(run: Run) {
        if (run.t0Tick == null || run.stage == RunStage.COUNTDOWN) return
        val t = run.t
        for (p in Sim.players()) {
            val kit = kitOf(run, p) ?: continue
            val tm = timersOf(run, p) ?: continue
            if (!tm.abilityNoted && t >= tm.abilityAt) { tm.abilityNoted = true; say(p, ClassKit.abilityAvailable(kit.ability)) }
            if (!tm.ultNoted && t >= tm.ultAt) {
                tm.ultNoted = true
                say(p, if (tm.ultUsed) ClassKit.ultimateRecharged(kit.cls, kit.ultimate) else ClassKit.ultimateReady(kit.ultimate))
                tm.reminderAt = t + ClassKit.ULT_REMINDER
            } else if (t >= tm.reminderAt) { tm.reminderAt = t + ClassKit.ULT_REMINDER; say(p, ClassKit.ultimateReady(kit.ultimate)) }
        }
        val it = effects.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val p = Sim.players().firstOrNull { pl -> pl.uuid == e.player }
            if (p == null || t > e.until) { if (p != null) EngineerClient.safely("ads ability end") { Sim.asPlayer(p) { e.end(p) } }; it.remove(); continue }
            if ((e.until - t) % e.every == 0) EngineerClient.safely("ads ability step") { Sim.asPlayer(p) { e.step(p, t) } }
        }
    }

    // ---- what they do ----------------------------------------------------------------------------------------------------

    private fun look(p: ServerPlayer): Vec3 {
        val yaw = Math.toRadians(p.yRot.toDouble()); val pitch = Math.toRadians(p.xRot.toDouble())
        return Vec3(-sin(yaw) * cos(pitch), -sin(pitch), cos(yaw) * cos(pitch))
    }

    private fun aliveMobsNear(at: Vec3, r: Double) = Services.mobs.all().filter { it.alive && Vec3(it.x, it.y, it.z).distanceTo(at) <= r }

    private fun hurt(run: Run, id: UUID, by: UUID): Double {
        val dmg = DamageModule.blastDamage
        return if (DamageModule.hurtMob(run, id, dmg, DamageSource.OTHER, by)) dmg else 0.0
    }

    private fun abilityOf(run: Run, p: ServerPlayer, kit: ClassKit.Kit) {
        val level = Sim.level
        when (kit.ability) {
            "Healing Circle" -> {
                for (r in run.state.players.values) {
                    val sp = Sim.players().firstOrNull { it.uuid == r.uuid }
                    val near = if (sp != null) sp.distanceTo(p) <= 8.0 else true
                    if (!r.ghost && near) r.hpPercent = minOf(100.0, r.hpPercent + 10.0)
                }
                for (i in 0 until 24) { val a = i * Math.PI / 12; level.sendParticles(ParticleTypes.HEART, p.x + 3 * cos(a), p.y + 0.5, p.z + 3 * sin(a), 1, 0.0, 0.0, 0.0, 0.0) }
                Sim.sound(SoundEvents.PLAYER_LEVELUP, 0.6f, 1.6f, p.position())
            }
            "Guided Sheep" -> Mage.spawnSheep(p)
            "Explosive Shot" -> SimItems.volleyArrows(p)
            "Throwing Axe" -> {
                val eye = p.eyePosition; val dir = look(p)
                val hit = HashSet<UUID>()
                for (s in 1..24) {
                    val at = eye.add(dir.scale(s * 0.5))
                    if (s % 2 == 0) level.sendParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 2, 0.1, 0.1, 0.1, 0.0)
                    for (m in aliveMobsNear(at, 1.5)) if (hit.add(m.id)) hurt(run, m.id, p.uuid)
                }
                Sim.sound(SoundEvents.TRIDENT_THROW, 1f, 0.8f, p.position())
            }
            "Seismic Wave" -> {
                val dir = look(p).let { Vec3(it.x, 0.0, it.z).normalize() }
                var n = 0; var total = 0.0
                for ((i, d) in listOf(3.0, 6.0, 9.0).withIndex()) {
                    val at = p.position().add(dir.scale(d))
                    level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.3, at.z, 2, 0.6, 0.1, 0.6, 0.0)
                    for (m in aliveMobsNear(at, 2.5)) { val dmg = hurt(run, m.id, p.uuid); if (dmg > 0) { n++; total += dmg } }
                    if (i == 0) Sim.sound(SoundEvents.GENERIC_EXPLODE.value(), 0.8f, 0.7f, at)
                }
                if (n > 0) say(p, ClassKit.hitLine("Seismic Wave", n, total))
            }
        }
    }

    private fun ultimateOf(run: Run, p: ServerPlayer, kit: ClassKit.Kit) {
        val level = Sim.level
        when (kit.ultimate) {
            "Wish" -> {
                var healed = 0.0
                for (r in run.state.players.values) if (!r.ghost) { healed += 100.0 - r.hpPercent; r.hpPercent = 100.0 }
                level.sendParticles(ParticleTypes.HEART, p.x, p.y + 1.0, p.z, 30, 1.5, 1.0, 1.5, 0.0)
                Sim.sound(SoundEvents.BEACON_ACTIVATE, 1f, 1.4f, p.position())
                say(p, ClassKit.wishSelf(0, 0))
            }
            "Thunderstorm" -> {
                var n = 0; var total = 0.0; val struck = HashSet<UUID>()
                effects += Effect(p.uuid, run.t + 100, 10, step = { sp, _ ->
                    val r = DamageModule.activeRun() ?: return@Effect
                    for (m in aliveMobsNear(sp.position(), 12.0).shuffled().take(3)) {
                        net.minecraft.world.entity.LightningBolt(net.minecraft.world.entity.EntityTypes.LIGHTNING_BOLT, level).let { b -> b.setVisualOnly(true); b.snapTo(m.x, m.y, m.z, 0f, 0f); level.addFreshEntity(b) }
                        val dmg = hurt(r, m.id, sp.uuid)
                        if (dmg > 0) { total += dmg; if (struck.add(m.id)) n++ }
                    }
                }, end = { sp -> if (n > 0) say(sp, ClassKit.hitLine("Thunderstorm", n, total)) })
            }
            "Rapid Fire" -> effects += Effect(p.uuid, run.t + 80, 2, step = { sp, _ ->
                val from = sp.eyePosition.subtract(0.0, 0.1, 0.0)
                Bows.launch(from, from, look(sp).scale(3.0), owner = sp)
                Sim.sound(SoundEvents.ARROW_SHOOT, 0.5f, 1.2f, sp.position())
            })
            "Ragnarok" -> {
                level.sendParticles(ParticleTypes.ANGRY_VILLAGER, p.x, p.y + 1.0, p.z, 12, 1.0, 0.8, 1.0, 0.0)
                Sim.sound(SoundEvents.RAVAGER_ROAR, 1f, 1f, p.position())
            }
            "Castle of Stone" -> {
                for (i in 0 until 32) { val a = i * Math.PI / 16; level.sendParticles(ParticleTypes.CLOUD, p.x + 2 * cos(a), p.y + 0.2, p.z + 2 * sin(a), 1, 0.0, 0.4, 0.0, 0.0) }
                Sim.sound(SoundEvents.ANVIL_LAND, 0.8f, 0.6f, p.position())
            }
        }
    }
}
