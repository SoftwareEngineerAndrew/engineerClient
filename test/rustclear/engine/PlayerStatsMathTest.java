package net.rustclear.mod.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The stat model (StatBuilder, CombatMath, DungeonClass, SkyblockProfile) against Hypixel's
 * numbers: base stats, skills, class passives, and that every stat follows the gear worn and held.
 */
class PlayerStatsMathTest {
    private static ItemDefs.Instance item(String id, int stars) {
        ItemDefs.Def def = ItemDefs.get().def(id);
        return new ItemDefs.Instance(def, stars, 0, 7, 50, List.of());
    }

    /** No skills, slayers or accessories: only what's under test counts. */
    private static SkyblockProfile bare(int cata, int classLevel) {
        SkyblockProfile p = SkyblockProfile.fixed(cata, classLevel);
        for (String skill : List.of("farming", "mining", "combat", "foraging", "fishing", "enchanting", "alchemy", "carpentry")) {
            p.skills.put(skill, 0);
        }
        for (String boss : List.of("zombie", "spider", "wolf", "enderman")) {
            p.slayers.put(boss, 0);
        }
        p.accessoryPower = 0;
        p.dungeonAccessoryPower = 0;
        p.maxAccessories = false;
        return p;
    }

    private static StatSheet build(SkyblockProfile p, DungeonClass c, boolean solo, List<ItemDefs.Instance> armor,
                                   List<ItemDefs.Instance> equipment, ItemDefs.Instance held) {
        return StatBuilder.build(p, c, solo, armor, equipment, held, PetDefs.Bonus.NONE, StatBuilder.Run.NONE, StatBuilder.Buffs.NONE);
    }

    @Test
    void nothingOnIsHypixelsBaseStats() {
        StatSheet s = build(bare(0, 0), null, false, List.of(), List.of(), null);
        assertEquals(100, s.get(Stat.HEALTH));
        assertEquals(0, s.get(Stat.DEFENSE));
        assertEquals(30, s.get(Stat.CRIT_CHANCE));
        assertEquals(50, s.get(Stat.CRIT_DAMAGE));
        assertEquals(100, s.get(Stat.SPEED));
        assertEquals(100, s.get(Stat.VITALITY));
        assertEquals(100, CombatMath.ehp(s));
        assertEquals(100, CombatMath.maxMana(s));
        // A fist: (5 + 0) x 1 x 1.
        assertEquals(5, CombatMath.baseHit(s, CombatMath.Kind.MELEE), 1e-9);
    }

    @Test
    void effectiveHealthIsHealthTimesOnePlusDefenseOverHundred() {
        StatSheet s = new StatSheet();
        s.add(StatSheet.Source.ARMOR, Stat.DEFENSE, 200);
        // The wiki's example: 100 Health and 200 Defense is 300 EHP.
        assertEquals(300, CombatMath.ehp(s), 1e-9);
        assertEquals(100.0 / 300, CombatMath.taken(200), 1e-12);
    }

    @Test
    void everyStatFollowsTheGear() {
        SkyblockProfile p = bare(50, 50);
        StatSheet naked = build(p, DungeonClass.MAGE, false, List.of(), List.of(), null);
        StatSheet armed = build(p, DungeonClass.MAGE, false,
            List.of(item("POWER_WITHER_HELMET", 5), item("POWER_WITHER_CHESTPLATE", 5), item("POWER_WITHER_LEGGINGS", 5), item("POWER_WITHER_BOOTS", 5)),
            List.of(item("STARRED_ADAPTIVE_BELT", 5), item("SOULWEAVER_GLOVES", 0)),
            item("HYPERION", 5));
        // Armour and equipment: Health, Defense, so EHP.
        assertTrue(armed.get(Stat.HEALTH) > naked.get(Stat.HEALTH));
        assertTrue(armed.get(Stat.DEFENSE) > naked.get(Stat.DEFENSE));
        assertTrue(CombatMath.ehp(armed) > CombatMath.ehp(naked) * 5);
        // The weapon: Damage, Strength, Intelligence, so every hit, ability and the Mana pool.
        assertTrue(armed.weaponDamage > 1000);
        assertTrue(armed.get(Stat.STRENGTH) > naked.get(Stat.STRENGTH));
        assertTrue(CombatMath.baseHit(armed, CombatMath.Kind.MELEE) > CombatMath.baseHit(naked, CombatMath.Kind.MELEE) * 100);
        assertTrue(CombatMath.witherImpact(armed) > CombatMath.witherImpact(naked));
        assertTrue(CombatMath.maxMana(armed) > CombatMath.maxMana(naked));
        // Each source shows on its own breakdown line.
        assertTrue(armed.flat(StatSheet.Source.ARMOR, Stat.HEALTH) > 0);
        assertTrue(armed.flat(StatSheet.Source.EQUIPMENT, Stat.STRENGTH) > 0);
        assertTrue(armed.flat(StatSheet.Source.HELD, Stat.INTELLIGENCE) > 0);
    }

    @Test
    void dungeonGearScalesWithStarsAndCatacombs() {
        SkyblockProfile low = bare(0, 50);
        SkyblockProfile high = bare(50, 50);
        double cata0 = build(low, null, false, List.of(item("POWER_WITHER_CHESTPLATE", 0)), List.of(), null).get(Stat.HEALTH);
        double cata50 = build(high, null, false, List.of(item("POWER_WITHER_CHESTPLATE", 5)), List.of(), null).get(Stat.HEALTH);
        double base = ItemDefs.get().def("POWER_WITHER_CHESTPLATE").stats.get("Health");
        // Catacombs 0, no stars: +10% +5%. Catacombs 50, five stars: +485% +50% +5%, and +2 Health a Catacombs level.
        assertEquals(100 + base * 1.15, cata0, 1e-6);
        assertEquals(100 + 2 * 50 + base * 6.40, cata50, 1e-6);
    }

    @Test
    void aHitIsWeaponDamageTimesStrengthAndCrits() {
        StatSheet s = new StatSheet();
        s.weaponDamage = 95;
        s.add(StatSheet.Source.HELD, Stat.STRENGTH, 100);
        s.add(StatSheet.Source.HELD, Stat.CRIT_DAMAGE, 50);
        // (5 + 95) x (1 + 100/100) = 200; a crit x (1 + 100/100) = 400.
        assertEquals(200, CombatMath.baseHit(s, CombatMath.Kind.MELEE), 1e-9);
        assertEquals(400, CombatMath.critHit(s, CombatMath.Kind.MELEE), 1e-9);
        // 30% crit chance: 0.3 x 400 + 0.7 x 200.
        assertEquals(260, CombatMath.expectedHit(s, CombatMath.Kind.MELEE, 1), 1e-9);
        s.damageAdditive = 50;
        assertEquals(300, CombatMath.baseHit(s, CombatMath.Kind.MELEE), 1e-9);
    }

    @Test
    void witherImpactIsTenThousandWithTheCataBonusAndIntelligence() {
        StatSheet s = new StatSheet();
        s.cata = 50;
        s.add(StatSheet.Source.HELD, Stat.INTELLIGENCE, 1000);
        // 10,000 x 5.85 x (1 + 10 x 0.3).
        assertEquals(10_000 * 5.85 * 4, CombatMath.witherImpact(s), 1e-6);
    }

    @Test
    void skillsGiveHypixelsRewards() {
        SkyblockProfile p = SkyblockProfile.fixed(50, 50);
        Map<Stat, Double> skills = p.skillStats();
        // Combat 60: +0.5 crit chance a level.
        assertEquals(30, skills.get(Stat.CRIT_CHANCE), 1e-9);
        // Farming 60 and Fishing 50: 2 x 14 + 3 x 5 + 4 x 6 + 5 x (35 or 25), and Carpentry 50 x 1.
        assertEquals((28 + 15 + 24 + 175) + (28 + 15 + 24 + 125) + 50, skills.get(Stat.HEALTH), 1e-9);
        // Mining 60: 14 + 2 x 46.
        assertEquals(14 + 92, skills.get(Stat.DEFENSE), 1e-9);
        // Combat 60's Warrior: 4 x 50 + 10.
        assertEquals(210, p.warriorPercent(), 1e-9);
    }

    @Test
    void classPassives() {
        // Hypixel's Dungeoneering menu and run-start lines (stats.md §6, abilities-items.md §1): solo adds the flat base again.
        // Mage 48: Intelligence 490 -> 740, Cooldown Reduction 49% -> 74%, Ability Damage 14 -> 19.
        assertEquals(490, DungeonClass.MAGE.bonus(48, false).stat(Stat.INTELLIGENCE), 1e-9);
        assertEquals(740, DungeonClass.MAGE.bonus(48, true).stat(Stat.INTELLIGENCE), 1e-9);
        assertEquals(0.49, DungeonClass.MAGE.bonus(48, false).cooldownReduction(), 1e-9);
        assertEquals(0.74, DungeonClass.MAGE.bonus(48, true).cooldownReduction(), 1e-9);
        assertEquals(14, DungeonClass.MAGE.bonus(48, false).stat(Stat.ABILITY_DAMAGE), 1e-9);
        assertEquals(19, DungeonClass.MAGE.bonus(48, true).stat(Stat.ABILITY_DAMAGE), 1e-9);
        // Berserk 48: Melee Damage 76% -> 116%, Walk Speed 49.2 -> 79.2, Bloodlust Damage 56% -> 76%, Lust per hit 159% -> 174%.
        DungeonClass.Bonus berserk = DungeonClass.BERSERK.bonus(48, false);
        DungeonClass.Bonus soloBerserk = DungeonClass.BERSERK.bonus(48, true);
        assertEquals(76, berserk.meleeAdditive(), 1e-9);
        assertEquals(116, soloBerserk.meleeAdditive(), 1e-9);
        assertEquals(49.2, berserk.stat(Stat.SPEED), 1e-9);
        assertEquals(79.2, soloBerserk.stat(Stat.SPEED), 1e-9);
        assertEquals(0.56, berserk.bloodlust(), 1e-9);
        assertEquals(1.59, berserk.lustPerHit(), 1e-9);
        assertEquals(8.8, berserk.lustCap(), 1e-9);
        // Archer 47: Arrow Damage 225.2% -> 275.2%, Extra Arrow Chance 97% -> 147%, Melee Damage -25%.
        assertEquals(225.2, DungeonClass.ARCHER.bonus(47, false).arrowAdditive(), 1e-9);
        assertEquals(275.2, DungeonClass.ARCHER.bonus(47, true).arrowAdditive(), 1e-9);
        assertEquals(0.97, DungeonClass.ARCHER.bonus(47, false).extraArrowChance(), 1e-9);
        assertEquals(1.47, DungeonClass.ARCHER.bonus(47, true).extraArrowChance(), 1e-9);
        assertEquals(-25, DungeonClass.ARCHER.bonus(47, false).meleeAdditive(), 1e-9);
        // Tank 50: Defense 101 -> 151, Vitality 15.1 -> 25.1, Total Defense 35% -> 60%.
        DungeonClass.Bonus tank = DungeonClass.TANK.bonus(50, false);
        DungeonClass.Bonus soloTank = DungeonClass.TANK.bonus(50, true);
        assertEquals(101, tank.stat(Stat.DEFENSE), 1e-9);
        assertEquals(151, soloTank.stat(Stat.DEFENSE), 1e-9);
        assertEquals(15.1, tank.stat(Stat.VITALITY), 1e-9);
        assertEquals(25.1, soloTank.stat(Stat.VITALITY), 1e-9);
        assertEquals(0.35, tank.defensePercent(), 1e-9);
        assertEquals(0.60, soloTank.defensePercent(), 1e-9);
        // Healer 49: Vitality +19.8, Mending +34.5.
        DungeonClass.Bonus healer = DungeonClass.HEALER.bonus(49, false);
        assertEquals(19.8, healer.stat(Stat.VITALITY), 1e-9);
        assertEquals(34.5, healer.stat(Stat.MENDING), 1e-9);
        assertEquals(0.99, healer.healingBonus(), 1e-9);
    }

    @Test
    void speedCapRisesWithCapLines() {
        // Young Dragon's +100 Walk Speed Cap is a full set bonus: only with all four pieces.
        List<ItemDefs.Instance> three = List.of(item("YOUNG_DRAGON_HELMET", 0), item("YOUNG_DRAGON_CHESTPLATE", 0),
            item("YOUNG_DRAGON_LEGGINGS", 0));
        List<ItemDefs.Instance> four = new java.util.ArrayList<>(three);
        four.add(item("YOUNG_DRAGON_BOOTS", 0));
        assertEquals(0, StatBuilder.speedCap(three, List.of(), null), 1e-9);
        assertEquals(100, StatBuilder.speedCap(four, List.of(), null), 1e-9);
        assertEquals(450, new StatSheet().speedCap, 1e-9);
    }

    @Test
    void protectiveBarrierMultipliesAllDefense() {
        SkyblockProfile p = bare(50, 50);
        StatSheet tank = build(p, DungeonClass.TANK, false, List.of(item("POWER_WITHER_CHESTPLATE", 5)), List.of(), null);
        double flat = tank.flat(Stat.DEFENSE);
        assertEquals(flat * 1.35, tank.get(Stat.DEFENSE), 1e-6);
    }

    @Test
    void speedStopsAtTheCap() {
        StatSheet s = new StatSheet();
        s.add(StatSheet.Source.BUFFS, Stat.SPEED, 800);
        assertEquals(StatSheet.SPEED_CAP, s.get(Stat.SPEED));
        assertEquals(900, s.uncappedSpeed(), 1e-9);
    }

    @Test
    void accessoryPowerMultiplier() {
        // 29.97 x ln(0.0019 x 1400 + 1)^1.2
        assertEquals(29.97 * Math.pow(Math.log(0.0019 * 1400 + 1), 1.2), SkyblockProfile.powerMultiplier(1400), 1e-9);
        assertEquals(0, SkyblockProfile.powerMultiplier(0));
    }

    @Test
    void attackSpeedShortensShots() {
        StatSheet s = new StatSheet();
        assertEquals(10, CombatMath.shotTicks(s, 10));
        s.add(StatSheet.Source.HELD, Stat.ATTACK_SPEED, 100);
        assertEquals(5, CombatMath.shotTicks(s, 10));
        s.add(StatSheet.Source.HELD, Stat.ATTACK_SPEED, 100);
        assertEquals(5, CombatMath.shotTicks(s, 10));
    }
}
