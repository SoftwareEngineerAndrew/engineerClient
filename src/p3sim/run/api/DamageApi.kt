package com.engineerclient.p3sim.run.api

import com.engineerclient.p3sim.run.DamageSource
import com.engineerclient.p3sim.run.Run
import java.util.UUID

/** What happened to a hit on a party member. */
data class HurtResult(
    /** Percent of max HP actually taken (0 when ignored or absorbed). */
    val applied: Double,
    val died: Boolean,
    /** The hit fell inside the 10-tick immunity window (or the player is a ghost, or the Spirit/Bonzo/Phoenix mask saved them). */
    val ignored: Boolean,
)

/**
 * The damage hub and the player's percent-of-max-HP ledger. Implemented by [com.engineerclient.p3sim.clear.damage.DamageModule].
 * Everything that hurts or kills goes through here so the mob HP ledger, the player ledger, deaths, ghosts and the score
 * stay consistent. In the BOSS stage the boss sim's existing behaviour is unchanged; the hub only observes.
 * Server thread only.
 */
interface DamageService {
    /**
     * Damages a mob by [amount] HP (SkyBlock scale). Arrows, beams and melee use this. Applies through
     * [MobService.setHp]. Returns false if the mob is unknown or already dead.
     */
    fun hurtMob(run: Run, mob: UUID, amount: Double, source: DamageSource, by: UUID?): Boolean = false

    /**
     * An Implosion: hits every mob (alive, inside the box `[x0,y0,z0,x1,y1,z1]`) for the floor's profile damage (F7: one
     * cast kills anything in the box; M7: 1-13 casts). [damage] overrides the profile. Returns how many mobs were hit.
     */
    fun implode(run: Run, caster: UUID?, box: DoubleArray, damage: Double? = null): Int = 0

    /** A mob's melee hit on a party member: the size is rolled from the pack's melee table (per archetype if present). */
    fun meleeHit(run: Run, mob: UUID, player: UUID): HurtResult = HurtResult(0.0, died = false, ignored = true)

    /**
     * Hurts a party member by [percent] of max HP (a trap: exactly 15.0, [trueDamage] true). Applies the immunity window,
     * the "No deaths" clamp, masks, then death/ghost. Posts `PlayerDamaged`, `PlayerDied`.
     */
    fun hurtPlayer(run: Run, player: UUID, percent: Double, source: DamageSource, attacker: UUID?, trueDamage: Boolean = false): HurtResult =
        HurtResult(0.0, died = false, ignored = true)

    /** The player's HP as percent of max (0..100); 100 when unknown. */
    fun hpPercent(run: Run, player: UUID): Double = 100.0

    /** Kills a party member now (a ghost for ~6 seconds, `Team Deaths` +1). */
    fun kill(run: Run, player: UUID, source: DamageSource, attacker: UUID? = null) {}

    /** Revives a ghost ([by] null: Revive Stone or the timer). */
    fun revive(run: Run, player: UUID, by: UUID? = null) {}
}
