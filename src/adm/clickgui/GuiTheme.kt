package com.adm.clickgui

import com.adm.features.impl.render.ClickGUIModule
import com.adm.utils.Color
import com.adm.utils.Colors
import net.minecraft.util.ARGB
import java.awt.Color.HSBtoRGB

object GuiTheme {

    const val ROW_WIDTH = 160
    const val ROW_HEIGHT = 21
    const val SETTING_HEIGHT = 17
    const val RADIUS = 5f
    const val CAP = 5
    const val PADDING = 6
    const val INNER_WIDTH = ROW_WIDTH - PADDING * 2

    const val PANEL_BLUR = 12f

    val background: Color get() = Colors.gray26
    val surface: Color get() = Colors.gray38
    val accent: Color get() = ClickGUIModule.clickGUIColor

    val shadow: Color = Color(0, 0, 0, 0.4f)
}

fun blend(from: Int, to: Int, progress: Float): Int = ARGB.srgbLerp(progress, from, to)

fun Color.hoverTint(hover: Float, factor: Float = 1.3f): Int =
    if (hover <= 0f) rgba else blend(rgba, lighter(factor), hover)

// The packed colour Color.brighter / darker / hsbMax would give, without making a Color each frame.
internal fun Color.lighter(factor: Float): Int =
    packHsb(hue, saturation, (brightness * factor.coerceAtLeast(1f)).coerceAtMost(1f), alphaFloat)

internal fun Color.dimmer(factor: Float): Int = packHsb(hue, saturation, brightness * factor, alphaFloat)

internal fun Color.fullHue(): Int = packHsb(hue, 1f, 1f, 1f)

private fun packHsb(hue: Float, saturation: Float, brightness: Float, alpha: Float): Int =
    (HSBtoRGB(hue, saturation, brightness) and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)
