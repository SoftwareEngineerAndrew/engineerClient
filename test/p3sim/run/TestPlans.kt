package com.engineerclient.p3sim.run

import com.engineerclient.p3sim.pack.Fixtures
import com.engineerclient.p3sim.pack.PackReport
import com.engineerclient.p3sim.pack.PackSet
import com.engineerclient.p3sim.pack.layouts.LayoutPack
import java.nio.file.Path

/** A tiny invented dungeon for the run tests: four rooms in a chain, entrance -> hall -> fairy -> blood. */
object TestPlans {
    val layoutJson = """
    {"format":"ads-layouts","version":1,"layouts":[
     {"id":"t-1","floor":"F7","doorsComplete":true,"path":[0,1,2,3],"playable":true,"party":[],
      "rooms":[
       {"i":0,"name":"Entrance","family":"Entrance","type":"entrance","shape":"1x1","tiles":[[3,0]],"rot":"NORTH","secretsMax":0,"tNone":3,"tGreen":13},
       {"i":1,"name":"Wide Hall","family":"Wide_Hall","type":"normal","shape":"1x2","tiles":[[3,1],[3,2]],"rot":"WEST","secretsMax":3,"tNone":100,"tWhite":150},
       {"i":2,"name":"Fairy","family":"Fairy","type":"fairy","shape":"1x1","tiles":[[4,2]],"rot":"EAST","secretsMax":0},
       {"i":3,"name":"Last Stop","family":"Blood","type":"blood","shape":"1x1","tiles":[[4,3]],"rot":"SOUTH","secretsMax":0}
      ],
      "doors":[
       {"axis":"S","a":[3,0],"b":[3,1],"ra":0,"rb":1,"kind":"entrance","tOpen":3},
       {"axis":"E","a":[3,2],"b":[4,2],"ra":1,"rb":2,"kind":"fairy","tOpen":3},
       {"axis":"S","a":[4,2],"b":[4,3],"ra":2,"rb":3,"kind":"blood","tOpen":400},
       {"axis":"E","a":[3,1],"b":[4,1],"ra":1,"rb":9,"kind":"none"}
      ],
      "keys":[],"puzzles":[]}
    ]}
    """.trimIndent()

    fun plan(seed: Long = 1): DungeonPlan {
        val pack = LayoutPack.fromJson(Fixtures.json(layoutJson))!!
        return (DungeonPlan.build(pack.layout("t-1")!!, seed, null) as DungeonPlan.Result.Ok).plan
    }

    fun emptyPacks(): PackSet = PackSet(Path.of("/nowhere"), null, null, null, null, PackReport())
}
