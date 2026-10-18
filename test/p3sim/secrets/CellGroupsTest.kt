package com.engineerclient.p3sim.secrets

import com.engineerclient.p3sim.clear.secrets.BreakerRules
import com.engineerclient.p3sim.clear.secrets.CellGroups
import com.engineerclient.p3sim.clear.secrets.GroupKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class CellGroupsTest {
    private val plan = SecretFixtures.standard()
    private val groups = CellGroups.build(plan)

    @Test fun groupsAreResolvedIntoTheWorld() {
        assertEquals(2, groups.groups.size)
        val tomb = groups.ofRoom(0, GroupKind.CRYPT).single()
        assertEquals(3, tomb.cells.size)
        // Canonical (25,70,25) in a SOUTH room at tile (1,1) is world (-143,70,-143).
        assertNotNull(groups.standingAt(-143, 70, -143))
        assertEquals(tomb, groups.standingAt(-142, 70, -143))
        assertNull(groups.standingAt(-143, 72, -143))
        val wall = groups.ofRoom(0, GroupKind.WALL).single()
        assertEquals(6, wall.cells.size)
    }

    @Test fun aBlastHitsTheGroupsWithABlockInReach() {
        val hit = groups.hitBy(-143.5, 70.5, -141.5, 3.0)
        assertEquals(listOf(GroupKind.CRYPT), hit.map { it.kind })
        assertTrue(groups.hitBy(-143.5, 70.5, -131.5, 3.0).isEmpty())
        // The wall at x -167: a blast on the other side of a one-block wall still reaches it.
        assertEquals(listOf(GroupKind.WALL), groups.hitBy(-164.0, 71.0, -158.0, 3.0).map { it.kind })
    }

    @Test fun aGroupIsDestroyedOnce() {
        val g = groups.groups.first()
        assertTrue(groups.destroy(g))
        assertFalse(groups.destroy(g))
        assertNull(groups.standingAt(g.cells[0].x, g.cells[0].y, g.cells[0].z))
        assertTrue(groups.hitBy(g.cells[0].x + 0.5, g.cells[0].y + 0.5, g.cells[0].z + 0.5, 3.0).none { it === g })
        groups.setDestroyed(emptyList())
        assertNotNull(groups.standingAt(g.cells[0].x, g.cells[0].y, g.cells[0].z))
        groups.setDestroyed(listOf(g.id, "nonsense"))
        assertEquals(setOf(g.id), groups.destroyedIds)
    }

    // ---- the Dungeonbreaker's dungeon rules ----------------------------------------------------------------------

    @Test fun theOuterRingOfARoomAndEverythingOutsideAreRefused() {
        // Room 0 covers x,z -168..-138.
        assertTrue(BreakerRules.isBorderOrOutside(plan, -168, -150), "west ring")
        assertTrue(BreakerRules.isBorderOrOutside(plan, -150, -138), "south ring")
        assertFalse(BreakerRules.isBorderOrOutside(plan, -167, -167), "one inside the ring is open")
        assertFalse(BreakerRules.isBorderOrOutside(plan, -153, -153), "the middle")
        assertTrue(BreakerRules.isBorderOrOutside(plan, -137, -150), "the door gap column")
        assertTrue(BreakerRules.isBorderOrOutside(plan, -190, -190), "outside any room")
        assertTrue(BreakerRules.isBorderOrOutside(plan, 5, 5), "beyond the dungeon")
    }

    @Test fun theGapInsideATwoTileRoomIsInsideButItsEndsAreTheRing() {
        // Room 1: tiles (2,1),(3,1): x -136..-75, the gap column x -105 is inside it.
        assertEquals(1, BreakerRules.cellRoom(plan, -105, -150))
        assertFalse(BreakerRules.isBorderOrOutside(plan, -105, -150))
        assertTrue(BreakerRules.isBorderOrOutside(plan, -105, -168), "its north ring")
        assertTrue(BreakerRules.isBorderOrOutside(plan, -136, -150), "its west ring")
        assertNull(BreakerRules.cellRoom(plan, -137, -150), "between room 0 and room 1")
    }

    @Test fun cryptsWallsAndUncollectedSecretsAreRefusedAndTheRestIsOpen() {
        val secret = { x: Int, y: Int, z: Int -> x == -153 && y == 70 && z == -153 }
        assertEquals(BreakerRules.REFUSAL, BreakerRules.refusal(plan, groups, secret, -143, 70, -143), "a crypt tomb")
        assertEquals(BreakerRules.REFUSAL, BreakerRules.refusal(plan, groups, secret, -167, 70, -158), "a secret wall (its x is -167, inside the ring)")
        assertEquals(BreakerRules.REFUSAL, BreakerRules.refusal(plan, groups, secret, -153, 70, -153), "a secret")
        assertEquals(BreakerRules.THERE, BreakerRules.refusal(plan, groups, secret, -168, 70, -150), "the ring says 'digging there'")
        assertEquals(BreakerRules.THERE, BreakerRules.refusal(plan, groups, secret, -190, 70, -190), "outside any room")
        assertTrue("Blood" in BreakerRules.LOCKED_ROOMS && "Tic Tac Toe" in BreakerRules.LOCKED_ROOMS)
        assertEquals("§cA mystical force prevents you digging in this room!", BreakerRules.ROOM_LOCKED)
        assertNull(BreakerRules.refusal(plan, groups, secret, -150, 70, -150))
        // Once a tomb is gone its blocks are ordinary air (and the rule no longer applies).
        groups.destroy(groups.ofRoom(0, GroupKind.CRYPT).single())
        assertNull(BreakerRules.refusal(plan, groups, secret, -143, 70, -143))
    }
}

class LeversTest {
    private val plan = SecretFixtures.standard()
    private val sp = com.engineerclient.p3sim.clear.secrets.SecretPlan.build(plan, kotlin.random.Random(1))

    @Test fun aRoomsLeverOpensItsWallAndUnlocksItsChest() {
        val (levers, free) = com.engineerclient.p3sim.clear.secrets.Levers.build(plan, sp)
        assertTrue(free.isEmpty())
        val l = levers.values.single()
        // Canonical (20,71,3) in a SOUTH room at tile (1,1) is world (-148, 71, -165).
        assertEquals(com.engineerclient.p3sim.pack.Pos(-148, 71, -165), l.pos)
        assertEquals(listOf(com.engineerclient.p3sim.pack.Pos(-166, 70, -148), com.engineerclient.p3sim.pack.Pos(-166, 71, -148)), l.opens)
        assertEquals(listOf("0:L1"), l.chests)
        assertEquals(CellGroups.key(-148, 71, -165), l.key)
    }

    @Test fun aLockedChestWithoutALeverIsNeverLocked() {
        val d = SecretFixtures.design("Q", "1x1", listOf(SecretFixtures.secret("L1", "lockedChest", 5, 70, 5), SecretFixtures.secret("L2", "lockedChest", 6, 70, 5, lever = com.engineerclient.p3sim.pack.Pos(1, 70, 1))))
        val p = SecretFixtures.plan(SecretFixtures.room(0, d, listOf(com.engineerclient.p3sim.pack.Tile(0, 0)), com.engineerclient.p3sim.pack.Rot.SOUTH, 2))
        val s = com.engineerclient.p3sim.clear.secrets.SecretPlan.build(p, kotlin.random.Random(1))
        val (levers, free) = com.engineerclient.p3sim.clear.secrets.Levers.build(p, s)
        assertEquals(setOf("0:L1"), free)
        assertEquals(1, levers.size, "a lever that opens nothing still unlocks its chest")
        assertEquals(emptyList(), levers.values.single().opens)
    }

    @Test fun puzzleRoomsAreLeftToThePuzzles() {
        val d = SecretFixtures.design("P", "1x1", emptyList(), levers = listOf(com.engineerclient.p3sim.pack.rooms.RoomLever(com.engineerclient.p3sim.pack.Pos(1, 70, 1), emptyList())))
        val p = SecretFixtures.plan(SecretFixtures.room(0, d, listOf(com.engineerclient.p3sim.pack.Tile(0, 0)), com.engineerclient.p3sim.pack.Rot.SOUTH, 0, com.engineerclient.p3sim.run.RoomType.PUZZLE))
        assertTrue(com.engineerclient.p3sim.clear.secrets.Levers.build(p, com.engineerclient.p3sim.clear.secrets.SecretPlan.build(p, kotlin.random.Random(1))).first.isEmpty())
    }
}
