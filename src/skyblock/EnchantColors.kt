package com.engineerclient.skyblock

import com.adm.AdmMod.mc
import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.clickgui.settings.impl.NumberSetting
import com.adm.clickgui.settings.impl.SelectorSetting
import com.adm.features.Category
import com.adm.features.Module
import com.adm.utils.Color
import com.adm.utils.itemId
import com.adm.utils.romanToInt
import com.adm.utils.skyblock.LocationUtils
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor

object EnchantColors : Module(
    name = "Enchant Colors",
    category = Category.SKYBLOCK,
    description = "Colours enchantments in item tooltips by level.",
    key = null,
) {
    enum class Scheme(private val label: String) { CLASSIC("Noam"), CHROMA("Devonian"), CUSTOM("Custom"); override fun toString() = label }

    private val scheme by SelectorSetting("Scheme", Scheme.CLASSIC, desc = "Which colour scheme to use.")
    private val boldMax by BooleanSetting("Bold Max Level", true, desc = "Max level enchants in bold.")
    private val chromaMax by BooleanSetting("Chroma Max Level", false, desc = "Max level enchants cycle through colours.")
    private val chromaSpeed by NumberSetting("Chroma Speed", 1.0, 0.1..3.0, 0.1, desc = "How fast the colours cycle.").withDependency { chromaMax || scheme == Scheme.CHROMA }
    private val numbers by BooleanSetting("Levels as Numbers", false, desc = "Shows 5 instead of V.")
    private val maxColor by ColorSetting("Max Color", Color(255, 170, 0), false, desc = "Max level.").withDependency { scheme == Scheme.CUSTOM }
    private val highColor by ColorSetting("High Color", Color(255, 170, 0), false, desc = "Above the usual level.").withDependency { scheme == Scheme.CUSTOM }
    private val goodColor by ColorSetting("Good Color", Color(0, 170, 170), false, desc = "The usual level.").withDependency { scheme == Scheme.CUSTOM }
    private val lowColor by ColorSetting("Low Color", Color(170, 170, 170), false, desc = "Below the usual level.").withDependency { scheme == Scheme.CUSTOM }
    private val ultimateColor by ColorSetting("Ultimate Color", Color(255, 85, 255), false, desc = "Ultimate enchants.").withDependency { scheme == Scheme.CUSTOM }

    private val pattern = Regex("([A-Za-z][A-Za-z '\\-]+?) ([IVXLCDM]+|\\d+)(?=,\\s*|\\)|$| [\\d,]+$)")

    private class Piece(val before: String, val name: String, val roman: String, val level: Int, val ench: Ench) {
        val maxed = level >= ench.max
    }

    // One tooltip line split at its enchants; the plain build is kept until a setting changes.
    private class Line(val pieces: List<Piece>, val tail: String) {
        val anyMaxed = pieces.any { it.maxed && !it.ench.ultimate }
        var built: Component? = null
        var builtAt = -1
    }

    private val noEnchants = Line(emptyList(), "")
    private val lines = HashMap<String, Line>()

    private var looks = 0
    private var lookScheme = Scheme.CLASSIC
    private var lookBold = false
    private var lookChroma = false
    private var lookNumbers = false
    private val lookColors = IntArray(5)

    init {
        ItemTooltipCallback.EVENT.register { stack, _, _, tooltip ->
            if (!enabled || !LocationUtils.isInSkyblock || stack.itemId.isEmpty()) return@register
            checkLooks()
            for (i in 1 until tooltip.size) {
                val text = tooltip[i].string
                if (text.isEmpty() || '\u25c6' in text) continue
                val line = lines[text] ?: split(text).also {
                    if (lines.size >= 512) lines.clear()
                    lines[text] = it
                }
                if (line !== noEnchants) tooltip[i] = render(line, i)
            }
        }
    }

    private fun checkLooks() {
        val c = lookColors
        val custom = scheme == Scheme.CUSTOM
        val max = if (custom) maxColor.rgba else 0
        val high = if (custom) highColor.rgba else 0
        val good = if (custom) goodColor.rgba else 0
        val low = if (custom) lowColor.rgba else 0
        val ultimate = if (custom) ultimateColor.rgba else 0
        if (looks != 0 && scheme == lookScheme && boldMax == lookBold && chromaMax == lookChroma && numbers == lookNumbers &&
            c[0] == max && c[1] == high && c[2] == good && c[3] == low && c[4] == ultimate) return
        looks++
        lookScheme = scheme
        lookBold = boldMax
        lookChroma = chromaMax
        lookNumbers = numbers
        c[0] = max; c[1] = high; c[2] = good; c[3] = low; c[4] = ultimate
    }

    private fun split(text: String): Line {
        var pieces: ArrayList<Piece>? = null
        var last = 0
        for (m in pattern.findAll(text)) {
            val name = m.groupValues[1]
            val ench = enchantInfo(name.lowercase().replace("'", "")) ?: continue
            val roman = m.groupValues[2]
            val list = pieces ?: ArrayList<Piece>(4).also { pieces = it }
            list.add(Piece(text.substring(last, m.range.first), name, roman, romanToInt(roman), ench))
            last = m.range.last + 1
        }
        return pieces?.let { Line(it, text.substring(last)) } ?: noEnchants
    }

    private fun render(line: Line, row: Int): Component {
        val chroma = line.anyMaxed && (chromaMax || scheme == Scheme.CHROMA)
        if (!chroma && line.builtAt == looks) return line.built!!
        val out = Component.empty().withStyle(ChatFormatting.GRAY)
        var x = 0f
        for (p in line.pieces) {
            out.append(p.before)
            val shown = p.name + " " + if (numbers) p.level.toString() else p.roman
            if (chroma) x += mc.font.width(p.before)
            out.append(piece(p, shown, x, row))
            if (chroma) x += mc.font.width(shown)
        }
        out.append(line.tail)
        if (!chroma) {
            line.built = out
            line.builtAt = looks
        }
        return out
    }

    private fun piece(p: Piece, shown: String, x: Float, row: Int): MutableComponent {
        val ench = p.ench
        val custom = scheme == Scheme.CUSTOM
        if (ench.ultimate) return Component.literal(shown).withStyle(Style.EMPTY.withColor(rgb(if (custom) ultimateColor.rgba else 0xFF55FF)).withBold(true))
        val bold = boldMax && p.maxed
        if (p.maxed && (chromaMax || scheme == Scheme.CHROMA)) return cycling(shown, bold, x, row)
        val level = p.level
        val color = when {
            custom && p.maxed -> maxColor.rgba
            level > ench.good -> if (custom) highColor.rgba else 0xFFAA00
            level == ench.good -> if (custom) goodColor.rgba else if (scheme == Scheme.CHROMA) 0x5555FF else 0x00AAAA
            else -> if (custom) lowColor.rgba else 0xAAAAAA
        }
        return Component.literal(shown).withStyle(Style.EMPTY.withColor(rgb(color)).withBold(bold))
    }

    private fun cycling(shown: String, bold: Boolean, startX: Float, row: Int): MutableComponent {
        val out = Component.empty()
        val phase = System.currentTimeMillis() * chromaSpeed - row * 100
        var x = startX
        for (i in shown.indices) {
            val glyph = shown.substring(i, i + 1)
            val hue = (((phase - x * 15) / 3000.0) % 1.0).toFloat()
            out.append(Component.literal(glyph).withStyle(Style.EMPTY.withColor(rgb(java.awt.Color.HSBtoRGB(hue, 1f, 1f))).withBold(bold)))
            x += mc.font.width(glyph)
        }
        return out
    }

    private fun rgb(color: Int) = TextColor.fromRgb(color and 0xFFFFFF)
}
