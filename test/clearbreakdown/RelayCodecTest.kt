package com.engineerclient.clearbreakdown

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelayCodecTest {
    /** What the site sends on: the client's frame with the sender added. */
    private fun relayed(frame: String, from: String) = JsonParser.parseString(frame).asJsonObject.apply { addProperty("from", from) }.toString()

    @Test fun roundTrip() {
        val items = listOf(Relayed.Secret("chest:1,2,3", "k1", "Mirror", listOf("A", "B"), false), Relayed.Presence("A", "k1"),
            Relayed.Presence("B", ""), Relayed.Presence("C", null))
        assertEquals("Bob" to items, RelayCodec.decode(relayed(RelayCodec.frames(items).single(), "Bob")))
    }

    @Test fun splitsUnderLimit() {
        val items = (1..400).map { Relayed.Secret("item:$it", "k$it", "Room $it", listOf("Player$it"), true) }
        val fs = RelayCodec.frames(items)
        assertTrue(fs.size > 1)
        assertTrue(fs.all { it.length <= 3500 })
        assertEquals(items, fs.flatMap { RelayCodec.decode(relayed(it, "x"))!!.second })
    }

    @Test fun emptyMakesNoFrames() {
        assertEquals(emptyList(), RelayCodec.frames(emptyList()))
    }

    @Test fun garbageIsNull() {
        assertNull(RelayCodec.decode("pong"))
        assertNull(RelayCodec.decode("{\"t\":\"online\"}"))
        assertNull(RelayCodec.decode("{\"t\":\"cb\",\"d\":[]}"))
    }

    @Test fun badItemsSkipped() {
        assertEquals("Bob" to listOf(Relayed.Presence("A", "k")),
            RelayCodec.decode("""{"t":"cb","from":"Bob","d":[{"s":1},{"q":2},{"p":"A","r":"k"}]}"""))
    }
}
