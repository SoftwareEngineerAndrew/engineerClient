package com.adm.clickgui

import com.mojang.blaze3d.platform.InputConstants
import com.adm.AdmMod.mc
import com.adm.clickgui.settings.impl.HUDSetting
import com.adm.clickgui.settings.impl.HudElement
import com.adm.features.ModuleManager
import com.adm.features.ModuleManager.hudSettingsCache
import com.adm.utils.Colors
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import kotlin.math.sign

object HudManager : Screen(Component.literal("HUD Manager")) {

    private var held: HUDSetting? = null
    private var offsetX = 0
    private var offsetY = 0

    override fun init() {
        for (setting in hudSettingsCache) if (setting.isEnabled) setting.hud.clampToScreen()
    }

    override fun extractRenderState(guiGraphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        super.extractRenderState(guiGraphics, mouseX, mouseY, deltaTicks)
        HudLayer.render(guiGraphics, example = true, mouseX, mouseY)

        val setting = HudLayer.hovered(mouseX, mouseY) ?: return
        val hud = setting.hud
        val textX = hud.x + hud.scaledWidth + LABEL_GAP
        guiGraphics.text(font, setting.name, textX, hud.y, Colors.WHITE.rgba, true)
        guiGraphics.textWithWordWrap(font, Component.literal(setting.description), textX, hud.y + font.lineHeight + 1, LABEL_WIDTH, Colors.WHITE.rgba)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val setting = HudLayer.hovered(event.x().toInt(), event.y().toInt()) ?: return super.mouseClicked(event, doubleClick)
        held = setting
        offsetX = event.x().toInt() - setting.hud.x
        offsetY = event.y().toInt() - setting.hud.y
        return true
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        val hud = held?.hud ?: return super.mouseDragged(event, dragX, dragY)
        hud.x = event.x().toInt() - offsetX
        hud.y = event.y().toInt() - offsetY
        hud.clampToScreen()
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (held == null) return super.mouseReleased(event)
        held = null
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val setting = HudLayer.hovered(mouseX.toInt(), mouseY.toInt())
            ?: return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        resize(setting.hud, verticalAmount.sign.toFloat() * SCROLL_STEP)
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val scale = mc.window.guiScale.coerceAtLeast(1)
        val setting = HudLayer.hovered((mc.mouseHandler.xpos() / scale).toInt(), (mc.mouseHandler.ypos() / scale).toInt())
            ?: return super.keyPressed(event)
        val hud = setting.hud
        when (event.key) {
            InputConstants.KEY_EQUALS -> resize(hud, KEY_STEP)
            InputConstants.KEY_MINUS -> resize(hud, -KEY_STEP)
            InputConstants.KEY_RIGHT -> hud.x += NUDGE
            InputConstants.KEY_LEFT -> hud.x -= NUDGE
            InputConstants.KEY_UP -> hud.y -= NUDGE
            InputConstants.KEY_DOWN -> hud.y += NUDGE
            else -> return super.keyPressed(event)
        }
        hud.clampToScreen()
        return true
    }

    override fun onClose() {
        held = null
        ModuleManager.saveConfigurations()
        super.onClose()
    }

    override fun isPauseScreen(): Boolean = false

    fun resetHUDS() {
        for (setting in hudSettingsCache) setting.hud.unlink()
        for (setting in hudSettingsCache) {
            val hud = setting.hud
            hud.x = 10
            hud.y = 10
            hud.scale = 1f
        }
    }

    private fun resize(hud: HudElement, amount: Float) {
        hud.scale = (hud.scale + amount).coerceIn(HudElement.MIN_SCALE, HudElement.MAX_SCALE)
        hud.clampToScreen()
    }

    private const val LABEL_GAP = 10
    private const val LABEL_WIDTH = 150
    private const val KEY_STEP = 0.1f
    private const val SCROLL_STEP = 0.1f
    private const val NUDGE = 5
}
