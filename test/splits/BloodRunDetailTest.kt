package com.engineerclient.splits

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BloodRunDetailTest {
    // Real time running 3 ms a tick ahead of the server, so Debug's lag lines show too.
    private fun at(tick: Int) = Stamp(tick * 53L, tick)

    private val entrance = BloodRunDetail.MapRoom("e", "Entrance", fairy = false, entrance = true)
    private val pipes = BloodRunDetail.MapRoom("p", "Pipes", fairy = false, entrance = false)
    private val fairy = BloodRunDetail.MapRoom("f", "Fairy", fairy = true, entrance = false)
    private val duncan = BloodRunDetail.MapRoom("d", "Duncan", fairy = false, entrance = false)
    private val dino = BloodRunDetail.MapRoom("n", "Dino", fairy = false, entrance = false)

    /** A rush, step by step: Pipes, then the door out of fairy (Pipes led there) into Duncan, then Dino to the blood door. */
    private val rush: List<Pair<Int, (BloodRunDetail) -> Unit>> = listOf(
        128 to { b -> b.onChat("[NPC] Mort: Here, I found this map when I first entered the dungeon.", at(128)) },
        131 to { b -> b.onDoorStart(at(131), entrance, pipes) },
        144 to { b -> b.onDoorDown(at(144), entrance, pipes) },
        292 to { b -> b.onKeySpawned(at(292), 12.4) },
        304 to { b -> b.onChat("[MVP+] Alice has obtained Wither Key!", at(304)) },
        306 to { b -> b.onChat("Alice opened a WITHER door!", at(306)) },
        // Pipes' row is already up when this makes it the room before fairy.
        319 to { b -> b.onDoorDown(at(319), fairy, duncan) },
        450 to { b -> b.onKeySpawned(at(450), 30.0) },
        466 to { b -> b.onChat("Bob has obtained Wither Key!", at(466)) },
        480 to { b -> b.onChat("Bob opened a WITHER door!", at(480)) },
        493 to { b -> b.onDoorDown(at(493), duncan, dino) },
        640 to { b -> b.onChat("A Blood Key was picked up!", at(640)) },
        680 to { b -> b.onChat("The BLOOD DOOR has been opened!", at(680)) },
    )

    /** What a fresh tracker, given the first [steps] events, shows at [now]: nothing kept from an earlier frame. */
    private fun fresh(steps: Int, level: BloodRunDetail.Level, now: Stamp, totalRow: Boolean) =
        BloodRunDetail().apply { rush.take(steps).forEach { it.second(this) } }.lines(level, now, totalRow)

    @Test fun keptLinesMatchFreshOnesEveryFrame() {
        val b = BloodRunDetail()
        rush.forEachIndexed { i, (tick, event) ->
            event(b)
            // A few frames after each event, every level, as the HUD would draw them.
            for (frame in listOf(tick, tick + 3, tick + 9)) for (level in BloodRunDetail.Level.entries) for (total in listOf(true, false)) {
                assertEquals(fresh(i + 1, level, at(frame), total), b.lines(level, at(frame), total), "after tick $tick, $level, frame $frame, total $total")
            }
        }
    }

    @Test fun pipesTurnsPinkOnceFairyIsKnown() {
        val b = BloodRunDetail()
        rush.take(6).forEach { it.second(b) }
        assertTrue(b.lines(BloodRunDetail.Level.COMPACT, at(310)).first().contains("§5Pipes: "))
        rush[6].second(b)
        assertTrue(b.lines(BloodRunDetail.Level.COMPACT, at(320)).first().contains("§dPipes: "))
    }

    @Test fun resetForgetsTheFinishedRush() {
        val b = BloodRunDetail()
        rush.forEach { it.second(b) }
        b.lines(BloodRunDetail.Level.COMPACT, at(700))
        b.reset()
        assertEquals(emptyList(), b.lines(BloodRunDetail.Level.COMPACT, at(710)))
        rush.take(5).forEach { it.second(b) }
        assertEquals(fresh(5, BloodRunDetail.Level.COMPACT, at(305), true), b.lines(BloodRunDetail.Level.COMPACT, at(305)))
    }
}
