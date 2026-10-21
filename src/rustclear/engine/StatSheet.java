package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * One player's stats, built up from where each part comes from, Hypixel's way: every flat
 * source adds up, then additive percents (Protective Barrier's +35% Defense) apply together, then
 * each multiplier (a blessing's x1.02) on its own. A stat = flat x (1 + additive) x multipliers.
 * Speed stops at its cap. Also holds the damage modifiers that aren't stats (Combat's
 * Warrior bonus, Berserk melee damage, the Archer's arrow multiplier). Free of Minecraft.
 */
public final class StatSheet {
    /**
     * 450: Speed stops here with no item or pet that raises the cap (stats.md §5: exactly 450 with a
     * Spider, Golden Dragon or Spirit pet in 110 menus). OPEN: whether that is 400 + 50 from the profile.
     */
    public static final double SPEED_CAP = 450;

    public enum Source {
        BASE("Base stats"),
        SKILLS("Skills"),
        SLAYERS("Slayers"),
        ACCESSORIES("Accessory Power"),
        ACCESSORY_BAG("Accessories"),
        CLASS("Class"),
        ARMOR("Armor"),
        EQUIPMENT("Equipment"),
        HELD("Held item"),
        PET("Pet"),
        BLESSINGS("Blessings"),
        POTION("Dungeon Potion"),
        BUFFS("Buffs");

        public final String label;

        Source(String label) {
            this.label = label;
        }
    }

    /** A x[factor] on [stat] from [label] (a blessing, Ragnarok...). */
    public record Multiplier(String label, Stat stat, double factor) {}

    /** +[value] [stat] from [label] (an item's name, "Combat Skill"...), of kind [source]. */
    public record Part(Source source, String label, Stat stat, double value) {}

    /** +[fraction] (0.35: 35%) [stat] from [label], added up with the other percents. */
    public record Percent(String label, Stat stat, double fraction) {}

    private final Map<Source, EnumMap<Stat, Double>> flat = new EnumMap<>(Source.class);
    private final List<Part> parts = new ArrayList<>();
    private final EnumMap<Stat, Double> additive = new EnumMap<>(Stat.class);
    private final List<Percent> percents = new ArrayList<>();
    private final List<Multiplier> multipliers = new ArrayList<>();

    /** The held weapon's Damage (Hypixel's "+X Damage"), before the 5 every hit has. */
    public double weaponDamage;
    /** Additive damage percents: Combat's Warrior, everything (melee, arrows, abilities' magic part). */
    public double damageAdditive;
    /** Berserk melee damage, melee hits only. */
    public double meleeAdditive;
    /** Arrows only: the Dungeon potion's Archery III. */
    public double bowAdditive;
    /** Arrows only: the Archer class's multiplier. */
    public double arrowMultiplier = 1;
    /** Every hit: Ragnarok's x1.5... */
    public double damageMultiplier = 1;
    /**
     * Damage taken from attackers of a Hypixel mob type ("Wither", "Undead"...): the accessories'
     * reductions, multiplied together when an attacker has several types (OPEN: whether they stack).
     */
    public final java.util.Map<String, Double> takenByType = new java.util.HashMap<>();
    /** Damage taken by a hit of half the player's max Health or more (Burststopper Artifact). */
    public double bigHitTaken = 1;

    /** What [takenByType] leaves of a hit by [attacker] (a mob's or boss's name). */
    public double takenFrom(String attacker) {
        double m = 1;
        for (String type : Accessories.mobTypes(attacker)) {
            m *= takenByType.getOrDefault(type, 1.0);
        }
        return m;
    }
    /** The class and its passives, for the abilities and the stats page. */
    public DungeonClass dungeonClass;
    public DungeonClass.Bonus classBonus = DungeonClass.none();
    public int classLevel;
    public boolean soloClass;
    public int cata;
    /** Speed's cap: [SPEED_CAP] plus the "+N Speed Cap" of worn items and the pet (700 seen). */
    public double speedCap = SPEED_CAP;

    public StatSheet() {
        for (Stat s : Stat.values()) {
            if (s.base != 0) {
                add(Source.BASE, s, s.base);
            }
        }
    }

    public void add(Source source, Stat stat, double value) {
        add(source, source.label, stat, value);
    }

    /** +[value] [stat] from [label] (shown in the stats breakdown), of kind [source]. */
    public void add(Source source, String label, Stat stat, double value) {
        if (value != 0) {
            flat.computeIfAbsent(source, k -> new EnumMap<>(Stat.class)).merge(stat, value, Double::sum);
            parts.add(new Part(source, label, stat, value));
        }
    }

    public void addAll(Source source, Map<Stat, Double> values) {
        values.forEach((stat, v) -> add(source, stat, v));
    }

    public void addAll(Source source, String label, Map<Stat, Double> values) {
        values.forEach((stat, v) -> add(source, label, stat, v));
    }

    /** +[fraction] (0.35 for 35%) to [stat] from [label], with the other additive percents. */
    public void addPercent(String label, Stat stat, double fraction) {
        if (fraction != 0) {
            additive.merge(stat, fraction, Double::sum);
            percents.add(new Percent(label, stat, fraction));
        }
    }

    /** Each flat source of [stat], biggest first. */
    public List<Part> parts(Stat stat) {
        return parts.stream().filter(p -> p.stat() == stat)
            .sorted((a, b) -> Double.compare(Math.abs(b.value()), Math.abs(a.value()))).toList();
    }

    /** Each additive percent on [stat], biggest first. */
    public List<Percent> percents(Stat stat) {
        return percents.stream().filter(p -> p.stat() == stat)
            .sorted((a, b) -> Double.compare(Math.abs(b.fraction()), Math.abs(a.fraction()))).toList();
    }

    /** All of [stat]'s multipliers together. */
    public double multiplier(Stat stat) {
        double m = 1;
        for (Multiplier x : multipliers(stat)) {
            m *= x.factor();
        }
        return m;
    }

    public void multiply(String label, Stat stat, double factor) {
        if (factor != 1) {
            multipliers.add(new Multiplier(label, stat, factor));
        }
    }

    /** Every flat source of [stat] added up. */
    public double flat(Stat stat) {
        double sum = 0;
        for (EnumMap<Stat, Double> m : flat.values()) {
            sum += m.getOrDefault(stat, 0.0);
        }
        return sum;
    }

    public double flat(Source source, Stat stat) {
        EnumMap<Stat, Double> m = flat.get(source);
        return m == null ? 0 : m.getOrDefault(stat, 0.0);
    }

    public double additive(Stat stat) {
        return additive.getOrDefault(stat, 0.0);
    }

    public List<Multiplier> multipliers(Stat stat) {
        return multipliers.stream().filter(m -> m.stat() == stat).toList();
    }

    /** [stat] as it plays: flat x (1 + additive) x multipliers; Speed capped. */
    public double get(Stat stat) {
        double value = flat(stat) * (1 + additive(stat));
        for (Multiplier m : multipliers) {
            if (m.stat() == stat) {
                value *= m.factor();
            }
        }
        if (stat == Stat.SPEED) {
            value = Math.min(value, speedCap);
        }
        return Math.max(0, value);
    }

    /** Speed before the cap, for the stats page. */
    public double uncappedSpeed() {
        double value = flat(Stat.SPEED) * (1 + additive(Stat.SPEED));
        for (Multiplier m : multipliers(Stat.SPEED)) {
            value *= m.factor();
        }
        return value;
    }

    /** Where [stat] comes from, one "§7Armor: §a+1,234" line per source, then its percents. */
    public List<String> breakdown(Stat stat) {
        List<String> lines = new ArrayList<>();
        for (Source source : Source.values()) {
            double v = flat(source, stat);
            if (v != 0) {
                lines.add("§7 " + source.label + ": " + stat.color + (v > 0 ? "+" : "") + stat.format(v));
            }
        }
        double add = additive(stat);
        if (add != 0) {
            lines.add("§7 Bonus: " + stat.color + "+" + Stat.number(add * 100) + "%");
        }
        for (Multiplier m : multipliers(stat)) {
            lines.add("§7 " + m.label() + ": " + stat.color + String.format(java.util.Locale.ROOT, "x%.2f", m.factor()));
        }
        return lines;
    }
}
