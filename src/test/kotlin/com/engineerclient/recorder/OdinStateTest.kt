package com.engineerclient.recorder

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OdinStateTest {

    private fun parse(members: String) = JsonParser.parseString("{$members}").asJsonObject

    @Test
    fun membersBuilderWritesValidJson() {
        val j = OdinJs()
        j.s("a", "x\"y").n("b", 1.5).n("c", null).b("d", true).s("e", null)
        j.obj("o") { n("i", 3); s("t", "z") }
        j.arr("l", listOf(1, 2)) { out, x -> OdinJs.num(out, x) }
        j.raw("r", "[1]")
        val o = parse(j.toString())
        assertEquals("x\"y", o["a"].asString)
        assertEquals(1.5, o["b"].asDouble)
        assertTrue(o["c"].isJsonNull)
        assertTrue(o["d"].asBoolean)
        assertEquals(3, o["o"].asJsonObject["i"].asInt)
        assertEquals(2, o["l"].asJsonArray.size())
    }

    @Test
    fun safeMemberRewindsOnError() {
        val j = OdinJs()
        j.n("a", 1)
        j.safe("bad") { it.append("[1,2,"); throw IllegalStateException("boom") }
        j.n("c", 2)
        val o = parse(j.toString())
        assertTrue(o["bad"].asJsonObject["@error"].asString.contains("boom"))
        assertEquals(2, o["c"].asInt)
    }

    @Test
    fun numbersKeepNaNAndBigLongs() {
        val sb = StringBuilder()
        OdinJs.nums(sb, Double.NaN, 1L shl 60, 0.1f)
        assertEquals("[\"NaN\",\"1152921504606846976\",0.1]", sb.toString())
    }

    @Test
    fun tileGrid() {
        assertEquals(0, OdinState.tileOf(-200, -200))
        assertEquals(0, OdinState.tileOf(-201, -201))
        assertEquals(1, OdinState.tileOf(-169, -200))
        assertEquals(6, OdinState.tileOf(-200, -169))
        assertEquals(35, OdinState.tileOf(-10, -10))
        assertNull(OdinState.tileOf(-202, -100))
        assertNull(OdinState.tileOf(-9, -100))
        assertNull(OdinState.tileOf(50, 50))
    }

    @Test
    fun mapToWorldMatchesRunRecorder() {
        // startX 5, roomGap 20 (map units): map pixel -118 is ((-118+128)/2 - 5) = 0 units -> -200.
        assertEquals(-200.0, OdinState.mapToWorld(-118, 5, 20))
        assertEquals(-168.0, OdinState.mapToWorld(-78, 5, 20))
    }

    @Test
    fun ghostDeathLines() {
        assertEquals("Steve" to "Steve", OdinState.ghostDeath(" ☠ Steve was killed by Lost Adventurer and became a ghost.") { "me" })
        assertEquals("me" to "You", OdinState.ghostDeath("☠ You were killed by Shadow Assassin and became a ghost.") { "me" })
        assertNull(OdinState.ghostDeath("Steve disconnected.") { "me" })
    }

    @Test
    fun secretSettingNames() {
        assertTrue(OdinState.SECRET_NAME.containsMatchIn("API Key"))
        assertTrue(OdinState.SECRET_NAME.containsMatchIn("Webhook URL"))
        assertTrue(OdinState.SECRET_NAME.containsMatchIn("token"))
        assertFalse(OdinState.SECRET_NAME.containsMatchIn("Message"))
    }
}
