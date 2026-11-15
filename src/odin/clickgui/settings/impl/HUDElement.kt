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

    /** Module/setting name, set by its HUDSetting; how other HUDs link to this one. */
    var id = ""

    /** The HUD this one is linked to: it then sits at an offset from it and moves with it. */
    var link: String? = null
        private set
    private var lx = 0
    private var ly = 0
    private var resolving = false

    fun parent(): HudElement? {
        val id = link ?: return null
        if (resolving) return null
        return resolve(id)
    }

    private inline fun <T> guarded(block: () -> T): T {
        resolving = true
        try { return block() } finally { resolving = false }
    }

    fun linkTo(other: HudElement): Boolean {
        var p: HudElement? = other
        var depth = 0
        while (p != null && depth++ < 64) {
            if (p === this) return false
            p = p.parent()
        }
        val cx = x; val cy = y
        link = other.id
        lx = cx - other.x
        ly = cy - other.y
        return true
    }

    fun unlink() {
        val cx = x; val cy = y
        link = null
        x = cx; y = cy
    }

    private fun screen(): Pair<Int, Int>? = runCatching { mc.window.guiScaledWidth to mc.window.guiScaledHeight }.getOrNull()?.takeIf { it.first > 0 && it.second > 0 }

    var x: Int
        get() {
            parent()?.let { p -> return guarded { p.x } + lx }
            val (w, _) = screen() ?: return px
            if (fx.isNaN()) fx = px.toDouble() / w
            return Math.round(fx * w).toInt()
        }
        set(value) {
            parent()?.let { p -> lx = value - guarded { p.x }; return }
            px = value
            fx = screen()?.let { value.toDouble() / it.first } ?: Double.NaN
        }

    var y: Int
        get() {
            parent()?.let { p -> return guarded { p.y } + ly }
            val (_, h) = screen() ?: return py
            if (fy.isNaN()) fy = py.toDouble() / h
            return Math.round(fy * h).toInt()
        }
        set(value) {
            parent()?.let { p -> ly = value - guarded { p.y }; return }
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

    /** What the content drew this frame; in the middle column [width] is the widest it has been (the example's in Edit HUD), so text centres in it. */
    var contentWidth: Int = 0
        private set
    private var box = 0

    /** How far right the content goes to sit in the middle of [width]. */
    val centerOffset: Int get() = if (inMiddle()) ((width - contentWidth) / 2).coerceAtLeast(0) else 0

    fun onMeasured(measuredWidth: Int, measuredHeight: Int, example: Boolean = false) {
        contentWidth = measuredWidth
        box = if (example) measuredWidth else maxOf(box, measuredWidth)
        width = if (measuredWidth == 0) 0 else if (inMiddle()) box else measuredWidth
        height = measuredHeight
    }

    /** Whether the HUD's middle is in the screen's middle third. */
    fun inMiddle(): Boolean {
        val w = screen()?.first ?: return false
        val mid = x + scaledWidth / 2
        return mid > w / 3 && mid < w * 2 / 3
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
            link?.let { addProperty("link", it); addProperty("lx", lx); addProperty("ly", ly) }
        }

    fun read(element: JsonElement, toggleable: Boolean) {
        if (element !is JsonObject) return

        element.get("x")?.asInt?.let { px = it; fx = Double.NaN }
        element.get("y")?.asInt?.let { py = it; fy = Double.NaN }
        element.get("fx")?.asDouble?.let { fx = it }
        element.get("fy")?.asDouble?.let { fy = it }
        scale = element.get("scale")?.asFloat ?: scale
        enabled = !toggleable || element.get("enabled")?.asBoolean ?: enabled
        link = element.get("link")?.asString
        lx = element.get("lx")?.asInt ?: 0
        ly = element.get("ly")?.asInt ?: 0
    }

    companion object {
        var resolve: (String) -> HudElement? = { null }
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