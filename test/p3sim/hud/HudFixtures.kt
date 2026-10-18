package com.engineerclient.p3sim.clear.hud

import com.engineerclient.p3sim.run.CheckMark
import com.engineerclient.p3sim.run.PlayerRecord
import com.engineerclient.p3sim.run.RunBus
import com.engineerclient.p3sim.run.RunStage
import com.engineerclient.p3sim.run.RunState
import com.engineerclient.p3sim.run.TestPlans
import com.engineerclient.p3sim.pack.Fixtures
import com.engineerclient.p3sim.pack.layouts.LayoutPack
import com.engineerclient.p3sim.run.DungeonPlan
import java.util.UUID

/** Synthetic runs for the channel tests: the four-room test dungeon and invented party members. */
object HudFixtures {
    val me = UUID(0, 1)
    val a = UUID(0, 2)
    val b = UUID(0, 3)

    /**
     * A second invented dungeon with a 2x2 room, a wither door and a blood door:
     * entrance (2,0) -S- mid (2,1) -E(wither)- big 2x2 {(3,1),(4,1),(3,2),(4,2)}, mid -S(blood)- blood (2,2).
     */
    val bigLayout = """
    {"format":"ads-layouts","version":1,"layouts":[
     {"id":"t-2","floor":"F7","doorsComplete":true,"path":[0,1,3],"playable":true,"party":[],
      "rooms":[
       {"i":0,"name":"Entrance","family":"Entrance","type":"entrance","shape":"1x1","tiles":[[2,0]],"rot":"NORTH","secretsMax":0},
       {"i":1,"name":"Mid Room","family":"Mid","type":"puzzle","shape":"1x1","tiles":[[2,1]],"rot":"WEST","secretsMax":2},
       {"i":2,"name":"Big Room","family":"Big","type":"yellow","shape":"2x2","tiles":[[3,1],[4,1],[3,2],[4,2]],"rot":"SOUTH","secretsMax":4},
       {"i":3,"name":"Last Stop","family":"Blood","type":"blood","shape":"1x1","tiles":[[2,2]],"rot":"SOUTH","secretsMax":0}
      ],
      "doors":[
       {"axis":"S","a":[2,0],"b":[2,1],"ra":0,"rb":1,"kind":"entrance"},
       {"axis":"E","a":[2,1],"b":[3,1],"ra":1,"rb":2,"kind":"wither"},
       {"axis":"S","a":[2,1],"b":[2,2],"ra":1,"rb":3,"kind":"blood"}
      ],
      "keys":[],"puzzles":[]}
    ]}
    """.trimIndent()

    fun bigState(clock: () -> Int = { 0 }): RunState {
        val pack = LayoutPack.fromJson(Fixtures.json(bigLayout))!!
        val plan = (DungeonPlan.build(pack.layout("t-2")!!, 1, null) as DungeonPlan.Result.Ok).plan
        return RunState(plan, RunBus(), clock)
    }

    fun state(clock: () -> Int = { 0 }): RunState = RunState(TestPlans.plan(), RunBus(), clock)

    fun player(state: RunState, id: UUID, name: String, cls: String?, bot: Boolean = false): PlayerRecord =
        PlayerRecord(id, name, cls, bot).also { state.players[id] = it }

    /** A party of [me] (a mage) and two bots, standing in the entrance. */
    fun party(state: RunState) {
        player(state, me, "Alpha", "Mage").also { it.x = -185.0; it.z = -185.0 }
        player(state, a, "BetaBetaBetaBeta", "Berserk", true).also { it.x = -185.0; it.z = -185.0 }
        player(state, b, "Gamma", "Healer", true).also { it.x = -185.0; it.z = -185.0 }
    }

    fun ctx(state: RunState, stage: RunStage = RunStage.CLEAR, t: Int = 400, stats: HudStats = HudStats.DEFAULT, t0Known: Boolean = true) =
        HudContext(state, stage, "F7", t, t0Known, sinceBegin = t + 150, stats = stats)

    fun mark(state: RunState, room: Int, m: CheckMark) = state.setCheck(room, m)
}
