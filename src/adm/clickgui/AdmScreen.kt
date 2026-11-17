package com.adm.clickgui

import com.adm.features.ModuleManager
import com.adm.utils.ui.compose.UiHost
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

abstract class AdmScreen(title: Component) : Screen(title) {

    protected abstract val ui: UiHost

    protected open val guiScale: Float get() = 1f

    override fun init() = ui.settle()

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean =
        ui.click(local(event), doubleClick) || super.mouseClicked(event, doubleClick)

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean =
        ui.drag(local(event)) || super.mouseDragged(event, dragX, dragY)

    override fun mouseReleased(event: MouseButtonEvent): Boolean =
        ui.release(local(event)) || super.mouseReleased(event)

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val scale = guiScale
        return ui.scroll(mouseX / scale, mouseY / scale, verticalAmount) ||
            super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    override fun charTyped(event: CharacterEvent): Boolean = ui.char(event) || super.charTyped(event)

    override fun keyPressed(event: KeyEvent): Boolean = ui.key(event) || super.keyPressed(event)

    override fun onClose() {
        ui.dismiss()
        ModuleManager.saveConfigurations()
        super.onClose()
    }

    override fun isPauseScreen(): Boolean = false

    private fun local(event: MouseButtonEvent): MouseButtonEvent {
        val scale = guiScale
        return if (scale == 1f) event else MouseButtonEvent(event.x() / scale, event.y() / scale, event.buttonInfo())
    }
}
