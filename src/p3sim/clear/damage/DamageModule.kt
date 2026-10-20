package com.engineerclient.p3sim.clear.damage

import com.engineerclient.EngineerClient
import com.engineerclient.p3sim.Masks
import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.run.AdsCommand
import com.engineerclient.p3sim.run.AdsModule
import com.engineerclient.p3sim.run.DamageSource
import com.engineerclient.p3sim.run.EndReason
import com.engineerclient.p3sim.run.PlayerDamaged
import com.engineerclient.p3sim.run.PlayerDied
import com.engineerclient.p3sim.run.PlayerRecord
import com.engineerclient.p3sim.run.PlayerRevived
import com.engineerclient.p3sim.run.Run
import com.engineerclient.p3sim.run.Runs
import com.engineerclient.p3sim.run.api.DamageService
import com.engineerclient.p3sim.run.api.HurtResult
import com.engineerclient.p3sim.run.api.MobRecord
import com.engineerclient.p3sim.run.api.Services
import com.google.gson.JsonObject
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * The damage hub of a full run.
 *
 * Mob side: every hit on a mob (an Implosion, an arrow, a Mage beam, a sword) goes through [hurtMob], which lowers the mob's
 * SkyBlock-scale HP through the mobs module ([com.engineerclient.p3sim.run.api.MobService.setHp]: the nametag, `MobDamaged`,
 * the kill and the key drop are its work). A cast of Hyperion's Implosion ([castImplosion]) reaches every mob in its box; on F7
 * that is one cast per kill, on M7 up to 13 ([ImplosionRules]).
 *
 * Player side: a percent-of-max-HP ledger per party member in [com.engineerclient.p3sim.run.RunState] ([PlayerLedger]).
 * The vanilla health bar a player sees is `40 * percent / 100`. A hit that would kill first meets the masks and Phoenix (the
 * existing [Masks]), then the player dies: ghost for 6 seconds, `Team Deaths` +1, revived on the timer. "No deaths" in the
 * run's settings holds the ledger at one vanilla hit point instead. Health also comes back slowly ([regenPercentPerSecond]).
 *
 * In the BOSS stage this hub does nothing: the boss sim's own damage, masks and ghost handling run unchanged.
 */
object DamageModule : AdsModule, DamageService {
    override val id = "damage"
    override val owner = "damage"

    // ---- settings (created in registerSettings; the defaults below are what a test or a headless run sees) -----------

    private lateinit var arrowS: NumberSetting<Int>
    private lateinit var beamS: NumberSetting<Int>
    private lateinit var meleeS: NumberSetting<Int>
    private lateinit var blastS: NumberSetting<Int>
    private lateinit var implosionS: NumberSetting<Int>
    private lateinit var regenS: NumberSetting<Int>
    private lateinit var shieldS: NumberSetting<Int>

    /** HP one arrow takes off a mob. UNMEASURED (the damage numbers in the recordings were never mined): ordinary F7 mobs have 0.4 to 8.8 million. */
    const val DEFAULT_ARROW = 1_200_000.0
    /** HP one Mage beam takes off a mob. UNMEASURED. */
    const val DEFAULT_BEAM = 1_000_000.0
    /** HP a sword hit takes off a mob. UNMEASURED (clears are done with Implosion and bows). */
    const val DEFAULT_MELEE = 500_000.0
    /** HP an Explosive Shot blast or a Guided Sheep takes off each mob within [BLAST_RADIUS]. UNMEASURED. */
    const val DEFAULT_BLAST = 3_000_000.0
    /** How far a blast reaches. UNMEASURED. */
    const val BLAST_RADIUS = 3.0
    /** Percent of max HP that comes back each second, in one step every [REGEN_STEP_TICKS] ticks. Measured: p50 2.3 (p10 1.3, p90 3.5), starting about 5 ticks after a hit. */
    const val DEFAULT_REGEN = 2.3
    /** Ticks between two regeneration steps (measured: 20 in half the steps, 15 to 25 otherwise). */
    const val REGEN_STEP_TICKS = 20
    /** Ticks after a hit before the first regeneration step. */
    const val REGEN_AFTER_HIT_TICKS = 5

    val arrowDamage: Double get() = if (::arrowS.isInitialized) arrowS.value * 1000.0 else DEFAULT_ARROW
    val beamDamage: Double get() = if (::beamS.isInitialized) beamS.value * 1000.0 else DEFAULT_BEAM
    val meleeDamage: Double get() = if (::meleeS.isInitialized) meleeS.value * 1000.0 else DEFAULT_MELEE
    val blastDamage: Double get() = if (::blastS.isInitialized) blastS.value * 1000.0 else DEFAULT_BLAST
    private val implosionMultiplier: Double get() = if (::implosionS.isInitialized) implosionS.value / 100.0 else 1.0
    val regenPercentPerSecond: Double get() = if (::regenS.isInitialized) regenS.value / 10.0 else DEFAULT_REGEN
    /** What a fresh Wither Shield soaks, in percent of max health. UNMEASURED. */
    val shieldPoolPercent: Double get() = if (::shieldS.isInitialized) shieldS.value.toDouble() else WitherShield.DEFAULT_POOL_PERCENT

    override fun registerSettings(host: com.odtheking.odin.features.Module) {
        arrowS = host.registerSetting(NumberSetting("Clear Arrow Damage", 1200, 50..20000, 50, unit = "k", desc = "In a full run: the HP one arrow takes off a mob (thousands). Not measured on Hypixel; F7 mobs have 420k to 8.8M."))
        beamS = host.registerSetting(NumberSetting("Clear Beam Damage", 1000, 50..20000, 50, unit = "k", desc = "In a full run: the HP one Mage beam takes off a mob (thousands). Not measured on Hypixel."))
        meleeS = host.registerSetting(NumberSetting("Clear Sword Damage", 500, 50..20000, 50, unit = "k", desc = "In a full run: the HP a sword hit takes off a mob (thousands). Not measured on Hypixel."))
        blastS = host.registerSetting(NumberSetting("Clear Blast Damage", 3000, 50..20000, 50, unit = "k", desc = "In a full run: the HP an Explosive Shot blast or a Guided Sheep takes off each mob within 3 blocks (thousands). Not measured on Hypixel."))
        implosionS = host.registerSetting(NumberSetting("Implosion Damage", 100, 10..400, 5, unit = "%", desc = "In a full run: scales the floor's measured Implosion damage per mob (F7 25.4M, M7 56.6M). 100 is your recorded gear; on M7 lower values need more casts per mob."))
        regenS = host.registerSetting(NumberSetting("Clear Health Regen", 23, 0..100, 1, unit = " per 10 s", desc = "In a full run: percent of max health that comes back every 10 seconds, in a step every second (23 means 2.3 percent a second, the middle of what Hypixel shows: 1.3 to 3.5)."))
        numbersS = host.registerSetting(NumberSetting("Damage Numbers", DamageNumbers.DEFAULT_CHANCE, 0..100, 1, unit = "%", desc = "In a full run: the share of hits on a mob that float a damage number over it for a second (grey, or a coloured ✧crit✧). On Hypixel every hit by every player gets one."))
        shieldS = host.registerSetting(NumberSetting("Wither Shield Size", 93, 0..200, 1, unit = "%", desc = "In a full run: how much damage a Hyperion cast's Wither Shield soaks, in percent of your max health. Not measured on Hypixel."))
    }

    // ---- state ----------------------------------------------------------------------------------------------------

    private lateinit var numbersS: NumberSetting<Int>
    private var numberRolls: Random = Random(0)

    private val mobLedger = MobDamageLedger()
    private var rolls: Random = Random(0)
    private val meleeRandom: Random get() = rolls
    /** The run tick of each player's next regeneration step. */
    private val regenCarry = HashMap<UUID, Int>()
    private val shields = HashMap<UUID, WitherShield>()
    /** Damage each player has dealt so far and how many milestone lines they have had. */
    private val dealt = HashMap<UUID, Double>()
    private val milestonesShown = HashMap<UUID, Int>()
    /** A ghost being revived: who is reviving, and the run tick it completes. */
    private class Reviving(val by: UUID, val dueT: Int)
    private val reviving = HashMap<UUID, Reviving>()
    private val shown = HashMap<UUID, Float>()

    /** The mobs module (or anyone who knows) can say a mob cannot be hurt right now: a sleeping Fels. Default: tagged [ASLEEP_TAG]. */
    var sleepingCheck: (MobRecord) -> Boolean = { Services.mobs.isAsleep(it.id) }

    /** An entity tag the mobs module may put on a sleeping mob's body so the damage hub leaves it alone. */
    const val ASLEEP_TAG = "ads_asleep"

    /** What a cast did. */
    class Cast(val count: Int, val total: Double, val killed: Int)

    /** The run to hurt things in: a dungeon-stage run that is not over (the boss stage keeps its own damage). */
    fun activeRun(): Run? = Runs.current?.takeIf { !it.isOver && it.stage.inDungeon }

    /** The Grandma Wolf Kill Combo of each human. */
    private val combo = KillCombo()
    /** Whether the humans have the pet (a replay of someone without it has no Kill Combo lines). */
    @Volatile var killCombo = true

    override fun onRunStart(run: Run) {
        combo.clear()
        mobLedger.clear(); regenCarry.clear(); shown.clear(); shields.clear(); dealt.clear(); milestonesShown.clear(); reviving.clear()
        rolls = run.subRandom("damage")
        numberRolls = run.subRandom("damage.numbers")
        ClassAbilities.reset()
    }

    override fun onRunEnd(run: Run, reason: EndReason) {
        ClassAbilities.reset()
        mobLedger.clear(); regenCarry.clear(); shields.clear(); dealt.clear(); milestonesShown.clear(); reviving.clear()
        EngineerClient.safely("ads damage end") {
            Masks.stopped()
            // The boss sim expects its permanent 16 absorption back.
            for (p in Sim.players()) { p.health = PlayerLedger.FULL_BAR.toFloat(); p.absorptionAmount = WitherShield.FULL_ABSORPTION.toFloat() }
        }
    }

    override fun restore(run: Run, data: JsonObject) {
        ClassAbilities.reset()
        mobLedger.clear(); regenCarry.clear(); shown.clear(); shields.clear(); dealt.clear(); milestonesShown.clear(); reviving.clear()
        // A ghost the savepoint says is alive comes back with the rest of the party.
        for (rec in run.state.players.values) if (!rec.ghost) human(rec.uuid)?.let { p -> Sim.asPlayer(p) { if (Masks.ghost) Masks.reviveGhost(p, null) } }
    }

    override fun onEvent(run: Run, event: com.engineerclient.p3sim.run.RunEvent) {
        if (event is com.engineerclient.p3sim.run.MobKilled && killCombo) event.by?.let { id ->
            val p = human(id) ?: return@let
            combo.kill(id, run.t)?.let { line -> EngineerClient.safely("ads kill combo") { Sim.asPlayer(p) { Sim.chat(line) } } }
        }
        // The Dungeonbreaker gains 5 charges for each secret its finder collects.
        if (event is com.engineerclient.p3sim.run.SecretFound) event.player?.let { id ->
            human(id)?.let { p -> EngineerClient.safely("ads damage breaker charges") { Sim.asPlayer(p) { com.engineerclient.p3sim.SimItems.addBreakerCharges(BREAKER_CHARGES_PER_SECRET) } } }
        }
    }

    /** Dungeonbreaker charges a secret gives its finder (the item's own rule). */
    const val BREAKER_CHARGES_PER_SECRET = 5

    // ---- tick -----------------------------------------------------------------------------------------------------

    override fun onTick(run: Run) {
        if (!run.stage.inDungeon) return
        val t = run.t
        EngineerClient.safely("ads class abilities") { ClassAbilities.tick(run) }
        for ((id, line) in combo.expire(t)) if (line != null) human(id)?.let { p -> EngineerClient.safely("ads kill combo") { Sim.asPlayer(p) { Sim.chat(line) } } }
        for (rec in run.state.players.values.toList()) {
            if (PlayerLedger.reviveDue(rec, t)) { reviving.remove(rec.uuid); revive(run, rec.uuid, null); continue }
            val r = reviving[rec.uuid]
            if (r != null) {
                // The reviver dying (or leaving) cancels it; otherwise it completes on time.
                val reviver = run.state.players[r.by]
                if (!rec.ghost || reviver == null || reviver.ghost) reviving.remove(rec.uuid)
                else if (t >= r.dueT) { reviving.remove(rec.uuid); revive(run, rec.uuid, r.by); continue }
            }
            if (!rec.ghost && rec.hpPercent < 100.0 && regenPercentPerSecond > 0.0) {
                // A step of a second's worth every 20 ticks, the first 5 ticks after the latest hit.
                val next = regenCarry.getOrPut(rec.uuid) { if (rec.lastHitT != Int.MIN_VALUE) rec.lastHitT + REGEN_AFTER_HIT_TICKS else t + REGEN_STEP_TICKS }
                if (t >= next) {
                    rec.hpPercent = minOf(100.0, rec.hpPercent + regenPercentPerSecond * REGEN_STEP_TICKS / 20.0)
                    regenCarry[rec.uuid] = t + REGEN_STEP_TICKS
                }
            } else regenCarry.remove(rec.uuid)
            human(rec.uuid)?.let { p ->
                showBar(p, if (rec.ghost) 100.0 else rec.hpPercent)
                showShield(p, if (rec.ghost) 0.0 else (shields[rec.uuid]?.absorption(t) ?: 0.0))
            }
        }
    }

    /** Puts the vanilla health bar at `40 * percent / 100` (only when it changed). */
    private fun showBar(p: ServerPlayer, percent: Double) {
        val v = PlayerLedger.vanilla(percent).toFloat()
        if (shown[p.uuid] == v && p.health == v) return
        shown[p.uuid] = v
        p.health = v
    }

    /** The gold bar: 16 for a fresh Wither Shield, less as it soaks hits, nothing without one. */
    private fun showShield(p: ServerPlayer, amount: Double) {
        val v = amount.toFloat()
        if (p.absorptionAmount == v) return
        // 26.1.2 and later clamp absorption to the attribute: make room for the 16 first.
        p.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_ABSORPTION)?.let { if (it.baseValue < WitherShield.FULL_ABSORPTION) it.baseValue = WitherShield.FULL_ABSORPTION }
        p.absorptionAmount = v
    }

    /** A Hyperion cast by [p] in the clear: starts the Wither Shield (a cast that hits nothing still does). */
    fun startShield(p: ServerPlayer) {
        val run = activeRun() ?: return
        val rec = run.state.players[p.uuid] ?: return
        if (rec.ghost) return
        val cur = shields[p.uuid]
        if (cur == null || !cur.active(run.t)) shields[p.uuid] = WitherShield(shieldPoolPercent).also { it.start(run.t) }
    }

    private fun human(id: UUID): ServerPlayer? = Sim.players().firstOrNull { it.uuid == id }

    // ---- mob side ---------------------------------------------------------------------------------------------------

    /** The mob record whose body is [e], or null (not a mob of the run, or no run). */
    fun mobFor(e: Entity): MobRecord? {
        if (activeRun() == null) return null
        val id = e.id
        return Services.mobs.recordOfEntity(id)?.takeIf { it.alive }
    }

    private fun asleep(rec: MobRecord): Boolean {
        if (sleepingCheck(rec)) return true
        val e = rec.entityId?.let { Sim.level.getEntity(it) } ?: return false
        return e.entityTags().contains(ASLEEP_TAG)
    }

    override fun hurtMob(run: Run, mob: UUID, asked: Double, source: DamageSource, by: UUID?): Boolean {
        if (!run.stage.inDungeon) return false
        val rec = Services.mobs.mob(mob) ?: return false
        val replayed = replayHit?.invoke(mob, by)
        val amount = replayed ?: asked
        if (!rec.alive || amount <= 0.0 || amount.isNaN()) return false
        // A sleeping Fels cannot be hurt, except by a teammate bot (it wakes it, as a body next to it would).
        if (asleep(rec) && by?.let { run.state.players[it]?.isBot } != true) return false
        // For now every hit kills (no damage maths in the clear yet): the mob takes all it has left.
        val left = if (oneShot && replayed == null) 0.0 else max(0.0, rec.hp - amount)
        mobLedger.record(mob, amount, source, by)
        // The milestones count the health a hit took, not the hit: a 69 million Implosion on three mobs of about a million each is
        // Hypixel's first milestone (750,000), not its fifth (DR, a Mage's run: ❶ on that cast, ❷ 18 ticks later, ❸ at 11 s).
        if (by != null) addDealt(run, by, min(amount, rec.hp), source)
        val numbers = if (::numbersS.isInitialized) numbersS.value else DamageNumbers.DEFAULT_CHANCE
        if (numbers > 0 && numberRolls.nextInt(100) < numbers) EngineerClient.safely("ads damage number") {
            val e = rec.entityId?.let { Sim.level.getEntity(it) }
            if (e != null) DamageNumbers.show(e.x, e.y, e.z, amount, numberRolls)
        }
        val after = Services.mobs.setHp(run, mob, left, by)
        // The kill counts only when the mob really died (the mobs module may have refused or lost it), so the count matches the MobKilled events.
        if (left <= 0.0 && after?.alive == false) {
            mobLedger.forget(mob)
            if (by != null) run.state.players[by]?.let { it.extras[KILLS] = ((it.extras[KILLS] as? Int) ?: 0) + 1 }
        } else if (left > 0.0) EngineerClient.safely("ads damage hurt animation") {
            val e = rec.entityId?.let { Sim.level.getEntity(it) } as? LivingEntity
            if (e != null) for (p in Sim.players()) p.connection.send(ClientboundHurtAnimationPacket(e))
        }
        return true
    }

    /** Adds to [by]'s damage total and prints the class milestone lines it crosses (several can fire in one tick). */
    private fun addDealt(run: Run, by: UUID, amount: Double, source: DamageSource) {
        val rec = run.state.players[by] ?: return
        // An Archer's milestones count "Ranged Damage" (their line says so): a Hyperion's Implosion or a sword hit is not.
        if (rec.dungeonClass.equals("archer", true) && source != DamageSource.BOW && source != DamageSource.PROJECTILE) return
        val total = (dealt[by] ?: 0.0) + amount
        dealt[by] = total
        val cls = rec.dungeonClass ?: return
        val table = ClassMilestones.thresholds(cls) ?: return
        val shownSoFar = milestonesShown[by] ?: 0
        val now = ClassMilestones.reached(table, total)
        if (now <= shownSoFar) return
        milestonesShown[by] = now
        val sp = human(by) ?: return
        val seconds = com.engineerclient.p3sim.clear.secrets.Blessings.seconds(run.t)
        for (i in shownSoFar until now) Sim.asPlayer(sp) { Sim.chat(ClassMilestones.line(cls, i, table, seconds)) }
    }

    /** A replay's damage for a hit: the HP the recording's mob lost then (null: not the replay's hit to say, 0: Hypixel shows no loss then). */
    @Volatile var replayHit: ((UUID, UUID?) -> Double?)? = null

    /** Every hit on a clear mob kills it (the damage numbers are not modelled yet). */
    @Volatile var oneShot = true

    /** The key of a player's kill count in [PlayerRecord.extras]. */
    const val KEY_KILLS = "damage.kills"
    private const val KILLS = KEY_KILLS

    /** What the book says a mob has taken (for the dev tools and tests). */
    fun taken(mob: UUID): MobDamageLedger.Entry? = mobLedger.of(mob)

    override fun implode(run: Run, caster: UUID?, box: DoubleArray, damage: Double?): Int = cast(run, caster, box, damage).count

    /** A Hyperion Implosion cast by [p] at their final position: the box is around their eye. */
    fun castImplosion(run: Run, p: ServerPlayer): Cast = cast(run, p.uuid, ImplosionRules.castBox(p.x, p.eyeY, p.z), null)

    private fun hitboxOf(rec: MobRecord): ImplosionRules.Hitbox {
        val e = rec.entityId?.let { Sim.level.getEntity(it) }
        if (e != null) {
            val b = e.boundingBox
            return ImplosionRules.Hitbox((b.minX + b.maxX) / 2, b.minY, (b.minZ + b.maxZ) / 2, b.maxX - b.minX, b.maxY - b.minY)
        }
        val (w, h) = ImplosionRules.size(rec.kind)
        return ImplosionRules.Hitbox(rec.x, rec.y, rec.z, w, h)
    }

    /** Runs a cast in [box]: one damage roll for the cast (the same for every mob), every mob it reaches takes it. A secret bat in the box dies and counts as an enemy. */
    fun cast(run: Run, caster: UUID?, box: DoubleArray, damage: Double?): Cast {
        if (!run.stage.inDungeon) return Cast(0, 0.0, 0)
        val all = Services.mobs.all().toList()
        // A secret bat is a plain bat, not a registered mob: the secrets module kills the ones in the box. The cast's line counts each one as an enemy.
        val bats = if (box.size >= 6) killBats(run, net.minecraft.world.phys.AABB(box[0], box[1], box[2], box[3], box[4], box[5]), caster) else 0
        val ids = ImplosionRules.select(box, all.map { ImplosionRules.Candidate(it.id, hitboxOf(it), it.alive, asleep(it)) })
        if (ids.isEmpty() && bats == 0) return Cast(0, 0.0, 0)
        val profile = ImplosionRules.profile(run.config.floor)
        val base = damage ?: profile.castBase(rolls, implosionMultiplier)
        val scale = run.config.mobHpScale ?: 1.0
        // A bat takes the cast's base roll: it has 100 HP, so any hit kills it, but the line's total still adds the roll.
        var hit = bats; var killed = bats; var total = bats * base
        for (id in ids) {
            val before = Services.mobs.mob(id)
            // An explicit damage (a caller's own number) is the same for every mob; otherwise each mob's kind and hp decide.
            val dmg = if (damage != null || before == null) base else profile.perMob(before.kind, before.miniboss, before.maxHp, scale, base, rolls)
            if (hurtMob(run, id, dmg, DamageSource.IMPLOSION, caster)) {
                hit++; total += dmg
                if (before != null && !before.alive) killed++
            }
        }
        return Cast(hit, total, killed)
    }

    // ---- secret bats -------------------------------------------------------------------------------------------------------

    /**
     * A secret bat: the silent, persistent plain bat the secrets module puts at a bat site, alive, in a run's dungeon stage. It is not a
     * registered mob ([mobFor] is null for it), but any damage kills it (100 HP in one packet), so arrows and blasts must reach it.
     */
    fun isSecretBat(e: Entity): Boolean =
        e is net.minecraft.world.entity.ambient.Bat && e.isAlive && e.isSilent && e.isPersistenceRequired && activeRun() != null

    /** Who kills the secret bats in a box and says how many died: the secrets module (it credits the finder and plays Odin's bat sound). A test swaps it. */
    internal var batKiller: (Run, net.minecraft.world.phys.AABB, UUID?) -> Int = { run, box, by -> com.engineerclient.p3sim.clear.secrets.SecretsModule.killBats(run, box, by) }

    /** Kills the secret bats inside [box] through [batKiller]; 0 when there are none or the module fails. */
    private fun killBats(run: Run, box: net.minecraft.world.phys.AABB, by: UUID?): Int {
        var n = 0
        EngineerClient.safely("ads damage bats") { n = batKiller(run, box, by) }
        return n
    }

    /** An arrow of [shooter]'s hit [e]; true when it was a mob of the run (the damage is taken off) or a secret bat (it dies). */
    fun arrowHit(e: Entity, shooter: ServerPlayer?): Boolean {
        val run = activeRun() ?: return false
        val rec = mobFor(e)
        // A secret bat dies to the arrow like to any other damage.
        if (rec == null) return isSecretBat(e) && killBats(run, e.boundingBox.inflate(BAT_HIT_MARGIN), shooter?.uuid) > 0
        hurtMob(run, rec.id, arrowDamage, DamageSource.BOW, shooter?.uuid)
        return true
    }

    /** How far a bat's box is widened to find the bat an arrow hit among the secrets module's (it only needs to touch this one). */
    private const val BAT_HIT_MARGIN = 0.05

    /** A Mage beam of [p]'s hit [e]; true when it was a mob of the run. */
    fun beamHit(e: Entity, p: ServerPlayer): Boolean {
        val run = activeRun() ?: return false
        val rec = mobFor(e)
        if (rec == null) {
            // A secret bat (a plain bat, not a registered mob) dies to the beam.
            if (e is net.minecraft.world.entity.ambient.Bat) killBats(run, e.boundingBox.inflate(0.3), p.uuid)
            return false
        }
        hurtMob(run, rec.id, beamDamage, DamageSource.MAGE_BEAM, p.uuid)
        return true
    }

    /** A blast (Explosive Shot, Guided Sheep) at ([x],[y],[z]): every mob whose body is within [BLAST_RADIUS] takes [blastDamage], and every secret bat there dies. Returns how many. */
    fun blast(x: Double, y: Double, z: Double, source: DamageSource, by: UUID?): Int {
        val run = activeRun() ?: return 0
        val box = doubleArrayOf(x - BLAST_RADIUS, y - BLAST_RADIUS, z - BLAST_RADIUS, x + BLAST_RADIUS, y + BLAST_RADIUS, z + BLAST_RADIUS)
        val ids = ImplosionRules.select(box, Services.mobs.all().map { ImplosionRules.Candidate(it.id, hitboxOf(it), it.alive, asleep(it)) })
        val bats = killBats(run, net.minecraft.world.phys.AABB(box[0], box[1], box[2], box[3], box[4], box[5]), by)
        return ids.count { hurtMob(run, it, blastDamage, source, by) } + bats
    }

    override fun onAttackEntity(run: Run, player: ServerPlayer, hand: InteractionHand, entity: Entity): InteractionResult? {
        val rec = mobFor(entity) ?: return null
        // A Mage's beam item fires from the click itself (Mage.click); a sword hit on top would hit twice.
        val held = com.engineerclient.p3sim.SimItems.idOf(player.mainHandItem)
        if (!(com.engineerclient.p3sim.Mage.isMage() && com.engineerclient.p3sim.Mage.beamItem(held))) hurtMob(run, rec.id, meleeDamage, DamageSource.MELEE, player.uuid)
        return InteractionResult.SUCCESS
    }

    // ---- the Revive Stone ---------------------------------------------------------------------------------------------

    /** The item id the secrets module gives from chests: a right click revives a dead teammate and uses it up. */
    const val REVIVE_STONE = "REVIVE_STONE"

    override fun onUseItem(run: Run, player: ServerPlayer, hand: InteractionHand): InteractionResult? = useReviveStone(run, player, hand)

    override fun onUseBlock(run: Run, player: ServerPlayer, hand: InteractionHand, hit: net.minecraft.world.phys.BlockHitResult): InteractionResult? = useReviveStone(run, player, hand)

    private fun useReviveStone(run: Run, player: ServerPlayer, hand: InteractionHand): InteractionResult? {
        val held = player.getItemInHand(hand)
        if (held.isEmpty || com.engineerclient.p3sim.SimItems.idOf(held) != REVIVE_STONE) return null
        val me = run.state.players[player.uuid] ?: return null
        if (me.ghost) return null
        val ghosts = run.state.players.values.filter { it.ghost && it.uuid != player.uuid }
        if (ghosts.isEmpty()) { Sim.chat("§cThere are no players available to revive right now!"); return InteractionResult.FAIL }
        val dead = ghosts.firstOrNull { reviving[it.uuid] == null }
        if (dead == null) { Sim.chat("§cSomeone else is currently reviving that player!"); return InteractionResult.FAIL }
        held.shrink(1)
        // The revive takes about 6 seconds (measured 119 to 121 ticks) and is announced when it starts.
        reviving[dead.uuid] = Reviving(player.uuid, run.t + PlayerLedger.REVIVE_TICKS)
        Sim.everyone { Sim.chat("§a ❣ §r${colourOf(me)}${me.name} §r§7is reviving §r${colourOf(dead)}${dead.name}§r§7!") }
        return InteractionResult.SUCCESS
    }

    // ---- player side ------------------------------------------------------------------------------------------------

    override fun hpPercent(run: Run, player: UUID): Double = run.state.players[player]?.hpPercent ?: 100.0

    override fun meleeHit(run: Run, mob: UUID, player: UUID): HurtResult {
        val rec = Services.mobs.mob(mob) ?: return HurtResult(0.0, died = false, ignored = true)
        val table = run.packs.mobs?.playerDamage
        val floor = run.config.floor
        val pct = table?.meleeByArchetype?.get(floor)?.get(rec.kind)?.let { MeleeSampler.fromArchetype(it, meleeRandom) }
            ?: table?.melee?.get(floor)?.let { MeleeSampler.sample(it, meleeRandom) }
            ?: MeleeSampler.fromArchetype(MeleeSampler.FALLBACK, meleeRandom)
        return hit(run, player, pct, DamageSource.MELEE, mob, false, table?.immunityTicks ?: PlayerLedger.IMMUNITY_TICKS, rec.name)
    }

    override fun hurtPlayer(run: Run, player: UUID, percent: Double, source: DamageSource, attacker: UUID?, trueDamage: Boolean): HurtResult =
        hit(run, player, percent, source, attacker, trueDamage, PlayerLedger.IMMUNITY_TICKS, attacker?.let { Services.mobs.mob(it)?.name })

    private fun hit(run: Run, player: UUID, percent: Double, source: DamageSource, attacker: UUID?, trueDamage: Boolean, immunity: Int, cause: String?): HurtResult {
        val ignored = HurtResult(0.0, died = false, ignored = true)
        if (!run.stage.inDungeon) return ignored
        val rec = run.state.players[player] ?: return ignored
        val sp = human(player)
        // A ghost, a proc's safe window, and the Creeper Veil (named hits only) take the hit before the ledger sees it.
        if (sp != null && !rec.ghost && Sim.asPlayer(sp) { Masks.covered() }) return ignored
        // The veil takes a hit the ledger would have ignored (inside the immunity window) for free, and never a true-damage hit (a trap's 15 %).
        val windowOpen = rec.lastHitT != Int.MIN_VALUE && (run.t.toLong() - rec.lastHitT) < immunity
        if (sp != null && !rec.ghost && !windowOpen && !trueDamage && source != DamageSource.FALL && source != DamageSource.OTHER && source != DamageSource.TRAP && source != DamageSource.CRUSH &&
            Sim.asPlayer(sp) { com.engineerclient.p3sim.SimItems.veilAbsorbs() }) return ignored
        // A Wither Shield soaks the hit first: health stays, the bar drops, the hurt animation still plays.
        var left = percent
        val shield = shields[player]
        if (shield != null && shield.active(run.t) && !windowOpen && !rec.ghost && percent > 0.0) {
            left = shield.absorb(run.t, percent)
            if (left <= 0.0) {
                rec.lastHitT = run.t
                felt(sp)
                run.bus.post(PlayerDamaged(run.t, player, 0.0, rec.hpPercent, source, attacker, trueDamage))
                return HurtResult(0.0, died = false, ignored = false)
            }
        }
        regenCarry.remove(player)
        val h = PlayerLedger.hit(rec, left, run.t, run.config.noDeaths && !rec.isBot, immunity)
        return when (h.outcome) {
            PlayerLedger.Outcome.IGNORED -> ignored
            PlayerLedger.Outcome.HURT, PlayerLedger.Outcome.HELD -> {
                felt(sp)
                run.bus.post(PlayerDamaged(run.t, player, h.applied, rec.hpPercent, source, attacker, trueDamage))
                HurtResult(h.applied, died = false, ignored = false)
            }
            PlayerLedger.Outcome.LETHAL -> lethal(run, rec, sp, source, attacker, trueDamage, cause)
        }
    }

    /** The hit would take the last health: masks first, then death. */
    private fun lethal(run: Run, rec: PlayerRecord, sp: ServerPlayer?, source: DamageSource, attacker: UUID?, trueDamage: Boolean, cause: String?): HurtResult {
        if (sp != null && Sim.asPlayer(sp) { Masks.tryProc(sp) }) {
            PlayerLedger.saved(rec)
            run.bus.post(PlayerDamaged(run.t, rec.uuid, 0.0, rec.hpPercent, source, attacker, trueDamage))
            felt(sp)
            return HurtResult(0.0, died = false, ignored = true)
        }
        val taken = rec.hpPercent
        felt(sp)
        die(run, rec, sp, source, attacker, cause)
        run.bus.post(PlayerDamaged(run.t, rec.uuid, taken, 0.0, source, attacker, trueDamage))
        return HurtResult(taken, died = true, ignored = false)
    }

    private fun die(run: Run, rec: PlayerRecord, sp: ServerPlayer?, source: DamageSource, attacker: UUID?, cause: String?) {
        PlayerLedger.die(rec, run.t)
        run.state.deaths.teamDeaths++
        run.state.deaths.log += run.t to rec.uuid
        if (sp != null) Sim.asPlayer(sp) { Masks.die(sp, cause, PlayerLedger.REVIVE_TICKS, crushed = source == DamageSource.CRUSH) }
        else Sim.everyone { Sim.chat(deathLine(rec.name, source, cause)) }
        regenCarry.remove(rec.uuid)
        run.bus.post(PlayerDied(run.t, rec.uuid, source, attacker))
    }

    /** The line a teammate's death prints to everyone: what the recordings show for a mob, a crusher, a dropped connection and a plain death. */
    internal fun deathLine(name: String, source: DamageSource, cause: String?): String = when {
        source == DamageSource.DISCONNECT -> "§c ☠ §r§7$name disconnected and became a ghost."
        source == DamageSource.CRUSH -> "§c ☠ §r§7$name was crushed and became a ghost."
        cause == null -> "§c ☠ §r§7$name died and became a ghost."
        else -> "§c ☠ §r§7$name was killed by $cause and became a ghost."
    }

    /** What a hit feels like: the hurt animation (red flash, camera tilt) and the hurt sound on the same tick. */
    private fun felt(sp: ServerPlayer?) {
        if (sp == null) return
        EngineerClient.safely("ads damage felt") {
            sp.connection.send(ClientboundHurtAnimationPacket(sp.id, 0f))
            Sim.asPlayer(sp) { Sim.sound(SoundEvents.PLAYER_HURT, 1f, 1f, null, net.minecraft.sounds.SoundSource.PLAYERS) }
        }
    }

    override fun kill(run: Run, player: UUID, source: DamageSource, attacker: UUID?) {
        if (!run.stage.inDungeon) return
        val rec = run.state.players[player] ?: return
        if (rec.ghost) return
        val sp = human(player)
        if (sp != null && Sim.asPlayer(sp) { Masks.covered() }) return
        // "No deaths" holds the ledger at the floor; the kill still counts as a hit for the window.
        if (run.config.noDeaths && !rec.isBot) { rec.hpPercent = minOf(rec.hpPercent, PlayerLedger.NO_DEATH_FLOOR); rec.lastHitT = run.t; return }
        lethal(run, rec, sp, source, attacker, false, attacker?.let { Services.mobs.mob(it)?.name })
    }

    override fun revive(run: Run, player: UUID, by: UUID?) {
        val rec = run.state.players[player] ?: return
        if (!rec.ghost) return
        PlayerLedger.revive(rec)
        val reviver = by?.let { run.state.players[it] } ?: nearestMate(run, rec)
        val sp = human(player)
        if (sp != null) Sim.asPlayer(sp) { Masks.reviveGhost(sp, reviver?.name, reviver?.let { colourOf(it) } ?: "§b") }
        else Sim.everyone { Sim.chat("§a ❣ §r${colourOf(rec)}${rec.name}§r§a was revived by §r${colourOf(reviver ?: rec)}${reviver?.name ?: rec.name}§r§a!") }
        run.bus.post(PlayerRevived(run.t, player, by))
    }

    /** The colour code of a member's name: a bot's rank sets it, everyone else reads as the commonest rank (aqua). */
    private fun colourOf(rec: PlayerRecord): String = (rec.extras["nameColour"] as? String) ?: "§b"

    private fun nearestMate(run: Run, of: PlayerRecord): PlayerRecord? =
        run.state.players.values.filter { it !== of && !it.ghost }.minByOrNull { (it.x - of.x) * (it.x - of.x) + (it.y - of.y) * (it.y - of.y) + (it.z - of.z) * (it.z - of.z) }

    // ---- dev commands -----------------------------------------------------------------------------------------------

    /** What `/ads dev item` calls each secret item that a room's floor or a chest gives. */
    private val ITEM_NAMES: Map<String, com.engineerclient.p3sim.clear.secrets.LootItem> = mapOf(
        "leap" to com.engineerclient.p3sim.clear.secrets.LootItem.SPIRIT_LEAP,
        "decoy" to com.engineerclient.p3sim.clear.secrets.LootItem.DECOY,
        "jerry" to com.engineerclient.p3sim.clear.secrets.LootItem.INFLATABLE_JERRY,
        "trap" to com.engineerclient.p3sim.clear.secrets.LootItem.TRAP,
        "kit" to com.engineerclient.p3sim.clear.secrets.LootItem.DEFUSE_KIT,
        "weights" to com.engineerclient.p3sim.clear.secrets.LootItem.TRAINING_WEIGHTS,
        "potion" to com.engineerclient.p3sim.clear.secrets.LootItem.HEALING_POTION,
    )

    override fun devCommands(): List<AdsCommand> = listOf(
        AdsCommand("hurt", "/ads dev hurt <percent>", "Hurts you by that percent of max health, as a trap would (15 is a trap).",
            server = { c ->
                val run = c.run
                if (run == null) { c.reply("No run is on.") } else {
                    val pct = c.arg(0)?.toDoubleOrNull() ?: 15.0
                    val r = hurtPlayer(run, c.player.uuid, pct, DamageSource.TRAP, null, true)
                    c.reply("hurt ${"%.1f".format(java.util.Locale.ROOT, pct)}%: took ${"%.1f".format(java.util.Locale.ROOT, r.applied)}, ignored=${r.ignored}, died=${r.died}; health ${"%.1f".format(java.util.Locale.ROOT, hpPercent(run, c.player.uuid))}%")
                }
            }),
        AdsCommand("heal", "/ads dev heal", "Sets your health to full and revives you if you are a ghost.",
            server = { c ->
                val run = c.run
                if (run == null) { c.reply("No run is on.") } else {
                    revive(run, c.player.uuid, null)
                    run.state.players[c.player.uuid]?.hpPercent = 100.0
                    c.reply("health 100%")
                }
            }),
        AdsCommand("cast", "/ads dev cast", "Casts an Implosion from where you stand and says what it hit.",
            server = { c ->
                val run = c.run
                if (run == null) { c.reply("No run is on.") } else {
                    val r = castImplosion(run, c.player)
                    c.reply("Implosion hit ${r.count} mobs for ${"%,.0f".format(java.util.Locale.ROOT, r.total)} in all, ${r.killed} died (floor ${run.config.floor}).")
                }
            }),
        AdsCommand("mobhit", "/ads dev mobhit <hp>", "Takes that many HP off the nearest live mob within 30 blocks.",
            server = { c ->
                val run = c.run
                if (run == null) { c.reply("No run is on.") } else {
                    val amount = c.arg(0)?.toDoubleOrNull() ?: 1_000_000.0
                    val p = c.player
                    val m = Services.mobs.all().filter { it.alive }.minByOrNull { (it.x - p.x) * (it.x - p.x) + (it.y - p.y) * (it.y - p.y) + (it.z - p.z) * (it.z - p.z) }
                    if (m == null) c.reply("No live mob.") else {
                        val ok = hurtMob(run, m.id, amount, DamageSource.OTHER, p.uuid)
                        c.reply("${m.name} (${m.kind}): ok=$ok, hp now ${"%,.0f".format(java.util.Locale.ROOT, m.hp)} of ${"%,.0f".format(java.util.Locale.ROOT, m.maxHp)}")
                    }
                }
            }),
        AdsCommand("item", "/ads dev item <leap|decoy|jerry|trap|kit|weights|potion> [count]", "Puts a secret item in your inventory: the Spirit Leap stack (5 by default), or one of the items whose ability is not in ADS yet.",
            server = { c ->
                val name = c.arg(0)?.lowercase()
                val item = name?.let { ITEM_NAMES[it] }
                if (item == null) { c.reply("Say which item: ${ITEM_NAMES.keys.joinToString(", ")}.") } else {
                    val count = c.arg(1)?.toIntOrNull()?.coerceIn(1, 64) ?: if (item == com.engineerclient.p3sim.clear.secrets.LootItem.SPIRIT_LEAP) 5 else 1
                    com.engineerclient.p3sim.clear.secrets.SecretWorld.give(c.player, com.engineerclient.p3sim.clear.secrets.SecretWorld.stack(item, count, c.run?.packs?.rooms?.textures.orEmpty()))
                    c.reply("Gave $count x ${item.displayName}.")
                }
            }),
        AdsCommand("ledger", "/ads dev ledger", "Prints every party member's health percent, ghost state and deaths.",
            server = { c ->
                val run = c.run
                if (run == null) { c.reply("No run is on.") } else {
                    for (r in run.state.players.values) c.reply("${r.name}: ${"%.1f".format(java.util.Locale.ROOT, r.hpPercent)}%${if (r.ghost) " ghost, back at t=${r.reviveAtT}" else ""}, deaths ${r.deaths}, kills ${r.extras[KILLS] ?: 0}")
                    c.reply("Team deaths ${run.state.deaths.teamDeaths}; No deaths ${if (run.config.noDeaths) "on" else "off"}; mobs with damage on the book ${mobLedger.size}.")
                }
            }),
    )
}
