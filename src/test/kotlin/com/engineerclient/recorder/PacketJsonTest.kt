package com.engineerclient.recorder

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import it.unimi.dsi.fastutil.ints.IntArrayList
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.protocol.game.ClientboundGameEventPacket
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.common.ClientboundStoreCookiePacket
import net.minecraft.resources.Identifier
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.phys.Vec3
import java.util.BitSet
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Packets and values written losslessly, as valid JSON with their real field names. */
class PacketJsonTest {

    companion object {
        init {
            // Registries (items, blocks, particles) need the game bootstrapped; it runs headlessly.
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    private fun json(v: Any?): JsonObject = JsonParser.parseString(PacketJson.writeNow(v)).asJsonObject
    private fun raw(v: Any?) = PacketJson.writeNow(v)
    private fun captured(p: net.minecraft.network.protocol.Packet<*>) = JsonParser.parseString(PacketJson.capture(p)()).asJsonObject

    @Test
    fun `a record packet keeps its field names, its class and exact numbers`() {
        val o = json(ClientboundSetEntityMotionPacket(42, Vec3(0.1, -1.25, 1e-7)))
        assertEquals("ClientboundSetEntityMotionPacket", o["@c"].asString)
        assertEquals(42, o["id"].asInt)
        val m = o["movement"].asJsonArray
        assertEquals(0.1, m[0].asDouble)
        assertEquals(1e-7, m[2].asDouble)
    }

    @Test
    fun `numbers keep their exact value, non-finite and huge ones as strings`() {
        assertEquals("[\"NaN\",\"Infinity\",\"-Infinity\",0.30000000000000004]", raw(listOf(Double.NaN, Double.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0.1 + 0.2)))
        assertEquals("[9007199254740992,\"9007199254740993\",\"-9223372036854775808\"]", raw(listOf(1L shl 53, (1L shl 53) + 1, Long.MIN_VALUE)))
        assertEquals("0.1", raw(0.1f))
    }

    @Test
    fun `strings are uncapped and escaped, lone surrogates kept`() {
        val long = "x".repeat(10_000)
        assertEquals(long, JsonParser.parseString(raw(long)).asString)
        assertEquals("a\"b\\c\n\u0001\uD83D\uDE00", JsonParser.parseString(raw("a\"b\\c\n\u0001\uD83D\uDE00")).asString)
        assertEquals("\"\\ud800\"", raw("\uD800"))
    }

    @Test
    fun `lists are uncapped`() {
        val ids = IntArrayList((0 until 1000).toList().toIntArray())
        assertEquals(1000, json(ClientboundRemoveEntitiesPacket(ids))["entityIds"].asJsonArray.size())
    }

    @Test
    fun `a cycle is cut, a shared value is written each time`() {
        val a = ArrayList<Any>()
        a.add(a)
        assertEquals("[{\"@cycle\":\"ArrayList\"}]", raw(a))
        val shared = listOf(1, 2)
        assertEquals("[[1,2],[1,2]]", raw(listOf(shared, shared)))
    }

    @Test
    fun `nesting past the safety depth is written as text`() {
        var v: Any = listOf(1)
        repeat(80) { v = listOf(v) }
        val s = raw(v)
        JsonParser.parseString(s)
        assertTrue(s.contains("\"@depth\""))
    }

    @Test
    fun `a collection that throws part way keeps what it gave and the error`() {
        val bad = object : AbstractList<Int>() {
            override val size = 3
            override fun get(index: Int): Int = if (index == 1) throw IllegalStateException("boom") else index
        }
        val a = JsonParser.parseString(raw(listOf(bad))).asJsonArray[0].asJsonArray
        assertEquals(0, a[0].asInt)
        assertTrue(a[1].asJsonObject["@error"].asString.contains("boom"))
        assertEquals(2, a.size())
    }

    @Test
    fun `bytes, bitsets and maps`() {
        val b = json(mapOf("b" to byteArrayOf(1, 2, 3)))["b"].asJsonObject
        assertEquals(3, b["len"].asInt)
        assertContentEquals(byteArrayOf(1, 2, 3), java.util.Base64.getDecoder().decode(b["b64"].asString))
        assertEquals("{\"bits\":[5]}", raw(BitSet.valueOf(longArrayOf(5))))
        assertEquals(500, json((0 until 500).associate { "k$it" to it }).size())
    }

    @Test
    fun `cookie bytes are hashed unless allowed`() {
        val p = ClientboundStoreCookiePacket(Identifier.parse("hypixel:t"), byteArrayOf(9, 9))
        PacketJson.cookiePayloads = false
        val o = json(p)["payload"].asJsonObject
        assertEquals(2, o["len"].asInt)
        assertEquals(64, o["sha256"].asString.length)
        assertFalse(o.has("b64"))
        PacketJson.cookiePayloads = true
        try { assertTrue(json(p)["payload"].asJsonObject.has("b64")) } finally { PacketJson.cookiePayloads = false }
    }

    @Test
    fun `text keeps its styles and click events`() {
        val plain = json(ClientboundSystemChatPacket(Component.literal("hello"), false))["content"].asJsonObject
        assertEquals("hello", plain["t"].asString)
        assertFalse(plain.has("j"))
        val styled = Component.literal("click").withStyle(Style.EMPTY.withClickEvent(ClickEvent.RunCommand("/party accept x")).withBold(true))
        val o = json(ClientboundSystemChatPacket(styled, false))["content"].asJsonObject
        assertEquals("click", o["t"].asString)
        val j = o["j"].asJsonObject
        assertTrue(j["bold"].asBoolean)
        assertEquals("/party accept x", j["click_event"].asJsonObject["command"].asString)
    }

    @Test
    fun `an empty item is null`() {
        // Whole stacks need the data-driven item components, which a headless bootstrap does not load.
        assertEquals("null", RichJson.itemNow(ItemStack.EMPTY))
        assertEquals("[null]", raw(listOf(ItemStack.EMPTY)))
    }

    @Test
    fun `registry objects and particles by id`() {
        assertEquals("\"minecraft:stone\"", raw(Blocks.STONE))
        assertEquals("\"minecraft:chest\"", raw(BlockEntityType.CHEST))
        val p = json(ParticleTypes.FLAME)
        assertEquals("minecraft:flame", p["type"].asString)
    }

    @Test
    fun `decoded extras sit beside the fields`() {
        val ev = captured(ClientboundGameEventPacket(ClientboundGameEventPacket.CHANGE_GAME_MODE, 1f))
        assertEquals("CHANGE_GAME_MODE", ev["eventName"].asString)
        val le = captured(ClientboundLevelEventPacket(2001, BlockPos(1, 2, 3), net.minecraft.world.level.block.Block.getId(Blocks.STONE.defaultBlockState()), false))
        assertEquals("minecraft:stone", le["state"].asString)
    }

    @Test
    fun `a map's colour patch replaces the raw field, marked full when whole`() {
        val colors = ByteArray(128 * 128) { (it % 7).toByte() }
        val p = net.minecraft.network.protocol.game.ClientboundMapItemDataPacket(net.minecraft.world.level.saveddata.maps.MapId(3), 0, false,
            java.util.Optional.empty(), java.util.Optional.of(net.minecraft.world.level.saveddata.maps.MapItemSavedData.MapPatch(0, 0, 128, 128, colors)))
        val s = PacketJson.capture(p)()
        assertEquals(1, Regex("\"colorPatch\"").findAll(s).count())
        val c = JsonParser.parseString(s).asJsonObject["colorPatch"].asJsonObject
        assertTrue(c["full"].asBoolean)
        assertContentEquals(colors, java.util.Base64.getDecoder().decode(c["b64"].asString))
    }

    @Test
    fun `light arrays are keyed by their mask bit`() {
        val a = ByteArray(2048) { 1 }; val b = ByteArray(2048) { 2 }
        val copy = RichJson.LightCopy(longArrayOf(0b1010), longArrayOf(0b1), listOf(a, b), longArrayOf(0), longArrayOf(0), emptyList())
        val o = JsonParser.parseString(StringBuilder().also { RichJson.writeLight(it, copy, -4) }.toString()).asJsonObject
        val arrays = o["sky"].asJsonObject["arrays"].asJsonObject
        assertEquals(setOf("1", "3"), arrays.keySet())
        assertContentEquals(b, java.util.Base64.getDecoder().decode(arrays["3"].asString))
        assertEquals(-5, o["y0"].asInt)
    }

    @Test
    fun `entity ids by checked field names`() {
        assertContentEquals(intArrayOf(42), PacketDecode.entityIds(ClientboundSetEntityMotionPacket(42, Vec3.ZERO)))
        assertContentEquals(intArrayOf(7), PacketDecode.entityIds(ClientboundMoveEntityPacket.Pos(7, 1, 2, 3, true)))
        assertContentEquals(intArrayOf(1, 2, 3), PacketDecode.entityIds(ClientboundRemoveEntitiesPacket(1, 2, 3)))
        assertNull(PacketDecode.entityIds(ClientboundStoreCookiePacket(Identifier.parse("a:b"), ByteArray(0))))
        PacketDecode.selfId = 2
        try { assertEquals(",\"e\":[1,2,3],\"self\":true", PacketDecode.entityMembers(ClientboundRemoveEntitiesPacket(1, 2, 3))) } finally { PacketDecode.selfId = -1 }
    }
}
