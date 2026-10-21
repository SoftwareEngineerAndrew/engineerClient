package net.rustclear.mod.engine;

import java.util.Locale;
import java.util.Random;
import net.minecraft.server.level.ServerPlayer;

/**
 * Dungeon blessings as Hypixel hands them out (score-secrets-puzzles.md "Blessings", measured from
 * the recordings' chat and chest packets):
 * - a secret chest holds one 39% of the time (87 of ~222 own secret-chest opens), level I (63%) or
 *   II, granted 23-24 ticks after the lid opens (every own case);
 * - a puzzle's reward chest holds a level V, the same 23-24 ticks after it opens (Three Weirdos:
 *   "PUZZLE SOLVED" +24);
 * - a room-clear drop is a level V item: "<name> has obtained Blessing of X!", then the find line with
 *   how long it lay there, e.g. "(07s)" or "(01m 22s)";
 * - the Quiz grants Blessing of Time V.
 * Everyone in the party gets the buff and the "Granted you" lines; the finder reads "You found a".
 * Values are the base ones (10-12/10-13 showed x1.25 for a while, gone again by 10-16).
 */
final class Blessings {
    private Blessings() {}

    /** A secret chest holds a blessing this often, level II this share of the time. */
    static final double CHEST_CHANCE = 0.39;
    static final double CHEST_LEVEL_II = 0.37;
    /** A chest's blessing (secret or puzzle) is granted this long after the lid opens. */
    static final int CHEST_DELAY = 23;

    // Kinds by source, from the recorded find lines (Life/Power/Stone/Wisdom).
    private static final int[] CHEST_WEIGHTS = {751, 533, 546, 350};
    private static final int[] PUZZLE_WEIGHTS = {248, 198, 31, 74};
    private static final int[] ITEM_WEIGHTS = {207, 278, 128, 208};

    static Pickup.Kind chestKind(Random random) {
        return pick(random, CHEST_WEIGHTS);
    }

    static Pickup.Kind puzzleKind(Random random) {
        return pick(random, PUZZLE_WEIGHTS);
    }

    static Pickup.Kind itemKind(Random random) {
        return pick(random, ITEM_WEIGHTS);
    }

    static int chestLevel(Random random) {
        return random.nextDouble() < CHEST_LEVEL_II ? 2 : 1;
    }

    private static Pickup.Kind pick(Random random, int[] weights) {
        int total = 0;
        for (int w : weights) {
            total += w;
        }
        int r = random.nextInt(total);
        for (int i = 0; i < weights.length; i++) {
            r -= weights[i];
            if (r < 0) {
                return Pickup.Kind.BLESSINGS[i];
            }
        }
        return Pickup.Kind.BLESSINGS[0];
    }

    /** The blessing's stat name in the run ("life", "power", "stone", "wisdom"). */
    static String key(Pickup.Kind kind) {
        return kind.name().substring("BLESSING_".length()).toLowerCase(Locale.ROOT);
    }

    /**
     * [finder] found a level-[level] [kind] (null finder: nobody named). [lyingTicks] >= 0 adds the
     * room-clear item's "(time)" suffix.
     */
    static void grant(DungeonRun run, Pickup.Kind kind, int level, ServerPlayer finder, long lyingTicks) {
        String word = kind.name.substring("§dBlessing of ".length());
        grant(run, key(kind), word, level, finder, lyingTicks, granted(kind, level));
    }

    /** The Quiz's Blessing of Time V. */
    static void grantTime(DungeonRun run, ServerPlayer finder) {
        String v = flat(5.8 * 5) + "§7 & §a+" + mult(1 + 0.03 * 5) + "x ";
        String line = "     §7Granted you §a+" + flat(5.8 * 5) + "§7 & §a+" + mult(1 + 0.03 * 5) + "x HP§7, §a+" + v
            + "§a❈ Defense§7, §a+" + v + "§b✎ Intelligence§7, and §a+" + v + "§c❁ Strength§7.";
        grant(run, "time", "Time", 5, finder, -1, new String[] {line});
    }

    private static void grant(DungeonRun run, String key, String word, int level, ServerPlayer finder, long lyingTicks,
                              String[] granted) {
        run.addBlessing(key, level);
        String suffix = lyingTicks >= 0 ? "§f! (§a" + time(lyingTicks) + "§f)" : "§f!";
        String what = "§dBlessing of " + word + " " + roman(level);
        run.forEachPlayer(p -> {
            String found = finder == null ? "§fA " + what + "§f was found" + suffix
                : (p == finder ? "§fYou" : DungeonRun.nameColour(finder)) + " §ffound a " + what + suffix;
            p.sendSystemMessage(EngineItems.text("§6§lDUNGEON BUFF! §r" + found));
            for (String line : granted) {
                p.sendSystemMessage(EngineItems.text(line));
            }
        });
    }

    /** The room-clear item's pickup line, sent before the find line. */
    static String obtainedLine(ServerPlayer taker, Pickup.Kind kind) {
        return DungeonRun.nameColour(taker) + "§f §ehas obtained §d" + kind.name.substring(2) + "§e!";
    }

    /**
     * What one blessing grants (classic glyphs, OPEN 69): Power/Stone/Wisdom +5.8 a level and x(1 +
     * 0.03 a level), Stone +7.2 Damage a level, Wisdom +4.8 Speed a level, Life x(1 + 0.044 a level)
     * Health and Health Regen.
     */
    static String[] granted(Pickup.Kind kind, int level) {
        String both = "§a+" + flat(5.8 * level) + "§7 & §a+" + mult(1 + 0.03 * level) + "x ";
        return switch (kind) {
            case BLESSING_POWER -> new String[] {
                "     §7Granted you " + both + "§c❁ Strength§7.",
                "     §7Also granted you " + both + "§9☠ Crit Damage§7."};
            case BLESSING_STONE -> new String[] {
                "     §7Granted you " + both + "§a❈ Defense §7and §a+" + flat(7.2 * level) + " §c❁ Damage§7."};
            case BLESSING_WISDOM -> new String[] {
                "     §7Granted you " + both + "§b✎ Intelligence §7and §a+" + flat(4.8 * level) + " §f✦ Speed§7."};
            default -> {
                String m = mult(1 + 0.044 * level);
                yield new String[] {"     §7Granted you §a+" + m + "x HP §7and §a+" + m + "x §c❤ Health Regen§7."};
            }
        };
    }

    /** "5.8", "11.6", "29", "36". */
    static String flat(double v) {
        double r = Math.round(v * 10) / 10.0;
        return r == Math.rint(r) ? Long.toString((long) r) : Double.toString(r);
    }

    static String mult(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /** "07s", "01m 22s". */
    static String time(long ticks) {
        long s = ticks / 20;
        return s < 60 ? String.format(Locale.ROOT, "%02ds", s) : String.format(Locale.ROOT, "%02dm %02ds", s / 60, s % 60);
    }

    static String roman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            default -> "V";
        };
    }
}
