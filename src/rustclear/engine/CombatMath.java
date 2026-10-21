package net.rustclear.mod.engine;

/**
 * Hypixel SkyBlock's combat formulas (community wiki's Damage Calculation and Effective Health
 * pages, Oct 2026, unchanged by the 0.27.2 Minister Update; DPS-Bot's tested versions), free of
 * Minecraft so they can be unit tested.
 *
 * - A hit: (5 + Damage) x (1 + Strength / 100) x (1 + additive / 100) x multipliers, x (1 + Crit
 *   Damage / 100) when it crits. Crit Chance over 100% does nothing without Overload.
 * - An ability: base x (1 + Intelligence / 100 x scaling) x (1 + Ability Damage / 100), the base
 *   raised by the Catacombs stat bonus on dungeon items.
 * - Damage taken: x 100 / (Defense + 100); true damage uses True Defense the same way.
 * - Effective Health: Health x (1 + Defense / 100).
 * - Mana: 100 + Intelligence.
 */
public final class CombatMath {
    private CombatMath() {
    }

    public static double maxMana(StatSheet s) {
        return 100 + s.get(Stat.INTELLIGENCE);
    }

    public static double ehp(StatSheet s) {
        return s.get(Stat.HEALTH) * (1 + s.get(Stat.DEFENSE) / 100);
    }

    /** Against true damage. */
    public static double trueEhp(StatSheet s) {
        return s.get(Stat.HEALTH) * (1 + s.get(Stat.TRUE_DEFENSE) / 100);
    }

    /** The share of a hit that gets through [defense]. */
    public static double taken(double defense) {
        return 100 / (Math.max(0, defense) + 100);
    }

    /** One hit on the player: [raw] less Defense (or True Defense, for true damage). */
    public static double damageTaken(StatSheet s, double raw, boolean trueDamage) {
        return raw * taken(s.get(trueDamage ? Stat.TRUE_DEFENSE : Stat.DEFENSE));
    }

    public enum Kind { MELEE, ARROW }

    /** A hit before crits: weapon damage and Strength, the additive percents for [kind], then multipliers. */
    public static double baseHit(StatSheet s, Kind kind) {
        double additive = s.damageAdditive + (kind == Kind.MELEE ? s.meleeAdditive : s.bowAdditive);
        double hit = (5 + s.weaponDamage) * (1 + s.get(Stat.STRENGTH) / 100) * (1 + additive / 100) * s.damageMultiplier;
        return kind == Kind.ARROW ? hit * s.arrowMultiplier : hit;
    }

    public static double critHit(StatSheet s, Kind kind) {
        return baseHit(s, kind) * (1 + s.get(Stat.CRIT_DAMAGE) / 100);
    }

    /** Crit chance as a 0..1 chance; [critScale] is the weapon's (the Terminator's 0.25). */
    public static double critChance(StatSheet s, double critScale) {
        return Math.max(0, Math.min(1, s.get(Stat.CRIT_CHANCE) * critScale / 100));
    }

    /** The average hit: crits weighted by their chance. */
    public static double expectedHit(StatSheet s, Kind kind, double critScale) {
        double cc = critChance(s, critScale);
        return critHit(s, kind) * cc + baseHit(s, kind) * (1 - cc);
    }

    /** One rolled hit: [roll] in 0..1 under the crit chance crits. */
    public static double rolledHit(StatSheet s, Kind kind, double critScale, double roll) {
        return roll < critChance(s, critScale) ? critHit(s, kind) : baseHit(s, kind);
    }

    /**
     * An ability's hit: [base] (the item's, already with the Catacombs bonus where it gets one)
     * x (1 + Intelligence / 100 x [scaling]) x (1 + Ability Damage / 100) x (1 + magic additive / 100).
     * Only Combat's Warrior counts as magic additive; Strength and crits don't.
     */
    public static double abilityHit(StatSheet s, double base, double scaling) {
        return base * (1 + s.get(Stat.INTELLIGENCE) / 100 * scaling) * (1 + s.get(Stat.ABILITY_DAMAGE) / 100)
            * (1 + s.damageAdditive / 100) * s.damageMultiplier;
    }

    /** Wither Impact's: 10,000 x (1 + Catacombs bonus), scaling 0.3. */
    public static double witherImpact(StatSheet s) {
        return abilityHit(s, 10_000 * (1 + ItemStats.cataBonus(s.cata) / 100.0), 0.3);
    }

    /** A Mage Beam's share of the melee hit: 0.3 + 0.0009 a point of Intelligence (Mage Staff). */
    public static double beamShare(StatSheet s) {
        return 0.3 + 0.0009 * s.get(Stat.INTELLIGENCE);
    }

    /** Extra hits from Ferocity: one per 100, and the rest as a chance ([roll] in 0..1). */
    public static int ferocityHits(StatSheet s, double roll) {
        double f = s.get(Stat.FEROCITY);
        int hits = (int) (f / 100);
        return hits + (roll < (f % 100) / 100 ? 1 : 0);
    }

    /** Ticks between Terminator volleys: its 5 at no Attack Speed, down to half at the 100 cap. */
    public static int shotTicks(StatSheet s, int baseTicks) {
        double as = Math.min(100, s.get(Stat.ATTACK_SPEED));
        return Math.max(1, (int) Math.ceil(baseTicks / (1 + as / 100)));
    }

    /** Healing received: x Vitality / 100. Healing given to others: x Mending / 100. */
    public static double healReceived(StatSheet target, double amount) {
        return amount * target.get(Stat.VITALITY) / 100;
    }

    public static double healGiven(StatSheet healer, double amount) {
        return amount * healer.get(Stat.MENDING) / 100;
    }

    /** Out of combat regen a second: 1.5% of max Health, x Health Regen / 100 and x Vitality / 100. */
    public static double regenPerSecond(StatSheet s) {
        return s.get(Stat.HEALTH) * 0.015 * s.get(Stat.HEALTH_REGEN) / 100 * s.get(Stat.VITALITY) / 100;
    }

    /** Mana regen, once every 20 ticks: +2.2% of max Mana (abilities-items.md, measured on the action bar). */
    public static double manaRegenPerSecond(StatSheet s) {
        return maxMana(s) * 0.022;
    }
}
