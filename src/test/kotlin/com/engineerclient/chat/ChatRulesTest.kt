package com.engineerclient.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatRulesTest {

    private val rules = ChatRules.bundled()

    @Test
    fun `bundled rules load and every regex compiles in Java`() {
        assertTrue(rules.size > 100, "expected the exported rule set, got ${rules.size}")
        assertEquals(emptyList(), rules.invalid, "regexes the page accepted but Java rejects")
    }

    @Test
    fun `placeholders match what other players will see`() {
        // no rank, digit-leading name, any blessing and tier — none of these are in Andrew's logs verbatim
        val blessing = "DUNGEON BUFF! 85nd found a Blessing of Wisdom IV!"
        val profile = "You are playing on profile: Papaya (Co-op)"
        val uuid = "Profile ID: 0123abcd-4567-89ef-0123-456789abcdef"
        for (line in listOf(blessing, profile, uuid)) {
            if (rules.block.any { it.template.contains("<blessing>") || it.template.contains("<profile>") || it.template.contains("<uuid>") }) {
                assertNotNull(rules.hides(line), "should be hidden: $line")
            }
        }
    }

    @Test
    fun `multi-line announcements match as one message`() {
        if (rules.block.none { it.template.contains("fire sale") }) return
        val fireSale = "A FIRE SALE A\n♨ Selling an item for a limited time!\n   ♨ Some Other Skin (12 left)\n♨ [WARP] To Elizabeth in the next 2d 3h 4m to grab yours!"
        assertNotNull(rules.hides(fireSale))
    }

    @Test
    fun `colour codes are stripped before matching`() {
        val plain = rules.block.firstOrNull { it.template == "There are blocks in the way!" } ?: return
        assertNotNull(plain)
        assertNotNull(rules.hides(ChatRules.strip("§cThere are blocks in the way!")))
    }

    @Test
    fun `custom show rule wins over a block template`() {
        val r = ChatRules(
            custom = listOf(ChatRules.Custom("gg$", "show")),
            block = listOf(ChatRules.Block("^Party > \\w{1,16}: .*$", "party chat")),
        )
        assertNull(r.hides("Party > p3wr: gg"))
        assertNotNull(r.hides("Party > p3wr: hello"))
    }

    @Test
    fun `every boss line is hidden, including speakers never logged`() {
        assertNotNull(rules.hides("[BOSS] Goldor: Who dares trespass into my domain?"))
        assertNotNull(rules.hides("[BOSS] Sadan: So you made it all the way here... Now you wish to defy me?"))
        assertNull(rules.hides("Party > [MVP+] p3wr: [BOSS] is not at the start"))
    }

    @Test
    fun `any hit-you-for-damage line is hidden`() {
        assertNotNull(rules.hides("Bonzo's Balloon hit you for 1,234 damage."))
        assertNotNull(rules.hides("Some New Mob's Laser hit you for 50 damage."))
        assertNull(rules.hides("Party > [MVP+] p3wr: it hit you for 5 damage. lol"))
    }

    @Test
    fun `Oruo quiz answers and progress stay visible`() {
        for (line in listOf("ⓐ 10 Fairy Souls", "ⓑ Apprentice Necromancer", "ⓒ 42 Fairy Souls", "Question #1",
                            "[STATUE] Oruo the Omniscient: p3wr answered Question #2 correctly!")) {
            assertNull(rules.hides(line), "quiz line must show: $line")
        }
    }

    @Test
    fun `generalised rules catch variants that were never logged`() {
        for (line in listOf(
            "Moved 3 Some Future Item from your Sacks to your inventory.",
            "Your Frost Nova hit 4 enemies for 12,345 damage.",
            "Seismic Wave is ready to use! Press DROP to activate it!",
            "ESSENCE! SomePlayer found x5 Crimson Essence!",
            "RARE DROP! Some New Sword (+123  Magic Find)",
            "Tank Milestone ❾: You have tanked and dealt 1,000,000 Total Damage so far! 3m 2s",
        )) assertNotNull(rules.hides(line), "should be hidden: $line")
        for (line in listOf("Party > [MVP+] p3wr: leap to me", "Party > [MVP+] Friend: [Skyblocker] Leaped to p3wr!",
                            "You cannot invite that player since they're not online.")) {
            assertNull(rules.hides(line), "should stay visible: $line")
        }
    }

    @Test
    fun `centred lines with leading spaces are matched`() {
        for (raw in listOf("     §aGranted you +29 & +1.15x §b\uE01E Intelligence and +24 §f\u2726 Speed.",
                           "     Granted you +29 & +1.15x  Intelligence and +24  Speed.",
                           "   Also granted you +12 & +1.1x  Crit Damage.")) {
            assertNotNull(rules.hides(ChatRules.strip(raw)), "should be hidden: $raw")
        }
    }
}
