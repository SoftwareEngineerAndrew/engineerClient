package com.engineerclient.p3sim.pack

import com.engineerclient.p3sim.pack.mobs.MobPack
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The mob tables loader (FORMAT-mobs.md) on a synthetic pack: invented archetypes, cells and numbers. */
class MobPackTest {
    private val text = """
    {"format":"ads-mobs","version":1,"mc":"26.3",
     "archetypes":[
      {"id":"test_walker","name":"Test Walker","cls":"clear","entity":"minecraft:zombie","disguise":false,"skin":null,"bodyName":null,
       "hp":{"F7":1000,"M7":50000},"star":true,"tag":{"idOffset":1,"dy":2.09,"head":"H","nameColor":"c","bold":false},"movement":"static","wake":null,"seen":{"F7":10}},
      {"id":"test_sleeper","name":"Test Sleeper","cls":"clear","entity":"minecraft:enderman","disguise":false,"bodyName":"Dinnerbone","hp":{"F7":2000},"star":true,
       "tag":{"idOffset":3,"dy":3.09,"head":"H2","nameColor":"c","bold":true},"movement":"sleeper","wake":{"radius":5.0,"trigger":"player-body"},"modifierWeights":{"":0.5,"Healthy":0.5}},
      {"id":"test_actor","name":"Test Actor","cls":"miniboss","entity":"minecraft:player","disguise":true,"skin":{"id":"ab","value":null,"signature":null},"hp":{"F7":3000},"star":true,"movement":"static"}
     ],
     "modifiers":[{"id":"","weight":{"F7":0.6,"M7":null},"hpFactor":1.0,"nameColor":null,"hpColor":"a"},{"id":"Healthy","weight":{"F7":0.4},"hpFactor":1.67,"nameColor":"2","hpColor":"a"}],
     "hpScale":{"unit":"multiplier","F7":{"n":10,"values":[1.0,1.5],"counts":[7,3]},"M7":null},
     "rooms":[
      {"name":"Test Room","designs":["Test_Room"],"clear":"mob","visits":{"adr":3,"cd":1},
       "starred":{"mean":2.5,"p50":2,"p90":3,"max":3,"hist":{"2":2,"3":1}},"unstarred":{"mean":0.0,"p50":0,"p90":0,"max":0,"hist":{"0":3}},
       "cells":[[4,70,5,0,1,3],[6,70,7,1,1,1],[9,9,9,77,1,1]],
       "layouts":[{"src":"adr","fl":"F7","m":[[4,70,5,0,1],[6,70,7,1,0],[1,1,1,99,1]]},{"src":"cd","fl":"?","m":[]}]},
      {"name":"Dino Site","clear":"none","visits":{"adr":1,"cd":0},"cells":[],"layouts":[]}
     ],
     "minibosses":[{"room":"Test Room","arch":2,"cell":[10,70,10],"src":"corpus","hpFactor":null,"loop":[[1,70,1],[2,70,2]]}],
     "playerDamage":{"unit":"percent","immunityTicks":10,
      "melee":{"F7":{"n":100,"perMinute":5.3,"quantiles":{"0":0.0,"50":2.0,"100":120.0},"atoms":[{"value":10.0,"p":0.05}],"provisional":false}},
      "meleeByArchetype":{"F7":{"test_walker":{"n":40,"p10":0.7,"p50":3.0,"p90":10.0}}},
      "projectile":{"arrow":{"n":30,"p10":0.5,"p50":3.0,"p90":9.0}},"trap":{"percent":15.0,"kinds":["Arrow Trap"]}},
     "gaps":["synthetic"]}
    """.trimIndent()

    private fun pack(): MobPack = assertNotNull(MobPack.fromJson(Fixtures.json(text)))

    @Test fun archetypes() {
        val p = pack()
        assertEquals(3, p.archetypes.size)
        val w = p.archetype(0)!!
        assertEquals("Test Walker", w.name); assertEquals(1000L, w.hpFor("F7")); assertEquals(50000L, w.hpFor("M7")); assertEquals(1, w.tag!!.idOffset)
        val s = p.archetypeById("test_sleeper")!!
        assertEquals(3, s.tag!!.idOffset); assertTrue(s.tag!!.bold); assertEquals(5.0, s.wake!!.radius); assertEquals("Dinnerbone", s.bodyName); assertEquals(0.5, s.modifierWeights!!["Healthy"])
        val a = p.byName("Test Actor")!!
        assertTrue(a.disguise); assertEquals("ab", a.skin!!.id); assertNull(a.skin!!.value)
        assertEquals("miniboss", a.cls)
    }

    @Test fun modifiersAndHpScale() {
        val p = pack()
        assertEquals(1.67, p.modifier("Healthy")!!.hpFactor); assertNull(p.modifier("")!!.weight["M7"])
        val t = p.hpScaleFor("F7")!!
        assertEquals(10, t.n); assertEquals(listOf(1.0, 1.5), t.values.toList()); assertEquals(listOf(7, 3), t.counts.toList())
        assertNull(p.hpScaleFor("M7"))
    }

    @Test fun roomsDropUnknownArchetypesAndJoinByName() {
        val p = pack()
        val r = p.room("Test Room")!!
        assertEquals(2, r.cells.size, "the cell with archetype 77 is dropped")
        assertEquals(MobPack.norm("a b-c"), "a_b_c")
        assertEquals(4, r.visits)
        assertEquals(2, r.layouts[0].mobs.size, "archetype 99 dropped; a starred and an unstarred mob remain")
        assertEquals(false, r.layouts[0].mobs[1].star)
        assertEquals(0, r.layouts[1].mobs.size)
        assertEquals(2.0, r.starred!!.p50); assertEquals(mapOf(2 to 2, 3 to 1), r.starred!!.hist)
        // Join: exact, then ZWSP-stripped, then alias, then the normalised key (spaces and hyphens as underscores).
        assertNotNull(p.room("Dino​ Site"))
        assertEquals("Dino Site", p.room("Dino_Site")!!.name)
        assertEquals("Test Room", p.room("Other Name") { if (it == "Other Name") "Test Room" else null }!!.name)
        assertNull(p.room("Nowhere"))
    }

    @Test fun minibossesAndDamage() {
        val p = pack()
        val m = p.minibossesOf("Test Room").single()
        assertEquals(Pos(10, 70, 10), m.cell); assertEquals(2, m.loop!!.size)
        val d = p.playerDamage!!
        assertEquals(10, d.immunityTicks); assertEquals(15.0, d.trapPercent)
        val f7 = d.melee["F7"]!!
        assertEquals(2.0, f7.quantiles[50.0]); assertEquals(3, f7.quantiles.size); assertEquals(0.05, f7.atoms[0].p)
        assertEquals(3.0, d.meleeByArchetype["F7"]!!["test_walker"]!!.p50)
        assertEquals(9.0, d.projectile["arrow"]!!.p90)
    }

    @Test fun refusesWrongVersion() {
        val r = PackReport()
        assertNull(MobPack.fromJson(Fixtures.json("""{"format":"ads-mobs","version":3}"""), r))
        assertTrue(r.errors().single().contains("version 3"))
    }
}
