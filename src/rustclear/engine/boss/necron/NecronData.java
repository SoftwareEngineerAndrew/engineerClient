package net.rustclear.mod.engine.boss.necron;

import java.util.List;
import net.minecraft.world.phys.Vec3;

/**
 * Every P4 number (boss-p4-necron.md; the new script on main from 10-06, 35/35 runs). N = 0 is
 * Necron's first line; D = "☠ Defeated". A Hypixel change is an edit here.
 */
final class NecronData {
    private NecronData() {
    }

    // ---------------------------------------------------------------- the script (F1)

    static final String L0 = "You went further than any human before, congratulations.";
    /** Lines at N + 0/42/84/126 (35/35 new runs). */
    static final List<String> INTRO = List.of(L0, "I'm afraid, your journey ends now.", "Goodbye.",
        "That's a very impressive trick. I guess I'll have to handle this myself.");
    static final int[] INTRO_AT = {0, 42, 84, 126};
    static final String WITNESS = "WITNESS MY RAW NUCLEAR POWER!";
    static final String SOMETIMES = "Sometimes when you have a problem, you just need to destroy it all and start again.";
    static final int TAUNT_1 = 188;
    /** A second taunt in 11 of 35 runs (the other line or the same: SW 4, WS 3, SS 3, WW 1). */
    static final int TAUNT_2 = 394;
    static final double SECOND_TAUNT = 11 / 35.0;
    /** One-taunt runs: Sometimes 15, WITNESS 9. */
    static final double FIRST_IS_SOMETIMES = 15 / 24.0;
    static final int ARGH = 270;
    static final String ARGH_LINE = "ARGH!";
    static final int SPACE = 332;
    static final String SPACE_LINE = "Let's make some space!";

    // ---------------------------------------------------------------- Necron (F3, F4)

    /** He stands here all fight (a wither, synced health 1.0), spawned 2 ticks before his first line. */
    static final Vec3 HOME = new Vec3(54.0, 66.0, 76.0);
    static final int SPAWN_BEFORE = 2;
    /** Excursions: off-centre and down at 0.25 a tick from N+78 (snap back 90-96) and from N+235 (snap 240). */
    static final int MOVE_1 = 78;
    static final int SNAP_1_MIN = 90;
    static final int SNAP_1_MAX = 96;
    static final int MOVE_2 = 235;
    static final int SNAP_2 = 240;
    static final double MOVE_SPEED = 0.25;
    /** The bar's plateaus and the ticks each gate opens (OPEN 52: what releases a gate isn't known; here time + damage). */
    static final float[] PLATEAUS = {0.8f, 0.25f, 0.05f, 0f};
    static final int[] GATE_AT = {107, 187, 250, 323};
    /** He takes damage from his first snap back (the hit sound from N+89; the bar fills then). */
    static final int DAMAGEABLE = 89;

    // ---------------------------------------------------------------- attacks (F5)

    /** 16 fireballs: 8 from N+19 and 8 from N+175, one per 10 ticks, from his head (y + 2.97). */
    static final int VOLLEY_1 = 19;
    static final int VOLLEY_2 = 175;
    static final int VOLLEY_SIZE = 8;
    static final int VOLLEY_GAP = 10;
    static final double FIRE_Y = 2.97;
    /** 1.072 blocks a tick (median of 527 tracks), 11.5° down, ~25 ticks, then a radius-0 explosion. */
    static final double FIRE_SPEED = 1.072;
    static final double FIRE_PITCH = 11.5;
    static final int FIRE_LIFE = 25;
    /** Headings (Minecraft yaw): due south 220 of 563, the rest the four diagonals. */
    static final float[] FIRE_YAWS = {0f, 45f, -45f, 135f, -135f};
    static final int[] FIRE_YAW_WEIGHTS = {220, 86, 86, 86, 85};
    /** 30 lightning bolts, one per 10 ticks from N+20, round the arena edge (x 16-30 and 79-90, y 59-63). */
    static final int BOLT_FIRST = 20;
    static final int BOLT_LAST = 310;
    /** Nuclear Frenzy pulses (explode v30 p0.49 + wither.ambient v30 p0.7 at him), identical in every run. */
    static final int[] FRENZY = {108, 128, 148, 168, 248, 268, 288, 308};
    /** Two wither skulls in one tick at N+229..237. */
    static final int SKULLS_MIN = 229;
    static final int SKULLS_MAX = 237;
    /** 10 Wither TNT at ARGH + 100, 0.2 up, fuse 61-62; he is removed 20 later, D 44 after the TNT. */
    static final int TNT = ARGH + 100;
    static final int TNT_COUNT = 10;
    static final int TNT_FUSE = 62;
    static final int REMOVED = TNT + 20;
    static final int DEFEATED = TNT + 44;
    /** Death throes from N+310: wither.hurt v15 every 5 ticks (x24), explode v15 p0.49 every 10 (x12). */
    static final int THROES = 310;

    // ---------------------------------------------------------------- the arena (F6)

    /** The south platform: 49 blocks at N+57, 696 at N+59 (p4_blocks.json "south", frames at -2 and 0). */
    static final int SOUTH = 59;
    /** One corner at N+275 (median; 270-292): sectors by how often each broke on main (new script). */
    static final int CORNER = 275;
    static final String[] CORNERS = {"corner_px_pz", "corner_nx_nz", "corner_px_nz", "corner_nx_pz", "north"};
    static final int[] CORNER_WEIGHTS = {11, 7, 5, 5, 7};
    /** The seven lantern columns (3x3, y 59-90) flash from N+21 to N+320 (F5 light columns). */
    static final int[][] LANTERN_COLUMNS = {{26, 75}, {34, 56}, {34, 94}, {53, 48}, {72, 56}, {72, 94}, {80, 75}};
    static final int FLASH_FROM = 21;
    static final int FLASH_TO = 320;
    /** A lit ring every 7 levels moves up a level every 2 ticks and stays lit 5 (two recorded columns). */
    static final int RING_GAP = 7;
    static final int RING_STEP = 2;
    static final int RING_LIT = 5;
}
