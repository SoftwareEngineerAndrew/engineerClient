package net.rustclear.mod.engine.boss.maxor;

import java.util.List;
import net.minecraft.world.phys.Vec3;

/**
 * P1's numbers (boss-p1-maxor.md, main network after the 10-06 update; alpha F7/M7 share them).
 * Times are ticks from M0, Maxor's first line.
 */
final class MaxorData {
    private MaxorData() {
    }

    // ---- entry (pearls-teleports.md / critic C5: the teleport is exactly M0 - 2, absolute yaw 360 pitch 0)
    static final Vec3 ENTRY = new Vec3(73.5, 221.5, 14.5);
    static final int TELEPORT_BEFORE = 2;
    /** The bar "§c§lMaxor" appears at M0 - 1. */
    static final int BAR_BEFORE = 1;

    // ---- script
    /** Intro lines 42 apart (zero spread over 240 runs). */
    static final int LINE_GAP = 42;
    static final List<String> INTRO = List.of("WELL! WELL! WELL! LOOK WHO'S HERE!",
        "I'VE BEEN TOLD I COULD HAVE A BIT OF FUN WITH YOU.",
        "DON'T DISAPPOINT ME, I HAVEN'T HAD A GOOD FIGHT IN A WHILE.");
    static final List<String> STUN_LINES = List.of("YOU TRICKED ME!", "THAT BEAM! IT HURTS! IT HURTS!!");
    /** Taunt pool A from M0 + 281 (or enrage + 162), pool B from M0 + 436; most runs have none (76/116). */
    static final List<String> TAUNT_A = List.of("YOUR MOBILITY TRICKS DON'T WORK IN MY DOMAIN!",
        "YOUR WEAPONS CAN'T PIERCE THROUGH MY SHIELD!", "I HOPE YOU LIKE EXPLOSIONS TOO!",
        "MY MINIONS WILL HAVE TO WIPE THE FLOOR AFTER I'M DONE WITH YOU ALL!");
    static final List<String> TAUNT_B = List.of("Eat Wither Skulls, scum!", "Time for me to blast you away for good!",
        "How about you taste some rapid fire Wither Skulls!");
    static final int TAUNT_A_AT = 281;
    static final int TAUNT_A_AFTER_ENRAGE = 162;
    static final int TAUNT_B_AT = 440;
    /** Chance of each taunt (0 in 76/116 runs, 1 in 23, 2 in 15). OPEN: what the taunts do */
    static final double TAUNT_P = 0.25;
    /** A spoken line floats above Maxor as "§4§l<line>" for about 20 ticks. */
    static final int FLOAT_TICKS = 20;

    /** Maxor and the two top crystals at M0 + 4; his name stands at +20, the crystal labels at +26. */
    static final int SPAWN = 4;
    static final int NAME_STANDS = 20;
    static final int CRYSTAL_LABELS = 26;
    /** Pylons open ("Energy Crystal Missing", the floor under the beacon cleared) at +80/81. */
    static final int PYLONS = 80;
    static final int BEAM_CLEAR = 81;
    /** He starts moving and the adds spawn at +83; his first skull at +95; the beacon at +120. */
    static final int MOVE = 83;
    static final int FIRST_SKULL = 95;
    static final int BEACON = 120;
    /** M7 debris: ~200 blocks from +83 to +200 (one alpha run), from a ring ~10 round the beam. */
    static final int DEBRIS_UNTIL = 200;
    static final double DEBRIS_PER_TICK = 200 / 117.0;
    static final double DEBRIS_RADIUS = 10;

    // ---- positions
    static final Vec3 MAXOR = new Vec3(73.0, 226.0, 53.0);
    static final Vec3[] TOPS = {new Vec3(64.5, 238.375, 50.5), new Vec3(82.5, 238.375, 50.5)};
    static final Vec3[] PYLON_CRYSTALS = {new Vec3(52.5, 224.375, 41.5), new Vec3(94.5, 224.375, 41.5)};
    static final Vec3[] PYLON_STANDS = {new Vec3(52.5, 223.5, 41.5), new Vec3(94.5, 223.5, 41.5)};
    /** The beam: a beacon at (73, 221, 73), the glass above it colouring it. */
    static final int[] BEACON_POS = {73, 221, 73};
    static final int[] GLASS_POS = {73, 223, 73};
    static final int[] BEAM_BLOCK = {73, 222, 73};
    static final double BEAM_X = 73.5;
    static final double BEAM_Z = 73.5;
    /** A hit needs Maxor within this of the beam (max seen 3.42) at about y 225.5+. */
    static final double HIT_RANGE = 3.4;
    static final double HIT_MIN_Y = 224.5;

    // ---- crystals and the laser
    /** "The Energy Laser is charging up!" 27-29 ticks after the pair is complete (529/529). */
    static final int CHARGE_MIN = 27;
    static final int CHARGE_SPREAD = 3;
    /** Hits fall on a 10-tick grid from the beacon tick (110/112). */
    static final int HIT_GRID = 10;
    /** After a hit: new top crystals at +39/40, the pylon crystals gone at +41. */
    static final int TOPS_BACK = 39;
    static final int PYLONS_RESET = 41;
    /** The beam's glass goes black this long after a hit (red at the hit, yellow at the charge). */
    static final int GLASS_BLACK = 50;
    /** A picked crystal is removed 2-4 ticks after the click (median 4 post, p10 2): 3. */
    static final int PICK_REMOVE = 3;

    // ---- stuns, enrage, the kill
    /** Stun 1 ends at 25% or after 240 ticks (max 239 measured). OPEN: 39 */
    static final double STUN_FLOOR = 0.25;
    static final int STUN_MAX = 240;
    /** The laser itself takes the bar to 0.95. */
    static final double HIT_SHARE = 0.05;
    /** The kill is hit 2 + 5 (median; p10 2, p90 11). */
    static final int KILL_AFTER_HIT2 = 5;
    /** Explosion at kill + 40, platforms crumble and his health field 0 at +59, Storm speaks at +62. */
    static final int KILL_EXPLODE = 40;
    static final int CRUMBLE = 59;
    static final int STORM_LINE = 62;
    /** movement_speed after enrage (105/166 main-post). OPEN: 42 */
    static final double ENRAGE_SPEED = 0.5775;

    // ---- movement and attacks
    /** Walking speed to the beam: median 0.40 b/t (0.50-0.55 mid-walk). */
    static final double WALK = 0.40;
    /** Skulls: about 12 per 100 ticks before stun 1, none stunned, 45 per 100 enraged; power 0.1. */
    static final double SKULLS_CALM = 0.12;
    static final double SKULLS_ENRAGED = 0.45;
    static final double SKULL_POWER = 0.1;
    /** A skull's blast hits players this close. OPEN: skull blast radius */
    static final double SKULL_RADIUS = 2.5;
    /** "Maxor's Wither TNT hit you for 7200.0 damage." within this of the TNT. OPEN: radius, fuse */
    static final double TNT_DAMAGE = 7_200;
    static final double TNT_RADIUS = 5;
    static final int TNT_FUSE = 40;

    /**
     * Maxor's real health behind the bar. The recordings show only the bar's share, so F7 uses
     * 100M (the SkyBlock wiki's figure) and M7 his max_health attribute, 800M. OPEN: Maxor's HP
     */
    static final double HEALTH_F7 = 100_000_000;
    static final double HEALTH_M7 = 800_000_000;

    // ---- the adds: 106 wither skeletons at +83 (92/116 runs)
    static final int ADDS = 106;
    /** Spots where an add stood in 30+ of 40 runs (the rest land around the floors at y 221 and 225). */
    static final double[][] ADD_PERCHES = {
        {96.5, 239.0, 34.5}, {66.5, 238.0, 49.5}, {80.5, 238.0, 49.5}, {54.5, 236.0, 73.5}};
}
