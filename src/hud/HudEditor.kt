package com.engineerclient.hud

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.engineerclient.EcConfig
import com.engineerclient.OdinHuds
import com.mojang.blaze3d.platform.InputConstants
import com.odtheking.odin.clickgui.HudLayer
import com.odtheking.odin.clickgui.HudManager
import com.odtheking.odin.clickgui.settings.impl.HUDSetting
import com.odtheking.odin.clickgui.settings.impl.HudElement
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.ModuleManager
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sign

/** Blade's grid HUD editor, for every HUD registered with Odin; opened wherever Odin opens its own. */
class HudEditor(private val parent: Screen?) : Screen(Component.literal("HUD Editor")) {

    private var huds = listOf<HUDSetting>()
    private var selected: HUDSetting? = null
    private var dragging = false
    private var grabX = 0
    private var grabY = 0
    private var categoryIndex = 0
    private var swappedTime = System.currentTimeMillis()

    init {
        filter = null
        updateHuds()
    }

    private fun shown(setting: HUDSetting) = setting.module.enabled && setting.hud.enabled

    private fun categories(): List<Category> {
        val used = ModuleManager.hudSettingsCache.filter(::shown).map { it.module.category }.toSet()
        return Category.categories.values.filter { it in used }
    }

    private fun updateHuds() {
        huds = ModuleManager.hudSettingsCache.filter { shown(it) && (filter == null || it.module.category == filter) }
        selected = null
        swappedTime = System.currentTimeMillis()
    }

    override fun init() {
        HudLayer.settle()
        for (setting in huds) setting.hud.clampToScreen()
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        selected?.let { scale(it.hud, verticalAmount.sign.toFloat() * SCALE_STEP) }
        return true
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val x = event.x().toInt()
        val y = event.y().toInt()
        selected = huds.lastOrNull { val h = it.hud; x in h.x..h.x + h.scaledWidth && y in h.y..h.y + h.scaledHeight }
        selected?.let {
            dragging = true
            grabX = x - it.hud.x
            grabY = y - it.hud.y
        }
        return true
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        val hud = selected?.hud ?: return true
        if (!dragging) return true
        hud.x = event.x().toInt() - grabX
        hud.y = event.y().toInt() - grabY
        hud.clampToScreen()
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        dragging = false
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val ctrl = event.modifiers() and InputConstants.MOD_CONTROL != 0
        val shift = event.modifiers() and InputConstants.MOD_SHIFT != 0
        val key = event.key()
        when {
            key == InputConstants.KEY_R && ctrl && shift -> {
                huds.forEach(OdinHuds::reset)
                huds.forEach { it.hud.clampToScreen() }
                return true
            }
            key == InputConstants.KEY_G && ctrl -> {
                EcConfig.data.hudGrid = !EcConfig.data.hudGrid
                EcConfig.save()
                return true
            }
            key == InputConstants.KEY_T && ctrl -> {
                val list = categories()
                categoryIndex = (categoryIndex + 1) % (list.size + 1)
                filter = if (categoryIndex == 0) null else list[categoryIndex - 1]
                updateHuds()
                return true
            }
            key == InputConstants.KEY_TAB -> {
                if (huds.isNotEmpty()) selected = huds[(huds.indexOf(selected) + 1) % huds.size]
                return true
            }
        }
        val setting = selected ?: return super.keyPressed(event)
        val hud = setting.hud
        val w = minecraft.window.guiScaledWidth
        val h = minecraft.window.guiScaledHeight
        when {
            key == InputConstants.KEY_R -> OdinHuds.reset(setting)
            key == InputConstants.KEY_H && ctrl -> hud.x = (w - hud.scaledWidth) / 2
            key == InputConstants.KEY_V && ctrl -> hud.y = (h - hud.scaledHeight) / 2
            key == InputConstants.KEY_S -> {
                hud.x = snap(hud.x, w / 2)
                hud.y = snap(hud.y, h / 2)
            }
            key == InputConstants.KEY_LEFT -> hud.x -= MOVE_STEP
            key == InputConstants.KEY_RIGHT -> hud.x += MOVE_STEP
            key == InputConstants.KEY_UP -> hud.y -= MOVE_STEP
            key == InputConstants.KEY_DOWN -> hud.y += MOVE_STEP
            key == InputConstants.KEY_EQUALS -> scale(hud, KEY_SCALE_STEP)
            key == InputConstants.KEY_MINUS -> scale(hud, -KEY_SCALE_STEP)
            else -> return super.keyPressed(event)
        }
        hud.clampToScreen()
        return true
    }

    // Blade's grid runs out from the screen's centre.
    private fun snap(pos: Int, center: Int) = center + ((pos - center).toFloat() / GRID).roundToInt() * GRID

    private fun scale(hud: HudElement, amount: Float) {
        hud.scale = (hud.scale + amount).coerceIn(HudElement.MIN_SCALE, HudElement.MAX_SCALE)
        hud.clampToScreen()
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, deltaTicks)
        val width = graphics.guiWidth()
        val height = graphics.guiHeight()

        if (EcConfig.data.hudGrid) {
            val pose = graphics.pose()
            pose.pushMatrix()
            pose.translate(-0.5f, -0.5f)
            for (x in gridLines(width)) graphics.verticalLine(x, 0, height, GRID_COLOR)
            for (y in gridLines(height)) graphics.horizontalLine(0, width, y, GRID_COLOR)
            pose.popMatrix()
        }

        graphics.blit(RenderPipelines.GUI_TEXTURED, CROSSHAIR, width / 2 - CROSSHAIR_SIZE, height / 2 - CROSSHAIR_SIZE, 0f, 0f, CROSSHAIR_SIZE * 2, CROSSHAIR_SIZE * 2, CROSSHAIR_SIZE * 2, CROSSHAIR_SIZE * 2)

        val longest = INFO.maxOf { font.width(it) }
        for (i in INFO.indices) {
            val y = height - (font.lineHeight + 1) * (INFO.size - i)
            graphics.text(font, INFO[i], 1, y, WHITE, true)
            graphics.text(font, KEYS[i], 7 + longest, y, WHITE, true)
        }

        selected?.let {
            val hud = it.hud
            graphics.fill(hud.x - MARGIN, hud.y - MARGIN, hud.x + hud.scaledWidth + MARGIN, hud.y + hud.scaledHeight + MARGIN, HIGHLIGHT)
            graphics.text(font, it.name, hud.x + (hud.scaledWidth - font.width(it.name)) / 2, hud.y - font.lineHeight - MARGIN, WHITE, true)
        }

        HudLayer.render(graphics, true, mouseX, mouseY)

        val since = System.currentTimeMillis() - swappedTime
        if (since < DISPLAY_TIME) {
            val text = filter?.name ?: "all"
            val alpha = (cos(since.toDouble() / DISPLAY_TIME * Math.PI / 2) * 255).toInt().coerceIn(4, 255)
            val pose = graphics.pose()
            pose.pushMatrix()
            pose.scale(1.5f, 1.5f)
            val x = (width - font.width(text)) / 2
            val y = (height - font.lineHeight) / 2 - 20
            graphics.text(font, text, (x / 1.5f).toInt(), (y / 1.5f).toInt(), alpha shl 24 or 0xFFFFFF, true)
            pose.popMatrix()
        }
    }

    private fun gridLines(size: Int): List<Int> =
        (size / 2 downTo 0 step GRID) + (size / 2 + GRID until size step GRID)

    override fun removed() {
        filter = null
        ModuleManager.saveConfigurations()
    }

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }

    override fun isPauseScreen() = false

    companion object {
        // Read by HUDSetting.isEnabled while Odin composes its HUDs, so a category switch recomposes them.
        private var filter by mutableStateOf<Category?>(null)

        @JvmStatic
        fun hides(setting: HUDSetting): Boolean {
            val category = filter ?: return false
            return setting.module.category != category
        }

        @JvmStatic
        fun isOpen() = Minecraft.getInstance().gui.screen() is HudEditor

        @JvmStatic
        fun replace(screen: Screen?): Screen? = if (screen === HudManager) HudEditor(Minecraft.getInstance().gui.screen()) else screen

        private val CROSSHAIR = Identifier.fromNamespaceAndPath("engineerclient", "hud/crosshair.png")
        private const val CROSSHAIR_SIZE = 4
        private const val GRID = 20
        private const val GRID_COLOR = 0xFF004444.toInt()
        private const val HIGHLIGHT = 0x99FFFFFF.toInt()
        private const val WHITE = -1
        private const val MARGIN = 2
        private const val MOVE_STEP = 2
        private const val SCALE_STEP = 0.02f
        private const val KEY_SCALE_STEP = 0.1f
        private const val DISPLAY_TIME = 1250

        private val KEYS = arrayOf("t + ctrl", "r", "r + ctrl + shift", "g + ctrl", "s", "h + ctrl", "v + ctrl", "tab", "scroll / + -")
        private val INFO = arrayOf(
            "toggle category", "reset component", "reset all editable components", "toggle grid",
            "snap to closest grid", "center selected horizontally", "center selected vertically",
            "cycle selected component", "scale selected component",
        )
    }
}
