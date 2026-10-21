package net.rustclear.mod.engine;

import java.util.List;

/**
 * The dungeon score as Hypixel shows it (score-secrets-puzzles.md): recomputed every 200 ticks
 * from Mort's first line and shown on the sidebar as "Cleared: x% (score)", "(0)" until the first
 * refresh. The model is the simplest one that fits what was measured:
 * - at the end (82 main F7 runs, ±2): 263 + 40 x secrets% + min(crypts, 5) + 2 x mimic + bat +
 *   prince - deaths;
 * - during the clear the live score sits below that by 115 at 0-9% cleared down to 34 at 90-99%,
 *   about 0.9 a percent, and jumps +35 (F7) / +28 (M7) once the team is in the boss.
 * So: 138 + 0.9 x Cleared% + 35 in the boss + 40 x secrets% + bonuses - deaths - puzzles - speed.
 * OPEN: 11 (what the live score's hidden term is; the +35/+28 boss term models it),
 * OPEN: 12 (where the +3 over Odin's 260 comes from; it is in the 138).
 */
public final class Score {
    /** 263 at the end less the room term (90) and the boss term (35). */
    private static final int BASE = 138;
    private static final double ROOMS_PER_PERCENT = 0.9;
    private static final int BOSS_F7 = 35;
    private static final int BOSS_M7 = 28;
    /** A puzzle failed (until it resets) or still unsolved in the boss: about -12 (fitted, -10..-14 at the end). */
    private static final int PUZZLE_PENALTY = 12;
    /** The live score's refresh period. */
    public static final int REFRESH_TICKS = 200;

    /**
     * The k-th speed point is lost at these wall seconds after Mort (3066a9, main F7, the only main
     * long run with every point; the other long runs agree within 25 s for the first points).
     * OPEN: 14 (the speed limit; M7 uses F7's curve)
     */
    private static final int[][] SPEED_LOSS = {
        {861, 1}, {892, 2}, {932, 3}, {963, 4}, {994, 5}, {1045, 6}, {1076, 7}, {1147, 8}, {1279, 10},
        {1681, 15}, {2124, 20}, {2686, 25}, {3218, 30}};

    public int deaths;
    public int failedPuzzles;
    /** Puzzles failed and not yet reset (each costs until it is redone). */
    public int puzzlesInFail;
    /** Puzzles not solved, counted once the boss is entered. */
    public int puzzlesUnsolved;
    public boolean spiritFirstDeath;
    public int clearedRooms;
    public int totalRooms;
    /** Grid tiles of cleared rooms, and all the dungeon's tiles (36): "Cleared: x%" counts tiles. */
    public int clearedTiles;
    public int totalTiles;
    public int secretsFound;
    public int totalSecrets;
    public long elapsedTicks;
    public int crypts;
    public boolean mimicKilled;
    public boolean batSlain;
    public boolean princeKilled;
    public boolean paulEzpz;
    /** The team reached the boss. */
    public boolean bossEntered;
    public boolean master;
    /** Wall time of Mort's first line (System.nanoTime), or 0. Speed runs on wall time, not ticks. */
    public long startNanos;
    /** What the sidebar shows: the last 200-tick refresh. */
    public int live;

    /** Back to a fresh run. */
    public void reset() {
        deaths = 0;
        failedPuzzles = 0;
        puzzlesInFail = 0;
        puzzlesUnsolved = 0;
        spiritFirstDeath = false;
        clearedRooms = 0;
        totalRooms = 0;
        clearedTiles = 0;
        totalTiles = 0;
        secretsFound = 0;
        totalSecrets = 0;
        elapsedTicks = 0;
        crypts = 0;
        mimicKilled = false;
        batSlain = false;
        princeKilled = false;
        paulEzpz = false;
        bossEntered = false;
        startNanos = 0;
        live = 0;
    }

    /** "Cleared: x%": round(100 x cleared tiles / 36). */
    public int clearedPercent() {
        return totalTiles == 0 ? 0 : Math.round(100f * clearedTiles / totalTiles);
    }

    /** The team's first death costs 1, each later one 2 (S21; first-death -2 in 5 of 45: OPEN: 13). */
    public int deathPenalty() {
        return deaths == 0 ? 0 : 1 + 2 * (deaths - 1);
    }

    public int bonus() {
        return Math.min(5, crypts) + (mimicKilled ? 2 : 0) + (batSlain ? 1 : 0) + (princeKilled ? 1 : 0);
    }

    /** Speed points lost by now (none in a normal run). */
    public int speedLoss() {
        if (startNanos == 0) {
            return 0;
        }
        double seconds = (System.nanoTime() - startNanos) / 1e9;
        if (seconds < SPEED_LOSS[0][0]) {
            return 0;
        }
        for (int i = 1; i < SPEED_LOSS.length; i++) {
            if (seconds < SPEED_LOSS[i][0]) {
                int[] a = SPEED_LOSS[i - 1];
                int[] b = SPEED_LOSS[i];
                return a[1] + (int) ((seconds - a[0]) * (b[1] - a[1]) / (b[0] - a[0]));
            }
        }
        int[] last = SPEED_LOSS[SPEED_LOSS.length - 1];
        // Past the table, the last measured rate (about 1 a 106 s).
        return Math.min(100, last[1] + (int) ((seconds - last[0]) / 106));
    }

    /** The score now. */
    public int total() {
        double secrets = totalSecrets == 0 ? 1 : Math.min(1.0, secretsFound / (double) totalSecrets);
        int puzzles = PUZZLE_PENALTY * (puzzlesInFail + (bossEntered ? puzzlesUnsolved : 0));
        int score = BASE + (int) Math.round(ROOMS_PER_PERCENT * clearedPercent())
            + (bossEntered ? (master ? BOSS_M7 : BOSS_F7) : 0)
            + (int) Math.floor(40 * secrets) + bonus() - deathPenalty() - puzzles - speedLoss();
        return Math.max(0, score);
    }

    /** The score the run is heading for if the boss is entered and beaten now (the "300 at blood" number). */
    public int withBoss() {
        boolean was = bossEntered;
        bossEntered = true;
        int score = total();
        bossEntered = was;
        return score;
    }

    /**
     * The fewest secrets found (of [totalSecrets]) for which the score heading into the boss reaches [target], with
     * everything else as it is now (rooms, crypts, mimic, deaths, puzzles, time); -1 when even every secret is short.
     * Every room checkmarked, no deaths, puzzles done and full speed leave 263 (Odin's 260 + 3 measured, OPEN: 12), so
     * 300 needs 37 more from 40 x secrets% + crypts (5 at most) + mimic (2): 80% of the secrets with 5 crypts, 92.5%
     * with none. The boss room's own share of exploration is the boss term, counted here as if entered.
     */
    public int secretsNeeded(int target) {
        int was = secretsFound;
        try {
            for (int s = 0; s <= totalSecrets; s++) {
                secretsFound = s;
                if (withBoss() >= target) {
                    return s;
                }
            }
            return -1;
        } finally {
            secretsFound = was;
        }
    }

    /** The 200-tick refresh: the sidebar shows this until the next one. */
    public void refresh() {
        live = total();
    }

    /** S+ 300 and up (lowest S+ seen 300, highest S 298), S 270, A 230, B 160, C 100, else D. OPEN: 16 */
    public static String rank(int score) {
        return score >= 300 ? "S+" : score >= 270 ? "S" : score >= 230 ? "A" : score >= 160 ? "B" : score >= 100 ? "C" : "D";
    }

    /** DungeonScoreState::sync_exploration, plus the tiles "Cleared: x%" counts. */
    public void syncExploration(List<RoomState> rooms) {
        int cleared = 0;
        int total = 0;
        int found = 0;
        int secrets = 0;
        int tiles = 0;
        int tilesCleared = 0;
        int unsolved = 0;
        for (RoomState room : rooms) {
            int size = room.segments().size();
            tiles += size;
            if (room.cleared()) {
                tilesCleared += size;
            }
            if (room.type() == RoomType.PUZZLE && !room.puzzleCompleted) {
                unsolved++;
            }
            if (room.type() == RoomType.ENTRANCE || room.type() == RoomType.FAIRY) {
                continue;
            }
            total++;
            // Hypixel's "Completed Rooms": only rooms with a checkmark, so not ones nobody has reached yet.
            if (room.cleared()) {
                cleared++;
            }
            found += room.foundSecrets;
            secrets += room.data().secrets();
        }
        clearedRooms = cleared;
        totalRooms = total;
        secretsFound = found;
        totalSecrets = secrets;
        clearedTiles = tilesCleared;
        totalTiles = tiles;
        puzzlesUnsolved = unsolved;
    }
}
