package com.engineerclient.p3sim.bots

import com.engineerclient.p3sim.clear.bots.BotTables
import com.engineerclient.p3sim.pack.Fixtures
import com.engineerclient.p3sim.pack.layouts.LayoutPack
import com.engineerclient.p3sim.pack.runstate.RunStatePack
import com.engineerclient.p3sim.run.DungeonPlan

/**
 * Invented dungeons and tables for the bots tests. Nothing here is recorded data: the room names are made up, and the numbers are round
 * values shaped like a plausible party's pace so the planner has something to draw from.
 */
object BotFixtures {
    /**
     * A 14-room tree on the 6x6 grid. The path runs along the top row: entrance (0,0), A (1,0), B (2,0), the Fairy room (3,0), C (4,0), D (5,0),
     * then Blood below D at (5,1). Doors along it: entrance, wither, free fairy, wither, wither, blood. Side rooms hang below A, B, C and the
     * entrance; two more chain below those.
     */
    fun layoutJson(id: String = "bt-1"): String {
        data class R(val i: Int, val name: String, val type: String, val tiles: List<Pair<Int, Int>>, val secrets: Int)
        val rooms = listOf(
            R(0, "Entrance", "entrance", listOf(0 to 0), 0), R(1, "Room A", "normal", listOf(1 to 0), 3), R(2, "Room B", "normal", listOf(2 to 0), 2),
            R(3, "Fairy", "fairy", listOf(3 to 0), 0), R(4, "Room C", "normal", listOf(4 to 0), 4), R(5, "Room D", "normal", listOf(5 to 0), 1),
            R(6, "Blood", "blood", listOf(5 to 1), 0), R(7, "Side 1", "normal", listOf(0 to 1), 2), R(8, "Side 2", "normal", listOf(1 to 1), 5),
            R(9, "Puzzle 1", "puzzle", listOf(2 to 1), 0), R(10, "Side 3", "normal", listOf(4 to 1), 3), R(11, "Side 4", "normal", listOf(1 to 2), 1),
            R(12, "Trap 1", "trap", listOf(2 to 2), 2), R(13, "Champion 1", "yellow", listOf(4 to 2), 0),
        )
        val sb = StringBuilder()
        sb.append("""{"format":"ads-layouts","version":1,"layouts":[{"id":"$id","floor":"F7","doorsComplete":true,"playable":true,"party":[],"path":[0,1,2,3,4,5,6],"rooms":[""")
        sb.append(rooms.joinToString(",") { r ->
            val tiles = r.tiles.joinToString(",") { "[${it.first},${it.second}]" }
            """{"i":${r.i},"name":"${r.name}","family":"${r.name.replace(' ', '_')}","type":"${r.type}","shape":"1x1","tiles":[$tiles],"rot":"SOUTH","secretsMax":${r.secrets}}"""
        })
        sb.append("""],"doors":[""")
        fun door(axis: Char, a: Pair<Int, Int>, b: Pair<Int, Int>, ra: Int, rb: Int, kind: String) =
            """{"axis":"$axis","a":[${a.first},${a.second}],"b":[${b.first},${b.second}],"ra":$ra,"rb":$rb,"kind":"$kind"}"""
        sb.append(listOf(
            door('E', 0 to 0, 1 to 0, 0, 1, "entrance"), door('E', 1 to 0, 2 to 0, 1, 2, "wither"), door('E', 2 to 0, 3 to 0, 2, 3, "fairy"),
            door('E', 3 to 0, 4 to 0, 3, 4, "wither"), door('E', 4 to 0, 5 to 0, 4, 5, "wither"), door('S', 5 to 0, 5 to 1, 5, 6, "blood"),
            door('S', 0 to 0, 0 to 1, 0, 7, "normal"), door('S', 1 to 0, 1 to 1, 1, 8, "normal"), door('S', 2 to 0, 2 to 1, 2, 9, "normal"),
            door('S', 4 to 0, 4 to 1, 4, 10, "normal"), door('S', 1 to 1, 1 to 2, 8, 11, "normal"), door('S', 2 to 1, 2 to 2, 9, 12, "normal"),
            door('S', 4 to 1, 4 to 2, 10, 13, "normal"),
        ).joinToString(","))
        sb.append("""],"keys":[],"puzzles":["Puzzle 1"]}]}""")
        return sb.toString()
    }

    fun dungeon(seed: Long = 1): DungeonPlan {
        val pack = LayoutPack.fromJson(Fixtures.json(layoutJson()))!!
        return (DungeonPlan.build(pack.layout("bt-1")!!, seed, null) as DungeonPlan.Result.Ok).plan
    }

    private fun q(vararg v: Number) = """{"n":300,"pct":[5,25,50,75,95],"q":[${v.joinToString(",")}]}"""
    private fun q7(vararg v: Number) = """{"n":300,"q":[${v.joinToString(",")}]}"""

    /** Run-state tables with the sections the bots read, in round invented numbers. */
    val tablesJson = """
    {"format":"ads-runstate","version":1,"pct":[5,10,25,50,75,90,95],
     "rooms":{"Room A":{"type":"normal","shape":"1x1","tiles":1,"secretsMax":3,"dur":${q7(60, 100, 200, 400, 600, 900, 1200)},"toWhite":${q7(10, 15, 30, 50, 90, 140, 200)}},
              "Side 2":{"type":"normal","shape":"1x1","tiles":1,"secretsMax":5,"dur":${q7(150, 200, 350, 600, 900, 1300, 1600)}}},
     "doors":{"keyObtainToUseTicks":${q(1, 2, 5, 18, 40)},"blood":{"keyAfterStartTicks":${q(180, 240, 300, 380, 520)},"doorAfterKeyTicks":${q(1, 2, 5, 15, 40)}}},
     "splits":{"F7":{"bloodKey":${q(180, 240, 300, 380, 520)},"bloodKeyAfterLastDoor":${q(40, 70, 100, 140, 220)},"bloodOpen":${q(190, 250, 310, 390, 540)},"watcherDone":${q(1000, 1200, 1400, 1600, 1800)},
                 "maxorEntry":${q(1200, 1400, 1600, 1800, 2200)},"campTicks":${q(800, 900, 1000, 1100, 1200)},"provenToMaxor":${q(60, 80, 100, 120, 140)},
                 "tilesDoneAtMaxor":${q(10, 11, 12, 13, 14)},"roomsGreenFracAtMaxor":${q(0.5, 0.6, 0.7, 0.8, 0.9)},"perType":{"BLOOD":{"greenFrac":0.47}}}},
     "timing":{"roomTypeDur":{"NORMAL":${q(60, 280, 490, 880, 1500)},"PUZZLE":${q(40, 140, 280, 370, 700)},"TRAP":${q(160, 190, 220, 270, 440)},"CHAMPION":${q(2, 17, 30, 50, 120)}},
       "classRoles":{"n":300,
         "HEALER":{"roomsOwnedMean":3.7,"puzzlesOwnedMean":0.65,"doorOpenerFrac":0.5,"movingSpeedBpt":1.2,"ownedByType":{"NORMAL":2.7,"PUZZLE":0.65,"TRAP":0.2,"CHAMPION":0.2}},
         "MAGE":{"roomsOwnedMean":3.5,"puzzlesOwnedMean":0.5,"doorOpenerFrac":0.1,"movingSpeedBpt":1.1,"ownedByType":{"NORMAL":2.5,"PUZZLE":0.5,"TRAP":0.2,"CHAMPION":0.2}},
         "BERSERK":{"roomsOwnedMean":4.6,"puzzlesOwnedMean":0.9,"doorOpenerFrac":0.02,"movingSpeedBpt":1.15,"ownedByType":{"NORMAL":3.2,"PUZZLE":0.9,"TRAP":0.3,"CHAMPION":0.3}},
         "ARCHER":{"roomsOwnedMean":3.7,"puzzlesOwnedMean":0.7,"doorOpenerFrac":0.09,"movingSpeedBpt":1.2,"ownedByType":{"NORMAL":2.5,"PUZZLE":0.7,"TRAP":0.2,"CHAMPION":0.2}},
         "TANK":{"roomsOwnedMean":4.2,"puzzlesOwnedMean":0.9,"doorOpenerFrac":0.01,"movingSpeedBpt":1.05,"ownedByType":{"NORMAL":2.9,"PUZZLE":0.9,"TRAP":0.2,"CHAMPION":0.2}},
         "doorOpenAfterT0Ticks":{"1":${q(33, 46, 62, 85, 138)},"2":${q(82, 111, 142, 176, 253)},"3":${q(132, 172, 210, 256, 353)},"4":${q(176, 220, 265, 325, 449)}}},
       "puzzleSolve":{}}}
    """.trimIndent()

    fun tablesPack(): RunStatePack = RunStatePack.fromJson(Fixtures.json(tablesJson))!!

    fun tables(): BotTables = BotTables(tablesPack())
}
