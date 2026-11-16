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

    init {
        ItemTooltipCallback.EVENT.register { stack, _, _, lines ->
            if (!enabled || !LocationUtils.isInSkyblock || stack.itemId.isEmpty()) return@register
            for (i in 1 until lines.size) {
                val text = lines[i].string
                if (text.isEmpty() || "\u25c6" in text) continue
                rebuild(text, i)?.let { lines[i] = it }
            }
        }
    }

    private fun rebuild(text: String, lineIndex: Int): Component? {
        val line = Component.empty().withStyle(ChatFormatting.GRAY)
        var found = false
        var last = 0
        var x = 0f
        for (m in pattern.findAll(text)) {
            val name = m.groupValues[1]
            val ench = ENCHANTS[name.lowercase().replace("'", "")] ?: continue
            found = true
            val roman = m.groupValues[2]
            val level = romanToInt(roman)
            val before = text.substring(last, m.range.first)
            line.append(before)
            x += mc.font.width(before)
            val shown = "$name ${if (numbers) level.toString() else roman}"
            line.append(styled(shown, ench, level, x, lineIndex))
            x += mc.font.width(shown)
            last = m.range.last + 1
        }
        if (!found) return null
        line.append(text.substring(last))
        return line
    }

    private fun styled(text: String, ench: Ench, level: Int, x: Float, lineIndex: Int): MutableComponent {
        val maxed = level >= ench.max
        if (ench.ultimate) return Component.literal(text).withStyle(Style.EMPTY.withColor(rgb(if (scheme == Scheme.CUSTOM) ultimateColor.rgba else 0xFF55FF)).withBold(true))
        val bold = boldMax && maxed
        if (maxed && (chromaMax || scheme == Scheme.CHROMA)) return chroma(text, bold, x, lineIndex)
        val color = when (scheme) {
            Scheme.CLASSIC -> when {
                level > ench.good -> 0xFFAA00
                level == ench.good -> 0x00AAAA
                else -> 0xAAAAAA
            }
            Scheme.CHROMA -> when {
                level > ench.good -> 0xFFAA00
                level == ench.good -> 0x5555FF
                else -> 0xAAAAAA
            }
            Scheme.CUSTOM -> when {
                maxed -> maxColor.rgba
                level > ench.good -> highColor.rgba
                level == ench.good -> goodColor.rgba
                else -> lowColor.rgba
            }
        }
        return Component.literal(text).withStyle(Style.EMPTY.withColor(rgb(color)).withBold(bold))
    }

    private fun chroma(text: String, bold: Boolean, startX: Float, lineIndex: Int): MutableComponent {
        val out = Component.empty()
        val now = System.currentTimeMillis() * chromaSpeed
        var x = startX
        for (c in text) {
            val s = c.toString()
            val hue = (((now - x * 15 - lineIndex * 100) / 3000.0) % 1.0).toFloat()
            out.append(Component.literal(s).withStyle(Style.EMPTY.withColor(rgb(java.awt.Color.HSBtoRGB(hue, 1f, 1f))).withBold(bold)))
            x += mc.font.width(s)
        }
        return out
    }

    private fun rgb(color: Int) = TextColor.fromRgb(color and 0xFFFFFF)
}
