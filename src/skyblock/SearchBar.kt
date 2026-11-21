package com.engineerclient.skyblock

import com.mojang.blaze3d.platform.InputConstants
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.features.Category
import com.adm.features.Module
import com.adm.utils.Color
import com.adm.utils.loreString
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import java.util.IdentityHashMap
import java.util.Locale
import java.util.Stack

object SearchBar : Module(
    name = "Search Bar",
    category = Category.SKYBLOCK,
    description = "Ctrl+F in any inventory opens a search bar that dims items that don't match (>1m or <500k filter by worth), and works as a calculator.",
    key = null,
    toggled = true,
) {
    private val formatMath by BooleanSetting("Format Math Results", true, desc = "Shows results as 1.5M instead of 1500000.")
    private val searchLore by BooleanSetting("Search Lore", true, desc = "Also matches text in the item's lore.")
    private val missColor by ColorSetting("Miss Color", Color(0x11, 0x11, 0x11, 0xaa / 255f), true, desc = "Drawn over items that don't match.")

    private var shown = false
    private var value = ""
    private var result = Double.NaN
    private var terms = emptyList<List<Part>>()
    private var priceTerms = false
    private var box: EditBox? = null

    init {
        ScreenEvents.AFTER_INIT.register { _, screen, width, _ ->
            if (!enabled || screen !is AbstractContainerScreen<*>) return@register
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
            ScreenEvents.remove(screen).register { if (box === edit) box = null; matches.clear() }
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
        result = MathParser.calculation(lower)
        resultLine = null
        terms = lower.split(",").map { term -> term.split("&").map { it.trim() }.filter { it.isNotEmpty() }.map(::part) }.filter { it.isNotEmpty() }
        priceTerms = terms.any { term -> term.any { it.text == null } }
        matches.clear()
    }

    private class Part(val text: String?, val above: Double, val below: Double)

    // ">1m" and "<500k" filter on the item's estimated worth.
    private fun part(text: String): Part {
        if (text.length > 1 && (text[0] == '>' || text[0] == '<')) {
            val bound = MathParser.parse(text.substring(1))
            if (!bound.isNaN()) return if (text[0] == '>') Part(null, bound, Double.POSITIVE_INFINITY) else Part(null, Double.NEGATIVE_INFINITY, bound)
        }
        return Part(text, 0.0, 0.0)
    }

    private fun render(edit: EditBox, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        edit.extractRenderState(graphics, mouseX, mouseY, delta)
        if (result.isNaN()) return
        val formatted = formatMath
        if (resultLine == null || formatted != resultFormatted) {
            resultFormatted = formatted
            resultLine = "  §e= §2" + (if (formatted) format(result) else result.toLong().toString())
            resultX = mc.font.width(value) + 4
        }
        graphics.text(mc.font, resultLine!!, edit.x + resultX, edit.y + (edit.height - 8) / 2, -1, true)
    }

    private var resultLine: String? = null
    private var resultFormatted = false
    private var resultX = 0

    private val matches = IdentityHashMap<ItemStack, Boolean>()
    private var matchedLore = true
    private var matchedPrices = 0L

    fun shadeSlot(graphics: GuiGraphicsExtractor, slot: Slot) {
        if (!active() || box == null || terms.isEmpty() || !result.isNaN()) return
        if (matchedLore != searchLore) {
            matchedLore = searchLore
            matches.clear()
        }
        if (priceTerms && matchedPrices != ItemWorth.fetchedAt) {
            matchedPrices = ItemWorth.fetchedAt
            matches.clear()
        }
        val stack = slot.item
        val hit = matches[stack] ?: matches(stack).also {
            if (matches.size >= 1024) matches.clear()
            matches[stack] = it
        }
        if (!hit) graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, missColor.rgba)
    }

    private fun matches(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        val name = stack.hoverName.string.lowercase()
        val lore = if (searchLore) stack.loreString.map { it.lowercase() } else emptyList()
        val worth = if (priceTerms) ItemWorth.worth(stack)?.first ?: Double.NaN else Double.NaN
        return terms.any { term ->
            term.all { part ->
                val text = part.text
                if (text == null) worth > part.above && worth < part.below
                else text in name || lore.any { text in it }
            }
        }
    }

    private fun format(num: Double): String = when {
        num >= 1e9 -> "%.1fB".format(Locale.ROOT, num / 1e9)
        num >= 1e6 -> "%.1fM".format(Locale.ROOT, num / 1e6)
        num >= 1e3 -> "%.1fK".format(Locale.ROOT, num / 1e3)
        else -> num.toFloat().toString()
    }

    private object MathParser {
        private val number = Regex("^-?\\d+(\\.\\d+)?[bmk]?", RegexOption.IGNORE_CASE)
        private val operator = Regex("^[()+\\-*/^x]")

        /** [text] worked out when it is a sum, not a bare number (a search for "5" still searches). */
        fun calculation(text: String): Double = if ((tokens(text)?.size ?: 0) > 1) parse(text) else Double.NaN

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
