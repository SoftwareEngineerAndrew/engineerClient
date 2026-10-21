package net.rustclear.mod.engine;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/**
 * Class milestones, measured from the recordings' "<Class> Milestone ❶: You have dealt N Total
 * Damage so far! 01s" lines (abilities-items.md §2, session 8): nine fixed thresholds a class,
 * the same for Mage, Berserk and Archer (750k, 3.75M, 15M, 37.5M, 75M, 112.5M, 150M, 187.5M, 225M),
 * x0.6 for Tank, x29/15 on Master Mode (alpha). Archers count only ranged damage ("Ranged Damage"),
 * Tanks the damage they take as well ("You have tanked and dealt"). The time is the run's, as the
 * tab writes it. A big hit can pass several at once: each prints.
 */
final class Milestones {
    static final double[] THRESHOLDS = {750_000, 3_750_000, 15_000_000, 37_500_000, 75_000_000, 112_500_000,
        150_000_000, 187_500_000, 225_000_000};
    static final String[] GLYPHS = {"⓿", "❶", "❷", "❸", "❹", "❺", "❻", "❼", "❽", "❾"};

    private final DungeonRun run;
    /** {all damage dealt, ranged damage dealt, damage taken} per player. */
    private final Map<UUID, double[]> counts = new HashMap<>();
    private final Map<UUID, Integer> reached = new HashMap<>();

    Milestones(DungeonRun run) {
        this.run = run;
    }

    /** Threshold [i] (0-8) for [c] on this floor. */
    double threshold(DungeonClass c, int i) {
        double t = THRESHOLDS[i] * (c == DungeonClass.TANK ? 0.6 : 1);
        return run.floor.master() ? t * 29 / 15 : t;
    }

    void dealt(ServerPlayer player, double amount, boolean ranged) {
        double[] c = counts.computeIfAbsent(player.getUUID(), k -> new double[3]);
        c[0] += amount;
        if (ranged) {
            c[1] += amount;
        }
        check(player);
    }

    void taken(ServerPlayer player, double amount) {
        counts.computeIfAbsent(player.getUUID(), k -> new double[3])[2] += amount;
        check(player);
    }

    /** What [player]'s class counts toward its milestones. */
    double progress(ServerPlayer player, DungeonClass c) {
        double[] n = counts.getOrDefault(player.getUUID(), new double[3]);
        return switch (c) {
            case ARCHER -> n[1];
            case TANK -> n[0] + n[2];
            // OPEN: the Healer's milestones were never recorded (the recorder is never Healer).
            default -> n[0];
        };
    }

    int reached(ServerPlayer player) {
        return reached.getOrDefault(player.getUUID(), 0);
    }

    private void check(ServerPlayer player) {
        if (run.state() != DungeonRun.State.STARTED) {
            return;
        }
        DungeonClass c = PlayerStats.classOf(player);
        if (c == null) {
            return;
        }
        int done = reached(player);
        double have = progress(player, c);
        while (done < THRESHOLDS.length && have >= threshold(c, done)) {
            done++;
            String what = switch (c) {
                case ARCHER -> "§7: You have dealt §c%s§7 Ranged Damage so far! §a%s";
                case TANK -> "§7: You have tanked and dealt §c%s§7 Total Damage so far! §a%s";
                default -> "§7: You have dealt §c%s§7 Total Damage so far! §a%s";
            };
            player.sendSystemMessage(EngineItems.text("§e§l" + c.title + " Milestone §e" + GLYPHS[done] + String.format(Locale.ROOT,
                what, String.format(Locale.ROOT, "%,d", Math.round(threshold(c, done - 1))), TabList.clock(run.runTicks() / 20))));
        }
        reached.put(player.getUUID(), done);
    }

    /** The tab's " Your Milestone:" glyph (☄ for Archers, ☠ otherwise; OPEN for Tank and Healer). */
    static String symbol(DungeonClass c) {
        return c == DungeonClass.ARCHER ? "☄" : "☠";
    }

    /** The tab's progress row, "(51.7% to ❽)", counted from the last milestone; null at ❾. */
    String progressLine(ServerPlayer player, DungeonClass c) {
        int done = reached(player);
        if (done >= THRESHOLDS.length) {
            return null;
        }
        double from = done == 0 ? 0 : threshold(c, done - 1);
        double share = (progress(player, c) - from) / (threshold(c, done) - from) * 100;
        return "(" + TabList.trimmed(Math.max(0, Math.min(99.9, Math.floor(share * 10) / 10))) + "% to " + GLYPHS[done + 1] + ")";
    }
}
