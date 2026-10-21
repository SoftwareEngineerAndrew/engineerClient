package net.rustclear.mod.engine.boss.witherking;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Every P5 number (boss-p5-wither-king.md). All of it is alpha-measured (5 M7 runs, 2026-10-05): M7
 * ships provisional (decision 6). A = "You... again?"; t = 0 is Necron's removal.
 */
final class WitherKingData {
    private WitherKingData() {
    }

    /** The five colours: relic stand, placed-relic stand (by its cauldron), cauldron, dragon spawn spot (y 14). */
    record Colour(String name, String code, Vec3 relic, Vec3 placed, BlockPos cauldron, Vec3 spawn) {}

    static final List<Colour> COLOURS = List.of(
        new Colour("Purple", "§5", new Vec3(57, 7.3, 133), new Vec3(55, 6.3, 42), new BlockPos(54, 7, 41), new Vec3(56.5, 14, 125.5)),
        new Colour("Green", "§a", new Vec3(21, 5.3, 95), new Vec3(50, 6.3, 45), new BlockPos(49, 7, 44), new Vec3(27.5, 14, 94.5)),
        new Colour("Blue", "§b", new Vec3(92, 5.3, 95), new Vec3(60, 6.3, 45), new BlockPos(59, 7, 44), new Vec3(84.5, 14, 94.5)),
        new Colour("Orange", "§6", new Vec3(93, 5.3, 57), new Vec3(58, 6.3, 43), new BlockPos(57, 7, 42), new Vec3(85.5, 14, 56.5)),
        new Colour("Red", "§c", new Vec3(21, 5.3, 60), new Vec3(52, 6.3, 43), new BlockPos(51, 7, 42), new Vec3(27.5, 14, 59.5)));

    /** "You... again?" 60 after the last relic is placed (4 of 5 runs: 60-61). */
    static final int AGAIN_AFTER_PLACE = 60;
    static final String WRONG_PLACE = "§cIt doesn't seem like that is supposed to go there.";

    /** Dragon 1 announced at A, dragon 2 at A + 60; later ones 3-19 after the last death (median 8, the 10-tick burst grid). */
    static final int SECOND_AT = 60;
    static final int NEXT_AFTER_DEATH = 8;
    /** Particles for 100 ticks (a burst every 10 at y 19), then the dragon. */
    static final int WARN = 100;
    static final double WARN_Y = 19;
    /** 1,000,000,000 health (26/26). */
    static final double DRAGON_HP = 1e9;
    /**
     * Each dragon's flight after it spawns, as {tick, x, y, z} points (boss-p5-wither-king.md
     * "Dragon flight", Phase 9 session): Green, Blue, Orange and Red take off along the same route
     * every run, climbing to y ~19 at ~0.37 blocks a tick; Purple hangs at its spawn for 20 ticks.
     * Green 3 runs, Blue 1 (43 ticks), Orange 3 (to +30); Red has one point (+10), the rest is
     * Orange's route mirrored across x 56 (OPEN 60). Routes end where the recordings do (dragons
     * die in ~20 ticks); after that each wanders ([WANDER_*]).
     */
    static final java.util.Map<String, double[][]> ROUTES = java.util.Map.of(
        "Green", new double[][] {{0, 27, 14, 94}, {10, 26.2, 15.4, 90.4}, {20, 25.3, 16.8, 86.7}, {30, 24.5, 18.2, 83},
            {40, 24.4, 18.8, 80}, {50, 25, 18.9, 76.1}},
        "Blue", new double[][] {{0, 84, 14, 94}, {10, 83, 15, 97}, {20, 81, 17, 101}, {30, 80, 18, 104}, {40, 78, 19, 107}},
        "Orange", new double[][] {{0, 85, 14, 56}, {10, 82.5, 16, 57}, {20, 79.5, 18, 59}, {30, 79, 19, 62}},
        "Red", new double[][] {{0, 27, 14, 59}, {10, 29, 16, 58}, {20, 32.5, 18, 59}, {30, 33, 19, 62}},
        "Purple", new double[][] {{0, 56, 14, 125}, {20, 56, 14, 125}});
    /**
     * After its route a flying dragon steers between random points of the box the recordings show
     * dragons in (x 25-90, y 19-26, z 45-115), turning at most [TURN] degrees a tick (twice that on
     * the routes) at [WANDER_SPEED], climbing or sinking at most [CLIMB] a tick (measured take-off:
     * 0.14-0.2), and picks the next point within [WANDER_REACHED]. OPEN 60: the real points, turn
     * rate and speed past the routes aren't recorded; speed is the routes' ~0.37-0.4.
     */
    static final double WANDER_X0 = 25, WANDER_X1 = 90, WANDER_Y0 = 19, WANDER_Y1 = 26, WANDER_Z0 = 45, WANDER_Z1 = 115;
    static final double WANDER_SPEED = 0.45;
    static final float TURN = 4f;
    static final double CLIMB = 0.2;
    static final double WANDER_REACHED = 4;
    /**
     * Purple doesn't fly: after its 20 ticks at the spawn it teleports to a random point of the box
     * every 20-35 ticks (the user; its sightings jump 20-77 blocks between syncs 20-35 ticks apart:
     * 429ba6 +20 -> +40, c2ba4d +20 -> +55 -> +80). OPEN 60.
     */
    static final int TELEPORT_MIN = 20, TELEPORT_MAX = 35;
    /** A kill counts within this of its own spot (OPEN: between 16.5 and 29.5, Q2). */
    static final double COUNT_RADIUS = 20;
    /** A counted kill's statue falls 202 ticks later (201-203, 15/15); the body stays ~160 (154-161). */
    static final int COLLAPSE_AFTER = 202;
    static final int BODY = 160;
    /** The statue: solid blocks within 12 (horizontal) of the spot, y 10-34 (740-1,440 falling blocks seen). */
    static final int STATUE_RADIUS = 12;
    static final int STATUE_Y0 = 10;
    static final int STATUE_Y1 = 34;
    /** Defeated 2-9 after the fifth counted kill (8, 6, 2, 9); the King's last lines at Defeated + 56, then + 52. */
    static final int DEFEATED_AFTER = 6;
    static final int INCREDIBLE_AFTER = 56;
    static final int THANK_AFTER = 52;

    // ---------------------------------------------------------------- dialogue (§8), 62 apart from A

    static final int GAP = 62;
    static final String AGAIN = "You... again?";
    static final List<String> FIXED = List.of(AGAIN, "I no longer wish to fight, but I know that will not stop you.",
        "We will decide it all, here, now.");
    static final List<String> FILL = List.of("I have more of those.", "Futile.", "Oh, this one hurts!", "My soul is disposable.");
    static final String ALLIES = "My enemies are now my strongest allies!";
    static final List<String> AT_392 = List.of("My soul is disposable.", "Mortals fighting against supreme beings, what a moving spectacle.");
    static final List<String> LORE = List.of("Mortals fighting against supreme beings, what a moving spectacle.",
        "This world isn't worth fighting for, how can you be so determined?",
        "Storm was my favorite sparring partner, you must have enjoyed fighting him!",
        "I have worked on this spell for centuries.", "Your skills have faded humans.");
    static final List<String> LATE = List.of("I have more of those.", "Oh, this one hurts!", "My soul is disposable.");
    static final String INCREDIBLE = "Incredible. You did what I couldn't do myself.";
    static final String THANK = "In a way I should thank you, I lost all hopes centuries ago that it would ever end.";

    // ---------------------------------------------------------------- the rest

    /** ~120 Wither Husks (500M, player NPCs) over the floor from the bar's start, for 400-700 ticks (OPEN: what they do). */
    static final int HUSKS = 120;
    static final int HUSK_SPAN = 550;
    static final double HUSK_HP = 500_000_000;
    /** The King's body: a wither from (54, 106, 32) down to (54, 3, 32) by A. */
    static final Vec3 KING_TOP = new Vec3(54, 106, 32);
    static final Vec3 KING_FLOOR = new Vec3(54, 3, 32);
    /** OPEN: how players reach the P5 floor isn't recorded; they are put here when P5 starts. */
    static final Vec3 FLOOR_SPOT = new Vec3(54.5, 5, 70.5);
}
