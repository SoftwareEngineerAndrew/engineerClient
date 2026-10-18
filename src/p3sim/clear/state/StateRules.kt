package com.engineerclient.p3sim.clear.state

import com.engineerclient.p3sim.pack.layouts.DoorKind
import com.engineerclient.p3sim.pack.runstate.ChestClear
import com.engineerclient.p3sim.pack.runstate.Dist
import com.engineerclient.p3sim.pack.runstate.RunStatePack
import com.google.gson.JsonObject

/**
 * The numbers the state module runs on, resolved once per run: the run-state pack's measured values where it has them, the
 * documented defaults (the same figures, written down here) where the pack, or a section of it, is missing. Pure.
 *
 * Every default is MEASURED unless its comment says otherwise (FORMAT-runstate section 4 and addendum A).
 */
class StateRules(
    /** Ticks from the "Starting in 1 second" line to Mort's first line (T0). 20 measured (19 in 19 %, 20 in 61 %, 21 in 18 %). */
    val startLeadTicks: Int = START_LEAD_TICKS,
    /** Ticks from the "Starting in 1 second" line until the entrance door turns to barrier. Median 22 (21 to 25). */
    val entranceFallAfterStart1: Int = ENTRANCE_FALL_AFTER_START1,
    /** The free Fairy door falls this many ticks after the entrance door (median -1: one tick before). */
    val freeDoorOffset: Int = FREE_DOOR_OFFSET,
    /** Ticks a door of each kind stays barrier before it turns to air (wither 14, blood 14, entrance 15; the free Fairy door is a wither door). */
    val barrierTicks: Map<DoorKind, Int> = DEFAULT_BARRIER,
    /** Ticks after the open (the chat line) before a wither or blood door turns to barrier: 1 (171 of 176 measured). The entrance and free Fairy doors write at once. */
    val keyDoorBarrierDelay: Int = KEY_DOOR_BARRIER_DELAY,
    /** Ticks from the first barrier block to the other 35 (0: all 36 in one tick, 96.7 % of doors). */
    val firstBlockToRestTicks: Int = 0,
    /** A key stand nobody takes is given to the nearest player after this many ticks (a safety, essentially never reached). */
    val autoCollectTicks: Int = 200,
    /** Share of key stands picked up in the tick they appear (the killer has them at once). Measured 0.669. */
    val pickupInstantFrac: Double = PICKUP_INSTANT_FRAC,
    /**
     * How far under the floor surface (the top of the floor block) a key stand sits: 0.46875, so 68.53125 over a floor whose surface is
     * y 69 (the door bodies start at y 69, the walking level). 71 % of measured stands sit at y 68.53125 and the others step by whole blocks
     * with the room floor. INFERRED: the pack's note says "mob feet + 0.53125", which would put the floor surface at 68; the door
     * geometry says 69. Only the mode (68.53125) is measured.
     */
    val standYBelowSurface: Double = STAND_Y_BELOW_SURFACE,
    /** The y a stand gets when the floor cannot be found. */
    val standYDefault: Double = STAND_Y_DEFAULT,
    /** Ticks the entrance room takes from NONE to GREEN (median 5). */
    val entranceNtoG: Int = ENTRANCE_N_TO_G,
    /** Room dwell-to-white tables, for the rooms the mobs module gave no required mob (synthesised clears). */
    private val checkTicks: Map<String, Dist> = emptyMap(),
    private val roomToWhite: (String) -> Dist? = { null },
    /** The chest that finishes each trap room, by room name (the pack's `chestClears`). A trap room without an entry finishes on the pooled timer instead. */
    val chestClears: Map<String, ChestClear> = emptyMap(),
    /**
     * Ticks from the Watcher's pass line to the blood room's green check when the blood module does not say when its portal opens:
     * 90, the median of the measured gap from the Watcher's last kill (the check came 80 to 94 ticks after it, 1 to 5 after the portal blocks).
     */
    val bloodGreenAfterPass: Int = BLOOD_GREEN_AFTER_PASS,
    /** Ticks from the blood portal opening to the green check, when the blood module announces the portal: 1 (measured -1 to 4, median 1). */
    val bloodGreenAfterPortal: Int = BLOOD_GREEN_AFTER_PORTAL,
) {
    /** Ticks a door of [kind] holds barrier. */
    fun barrierFor(kind: DoorKind): Int = barrierTicks[kind] ?: barrierTicks[DoorKind.WITHER] ?: 14

    /** The chest rule of the trap room called [roomName], or null when the pack has none (or none that names a cell). */
    fun chestClear(roomName: String): ChestClear? = chestClears[roomName.replace("\u200b", "")]?.takeIf { it.cell != null }

    /** Ticks from the first opening of a trap room's end chest to its white mark, sampled with [u] in `[0, 1]` (measured 0 to 5, median 2). */
    fun chestOpenDelay(roomName: String, u: Double): Int =
        Math.round(chestClear(roomName)?.afterOpen?.takeUnless { it.advisory }?.sample(u) ?: CHEST_OPEN_DELAY_TICKS).toInt().coerceAtLeast(0)

    /** Ticks after a room turns NONE at which its objective is considered done, sampled with [u] in `[0, 1]` (a room with no required mob). */
    fun whiteDelay(roomName: String, type: String, u: Double): Int {
        val d = roomToWhite(roomName)?.takeUnless { it.advisory }
            ?: checkTicks["${type.lowercase()}.NtoW"]?.takeUnless { it.advisory }
            ?: checkTicks["${type.lowercase()}.NtoG"]?.takeUnless { it.advisory }
        return Math.round(d?.sample(u) ?: SYNTH_WHITE_TICKS).toInt().coerceAtLeast(1)
    }

    companion object {
        const val START_LEAD_TICKS = 20
        const val ENTRANCE_FALL_AFTER_START1 = 22
        const val FREE_DOOR_OFFSET = -1
        const val KEY_DOOR_BARRIER_DELAY = 1
        /** The falling-block animation of an opening door (client-state): the blocks ride bats from +1, the bats sink from +7, all gone at +23. */
        const val DEBRIS_RIDE_DELAY = 1
        const val DEBRIS_SINK_START = 7
        const val DEBRIS_REMOVE = 23
        /** Height of the bat above the block's floor (y of the cell + this). */
        const val DEBRIS_DY = 0.34375
        /** The bats drop 0.306 at the first sink tick, then 0.3125 a tick (about 4 blocks in all; recorded as 0.9375 every 3 ticks). */
        const val DEBRIS_FIRST_DROP = 0.306
        const val DEBRIS_DROP_PER_TICK = 0.3125
        const val DEBRIS_LAST_DROP_TICK = 19
        /** Key model stand: spin in degrees a tick, and the bob (a triangle wave of 0.01 a tick between +0.04 and +0.22, period 36). */
        const val STAND_SPIN_DEG = 2.0f
        const val STAND_BOB_MIN = 0.04
        const val STAND_BOB_STEP = 0.01
        const val STAND_BOB_HALF_PERIOD = 18
        /** The model stand sits this far under the name stand. */
        const val MODEL_STAND_BELOW = 0.25
        /** The name stand outlives the model stand by this many ticks when a key is picked up. */
        const val NAME_STAND_LINGER = 2
        const val PICKUP_INSTANT_FRAC = 0.669
        const val STAND_Y_BELOW_SURFACE = 0.46875
        const val STAND_Y_DEFAULT = 68.53125
        const val ENTRANCE_N_TO_G = 5
        /** The gap from opening a trap room's end chest to the white mark when the pack gives no table: 2 (measured 0 to 5, median 2). */
        const val CHEST_OPEN_DELAY_TICKS = 2.0
        /** Ticks from the Watcher's pass line to the blood room's green check without a portal notice from the blood module (see [bloodGreenAfterPass]). */
        const val BLOOD_GREEN_AFTER_PASS = 90
        const val BLOOD_GREEN_AFTER_PORTAL = 1
        /** UNMEASURED fallback: the pooled NORMAL room median of NONE to WHITE (50 ticks) when the pack has no table. */
        const val SYNTH_WHITE_TICKS = 50.0
        val DEFAULT_BARRIER: Map<DoorKind, Int> = mapOf(DoorKind.WITHER to 14, DoorKind.FAIRY to 14, DoorKind.BLOOD to 14, DoorKind.ENTRANCE to 15)

        /** The rules from the pack (null pack = all defaults). A field the pack lacks keeps its default. */
        fun fromPack(pack: RunStatePack?): StateRules {
            val doors = pack?.doors
            val raw: JsonObject? = doors?.raw
            val fall = raw?.getAsJsonObject("fall")
            val modes = HashMap(DEFAULT_BARRIER)
            // `barrierTicksByKind.<kind>.ticks` maps a tick count to its share; the mode is the figure the sim uses.
            fun mode(kind: String): Int? = fall?.getAsJsonObject("barrierTicksByKind")?.getAsJsonObject(kind)?.getAsJsonObject("ticks")
                ?.entrySet()?.maxByOrNull { it.value.asDouble }?.key?.toIntOrNull()
            mode("wither")?.let { modes[DoorKind.WITHER] = it; modes[DoorKind.FAIRY] = it }
            mode("blood")?.let { modes[DoorKind.BLOOD] = it }
            mode("entrance")?.let { modes[DoorKind.ENTRANCE] = it }
            val offset = raw?.getAsJsonObject("entrance")?.getAsJsonObject("freeFairyOffset")?.let { Dist.parse(it, Dist.DEFAULT_PCT)?.median?.let { m -> Math.round(m).toInt() } }
            val ticks = HashMap<String, Dist>()
            pack?.checkStates?.types?.forEach { (type, ts) -> ts.ticks.forEach { (k, d) -> ticks["$type.$k"] = d } }
            return StateRules(
                startLeadTicks = doors?.startLeadTicks ?: START_LEAD_TICKS,
                entranceFallAfterStart1 = doors?.entranceFallAfterStart1?.median?.let { Math.round(it).toInt() } ?: ENTRANCE_FALL_AFTER_START1,
                freeDoorOffset = offset ?: FREE_DOOR_OFFSET,
                barrierTicks = modes,
                keyDoorBarrierDelay = KEY_DOOR_BARRIER_DELAY,
                firstBlockToRestTicks = fall?.get("firstBlockToRestTicks")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
                autoCollectTicks = doors?.autoCollectTicks ?: 200,
                pickupInstantFrac = doors?.pickupInstantFrac ?: PICKUP_INSTANT_FRAC,
                checkTicks = ticks,
                roomToWhite = { name -> pack?.room(name)?.toWhite },
                chestClears = pack?.chestClears.orEmpty(),
            )
        }
    }
}

/** The few things a player may change (settings) or a test sets, kept out of the measured [StateRules]. */
data class StateConfig(
    /** Ticks from the run's start to the "Starting in 4 seconds" line: the potions line on arrival comes a median 57 ticks before it (ADR, 260 runs; p25 51, p75 64). A setting. */
    val countdownDelayTicks: Int = 60,
    /** How close (blocks) a party member has to be to take a key stand. UNMEASURED (63 to 67 % are taken in the tick the stand appears, the rest after a median 8 ticks). */
    val pickupRadius: Double = 2.5,
    /** Rooms with no required mob turn white on the measured NONE to WHITE schedule (the pack's SYNTHESISE_ROOM_CLEARS). */
    val synthesiseClears: Boolean = true,
    /** Ticks after a key room's objective is done before a key is dropped at the room's centre, if the mobs module has not dropped one. */
    val keyFallbackTicks: Int = 5,
)
