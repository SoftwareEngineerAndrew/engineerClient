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
}
