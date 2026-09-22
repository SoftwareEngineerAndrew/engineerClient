package com.engineerclient.rotation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every line here was taken from a real captured log, not invented. The two instances differ:
 * the sandbox one shows Hypixel's raw wording, the main one shows what reaches us after other
 * dungeon mods have rewritten the line (article dropped, name coloured, split times appended).
 * Both have to parse, because the party runs the second setup.
 */
class P3ChatParserTest {

    @Test
    fun `hypixel wording`() {
        val c = assertNotNull(P3ChatParser.completion("p3wr activated a terminal! (1/7)"))
        assertEquals("p3wr", c.ign)
        assertEquals("terminal", c.type)
        assertEquals(1, c.done)
        assertEquals(7, c.total)
        assertTrue(!c.sectionDone)
    }

    @Test
    fun `rewritten by another mod - article dropped, colours, split times appended`() {
        val c = assertNotNull(P3ChatParser.completion("p3wr §aactivated terminal! (§c2§a/7) §8(§71.237s §8| §71.237s§8)"))
        assertEquals("p3wr", c.ign)
        assertEquals("terminal", c.type)
        assertEquals(2, c.done)
        assertEquals(7, c.total)
    }

    @Test
    fun `coloured name prefix`() {
        val c = assertNotNull(P3ChatParser.completion("§6p3wr §aactivated a lever! (§c1§a/7) §8(§7243.162s §8| §7243.162s§8)"))
        assertEquals("p3wr", c.ign)
        assertEquals("lever", c.type)
    }

    @Test
    fun `devices and the section-closing line`() {
        val device = assertNotNull(P3ChatParser.completion("Strohhut6246 §acompleted device! (§c6§a/7)"))
        assertEquals("device", device.type)
        assertEquals("Strohhut6246", device.ign)

        val closing = assertNotNull(P3ChatParser.completion("qtSylvie activated a lever! (8/8)"))
        assertTrue(closing.sectionDone, "8/8 closes the section")
    }

    @Test
    fun `phase boundaries`() {
        assertTrue(P3ChatParser.isPhaseStart("[BOSS] Goldor: Who dares trespass into my domain?"))
        assertTrue(P3ChatParser.isGateDestroyed("§aThe gate has been destroyed! §8(§72.0s §8| §72.0s§8)"))
        assertTrue(P3ChatParser.isPhaseEnd("The Core entrance is opening!"))
        assertTrue(!P3ChatParser.isPhaseStart("[BOSS] Goldor: I won't let you break the factory core"))
    }

    @Test
    fun `party lines, ranked and not`() {
        // Verbatim from the party's own logs.
        val a = assertNotNull(P3ChatParser.partyLine("§9Party §8> §6[MVP§d++§6] m7kitten§f: Spirit Procced! (1/3)"))
        assertEquals("m7kitten", a.ign)
        assertEquals("Spirit Procced! (1/3)", a.message)

        val b = assertNotNull(P3ChatParser.partyLine("§9Party §8> §a[VIP§6+§a] Strohhut6246§f: Bonzo Procced! (3/3)"))
        assertEquals("Strohhut6246", b.ign)

        // Minecraft's repeat counter must not break the parse.
        val c = assertNotNull(P3ChatParser.partyLine("§9Party §8> §6[MVP§d++§6] sparkle43§f: Spirit Procced! (1/3) (2)"))
        assertEquals("sparkle43", c.ign)

        assertNull(P3ChatParser.partyLine("p3wr activated a terminal! (1/7)"))
    }

    @Test
    fun `mask procs, self and party`() {
        MaskTracker.reset()
        assertEquals(3, MaskTracker.available("me"))

        assertEquals(MaskTracker.Kind.SPIRIT, MaskTracker.onSelfLine("me", "Second Wind Activated! Your Spirit Mask saved your life!"))
        assertEquals(2, MaskTracker.available("me"), "spirit is on cooldown")

        // Verbatim: Hypixel puts a private-use glyph between "Your" and "Bonzo's".
        assertEquals(MaskTracker.Kind.BONZO, MaskTracker.onSelfLine("me", "Your \uE068 Bonzo's Mask saved your life!"))
        assertEquals(1, MaskTracker.available("me"))

        val party = assertNotNull(P3ChatParser.partyLine("§9Party §8> §6[MVP§d++§6] m7kitten§f: Phoenix Procced! (2/3)"))
        assertEquals(MaskTracker.Kind.PHOENIX, MaskTracker.onPartyAnnouncement(party.ign, party.message))
        assertEquals(2, MaskTracker.available("m7kitten"))

        // Spirit is the shortest at 600 ticks; run it out and it comes back.
        repeat(600) { MaskTracker.tick() }
        assertEquals(2, MaskTracker.available("me"), "spirit recovered, bonzo still down")
        assertEquals(0, MaskTracker.cooldownTicks("me", MaskTracker.Kind.SPIRIT))
        MaskTracker.reset()
    }

    @Test
    fun `positional messages and leap announcements as they arrive`() {
        // The party's own /posmsg texts, and Odin's leap announce, verbatim from the logs.
        val arrive = assertNotNull(P3ChatParser.partyLine("§9Party §8> §b[MVP§4+§b] p3wr§f: At High EE2!"))
        assertEquals("At High EE2!", arrive.message)

        val leap = assertNotNull(P3ChatParser.partyLine("§9Party §8> §b[MVP§c+§b] cancelledpackets§f: Leaped to Skyyqt!"))
        assertEquals("Skyyqt", P3ChatParser.leapedTo(leap.message))
        assertNull(P3ChatParser.leapedTo("ee3"))
        assertEquals("Skyyqt", P3ChatParser.teleportedTo("You have teleported to Skyyqt!"))

        // Andrew's own leap, as it reaches every client — long numeric IGN, MVP+ rank.
        val mine = assertNotNull(P3ChatParser.partyLine("§9Party §8> §b[MVP§4+§b] p3wr§f: Leaped to Shadow100119171!"))
        assertEquals("p3wr", mine.ign)
        assertEquals("Shadow100119171", P3ChatParser.leapedTo(mine.message))
    }

    @Test
    fun `starting-role announcements`() {
        val a = assertNotNull(P3ChatParser.partyLine("§9Party §8> §b[MVP§4+§b] p3wr§f: brw s1 43"))
        assertEquals("43", P3ChatParser.startingRole(a.message))
        assertEquals("l+ee2", P3ChatParser.startingRole("BRW S1 l+ee2"), "case-insensitive, plus sign kept")
        assertNull(P3ChatParser.startingRole("brw s1"), "a role is required")
        assertNull(P3ChatParser.startingRole("at s1 43"), "party chatter must not bind anyone")
    }

    @Test
    fun `lines that must not match`() {
        // Hypixel's own near-misses, and party chatter quoting the same words.
        assertNull(P3ChatParser.completion("Someone has already activated this lever!"))
        assertNull(P3ChatParser.completion("§9Party §8> §b[MVP§c+§b] cancelledpackets§f: Melody Terminal start!"))
        assertNull(P3ChatParser.completion("§9Party §8> §a[VIP§6+§a] Strohhut6246§f: At Simon Says Device!"))
        assertNull(P3ChatParser.completion("[BOSS] Goldor: What do you think you are doing there!"))
        // A mod relaying completions into party chat — verbatim from a run — must not be credited.
        assertNull(P3ChatParser.completion("§9Party §8> §b[MVP§4+§b] p3wr§f: p3wr activated lever! (2/7)"))
    }
}
