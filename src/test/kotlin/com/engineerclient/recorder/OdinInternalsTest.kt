package com.engineerclient.recorder

import com.google.gson.JsonParser
import net.minecraft.core.BlockPos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/** Stands in for an Odin object: private state kept as static fields of a Kotlin object. */
private object FakeSolver {
    @Suppress("unused") private var counter = 7
    @Suppress("unused") private val seen = mutableListOf(BlockPos(1, 2, 3))
}

private class FakeData(@Suppress("unused") private val started: Long)

/** The pure parts of Odin Internals: reflective reads, value freezing and the change key. */
class OdinInternalsTest {

    @Test
    fun `private static fields of a Kotlin object are read by name, and missing ones say which`() {
        val cls = FakeSolver::class.java.name
        assertEquals(7, Reflect.field(cls, "counter"))
        assertEquals(listOf(BlockPos(1, 2, 3)), Reflect.field(cls, "seen"))
        val e = assertFailsWith<Reflect.Unavailable> { Reflect.field(cls, "gone") }
        assertEquals("gone", e.field)
        assertFailsWith<Reflect.Unavailable> { Reflect.field("com.example.NoSuchClass", "x") }
        assertEquals(42L, Reflect.field(FakeData(42L), "started"))
    }

    @Test
    fun `ticking members are written but are not a change`() {
        val a = OdinInternals.compose("Water", listOf(OdinInternals.Part("pattern", "3", false), OdinInternals.Part("tick", "10", true)))
        val b = OdinInternals.compose("Water", listOf(OdinInternals.Part("pattern", "3", false), OdinInternals.Part("tick", "11", true)))
        val c = OdinInternals.compose("Water", listOf(OdinInternals.Part("pattern", "4", false), OdinInternals.Part("tick", "11", true)))
        assertEquals(a.second, b.second)
        assertNotEquals(b.second, c.second)
        val o = JsonParser.parseString("{${b.first}}").asJsonObject
        assertEquals("Water", o["mod"].asString)
        assertEquals(11, o["tick"].asInt)
    }

    @Test
    fun `values freeze maps, pairs and positions as plain JSON`() {
        val v = OdinInternals.value(mapOf(BlockPos(1, 2, 3) to (BlockPos(4, 5, 6) to 7), "k" to Triple(1, "a", null), "arr" to arrayOf(1.5, 2.0)))
        val o = JsonParser.parseString(v).asJsonObject
        assertEquals("[[4,5,6],7]", o["1,2,3"].toString())
        assertEquals("[1,\"a\",null]", o["k"].toString())
        assertEquals(2, o["arr"].asJsonArray.size())
        assertEquals("null", OdinInternals.value(null))
    }

    @Test
    fun `sha1 is lowercase hex`() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", OdinInternals.sha1("abc".toByteArray()))
    }
}
