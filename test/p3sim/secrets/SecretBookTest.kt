package com.engineerclient.p3sim.secrets

import com.engineerclient.p3sim.clear.secrets.Claim
import com.engineerclient.p3sim.run.BonusKind
import com.engineerclient.p3sim.run.BonusScored
import com.engineerclient.p3sim.run.CryptDestroyed
import com.engineerclient.p3sim.run.SecretFound
import com.engineerclient.p3sim.run.SecretKind
import com.engineerclient.p3sim.run.CheckMark
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretBookTest {
    private val me = UUID(0, 1)
    private val bot = UUID(0, 2)

    private fun harness(): SecretFixtures.Harness = SecretFixtures.Harness(SecretFixtures.standard()).also { h ->
        h.state.players[me] = com.engineerclient.p3sim.run.PlayerRecord(me, "Me", "ARCHER", false)
        h.state.players[bot] = com.engineerclient.p3sim.run.PlayerRecord(bot, "Bot", "MAGE", true)
    }

    @Test fun aFindMovesEveryCountAndPostsOneEvent() {
        val h = harness()
        val s = h.secretPlan.room(0)!!.sites.first { it.id == "s1" }
        val c = h.book.claim(0, SecretKind.CHEST, me, s.x, s.y, s.z)
        assertIs<Claim.Credited>(c)
        assertEquals(1, h.state.rooms[0].secretsFound)
        assertEquals(1, h.state.secrets.teamFound)
        assertEquals(1, h.state.secrets.byPlayer[me])
        assertEquals(1, h.state.players[me]!!.secretsFound)
        val ev = h.events.filterIsInstance<SecretFound>().single()
        assertEquals(0, ev.room); assertEquals(SecretKind.CHEST, ev.kind); assertEquals(me, ev.player)
        assertEquals(s.x, ev.x)
    }

    @Test fun theEventSeesTheCountsAlreadyMoved() {
        val h = harness()
        var seen = -1
        h.bus.subscribe { if (it is SecretFound) seen = h.state.rooms[it.room].secretsFound }
        val s = h.secretPlan.room(0)!!.sites.first()
        h.book.claim(0, s.kind, me, s.x, s.y, s.z)
        assertEquals(1, seen)
    }

    @Test fun aSecretCountsOnce() {
        val h = harness()
        val s = h.secretPlan.room(0)!!.sites.first()
        assertIs<Claim.Credited>(h.book.claim(0, s.kind, me, s.x, s.y, s.z))
        assertIs<Claim.Duplicate>(h.book.claim(0, s.kind, bot, s.x, s.y, s.z))
        assertEquals(1, h.state.secrets.teamFound)
        // An unlisted spot (a puzzle's reward chest) counts once per spot, and never past the maximum.
        val r3 = h.book.claim(3, SecretKind.CHEST, me, 9, 9, 9)
        assertIs<Claim.Credited>(r3)
        assertNull(r3.site, "room 3 lists no secret")
        assertIs<Claim.RoomFull>(h.book.claim(3, SecretKind.CHEST, me, 9, 9, 9), "its maximum is one")
    }

    @Test fun aRoomNeverCountsPastItsMaximum() {
        val h = harness()
        val sites = h.secretPlan.room(0)!!.sites
        for (s in sites) assertIs<Claim.Credited>(h.book.claim(0, s.kind, me, s.x, s.y, s.z))
        assertEquals(5, h.state.rooms[0].secretsFound)
        assertIs<Claim.RoomFull>(h.book.claim(0, SecretKind.CHEST, me, 1, 1, 1))
        assertIs<Claim.NoRoom>(h.book.claim(99, SecretKind.CHEST, me, 1, 1, 1))
        assertEquals(5, h.state.secrets.teamFound)
    }

    @Test fun aBotThatNamesNoSpotGetsTheNearestPendingOneOfItsKind() {
        val h = harness()
        val c = h.book.claim(0, SecretKind.BAT, bot, 0, 0, 0)
        assertIs<Claim.Credited>(c)
        assertEquals("0:s3", c.site!!.key)
        assertTrue(h.book.isFound(c.site!!))
        assertEquals(1, h.state.players[bot]!!.secretsFound)
        assertEquals(4, h.book.pending(0).size)
    }

    @Test fun essenceSkullsAreCounted() {
        val h = harness()
        val s = h.secretPlan.room(0)!!.sites.first { it.kind == SecretKind.ESSENCE }
        h.book.claim(0, SecretKind.ESSENCE, me, s.x, s.y, s.z)
        assertEquals(1, h.state.secrets.witherEssence)
    }

    @Test fun theLastPlaceableSecretCreditsTheMissingOnes() {
        val h = harness()
        val s = h.secretPlan.room(1)!!.sites.single()
        val c = h.book.claim(1, SecretKind.CHEST, me, s.x, s.y, s.z) as Claim.Credited
        assertEquals(2, c.autoCredited)
        assertEquals(3, h.state.rooms[1].secretsFound)
        assertEquals(3, h.state.secrets.teamFound)
        assertEquals(3, h.events.filterIsInstance<SecretFound>().size, "one event per counted secret")
        assertTrue(1 in h.book.autoCreditedRooms)
    }

    @Test fun autoCreditCanBeSwitchedOff() {
        val h = harness(); h.auto = false
        val s = h.secretPlan.room(1)!!.sites.single()
        val c = h.book.claim(1, SecretKind.CHEST, me, s.x, s.y, s.z) as Claim.Credited
        assertEquals(0, c.autoCredited)
        assertEquals(1, h.state.rooms[1].secretsFound)
        assertEquals(0, h.book.roomCleared(3, me, 0, 70, 0))
    }

    @Test fun aClearedRoomWithNothingPlaceableCountsItsSecrets() {
        val h = harness()
        assertEquals(1, h.book.roomCleared(3, null, -60, 70, -60))
        assertEquals(1, h.state.rooms[3].secretsFound)
        assertEquals(0, h.book.roomCleared(3, null, -60, 70, -60), "nothing left to credit")
        assertEquals(0, h.book.roomCleared(0, null, 0, 70, 0), "a room with placeable secrets waits for the player")
    }

    @Test fun cryptsAndBonusesPostEvents() {
        val h = harness()
        h.book.crypt(0, me); h.book.crypt(0, me)
        assertEquals(2, h.state.secrets.crypts)
        assertEquals(2, h.events.filterIsInstance<CryptDestroyed>().size)
        assertTrue(h.book.bonus(BonusKind.PRINCE, me))
        assertTrue(!h.book.bonus(BonusKind.PRINCE, me), "a bonus scores once")
        assertTrue(h.state.secrets.princeKilled)
        assertEquals(1, h.events.filterIsInstance<BonusScored>().size)
    }

    @Test fun aSnapshotRestoresTheLedgerExactly() {
        val h = harness()
        val sites = h.secretPlan.room(0)!!.sites
        h.book.claim(0, sites[0].kind, me, sites[0].x, sites[0].y, sites[0].z)
        h.book.claim(0, sites[1].kind, bot, sites[1].x, sites[1].y, sites[1].z)
        h.book.crypt(0, me); h.book.bonus(BonusKind.BAT, me)
        h.state.secrets.blessings["POWER"] = 5
        val snap = h.book.snapshot()
        // More happens after the snapshot...
        h.book.claim(0, sites[2].kind, me, sites[2].x, sites[2].y, sites[2].z)
        h.book.crypt(0, me)
        // ...and the restore puts the earlier state back, whatever the hub now says.
        h.book.restore(snap)
        assertEquals(2, h.state.rooms[0].secretsFound)
        assertEquals(2, h.state.secrets.teamFound)
        assertEquals(1, h.state.secrets.crypts)
        assertTrue(h.state.secrets.batKilled)
        assertEquals(1, h.state.secrets.byPlayer[me]); assertEquals(1, h.state.players[me]!!.secretsFound)
        assertEquals(5, h.state.secrets.blessings["POWER"])
        assertTrue(h.book.isFound(sites[0]) && h.book.isFound(sites[1]) && !h.book.isFound(sites[2]))
        // The third can be found again after the restore.
        assertIs<Claim.Credited>(h.book.claim(0, sites[2].kind, me, sites[2].x, sites[2].y, sites[2].z))
        assertEquals(3, h.state.secrets.teamFound)
    }

    @Test fun theDoorsModuleCanTurnTheRoomGreenOnTheEvent() {
        // The hub contract: when the count reaches the maximum the event has been delivered with the count already full.
        val h = harness()
        var full = false
        h.bus.subscribe { if (it is SecretFound && h.state.rooms[it.room].secretsFound == h.state.rooms[it.room].secretsMax) { full = true; h.state.setCheck(it.room, CheckMark.GREEN) } }
        for (s in h.secretPlan.room(0)!!.sites) h.book.claim(0, s.kind, me, s.x, s.y, s.z)
        assertTrue(full)
        assertEquals(CheckMark.GREEN, h.state.rooms[0].check)
    }
}

class SecretBookCreditRoomTest {
    @Test fun aTeammateClearingARoomCountsWhatIsLeft() {
        val h = SecretFixtures.Harness(SecretFixtures.standard())
        val bot = UUID(0, 2)
        h.state.players[bot] = com.engineerclient.p3sim.run.PlayerRecord(bot, "Bot", "MAGE", true)
        // One of room 0's five was found by the player already.
        val first = h.secretPlan.room(0)!!.sites.first()
        h.book.claim(0, first.kind, UUID(0, 1), first.x, first.y, first.z)
        assertEquals(4, h.book.creditRemaining(0, bot, -150, -150))
        assertEquals(5, h.state.rooms[0].secretsFound)
        assertEquals(4, h.state.players[bot]!!.secretsFound)
        // Room 1 has one placeable and two the data cannot place: all three count.
        assertEquals(3, h.book.creditRemaining(1, bot, -120, -150))
        assertEquals(3, h.state.rooms[1].secretsFound)
        // Room 3 places nothing: its one printed secret counts.
        assertEquals(1, h.book.creditRemaining(3, bot, -60, -60))
        assertEquals(0, h.book.creditRemaining(3, bot, -60, -60))
        assertEquals(0, h.book.creditRemaining(99, bot, 0, 0))
    }
}
