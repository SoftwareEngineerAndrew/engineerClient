package net.rustclear.mod.engine.boss.storm;

import java.util.List;
import net.minecraft.world.phys.Vec3;

/**
 * P2's numbers (boss-p2-storm.md, main after the 10-06 update; alpha shares them). t = 0 is
 * Storm's first line, 62 ticks after Maxor's kill.
 */
final class StormData {
    private StormData() {
    }

    /** The fixed script (zero spread in 196 phases); the lightning line is a coin flip. */
    static final int[] LINE_AT = {0, 62, 124, 186, 424, 486, 548};
    static final List<String> LINES = List.of("Pathetic Maxor, just like expected.",
        "Don't boast about beating this simple-minded Wither.",
        "My abilities are unparalleled, in many ways I am the last bastion.",
        "The memory of your death will be your fondest, focus up!",
        "The power of lightning is quite phenomenal. A single strike can vaporize a person whole.",
        "I'd be happy to show you what that's like!");
    static final List<String> LIGHTNING_LINES = List.of("ENERGY HEED MY CALL!", "THUNDER LET ME BE YOUR CATALYST!");
    static final List<String> CRUSH_LINES = List.of("Ouch, that hurt!", "Oof");
    static final String DEATH = "I should have known that I stood no chance.";
    static final String SON = "At least my son died by your hands.";
    static final String FOOL = "Fool, I'd hide under something next time if I were you!";
    /** Taunts while he is free: the first at ~847, then every 62. */
    static final int FIRST_TAUNT = 847;
    static final int TAUNT_GAP = 62;
    static final List<String> TAUNTS = List.of("BEGONE PILLAR!", "No more adventurers, no more heroes, death and thunder!",
        "Not just your land, but every kingdom will soon be ruled by our army of undead!", "This factory is too small for me!",
        "Slowing me down will be your greatest accomplishment!",
        "The days are numbered until I am finally unleashed again on the world!",
        "The Age of Men is over, we are creating tens, hundreds of withers!!", "THAT WAS ONLY IN MY WAY!");
    static final int LINE_GAP = 62;

    // ---- movement
    /** He appears here (at Maxor's kill + 60; this model starts him with his first line). */
    static final Vec3 SPAWN = new Vec3(103.0, 188.0, 53.0);
    /** The opening route at 0.40 b/t (ADS's waypoints), parking at exactly (102.375, 183, 52.375) at t 424. */
    static final Vec3[] ROUTE = {new Vec3(73, 183, 83), new Vec3(43, 183, 53), new Vec3(73, 183, 23), new Vec3(103, 183, 53),
        new Vec3(102.375, 183.0, 52.375)};
    static final double ROUTE_SPEED = 0.40;
    /** He leaves the park at 646-647 and is over Purple for the 659 check. OPEN: his speed when free */
    static final int LEAVE = 646;
    static final double FREE_SPEED = 0.75;

    // ---- attacks
    /** Lightning Fireballs every 40 ticks from t 78 to 398 (527/601 gaps exactly 40). */
    static final int FIREBALL_FIRST = 78;
    static final int FIREBALL_LAST = 398;
    static final int FIREBALL_GAP = 40;
    static final double FIREBALL_F7 = 75_000;
    static final double FIREBALL_RADIUS = 3;
    /** A Static Field: 4 bolts a strike, 7 strikes at +0, 3, 7, 11, 15, 19, 23 after the impact. */
    static final int[] FIELD_STRIKES = {0, 3, 7, 11, 15, 19, 23};
    static final int FIELD_BOLTS = 4;
    static final double FIELD_F7 = 10_800;
    static final double FIELD_RADIUS = 2.5;
    /** Giga Lightning: volleys at 518-520 and 528-530; exposed players take it (13/14), hidden ones don't (44/46). */
    static final int[] GIGA = {519, 529};
    /** F7 true damage 4,215-15,841 (median ~10,300). OPEN: Q5 what sets it */
    static final double GIGA_MIN = 4_215;
    static final double GIGA_MAX = 15_841;
    static final double GIGA_M7 = 999_999_999;
    /** Hidden: inside a pillar's 7x7 square at y <= 171.5. */
    static final double HIDE_MAX_Y = 171.5;
    /** After the enrage: skull pairs every 5 ticks from about +16 (median) until crush 2. */
    static final int SKULLS_AFTER_ENRAGE = 16;
    static final int SKULL_GAP = 5;

    // ---- pillars (crushers): 7x7, -x/-z corners; Red never moves
    static final int[][] PILLAR_CORNERS = {{97, 62}, {43, 62}, {43, 38}, {97, 38}};
    static final String[] PILLAR_NAMES = {"Purple", "Yellow", "Green", "Red"};
    /** Pads (ADS's boxes, unverified: low): x1, x2, z1, z2, y 169.5-171.5. OPEN: Q2 */
    static final int[][] PADS = {{111, 118, 91, 98}, {29, 36, 91, 98}, {29, 36, 9, 16}};
    static final double PAD_MIN_Y = 169.5;
    static final double PAD_MAX_Y = 171.5;
    /** Phase-start bottoms (Purple/Yellow 177 in 90%, Green 177); the floor; the armed rest. */
    static final int START_BOTTOM = 177;
    static final int FLOOR = 169;
    static final int TOP = 189;
    static final int REST = 186;
    /** A press: 5 layers, one every 4 ticks, from a check tick. */
    static final int STEP_LAYERS = 5;
    static final int STEP_TICKS = 4;
    /** At the floor: wait 25, rise a layer every 4 to 189, wait 27, step down 3 to 186. */
    static final int FLOOR_WAIT = 25;
    static final int TOP_WAIT = 27;
    /** A crushed pillar collapses 20 ticks later and stays spent at these bottoms (Purple ~187, Yellow ~181, Green ~180). */
    static final int COLLAPSE = 20;
    static final int[] SPENT_BOTTOM = {187, 181, 180, 0};

    // ---- crushes
    /** Checks every 20 ticks at t = 19 mod 20 (98-99% of 340 crush lines). */
    static final int CHECK_PERIOD = 20;
    static final int CHECK_RESIDUE = 19;
    /** His head point above his feet (crushed at -0.043, never at -0.084). */
    static final double HEAD = 3.06;
    /** A pillar crushes only within 60 ticks of its last step. */
    static final int ARMED = 60;
    /** ~8% of second crushes print no line. */
    static final double SILENT_CRUSH_2 = 0.08;
    /** After crush 1 the party takes him to 45% and he is enraged (bar 0.45). OPEN: Q1 what frees him */
    static final double ENRAGE_AT = 0.45;
    /** Crush 2 -> the death line: median 6 (p10 2, p90 62). */
    static final int DEATH_AFTER_CRUSH2 = 6;
    /** Death -> "At least my son..." +42, -> Goldor +62; his health field 0 at death + 60. */
    static final int SON_AFTER = 42;
    static final int GOLDOR_AFTER = 62;
    static final int HEALTH_ZERO_AFTER = 60;
    /** His health behind the bar (the recordings show only the bar). OPEN: Storm's HP */
    static final double HEALTH_F7 = 100_000_000;
    static final double HEALTH_M7 = 800_000_000;

    // ---- his corpse (ADS's StormCorpse numbers from 22 Boss Recorder fights; its spiral schedule
    // predicts 38 bolts between the death line and Goldor's, 34 measured: boss-p2-storm.md §9)
    /** The body spins this much a tick until its health goes to 0. */
    static final float CORPSE_SPIN = 40f;
    /** A wither.hurt from the body every 9-11 ticks. */
    static final int CORPSE_HURT_MIN = 9;
    static final int CORPSE_HURT_MAX = 11;
    /** The body is removed this long after the death line. */
    static final int CORPSE_GONE = 80;
    /** Lightning spirals from the body, by start tick after the death line: 20 bolts each, 4 ticks and 2 blocks apart, turning 90° a bolt. */
    static final int[] SPIRALS = {0, 20, 40, 60, 75, 90, 105, 120, 135, 150, 160, 170, 180, 190, 200, 210, 220, 230,
        240, 250, 260, 270, 280, 290};
    static final int SPIRAL_BOLTS = 20;
    static final int SPIRAL_STEP = 4;
    static final double SPIRAL_SPACING = 2.0;
}
