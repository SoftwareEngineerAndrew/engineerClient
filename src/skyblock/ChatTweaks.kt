package com.engineerclient.skyblock

import com.engineerclient.EngineerClient
import com.engineerclient.mixin.ChatCopyAccessor
import com.mojang.blaze3d.platform.InputConstants
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import net.minecraft.ChatFormatting
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence
import net.minecraft.util.Mth

object ChatTweaks : Module(
    name = "Chat Tweaks",
    category = Category.SKYBLOCK,
    description = "Longer chat history, stacked repeats and no blank lines.",
    key = null,
) {
    private val removeLimit by BooleanSetting("Remove Chat Limit", false, desc = "Keeps more than vanilla's 100 chat lines.")
    private val maxMessages by NumberSetting("Max Messages", 10000, 100..100000, 100, desc = "How many lines the chat keeps.").withDependency { removeLimit }
    private val compactChat by BooleanSetting("Compact Chat", false, desc = "Stacks a repeated message onto the last one with (xN).")
    private val hideEmpty by BooleanSetting("Hide Empty Messages", false, desc = "Drops chat lines with nothing but spaces.")
    private val compactHoppity by BooleanSetting("Compact Hoppity Messages", true, desc = "Joins the two lines of a Hoppity rabbit find into one.")
    private val copyChat by BooleanSetting("Copy On Right Click", true, desc = "Right click a chat message to copy it, shift for one line.")
    private val copyLineOnly by BooleanSetting("Copy Line Only", false, desc = "Always copies just the clicked line.").withDependency { copyChat }

    private var lastKey: String? = null
    private var lastShown: Component? = null
    private var count = 1

    private val hoppityName = Regex("^§D§LHOPPITY'S HUNT §7You found (.+) §.\\(.+\\)!$", RegexOption.IGNORE_CASE)
    private val hoppityInfo = Regex("^(§.§.)(DUPLICATE|NEW) RABBIT! (.+)")
    private var hoppityRabbit = ""

    init {
        on<MessageEvent.Chat> {
            if (!compactHoppity) return@on
            val text = component.string
            if (message == "HOPPITY'S HUNT You found a Hitman Egg!") {
                cancel()
                return@on
            }
            hoppityName.find(text)?.let {
                hoppityRabbit = it.groupValues[1]
                cancel()
                return@on
            }
            hoppityInfo.find(text)?.let {
                val (style, status, chocolate) = it.destructured
                val line = Component.literal("$style$status $hoppityRabbit $chocolate")
                mc.execute { mc.gui.hud.chat.addClientSystemMessage(line) }
                hoppityRabbit = ""
                cancel()
            }
        }
    }

    fun chatLimit(vanilla: Int): Int = if (enabled && removeLimit) maxMessages else vanilla

    fun hides(contents: Component): Boolean = enabled && hideEmpty && contents.string.isBlank()

    fun compactOn(): Boolean = enabled && compactChat

    fun compact(contents: Component, top: Component?): Component {
        val key = ChatFormatting.stripFormatting(contents.string) ?: ""
        if (key.isBlank()) {
            lastKey = null
            return contents
        }
        if (top != null && top === lastShown && key == lastKey) {
            count++
            val out = contents.copy().append(Component.literal(" (x$count)").withStyle(ChatFormatting.GRAY))
            lastShown = out
            return out
        }
        lastKey = key
        lastShown = contents
        count = 1
        return contents
    }

    fun copyChat(event: MouseButtonEvent) {
        if (!enabled || !copyChat || event.button() != InputConstants.MOUSE_BUTTON_RIGHT) return
        val chat = mc.gui.hud.chat as ChatCopyAccessor
        if (chat.`ec$width`() + 4 < event.x()) return
        val lineHeight = (9 * (mc.options.chatLineSpacing().get() + 1.0) * chat.`ec$scale`()).toInt()
        if (lineHeight <= 0) return
        val index = ((Mth.floor(mc.window.guiScaledHeight - 40.0) - event.y()) / lineHeight).toInt() + chat.`ec$scroll`()
        val lines = chat.`ec$lines`()
        if (index !in lines.indices) return

        val clicked = lines[index]
        val sb = StringBuilder()
        if (copyLineOnly || event.hasShiftDown()) appendPlain(sb, clicked.content())
        else for (i in lines.indices.reversed()) if (lines[i].parent() === clicked.parent()) appendPlain(sb, lines[i].content())

        mc.keyboardHandler.clipboard = sb.toString().replace(Regex("§."), "")
        EngineerClient.msg("§7Copied chat message.")
    }

    private fun appendPlain(sb: StringBuilder, text: FormattedCharSequence) {
        text.accept { _, _, codePoint -> sb.appendCodePoint(codePoint); true }
    }
}
