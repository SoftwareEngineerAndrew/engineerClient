package com.engineerclient.skyblock

import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component

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

    private var lastKey: String? = null
    private var lastShown: Component? = null
    private var count = 1

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
}
