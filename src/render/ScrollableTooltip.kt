package com.engineerclient.render

import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.NumberSetting
import com.adm.features.Category
import com.adm.features.Module
import com.engineerclient.mixin.HoveredSlotAccessor
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.Slot
import org.joml.Matrix3x2fStack

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

    private const val SCROLL_WINDOW_MS = 100L
    private const val FORGET_AFTER_MS = 250L

    private var panX = 0f
    private var panY = 0f
    private var zoom = 1f
    private var shownFor: Slot? = null
    private var shownAt = 0L

    private val onScroll = ScreenMouseEvents.AfterMouseScroll { _, _, _, horizontalAmount, verticalAmount, consumed ->
        if (enabled && System.currentTimeMillis() - shownAt < SCROLL_WINDOW_MS) nudge(horizontalAmount, verticalAmount)
        consumed
    }

    init {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ -> ScreenMouseEvents.afterMouseScroll(screen).register(onScroll) }
    }

    private fun held(left: Int, right: Int) = InputConstants.isKeyDown(left) || InputConstants.isKeyDown(right)

    private fun dir(amount: Double): Float = if (amount > 0.0) 1f else if (amount < 0.0) -1f else 0f

    private fun nudge(dx: Double, dy: Double) {
        val ctrl = held(InputConstants.KEY_LCONTROL, InputConstants.KEY_RCONTROL)
        val shift = held(InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT)
        val px = step.toFloat()
        if (ctrl && scaling) {
            zoom = (zoom * (1f + 0.1f * dir(dy))).coerceIn(0.2f, 5f)
        } else if (shift && horizontal) {
            panX += px * dir(if (dy != 0.0) dy else dx)
        } else if (!ctrl && !shift && vertical) {
            panY += px * dir(dy)
            if (horizontal) panX -= px * dir(dx)
        }
    }

    @JvmStatic
    fun place(pose: Matrix3x2fStack, x: Int, y: Int) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        val screen = mc.gui.screen()
        val slot = if (screen is AbstractContainerScreen<*>) (screen as HoveredSlotAccessor).ecHoveredSlot() else null
        if (resetOnNew && (slot !== shownFor || now - shownAt > FORGET_AFTER_MS)) {
            panX = 0f
            panY = 0f
            zoom = 1f
        }
        shownFor = slot
        shownAt = now
        if (panX == 0f && panY == 0f && zoom == 1f) return
        pose.translate(x + panX, y + panY)
        pose.scale(zoom)
        pose.translate(-x.toFloat(), -y.toFloat())
    }
}
