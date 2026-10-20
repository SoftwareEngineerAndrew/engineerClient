package com.engineerclient.p3sim.clear.replay

import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Which chests a recording opened as reward chests (2026-10-13_20-38-47_F7's Spider Essence chest, and a plain one). */
class ReplayChestsTest {
    @Test
    fun aRewardChestIsTheOneWithTheCornerSound() {
        val ev = listOf(
            """[515,"snd","minecraft:block.chest.open","BLOCKS",-12,88,-89,0.5,0.984127]""",
            """[516,"snd","minecraft:block.chest.open","BLOCKS",-11.5,88.5,-88.5,0.5,0.9047619]""",
            """[519,"snd","minecraft:block.note_block.harp","BLOCKS",-12,88,-89,1,0.7936508]""",
            """[600,"snd","minecraft:block.chest.open","BLOCKS",4.5,70.5,-30.5,0.5,0.95]""",
            """[601,"chat","This chest has already been searched!"]""",
        ).map { JsonParser.parseString(it).asJsonArray }
        assertEquals(setOf("-12,88,-89"), ReplayFile.rewardChests(ev))
    }
}
