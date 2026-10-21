package net.rustclear.mod;

import java.util.ArrayList;
import java.util.List;

/**
 * What the next dungeon must look like, in the JSON shape the server reads. A null or empty field
 * is fully random; only fully random runs count for the leaderboard.
 */
public final class DungeonSettings {
    /** Room names that must spawn. Empty: random rooms. */
    public List<String> rooms = new ArrayList<>();
    /** Puzzle names that may spawn. Null: any puzzle. */
    public List<String> puzzles;
    /** Inclusive [min, max] for the total secrets. Null: any count. */
    public int[] secrets;
    /**
     * The player's speed stat, Hypixel SkyBlock style (100 is vanilla walking; the movement speed
     * attribute is speed / 1000). Null: the server's default, 500. Not part of what makes a run
     * random, so it never changes leaderboard eligibility.
     */
    public Integer speed;
    /**
     * Whether practice rooms spawn their mobs (null: no, as on the server). A player preference
     * like speed, so it never changes leaderboard eligibility.
     */
    public Boolean practiceMobs;

    public boolean practiceMobs() {
        return Boolean.TRUE.equals(practiceMobs);
    }

    /**
     * Mob HP rises when a player walks into a room, x(1 + 0.05 k) as measured on Hypixel; what k
     * counts is still open (docs/f7-sim open question B5), so the leader can turn it off. Null: on.
     */
    public Boolean roomHpScaling;

    public boolean roomHpScalingOrDefault() {
        return !Boolean.FALSE.equals(roomHpScaling);
    }

    /**
     * Debug: write the SimLog (engine.SimLog), the host's own run as JSON lines for
     * tools/f7check. Null: off. The host's alone; never sent anywhere.
     */
    public Boolean simLog;

    public boolean simLog() {
        return Boolean.TRUE.equals(simLog);
    }

    /**
     * The floor ("F7" or "M7"; null: F7), set by the party leader in a Rustclear world. Only the
     * mod's own worlds read it; it is never sent to the Rust server.
     */
    public String floor;
    /**
     * How the party's Catacombs and class levels are set in a Rustclear world, the leader's
     * choice: "hypixel" (each player's own, read from their Hypixel profile) or "fixed" (null:
     * everyone at [partyCata] and [partyClassLevel]). Stats themselves come from each player's
     * gear (engine.PlayerStats). Never sent to the Rust server.
     */
    public String levels;
    public Integer partyCata;
    public Integer partyClassLevel;
    /** The player's Hypixel profile as their game read it (null: not read). Their own; never sent to the Rust server. */
    public net.rustclear.mod.engine.SkyblockProfile hypixel;

    public static final int DEFAULT_CATA = 50;
    public static final int DEFAULT_CLASS_LEVEL = 50;

    public Floor floorOrDefault() {
        return Floor.of(floor);
    }

    public boolean levelsFromHypixel() {
        return "hypixel".equals(levels);
    }

    public int partyCataOrDefault() {
        return partyCata == null ? DEFAULT_CATA : Math.max(0, Math.min(DEFAULT_CATA, partyCata));
    }

    public int partyClassLevelOrDefault() {
        return partyClassLevel == null ? DEFAULT_CLASS_LEVEL : Math.max(0, Math.min(DEFAULT_CLASS_LEVEL, partyClassLevel));
    }

    /** The Catacombs level item tooltips use on this side: the player's Hypixel one in Hypixel mode, else the party's. */
    public int cataForTooltips() {
        return levelsFromHypixel() && hypixel != null ? hypixel.cataLevel() : partyCataOrDefault();
    }

    /** A copy without anything only Rustclear worlds understand, for the Rust server. */
    public DungeonSettings forRustServer() {
        DungeonSettings out = copy();
        out.floor = null;
        out.levels = null;
        out.partyCata = null;
        out.partyClassLevel = null;
        out.hypixel = null;
        out.simLog = null;
        out.roomHpScaling = null;
        return out;
    }

    public static final int DEFAULT_SPEED = 500;
    public static final int MIN_SPEED = 400;
    public static final int MAX_SPEED = 700;

    /** The speed to play at: {@link #speed} clamped to 400..700, or 500 when unset. */
    public int speedOrDefault() {
        return speed == null ? DEFAULT_SPEED : clampSpeed(speed);
    }

    public static int clampSpeed(int value) {
        return Math.max(MIN_SPEED, Math.min(MAX_SPEED, value));
    }

    public boolean isFullyRandom() {
        return (rooms == null || rooms.isEmpty()) && puzzles == null && secrets == null;
    }

    public DungeonSettings copy() {
        DungeonSettings copy = new DungeonSettings();
        copy.rooms = rooms == null ? new ArrayList<>() : new ArrayList<>(rooms);
        copy.puzzles = puzzles == null ? null : new ArrayList<>(puzzles);
        copy.secrets = secrets == null ? null : secrets.clone();
        copy.speed = speed == null ? null : clampSpeed(speed);
        copy.practiceMobs = practiceMobs;
        copy.simLog = simLog;
        copy.roomHpScaling = roomHpScaling;
        copy.floor = floor == null ? null : Floor.of(floor).code;
        copy.levels = levels;
        copy.partyCata = partyCata;
        copy.partyClassLevel = partyClassLevel;
        copy.hypixel = hypixel == null ? null : hypixel.copy();
        return copy;
    }
}
