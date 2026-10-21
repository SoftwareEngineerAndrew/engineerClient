package net.rustclear.mod.engine;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * The five dungeon classes and what their passives give at a class level (0..50), from the
 * community wiki's class pages (Oct 2026, after the Jul 22 0.26.1 class changes) and DPS-Bot's
 * tested model (github.com/AscentSB/DPS-Bot), checked against Hypixel's Dungeoneering menu and the
 * run-start doubling lines (stats.md §6). A class nobody else in the party plays is "solo", and its
 * passives are "doubled" the way Hypixel announces it ("Your Mage stats are doubled because you are
 * the only player using this class!"): each passive's flat base is added once more (Mage +250
 * Intelligence, +5 Ability Damage and +25% cooldown reduction; Berserk +40% melee and +30 Speed;
 * Archer +50% arrow damage and extra arrow chance; Tank +50 Defense, +10 Vitality and +25%
 * Protective Barrier), not the per-level part. Free of Minecraft, so it can be unit tested.
 */
public enum DungeonClass {
    HEALER("Healer", "§a", 0x55FF55, "Healing Circle", new String[] {
        "Place a circle at your feet that heals",
        "everyone standing in it every second."}, "Wish", new String[] {
        "Heal your whole party to full Health",
        "and give them an Absorption shield."}),
    MAGE("Mage", "§b", 0x55FFFF, "Guided Sheep", new String[] {
        "Launch a sheep that flies where you look",
        "and explodes, damaging nearby mobs."}, "Thunderstorm", new String[] {
        "Strike every mob within 10 blocks with",
        "lightning once a second for 15s."}),
    BERSERK("Berserk", "§c", 0xFF5555, "Throwing Axe", new String[] {
        "Throw an axe at the first mob ahead for",
        "your highest hit of the last minute."}, "Ragnarok", new String[] {
        "Summon 3 Zombie minions to fight for you",
        "and gain +100 Attack Speed, +400 Speed",
        "and 50% more damage for 15s."}),
    ARCHER("Archer", "§6", 0xFFAA00, "Explosive Shot", new String[] {
        "Fire three arrows that explode where",
        "they land."}, "Rapid Fire", new String[] {
        "Fire a stream of arrows where you look",
        "for 3s, +1s every 10 levels."}),
    TANK("Tank", "§7", 0xAAAAAA, "Seismic Wave", new String[] {
        "Send a shockwave through the ground,",
        "damaging every mob in front of you."}, "Castle of Stone", new String[] {
        "Take 70% less damage for 20s."});

    public static final int MAX_LEVEL = 50;

    public final String title;
    public final String color;
    /** The class's color as RGB, for screens and the Dungeon Orb's tint. */
    public final int rgb;
    /** Ctrl+Q (drop the stack) and Q (drop one): the class's ability and ultimate, with what each does. */
    public final String ability;
    public final String[] abilityText;
    public final String ultimate;
    public final String[] ultimateText;

    DungeonClass(String title, String color, int rgb, String ability, String[] abilityText, String ultimate, String[] ultimateText) {
        this.title = title;
        this.color = color;
        this.rgb = rgb;
        this.ability = ability;
        this.abilityText = abilityText;
        this.ultimate = ultimate;
        this.ultimateText = ultimateText;
    }

    /** "MAGE", "mage", "Mage" or Hypixel's "berserker"; null for anything else. */
    public static DungeonClass of(String name) {
        if (name == null) {
            return null;
        }
        String n = name.trim().toUpperCase(Locale.ROOT);
        if (n.equals("BERSERKER")) {
            return BERSERK;
        }
        for (DungeonClass c : values()) {
            if (c.name().equals(n)) {
                return c;
            }
        }
        return null;
    }

    /** Everything one player's class gives them, at [level] and [solo] or not. */
    public record Bonus(Map<Stat, Double> stats, double defensePercent, double meleeAdditive, double arrowMultiplier,
                        double cooldownReduction, double extraArrowChance, double bloodlust, double lustPerHit,
                        double lustCap, double healingBonus, double auraPercent, double shieldPercent,
                        double shieldBelow, int shieldCooldownTicks, double arrowAdditive) {
        public double stat(Stat stat) {
            return stats.getOrDefault(stat, 0.0);
        }
    }

    /** No class (a player who hasn't picked one): nothing. */
    public static Bonus none() {
        return new Bonus(Map.of(), 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public Bonus bonus(int classLevel, boolean solo) {
        int l = Math.max(0, Math.min(MAX_LEVEL, classLevel));
        boolean max = l >= MAX_LEVEL;
        int fives = l / 5;
        // Solo adds each passive's flat base once more (Mage 48: Int 490 -> 740, AD 14 -> 19, CDR 49 -> 74%).
        double again = solo ? 1 : 0;
        Map<Stat, Double> stats = new EnumMap<>(Stat.class);
        return switch (this) {
            case MAGE -> {
                // Intelligence: 250 + 5 a level (490 at 48, measured).
                stats.put(Stat.INTELLIGENCE, 250 + 5.0 * l + 250 * again);
                // +5 Ability Damage and +1 every 5 levels (14 at 48, measured).
                stats.put(Stat.ABILITY_DAMAGE, 5 + fives + 5 * again);
                // Efficient Spells: 25% shorter cooldowns, +1% every other level (49% at 48, measured).
                double cdr = 25 + l / 2 + 25 * again;
                yield new Bonus(stats, 0, 0, 1, cdr / 100.0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
            }
            case BERSERK -> {
                // Walk Speed: +30 and +0.4 a level (49.2 at 48, measured).
                stats.put(Stat.SPEED, 30 + 0.4 * l + 30 * again);
                // Melee damage: +40% and +0.75% a level (76% at 48, measured).
                double melee = 40 + 0.75 * l + 40 * again;
                // Bloodlust: the hit after a kill deals +20% and +0.75% a level more (56% at 48, measured).
                double bloodlust = 20 + 0.75 * l + 20 * again;
                // Lust for Blood: each hit on the same mob deals +15% and +3% a level more than the last
                // (159% at 48, measured), up to a cap of 880% at 48 (measured; OPEN: the per-level step).
                double perHit = 15 + 3.0 * l + 15 * again;
                double cap = 250 + 630.0 / 48 * l + 250 * again;
                yield new Bonus(stats, 0, melee, 1, 0, 0, bloodlust / 100.0, perHit / 100.0, cap / 100.0, 0, 0, 0, 0, 0, 0);
            }
            case ARCHER -> {
                // Arrow Damage: an additive +225.2% at 47 (measured; 275.2% solo, so the flat base is 50).
                // OPEN: the per-level step is from one level only (175.2 / 47 a level).
                double arrows = 50 + 175.2 / 47 * l + 50 * again;
                // Doubleshot: 50% for a second arrow, +1% a level (97% at 47, measured); 147% solo.
                double extra = 50 + l + 50 * again;
                yield new Bonus(stats, 0, -25, 1, 0, extra / 100.0, 0, 0, 0, 0, 0, 0, 0, 0, arrows);
            }
            case TANK -> {
                stats.put(Stat.HEALTH, 100.0);
                // +50 Defense, +1 a level and +2 at 50 (101 at 50, measured); solo +50 (151).
                stats.put(Stat.DEFENSE, 50.0 + l + (max ? 1 : 0) + 50 * again);
                // +10 Vitality, +0.1 a level and +0.2 at 50 (15.1 at 50, measured); solo +10 (25.1).
                stats.put(Stat.VITALITY, 10 + 0.1 * l + (max ? 0.1 : 0) + 10 * again);
                // Protective Barrier: +25% Defense, +0.2% a level (35% at 50); solo 60%, measured.
                double barrier = 25 + 0.2 * l + 25 * again;
                // Its Absorption shield: 10% of max Health below 50% Health, every 90 s; solo 20% below 100%, every 45 s.
                yield new Bonus(stats, barrier / 100.0, 0, 1, 0, 0, 0, 0, 0, 0, 0,
                    solo ? 0.20 : 0.10, solo ? 1.0 : 0.5, solo ? 900 : 1800, 0);
            }
            case HEALER -> {
                // +10 Vitality, +0.2 a level and +0.2 at 50 (19.8 at 49, measured); +10 Mending and +0.5 a level
                // (34.5 at 49, measured) and +0.5 at 50. OPEN: solo assumed to add the +10 bases again, like the others.
                stats.put(Stat.VITALITY, 10 + 0.2 * l + (max ? 0.2 : 0) + 10 * again);
                stats.put(Stat.MENDING, 10 + 0.5 * l + (max ? 0.5 : 0) + 10 * again);
                // Renew: healing abilities heal +50% and +1% a level more (100% at 50).
                double renew = 50 + l;
                // Healing Aura: teammates within 8 blocks heal 1% of max Health a second, +0.1% every 5 levels (2% at 50).
                double aura = 1 + 0.1 * fives;
                yield new Bonus(stats, 0, 0, 1, 0, 0, 0, 0, 0, renew / 100.0, aura / 100.0, 0, 0, 0, 0);
            }
        };
    }

    /** One "[Class] <stat> a -> b" line of the run-start doubling block: [label], normal and solo values, [unit]. */
    public record Doubled(String label, double normal, double solo, String unit) {}

    /**
     * The lines Hypixel prints under "Your <Class> stats are doubled..." at [level], in its order
     * (abilities-items.md §1, measured at Mage 48, Archer 47, Berserk 48, Tank 50; the per-level
     * steps of the Berserk and Tank extras are fitted to that one level: OPEN). Healer was never
     * recorded: none.
     */
    public java.util.List<Doubled> doubled(int level) {
        int l = Math.max(0, Math.min(MAX_LEVEL, level));
        Bonus one = bonus(l, false);
        Bonus two = bonus(l, true);
        return switch (this) {
            case MAGE -> java.util.List.of(
                new Doubled("Intelligence", one.stat(Stat.INTELLIGENCE), two.stat(Stat.INTELLIGENCE), ""),
                new Doubled("Cooldown Reduction", one.cooldownReduction() * 100, two.cooldownReduction() * 100, "%"),
                new Doubled("Ability Damage", one.stat(Stat.ABILITY_DAMAGE), two.stat(Stat.ABILITY_DAMAGE), ""));
            case ARCHER -> java.util.List.of(
                new Doubled("Arrow Damage", one.arrowAdditive(), two.arrowAdditive(), "%"),
                new Doubled("Extra Arrow Chance", one.extraArrowChance() * 100, two.extraArrowChance() * 100, "%"));
            case BERSERK -> java.util.List.of(
                new Doubled("Melee Damage", one.meleeAdditive(), two.meleeAdditive(), "%"),
                new Doubled("Walk Speed", one.stat(Stat.SPEED), two.stat(Stat.SPEED), ""),
                new Doubled("Weapon Master Swing Range Increase", 0.5 + 4.4 / 48 * l, 1 + 4.4 / 48 * l, ""),
                new Doubled("Lust For Blood Damage Increase Per Hit", one.lustPerHit() * 100, two.lustPerHit() * 100, "%"),
                new Doubled("Lust For Blood Damage Increase Cap", one.lustCap() * 100, two.lustCap() * 100, "%"),
                new Doubled("Indomitable Strength to Defense", 5 + 0.1 * l, 10 + 0.1 * l, "%"),
                new Doubled("Bloodlust Heal Percent", 3, 6, "%"),
                new Doubled("Bloodlust Duration", 5, 10, ""),
                new Doubled("Bloodlust Damage", one.bloodlust() * 100, two.bloodlust() * 100, "%"));
            case TANK -> java.util.List.of(
                new Doubled("Defense", one.stat(Stat.DEFENSE), two.stat(Stat.DEFENSE), ""),
                new Doubled("Total Defense", one.defensePercent() * 100, two.defensePercent() * 100, "%"),
                new Doubled("Vitality", one.stat(Stat.VITALITY), two.stat(Stat.VITALITY), ""),
                new Doubled("Absorption Health", 200 + 10.0 * l, 400 + 10.0 * l, ""),
                new Doubled("Absorption Shield Percentage", one.shieldPercent() * 100, two.shieldPercent() * 100, "%"),
                new Doubled("Absorption Shield Health Required", one.shieldBelow() * 100, two.shieldBelow() * 100, "%"),
                new Doubled("Barrier Cooldown", one.shieldCooldownTicks() / 20.0, two.shieldCooldownTicks() / 20.0, ""));
            case HEALER -> java.util.List.of();
        };
    }

    /** The passives' lore lines for the stats page, for [level] and [solo]. */
    public String[] describe(int level, boolean solo) {
        Bonus b = bonus(level, solo);
        return switch (this) {
            case MAGE -> new String[] {
                "§7Intelligence: §b+" + Stat.number(b.stat(Stat.INTELLIGENCE)),
                "§7Ability Damage: §c+" + Stat.number(b.stat(Stat.ABILITY_DAMAGE)) + "%",
                "§7Cooldown Reduction: §a" + Stat.number(b.cooldownReduction() * 100) + "%",
                "§7Mage Staff: §fleft click a sword to beam"};
            case BERSERK -> new String[] {
                "§7Melee Damage: §c+" + Stat.number(b.meleeAdditive()) + "%",
                "§7Bloodlust: §c+" + Stat.number(b.bloodlust() * 100) + "% §7after a kill, §a3% §7heal a hit",
                "§7Lust for Blood: §c+" + Stat.number(b.lustPerHit() * 100) + "% §7a hit, up to §c" + Stat.number(b.lustCap() * 100) + "%",
                "§7Speed: §f+" + Stat.number(b.stat(Stat.SPEED))};
            case ARCHER -> new String[] {
                "§7Arrow Damage: §c+" + Stat.number(b.arrowAdditive()) + "%",
                "§7Melee Damage: §c" + Stat.number(b.meleeAdditive()) + "%",
                "§7Doubleshot: §a" + Stat.number(b.extraArrowChance() * 100) + "% §7extra arrow chance"};
            case TANK -> new String[] {
                "§7Health: §c+" + Stat.number(b.stat(Stat.HEALTH)) + " §7Defense: §a+" + Stat.number(b.stat(Stat.DEFENSE)),
                "§7Protective Barrier: §a+" + Stat.number(b.defensePercent() * 100) + "% Defense",
                "§7Absorption: §e" + Stat.number(b.shieldPercent() * 100) + "% §7below §c" + Stat.number(b.shieldBelow() * 100) + "% §7Health",
                "§7Diversion: §a80% §7of nearby teammates' damage"};
            case HEALER -> new String[] {
                "§7Renew: §a+" + Stat.number(b.healingBonus() * 100) + "% §7healing",
                "§7Healing Aura: §a" + Stat.number(b.auraPercent() * 100) + "% §7a second within 8 blocks",
                "§7Vitality: §4+" + Stat.number(b.stat(Stat.VITALITY)) + " §7Mending: §a+" + Stat.number(b.stat(Stat.MENDING))};
        };
    }
}
