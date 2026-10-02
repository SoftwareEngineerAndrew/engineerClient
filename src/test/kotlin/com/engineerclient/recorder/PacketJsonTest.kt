package com.engineerclient.recorder

import com.google.gson.JsonParser
import it.unimi.dsi.fastutil.ints.IntArrayList
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.world.phys.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals

/** Packets written field by field, as valid JSON with their real field names. */
class PacketJsonTest {

    private fun json(v: Any) = JsonParser.parseString(PacketJson.write(v)).asJsonObject

    @Test
    fun `a record packet keeps its field names and values`() {
        val o = json(ClientboundSetEntityMotionPacket(42, Vec3(0.5, -1.25, 0.0)))
        assertEquals(42, o["id"].asInt)
        assertEquals(-1.25, o["movement"].asJsonArray[1].asDouble)
    }

    @Test
    fun `text is plain text and lists are lists`() {
        assertEquals("hello", json(ClientboundSystemChatPacket(Component.literal("hello"), false))["content"].asString)
        assertEquals(listOf(1, 2, 3), json(ClientboundRemoveEntitiesPacket(IntArrayList(intArrayOf(1, 2, 3))))["entityIds"].asJsonArray.map { it.asInt })
    }
}
