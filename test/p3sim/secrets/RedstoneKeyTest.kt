package com.engineerclient.p3sim.secrets

import com.engineerclient.p3sim.clear.secrets.Claim
import com.engineerclient.p3sim.clear.secrets.RedstoneKeys
import com.engineerclient.p3sim.clear.secrets.SecretBook
import com.engineerclient.p3sim.clear.secrets.SecretPlan
import com.engineerclient.p3sim.pack.Pos
import com.engineerclient.p3sim.pack.Rot
import com.engineerclient.p3sim.pack.Tile
import com.engineerclient.p3sim.run.RunBus
import com.engineerclient.p3sim.run.RunState
import com.engineerclient.p3sim.run.SecretKind
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The three key rooms, with invented designs under their ids: where the gate, socket, door and chests land, and what counts. */
class RedstoneKeyTest {
    private fun crypt(rot: Rot = Rot.SOUTH) = SecretFixtures.design("Redstone_Crypt", "1x1", listOf(
        SecretFixtures.secret("s1", "chest", 9, 69, 3, "south"),
        SecretFixtures.secret("L1", "lockedChest", 29, 70, 15, "west", lever = Pos(15, 68, 15), baked = true),
        SecretFixtures.secret("D3", "redstoneKey", 4, 71, 4, baked = true, src = "recording"),
    )).let { SecretFixtures.plan(SecretFixtures.room(0, it, listOf(Tile(0, 0)), rot, 3)) }

    private fun keyRoom() = SecretFixtures.design("Redstone_Key", "1x1", listOf(SecretFixtures.secret("s1", "item", 25, 69, 5)))
        .let { SecretFixtures.plan(SecretFixtures.room(0, it, listOf(Tile(0, 0)), Rot.SOUTH, 3)) }

    private fun oasis(rot: Rot = Rot.SOUTH) = SecretFixtures.design("Golden_Oasis", "1x1", listOf(
        SecretFixtures.secret("S1", "redstoneKey", 12, 71, 8, src = "supplemental"),
        SecretFixtures.secret("S2", "chest", 12, 63, 23, src = "supplemental"),
        SecretFixtures.secret("S3", "chest", 13, 63, 25, src = "supplemental"),
    )).let { SecretFixtures.plan(SecretFixtures.room(0, it, listOf(Tile(0, 0)), rot, 1)) }

    @Test fun theCryptCountsItsChestItsLockedChestAndTheSocketNotTheKey() {
        val rs = SecretPlan.build(crypt(), Random(1)).room(0)!!
        assertEquals(listOf("s1", "L1", "SK"), rs.sites.map { it.id })
        assertEquals(3, rs.placeable)
        val gate = rs.gates.single()
        assertEquals(Triple(-196, 71, -196), Triple(gate.x, gate.y, gate.z), "the pack's key skull is the gate")
        val socket = rs.sites.single { it.id == "SK" }
        assertEquals(SecretKind.REDSTONE_KEY, socket.kind)
        assertEquals(Triple(-174, 72, -194), Triple(socket.x, socket.y, socket.z))
        val info = assertNotNull(socket.socket)
        assertEquals(Pos(-174, 73, -194), info.head.pos)
        assertNull(info.head.wallFacing)
        assertEquals(0, info.head.rotation)
        assertEquals(7, info.opens.size)
        assertTrue(Pos(-188, 70, -185) in info.opens && Pos(-185, 69, -185) in info.opens)
        assertTrue(info.chests.isEmpty())
    }

    @Test fun theKeyRoomGetsItsKeySocketAndTheChestTheDoorReveals() {
        val plans = (1..40).map { SecretPlan.build(keyRoom(), Random(it)).room(0)!! }
        val rs = plans.first()
        assertEquals(listOf("s1", "SK", "KC"), rs.sites.map { it.id })
        val kc = rs.sites.single { it.id == "KC" }
        assertEquals(SecretKind.CHEST, kc.kind); assertTrue(kc.hidden)
        assertEquals(Triple(-172, 62, -177), Triple(kc.x, kc.y, kc.z))
        assertEquals("south", kc.facing)
        // The key stands at one of the two recorded spots, picked by the seed.
        assertEquals(setOf(Triple(-190, 70, -174), Triple(-181, 66, -193)), plans.map { it.gates.single().let { g -> Triple(g.x, g.y, g.z) } }.toSet())
        assertEquals(plans[3].gates.single().key, SecretPlan.build(keyRoom(), Random(4)).room(0)!!.gates.single().key)
        assertEquals(4, rs.sites.single { it.id == "SK" }.socket!!.opens.size)
    }

    @Test fun theOasisCountsOnlyTheSocketAndItsTwoChestsAreLootOnly() {
        val rs = SecretPlan.build(oasis(), Random(1)).room(0)!!
        assertEquals(listOf("SK"), rs.sites.map { it.id })
        assertEquals(0, rs.missing)
        assertEquals(listOf("R1", "R2"), rs.bonus.map { it.id })
        assertTrue(rs.bonus.all { it.hidden && it.kind == SecretKind.CHEST })
        val gate = rs.gates.single()
        assertEquals(Triple(-188, 71, -192), Triple(gate.x, gate.y, gate.z), "the supplemental key site is the gate")
        val head = rs.sites.single().socket!!.head
        assertEquals("south", head.wallFacing)
        assertEquals(Pos(-190, 71, -196), head.pos)
        assertNotNull(SecretPlan.build(oasis(), Random(1)).bonusAt(0, rs.bonus[0].x, rs.bonus[0].y, rs.bonus[0].z))
    }

    @Test fun aRoomTurnedAroundTurnsTheHeadAndTheChestsWithIt() {
        val north = SecretPlan.build(oasis(Rot.NORTH), Random(1)).room(0)!!
        assertEquals("north", north.sites.single().socket!!.head.wallFacing, "south turned two quarter turns")
        assertTrue(north.bonus.all { it.facing == "south" }, "north turned two quarter turns")
        val east = SecretPlan.build(crypt(Rot.EAST), Random(1)).room(0)!!
        assertEquals(12, east.sites.single { it.id == "SK" }.socket!!.head.rotation, "a floor head turns four sixteenths per quarter turn")
    }

    @Test fun theSecretCountsWhenTheKeyIsUsedNotWhenItIsPickedUp() {
        val plan = oasis()
        val sp = SecretPlan.build(plan, Random(1))
        val bus = RunBus(); val state = RunState(plan, bus) { 0 }
        val book = SecretBook(sp, state, bus, { 0 }, { true })
        val me = UUID(0, 1)
        val gate = sp.room(0)!!.gates.single()
        // The gate is not a site: nothing at the key's spot is a secret.
        assertNull(sp.siteAt(0, gate.x, gate.y, gate.z))
        assertEquals(0, state.rooms[0].secretsFound)
        val socket = sp.room(0)!!.sites.single()
        assertIs<Claim.Credited>(book.claim(0, SecretKind.REDSTONE_KEY, me, socket.x, socket.y, socket.z))
        assertEquals(1, state.rooms[0].secretsFound)
        assertIs<Claim.RoomFull>(book.claim(0, SecretKind.CHEST, me, 1, 1, 1), "the loot chests add nothing once the socket met the count")
        // A teammate who names no spot for a key reaches the socket.
        val book2 = SecretBook(sp, RunState(plan, RunBus()) { 0 }, RunBus(), { 0 }, { true })
        assertEquals(socket.key, (book2.claim(0, SecretKind.REDSTONE_KEY, me, 0, 0, 0) as Claim.Credited).site!!.key)
    }

    @Test fun otherRoomsKeepTheirKeySiteAsASecret() {
        val d = SecretFixtures.design("Some_Room", "1x1", listOf(SecretFixtures.secret("k", "redstoneKey", 19, 66, 7)))
        val rs = SecretPlan.build(SecretFixtures.plan(SecretFixtures.room(0, d, listOf(Tile(0, 0)), Rot.SOUTH, 1)), Random(1)).room(0)!!
        assertEquals(1, rs.sites.size); assertTrue(rs.gates.isEmpty()); assertNull(rs.sites.single().socket)
    }

    @Test fun theTableCoversTheThreeRooms() {
        assertEquals(setOf("Redstone_Crypt", "Redstone_Key", "Golden_Oasis"), RedstoneKeys.ROOMS.map { it.designId }.toSet())
        assertNull(RedstoneKeys.forDesign("Admin")); assertNull(RedstoneKeys.forDesign(null))
        assertEquals(Pos(27, 68, 7), RedstoneKeys.forDesign("Redstone_Key")!!.socket)
    }
}
