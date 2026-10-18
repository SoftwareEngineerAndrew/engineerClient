package com.engineerclient.p3sim.hard

import com.engineerclient.p3sim.clear.puzzles.hard.ChestFinale
import com.engineerclient.p3sim.clear.puzzles.hard.ChestReward
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChestFinaleTest {
    @Test fun lidEventLeadsTheJingleAndFollowsTheOpenSound() {
        val s = ChestFinale.schedule()
        val lid = s.single { it.step == ChestFinale.Step.LID_EVENT }.at
        val open = s.first { it.step == ChestFinale.Step.OPEN_SOUND }.at
        assertEquals(lid - 1, open)
        val harp = s.filter { it.step == ChestFinale.Step.HARP }
        assertEquals(5, harp.size)
        assertEquals(lid + 3, harp.first().at)
        assertEquals(listOf(5, 5, 5, 5), harp.zipWithNext { a, b -> b.at - a.at })
        assertEquals(ChestFinale.HARP_PITCHES.toList(), harp.map { it.pitch })
    }

    @Test fun chestCellsAreTheMeasuredOnes() {
        assertEquals("minecraft:chest[facing=north,type=single,waterlogged=false]", ChestReward.ICE.edit().state)
        assertTrue(ChestReward.TP_MAZE.edit().state.contains("facing=south"))
        assertEquals(56, ChestReward.WATER.y)
    }
}
