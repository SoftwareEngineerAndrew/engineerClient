package com.engineerclient.p3sim.run.plugin

import com.engineerclient.p3sim.run.DungeonPlan
import com.engineerclient.p3sim.pack.runstate.RunStatePack

/**
 * A party strategy preset: how the bots split the clear. The bots module builds a ClearPlan from the class role table and
 * the measured timing tables; a strategy gets to adjust one thing in it, "who goes to the blood room and when". ADS ships
 * only the neutral default (id `"neutral"`, nobody camps; the bots simply finish their rooms). Plug-ins add presets.
 * Implemented by the plug-in; gains members only with defaults.
 */
interface PartyStrategy {
    /** Stable id used in settings and `RunConfig.strategy`. */
    val id: String
    /** Name shown in the settings. */
    val label: String
    /** One plain sentence for the settings tooltip. */
    val description: String get() = ""

    /** Decides the blood-room assignment for one run. Called once when the ClearPlan is built; may be called again on a re-plan. */
    fun bloodAssignment(ctx: StrategyContext): BloodAssignment
}

/** One seat of the party. [index] 0 is the human; bots follow. */
data class Seat(val index: Int, val dungeonClass: String, val isHuman: Boolean)

/** What a strategy sees. May gain members. */
interface StrategyContext {
    val plan: DungeonPlan
    val seats: List<Seat>
    val random: kotlin.random.Random
    /** The run-state tables (class roles, measured timings), or null when that pack is missing. */
    val tables: RunStatePack?
    /** Run ticks (`Run.t`) now; a re-plan passes the current time. */
    val now: Int
}

/**
 * The answer. [seat] is the seat sent to the blood room (null: nobody is routed there); [arriveAtT] the run tick
 * it should be there by (null: let the bots' timing decide); [extras] is a free-form bag a plug-in and its own camp can
 * share (the public bots module ignores keys it does not know).
 */
data class BloodAssignment(val seat: Int?, val arriveAtT: Int? = null, val extras: Map<String, String> = emptyMap()) {
    companion object { val NOBODY = BloodAssignment(null) }
}
