package com.adm.utils

import com.adm.AdmMod.mc
import com.adm.features.impl.render.ClickGUIModule
import net.minecraft.client.gui.components.ChatComponent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style

fun sendChatMessage(message: Any) {
    mc.execute { mc.player?.connection?.sendChat(message.toString()) }
}

fun sendCommand(command: String) {
    mc.execute { mc.player?.connection?.sendCommand(command) }
}

fun modMessage(message: Any?, prefix: String = "§3ADM §8»§r ", chatStyle: Style? = null) {
    val text = hexText("$prefix$message")
    chatStyle?.let { text.setStyle(chatStyle) }
    mc.schedule { mc.gui.hud.chat.addClientSystemMessage(text) }
}

fun modMessage(message: Component, prefix: String = "§3ADM §8»§r ", chatStyle: Style? = null) {
    val text = Component.literal(prefix).append(message)
    chatStyle?.let { text.setStyle(chatStyle) }
    mc.schedule { mc.gui.hud.chat.addClientSystemMessage(text) }
}

private val HEX_CODE = Regex("§x(?:§[0-9a-fA-F]){6}")

// Legacy text with §x§r§r§g§g§b§b hex colours, which the font alone can't show.
fun hexText(text: String): MutableComponent {
    if (!text.contains("§x")) return Component.literal(text)
    val out = Component.empty()
    var color = -1
    var at = 0
    for (m in HEX_CODE.findAll(text)) {
        if (m.range.first > at) out.append(Component.literal(text.substring(at, m.range.first)).also { if (color >= 0) it.withColor(color) })
        color = buildString { for (i in 3 until m.value.length step 2) append(m.value[i]) }.toInt(16)
        at = m.range.last + 1
    }
    if (at < text.length) out.append(Component.literal(text.substring(at)).also { if (color >= 0) it.withColor(color) })
    return out
}

fun devMessage(message: Any?) {
    if (!ClickGUIModule.devMessage) return println(message)
    modMessage(message, "§3ADM§bDev §8»§r ")
}

fun getCenteredText(text: String): String {
    val strippedText = text.noControlCodes
    if (strippedText.isEmpty()) return text
    val textWidth = mc.font.width(strippedText)
    val chatWidth = ChatComponent.getWidth(mc.options.chatWidth().get())

    if (textWidth >= chatWidth) return text

    val spacesNeeded = ((chatWidth - textWidth) / 2 / 4).coerceAtLeast(0)
    return " ".repeat(spacesNeeded) + text
}

fun getChatBreak(): String {
    return ChatComponent.getWidth(mc.options.chatWidth().get()).let {
        "§9§m" + "-".repeat(it / mc.font.width("-"))
    }
}