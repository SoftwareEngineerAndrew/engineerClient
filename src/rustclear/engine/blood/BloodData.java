package net.rustclear.mod.engine.blood;

import java.util.List;

/**
 * Every number of the Watcher's 15-mob camp (blood-watcher.md, main network after the 10-06
 * update), so a Hypixel change is an edit here. Ticks count from the blood door's tick ("door");
 * the Watcher's first line is door + 3 ("t0").
 */
final class BloodData {
    private BloodData() {
    }

    /** The Watcher's first line, after the door line (323/323 camps). */
    static final int T0 = 3;
    /** Counted mobs per camp (the old camp had 19). */
    static final int MOBS = 15;
    /** The kill-count loop: door + 10k ("loop ticks", t0 + 7 mod 10; 2,317/2,317 HP drops). */
    static final int LOOP = 10;
    /** The Watcher's HP after the last count (never 0: 95/98 camps). */
    static final double LAST_HP = 0.1;

    // ---- the wall
    /** Head stands: three rows above the centre's height (floor + 4), 10 a row, 30 a camp (37/40). */
    static final double[] ROWS = {-1.25, 2.75, 6.75};
    /**
     * One row's stands, in the room's frame with +x towards the portal: 2 on each portal-axis side
     * at (±12, ±9), 3 on each other side at (-4/0/4, ±12). The same shape in all 67 full walls.
     */
    static final double[][] ROW = {
        {-12, -9}, {-12, 9}, {12, -9}, {12, 9},
        {-4, -12}, {0, -12}, {4, -12}, {-4, 12}, {0, 12}, {4, 12}};

    // ---- the opening (4 heads: giant, revived boss, normal, normal; 62/69 in that order)
    /** The giant's throw: t0 + 115..121. */
    static final int FIRST_THROW_MIN = T0 + 115;
    static final int FIRST_THROW_SPREAD = 7;
    /**
     * The next three throws after the giant's, about t0 + 160 / 200 / 250 (the notes; landing
     * ticks in 274bd4 198 / 240 / 297 / 360 fit). Each gets up to ±5 ticks.
     */
    static final int[] OPENING_THROWS = {T0 + 160, T0 + 205, T0 + 265};
    static final int OPENING_JITTER = 5;
    /** Head flight to the mob's appearance: 80 for the opening heads, 40 later (77 / 38 measured on a 3-tick sample). */
    static final int FLIGHT_OPENING = 80;
    static final int FLIGHT_LATER = 40;
    /** The Watcher hops to each opening head at about 0.5 b/t and goes home at 0.70. */
    static final double HOP_SPEED = 0.5;
    static final double HOME_SPEED = 0.70;

    // ---- later departures (349/349 FAST, 90/90 SLOW)
    /** No later departure before door + 420 (t0 + 417). */
    static final int FIRST_DEPARTURE = 420;
    /** FAST beats door + 40k (nothing alive), SLOW beats door + 60k (something alive). */
    static final int FAST_BEAT = 40;
    static final int SLOW_BEAT = 60;
    /**
     * The dialogue skip's other side: the first later departure, when FAST (every opening mob already dead), waits
     * while he is talking, a line printed under TALK_AFTER ticks ago or the next one due within TALK_BEFORE
     * (re/critic §3, grid-fitted on 62 camps, medium). Main-15 with the last opening death before door + 420: first
     * move 440 in 6, 480 in 4, 520 in 1; held past 420 it is the SLOW move at 420 in 24 of 26 (blood-watcher.md §6).
     * Later departures ignore chat (316/322).
     */
    static final int TALK_AFTER = 18;
    static final int TALK_BEFORE = 10;
    /**
     * The gate waits two beats at most: the recordings' latest first move with the opening dead early is 520 (440 / 480
     * / 520 = 0 / 1 / 2 holds). Uncapped, the queue's reaction lines kept it shut 11-27 beats (batch 66, camps of 1,400-2,000).
     */
    static final int TALK_MAX_HOLDS = 2;
    static final double FAST_SPEED = 0.6125;
    static final double SLOW_SPEED = 0.4375;
    /**
     * A later head leaves this long after he does (or when he gets there, if sooner): 341 main
     * throws came a median 6.2 blocks short of the head, mostly on beat + 10. The opening heads
     * wait this long after he arrives. He stops this far in front of the stand.
     */
    static final int DWELL = 10;
    static final double STAND_OFF = 1.5;

    // ---- the end
    /** The portal opens 90 (60 of 97 camps) or 80 ticks after the final count. OPEN: 19 */
    static final double PORTAL_90_SHARE = 60 / 97.0;
    /** Portal blocks: 3 wide, 4 high from the floor, 14 blocks out on the side opposite the blood door (112/112). */
    static final int PORTAL_OUT = 14;
    /** The Watcher and his Eye leave about proven + 63..81 (median 75-81). */
    static final int REMOVE_AFTER_PROVEN = 78;
    /** "Proven" waits for the chat queue at most this long after the final count (rule P, never more in any era). */
    static final int PROVEN_MAX_WAIT = 20;

    // ---- chat (blood-watcher.md §7; the queue's minimum gaps)
    static final String INTRO = "Ah, we meet again. As I foresaw...";
    static final List<String> SPAWN = List.of("You'll do.", "Hmmm... this one!", "Go and live again!",
        "This guy looks like a fighter.", "Go, fight!");
    static final List<String> REACT = List.of("Very nice.", "Not bad.", "I'm impressed.", "Aw, I liked that one.",
        "That one was weak anyway.");
    static final String HANDLE = "Let's see how you can handle this.";
    static final List<String> ATTACK = List.of("Stop attacking me.", "That tickles.", "Oof", "Don't make me shoot you.");
    static final String PROVEN = "You have proven yourself. You may pass.";
    /** A spawn line per throw and a reaction per counted kill, each with p 0.5 (the notes; not re-verified). */
    static final double SPAWN_P = 0.5;
    static final double REACT_P = 0.5;
    static final int GAP_SPAWN = 62;
    static final int GAP_REACT = 42;
    /** One attack line at most per this many ticks (the line's own gap is 42; n=7). */
    static final int ATTACK_COOLDOWN = 100;

    /** "§c[BOSS] The Watcher§f: " as Hypixel colours every Watcher line (60 recordings). */
    static String line(String text) {
        return "§c[BOSS] The Watcher§f: " + text;
    }

    // ---- mobs (§4; entity data index 9, F7 main+alpha pooled, M7 alpha)
    /** The 17 normal names; a camp draws its 13 normals without repeats (12-13 names seen in 104 of 114 camps). */
    static final List<String> NORMALS = List.of("Revoker", "Psycho", "Reaper", "Cannibal", "Mute", "Ooze", "Putrid",
        "Freak", "Leech", "Tear", "Parasite", "Flamer", "Skull", "Mr. Dead", "Vader", "Frost", "Walker");
    /** The revived bosses (main-15: Livid 11, Bonzo 10, Scarf 9, Spirit Bear 9: even). */
    static final List<String> BOSSES = List.of("Bonzo", "Scarf", "Livid", "Spirit Bear");
    /** A normal's HP: a whole thousand from 248k to 256k, even (F7, n 1,154); M7 6.0M. */
    static final int NORMAL_MIN_K = 248;
    static final int NORMAL_MAX_K = 256;
    static final double NORMAL_M7 = 6_000_000;
    /** Ooze has 1.25x (312.5k-319k F7, 7.5M M7). */
    static final double OOZE = 1.25;
    /** Healthy is x1.3 in the camp (327k / 252k; 19.5M giants), not the clear's x1.6. */
    static final double HEALTHY = 1.3;
    /**
     * The giant's and the boss's HP: a base times 1.3^k, k = 0, 1, 2, 3, 4 with these weights
     * (F7 non-Healthy giants 48/27/16/6/2, bosses 37/27/16/13/2; pooled). What picks k is
     * unknown. OPEN: 17
     */
    static final double STEP = 1.3;
    static final double[] STEP_WEIGHTS = {0.43, 0.27, 0.16, 0.10, 0.04};
    static final double GIANT_F7 = 15_000_000;
    static final double GIANT_M7 = 400_000_000;
    static final double BOSS_F7 = 10_000_000;
    /** M7 bosses ran 165M-507M (n 23): 165M x 1.3^k covers it. */
    static final double BOSS_M7 = 165_000_000;
    /** The Parasite's three silverfish on death: [Lv79] 33,333 (F7), [Lv255] 1M (M7); never counted. */
    static final int PARASITE_SILVERFISH = 3;

    /** Every camp has one modifier for all its mobs (101/101), about evenly (Speedy 35, Healthy 30, Golden 24, Stealth 23, Stormy 23, Boomer 21 of 156). */
    enum Modifier {
        SPEEDY("Speedy", "§b"),
        HEALTHY("Healthy", "§a"),
        GOLDEN("Golden", "§6"),
        STEALTH("Stealth", "§7"),
        STORMY("Stormy", "§1"),
        BOOMER("Boomer", "§c");

        final String word;
        /** The colour of "<Modifier> <Name>" on the tag (the HP is always §a). */
        final String colour;

        Modifier(String word, String colour) {
            this.word = word;
            this.colour = colour;
        }
    }

    // ---- attacks on the camper (chat lines measured; rates not measured)
    /** Boomer: on death, to players this close. OPEN: radius */
    static final double BOOMER_F7 = 19_440;
    static final double BOOMER_RADIUS = 4;
    /** Stormy: a strike on a player this close, this often. OPEN: rate and range */
    static final double STORMY_F7 = 5_000;
    static final double STORMY_M7 = 70_000;
    static final int STORMY_PERIOD = 80;
    static final double STORMY_RANGE = 6;
    /** The bosses' attacks, this often on the nearest player in range. OPEN: rates and ranges */
    static final double SCARF_F7 = 2_160;
    static final double BONZO_F7 = 6_480;
    static final double LIVID_MIN = 450;
    static final double LIVID_MAX = 1_630;
    static final int BOSS_ATTACK_PERIOD = 60;
    static final double BOSS_ATTACK_RANGE = 12;
}
