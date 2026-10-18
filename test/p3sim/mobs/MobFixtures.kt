package com.engineerclient.p3sim.mobs

import com.engineerclient.p3sim.pack.Fixtures
import com.engineerclient.p3sim.pack.mobs.MobPack

/** A synthetic mob pack: invented archetypes, heads, rooms and numbers (no recorded data). The heads use plain letters where Hypixel uses icon glyphs. */
object MobFixtures {
    const val TEXT = """
    {"format":"ads-mobs","version":1,"mc":"26.3",
     "archetypes":[
      {"id":"test_walker","name":"Test Walker","cls":"clear","entity":"minecraft:zombie","disguise":false,"hp":{"F7":1300000,"M7":40000000},"star":true,
       "tag":{"idOffset":1,"dy":2.09,"head":"§2W §r§6✯ §r","nameColor":"§c","bold":false},"movement":"static"},
      {"id":"test_archer","name":"Test Archer","cls":"clear","entity":"minecraft:skeleton","disguise":false,"hp":{"F7":1600000},"star":true,
       "tag":{"idOffset":1,"dy":2.19,"head":"§fA §r§6✯ §r","nameColor":"§c","bold":false},"movement":"static"},
      {"id":"test_mancer","name":"Test Mancer","cls":"clear","entity":"minecraft:wither_skeleton","disguise":false,"hp":{"F7":1200000},"star":true,
       "tag":{"idOffset":3,"dy":2.5,"head":"§8M§r§f§r§5 §r§6✯ §r","nameColor":"§c","bold":false},"movement":"static"},
      {"id":"test_sleeper","name":"Test Sleeper","cls":"clear","entity":"minecraft:enderman","disguise":false,"bodyName":"Dinnerbone","hp":{"F7":1500000},"star":true,
       "tag":{"idOffset":1,"dy":3.09,"head":"§5S §r§6✯ §r","nameColor":"§c","bold":false},"movement":"sleeper","wake":{"radius":5.0,"trigger":"player-body"}},
      {"id":"test_actor","name":"Test Actor","cls":"clear","entity":"minecraft:player","disguise":true,"skin":{"id":"ab","value":null,"signature":null},"hp":{"F7":840000},"star":true,
       "tag":{"idOffset":1,"dy":2.09,"head":"§2◆§r§8◆§r§6 ✯ §r","nameColor":"§c","bold":false},"movement":"static"},
      {"id":"test_boss","name":"Test Boss","cls":"miniboss","entity":"minecraft:player","disguise":true,"hp":{"F7":2600000},"star":true,
       "tag":{"idOffset":1,"dy":2.09,"head":"§eB§r§5 §r§6✯ §r","nameColor":"§d","bold":true},"movement":"patrol"},
      {"id":"test_assassin","name":"Test Assassin","cls":"miniboss","entity":"minecraft:player","disguise":true,"hp":{"F7":20000000,"M7":145000000},"star":false,
       "tag":{"idOffset":1,"dy":2.09,"head":"§eX§r§5 §r","nameColor":"§d","bold":true},"movement":"teleport",
       "equipment":{"mainhand":"minecraft:iron_sword","mainhandName":"Silent Death","feet":"minecraft:leather_boots"}},
      {"id":"king_midas","name":"King Midas","cls":"special","entity":"minecraft:player","disguise":true,"hp":{"F7":4300000},"star":false,
       "tag":{"idOffset":1,"dy":2.09,"head":"§eK§r","nameColor":"§d","bold":true},"movement":"static"},
      {"id":"test_biter","name":"Test Biter","cls":"special","entity":"minecraft:silverfish","disguise":false,"hp":{"F7":1000000000},"star":false,
       "tag":{"idOffset":1,"dy":0.38,"head":"§4 §r","nameColor":"§8","bold":true,"modSplit":true},"movement":"static"},
      {"id":"lost_adventurer","name":"Lost Adventurer","cls":"miniboss","entity":"minecraft:player","disguise":true,"hp":{"F7":2600000},"star":true,
       "tag":{"idOffset":1,"dy":2.12,"head":"§eL§r§5 §r§6✯ §r","nameColor":"§d","bold":true},"movement":"patrol"},
      {"id":"angry_archaeologist","name":"Angry Archaeologist","cls":"miniboss","entity":"minecraft:player","disguise":true,"hp":{"F7":420000},"star":true,
       "tag":{"idOffset":1,"dy":2.12,"head":"§eA§r§5 §r§6✯ §r","nameColor":"§d","bold":true},"movement":"patrol"},
      {"id":"frozen_adventurer","name":"Frozen Adventurer","cls":"miniboss","entity":"minecraft:player","disguise":true,"hp":{"F7":2600000},"star":true,
       "tag":{"idOffset":1,"dy":2.12,"head":"§eF§r§5 §r§6✯ §r","nameColor":"§d","bold":true},"movement":"patrol"}
     ],
     "modifiers":[
      {"id":"","weight":{"F7":0.5,"M7":0.5},"hpFactor":1.0,"nameColor":null,"hpColor":"a","hpColorHurt":"e","hpThreshold":0.5},
      {"id":"Healthy","weight":{"F7":0.2,"M7":null},"hpFactor":1.6,"nameColor":"§2","hpColor":"a"},
      {"id":"Speedy","weight":{"F7":0.1,"M7":0.1},"hpFactor":1.0,"nameColor":"§3","hpColor":"b"},
      {"id":"Flaming","weight":{"F7":0.1,"M7":0.1},"hpFactor":1.0,"nameColor":"","hpColor":"e","headReset":false},
      {"id":"Fortified","weight":{"F7":0.1,"M7":0.1},"hpFactor":1.0,"nameColor":"§8","hpColor":"7"}
     ],
     "hpScale":{"F7":{"n":10,"values":[1.0,1.5],"counts":[7,3]},"M7":null},
     "rooms":[
      {"name":"Test Room","designs":["test_room"],"clear":"mob","visits":{"adr":20,"cd":0},
       "starred":{"mean":5.8,"p50":6,"p90":6,"max":6,"hist":{"5":4,"6":16}},"unstarred":{"mean":0.0,"p50":0,"p90":0,"max":0,"hist":{"0":20}},
       "cells":[[2,70,2,0,1,10],[4,70,4,1,1,5],[4,70,4,0,1,5],[6,70,6,2,1,20],[8,70,8,3,0,2]],
       "layouts":[
        {"src":"adr","fl":"F7","m":[[2,70,2,0,1],[4,70,4,1,1],[6,70,6,2,1],[8,70,8,0,1],[10,70,10,4,1],[12,70,12,0,1]]},
        {"src":"adr","fl":"F7","m":[[2,70,2,0,1],[4,70,4,1,1],[6,70,6,2,1],[8,70,8,0,1],[10,70,10,4,1]]},
        {"src":"adr","fl":"F7","m":[[2,70,2,0,1],[4,70,4,1,1],[6,70,6,2,1],[8,70,8,0,1],[10,70,10,4,1],[12,70,12,0,1]]},
        {"src":"adr","fl":"F7","m":[[2,70,2,0,1]]},
        {"src":"cd","fl":"?","m":[[2,70,2,0,1],[4,70,4,1,1],[6,70,6,2,1],[8,70,8,0,1],[10,70,10,4,1],[12,70,12,0,1]]}]},
      {"name":"Boss Room","designs":["boss_room"],"clear":"miniboss","visits":{"adr":4,"cd":0},
       "starred":{"mean":1.0,"p50":1,"p90":1,"max":1,"hist":{"1":4}},"unstarred":{"mean":0.0,"p50":0,"p90":0,"max":0,"hist":{"0":4}},
       "cells":[[3,70,3,5,1,4]],
       "layouts":[{"src":"adr","fl":"F7","m":[[3,70,3,5,1],[9,70,9,0,1]]},{"src":"adr","fl":"F7","m":[[5,70,5,5,1]]},{"src":"adr","fl":"F7","m":[[3,70,3,5,1]]},{"src":"adr","fl":"F7","m":[[4,70,4,5,1]]}]},
      {"name":"King Room","designs":["king_room"],"clear":"mob","visits":{"adr":3,"cd":0},
       "starred":{"mean":1.0,"p50":1,"p90":1,"max":1,"hist":{"1":3}},"cells":[[5,70,5,7,0,3],[7,70,7,0,1,3]],
       "layouts":[{"src":"adr","fl":"F7","m":[[5,70,5,7,0],[7,70,7,0,1]]},{"src":"adr","fl":"F7","m":[[5,70,5,7,0],[7,70,7,0,1]]},{"src":"adr","fl":"F7","m":[[5,70,5,7,0],[7,70,7,0,1]]}]}
     ],
     "minibosses":[{"room":"Boss Room","arch":5,"cell":[12,70,12],"src":"patrol","loop":[[12,70,12],[12,70,20],[20,70,20],[20,70,12]]}],
     "playerDamage":{"unit":"percent","immunityTicks":10,
      "melee":{"F7":{"n":100,"perMinute":5.3,"quantiles":{"0":0.0,"50":2.0,"100":120.0},"atoms":[{"value":10.0,"p":0.1}],"provisional":false}},
      "meleeByArchetype":{},"projectile":{"arrow":{"n":30,"p10":0.5,"p50":3.0,"p90":9.0}},"trap":{"percent":15.0,"kinds":[]}},
     "gaps":[]}
    """

    fun pack(): MobPack = MobPack.fromJson(Fixtures.json(TEXT))!!
}
