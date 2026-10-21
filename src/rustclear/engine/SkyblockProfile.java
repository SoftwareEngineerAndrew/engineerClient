package net.rustclear.mod.engine;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The parts of a SkyBlock profile the Rustclear world can't see on the player: Catacombs and
 * class levels, skills, slayers and Accessory Power. Either a player's real Hypixel profile
 * (read through the lobby, HypixelProfile) or the same numbers for everyone, as the party leader
 * picks. Gson fills it; free of Minecraft, so the stat math can be tested.
 *
 * Skill and slayer rewards are the NotEnoughUpdates repo's constants/bonuses.json (Oct 2026); the
 * Accessory Power formula and powers are DPS-Bot's (github.com/AscentSB/DPS-Bot).
 */
public final class SkyblockProfile {
    public static final int MAX_CATA = ItemStats.MAX_CATA;
    /**
     * What a profile assumes when the leader fixes levels (or nothing could be read): the most
     * Accessory Power a player can have today, every family's top accessory recombobulated
     * (Accessories.maxPower, accessories.md §3; AccessoriesTest pins it).
     */
    public static final int DEFAULT_ACCESSORY_POWER = 2176;
    /** What that build's 8 dungeon accessories add again inside a run (accessories.md §1.2). */
    public static final int DEFAULT_DUNGEON_ACCESSORY_POWER = 150;

    public int cata = MAX_CATA;
    /** Class levels by Hypixel's lower-case class name ("mage", "berserk"...). */
    public Map<String, Integer> classes = new LinkedHashMap<>();
    /** The class picked on Hypixel (Hypixel profiles only; a Rustclear player picks their own). */
    public String selectedClass;
    /** Skill levels by name ("combat", "farming"...). Missing: maxed. */
    public Map<String, Integer> skills = new LinkedHashMap<>();
    /** Slayer levels by boss ("zombie", "spider", "wolf", "enderman"). Missing: maxed. */
    public Map<String, Integer> slayers = new LinkedHashMap<>();
    public int accessoryPower = DEFAULT_ACCESSORY_POWER;
    /** Accessory Power of the dungeon accessories, counted again inside a run (Hypixel shows 1,743 in a run for 1,597 outside). */
    public int dungeonAccessoryPower = DEFAULT_DUNGEON_ACCESSORY_POWER;
    /**
     * Carries the max accessory build (Accessories.addMax): its own stats, enrichments and effects.
     * True for test players and fixed levels; a Hypixel profile's own accessories aren't read yet.
     */
    public boolean maxAccessories = true;
    /** The selected accessory power ("silky", "sighted"...); null: the class's usual one. */
    public String power;
    /**
     * Tuning points by Hypixel's API name ("walk_speed", "critical_damage"...): the profile's applied
     * template; null: Rustclear's split between Strength and Crit Damage.
     */
    public Map<String, Double> tuning;
    /** The SkyBlock level (0: not read), and its XP into the next level (out of 100). */
    public int level;
    public int levelXp;
    /** Total XP by skill, the Catacombs' and each class's (Hypixel profiles; empty or 0: not read). */
    public Map<String, Double> skillXp = new LinkedHashMap<>();
    public double cataXp;
    public Map<String, Double> classXp = new LinkedHashMap<>();
    /** The player's own Farming and Taming level caps (0: not read; the menus' "increase your cap" lines). */
    public int farmingCap;
    public int tamingCap;
    /** Where the numbers came from, for the stats page ("Hypixel: Steve on Apple", "Party leader"). */
    public String source;

    /** Everyone at Catacombs [cata] with every class at [classLevel], and everything else maxed. */
    public static SkyblockProfile fixed(int cata, int classLevel) {
        SkyblockProfile p = new SkyblockProfile();
        p.cata = clamp(cata, 0, MAX_CATA);
        for (DungeonClass c : DungeonClass.values()) {
            p.classes.put(c.name().toLowerCase(Locale.ROOT), clamp(classLevel, 0, DungeonClass.MAX_LEVEL));
        }
        p.source = "Party leader";
        return p;
    }

    public int classLevel(DungeonClass c) {
        Integer v = c == null || classes == null ? null : classes.get(c.name().toLowerCase(Locale.ROOT));
        return v == null ? DungeonClass.MAX_LEVEL : clamp(v, 0, DungeonClass.MAX_LEVEL);
    }

    public int cataLevel() {
        return clamp(cata, 0, MAX_CATA);
    }

    int skill(String name, int max) {
        Integer v = skills == null ? null : skills.get(name);
        return v == null ? max : clamp(v, 0, max);
    }

    int slayer(String name) {
        Integer v = slayers == null ? null : slayers.get(name);
        return v == null ? 9 : clamp(v, 0, 9);
    }

    public SkyblockProfile copy() {
        SkyblockProfile p = new SkyblockProfile();
        p.cata = cata;
        p.classes = classes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(classes);
        p.selectedClass = selectedClass;
        p.skills = skills == null ? new LinkedHashMap<>() : new LinkedHashMap<>(skills);
        p.slayers = slayers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(slayers);
        p.accessoryPower = accessoryPower;
        p.dungeonAccessoryPower = dungeonAccessoryPower;
        p.maxAccessories = maxAccessories;
        p.power = power;
        p.tuning = tuning == null ? null : new LinkedHashMap<>(tuning);
        p.level = level;
        p.levelXp = levelXp;
        p.skillXp = skillXp == null ? new LinkedHashMap<>() : new LinkedHashMap<>(skillXp);
        p.cataXp = cataXp;
        p.classXp = classXp == null ? new LinkedHashMap<>() : new LinkedHashMap<>(classXp);
        p.farmingCap = farmingCap;
        p.tamingCap = tamingCap;
        p.source = source;
        return p;
    }

    // ---------------------------------------------------------------- XP

    /** XP for each skill level (Hypixel's table; Hunting and Taming use it too). */
    static final long[] SKILL_XP = {50, 125, 200, 300, 500, 750, 1000, 1500, 2000, 3500, 5000, 7500, 10000, 15000, 20000,
        30000, 50000, 75000, 100000, 200000, 300000, 400000, 500000, 600000, 700000, 800000, 900000, 1000000, 1100000,
        1200000, 1300000, 1400000, 1500000, 1600000, 1700000, 1800000, 1900000, 2000000, 2100000, 2200000, 2300000,
        2400000, 2500000, 2600000, 2750000, 2900000, 3100000, 3400000, 3700000, 4000000, 4300000, 4600000, 4900000,
        5200000, 5500000, 5800000, 6100000, 6400000, 6700000, 7000000};
    /** XP for each Catacombs and class level to 50; past 50 the Catacombs take 200M a level (menus-skyblock.md §2.4). */
    static final long[] CATA_XP = {50, 75, 110, 160, 230, 330, 470, 670, 950, 1340, 1890, 2665, 3760, 5260, 7380, 10300,
        14400, 20000, 27600, 38000, 52500, 71500, 97000, 132000, 180000, 243000, 328000, 445000, 600000, 800000, 1065000,
        1410000, 1900000, 2500000, 3300000, 4300000, 5600000, 7200000, 9200000, 12000000, 15000000, 19000000, 24000000,
        30000000, 38000000, 48000000, 60000000, 75000000, 93000000, 116250000};
    static final long CATA_XP_PAST_50 = 200_000_000;

    /** [xp] into the level after [level], and what that level needs: {into, needed} (needed 0 past the table). */
    static double[] progress(double xp, long[] table, int level) {
        double left = xp;
        for (int i = 0; i < Math.min(level, table.length); i++) {
            left -= table[i];
        }
        return new double[] {Math.max(0, left), level < table.length ? table[level] : 0};
    }

    /** The Catacombs level [cataXp] is, past 50 too (levels above 50 are cosmetic). */
    static int cataDisplayLevel(double cataXp, int fallback) {
        if (cataXp <= 0) {
            return fallback;
        }
        int level = 0;
        double left = cataXp;
        while (left >= (level < CATA_XP.length ? CATA_XP[level] : CATA_XP_PAST_50) && level < 100) {
            left -= level < CATA_XP.length ? CATA_XP[level] : CATA_XP_PAST_50;
            level++;
        }
        return level;
    }

    /** A skill's level for the menus: the profile's, or [max] when not read. */
    public int skillLevel(String name, int max) {
        return skill(name, max);
    }

    // ---------------------------------------------------------------- skills and slayers

    /** Skill and max level; each reward step is {first level, stat, amount per level}. */
    private record Skill(String name, int max, Object[][] steps) {}

    private static final Skill[] SKILLS = {
        new Skill("farming", 60, new Object[][] {{1, Stat.HEALTH, 2.0}, {15, Stat.HEALTH, 3.0}, {20, Stat.HEALTH, 4.0}, {26, Stat.HEALTH, 5.0}}),
        new Skill("mining", 60, new Object[][] {{1, Stat.DEFENSE, 1.0}, {15, Stat.DEFENSE, 2.0}}),
        new Skill("combat", 60, new Object[][] {{1, Stat.CRIT_CHANCE, 0.5}}),
        new Skill("foraging", 54, new Object[][] {{1, Stat.STRENGTH, 1.0}, {15, Stat.STRENGTH, 2.0}}),
        new Skill("fishing", 50, new Object[][] {{1, Stat.HEALTH, 2.0}, {15, Stat.HEALTH, 3.0}, {20, Stat.HEALTH, 4.0}, {26, Stat.HEALTH, 5.0}}),
        new Skill("enchanting", 60, new Object[][] {{1, Stat.INTELLIGENCE, 1.0}, {15, Stat.INTELLIGENCE, 2.0}, {1, Stat.ABILITY_DAMAGE, 0.5}}),
        new Skill("alchemy", 50, new Object[][] {{1, Stat.INTELLIGENCE, 1.0}, {15, Stat.INTELLIGENCE, 2.0}}),
        new Skill("carpentry", 50, new Object[][] {{1, Stat.HEALTH, 1.0}}),
    };

    /** Slayer rewards by level, each {stat, amount} gained at that level (bonuses.json). */
    private static final Map<String, Object[][]> SLAYERS = Map.of(
        "zombie", new Object[][] {{Stat.HEALTH, 2.0}, {Stat.HEALTH, 2.0}, {Stat.HEALTH, 3.0}, {Stat.HEALTH, 3.0}, {Stat.HEALTH, 4.0},
            {Stat.HEALTH, 4.0}, {Stat.HEALTH, 5.0}, {Stat.HEALTH, 5.0}, {Stat.HEALTH, 6.0}},
        "spider", new Object[][] {{Stat.CRIT_DAMAGE, 1.0}, {Stat.CRIT_DAMAGE, 1.0}, {Stat.CRIT_DAMAGE, 1.0}, {Stat.CRIT_DAMAGE, 1.0},
            {Stat.CRIT_DAMAGE, 2.0}, {Stat.CRIT_DAMAGE, 2.0}, {Stat.CRIT_CHANCE, 1.0}, {Stat.CRIT_DAMAGE, 3.0}, {Stat.CRIT_DAMAGE, 3.0}},
        "wolf", new Object[][] {{Stat.SPEED, 1.0}, {Stat.HEALTH, 2.0}, {Stat.SPEED, 1.0}, {Stat.HEALTH, 2.0}, {Stat.CRIT_DAMAGE, 1.0},
            {Stat.HEALTH, 3.0}, {Stat.CRIT_DAMAGE, 2.0}, {Stat.SPEED, 1.0}, {Stat.HEALTH, 5.0}},
        "enderman", new Object[][] {{Stat.HEALTH, 1.0}, {Stat.INTELLIGENCE, 1.0}, {Stat.HEALTH, 2.0}, {Stat.INTELLIGENCE, 2.0},
            {Stat.HEALTH, 3.0}, {Stat.INTELLIGENCE, 3.0}, {Stat.HEALTH, 4.0}, {Stat.INTELLIGENCE, 4.0}, {Stat.HEALTH, 5.0}});

    /** What the skills give: their stats, Combat's crit chance among them. */
    public Map<Stat, Double> skillStats() {
        Map<Stat, Double> out = new EnumMap<>(Stat.class);
        for (Skill s : SKILLS) {
            int level = skill(s.name, s.max);
            for (int lv = 1; lv <= level; lv++) {
                // The step in force at [lv] for each stat: the last whose first level is at or below it.
                Map<Stat, Double> at = new EnumMap<>(Stat.class);
                for (Object[] step : s.steps) {
                    if ((int) step[0] <= lv) {
                        at.put((Stat) step[1], (Double) step[2]);
                    }
                }
                at.forEach((stat, v) -> out.merge(stat, v, Double::sum));
            }
        }
        return out;
    }

    public Map<Stat, Double> slayerStats() {
        Map<Stat, Double> out = new EnumMap<>(Stat.class);
        SLAYERS.forEach((boss, rewards) -> {
            int level = slayer(boss);
            for (int i = 0; i < level && i < rewards.length; i++) {
                out.merge((Stat) rewards[i][0], (Double) rewards[i][1], Double::sum);
            }
        });
        return out;
    }

    /** Combat's Warrior bonus: +4% damage a level to 50, +1% a level after (210% at 60), additive. */
    public double warriorPercent() {
        int combat = skill("combat", 60);
        return 4.0 * Math.min(combat, 50) + Math.max(0, combat - 50);
    }

    // ---------------------------------------------------------------- Accessory Power

    /** A power's stats per point of the power multiplier (DPS-Bot's table, Accessory Powers on the wiki). */
    private static final Map<String, Map<Stat, Double>> POWERS = new LinkedHashMap<>();

    static {
        // Stone powers' wiki "base" values become stats per point as base / 100 x 24 x the stat's factor.
        stone("silky", Map.of(Stat.CRIT_DAMAGE, 95.0, Stat.SPEED, 5.0));
        stone("sighted", Map.of(Stat.INTELLIGENCE, 100.0));
        stone("bloody", Map.of(Stat.STRENGTH, 45.0, Stat.CRIT_DAMAGE, 45.0, Stat.INTELLIGENCE, 10.0));
        stone("shaded", Map.of(Stat.STRENGTH, 20.0, Stat.CRIT_DAMAGE, 75.0, Stat.SPEED, 5.0));
        stone("forceful", Map.of(Stat.STRENGTH, 75.0, Stat.CRIT_DAMAGE, 20.0));
        stone("hurtful", Map.of(Stat.STRENGTH, 20.0, Stat.CRIT_DAMAGE, 80.0));
        stone("strong", Map.of(Stat.STRENGTH, 50.0, Stat.CRIT_DAMAGE, 50.0));
        // Bizarre: +43.2 Int, -2.4 Strength and -2.4 Crit Damage per point (2289aa n141865, accessories.md §3.2).
        stone("bizarre", Map.of(Stat.INTELLIGENCE, 120.0, Stat.STRENGTH, -10.0, Stat.CRIT_DAMAGE, -10.0));
        // Fortuitous: the Power Stone menu's +172.84 Health, +61.73 Defense, +246.92 Strength, +222.22 Crit Chance,
        // +246.92 Crit Damage (d3bba8) are these bases at a multiplier of 51.44.
        stone("fortuitous", Map.of(Stat.HEALTH, 14.0, Stat.DEFENSE, 5.0, Stat.STRENGTH, 20.0, Stat.CRIT_CHANCE, 45.0, Stat.CRIT_DAMAGE, 20.0));
        stone("mythical", Map.of(Stat.STRENGTH, 17.0, Stat.CRIT_CHANCE, 17.0, Stat.CRIT_DAMAGE, 17.0, Stat.INTELLIGENCE, 17.0, Stat.SPEED, 8.0));
        POWERS.put("warrior", Map.of(Stat.STRENGTH, 8.4, Stat.CRIT_CHANCE, 2.4, Stat.CRIT_DAMAGE, 6.0));
        POWERS.put("commando", Map.of(Stat.STRENGTH, 8.4, Stat.CRIT_CHANCE, 0.475, Stat.CRIT_DAMAGE, 8.4));
        POWERS.put("inspired", Map.of(Stat.STRENGTH, 4.8, Stat.INTELLIGENCE, 16.2, Stat.CRIT_CHANCE, 0.95, Stat.CRIT_DAMAGE, 3.6));
    }

    private static void stone(String name, Map<Stat, Double> base) {
        Map<Stat, Double> stats = new EnumMap<>(Stat.class);
        base.forEach((stat, v) -> stats.put(stat, v / 100 * 24 * switch (stat) {
            case CRIT_CHANCE -> 0.4;
            case INTELLIGENCE -> 1.5;
            case SPEED -> 0.5;
            case ATTACK_SPEED -> 0.3;
            default -> 1.0;
        }));
        POWERS.put(name, stats);
    }

    /** 29.97 x ln(0.0019 x AP + 1) ^ 1.2. */
    public static double powerMultiplier(int accessoryPower) {
        return accessoryPower <= 0 ? 0 : 29.97 * Math.pow(Math.log(0.0019 * accessoryPower + 1), 1.2);
    }

    /** The power in use: the profile's, else the class's usual (Silky for damage, Sighted for Mage, Bloody for the rest). */
    public String powerFor(DungeonClass c) {
        String p = powerId(power);
        return p != null ? p : defaultPower(c);
    }

    /**
     * What one tuning point gives, by API name (stats.md §7: Speed 1.5, Crit Damage 1, Crit Chance
     * 0.2, Attack Speed 0.3, Intelligence 2 from five recorded templates; OPEN: Health 5, Defense 1,
     * Strength 1 are the community's numbers).
     */
    static final Map<String, Map.Entry<Stat, Double>> TUNING = Map.of(
        "health", Map.entry(Stat.HEALTH, 5.0),
        "defense", Map.entry(Stat.DEFENSE, 1.0),
        "walk_speed", Map.entry(Stat.SPEED, 1.5),
        "strength", Map.entry(Stat.STRENGTH, 1.0),
        "critical_damage", Map.entry(Stat.CRIT_DAMAGE, 1.0),
        "critical_chance", Map.entry(Stat.CRIT_CHANCE, 0.2),
        "attack_speed", Map.entry(Stat.ATTACK_SPEED, 0.3),
        "intelligence", Map.entry(Stat.INTELLIGENCE, 2.0));

    /** The selected power's stats for [c]: the power times its multiplier (Silky at 1,743 checked against a recording). */
    public Map<Stat, Double> powerStats(DungeonClass c) {
        return powerStats(c, false);
    }

    public Map<Stat, Double> powerStats(DungeonClass c, boolean inDungeon) {
        return powerStats(powerFor(c), accessoryPower(inDungeon));
    }

    /** [power]'s stats at [accessoryPower] (empty for a power this table doesn't know). */
    public static Map<Stat, Double> powerStats(String power, int accessoryPower) {
        Map<Stat, Double> out = new EnumMap<>(Stat.class);
        double m = powerMultiplier(accessoryPower);
        POWERS.getOrDefault(power, Map.of()).forEach((stat, v) -> out.merge(stat, v * m, Double::sum));
        return out;
    }

    /** Every power by id, in the table's order. */
    public static List<String> powers() {
        return List.copyOf(POWERS.keySet());
    }

    /** [power] as an id the table knows ("Silky" → "silky"), or null. */
    public static String powerId(String power) {
        String p = power == null ? null : power.trim().toLowerCase(Locale.ROOT);
        return p != null && POWERS.containsKey(p) ? p : null;
    }

    /** The class's usual power: Silky for Archer and Berserk, Sighted for Mage, Bloody for the rest. */
    public static String defaultPower(DungeonClass c) {
        return c == DungeonClass.MAGE ? "sighted" : c == DungeonClass.ARCHER || c == DungeonClass.BERSERK ? "silky" : "bloody";
    }

    /** A power's Unique Power Bonus. Silky's +5 Attack Speed and Bizarre's +5 Ability Damage are recorded; OPEN: the others'. */
    public static Map<Stat, Double> uniqueBonus(String power) {
        return switch (power == null ? "" : power) {
            case "silky" -> Map.of(Stat.ATTACK_SPEED, 5.0);
            case "bizarre" -> Map.of(Stat.ABILITY_DAMAGE, 5.0);
            default -> Map.of();
        };
    }

    /** The Accessory Power that counts: inside a run the dungeon accessories count twice. */
    public int accessoryPower(boolean inDungeon) {
        return Math.max(0, accessoryPower) + (inDungeon ? Math.max(0, dungeonAccessoryPower) : 0);
    }

    /** The tuning points' stats: a point per 10 AP, spent as the profile's template says (none without one). */
    public Map<Stat, Double> tuningStats() {
        return tuningStats(false);
    }

    public Map<Stat, Double> tuningStats(boolean inDungeon) {
        Map<Stat, Double> out = new EnumMap<>(Stat.class);
        if (tuning == null) {
            return out;
        }
        double left = accessoryPower(inDungeon) / 10;
        for (Map.Entry<String, Double> e : tuning.entrySet()) {
            Map.Entry<Stat, Double> per = TUNING.get(e.getKey());
            double spent = e.getValue() == null ? 0 : Math.max(0, Math.min(left, e.getValue()));
            if (per != null && spent > 0) {
                out.merge(per.getKey(), spent * per.getValue(), Double::sum);
                left -= spent;
            }
        }
        return out;
    }

    /**
     * Accessory Power's stats: the selected power, its unique bonus, and the tuning points (split
     * between Strength and Crit Damage without a template). The accessories' own stats and effects
     * are Accessories.addMax's (max build only).
     */
    public Map<Stat, Double> accessoryStats(DungeonClass c) {
        return accessoryStats(c, false);
    }

    /** The same inside a run ([inDungeon]): the power and tuning points use the in-run Accessory Power. */
    public Map<Stat, Double> accessoryStats(DungeonClass c, boolean inDungeon) {
        Map<Stat, Double> out = powerStats(c, inDungeon);
        uniqueBonus(powerFor(c)).forEach((stat, v) -> out.merge(stat, v, Double::sum));
        if (tuning != null) {
            tuningStats(inDungeon).forEach((stat, v) -> out.merge(stat, v, Double::sum));
            return out;
        }
        int points = accessoryPower(inDungeon) / 10;
        out.merge(Stat.STRENGTH, (double) (points / 2), Double::sum);
        out.merge(Stat.CRIT_DAMAGE, (double) (points - points / 2), Double::sum);
        return out;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
