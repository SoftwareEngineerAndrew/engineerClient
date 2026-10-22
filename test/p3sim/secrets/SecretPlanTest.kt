package com.engineerclient.p3sim.secrets

import com.engineerclient.p3sim.clear.secrets.SecretPlan
import com.engineerclient.p3sim.pack.Pos
import com.engineerclient.p3sim.pack.Rot
import com.engineerclient.p3sim.pack.Tile
import com.engineerclient.p3sim.run.SecretKind
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretPlanTest {
    private val plan = SecretFixtures.standard()
    private val sp = SecretPlan.build(plan, Random(1))

    @Test fun sitesLandWhereThePlacementLawPutsThem() {
        // Room 0: SOUTH at tile (1,1): origin (-168, -168), no rotation.
        val chest = sp.room(0)!!.sites.first { it.id == "s1" }
        assertEquals(Triple(-163, 70, -163), Triple(chest.x, chest.y, chest.z))
        assertEquals("east", chest.facing)
        assertEquals(SecretKind.CHEST, chest.kind)
        // Room 2: WEST at tile (1,2): the canonical origin corner (0,70,0) goes to px = d-1-z = 30, pz = x = 0 -> (-168+30, -136+0).
        val c2 = sp.room(2)!!.sites.first { it.id == "s1" }
        assertEquals(Triple(-138, 70, -136), Triple(c2.x, c2.y, c2.z))
        // A canonical east turned one quarter clockwise is south.
        assertEquals("south", c2.facing)
        assertEquals(1, c2.quarterTurns)
    }

    @Test fun everyRotationOfAFacingIsAClockwiseStep() {
        assertEquals("east", SecretPlan.rotateFacing("north", 1))
        assertEquals("south", SecretPlan.rotateFacing("north", 2))
        assertEquals("west", SecretPlan.rotateFacing("north", 3))
        assertEquals("north", SecretPlan.rotateFacing("west", 1))
        assertNull(SecretPlan.rotateFacing(null, 2))
        assertEquals("up", SecretPlan.rotateFacing("up", 1), "words that are not a horizontal facing stay")
    }

    @Test fun theLeverOfALockedChestIsPlacedToo() {
        val l = sp.room(0)!!.sites.first { it.kind == SecretKind.LOCKED_CHEST }
        assertEquals(Pos(-148, 71, -165), l.lever)
        assertTrue(l.baked)
    }

    @Test fun placeableAndMissingFollowTheMaximum() {
        assertEquals(5, sp.room(0)!!.placeable); assertEquals(0, sp.room(0)!!.missing)
        assertEquals(1, sp.room(1)!!.placeable); assertEquals(2, sp.room(1)!!.missing)
        assertEquals(0, sp.room(3)!!.placeable); assertEquals(1, sp.room(3)!!.missing)
        assertNotNull(sp.site("0:s3"))
        assertEquals(SecretKind.BAT, sp.site("0:s3")!!.kind)
        assertEquals("0:s3", sp.siteAt(0, -156, 72, -156)!!.key)
    }

    @Test fun moreSecretsThanTheMaximumKeepTheMainDataFirst() {
        val d = SecretFixtures.design("G", "1x1", listOf(
            SecretFixtures.secret("S1", "redstoneKey", 12, 71, 8, src = "supplemental"),
            SecretFixtures.secret("s1", "chest", 3, 70, 3),
            SecretFixtures.secret("S2", "chest", 12, 63, 23, src = "supplemental"),
        ))
        val p = SecretFixtures.plan(SecretFixtures.room(0, d, listOf(Tile(0, 0)), Rot.SOUTH, 1))
        val rs = SecretPlan.build(p, Random(1)).room(0)!!
        assertEquals(listOf("s1"), rs.sites.map { it.id })
    }

    @Test fun anAlternativeSpotIsChosenFromTheSeed() {
        val d = SecretFixtures.design("K", "1x1", listOf(SecretFixtures.secret("k", "redstoneKey", 19, 66, 7, alt = listOf(Pos(10, 70, 26)))))
        val p = SecretFixtures.plan(SecretFixtures.room(0, d, listOf(Tile(0, 0)), Rot.SOUTH, 1))
        val seen = (1..40).map { SecretPlan.build(p, Random(it)).room(0)!!.sites.single().let { s -> s.x to s.z } }.toSet()
        assertEquals(setOf((-200 + 19) to (-200 + 7), (-200 + 10) to (-200 + 26)), seen)
        assertEquals(SecretPlan.build(p, Random(5)).room(0)!!.sites.single().x, SecretPlan.build(p, Random(5)).room(0)!!.sites.single().x)
    }

    @Test fun aRoomWithoutADesignHasNoSites() {
        val bare = com.engineerclient.p3sim.run.TestPlans.plan()
        val s = SecretPlan.build(bare, Random(1))
        assertTrue(s.rooms.all { it.sites.isEmpty() })
        assertEquals(3, s.room(1)!!.missing)
    }
}
