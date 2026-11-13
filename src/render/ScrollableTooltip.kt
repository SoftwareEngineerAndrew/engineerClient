package com.engineerclient.render

import com.engineerclient.mixin.HoveredSlotAccessor
import com.mojang.blaze3d.platform.InputConstants
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import org.joml.Matrix3x2fStack
import kotlin.math.sign

object ScrollableTooltip : Module(
    name = "Scrollable Tooltip",
    category = Category.RENDER,
    description = "Scroll to move tooltips, Shift-scroll sideways, Ctrl-scroll to scale.",
    key = null,
) {
    private val vertical by BooleanSetting("Vertical", true, desc = "Scroll moves the tooltip up and down.")
    private val horizontal by BooleanSetting("Horizontal", true, desc = "Shift-scroll moves the tooltip sideways.")
    private val scaling by BooleanSetting("Scale", true, desc = "Ctrl-scroll scales the tooltip.")
    private val step by NumberSetting("Scroll Step", 10, 1..40, 1, desc = "Pixels moved per scroll.")
    private val resetOnNew by BooleanSetting("Reset On New Tooltip", true, desc = "Each new tooltip starts at its normal place and size.")

    private var offsetX = 0f
    private var offsetY = 0f
    private var scale = 1f
    private var lastKey: Any? = null
    private var lastDrawn = 0L

    init {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            ScreenMouseEvents.afterMouseScroll(screen).register { _, _, _, dx, dy, consumed ->
                if (enabled && System.currentTimeMillis() - lastDrawn < 100) scroll(dx, dy)
                consumed
            }
        }
    }

    private fun scroll(dx: Double, dy: Double) {
        val ctrl = InputConstants.isKeyDown(InputConstants.KEY_LCONTROL) || InputConstants.isKeyDown(InputConstants.KEY_RCONTROL)
        val shift = InputConstants.isKeyDown(InputConstants.KEY_LSHIFT) || InputConstants.isKeyDown(InputConstants.KEY_RSHIFT)
        when {
            ctrl && scaling -> scale = (scale * (1f + 0.1f * sign(dy).toFloat())).coerceIn(0.2f, 5f)
            shift && horizontal -> offsetX += step * sign(if (dy != 0.0) dy else dx).toFloat()
            !ctrl && !shift && vertical -> {
                offsetY += step * sign(dy).toFloat()
                if (horizontal) offsetX -= step * sign(dx).toFloat()
            }
        }
    }

    fun begin(pose: Matrix3x2fStack, x: Int, y: Int): Boolean {
        if (!enabled) return false
        val now = System.currentTimeMillis()
        val key = (mc.gui.screen() as? AbstractContainerScreen<*>)?.let { (it as HoveredSlotAccessor).ecHoveredSlot() }
        if (resetOnNew && (key !== lastKey || now - lastDrawn > 250)) {
            offsetX = 0f
            offsetY = 0f
            scale = 1f
        }
        lastKey = key
        lastDrawn = now
        pose.pushMatrix()
        pose.translate(x + offsetX, y + offsetY)
        pose.scale(scale, scale)
        pose.translate(-x.toFloat(), -y.toFloat())
        return true
    }
}
