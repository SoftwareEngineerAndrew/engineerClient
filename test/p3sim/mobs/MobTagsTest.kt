package com.engineerclient.p3sim.mobs

import com.engineerclient.p3sim.clear.mobs.MobTags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The name tag grammar (FORMAT-mobs 3.3) and gate G6: every tag the formatter makes passes the star regexes of Odin and EC. */
class MobTagsTest {
    private val pack = MobFixtures.pack()

    @Test fun hpTextVectors() {
        assertEquals("1.3M", MobTags.formatHp(1_300_000.0))
        assertEquals("1M", MobTags.formatHp(999_950.0))
        assertEquals("999.9k", MobTags.formatHp(999_900.0))
        assertEquals("2M", MobTags.formatHp(2_000_000.0))
        assertEquals("900k", MobTags.formatHp(900_000.0))
        assertEquals("862.5k", MobTags.formatHp(862_500.0))
        assertEquals("94,500", MobTags.formatHp(94_500.0))
        assertEquals("90,000", MobTags.formatHp(90_000.0))
        assertEquals("150M", MobTags.formatHp(150_000_000.0))
        assertEquals("1B", MobTags.formatHp(1_000_000_000.0))
        assertEquals("1.6B", MobTags.formatHp(1_600_000_000.0))
        assertEquals("0", MobTags.formatHp(0.0))
        assertEquals("100k", MobTags.formatHp(100_000.0))
    }

    @Test fun hpTiesGoToEven() {
        // Measured on 81,316 real pairs: the exact binary value decides, ties round to even.
        assertEquals("1.2M", MobTags.formatHp(1_250_000.0))
        assertEquals("1.4M", MobTags.formatHp(1_350_000.0))
        assertEquals("1.6M", MobTags.formatHp(1_650_000.0))
        assertEquals("6M", MobTags.formatHp(5_950_000.0))
    }

    private fun tag(arch: String, mod: String?, hp: Double, max: Double, star: Boolean = true): String {
        val a = pack.archetypeById(arch)!!
        return MobTags.nametagLegacy(a.tag!!, a.name, mod?.let { pack.modifier(it) }, hp, max, star)
    }

    @Test fun plainStarredTag() {
        assertEquals("§2W §r§6✯ §r§cTest Walker§r §a1.3M§r§c❤", tag("test_walker", null, 1_300_000.0, 1_300_000.0))
        assertEquals("W ✯ Test Walker 1.3M❤", MobTags.plain(tag("test_walker", null, 1_300_000.0, 1_300_000.0)))
    }

    @Test fun hpColourTurnsYellowAtHalf() {
        assertTrue(tag("test_walker", null, 700_000.0, 1_300_000.0).contains("§r §a700k"))
        assertTrue(tag("test_walker", null, 650_000.0, 1_300_000.0).contains("§r §e650k"))
    }

    @Test fun modifierRecoloursTheName() {
        assertEquals("§5S §r§6✯ §r§2Healthy Test Sleeper§r §a2.4M§r§c❤", tag("test_sleeper", "Healthy", 2_400_000.0, 2_400_000.0))
        assertEquals("§fA §r§6✯ §r§3Speedy Test Archer§r §b1.6M§r§c❤", tag("test_archer", "Speedy", 1_600_000.0, 1_600_000.0))
    }

    @Test fun flamingHasNoColourAndLosesTheHeadsReset() {
        assertEquals("§2W §r§6✯ Flaming Test Walker§r §e1.3M§r§c❤", tag("test_walker", "Flaming", 1_300_000.0, 1_300_000.0))
    }

    @Test fun minibossIsBoldLightPurple() {
        assertEquals("§eB§r§5 §r§6✯ §r§d§lTest Boss§r §a2.6M§r§c❤", tag("test_boss", null, 2_600_000.0, 2_600_000.0))
    }

    @Test fun unstarredHeadDropsTheStar() {
        assertEquals("§2W §r", MobTags.unstarredHead("§2W §r§6✯ §r"))
        assertEquals("§2◆§r§8◆§r§6 §r", MobTags.unstarredHead("§2◆§r§8◆§r§6 ✯ §r"))
        assertEquals("§eX§r§5 §r", MobTags.unstarredHead("§eX§r§5 §r"))
        assertEquals("§2W §r§cTest Walker§r §a1.3M§r§c❤", tag("test_walker", null, 1_300_000.0, 1_300_000.0, star = false))
    }

    @Test fun splitModifierWritesTheWordAsItsOwnSegment() {
        assertEquals("§4 §r§2Healthy §r§8§lTest Biter§r §a1.6B§r§c❤", tag("test_biter", "Healthy", 1_600_000_000.0, 1_600_000_000.0, star = false))
        assertEquals("§4 §r§6Flaming §r§8§lTest Biter§r §e1B§r§c❤", tag("test_biter", "Flaming", 1_000_000_000.0, 1_000_000_000.0, star = false))
        assertEquals("§4 §r§8§lTest Biter§r §a1B§r§c❤", tag("test_biter", null, 1_000_000_000.0, 1_000_000_000.0, star = false))
    }

    /** Gate G6: every archetype x every modifier x the HP values of the spec, starred, passes the regex Odin and EC use. */
    @Test fun everyGeneratedStarredTagPassesTheStarRegexes() {
        val odin = Regex("^.*✯ .*\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?.?❤$")   // Odin Highlight.starredRegex
        val ec = Regex("^.*✯ .*\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?.?❤$")     // EC BrWaypoints2.STARRED and RandomStuff.STARRED_TAG
        var n = 0
        for (a in pack.archetypes) {
            if (a.tag == null || !a.star) continue   // an unstarred archetype's head has no star to match
            for (m in listOf(null) + pack.modifiers.filter { it.id.isNotEmpty() }) for (hp in listOf(420_000.0, 483_000.0, 1_200_000.0, 94_500.0, 90_000.0, 2_600_000_000.0)) {
                val legacy = MobTags.nametagLegacy(a.tag!!, a.name, m, hp, hp, true)
                val plain = MobTags.plain(legacy)
                // The modSplit archetype is an unstarred one in the pack; but made starred it still passes.
                assertTrue(odin.matches(plain), plain); assertTrue(ec.matches(plain), plain)
                assertTrue(MobTags.looksStarred(legacy), plain)
                assertFalse(plain.contains('§'), plain)
                n++
            }
        }
        assertTrue(n > 100)
    }

    @Test fun unstarredTagsDoNotLookStarred() {
        assertFalse(MobTags.looksStarred(tag("test_walker", null, 1_300_000.0, 1_300_000.0, star = false)))
    }
}
