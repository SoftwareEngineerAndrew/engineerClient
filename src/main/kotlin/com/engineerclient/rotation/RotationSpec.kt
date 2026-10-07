package com.engineerclient.rotation

import com.google.gson.GsonBuilder
import org.slf4j.LoggerFactory

/**
 * The M7 phase-3 role rotation, loaded from `resources/rotation/p3.json`.
 *
 * The file is authored in the rotation editor (an artifact page) and shipped verbatim,
 * so the strategy is data, not code. Its execution rules live in [RotationEngine];
 * this file only describes the shapes.
 */
object RotationSpec {

    // Deliberately no Minecraft, Fabric or Odin imports in this file or RotationEngine:
    // the rotation is plain data + plain logic, so it runs under a headless test.
    private val logger = LoggerFactory.getLogger("brw-rotation")

    /** [Role.leapRules] target meaning "this role does not leap". */
    const val NO_LEAP = "none"

    /**
     * [Role.arrived] value meaning "ready as soon as the holder has leapt at all since taking
     * this role" — the section-4 case, where being through the gate is what matters, not
     * standing on a particular block.
     */
    const val ARRIVED_ON_LEAP = "@leap"

    /** One thing a player does. [label] is a human note — the mod never works out *which* terminal. */
    data class Task(
        val type: String = "terminal",
        val label: String = "",
        /**
         * False for jobs a player does that the mod does not wait for: `l+ee2`'s levers and
         * 2nd device, and the 3rd device (the 4th terminal always lands after it).
         */
        val check: Boolean = true,
    )

    /**
     * One leap preference: go to [target], optionally only once [requires] has been handed out.
     * The leap is READY once the holder of [waitFor] (default: the target) has announced arrival
     * at their spot — see [Role.arrived].
     */
    data class LeapRule(val target: String = "", val requires: String? = null, val waitFor: String? = null)

    /** Where a player goes once their role is complete. */
    data class Exit(
        /** `pool` — into whichever pot lists them; `forced` — straight to [to]; `end` — done. */
        val kind: String = "pool",
        val to: String? = null,
    )

    data class Role(
        val id: String = "",
        val section: Int = 1,
        val name: String = "",
        val tasks: List<Task> = emptyList(),
        val note: String = "",
        /**
         * Which of the five colour/sound identities this role signals with, 1-5. The same slot
         * means the same job across sections — every "1st terminal" is slot 1 — and a section's
         * special role (levers, the 5th terminal, an early-enter) takes a spare slot. 0 = infer:
         * a numbered name is its number, anything else is 5.
         */
        val slot: Int = 0,
        /** Early-enter roles are the ones the team leaps to: `l+ee2`, `ee3`, `core`. */
        val early: Boolean = false,
        /** For an early-enter role: the section it opens up, i.e. the section people leap into. */
        val entersSection: Int = 0,
        /**
         * Who to spirit-leap to on taking this role, as ordered preferences — the first rule whose
         * [LeapRule.requires] is satisfied and whose target is actually held wins. Empty means the
         * obvious default: the early-enterer of this section. A single rule targeting [NO_LEAP]
         * means this role does not leap.
         *
         * Section 4 is why this is a list rather than one id: core holds a central spot for the
         * team until a 1st terminal is delegated, after which the 3rd-terminal and lever players
         * leap to the 4th-terminal player instead.
         */
        val leapRules: List<LeapRule> = emptyList(),
        /** Timing or judgement the leap needs that the graph cannot express. */
        val leapNote: String = "",
        /**
         * The party message this role's holder sends on reaching their spot — one of the texts in
         * Odin's positional messages (`/posmsg`), which every teammate runs with the same config.
         * Because it arrives as chat, every client learns of the arrival in the same order.
         * Empty means no such message: readiness then falls back to watching their position.
         * [ARRIVED_ON_LEAP] means any leap announcement made while holding this role.
         */
        val arrived: String = "",
        /**
         * The role is complete when its holder sends [arrived], not on a chat completion line —
         * `core`: "out of core" is what finishes it and drops them into the final pot.
         */
        val completeOnArrived: Boolean = false,
        val exit: Exit = Exit(),
    ) {
        /** Tasks the mod actually waits for. Empty means the role completes on assignment. */
        val checked: List<Task> get() = tasks.filter { it.check }

        /** The slot this role signals with, resolved: explicit, else its number, else 5. */
        val signalSlot: Int get() = when {
            slot in 1..5 -> slot
            name.toIntOrNull() in 1..5 -> name.toInt()
            else -> 5
        }
    }

    data class PotExit(
        /** A role id, or another pot id — pot chains are followed straight through. */
        val targets: List<String> = emptyList(),
        /** Only a player who just finished one of these roles may take this exit. Empty = anyone. */
        val cond: List<String> = emptyList(),
        val note: String = "",
        /** Reserved for the final arrival. Must sit at position 1 or earlier exits take them first. */
        val last: Boolean = false,
        /**
         * Minimum invincibilities the taker must have off cooldown — the sketch's "1 + mask".
         * 0 means no requirement. Like [cond], failing it just skips the exit: you take the next
         * one you do qualify for.
         */
        val mask: Int = 0,
    )

    data class Pot(
        val id: String = "",
        val after: Int = 1,
        val name: String = "",
        val note: String = "",
        /** Roles whose holder drops into this pot when they finish. */
        val eligible: List<String> = emptyList(),
        /** In finish order: the first arrival takes the first exit they qualify for. */
        val exits: List<PotExit> = emptyList(),
    )

    data class Graph(
        val version: Int = 0,
        val roles: List<Role> = emptyList(),
        val pots: List<Pot> = emptyList(),
        /**
         * Recore: once your section-4 role is done you rush into the core for the fight. This is
         * the /posmsg text a player sends on reaching it, having finished; whoever said it first
         * is who everyone else leaps to.
         */
        val recoreArrived: String = "inside core",
    ) {
        // Lazy, not eager: Gson builds the object through Kotlin's synthetic no-arg constructor
        // and only then fills `roles`/`pots` by reflection, so an index built in the constructor
        // would be built from empty lists and every lookup would silently miss.
        private val rolesById by lazy { roles.associateBy { it.id } }
        private val potsById by lazy { pots.associateBy { it.id } }

        fun role(id: String?): Role? = id?.let { rolesById[it] }
        fun pot(id: String?): Pot? = id?.let { potsById[it] }
        fun isPot(id: String): Boolean = potsById.containsKey(id)

        /** The five roles the run starts on. */
        val startingRoles: List<Role> get() = roles.filter { it.section == 1 }

        /** Pots whose eligibility list names this role. */
        fun potsFor(roleId: String): List<Pot> = pots.filter { roleId in it.eligible }

        fun name(id: String?): String = role(id)?.name ?: pot(id)?.name ?: "?"

        /** The early-enter role that opens [section], if the strategy has one. */
        fun earlyEnterInto(section: Int): Role? =
            roles.firstOrNull { it.early && it.entersSection == section }
    }

    private val gson = GsonBuilder().create()

    /** Parsed once at class-load; an unreadable spec leaves an empty graph and the feature simply stays quiet. */
    val graph: Graph by lazy {
        try {
            val text = RotationSpec::class.java.getResourceAsStream("/rotation/p3.json")
                ?.bufferedReader()?.use { it.readText() }
                ?: return@lazy Graph().also { logger.warn("[ec] rotation/p3.json missing from the jar") }
            gson.fromJson(text, Graph::class.java).also {
                logger.info("[ec] rotation spec v${it.version}: ${it.roles.size} roles, ${it.pots.size} pots")
            }
        } catch (t: Throwable) {
            logger.warn("[ec] failed to parse the rotation spec", t)
            Graph()
        }
    }
}
