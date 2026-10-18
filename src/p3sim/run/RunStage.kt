package com.engineerclient.p3sim.run

/**
 * The stages of a full run, in order. A run is one continuous server session of the ADS world; only the stage changes.
 *
 * ```
 * COUNTDOWN -> CLEAR -> BLOOD -> BOSS_ENTRY -> BOSS -> END
 * ```
 * - [COUNTDOWN]: from the run's creation to Mort's first line (`T0`): class lines, "Starting in N seconds", the entrance door.
 *   The run clock `Run.t` is negative here.
 * - [CLEAR]: from `T0` until the blood door opens: rooms, doors, keys, mobs, secrets, puzzles, the score.
 * - [BLOOD]: from the blood door opening until the Watcher says "You may pass." and the party stands ready to enter the boss.
 * - [BOSS_ENTRY]: the one-tick jump to P1 and the ticks around it. Lasts a few ticks.
 * - [BOSS]: the existing P1-P4 boss sim ([com.engineerclient.p3sim.Fight.Phase]) runs; the run only watches. Odin's bridge forces
 *   "in boss" only in this stage.
 * - [END]: the boss is dead (or the run was stopped); the end screen is up.
 *
 * Today's "start the boss directly" flow (the ADS menu's P1..P4) has NO Run at all: `Runs.current` is null and every
 * module must cope with that. That keeps the boss practice exactly as it was.
 */
enum class RunStage {
    COUNTDOWN, CLEAR, BLOOD, BOSS_ENTRY, BOSS, END;

    /** Whether the world is "in boss" for Odin (its map and room scanning stop, its boss features run). */
    val inBoss: Boolean get() = this == BOSS_ENTRY || this == BOSS

    /** Whether the dungeon's clear surfaces (sidebar, tab, map, mobs, doors) are live. */
    val inDungeon: Boolean get() = this == COUNTDOWN || this == CLEAR || this == BLOOD

    companion object {
        /**
         * Forward by exactly one stage, or to [END] from anywhere before it. A savepoint restore may go back (the engine
         * does that itself, not through this check).
         */
        fun canGo(from: RunStage, to: RunStage): Boolean =
            (to == END && from != END) || (to.ordinal == from.ordinal + 1 && to != END)
    }
}
