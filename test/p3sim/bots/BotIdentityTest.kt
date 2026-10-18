package com.engineerclient.p3sim.bots

import com.engineerclient.p3sim.clear.bots.*
import com.engineerclient.p3sim.run.PlayerRecord
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotIdentityTest {
    private val none = emptySet<String>()

    @Test fun namesAreValidAccountNames() {
        val r = Random(3)
        repeat(5000) {
            val n = BotIdentities.name(r)
            assertTrue(Regex("[A-Za-z0-9_]{3,16}").matches(n), n)
        }
        assertTrue(BotIdentities.PREFIXES.size * BotIdentities.SUFFIXES.size > 2000, "plenty of different names")
    }

    @Test fun twoBotsOfOnePartyNeverShareAName() {
        repeat(200) { seed ->
            val r = Random(seed)
            val taken = HashSet<String>()
            repeat(4) { val id = BotIdentities.draw(r, taken); assertTrue(taken.add(id.name.lowercase()), "seed $seed ${id.name}") }
        }
    }

    @Test fun ranksLevelsAndGlyphsFollowTheRecordedSpread() {
        val r = Random(11)
        val ids = (0 until 20000).map { BotIdentities.draw(r, none) }
        fun share(f: (BotIdentity) -> Boolean) = ids.count(f) / ids.size.toDouble()
        assertEquals(0.69, share { it.rank == BotRank.MVP_PLUS }, 0.02)
        assertEquals(0.12, share { it.rank == BotRank.VIP }, 0.02)
        assertEquals(0.06, share { it.rank == BotRank.MVP_PLUS_PLUS }, 0.015)
        val levels = ids.map { it.sbLevel }.sorted()
        assertEquals(93.0, levels[ids.size / 20].toDouble(), 8.0); assertEquals(255.0, levels[ids.size / 4].toDouble(), 10.0)
        assertEquals(326.0, levels[ids.size / 2].toDouble(), 10.0); assertEquals(502.0, levels[ids.size * 3 / 4].toDouble(), 10.0)
        assertTrue(levels.first() >= 40 && levels.last() <= 571)
        val cl = ids.map { it.classLevel }.sorted()
        assertEquals(48.0, cl[ids.size / 2].toDouble(), 2.0); assertEquals(50, cl.last())
        assertEquals(0.5, share { it.glyph == null }, 0.1)
        assertEquals(0.19, share { it.glyph == "§6➶" }, 0.02)
    }

    @Test fun profileIdsAreVersionFourAndRepeatForTheSameSeed() {
        val a = BotIdentities.uuid(Random(5)); val b = BotIdentities.uuid(Random(5))
        assertEquals(a, b); assertEquals(4, a.version()); assertEquals(2, a.variant())
        assertNotEquals(a, BotIdentities.uuid(Random(6)))
    }

    @Test fun theNametagReadsLikeHypixelsOwn() {
        val id = BotIdentity("Frostwren", BotRank.MVP_PLUS, 540, 50, "§6➶", UUID(0, 0))
        assertEquals("§8[§4540§8] §bFrostwren §6➶", id.nametag)
        val plain = BotIdentity("Mossmole12", BotRank.VIP, 255, 41, null, UUID(0, 0))
        assertEquals("§8[§3255§8] §aMossmole12", plain.nametag, "no glyph, no trailing space")
        assertEquals(listOf("§7", "§f", "§e", "§a", "§2", "§b", "§3", "§9", "§d", "§5", "§6", "§c", "§4"), listOf(10, 50, 100, 140, 180, 220, 260, 300, 340, 380, 420, 460, 500).map { BotIdentities.levelColour(it) })
    }

    @Test fun theLookSplitsTheNametagIntoTheTeamsPrefixAndSuffix() {
        val id = BotIdentity("Frostwren", BotRank.MVP_PLUS, 540, 50, "§6➶", UUID(7, 7))
        val look = id.look
        assertEquals("§8[§4540§8] §b", look.prefix); assertEquals(" §6➶", look.suffix); assertEquals("§b", look.nameColour)
        assertEquals("Frostwren", look.name); assertEquals(UUID(7, 7), look.profileId)
        assertEquals(id.nametag, look.tag)
        val plain = BotIdentity("Mossmole12", BotRank.VIP, 255, 41, null, UUID(8, 8)).look
        assertEquals("", plain.suffix); assertEquals("§a", plain.nameColour)
        assertEquals("§8[§3255§8] §aMossmole12", plain.tag)
    }

    @Test fun theRecordCarriesWhatTheOtherSurfacesRead() {
        val id = BotIdentity("Frostwren", BotRank.MVP_PLUS_PLUS, 326, 48, null, UUID(1, 1))
        val rec = PlayerRecord(id.uuid, id.name, "MAGE", true)
        id.applyTo(rec)
        assertEquals(326, rec.extras["sbLevel"]); assertEquals(48, rec.extras["classLevel"])
        assertEquals("§6", PartyStyle.nameColour(rec)); assertEquals("§6[MVP§c++§6]", PartyStyle.bracket(rec)); assertEquals("", PartyStyle.glyph(rec))
        // A record with no identity (a human) reads as the commonest rank, and has no glyph preference.
        val human = PlayerRecord(UUID(2, 2), "Someone", "HEALER", false)
        assertEquals("§b", PartyStyle.nameColour(human)); assertEquals("§b[MVP§c+§b]", PartyStyle.bracket(human)); assertNull(PartyStyle.glyph(human))
        assertEquals("§b", PartyStyle.nameColour(null))
    }
}
