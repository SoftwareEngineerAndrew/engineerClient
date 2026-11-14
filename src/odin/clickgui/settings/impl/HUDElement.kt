package com.odtheking.odin.clickgui.settings.impl

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.odtheking.odin.OdinMod.mc
import net.minecraft.client.gui.GuiGraphicsExtractor

open class HudElement(
    x: Int,
    y: Int,
    var scale: Float,
    enabled: Boolean = true,
) {
    // Where it sits as a share of the screen, as Blade keeps it, so it stays put when the window or GUI scale changes.
    // Until the window is known it holds pixels, turned into a share on first use.
    private var fx = Double.NaN
    private var fy = Double.NaN
    private var px = x
    private var py = y

    private fun screen(): Pair<Int, Int>? = runCatching { mc.window.guiScaledWidth to mc.window.guiScaledHeight }.getOrNull()?.takeIf { it.first > 0 && it.second > 0 }

    var x: Int
        get() {
            val (w, _) = screen() ?: return px
            if (fx.isNaN()) fx = px.toDouble() / w
            return Math.round(fx * w).toInt()
        }
        set(value) {
            px = value
            fx = screen()?.let { value.toDouble() / it.first } ?: Double.NaN
        }

    var y: Int
        get() {
            val (_, h) = screen() ?: return py
            if (fy.isNaN()) fy = py.toDouble() / h
            return Math.round(fy * h).toInt()
        }
        set(value) {
            py = value
            fy = screen()?.let { value.toDouble() / it.second } ?: Double.NaN
        }
    var enabled: Boolean by mutableStateOf(enabled)

    var width: Int = 0
        private set
    var height: Int = 0
        private set

    val scaledWidth: Int get() = (width * scale).toInt()
    val scaledHeight: Int get() = (height * scale).toInt()

    fun onMeasured(measuredWidth: Int, measuredHeight: Int) {
        width = measuredWidth
        height = measuredHeight
    }

    fun clampToScreen() {
        x = x.coerceIn(0, (mc.window.guiScaledWidth - scaledWidth).coerceAtLeast(0))
        y = y.coerceIn(0, (mc.window.guiScaledHeight - scaledHeight).coerceAtLeast(0))
    }

    fun write(): JsonObject =
        JsonObject().apply {
            addProperty("x", x)
            addProperty("y", y)
            if (!fx.isNaN()) addProperty("fx", fx)
            if (!fy.isNaN()) addProperty("fy", fy)
            addProperty("scale", scale)
            addProperty("enabled", enabled)
        }

    fun read(element: JsonElement, toggleable: Boolean) {
        if (element !is JsonObject) return

        element.get("x")?.asInt?.let { px = it; fx = Double.NaN }
        element.get("y")?.asInt?.let { py = it; fy = Double.NaN }
        element.get("fx")?.asDouble?.let { fx = it }
        element.get("fy")?.asDouble?.let { fy = it }
        scale = element.get("scale")?.asFloat ?: scale
        enabled = !toggleable || element.get("enabled")?.asBoolean ?: enabled
    }

    companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 5f
    }
}

fun GuiGraphicsExtractor.drawAtHud(hud: HudElement, content: GuiGraphicsExtractor.() -> Unit) {
    pose().pushMatrix()
    pose().translate(hud.x.toFloat(), hud.y.toFloat())
    pose().scale(hud.scale)
    content()
    pose().popMatrix()
}