package com.engineerclient.p3sim.clear.bots

/**
 * Every number the bots module uses that the data packs do not measure, in one place. Each is a named, documented constant
 * (UNMEASURED unless the comment says where it comes from); the ones a player would want to change are also settings
 * (see [BotsModule.registerSettings]). Pure: no Minecraft, no Odin.
 */
object BotTuning {
    // ---- movement ---------------------------------------------------------------------------------------------------

    /**
     * Visible walking speed of a bot, blocks per tick (MEASURED in the boss sim: teammates' sustained ground speed p10/p50/p90 is
     * 0.60 / 0.85 / 1.21 in recorded P3). A leg that must go faster than [HOP_ABOVE_BPT] is played as blink hops instead.
     */
    const val WALK_BPT = 0.85

    /** A leg that needs more than this speed to make its scheduled time is played as hops of [HOP_BLOCKS] (a Hyperion blink is 10 blocks). */
    const val HOP_ABOVE_BPT = 1.25

    /** One blink hop, blocks (MEASURED in the boss sim, `Party.BLINK`). */
    const val HOP_BLOCKS = 10.0

    /** The fastest a bot is ever planned to cover ground, blocks per tick (UNMEASURED: roughly 1.2 blocks a tick walking plus a blink every few seconds). */
    const val MAX_TRAVEL_BPT = 3.0

    /** Planning speed when the run-state pack has no class speeds: the pack's moving speed is 1.2 blocks a tick for every class, hops included. */
    const val DEFAULT_PLAN_BPT = 1.2

    /** Ticks a door needs after its first block turns to barrier before a walker may pass (the barrier stands 14 ticks, MEASURED, FORMAT-runstate 4). */
    const val DOOR_PASSABLE_AFTER = 15

    // ---- the door and key schedule ------------------------------------------------------------------------------------

    /**
     * How strongly one "pace of this party" draw moves every door, key and blood time of a run together (a fast party is fast
     * everywhere). 0 = independent draws, 1 = one draw for all. UNMEASURED; the marginal distributions are exact for any value.
     */
    const val PACE_CORRELATION = 0.85

    /** The same idea for room durations: how much a party that is fast at the doors is also fast in the rooms. UNMEASURED. */
    const val ROOM_PACE_CORRELATION = 0.25

    /** Fewest ticks between two paid doors falling (the opener has to walk and the next key has to drop). UNMEASURED. */
    const val MIN_DOOR_GAP = 12

    /** Fewest ticks between a room becoming reachable and its required mobs being dead (arrive, clear). UNMEASURED. */
    const val MIN_CLEAR_TICKS = 8

    /** Ticks before a door falls that its opener stands at the door. UNMEASURED. */
    const val OPENER_LEAD = 6

    /** Chance that a layout's doors are opened by two bots, not one (MEASURED: one player opens all doors in 58% of games, two in 36%, three in 5%). */
    const val SECOND_OPENER_CHANCE = 0.42

    /**
     * Chance that the bot that kills a key room's last mob and takes the key is not the bot that opens the door (MEASURED: pairing the
     * i-th "has obtained Wither Key!" with the i-th "opened a WITHER door!" of a game, 1132 of 1354 pairs in five-player F7 runs name two
     * different players).
     */
    const val KEY_SPLIT_CHANCE = 0.84

    /**
     * How far from the door's gap the opener stands when the door falls, blocks, as quantiles of a uniform draw (MEASURED: the opener's
     * distance to the door is p5 0.7, p25 1.6, p50 3.1, p75 4.7; the farther tail is not reproduced because a door only opens within
     * [OPEN_REACH]). Pairs of (share of draws, distance).
     */
    val OPENER_STAND_BLOCKS: List<Pair<Double, Double>> = listOf(0.0 to 0.7, 0.2 to 1.6, 0.5 to 3.1, 1.0 to 4.7)

    /** A door opens only while its opener stands within this many blocks of the gap; a late walker delays the door instead of opening it from across the room. */
    const val OPEN_REACH = 5.0

    /** Ticks between the opener arriving at the door and the door falling, when the walk made it late. UNMEASURED. */
    const val OPEN_SETTLE = 2

    // ---- the rooms --------------------------------------------------------------------------------------------------

    /**
     * Multiplies every sampled room duration to get how long a seat is busy in the room. CALIBRATED, not measured: the pack's room
     * durations run from the first entry of anyone to the room turning green, and teammates join a room part-way, so one seat's own
     * time in it is shorter. The headless gate ([ClearSim]) against the real pack sweeps this: tiles done at Maxor are right (34, 34, 34,
     * 35, 35) from about 0.8 down, and the share of rooms green at Maxor (median 0.81) only comes out at 0.4. It was 0.5 while the
     * door crew idled through every key room; now a bot visits each key room as a normal room (its mobs die when the key is due, its
     * secrets follow), that time is real work and the scale that matches the recordings is lower, and lower again (0.3) now that the walk
     * between rooms goes round what is in the rooms (the floors are read from the rooms' blocks), which makes the walks longer. Re-run the gate after changing a table.
     */
    const val ROOM_WORK_SCALE = 0.3

    /** Ticks added to a puzzle room for a bot that is not good at puzzles, per unit of missing affinity (class puzzle-share table). UNMEASURED. */
    const val PUZZLE_AFFINITY_PENALTY = 120.0

    /** Longest a class's room-time multiplier may move away from 1.0 either way (the class tables are noisy). UNMEASURED. */
    val CLASS_SCALE_RANGE = 0.75..1.35

    /** A room whose design has no secret positions still takes the seat a moment at the room's middle. */
    const val MIN_ROOM_DWELL = 20

    /** Fewest ticks a green-only task takes (a room that is already white and only needs its secrets). UNMEASURED. */
    const val MIN_GREEN_TASK = 60

    /** Default floor of the pack's tables. */
    const val FLOOR = "F7"

    // ---- when the player is in the way -------------------------------------------------------------------------------

    /**
     * Ticks after a room's scheduled start that the human's share of it is still the human's; after that bots cover it
     * (setting "Bots Cover After"). UNMEASURED.
     */
    const val COVER_AFTER_TICKS = 600

    /** Longest a bot holds back its kills while the human is standing in the room (setting). UNMEASURED. */
    const val HUMAN_ROOM_GRACE_TICKS = 400

    /** Fewest ticks between two re-plans (several events in the same moment make one re-plan). */
    const val REPLAN_DEBOUNCE = 10

    // ---- bodies -----------------------------------------------------------------------------------------------------

    /** A bot's body is spawned when a human is within this many blocks (setting "Bots Show Distance"); bots farther away exist only in the plan. */
    const val BODY_RADIUS = 72.0

    /** Extra blocks beyond [BODY_RADIUS] before a body is removed again (so a bot on the edge does not flicker). */
    const val BODY_HYSTERESIS = 12.0

    /** The floor height a bot stands at when nothing is loaded to ask: Hypixel's dungeon floor is block y 68, so feet at 69. */
    const val DEFAULT_FLOOR_Y = 69.0
}
