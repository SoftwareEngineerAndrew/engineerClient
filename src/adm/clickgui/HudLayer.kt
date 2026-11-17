package com.adm.clickgui

import androidx.compose.runtime.*
import com.adm.AdmMod.mc
import com.adm.clickgui.settings.impl.HUDSetting
import com.adm.clickgui.settings.impl.HudElement
import com.adm.features.ModuleManager.hudSettingsCache
import com.adm.utils.Colors
import com.adm.utils.render.pushPixelSnap
import com.adm.utils.render.roundedOutline
import com.adm.utils.ui.compose.*
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.roundToInt

object HudLayer {

    private var editing by mutableStateOf(false)
    private var composed: UiHost? = null

    fun render(graphics: GuiGraphicsExtractor, example: Boolean, mouseX: Int, mouseY: Int) {
        val screenW = mc.window.guiScaledWidth
        val screenH = mc.window.guiScaledHeight
        if (screenW <= 0 || screenH <= 0) return

        val snapped = graphics.pushPixelSnap(0, 0, screenW, screenH)
        var needsCompose = false
        val list = hudSettingsCache
        for (i in list.indices) {
            val setting = list[i]
            val draw = setting.drawer
            if (draw == null) needsCompose = true
            else if (setting.isEnabled) paint(graphics, setting.hud, draw, example, mouseX, mouseY)
        }
        if (snapped) graphics.pose().popMatrix()

        if (needsCompose) {
            editing = example
            val host = composed ?: UiHost { ComposedHuds() }.also { composed = it }
            host.render(graphics, screenW, screenH, mouseX, mouseY)
        }
    }

    fun hovered(mouseX: Int, mouseY: Int): HUDSetting? {
        val list = hudSettingsCache
        for (i in list.indices.reversed()) {
            val setting = list[i]
            if (!setting.isEnabled) continue
            val hud = setting.hud
            val w = hud.scaledWidth
            val h = hud.scaledHeight
            if (w <= 0 || h <= 0) continue
            val x = hud.x
            val y = hud.y
            if (mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h) return setting
        }
        return null
    }

    // Scaled about the box's centre and shifted back by the rounded half growth, each box snapped to device pixels, so a HUD lands on the same pixels the editor shows.
    private fun paint(
        graphics: GuiGraphicsExtractor,
        hud: HudElement,
        draw: GuiGraphicsExtractor.(Boolean) -> Pair<Int, Int>,
        example: Boolean,
        mouseX: Int,
        mouseY: Int,
    ) {
        val boxW = hud.width.coerceAtLeast(1)
        val boxH = hud.height.coerceAtLeast(1)
        val frameW = if (example) hud.scaledWidth else 0
        val frameH = if (example) hud.scaledHeight else 0
        val left = hud.x
        val top = hud.y
        val factor = hud.scale
        val boxX = left + ((factor - 1f) * boxW / 2f).roundToInt()
        val boxY = top + ((factor - 1f) * boxH / 2f).roundToInt()

        val pose = graphics.pose()
        val outerSnap = graphics.pushPixelSnap(left, top, maxOf(boxW, frameW), maxOf(boxH, frameH))

        val boxSnap = graphics.pushPixelSnap(boxX, boxY, boxW, boxH)
        if (factor != 1f) {
            val pivotX = boxX + boxW / 2f
            val pivotY = boxY + boxH / 2f
            pose.pushMatrix()
            pose.translate(pivotX, pivotY)
            pose.scale(factor, factor)
            pose.translate(-pivotX, -pivotY)
        }
        val innerSnap = graphics.pushPixelSnap(boxX, boxY, boxW, boxH)
        pose.pushMatrix()
        pose.translate((boxX + hud.centerOffset).toFloat(), boxY.toFloat())
        val (w, h) = graphics.draw(example)
        pose.popMatrix()
        hud.onMeasured(w, h, example)
        if (innerSnap) pose.popMatrix()
        if (factor != 1f) pose.popMatrix()
        if (boxSnap) pose.popMatrix()

        if (frameW > 0 && frameH > 0) {
            val over = mouseX >= left && mouseX < left + frameW && mouseY >= top && mouseY < top + frameH
            val frameSnap = graphics.pushPixelSnap(left, top, frameW, frameH)
            graphics.roundedOutline(left - 1, top - 1, left + frameW + 1, top + frameH + 1, Colors.WHITE.rgba, if (over) 1.5f else 1f, 3f)
            if (frameSnap) pose.popMatrix()
        }

        if (outerSnap) pose.popMatrix()
    }

    @Composable
    private fun ComposedHuds() {
        for (setting in hudSettingsCache) key(setting) {
            if (setting.drawer == null && setting.isEnabled) ComposedHud(setting)
        }
    }

    @Composable
    private fun ComposedHud(setting: HUDSetting) {
        val hud = setting.hud
        CompositionLocalProvider(LocalTextShadow provides true) {
            Box {
                val inner = Box { setting.content(editing) }
                inner.scale { hud.scale }
                    .offset({ ((hud.scale - 1f) * inner.width / 2f).roundToInt() }, { ((hud.scale - 1f) * inner.height / 2f).roundToInt() })
                if (editing) {
                    val pointer = remember { InteractionSource() }
                    Canvas(size = { hud.scaledWidth to hud.scaledHeight }) {}
                        .hoverable(pointer)
                        .drawBehind { graphics ->
                            graphics.roundedOutline(x - 1, y - 1, right + 1, bottom + 1, Colors.WHITE.rgba, if (pointer.hovered) 1.5f else 1f, 3f)
                        }
                }
            }.offset({ hud.x }, { hud.y })
        }
    }
}
