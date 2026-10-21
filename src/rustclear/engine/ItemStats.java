package net.rustclear.mod.engine;

/**
 * Hypixel SkyBlock's dungeon item stat math (community wiki, Oct 2026; which stats skip the cata
 * bonus or aren't scaled checked against the grey dungeon numbers in real items' lore, Oct 2026),
 * free of Minecraft so it can be unit tested.
 *
 * A mob-drop item's base stat is its floor tier's value times (1 + quality / 100). Inside the
 * Catacombs every stat then gets (cata bonus + 10 per star + 5 per master star + 5) percent more
 * (stats.md §4, measured exactly: the flat +5 is on every item); Attack Speed, Crit Chance, Ability
 * Damage, Magic Find, Ferocity, True Defense and Mending skip the cata bonus, Health Regen and
 * Vitality get only the flat +5 (x1.05), and Shot Cooldown and Swing Range are never scaled.
 * Master stars need all five stars and only count in Master Mode. Outside dungeons each star is
 * worth 2% (not on Health Regen or Vitality) and nothing else applies.
 */
public final class ItemStats {
    /** Catacombs level 0..50's stat bonus, percent. */
    private static final int[] CATA_BONUS = {
        10, 15, 20, 25, 31, 38, 46, 55, 65, 75, 85, 95, 105, 115, 125, 135, 145, 155, 165, 175, 185, 195, 205,
        215, 225, 235, 245, 255, 265, 275, 285, 295, 305, 315, 325, 335, 345, 355, 365, 375, 385, 395, 405, 415,
        425, 435, 445, 455, 465, 475, 485};

    public static final int MAX_CATA = 50;
    public static final int MAX_STARS = 5;
    public static final int MAX_MASTER_STARS = 5;
    public static final int MAX_QUALITY = 50;
    public static final int MAX_TIER = 10;
    /** The +5% every dungeon item stat gets in the Catacombs. OPEN: its source (stats.md, not recorded 5). */
    public static final int FLAT_PERCENT = 5;

    private ItemStats() {
    }

    public static int cataBonus(int level) {
        return CATA_BONUS[Math.max(0, Math.min(MAX_CATA, level))];
    }

    /** Stats the Catacombs level doesn't boost (stars and master stars still do). */
    public static boolean skipsCata(String stat) {
        return switch (stat) {
            case "Attack Speed", "Crit Chance", "Ability Damage", "Magic Find", "Ferocity", "True Defense", "Mending" -> true;
            default -> false;
        };
    }

    /** Stats nothing scales. */
    public static boolean neverScaled(String stat) {
        return switch (stat) {
            case "Shot Cooldown", "Swing Range" -> true;
            default -> false;
        };
    }

    /** Stats that stars and the Catacombs level don't scale: only the flat +5% in the Catacombs (15 -> 15.75). */
    public static boolean starless(String stat) {
        return switch (stat) {
            case "Health Regen", "Vitality" -> true;
            default -> false;
        };
    }

    /** The percent [stat] gains inside the Catacombs. */
    public static double dungeonPercent(String stat, int cata, int stars, int masterStars, boolean masterMode, int medallion) {
        if (neverScaled(stat)) {
            return 0;
        }
        if (starless(stat)) {
            return FLAT_PERCENT + medallion;
        }
        int s = clamp(stars, MAX_STARS);
        int m = s == MAX_STARS && masterMode ? clamp(masterStars, MAX_MASTER_STARS) : 0;
        return (skipsCata(stat) ? 0 : cataBonus(cata)) + 10.0 * s + 5.0 * m + FLAT_PERCENT + medallion;
    }

    public static double inDungeon(String stat, double value, int cata, int stars, int masterStars, boolean masterMode, int medallion) {
        return value * (1 + dungeonPercent(stat, cata, stars, masterStars, masterMode, medallion) / 100.0);
    }

    /** Outside dungeons: 2% a star, no cata bonus and no master stars. */
    public static double outside(String stat, double value, int stars) {
        return neverScaled(stat) || starless(stat) ? value : value * (1 + 0.02 * clamp(stars, MAX_STARS));
    }

    /** A mob drop's base stat: its floor tier's value (1..10) times (1 + quality / 100). */
    public static double tieredBase(double[] tiers, int tier, int quality) {
        return tiers[Math.max(1, Math.min(tiers.length, tier)) - 1] * (1 + clamp(quality, MAX_QUALITY) / 100.0);
    }

    /** Entrance and F1 are tier 1 up to F7's 7; M1 is 4 up to M7's 10. */
    public static int floorTier(String floor) {
        if (floor != null && floor.equalsIgnoreCase("E")) {
            return 1;
        }
        if (floor == null || floor.length() < 2) {
            return 7;
        }
        int n;
        try {
            n = Integer.parseInt(floor.substring(1));
        } catch (NumberFormatException e) {
            return 7;
        }
        n = Math.max(1, Math.min(7, n));
        return Character.toUpperCase(floor.charAt(0)) == 'M' ? n + 3 : n;
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(max, value));
    }
}
