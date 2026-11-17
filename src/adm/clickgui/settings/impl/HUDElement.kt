package com.adm.clickgui.settings.impl

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.adm.clickgui.settings.SettingState
import com.adm.AdmMod.mc
import net.minecraft.client.gui.GuiGraphicsExtractor

open class HudElement(
    x: Int,
    y: Int,
    var scale: Float,
    enabled: Boolean = true,
) {
    // Kept as a share of the screen so the HUD stays put across window and GUI scale changes; pixels until the window has a size.
    private var shareX = Double.NaN
    private var shareY = Double.NaN
    private var pixelX = x
    private var pixelY = y

    var id = ""

    var link: String? = null
        private set
    private var linkDx = 0
    private var linkDy = 0
    private var anchor: HudElement? = null
    private var anchorId: String? = null
    private var walking = false

    fun parent(): HudElement? {
        val target = link ?: return null
        if (walking) return null
        if (anchorId != target) {
            val found = resolve(target) ?: return null
            anchor = found
            anchorId = target
        }
        return anchor
    }

    private inline fun <T> walk(block: () -> T): T {
        walking = true
        try {
            return block()
        } finally {
            walking = false
        }
    }

    fun linkTo(other: HudElement): Boolean {
        var step: HudElement? = other
        var depth = 0
        while (step != null && depth++ < 64) {
            if (step === this) return false
            step = step.parent()
        }
        val ownX = x
        val ownY = y
        link = other.id
        linkDx = ownX - other.x
        linkDy = ownY - other.y
        return true
    }

    fun unlink() {
        val ownX = x
        val ownY = y
        link = null
        x = ownX
        y = ownY
    }

    var x: Int
        get() {
            val p = parent()
            if (p != null) return walk { p.x } + linkDx
            val w = liveWidth()
            if (w == 0) return pixelX
            if (shareX.isNaN()) shareX = pixelX.toDouble() / w
            return Math.round(shareX * w).toInt()
        }
        set(value) {
            val p = parent()
            if (p != null) {
                linkDx = value - walk { p.x }
                return
            }
            pixelX = value
            val w = liveWidth()
            shareX = if (w == 0) Double.NaN else value.toDouble() / w
        }

    var y: Int
        get() {
            val p = parent()
            if (p != null) return walk { p.y } + linkDy
            val h = liveHeight()
            if (h == 0) return pixelY
            if (shareY.isNaN()) shareY = pixelY.toDouble() / h
            return Math.round(shareY * h).toInt()
        }
        set(value) {
            val p = parent()
            if (p != null) {
                linkDy = value - walk { p.y }
                return
            }
            pixelY = value
            val h = liveHeight()
            shareY = if (h == 0) Double.NaN else value.toDouble() / h
        }

    var enabled: Boolean by SettingState(enabled)

    var width: Int = 0
        private set
    var height: Int = 0
        private set

    val scaledWidth: Int get() = (width * scale).toInt()
    val scaledHeight: Int get() = (height * scale).toInt()

    var contentWidth: Int = 0
        private set
    private var widest = 0

    // A HUD in the middle third keeps the width of its widest frame (the editor's example sets it) and centres what it draws in it.
    val centerOffset: Int get() = if (inMiddle()) ((width - contentWidth) / 2).coerceAtLeast(0) else 0

    fun onMeasured(measuredWidth: Int, measuredHeight: Int, example: Boolean = false) {
        contentWidth = measuredWidth
        widest = if (example) measuredWidth else maxOf(widest, measuredWidth)
        width = when {
            measuredWidth == 0 -> 0
            inMiddle() -> widest
            else -> measuredWidth
        }
        height = measuredHeight
    }

    fun inMiddle(): Boolean {
        val w = liveWidth()
        if (w == 0) return false
        val middle = x + scaledWidth / 2
        return middle > w / 3 && middle < w * 2 / 3
    }

    fun clampToScreen() {
        x = x.coerceIn(0, (mc.window.guiScaledWidth - scaledWidth).coerceAtLeast(0))
        y = y.coerceIn(0, (mc.window.guiScaledHeight - scaledHeight).coerceAtLeast(0))
    }

    private fun liveWidth(): Int {
        val window = mc.window ?: return 0
        val w = window.guiScaledWidth
        return if (w > 0 && window.guiScaledHeight > 0) w else 0
    }

    private fun liveHeight(): Int {
        val window = mc.window ?: return 0
        val h = window.guiScaledHeight
        return if (h > 0 && window.guiScaledWidth > 0) h else 0
    }

    fun write(): JsonObject {
        val json = JsonObject()
        json.addProperty("x", x)
        json.addProperty("y", y)
        if (!shareX.isNaN()) json.addProperty("fx", shareX)
        if (!shareY.isNaN()) json.addProperty("fy", shareY)
        json.addProperty("scale", scale)
        json.addProperty("enabled", enabled)
        val target = link
        if (target != null) {
            json.addProperty("link", target)
            json.addProperty("lx", linkDx)
            json.addProperty("ly", linkDy)
        }
        return json
    }

    fun read(element: JsonElement, toggleable: Boolean) {
        if (element !is JsonObject) return
        element.get("x")?.asInt?.let { pixelX = it; shareX = Double.NaN }
        element.get("y")?.asInt?.let { pixelY = it; shareY = Double.NaN }
        element.get("fx")?.asDouble?.let { shareX = it }
        element.get("fy")?.asDouble?.let { shareY = it }
        scale = element.get("scale")?.asFloat ?: scale
        enabled = !toggleable || element.get("enabled")?.asBoolean ?: enabled
        link = element.get("link")?.asString
        linkDx = element.get("lx")?.asInt ?: 0
        linkDy = element.get("ly")?.asInt ?: 0
    }

    companion object {
        var resolve: (String) -> HudElement? = { null }
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 5f
    }
}

fun GuiGraphicsExtractor.drawAtHud(hud: HudElement, content: GuiGraphicsExtractor.() -> Unit) {
    val pose = pose()
    pose.pushMatrix()
    pose.translate(hud.x.toFloat(), hud.y.toFloat())
    pose.scale(hud.scale)
    content()
    pose.popMatrix()
}
