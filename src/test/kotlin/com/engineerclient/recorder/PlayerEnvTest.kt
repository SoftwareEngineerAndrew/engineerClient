package com.engineerclient.recorder

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals

/** The pure parts of the player, frame and options capture: slot diffs, frame rows, option values. */
class PlayerEnvTest {

    /** A stand-in for ItemStack: mutable in place, compared by contents. */
    private class Stack(val id: String, var count: Int)
    private fun tracker() = SlotTracker<Stack>({ a, b -> a.id == b.id && a.count == b.count }, { Stack(it.id, it.count) })

    @Test
    fun `slot tracker reports everything first, then only real changes, including in-place count changes`() {
        val t = tracker()
        val b = Stack("arrow", 10)
        val inv = mutableListOf(Stack("sword", 1), b, Stack("air", 0))
        assertEquals(listOf(0, 1, 2), t.diff(inv))
        assertEquals(emptyList(), t.diff(inv))
        // A new object with the same contents is no change.
        inv[0] = Stack("sword", 1)
        assertEquals(emptyList(), t.diff(inv))
        // The same object with its count changed in place is one.
        b.count = 9
        assertEquals(listOf(1), t.diff(inv))
        inv[2] = Stack("bow", 1)
        assertEquals(listOf(2), t.diff(inv))
        t.seed(listOf(Stack("stone", 1)))
        assertEquals(emptyList(), t.diff(listOf(Stack("stone", 1))))
        t.reset()
        assertEquals(listOf(0), t.diff(listOf(Stack("stone", 1))))
    }

    @Test
    fun `frame rows are exact JSON arrays joined by commas`() {
        val sb = StringBuilder()
        FrameCapture.frameRow(sb, 123L, 0.25f, 90.5f, -12.75f, 0.1 + 0.2, 64.0, -3.5, 70f, false, "NONE", 7, 16_666_667L)
        FrameCapture.frameRow(sb, 124L, Float.NaN, 0f, 0f, 1.0, 2.0, 3.0, 80f, true, "WATER", 7, 1L)
        val arr = JsonParser.parseString("[$sb]").asJsonArray
        assertEquals(2, arr.size())
        val r = arr[0].asJsonArray
        assertEquals(12, r.size())
        assertEquals(0.30000000000000004, r[4].asDouble)
        assertEquals(0, r[8].asInt)
        assertEquals("NONE", r[9].asString)
        assertEquals("NaN", arr[1].asJsonArray[1].asString)
        assertEquals(1, arr[1].asJsonArray[8].asInt)
    }

    private enum class Mode { FANCY }

    @Test
    fun `option values are written as exact JSON`() {
        assertEquals("true", EnvOptions.value(true))
        assertEquals("0.1", EnvOptions.value(0.1))
        assertEquals("12", EnvOptions.value(12))
        assertEquals("\"FANCY\"", EnvOptions.value(Mode.FANCY))
        assertEquals("\"en_us\"", EnvOptions.value("en_us"))
        assertEquals("null", EnvOptions.value(null))
        assertEquals("\"Infinity\"", EnvOptions.value(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `option diff lists changed, new and removed entries`() {
        val before = mapOf("fov" to "70", "gamma" to "0.5", "gone" to "1")
        val now = mapOf("fov" to "90", "gamma" to "0.5", "new" to "true")
        assertEquals(listOf("fov" to "90", "new" to "true", "gone" to "null"), EnvOptions.diff(before, now))
        assertEquals(emptyList(), EnvOptions.diff(now, now))
    }
}
