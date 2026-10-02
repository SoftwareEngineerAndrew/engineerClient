package com.engineerclient.recorder

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure parts of the input capture: sample batching and what a typed line may say. */
class InputJsonTest {

    @Test
    fun samplesBatchIntoOneArrayAndEmpty() {
        val b = InputJson.SampleBuffer()
        val owner = Any()
        b.add(owner) { it.append("1,2.5,3.0,true") }
        b.add(owner) { it.append("2,4.0,5.0,false") }
        assertEquals(2, b.count)
        val d = b.take(owner)
        assertEquals("\"d\":[[1,2.5,3.0,true],[2,4.0,5.0,false]]", d)
        val arr = JsonParser.parseString("{$d}").asJsonObject["d"].asJsonArray
        assertEquals(2, arr.size())
        assertNull(b.take(owner))
    }

    @Test
    fun samplesFromAnEndedSessionNeverReachTheNext() {
        val b = InputJson.SampleBuffer()
        val old = Any(); val new = Any()
        b.add(old) { it.append("1") }
        assertNull(b.take(new))
        assertEquals(0, b.count)
        b.add(old) { it.append("1") }
        b.add(new) { it.append("2") }
        assertEquals("\"d\":[[2]]", b.take(new))
        assertNull(b.take(null))
    }

    @Test
    fun commandRootIsTheFirstWordWithoutSlash() {
        assertEquals("msg", InputJson.commandRoot("/msg Bob hi"))
        assertEquals("pc", InputJson.commandRoot("pc hello there"))
        assertEquals("warp", InputJson.commandRoot("warp"))
    }

    @Test
    fun typedTextOnlyWithTypedChat() {
        val on = JsonParser.parseString("{${InputJson.typedBody("chat", "hello \"you\"", true, null)}}").asJsonObject
        assertEquals("hello \"you\"", on["text"].asString)
        assertFalse(on.has("redacted"))

        val off = JsonParser.parseString("{${InputJson.typedBody("chat", "secret", false, null)}}").asJsonObject
        assertFalse(off.has("text"))
        assertTrue(off["redacted"].asBoolean)
        assertEquals(6, off["len"].asInt)
        assertFalse(off.has("root"))
        assertFalse(off.toString().contains("secret"))
    }

    @Test
    fun redactedCommandsKeepTheirName() {
        val cmd = JsonParser.parseString("{${InputJson.typedBody("command", "msg Bob my password", false, true)}}").asJsonObject
        assertEquals("msg", cmd["root"].asString)
        assertTrue(cmd["cancelled"].asBoolean)
        assertFalse(cmd.toString().contains("password"))

        val odinCmd = JsonParser.parseString("{${InputJson.typedBody("odin", "/pc hi", false, false)}}").asJsonObject
        assertEquals("pc", odinCmd["root"].asString)
        val odinChat = JsonParser.parseString("{${InputJson.typedBody("odin", "hi", false, false)}}").asJsonObject
        assertFalse(odinChat.has("root"))
    }
}
