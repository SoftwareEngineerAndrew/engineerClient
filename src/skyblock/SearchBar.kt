package com.engineerclient.skyblock

import com.mojang.blaze3d.platform.InputConstants
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.loreString
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.Slot
import java.util.Stack

object SearchBar : Module(
    name = "Search Bar",
    category = Category.SKYBLOCK,
    description = "Ctrl+F in any inventory opens a search bar that dims items that don't match, and works as a calculator.",
    key = null,
    toggled = true,
) {
    private val formatMath by BooleanSetting("Format Math Results", true, desc = "Shows results as 1.5M instead of 1500000.")
    private val missColor by ColorSetting("Miss Color", Color(0x11, 0x11, 0x11, 0xaa / 255f), true, desc = "Drawn over items that don't match.")

    private var shown = false
    private var value = ""
    private var result = Double.NaN
    private var terms = emptyList<String>()
    private var box: EditBox? = null

    init {
        ScreenEvents.AFTER_INIT.register { _, screen, width, _ ->
            if (screen !is AbstractContainerScreen<*>) return@register
            val edit = EditBox(mc.font, (width - 150) / 2, 20, 150, 20, Component.empty())
            edit.setMaxLength(100)
            edit.value = value
            edit.setResponder(::update)
            edit.isFocused = shown
            box = edit

            ScreenKeyboardEvents.allowKeyPress(screen).register { _, key -> !keyPressed(edit, key) }
            ScreenKeyboardEvents.allowCharType(screen).register { _, char ->
                if (!active() || !edit.isFocused) return@register true
                edit.charTyped(char)
                false
            }
            ScreenMouseEvents.beforeMouseClick(screen).register { _, click ->
                if (active()) edit.isFocused = click.x() in edit.x.toDouble()..(edit.x + edit.width).toDouble() && click.y() in edit.y.toDouble()..(edit.y + edit.height).toDouble()
            }
            ScreenEvents.afterExtract(screen).register { _, graphics, mouseX, mouseY, delta ->
                if (active()) render(edit, graphics, mouseX, mouseY, delta)
            }
            ScreenEvents.remove(screen).register { if (box === edit) box = null }
        }
    }

    private fun active() = enabled && shown

    private fun keyPressed(edit: EditBox, key: KeyEvent): Boolean {
        if (!enabled) return false
        if (key.hasControlDown() && key.key() == InputConstants.KEY_F) {
            shown = !shown
            edit.isFocused = shown
            return true
        }
        if (!shown || !edit.isFocused) return false
        if (key.key() == InputConstants.KEY_RETURN || key.key() == InputConstants.KEY_NUMPADENTER) {
            if (!result.isNaN()) edit.value = format(result)
            return true
        }
        if (key.key() == InputConstants.KEY_ESCAPE) return false
        edit.keyPressed(key)
        return true
    }

    private fun update(text: String) {
        value = text
        val lower = text.lowercase()
        result = MathParser.parse(lower)
        terms = lower.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun render(edit: EditBox, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        edit.extractRenderState(graphics, mouseX, mouseY, delta)
        if (result.isNaN()) return
        val shownResult = if (formatMath) format(result) else result.toLong().toString()
        graphics.text(mc.font, "  §e= §2$shownResult", edit.x + mc.font.width(value) + 4, edit.y + (edit.height - 8) / 2, -1, true)
    }

    fun shadeSlot(graphics: GuiGraphicsExtractor, slot: Slot) {
        if (!active() || box == null || terms.isEmpty() || !result.isNaN()) return
        val stack = slot.item
        val name = stack.hoverName.string.lowercase()
        val hit = !stack.isEmpty && terms.any { term -> term in name || stack.loreString.any { term in it.lowercase() } }
        if (!hit) graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, missColor.rgba)
    }

    private fun format(num: Double): String = when {
        num >= 1e9 -> "%.1fB".format(num / 1e9)
        num >= 1e6 -> "%.1fM".format(num / 1e6)
        num >= 1e3 -> "%.1fK".format(num / 1e3)
        else -> num.toFloat().toString()
    }

    private object MathParser {
        private val number = Regex("^-?\\d+(\\.\\d+)?[bmk]?", RegexOption.IGNORE_CASE)
        private val operator = Regex("^[()+\\-*/^x]")

        fun parse(text: String): Double {
            val tokens = tokens(text) ?: return Double.NaN
            if (tokens.isEmpty()) return Double.NaN
            if (tokens.size == 1) return toNumber(tokens[0]) ?: Double.NaN
            val rpn = toRpn(tokens) ?: return Double.NaN
            val stack = Stack<Double>()
            for (token in rpn) {
                toNumber(token)?.let { stack.push(it); continue }
                if (stack.size < 2) {
                    if (stack.size == 1 && token == "-") {
                        stack.push(-stack.pop())
                        continue
                    }
                    return Double.NaN
                }
                val right = stack.pop()
                val left = stack.pop()
                stack.push(when (token) {
                    "+" -> left + right
                    "-" -> left - right
                    "*" -> left * right
                    "/" -> left / right
                    "^" -> Math.pow(left, right)
                    else -> return Double.NaN
                })
            }
            return if (stack.isEmpty()) Double.NaN else stack.pop()
        }

        private fun toNumber(token: String): Double? {
            if (!number.matches(token)) return null
            val unit = token.last().lowercaseChar()
            val base = if (unit in "bmk") token.dropLast(1) else token
            val value = base.toDoubleOrNull() ?: return null
            return value * when (unit) {
                'b' -> 1e9
                'm' -> 1e6
                'k' -> 1e3
                else -> 1.0
            }
        }

        private fun precedence(op: String) = when (op) {
            "^" -> 4
            "*", "/" -> 3
            "+", "-" -> 2
            else -> -1
        }

        private fun toRpn(tokens: List<String>): List<String>? {
            val out = mutableListOf<String>()
            val stack = Stack<String>()
            for (token in tokens) {
                when {
                    toNumber(token) != null -> out.add(token)
                    precedence(token) != -1 -> {
                        while (stack.isNotEmpty() && stack.peek() != "(" &&
                            (precedence(stack.peek()) > precedence(token) || precedence(stack.peek()) == precedence(token) && token != "^")
                        ) out.add(stack.pop())
                        stack.push(token)
                    }
                    token == "(" -> stack.push(token)
                    token == ")" -> {
                        while (stack.isNotEmpty() && stack.peek() != "(") out.add(stack.pop())
                        if (stack.isEmpty()) return null
                        stack.pop()
                    }
                    else -> return null
                }
            }
            while (stack.isNotEmpty()) {
                val token = stack.pop()
                if (token == "(") return null
                out.add(token)
            }
            return out
        }

        private fun tokens(text: String): List<String>? {
            val out = mutableListOf<String>()
            var rest = text.replace(" ", "")
            var prevNumber = false
            while (rest.isNotEmpty()) {
                if (!prevNumber) {
                    number.find(rest)?.let {
                        out.add(it.value)
                        rest = rest.substring(it.value.length)
                        prevNumber = true
                        continue
                    }
                }
                val op = operator.find(rest) ?: return null
                out.add(if (op.value == "x") "*" else op.value)
                rest = rest.substring(1)
                prevNumber = op.value == ")"
            }
            return out
        }
    }
}
