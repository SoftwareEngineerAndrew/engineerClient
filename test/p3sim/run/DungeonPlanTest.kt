package com.engineerclient.p3sim.run

import com.engineerclient.p3sim.pack.Fixtures
import com.engineerclient.p3sim.pack.Rot
import com.engineerclient.p3sim.pack.Tile
import com.engineerclient.p3sim.pack.layouts.DoorKind
import com.engineerclient.p3sim.pack.layouts.LayoutPack
import com.engineerclient.p3sim.pack.rooms.BytesBlobSource
import com.engineerclient.p3sim.pack.rooms.RoomPack
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DungeonPlanTest {
    @Test fun buildsFromALayoutWithoutARoomPack() {
        val p = TestPlans.plan()
        assertEquals(4, p.rooms.size)
        assertEquals(RoomType.ENTRANCE, p.entrance!!.type); assertEquals(RoomType.BLOOD, p.blood!!.type); assertEquals(RoomType.FAIRY, p.fairy!!.type)
        assertEquals("Wide Hall", p.roomAt(3, 2)!!.name)
        assertEquals("Wide Hall", p.roomAtBlock(-100.0, -130.0)!!.name)   // tile (3,2): x -104..-74, z -136..-106
        assertNull(p.roomAtBlock(-169.0, -185.0), "a door gap column is no room")
        assertEquals(3, p.secretsTotal)
        assertEquals(5, p.tileUnits)
        assertEquals(listOf(0, 1, 2, 3), p.path)
        assertEquals(listOf(DoorKind.ENTRANCE, DoorKind.FAIRY, DoorKind.BLOOD), p.pathDoors.map { it.kind })
        assertEquals(0, p.paidWitherDoors.size, "the free fairy door is not paid")
        assertTrue(p.pathDoors.all { it.kind.connected })
        assertEquals(DoorKind.BLOOD, p.doorBetween(3, 2)!!.kind)
        assertNull(p.doorBetween(0, 3))
        assertEquals(DoorKind.ENTRANCE, p.doorAt('S', 3, 0)!!.kind)
        assertEquals(100, p.rooms[1].recordedNone); assertEquals(150, p.rooms[1].recordedWhite)
    }

    @Test fun needsKeyOnlyForWitherAndBlood() {
        val p = TestPlans.plan()
        assertEquals(listOf(false, false, true, false), p.doors.map { it.needsKey })
    }

    @Test fun anUnresolvedRotationOrIncompleteDoorsFailWithAMessage() {
        val noRot = TestPlans.layoutJson.replace(""""rot":"EAST",""", "")
        val l = LayoutPack.fromJson(Fixtures.json(noRot))!!.layout("t-1")!!
        val r = DungeonPlan.build(l, 1, null)
        assertIs<DungeonPlan.Result.Failed>(r)
        assertTrue(r.failure.message.contains("Fairy") && r.failure.message.contains("rotation"), r.failure.message)

        val incomplete = TestPlans.layoutJson.replace(""""doorsComplete":true""", """"doorsComplete":false""")
        val r2 = DungeonPlan.build(LayoutPack.fromJson(Fixtures.json(incomplete))!!.layout("t-1")!!, 1, null)
        assertIs<DungeonPlan.Result.Failed>(r2)
    }

    // ---- with a (synthetic) room pack ------------------------------------------------------------------------------

    private fun roomPack(): RoomPack {
        val v = Fixtures.volume(31, 31, 1, 69, listOf("minecraft:air", "minecraft:stone"), IntArray(31 * 31) { 1 })
        val vol = """{"kind":"volume","off":0,"len":${v.size},"crc32":"${Fixtures.crcHex(v)}","nonAir":961}"""
        fun d1(id: String, fam: String, name: String, type: String, rots: String = """["SOUTH","WEST","NORTH","EAST"]""") =
            """{"id":"$id","name":"$name","family":"$fam","type":"$type","shape":"1x1","tiles":[[0,0]],"W":31,"D":31,"H":1,"y0":69,"rotations":$rots,"vol":$vol,
               "odin":{"name":"$name","secrets":2}}"""
        val index = """{"format":"ads-rooms","version":1,"designs":[
            ${d1("Entrance_Open", "Entrance", "Entrance", "entrance")},
            ${d1("Entrance_Closed", "Entrance", "Entrance", "entrance")},
            {"id":"Wide_Hall","name":"Wide Hall","family":"Wide_Hall","type":"normal","shape":"1x2","tiles":[[0,0],[1,0]],"W":63,"D":31,"H":1,"y0":69,"rotations":["SOUTH","WEST"],"vol":$vol,"odin":{"secrets":9}},
            ${d1("Fairy", "Fairy", "Fairy", "fairy", """["SOUTH"]""")},
            ${d1("Blood_A", "Blood", "Blood", "blood")}
        ],"cores":[]}"""
        return RoomPack.fromIndex(Fixtures.json(index), BytesBlobSource(v))!!
    }

    @Test fun picksBuildsFromTheSeedDeterministically() {
        val rp = roomPack()
        val l = LayoutPack.fromJson(Fixtures.json(TestPlans.layoutJson))!!.layout("t-1")!!
        val picks = (0L..30L).map { seed -> ((DungeonPlan.build(l, seed, rp) as DungeonPlan.Result.Ok).plan.entrance!!.designId) }
        assertEquals(setOf("Entrance_Open", "Entrance_Closed"), picks.toSet(), "both builds occur across seeds")
        for (seed in 0L..5L) assertEquals(picks[seed.toInt()], (DungeonPlan.build(l, seed, rp) as DungeonPlan.Result.Ok).plan.entrance!!.designId)
    }

    @Test fun placementsFollowTheLawAndFairyIgnoresTheWord() {
        val rp = roomPack()
        val l = LayoutPack.fromJson(Fixtures.json(TestPlans.layoutJson))!!.layout("t-1")!!
        val r = DungeonPlan.build(l, 1, rp)
        // Wide_Hall is declared 63x31 in the pack but the placement law wants 1x2 = 63x31: fine. "Last Stop"'s family Blood exists.
        assertIs<DungeonPlan.Result.Ok>(r)
        val fairy = r.plan.fairy!!
        assertEquals(Rot.SOUTH, fairy.rotation, "Fairy is always SOUTH even though the layout says EAST")
        assertEquals(Rot.WEST, r.plan.rooms[1].rotation)
        assertEquals(listOf(Tile(3, 1), Tile(3, 2)), r.plan.rooms[1].placement!!.tiles())
        assertEquals(-200 + 32 * 3, r.plan.rooms[1].placement!!.originX)
        assertEquals(3, r.plan.rooms[1].secretsMax, "the layout's number wins over the design's Odin count")
    }

    @Test fun aMissingFamilyIsAMessage() {
        val rp = roomPack()
        val l = LayoutPack.fromJson(Fixtures.json(TestPlans.layoutJson.replace("Wide_Hall", "Nope_Hall")))!!.layout("t-1")!!
        val r = DungeonPlan.build(l, 1, rp)
        assertIs<DungeonPlan.Result.Failed>(r)
        assertTrue(r.failure.message.contains("Wide Hall"), r.failure.message)
    }

    @Test fun aPlacementThatBreaksTheTileLawIsAMessage() {
        val rp = roomPack()
        // The hall's tiles run along z, which is WEST; saying SOUTH breaks the tile law.
        val l = LayoutPack.fromJson(Fixtures.json(TestPlans.layoutJson.replace(""""rot":"WEST"""", """"rot":"SOUTH"""")))!!.layout("t-1")!!
        val r = DungeonPlan.build(l, 1, rp)
        assertIs<DungeonPlan.Result.Failed>(r)
        assertTrue(r.failure.message.contains("cannot be placed"), r.failure.message)
    }

    @Test fun aMultiTileRoomTouchingItsNeighbourTwiceKeepsTheDoorway() {
        // Two 1x2 halls side by side touch through two slots: a wall listed first, the doorway second. doorBetween must find the doorway.
        val json = """{"format":"ads-layouts","version":1,"layouts":[{"id":"t-2","floor":"F7","doorsComplete":true,"path":[0,1],"playable":true,
            "rooms":[
              {"i":0,"name":"Hall A","family":"A","type":"entrance","shape":"1x2","tiles":[[0,0],[0,1]],"rot":"WEST","secretsMax":0},
              {"i":1,"name":"Hall B","family":"B","type":"blood","shape":"1x2","tiles":[[1,0],[1,1]],"rot":"WEST","secretsMax":0}],
            "doors":[{"axis":"E","a":[0,0],"b":[1,0],"ra":0,"rb":1,"kind":"none"},{"axis":"E","a":[0,1],"b":[1,1],"ra":0,"rb":1,"kind":"wither"}],
            "keys":[],"puzzles":[]}]}"""
        val plan = (DungeonPlan.build(LayoutPack.fromJson(Fixtures.json(json))!!.layout("t-2")!!, 1, null) as DungeonPlan.Result.Ok).plan
        assertEquals(2, plan.doorsBetween(0, 1).size)
        assertEquals(DoorKind.WITHER, plan.doorBetween(0, 1)!!.kind, "the connected slot wins over a wall that is listed first")
        assertEquals(DoorKind.WITHER, plan.doorBetween(1, 0)!!.kind)
        assertEquals(listOf(DoorKind.WITHER), plan.pathDoors.map { it.kind })
    }

    @Test fun theCoreOfARecordedRoomPicksItsVariant() {
        val v = Fixtures.volume(31, 31, 1, 69, listOf("minecraft:air", "minecraft:stone"), IntArray(31 * 31) { 1 })
        val patch = Fixtures.patch(31, 31, 1, 69, listOf("minecraft:dirt"), listOf(0 to 0))
        val blob = v + patch
        val vol = """{"kind":"volume","off":0,"len":${v.size},"crc32":"${Fixtures.crcHex(v)}","nonAir":961}"""
        val pv = """{"kind":"patch","off":${v.size},"len":${patch.size},"crc32":"${Fixtures.crcHex(patch)}","nonAir":1}"""
        fun d1(id: String, fam: String, name: String, type: String, extra: String = "") =
            """{"id":"$id","name":"$name","family":"$fam","type":"$type","shape":"1x1","tiles":[[0,0]],"W":31,"D":31,"H":1,"y0":69,"rotations":["SOUTH","WEST","NORTH","EAST"],"vol":$vol $extra}"""
        val index = """{"format":"ads-rooms","version":1,"designs":[
            ${d1("Entrance_Open", "Entrance", "Entrance", "entrance", ""","variants":[{"id":"v1","kind":"state","core":777,"changedCells":1,"vol":$pv}]""")},
            {"id":"Wide_Hall","name":"Wide Hall","family":"Wide_Hall","type":"normal","shape":"1x2","tiles":[[0,0],[1,0]],"W":63,"D":31,"H":1,"y0":69,"rotations":["SOUTH","WEST"],"vol":$vol},
            ${d1("Fairy", "Fairy", "Fairy", "fairy")},
            ${d1("Blood_A", "Blood", "Blood", "blood")}
        ],"cores":[{"core":777,"design":"Entrance_Open","variant":"v1"},{"core":5,"design":"Entrance_Open","variant":null}]}"""
        val rp = RoomPack.fromIndex(Fixtures.json(index), BytesBlobSource(blob))!!
        fun planWithCore(core: Int): DungeonPlan {
            val json = TestPlans.layoutJson.replace(""""core":null""", "").replace(""""rot":"NORTH",""", """"rot":"NORTH","core":$core,""")
            return (DungeonPlan.build(LayoutPack.fromJson(Fixtures.json(json))!!.layout("t-1")!!, 1, rp) as DungeonPlan.Result.Ok).plan
        }
        val withVariant = planWithCore(777).entrance!!
        assertEquals("Entrance_Open", withVariant.designId); assertEquals("v1", withVariant.variantId)
        assertEquals("minecraft:dirt", rp.volumeFor(withVariant.design!!, withVariant.variantId)!!.stateAt(0, 69, 0), "the variant's patch is applied")
        assertEquals("minecraft:stone", rp.volumeFor(withVariant.design!!, null)!!.stateAt(0, 69, 0))
        assertNull(planWithCore(5).entrance!!.variantId, "a core of the base volume has no variant")
    }

    @Test fun aDoorBodyIsThirtySixBlocksAroundTheSlotCentre() {
        val plan = TestPlans.plan()
        val d = plan.doorAt('S', 3, 0)!!   // the entrance door: slot centre (-89, -169)
        assertEquals(-89 to -169, d.spot)
        assertEquals(36, d.bodyCells().size)
        assertEquals("minecraft:infested_chiseled_stone_bricks", d.bodyBlock)
        assertSame(d, plan.doorContaining(-90, 69, -170))
        assertSame(d, plan.doorContaining(-88, 72, -168))
        assertNull(plan.doorContaining(-88, 73, -168), "the lintel row is not the body")
        assertNull(plan.doorContaining(-87, 70, -169))
        assertEquals("minecraft:coal_block", plan.doorAt('E', 3, 2)!!.bodyBlock, "the free fairy door is coal too")
    }
}
