package com.engineerclient.p3sim.clear.bots

import com.engineerclient.p3sim.pack.dbl
import com.engineerclient.p3sim.pack.obj
import com.engineerclient.p3sim.pack.runstate.Dist
import com.engineerclient.p3sim.pack.runstate.RunStatePack
import com.engineerclient.p3sim.run.PlacedRoom
import com.engineerclient.p3sim.run.RoomType

/**
 * The run-state tables as the ClearPlan reads them (FORMAT-runstate 3, 4, 5, 9), each with a documented built-in default for
 * when the pack, or just that table, is missing. All durations are server ticks with lag removed (the lag layer re-adds loss at
 * run time; applying it here would count it twice). Pure.
 */
class BotTables(private val pack: RunStatePack?, val floor: String = BotTuning.FLOOR) {
    private val splits get() = pack?.splits?.get(floor)
    private val rolesRaw get() = pack?.classRoles?.raw

    /** True when the pack's tables are being used (false: every number is a built-in default). */
    val fromPack: Boolean get() = pack != null

    // ---- doors, keys, blood ------------------------------------------------------------------------------------------

    /** Ticks after T0 that the [k]-th paid wither door (1-based) falls: the pack's per-door table, else a default that follows its shape. */
    fun doorOpen(k: Int): Dist {
        val o = rolesRaw?.obj("doorOpenAfterT0Ticks")
        val d = o?.get(k.coerceAtMost(6).toString())?.let { Dist.parse(it, pack?.pct ?: Dist.DEFAULT_PCT) }
        if (d != null && !d.advisory) return if (k <= 6) d else shifted(d, (k - 6) * DEFAULT_EXTRA_DOOR_TICKS)
        return PlanMath.around(DEFAULT_DOOR_MEDIANS[(k - 1).coerceIn(0, DEFAULT_DOOR_MEDIANS.size - 1)] + (k - DEFAULT_DOOR_MEDIANS.size).coerceAtLeast(0) * DEFAULT_EXTRA_DOOR_TICKS,
            DOOR_SHAPE)
    }

    /** Ticks from a wither key being held to the door it opens falling (the opener's wait). */
    fun keyToUse(): Dist = pack?.doors?.keyObtainToUse?.takeIf { !it.advisory } ?: PlanMath.around(DEFAULT_KEY_TO_USE, DOOR_SHAPE)

    /** Ticks after T0 that the blood key is picked up. */
    fun bloodKey(): Dist = splits?.bloodKey?.takeIf { !it.advisory } ?: pack?.doors?.bloodKeyAfterStart?.takeIf { !it.advisory } ?: PlanMath.around(DEFAULT_BLOOD_KEY, DOOR_SHAPE)

    /** Ticks from the blood key to the blood door falling. */
    fun bloodDoorAfterKey(): Dist =
        pack?.doors?.bloodDoorAfterKey?.takeIf { !it.advisory } ?: splits?.dists?.get("doorAfterKey")?.takeIf { !it.advisory } ?: PlanMath.around(DEFAULT_DOOR_AFTER_KEY, DOOR_SHAPE)

    // ---- the milestones the gate compares ---------------------------------------------------------------------------

    /** Blood door open (ticks after T0), as recorded. */
    fun recordedBloodOpen(): Dist? = splits?.bloodOpen
    fun recordedWatcherDone(): Dist? = splits?.watcherDone
    fun recordedMaxorEntry(): Dist? = splits?.maxorEntry
    fun recordedTilesDone(): Dist? = splits?.tilesDoneAtMaxor
    fun recordedGreenFrac(): Dist? = splits?.roomsGreenFracAtMaxor

    /** bloodOpen to the Watcher's "proven" line: the camp length as a number, not a mechanism (the blood module owns that). */
    fun campTicks(): Dist = splits?.campTicks?.takeIf { !it.advisory } ?: PlanMath.around(DEFAULT_CAMP_TICKS, CAMP_SHAPE)

    /** "Proven" to the first boss line. */
    fun provenToMaxor(): Dist = splits?.provenToMaxor?.takeIf { !it.advisory } ?: PlanMath.around(DEFAULT_PROVEN_TO_MAXOR, CAMP_SHAPE)

    /** The share of runs in which the blood room is GREEN (not RED) at Maxor. */
    fun bloodGreenFrac(): Double = splits?.raw?.obj("perType")?.obj("BLOOD")?.dbl("greenFrac") ?: DEFAULT_BLOOD_GREEN_FRAC

    // ---- rooms -------------------------------------------------------------------------------------------------------

    /**
     * First entry to GREEN for a room, for a seat of class [cls]: the room's own class table when it has 8 samples or more, else the
     * room's pooled table, else the room type's table, else a default by type.
     */
    fun roomDur(room: PlacedRoom, cls: String?): Dist {
        val rr = pack?.room(room.name)
        if (cls != null) rr?.byClass?.get(cls)?.takeIf { !it.advisory }?.let { return it }
        rr?.dur?.takeIf { !it.advisory }?.let { return it }
        return typeDur(room.type)
    }

    /** NONE to WHITE for a room with secrets (the mobs are dead), or null when nothing is measured for it. */
    fun roomToWhite(room: PlacedRoom): Dist? =
        pack?.room(room.name)?.toWhite?.takeIf { !it.advisory } ?: pack?.checkStates?.types?.get(room.type.name.lowercase())?.ticks?.get("NtoW")?.takeIf { !it.advisory }

    /** WHITE to GREEN for a room, or null. */
    fun roomToGreen(room: PlacedRoom): Dist? =
        pack?.room(room.name)?.toGreen?.takeIf { !it.advisory } ?: pack?.checkStates?.types?.get(room.type.name.lowercase())?.ticks?.get("WtoG")?.takeIf { !it.advisory }

    private fun typeDur(type: RoomType): Dist {
        val key = when (type) { RoomType.YELLOW -> "CHAMPION"; else -> type.name }
        return pack?.roomTypeDur?.get(key)?.takeIf { !it.advisory } ?: PlanMath.around(DEFAULT_TYPE_DUR[type] ?: 400.0)
    }

    // ---- classes -----------------------------------------------------------------------------------------------------

    /** Rooms-owned weight of a class for the clear rooms (normal, puzzle, trap, champion; the blood room is the camp's, not a clear room). */
    fun classOwned(cls: String): Double {
        val by = rolesRaw?.obj(cls)?.obj("ownedByType")
        if (by != null) return listOf("NORMAL", "PUZZLE", "TRAP", "CHAMPION").sumOf { by.dbl(it) ?: 0.0 }.takeIf { it > 0 } ?: DEFAULT_CLASS_OWNED
        return pack?.classRoles?.classes?.get(cls)?.roomsOwnedMean?.takeIf { it > 0 } ?: DEFAULT_CLASS_OWNED
    }

    /** How good a class is at puzzles, 0..1 (its puzzle share relative to the best class). */
    fun puzzleAffinity(cls: String): Double {
        val roles = pack?.classRoles?.classes ?: return 1.0
        val best = roles.values.maxOfOrNull { it.puzzlesOwnedMean } ?: 0.0
        if (best <= 0.0) return 1.0
        return ((roles[cls]?.puzzlesOwnedMean ?: best) / best).coerceIn(0.0, 1.0)
    }

    /** Share of games the class opened the wither doors. */
    fun doorOpenerShare(cls: String): Double = pack?.classRoles?.classes?.get(cls)?.doorOpenerFrac ?: DEFAULT_OPENER_SHARE[cls] ?: 0.0

    /** Planning speed of a class while moving, blocks per tick (hops included). */
    fun moveBpt(cls: String): Double = (pack?.classRoles?.classes?.get(cls)?.movingSpeedBpt ?: BotTuning.DEFAULT_PLAN_BPT).coerceIn(0.4, BotTuning.MAX_TRAVEL_BPT)

    private fun shifted(d: Dist, by: Double) = Dist(d.n, d.pct, DoubleArray(d.q.size) { d.q[it] + by }, d.min?.plus(by), d.max?.plus(by))

    companion object {
        // Built-in defaults when a table is missing: round numbers shaped like the measured ones, UNMEASURED as defaults.
        val DEFAULT_DOOR_MEDIANS = doubleArrayOf(60.0, 140.0, 210.0, 265.0, 315.0, 365.0)
        const val DEFAULT_EXTRA_DOOR_TICKS = 50.0
        val DOOR_SHAPE = doubleArrayOf(0.55, 0.65, 0.8, 1.0, 1.3, 1.7, 2.2)
        const val DEFAULT_KEY_TO_USE = 6.0
        const val DEFAULT_BLOOD_KEY = 300.0
        const val DEFAULT_DOOR_AFTER_KEY = 6.0
        // INVENTED stand-ins for the blood room's length and the wait after it: round numbers with a symmetric spread, not measured. A real
        // run reads both from the local data pack; these only keep the planner working without one.
        const val DEFAULT_CAMP_TICKS = 1000.0
        const val DEFAULT_PROVEN_TO_MAXOR = 100.0
        val CAMP_SHAPE = doubleArrayOf(0.8, 0.85, 0.9, 1.0, 1.1, 1.15, 1.2)
        const val DEFAULT_BLOOD_GREEN_FRAC = 0.47
        const val DEFAULT_CLASS_OWNED = 3.5
        /** Without the class table every class is as likely to be the door crew. */
        val DEFAULT_OPENER_SHARE = mapOf("HEALER" to 0.2, "ARCHER" to 0.2, "MAGE" to 0.2, "BERSERK" to 0.2, "TANK" to 0.2)
        val DEFAULT_TYPE_DUR = mapOf(
            RoomType.NORMAL to 490.0, RoomType.PUZZLE to 280.0, RoomType.TRAP to 220.0, RoomType.YELLOW to 30.0,
            RoomType.RARE to 235.0, RoomType.FAIRY to 0.0, RoomType.ENTRANCE to 0.0, RoomType.BLOOD to 1100.0,
        )
    }
}
