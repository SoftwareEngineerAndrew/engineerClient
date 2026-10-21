package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The pets the Rustclear world has: Golden Dragon, Ender Dragon, Phoenix, Spirit and Black Cat.
 * Their stats go from level 1 to their max in a straight line (DPS-Bot's pets.json, from Hypixel's
 * own numbers), their lore is Hypixel's as recorded in the Pets menu (menus-skyblock.md B.1.1;
 * NotEnoughUpdates' for what wasn't recorded), and so are their skins. Free of Minecraft, so the
 * kit file can check pets and the stats can be tested.
 *
 * Perks that count in a fight are in the stats: Golden Dragon's Shining Scales (a maxed Gold
 * collection) and Dragon's Greed (at its cap), Ender Dragon's Superior, and a held item's stat boost
 * (Hephaestus Remedies doubles the pet's Strength). The rest are in the lore only.
 */
public final class PetDefs {
    public enum Rarity {
        COMMON("§f", "COMMON", 0, 1),
        UNCOMMON("§a", "UNCOMMON", 6, 2),
        RARE("§9", "RARE", 11, 3),
        EPIC("§5", "EPIC", 16, 4),
        LEGENDARY("§6", "LEGENDARY", 20, 5),
        MYTHIC("§d", "MYTHIC", 20, 6),
        SPECIAL("§c", "SPECIAL", 20, 0),
        VERY_SPECIAL("§c", "VERY SPECIAL", 20, 0);

        public final String color;
        public final String label;
        /** Where the rarity starts in [LEVEL_XP] (Hypixel's pet level table). */
        final int xpOffset;
        /** What the rarity adds to Pet Score (SPECIAL and VERY SPECIAL pets add nothing). */
        public final int score;

        Rarity(String color, String label, int xpOffset, int score) {
            this.color = color;
            this.label = label;
            this.xpOffset = xpOffset;
            this.score = score;
        }

        public static Rarity of(String name) {
            for (Rarity r : values()) {
                if (r.name().equalsIgnoreCase(name)) {
                    return r;
                }
            }
            return null;
        }
    }

    /** What a pet adds: flat stats, and additive percents (0.1 for 10%) under its perk's name. */
    public record Bonus(String label, Map<Stat, Double> flat, String percentLabel, Map<Stat, Double> percent) {
        public static final Bonus NONE = new Bonus("", Map.of(), "", Map.of());
    }

    /**
     * One kind of pet. [min] and [max] are its stats at level 1 and at its last level ([from] to
     * [maxLevel]: the Golden Dragon's stats run over levels 101-200); [otherMin]/[otherMax] its
     * perks' numbers, {0}, {1}... in the lore.
     */
    public enum Type {
        GOLDEN_DRAGON("Golden Dragon", 200, 101, List.of(Rarity.LEGENDARY),
            Map.of(Stat.STRENGTH, 25.0, Stat.ATTACK_SPEED, 25.0, Stat.MAGIC_FIND, 5.0),
            Map.of(Stat.STRENGTH, 50.0, Stat.ATTACK_SPEED, 50.0, Stat.MAGIC_FIND, 10.0),
            new double[] {5.05, 0.25, 2.5, 0.125, 126.2}, new double[] {10, 0.5, 5, 0.25, 250},
            "ewogICJ0aW1lc3RhbXAiIDogMTYyMDM1MDA5ODgyNiwKICAicHJvZmlsZUlkIiA6ICJiNWRkZTVmODJlYjM0OTkzYmMwN2Q0MGFiNWY2ODYyMyIsCiAgInByb2ZpbGVOYW1lIiA6ICJsdXhlbWFuIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzJlOWY5YjFmYzAxNDE2NmNiNDZhMDkzZTUzNDliMmJmNmVkZDIwMWI2ODBkNjJlNDhkYmYzYWY5YjA0NTkxMTYiLAogICAgICAibWV0YWRhdGEiIDogewogICAgICAgICJtb2RlbCIgOiAic2xpbSIKICAgICAgfQogICAgfQogIH0KfQ=="),
        ENDER_DRAGON("Ender Dragon", 100, 1, List.of(Rarity.EPIC, Rarity.LEGENDARY),
            Map.of(Stat.CRIT_CHANCE, 0.1, Stat.CRIT_DAMAGE, 0.5, Stat.STRENGTH, 0.5), Map.of(Stat.CRIT_CHANCE, 10.0, Stat.CRIT_DAMAGE, 50.0, Stat.STRENGTH, 50.0),
            new double[] {2, 0.5, 0.3, 0.1}, new double[] {200, 50, 30, 10},
            "eyJ0aW1lc3RhbXAiOjE1MTg5ODE1ODYzNTksInByb2ZpbGVJZCI6ImQxY2VjOWFkMWRhODQxNzliMWU1NjA0ZjcyYmZiMjI2IiwicHJvZmlsZU5hbWUiOiJydXRnZXI0NjUiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2FlYzNmZjU2MzI5MGIxM2ZmM2JjYzM2ODk4YWY3ZWFhOTg4YjZjYzE4ZGMyNTQxNDdmNTgzNzRhZmU5YjIxYjkifX19"),
        PHOENIX("Phoenix", 100, 1, List.of(Rarity.SPECIAL, Rarity.VERY_SPECIAL),
            Map.of(Stat.INTELLIGENCE, 51.0, Stat.STRENGTH, 10.5), Map.of(Stat.INTELLIGENCE, 150.0, Stat.STRENGTH, 60.0),
            new double[] {10.1, 2, 1.1, 2}, new double[] {20, 4, 13, 4},
            "ewogICJ0aW1lc3RhbXAiIDogMTY0Mjg2NTc3MTM5MSwKICAicHJvZmlsZUlkIiA6ICJiYjdjY2E3MTA0MzQ0NDEyOGQzMDg5ZTEzYmRmYWI1OSIsCiAgInByb2ZpbGVOYW1lIiA6ICJsYXVyZW5jaW8zMDMiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjZiMWI1OWJjODkwYzljOTc1Mjc3ODdkZGUyMDYwMGM4Yjg2ZjZiOTkxMmQ1MWE2YmZjZGIwZTRjMmFhM2M5NyIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9"),
        // OPEN: Spirit's and Black Cat's head skins (textures weren't kept from the recordings); a plain head until then.
        SPIRIT("Spirit", 100, 1, List.of(Rarity.EPIC, Rarity.LEGENDARY),
            Map.of(Stat.INTELLIGENCE, 1.0, Stat.SPEED, 0.3), Map.of(Stat.INTELLIGENCE, 100.0, Stat.SPEED, 30.0),
            new double[] {0.45}, new double[] {45}, null),
        BLACK_CAT("Black Cat", 100, 1, List.of(Rarity.LEGENDARY, Rarity.MYTHIC),
            Map.of(Stat.INTELLIGENCE, 1.0, Stat.SPEED, 0.25, Stat.MAGIC_FIND, 0.15),
            Map.of(Stat.INTELLIGENCE, 100.0, Stat.SPEED, 25.0, Stat.MAGIC_FIND, 15.0),
            new double[] {0.15, 1}, new double[] {15, 100}, null);

        public final String title;
        public final int maxLevel;
        final int from;
        public final List<Rarity> rarities;
        final Map<Stat, Double> min;
        final Map<Stat, Double> max;
        final double[] otherMin;
        final double[] otherMax;
        public final String skin;

        Type(String title, int maxLevel, int from, List<Rarity> rarities, Map<Stat, Double> min, Map<Stat, Double> max,
             double[] otherMin, double[] otherMax, String skin) {
            this.title = title;
            this.maxLevel = maxLevel;
            this.from = from;
            this.rarities = rarities;
            this.min = min;
            this.max = max;
            this.otherMin = otherMin;
            this.otherMax = otherMax;
            this.skin = skin;
        }

        public static Type of(String name) {
            for (Type t : values()) {
                if (t.name().equalsIgnoreCase(name)) {
                    return t;
                }
            }
            return null;
        }

        public Rarity best() {
            return rarities.get(rarities.size() - 1);
        }

        /** How far along its stats [level] is, 0 to 1. */
        double progress(int level) {
            return Math.max(0, Math.min(1, (level - from) / (double) (maxLevel - from)));
        }

        private double other(int i, int level) {
            double t = progress(level);
            return otherMin[i] + (otherMax[i] - otherMin[i]) * t;
        }
    }

    /** Hypixel's Combat stats (Superior raises them all), and Magic Find. */
    private static final List<Stat> COMBAT = List.of(Stat.HEALTH, Stat.DEFENSE, Stat.TRUE_DEFENSE, Stat.STRENGTH,
        Stat.CRIT_CHANCE, Stat.CRIT_DAMAGE, Stat.ATTACK_SPEED, Stat.FEROCITY, Stat.SWING_RANGE, Stat.INTELLIGENCE,
        Stat.ABILITY_DAMAGE, Stat.HEALTH_REGEN, Stat.VITALITY, Stat.MENDING, Stat.MAGIC_FIND);
    /** Shining Scales counts a Gold collection's digits up to 100M (9), as if maxed. */
    private static final int GOLD_DIGITS = 9;
    /** The stat glyphs Hypixel's resource pack draws in ability text (not in the stat lines at the top). */
    private static final String STRENGTH_GLYPH = "";
    private static final String CRIT_DAMAGE_GLYPH = "";
    private static final String SPEED_GLYPH = "";
    private static final String MAGIC_FIND_GLYPH = "";
    private static final String PET_LUCK_GLYPH = "";
    /** After a skinned pet's name: "§6Black Cat§5 ✦". */
    public static final String SKIN_MARK = "§5 ✦";

    /**
     * The exp each level costs, Hypixel's pet table: a pet of rarity r goes from level n to n + 1 for
     * LEVEL_XP[r.xpOffset + n - 1] (1 to 100 is 25,353,230 for a Legendary, 5,624,785 for a Common).
     */
    private static final int[] LEVEL_XP = {100, 110, 120, 130, 145, 160, 175, 190, 210, 230, 250, 275, 300, 330, 360, 400,
        440, 490, 540, 600, 660, 730, 800, 880, 960, 1050, 1150, 1260, 1380, 1510, 1650, 1800, 1960, 2130, 2310, 2500, 2700,
        2920, 3160, 3420, 3700, 4000, 4350, 4750, 5200, 5700, 6300, 7000, 7800, 8700, 9700, 10800, 12000, 13300, 14700, 16200,
        17800, 19500, 21300, 23200, 25200, 27400, 29800, 32400, 35200, 38200, 41400, 44800, 48400, 52200, 56200, 60400, 64800,
        69400, 74200, 79200, 84700, 90700, 97200, 104200, 111700, 119700, 128200, 137200, 146700, 156700, 167700, 179700,
        192700, 206700, 221700, 237700, 254700, 272700, 291700, 311700, 333700, 357700, 383700, 411700, 441700, 476700, 516700,
        561700, 611700, 666700, 726700, 791700, 861700, 936700, 1016700, 1101700, 1191700, 1286700, 1386700, 1496700, 1616700,
        1746700, 1886700};
    /** Golden Dragon past 100: 101 is free, 102 costs 5,555, each after it 1,886,700 (210,255,385 in all at 200). */
    private static final int DRAGON_XP_102 = 5555;
    private static final int DRAGON_XP_STEP = 1886700;

    /**
     * An item a pet holds: its name as the lore shows it (rarity colour and name), its effect lines,
     * and what it multiplies the pet's own Strength by (Hephaestus Remedies: x2, so a level 200 Golden
     * Dragon's 149.9 reads +300).
     */
    public record HeldItem(String id, String name, List<String> effect, double strength) {}

    /** The held items seen on Hypixel (menus-skyblock.md B.1.1), by their name. */
    private static final Map<String, HeldItem> HELD_ITEMS = Map.of(
        "Unalloyed Speed", new HeldItem(null, "§5Unalloyed Speed", List.of("§7Grants §f+50 Max Speed Cap§7."), 1),
        "Hephaestus Remedies", new HeldItem(null, "§6Hephaestus Remedies",
            List.of("§7Increases this pet's §c" + STRENGTH_GLYPH + " Strength §7by", "§a100%§7."), 2));

    private PetDefs() {
    }

    /** [level] within what [type] can be. */
    public static int clampLevel(Type type, int level) {
        return Math.max(1, Math.min(type.maxLevel, level));
    }

    /** [rarity] if [type] comes in it, else its best. */
    public static Rarity fit(Type type, Rarity rarity) {
        return rarity != null && type.rarities.contains(rarity) ? rarity : type.best();
    }

    // ---------------------------------------------------------------- exp

    /** The exp a [rarity] [type] has gained when it reaches [level] (Hypixel's table; 0 at level 1). */
    public static double expFor(Type type, Rarity rarity, int level) {
        double total = 0;
        for (int l = 2; l <= Math.min(level, 100); l++) {
            total += LEVEL_XP[Math.min(LEVEL_XP.length - 1, rarity.xpOffset + l - 2)];
        }
        for (int l = 102; l <= Math.min(level, type.maxLevel); l++) {
            total += l == 102 ? DRAGON_XP_102 : DRAGON_XP_STEP;
        }
        return total;
    }

    /** The level [exp] makes a [rarity] [type]. */
    public static int levelFor(Type type, Rarity rarity, double exp) {
        int level = 1;
        while (level < type.maxLevel && expFor(type, rarity, level + 1) <= exp) {
            level++;
        }
        return level;
    }

    // ---------------------------------------------------------------- held items and skins

    /** The held item Hypixel calls [id] ("HEPHAESTUS_REMEDIES", "PET_ITEM_..."), or null for none. */
    public static HeldItem heldItem(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String name = words(id.replaceFirst("^PET_ITEM_", ""));
        HeldItem known = HELD_ITEMS.get(name);
        // OPEN: the names, rarities and effect lines of held items other than the two recorded ones.
        return known != null ? new HeldItem(id, known.name(), known.effect(), known.strength())
            : new HeldItem(id, "§5" + name, List.of(), 1);
    }

    /** The skin's name as the lore's first line has it: "Ivory" for BLACK_CAT_IVORY. */
    public static String skinName(Type type, String skin) {
        // OPEN: Hypixel's skin ids and display names (only "Ivory Skin" on a Black Cat was recorded).
        String id = skin.replaceFirst("^PET_SKIN_", "");
        if (id.startsWith(type.name() + "_")) {
            id = id.substring(type.name().length() + 1);
        }
        return words(id);
    }

    /** "UNALLOYED_SPEED" as "Unalloyed Speed". */
    private static String words(String id) {
        StringBuilder out = new StringBuilder();
        for (String word : id.toLowerCase(Locale.ROOT).split("_")) {
            if (!word.isEmpty()) {
                out.append(out.isEmpty() ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- stats

    /** The pet's own stats at [level] (Shining Scales' Strength and Magic Find included). */
    public static Map<Stat, Double> stats(Type type, Rarity rarity, int level) {
        return stats(type, rarity, level, null);
    }

    /** The pet's own stats at [level] holding [held] (null: nothing). */
    public static Map<Stat, Double> stats(Type type, Rarity rarity, int level, HeldItem held) {
        Map<Stat, Double> out = new EnumMap<>(Stat.class);
        double t = type.progress(level);
        for (Map.Entry<Stat, Double> e : type.max.entrySet()) {
            double lo = type.min.getOrDefault(e.getKey(), 0.0);
            out.put(e.getKey(), lo + (e.getValue() - lo) * t);
        }
        if (type == Type.GOLDEN_DRAGON) {
            out.merge(Stat.STRENGTH, 11.1 * GOLD_DIGITS, Double::sum);
            out.merge(Stat.MAGIC_FIND, 2.2 * GOLD_DIGITS, Double::sum);
        }
        if (held != null && held.strength() != 1 && out.containsKey(Stat.STRENGTH)) {
            out.put(Stat.STRENGTH, out.get(Stat.STRENGTH) * held.strength());
        }
        return out;
    }

    /** All a pet adds to its owner's stats. */
    public static Bonus bonus(Type type, Rarity rarity, int level) {
        return bonus(type, rarity, level, null);
    }

    /** All a pet holding [held] adds to its owner's stats. */
    public static Bonus bonus(Type type, Rarity rarity, int level, HeldItem held) {
        Map<Stat, Double> percent = new EnumMap<>(Stat.class);
        String perk = "";
        if (type == Type.GOLDEN_DRAGON) {
            // Dragon's Greed at its cap (any real Magic Find setup reaches it).
            perk = "Dragon's Greed";
            percent.put(Stat.STRENGTH, type.other(2, level) / 100);
        } else if (type == Type.ENDER_DRAGON && rarity == Rarity.LEGENDARY) {
            perk = "Superior";
            double superior = type.other(3, level) / 100;
            for (Stat s : COMBAT) {
                percent.put(s, superior);
            }
        }
        return new Bonus(type.title, stats(type, rarity, level, held), perk, percent);
    }

    /** Pet Score: each unique pet's best rarity ([Rarity.score]), plus 1 for each max level pet (menus-skyblock.md B.1, slot 47). */
    public static int score(List<Type> types, List<Rarity> rarities, List<Integer> levels) {
        Map<Type, Integer> best = new EnumMap<>(Type.class);
        int maxed = 0;
        for (int i = 0; i < types.size(); i++) {
            best.merge(types.get(i), rarities.get(i).score, Math::max);
            if (rarities.get(i).score > 0 && levels.get(i) >= types.get(i).maxLevel) {
                maxed++;
            }
        }
        return best.values().stream().mapToInt(Integer::intValue).sum() + maxed;
    }

    // ---------------------------------------------------------------- text

    /** "§7[Lvl 200] §6Golden Dragon". */
    public static String name(Type type, Rarity rarity, int level) {
        return name(type, rarity, level, null);
    }

    /** "§7[Lvl 100] §6Black Cat§5 ✦" with a [skin]. */
    public static String name(Type type, Rarity rarity, int level, String skin) {
        return "§7[Lvl " + level + "] " + rarity.color + type.title + (skin == null ? "" : SKIN_MARK);
    }

    /** The pet's tooltip, as Hypixel's (without the last line that says what a click does). */
    public static List<String> lore(Type type, Rarity rarity, int level) {
        return lore(type, rarity, level, expFor(type, rarity, level), null, null);
    }

    /**
     * The pet's tooltip as Hypixel's Pets menu shows it, down to the exp line (menus-skyblock.md
     * B.1.1): skill and skin, the stat lines, each perk with a blank line after it, the held item,
     * then MAX LEVEL and the exp.
     */
    public static List<String> lore(Type type, Rarity rarity, int level, double exp, HeldItem held, String skin) {
        List<String> out = new ArrayList<>();
        out.add("§8Combat Pet" + (skin == null ? "" : ", " + skinName(type, skin) + " Skin"));
        out.add("");
        Map<Stat, Double> s = stats(type, rarity, level, held);
        switch (type) {
            case GOLDEN_DRAGON -> {
                if (level < type.from) {
                    out.add("§7Its stats and perks grow from level §a" + type.from + "§7.");
                }
                out.add(statLine(Stat.STRENGTH, s));
                out.add(statLine(Stat.ATTACK_SPEED, s));
                out.add(statLine(Stat.MAGIC_FIND, s));
                out.add("");
                perk(out, "Gold's Power", "§7Increase the potency of §6Midas'", "§6Sword§7 and §6Midas Staff's §5Greed Ability",
                    "§7by §a" + Stat.number(type.other(0, level)) + "%§7.");
                perk(out, "Shining Scales", "§7Grants §c+11.1" + STRENGTH_GLYPH + " Strength §7and §b+2.2" + MAGIC_FIND_GLYPH,
                    "§bMagic Find §7to your pet for each digit", "§7in your §6Gold Collection§7.", "§8(Max 100M collection)");
                perk(out, "Dragon's Greed", "§7Grants §c+" + hundredths(type.other(1, level)) + "%§c" + STRENGTH_GLYPH
                    + " Strength §7per §b5§b" + MAGIC_FIND_GLYPH, "§bMagic Find§7. §8(Max +" + Stat.number(type.other(2, level)) + "%)");
                perk(out, "Legendary Treasure", "§7Gain §c" + hundredths(type.other(3, level)) + "% §7damage for every million",
                    "§7coins in your bank. §8(Max " + Stat.number(type.other(4, level)) + "%)");
                perk(out, "Symbiosis", "§7If you own a level §a200 Golden", "§aDragon§7, gain §6+5 coins §7per monster",
                    "§7kill for every other unique maxed", "§7Combat Pet that you own§7.");
            }
            case ENDER_DRAGON -> {
                out.add(statLine(Stat.STRENGTH, s));
                out.add(statLine(Stat.CRIT_DAMAGE, s));
                out.add(statLine(Stat.CRIT_CHANCE, s));
                out.add("");
                perk(out, "End Strike", "§7Deal §a" + Stat.number(type.other(0, level)) + "% §7more damage to §5Ender", "§7mobs.");
                perk(out, "One With The Dragon", "§7Buffs the §6Aspect of the Dragons",
                    "§7sword by §a" + Stat.number(type.other(1, level)) + " §cDamage §7and §a" + Stat.number(type.other(2, level)),
                    "§cStrength§7.");
                if (rarity == Rarity.LEGENDARY) {
                    perk(out, "Superior", "§7Increases all §cCombat §7stats and",
                        "§bMagic Find §7by §a" + Stat.number(type.other(3, level)) + "%§7.");
                }
            }
            case PHOENIX -> {
                out.add(statLine(Stat.STRENGTH, s));
                out.add(statLine(Stat.INTELLIGENCE, s));
                out.add("");
                perk(out, "Rekindle", "§7Before death, become §eimmune §7and",
                    "§7gain §c" + Stat.number(type.other(0, level)) + " §c" + STRENGTH_GLYPH + " Strength §7for §a"
                        + Stat.number(type.other(1, level)) + " §7seconds", "§860s cooldown");
                perk(out, "Fourth Flare", "§7On 4th melee strike, §6ignite §7mobs,",
                    "§7dealing §c" + Stat.number(type.other(2, level)) + "x §7your §9" + CRIT_DAMAGE_GLYPH + " Crit Damage",
                    "§7each second for §a" + Stat.number(type.other(3, level)) + " §7seconds");
                if (rarity == Rarity.VERY_SPECIAL) {
                    perk(out, "Eternal Coins", "§7Don't lose coins from death.");
                }
            }
            case SPIRIT -> {
                // OPEN: Spirit's perk text was recorded but not transcribed; these are NotEnoughUpdates' lines.
                out.add(statLine(Stat.INTELLIGENCE, s));
                out.add(statLine(Stat.SPEED, s));
                out.add("");
                perk(out, "Spirit Assistance", "§7Spawns a helpful spirit when", "§7you die in dungeons.");
                perk(out, "Spirit Cooldowns", "§7Reduces the cooldown of your", "§7ghost abilities in dungeons by",
                    "§a" + Stat.number(type.other(0, level)) + "%§7.");
                if (rarity == Rarity.LEGENDARY) {
                    perk(out, "Half Life", "§7If you are the first player to die", "§7in a dungeon, the score penalty",
                        "§7for that death is reduced to §a1§7.");
                }
            }
            case BLACK_CAT -> {
                // OPEN: Black Cat's perk text was recorded but not transcribed; these are NotEnoughUpdates' lines.
                out.add(statLine(Stat.INTELLIGENCE, s));
                out.add(statLine(Stat.SPEED, s));
                out.add(statLine(Stat.MAGIC_FIND, s));
                out.add("§7Pet Luck: §d+" + shown(type.other(0, level)));
                out.add("");
                perk(out, "Hunter", "§7Increases your §f" + SPEED_GLYPH + " Speed §7and speed cap",
                    "§7by §a" + Stat.number(type.other(1, level)) + "§7.");
                perk(out, "Omen", "§7Grants §d" + Stat.number(type.other(0, level)) + PET_LUCK_GLYPH + " Pet Luck§7.");
                perk(out, "Supernatural", "§7Grants §b" + Stat.number(s.getOrDefault(Stat.MAGIC_FIND, 0.0)) + MAGIC_FIND_GLYPH
                    + " Magic Find§7.");
            }
        }
        if (held != null) {
            out.add("§6Held Item: " + held.name());
            out.addAll(held.effect());
            out.add("");
        }
        if (type == Type.SPIRIT) {
            out.add("§8This pet's perks are active even");
            out.add("§8when the pet is not summoned!");
            out.add("");
        }
        if (level >= type.maxLevel) {
            out.add("§b§lMAX LEVEL");
            out.add("§8▸ " + String.format(Locale.ROOT, "%,d", (long) Math.floor(exp)) + " XP");
        } else {
            // OPEN: Hypixel's "Progress to Level N: x%" line, XP bar and x/y XP line (no pet below max was recorded).
            out.add("§7Level §a" + level + "§7/§a" + type.maxLevel);
        }
        return out;
    }

    /** Whether the lore says "Can be upgraded at Kat in The Hub!": a pet that comes in a better rarity (Phoenix, Black Cat). */
    public static boolean upgradable(Type type, Rarity rarity) {
        return rarity != type.best();
    }

    /** "§7Strength: §c+60" (Hypixel shows whole numbers: a level 200 Golden Dragon's 299.8 Strength reads +300). */
    private static String statLine(Stat stat, Map<Stat, Double> s) {
        return "§7" + stat.key + ": " + stat.color + "+" + shown(s.getOrDefault(stat, 0.0)) + (stat.percent ? "%" : "");
    }

    private static String shown(double value) {
        return Stat.number(value >= 1 ? Math.round(value) : value);
    }

    /** [value] to two decimals without trailing zeros: "0.25". */
    private static String hundredths(double value) {
        return java.math.BigDecimal.valueOf(Math.round(value * 100) / 100.0).stripTrailingZeros().toPlainString();
    }

    /** A perk: its name in gold, its lines, and the blank line after it. */
    private static void perk(List<String> out, String name, String... lines) {
        out.add("§6" + name);
        out.addAll(List.of(lines));
        out.add("");
    }

    /** "§6§lLEGENDARY PET". */
    public static String rarityLine(Rarity rarity) {
        return rarity.color + "§l" + rarity.label + " PET";
    }

    /** A rarity's name as people write it: "Very Special". */
    public static String pretty(Rarity rarity) {
        return words(rarity.name());
    }

    /** Every type, for menus that list them. */
    public static Map<String, Type> all() {
        Map<String, Type> out = new LinkedHashMap<>();
        for (Type t : Type.values()) {
            out.put(t.name(), t);
        }
        return out;
    }
}
