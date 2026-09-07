package com.bloodrushwaypoints.rotation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the real engine over random finish schedules — the same check that validated the
 * rotation before any of it was written, so a spec edit that breaks the strategy fails here
 * rather than in a run.
 *
 * A "schedule" is an order in which the five players happen to finish their current role.
 * Because pots hand out roles by arrival order, every schedule is a different path through
 * the graph, and the strategy has to survive all of them.
 */
class RotationEngineTest {

    private val graph get() = RotationSpec.graph
    private val players = listOf("Alpha", "Bravo", "Charlie", "Delta", "Echo")

    private fun bindings(): Map<String, String> =
        graph.startingRoles.mapIndexed { i, role -> role.id to players[i] }.toMap()

    /**
     * Plays one whole phase 3, finishing a random live player's role at each step, and returns
     * every role that was held. Read from the engine's history rather than from live holders:
     * `l+ee2` and `core` have nothing to wait for, so they are assigned and completed within a
     * single step and would never be caught in a snapshot.
     */
    private fun playOne(rng: Random): Set<String> {
        RotationEngine.begin(bindings())
        repeat(200) {
            val live = RotationEngine.tracked()
            if (live.isEmpty()) return heldRoles()
            val holder = live[rng.nextInt(live.size)]
            // Land every outstanding task for that player; the last one completes the role.
            holder.remaining.toList().forEach { type -> RotationEngine.onTaskDone(holder.ign, type) }
        }
        return heldRoles()
    }

    private fun heldRoles(): Set<String> =
        players.flatMap { RotationEngine.historyOf(it) }.toSet()

    @Test
    fun `spec loads`() {
        assertEquals(23, graph.roles.size, "role count")
        assertEquals(7, graph.pots.size, "pot count")
        assertEquals(5, graph.startingRoles.size, "the run starts on five roles")
    }

    @Test
    fun `every schedule completes with nobody stranded`() {
        val rng = Random(20260905)
        val coreRuns = mutableListOf<Boolean>()
        repeat(20_000) {
            val held = playOne(rng)
            assertNull(RotationEngine.stuck, "a player was left with no exit: ${RotationEngine.stuck}")
            assertTrue(RotationEngine.tracked().isEmpty(), "the run ended with players still holding roles")
            coreRuns += "r_core" in held
        }
        assertTrue(coreRuns.all { it }, "core went unassigned in ${coreRuns.count { !it }} of ${coreRuns.size} runs")
    }

    @Test
    fun `every role is reachable`() {
        val rng = Random(7)
        val seen = mutableSetOf<String>()
        repeat(3_000) { seen += playOne(rng) }
        val never = graph.roles.filter { it.id !in seen }.map { it.name }
        assertTrue(never.isEmpty(), "roles never assigned in any schedule: $never")
    }

    @Test
    fun `core is an early enter role that skips section three`() {
        val core = graph.role("r_core")!!
        assertTrue(core.early, "core is one of the roles the team leaps to")
        assertTrue(core.checked.isEmpty(), "core has no task to wait for — it completes on assignment")
        // Its exits reach section 4 without ever handing out a section-3 role.
        assertEquals(2, core.section, "core is picked up in section 2")
    }

    @Test
    fun `the whole phase inventory is owned exactly once`() {
        var terminals = 0
        var levers = 0
        var devices = 0
        graph.roles.forEach { role ->
            role.tasks.forEach {
                when (it.type) {
                    "terminal" -> terminals++
                    "lever" -> levers++
                    "device" -> devices++
                }
            }
        }
        assertEquals(17, terminals, "4 + 5 + 4 + 4 terminals across the four sections")
        assertEquals(8, levers, "two levers per section")
        assertEquals(4, devices, "one device per section")
    }

    @Test
    fun `the mask branch holds however few masks the party has`() {
        val maskExit = graph.pots.flatMap { it.exits }.count { it.mask > 0 }
        assertEquals(1, maskExit, "the sketch has exactly one mask-gated exit")

        // Nobody qualifying must not stall the board: the requirement is a preference, and the
        // engine takes the exit anyway rather than leaving the last arrival with nowhere to go.
        listOf<(String) -> Int>(
            { 3 },                                   // everyone kitted, the normal case
            { ign -> if (ign == "Alpha") 1 else 3 },  // one player below the requirement
            { 1 },                                   // party best is 1, so 1 becomes the bar
            { 0 },                                   // party is dry; the bar drops to none
        ).forEach { masks ->
            RotationEngine.masksAvailable = masks
            val rng = Random(99)
            repeat(2_000) {
                playOne(rng)
                assertNull(RotationEngine.stuck, "stranded with masks=${masks("Alpha")}: ${RotationEngine.stuck}")
                assertTrue(RotationEngine.tracked().isEmpty(), "players left holding roles")
            }
        }
        RotationEngine.masksAvailable = { 3 }
    }

    @Test
    fun `leap targets follow the strategy, including section four's hand-over`() {
        val rng = Random(4242)
        var sawCoreHold = false
        var sawHandOver = false

        repeat(3_000) {
            RotationEngine.begin(bindings())
            repeat(200) {
                val live = RotationEngine.tracked()
                if (live.isEmpty()) return@repeat

                live.forEach { holder ->
                    val role = graph.role(holder.roleId)!!
                    val target = RotationEngine.leapTargetFor(holder.ign)

                    // Nobody is ever told to leap to themselves.
                    assertTrue(target == null || !target.equals(holder.ign, true), "${role.name} leaps to itself")

                    // core walks through; it never leaps.
                    if (role.id == "r_core") assertNull(target, "core should not leap")

                    // Section 4's 3rd terminal and levers: core until a 1st terminal exists,
                    // then the 4th-terminal player.
                    if (role.id == "r_4_3" || role.id == "r_4_l") {
                        val firstTerm = RotationEngine.tracked().find { it.roleId == "r_4_1" }
                        val fourthTerm = RotationEngine.tracked().find { it.roleId == "r_4_4" }
                        if (firstTerm != null && fourthTerm != null) {
                            assertEquals(fourthTerm.ign, target, "${role.name} should hand over to the 4th terminal")
                            sawHandOver = true
                        } else if (firstTerm == null && target != null) {
                            sawCoreHold = true
                        }
                    }
                }

                val holder = live[rng.nextInt(live.size)]
                holder.remaining.toList().forEach { type -> RotationEngine.onTaskDone(holder.ign, type) }
            }
        }
        assertTrue(sawHandOver, "never observed the section-4 hand-over to the 4th terminal")
        assertTrue(sawCoreHold, "never observed core holding the leap target before a 1st terminal existed")
    }

    @Test
    fun `a leap turns ready on the arrival message, not before`() {
        RotationEngine.begin(bindings())
        val ee2 = bindings()["r_lee2"]!!
        // Pot 1 exit 2 hands out role 2 (2nd terminal s2); pick whoever got it.
        val pot1 = graph.pot("p_1")!!
        val starters = bindings().filterKeys { it in pot1.eligible }.values.toList()
        starters.forEach { ign -> RotationEngine.roleOf(ign)!!.checked.forEach { RotationEngine.onTaskDone(ign, it.type) } }

        val onRole2 = RotationEngine.tracked().first { it.roleId == "r_2_2" }.ign
        val onRole1 = RotationEngine.tracked().first { it.roleId == "r_2_1" }.ign

        // Role 2 leaps to ee2, and is ready once ee2 has announced.
        assertEquals(ee2, RotationEngine.leapTargetFor(onRole2))
        assertEquals(false, RotationEngine.leapReadyFor(onRole2), "nobody has announced yet")
        RotationEngine.onPartyMessage(ee2, "ee2")
        assertEquals(true, RotationEngine.leapReadyFor(onRole2))

        // Role 1 also leaps to ee2 — but waits on the 2nd-terminal player, not on ee2.
        assertEquals(ee2, RotationEngine.leapTargetFor(onRole1))
        assertEquals(false, RotationEngine.leapReadyFor(onRole1), "ee2 being through is not enough for the 1st terminal")
        RotationEngine.onPartyMessage(onRole2, "2nd term s2")
        assertEquals(true, RotationEngine.leapReadyFor(onRole1))

        // Case and whitespace in the announcement must not matter.
        RotationEngine.begin(bindings())
        RotationEngine.onPartyMessage(ee2, "  EE2 ")
        starters.forEach { ign -> RotationEngine.roleOf(ign)!!.checked.forEach { RotationEngine.onTaskDone(ign, it.type) } }
        assertEquals(true, RotationEngine.leapReadyFor(RotationEngine.tracked().first { it.roleId == "r_2_2" }.ign))
    }

    @Test
    fun `leaping to someone inherits where they are`() {
        RotationEngine.begin(bindings())
        val ee2 = bindings()["r_lee2"]!!
        val pot1 = graph.pot("p_1")!!
        bindings().filterKeys { it in pot1.eligible }.values.forEach { ign ->
            RotationEngine.roleOf(ign)!!.checked.forEach { RotationEngine.onTaskDone(ign, it.type) }
        }
        val onRole1 = RotationEngine.tracked().first { it.roleId == "r_2_1" }.ign
        val onRole2 = RotationEngine.tracked().first { it.roleId == "r_2_2" }.ign

        // The 1st terminal waits on the 2nd-terminal player. That player never sends the box
        // text themselves — but they announce a leap to someone who already has, and so inherit it.
        RotationEngine.onPartyMessage(ee2, "2nd term s2")
        assertEquals(false, RotationEngine.leapReadyFor(onRole1))
        RotationEngine.onLeapAnnounce(onRole2, ee2)
        assertEquals(true, RotationEngine.leapReadyFor(onRole1), "arrivals follow a leap")
    }

    @Test
    fun `section four targets are ready once they have leapt`() {
        // Drive a run until someone holds s4's 1st terminal and someone else its 2nd, which
        // leaps to them. The 2nd's cue must go ready on the 1st's leap, not before.
        val rng = Random(31)
        var checked = false
        for (run in 0 until 400) {
            RotationEngine.begin(bindings())
            for (step in 0 until 200) {
                val live = RotationEngine.tracked()
                if (live.isEmpty()) break
                val first = live.find { it.roleId == "r_4_1" }
                val second = live.find { it.roleId == "r_4_2" }
                if (first != null && second != null) {
                    assertEquals(first.ign, RotationEngine.leapTargetFor(second.ign))
                    assertEquals(false, RotationEngine.leapReadyFor(second.ign), "1st terminal has not leapt yet")
                    RotationEngine.onLeapAnnounce(first.ign, "Whoever")
                    assertEquals(true, RotationEngine.leapReadyFor(second.ign), "1st terminal has leapt")
                    checked = true
                    break
                }
                val h = live[rng.nextInt(live.size)]
                h.remaining.toList().forEach { RotationEngine.onTaskDone(h.ign, it) }
            }
            if (checked) break
        }
        assertTrue(checked, "never reached a state with both s4 1st and 2nd terminals held")
    }

    @Test
    fun `a late announcer joins a running rotation, a duplicate does not`() {
        val four = bindings().filterKeys { it != "r_i4" }
        RotationEngine.begin(four)
        assertEquals(4, RotationEngine.tracked().size)

        RotationEngine.addStarter("Late", "r_i4")
        assertEquals(5, RotationEngine.tracked().size)
        assertEquals("i4", RotationEngine.roleOf("Late")!!.name)

        RotationEngine.addStarter("Late", "r_i4")           // said it twice
        RotationEngine.addStarter("Imposter", "r_i4")       // role already held
        RotationEngine.addStarter("Late", "r_2_1")          // not a section-1 role
        assertEquals(5, RotationEngine.tracked().size)
    }

    @Test
    fun `a reserved exit sits first or it never catches the last arrival`() {
        // Ordering trap: "reserved for the last one in" only restricts who may take an exit, it
        // does not make anyone prefer it. Behind an unconditional exit it can never fire.
        graph.pots.forEach { pot ->
            pot.exits.forEachIndexed { i, exit ->
                if (exit.last) {
                    val blockers = pot.exits.take(i).filter { it.cond.isEmpty() && !it.last }
                    assertTrue(
                        blockers.isEmpty(),
                        "${pot.name} exit ${i + 1} is reserved for the last arrival but " +
                            "${blockers.size} unconditional exit(s) come first and would take them",
                    )
                }
            }
        }
    }
}
