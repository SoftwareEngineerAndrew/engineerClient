package com.engineerclient.recorder

import com.google.gson.JsonParser
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket
import net.minecraft.network.protocol.game.ServerboundChatPacket
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket
import net.minecraft.network.protocol.game.ServerboundEditBookPacket
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket
import java.lang.reflect.Modifier
import java.util.Optional
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What you type never leaks through a packet the redaction does not know, and private messages you send stay hidden. */
class TypedPrivacyTest {

    @AfterTest
    fun reset() { Rec.config = RecConfig(); Rec.chatChannel = "all" }

    /** Serverbound packets with text fields that are not typed by the player (reviewed). */
    private val NOT_TYPED = setOf(
        "net.minecraft.network.protocol.login.ServerboundHelloPacket", // the account name, already in the meta
    )

    private fun textField(f: java.lang.reflect.Field): Boolean {
        if (Modifier.isStatic(f.modifiers)) return false
        if (f.type == String::class.java || f.type == Array<String>::class.java) return true
        val g = f.genericType.typeName
        return (f.type == List::class.java || f.type == Optional::class.java) && g.contains("java.lang.String")
    }

    @Test
    fun `every serverbound packet that holds text is redacted or reviewed`() {
        val jar = ServerboundChatPacket::class.java.protectionDomain.codeSource.location.toURI().let { java.io.File(it) }
        val names = ZipFile(jar).use { z ->
            z.entries().asSequence().map { it.name }
                .filter { it.startsWith("net/minecraft/network/protocol/") && it.substringAfterLast('/').matches(Regex("Serverbound[A-Za-z]*Packet\\.class")) }
                .map { it.removeSuffix(".class").replace('/', '.') }.toList()
        }
        assertTrue(names.size > 30, "found the packets: ${names.size}")
        val missing = names.filter { n ->
            val c = Class.forName(n, false, javaClass.classLoader)
            c.declaredFields.any(::textField) && n !in NOT_TYPED && c !in WireTap.TYPED_TEXT_PACKETS
        }
        assertTrue(missing.isEmpty(), "serverbound packets with typed text not redacted: $missing")
    }

    private fun body(p: Packet<*>) = JsonParser.parseString(WireTap.typedRedaction(p)!!).asJsonObject

    @Test
    fun `typed text is left out of signs, anvils, books and suggestions`() {
        val sign = body(ServerboundSignUpdatePacket(BlockPos(1, 2, 3), true, "secret", "", "ab", "c"))
        assertEquals("[6,0,2,1]", sign["lens"].toString())
        assertFalse(sign.toString().contains("secret"))
        assertEquals(6, body(ServerboundRenameItemPacket("secret"))["len"].asInt)
        val book = body(ServerboundEditBookPacket(3, listOf("p1 secret", "p2"), Optional.of("t")))
        assertEquals(2, book["pages"].asInt)
        assertFalse(book.toString().contains("secret"))
        val sug = body(ServerboundCommandSuggestionPacket(7, "/msg Bob hel"))
        assertEquals("msg", sug["command"].asString)
        assertEquals(7, sug["id"].asInt)
        assertFalse(sug.toString().contains("Bob"))
        assertEquals("typed_chat", WireTap.typedReason(ServerboundRenameItemPacket("x")))
        // Typed Chat on: written in full.
        Rec.config = RecConfig(typedChat = true)
        assertNull(WireTap.typedRedaction(ServerboundRenameItemPacket("x")))
        assertNull(WireTap.typedReason(ServerboundCommandSuggestionPacket(1, "/warp dh")))
    }

    @Test
    fun `private messages you send stay hidden with Typed Chat on`() {
        Rec.config = RecConfig(typedChat = true, hidePrivate = true)
        assertEquals("private", WireTap.typedReason(ServerboundChatCommandPacket("msg Bob secret")))
        assertEquals("private", WireTap.typedReason(ServerboundChatCommandPacket("g chat hi")))
        assertEquals("private", WireTap.typedReason(ServerboundCommandSuggestionPacket(1, "/r hel")))
        assertNull(WireTap.typedReason(ServerboundChatCommandPacket("warp dungeon_hub")))
        assertTrue(Rec.typedAllowed("hello", command = false))
        Rec.noteCommand("chat g")
        assertFalse(Rec.typedAllowed("hello", command = false))
        Rec.noteCommand("/chat a")
        assertTrue(Rec.typedAllowed("hello", command = false))
        assertFalse(Rec.typedAllowed("/oc hi", command = true))
        assertTrue(Rec.typedAllowed("/pc hi", command = true))
        // Hide Private Chats off: Typed Chat writes everything.
        Rec.config = RecConfig(typedChat = true, hidePrivate = false)
        assertNull(WireTap.typedReason(ServerboundChatCommandPacket("msg Bob secret")))
    }

    @Test
    fun `vanilla whispers count as private`() {
        assertTrue(Rec.privateText("Bob whispers to you: hi"))
        assertTrue(Rec.privateText("You whisper to Bob: hi"))
        assertFalse(Rec.privateText("Bob: whispers to you"))
    }
}
