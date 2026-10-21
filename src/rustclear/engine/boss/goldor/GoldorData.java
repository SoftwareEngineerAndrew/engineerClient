package net.rustclear.mod.engine.boss.goldor;

import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Every P3 number (boss-p3-goldor.md; main after the 10-06 update). n = ticks since "Who dares
 * trespass into my domain?". A Hypixel change is an edit here.
 */
final class GoldorData {
    private GoldorData() {
    }

    // ---------------------------------------------------------------- lines (§4)

    static final String WHO_DARES = "Who dares trespass into my domain?";
    /** The intro after "Who dares", 62 apart (167/167 runs: n 0/62/124/186). */
    static final List<String> INTRO = List.of(
        "Little ants, plotting and scheming, thinking they are invincible...",
        "I won't let you break the factory core, I gave my life to my Master.",
        "No one matches me in close quarters.");
    /** Each line at least 62 after the one before (a FIFO; the death-tick line isn't in it). */
    static final int LINE_GAP = 62;
    /** One per door, drawn at random with repeats (110/102/87 on main). */
    static final List<String> SECTION_LINES = List.of(
        "The little ants have a brain it seems.", "I will replace that gate with a stronger one!", "YOUR END IS NEAR!!");
    /** The taunts and how often each was said on main (58 ... 20): drawn by these weights, never twice in a row. */
    static final List<String> TAUNTS = List.of(
        "Slowing me down only prolongs your pain!", "You can't damage me, you can barely slow me down!",
        "There is no stopping me down there!", "Closer to me!", "Stop touching those terminals!",
        "Do you really think we won't repair everything? Your impact will be minuscule!",
        "I am the death zone, you are smart to flee.", "You are breaking precious materials, unforgivable.",
        "Come closer!", "CLOSER!");
    static final int[] TAUNT_WEIGHTS = {58, 44, 41, 37, 37, 35, 28, 25, 22, 20};
    /**
     * Taunts queued during S1 (spoken from 248 on): 0/1/2/3 in 2/28/43/11 of 84 complete main runs. A
     * line always comes at 248 (145/145); the two runs without a taunt had their section line there,
     * so the "none" share goes to one taunt (a taunt is only queued while S1 runs).
     */
    static final int[] S1_TAUNTS = {0, 30, 43, 11};
    /**
     * Later, unqueued taunts: 11 of 84 runs (13%), 17 lines, 10 of them in S4. Modelled as one draw per
     * run that puts 1-2 taunts somewhere in S4 (OPEN: 47-style, no trigger known).
     */
    static final double LATE_TAUNT_RUN = 0.13;
    static final String DEATH = "....";
    static final String FORGIVE = "Necron, forgive me.";
    /** "Necron, forgive me." comes 52 after "...." (35/35 post-update; 82 before). */
    static final int FORGIVE_AFTER = 52;
    /** Necron's first line, 10 after "forgive" (27/35 post-update); P4 begins there. */
    static final int NECRON_AFTER_FORGIVE = 10;
    /** When Goldor reaches the core alive (1 of 35 post runs), his arrival lines queue up. */
    static final List<String> ARRIVAL = List.of(
        "You have done it, you destroyed the factory...", "But you have nowhere to hide anymore!",
        "YOU ARE FACE TO FACE WITH GOLDOR!");
    static final String DEATH_TICK = "What do you think you are doing there!";

    // ---------------------------------------------------------------- sections and gates (§1-§3)

    /** A gate left standing at its section's end goes by itself this long after "will open" (33/33, max 100). */
    static final int GATE_AUTO = 100;
    /** Where a Superboom blows gate k (1-3): its box, reached within [BOOM_REACH]. */
    static final AABB[] GATE_BOXES = {null,
        new AABB(93, 113, 121, 108, 138, 125), new AABB(16, 113, 125, 20, 138, 140), new AABB(1, 113, 48, 16, 138, 52)};
    static final double BOOM_REACH = 1.5;
    /** Gate centres (for the explosion puff). */
    static final Vec3[] GATE_CENTRES = {null, new Vec3(100, 118, 122.5), new Vec3(17.5, 118, 132), new Vec3(8, 118, 49.5)};

    // ---------------------------------------------------------------- death ticks (§5)

    /** n mod 60 = 59 (2/3) or 58 (1/3); the phase is sticky: a per-run phase, then a 20% flip per window. */
    static final double DT_EARLY_RUN = 1 / 3.0;
    static final double DT_FLIP = 0.2;
    /** Death-tick zones per section (feet; prior, ADS's measurement in §5). */
    static final AABB[] DT_ZONES = {null,
        new AABB(90, 106, 26, 114, 146, 121), new AABB(20, 106, 122, 114, 146, 146),
        new AABB(-6, 106, 51, 18, 146, 146), new AABB(-6, 106, 26, 90, 146, 50)};
    /** The core (entered = "in") and the strip in front of its door: never hit. */
    static final AABB CORE_BOX = new AABB(39, 0, 54, 71, 155.5, 118);
    static final AABB STRIP = new AABB(45, 100, 50, 65, 160, 54.5);
    /** Leaving the core from S4's start until Goldor flies puts you back here (ads-analysis "inner chamber"). */
    static final Vec3 CHAMBER_BACK = new Vec3(54.5, 115, 58.3);

    // ---------------------------------------------------------------- Goldor (§8)

    /** He spawns 2 ticks before his line at (80, 119, 40) facing +x; walks 0.06, slowed by hits 0.06 x (1 - 0.1k). */
    static final Vec3 SPAWN = new Vec3(80, 119, 40);
    static final double WALK = 0.06;
    /** The slowed ladder's k (0.054 / 0.048 / 0.036 / 0.024 / 0.012). */
    static final int[] SLOW_K = {1, 2, 4, 6, 8};
    /**
     * Ticks a hit keeps him slowed, and how many hits make each rung (OPEN: the trigger behind the
     * ladder isn't measured; hits in the last 20 ticks -> k).
     */
    static final int SLOW_WINDOW = 20;
    static final double SPRINT = 0.6;
    /** The catch-up sprint 60 ticks after a door when he's still in that section's segment (OPEN 48). */
    static final int SPRINT_LAG = 60;
    static final double FLY = 0.8;
    /** He flies once everyone is in the core, 4 ticks after the opening at the earliest (median 18 after). */
    static final int FLY_MIN = 4;
    /** Flight start -> "....": median 44 (14-74, n 32, post-update). */
    static final int KILL_AFTER_FLY = 44;
    /** Armoured (health 1) on the track in 46 of 55 runs, else 300,000. */
    static final double ARMOURED = 46 / 55.0;

    /** The track: where its four lines cross (S1 x 99.55, S2 z 131.7, S3 x 8.4, S4 z 40). */
    static final Vec3[] CORNERS = {
        new Vec3(99.55, 119, 40), new Vec3(99.55, 119, 131.7), new Vec3(8.4, 118.5, 131.7), new Vec3(8.4, 118, 40)};
    /** y along each line: S1 119, S2 118.5, S3 118, S4 rising 118.1 -> 119. */
    static final double[] Y_FROM = {119, 118.5, 118, 118.1};
    static final double[] Y_TO = {119, 118.5, 118, 119};
    /** Track distance at each segment's start, and the loop's length. */
    static final double[] BOUNDS = {0, 90.7, 182.1, 272.8, 364.2};
    static final double LOOP = 364.2;
    /** (80, 119, 40), 19.5 blocks before the S1 corner. */
    static final double START_S = 344.7;
    /** The S1 segment, for the catch-up, starts on the S4 line between x 95.5 and 98. */
    static final double S1_ENTRY = 361.5;
    /** Where a catch-up sprint ends (the next corner). */
    static final double[] SPRINT_TO = {91.2, 182.3, 272.8};
    /** Over the core's door; flying in, then on through the door at 0.4 until z 56, then 0.07. */
    static final Vec3 CORE_POINT = new Vec3(53.6, 117, 40);
    static final double CORE_IN_X = 53.6;
    static final double CORE_IN_Z = 56.0;

    // ---------------------------------------------------------------- attacks (§10; triggers OPEN 47)

    /** Frenzy on n % 10 == 7 at players within this of him (OPEN 47: the trigger isn't measured). */
    static final int FRENZY_RESIDUE = 7;
    static final double FRENZY_RANGE = 4;
    /** F7: 2,165-43,680, ramping within a burst: a small first hit, then large. */
    static final double FRENZY_FIRST_MIN = 2_165;
    static final double FRENZY_FIRST_MAX = 13_000;
    static final double FRENZY_MIN = 30_000;
    static final double FRENZY_MAX = 43_680;
    /** Greatsword 120,000 (M7 1,000,000): a player touching an orbiting giant's sword (OPEN 47). */
    static final double GREATSWORD = 120_000;
    static final double SWORD_REACH = 2.0;
    static final int SWORD_GAP = 20;
    /** Four invisible golden-sword giants orbit him from n 11-19: radius 3 round him + (2.4, -8, -3.5), 6° a tick. */
    static final int GIANTS_FROM = 11;
    static final Vec3 GIANT_OFFSET = new Vec3(2.4, -8, -3.5);
    static final double GIANT_RADIUS = 3;
    static final double GIANT_STEP = 6;
}
