package com.engineerclient.hud

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.engineerclient.EcConfig
import com.engineerclient.CoreHuds
import com.mojang.blaze3d.platform.InputConstants
import com.adm.clickgui.HudLayer
import com.adm.clickgui.HudManager
import com.adm.clickgui.settings.impl.HUDSetting
import com.adm.clickgui.settings.impl.HudElement
import com.adm.features.Category
import com.adm.features.ModuleManager
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sign

/** ADM's grid HUD editor, for every HUD registered with ADM; opened wherever ADM opens its own. */
class HudEditor(private val parent: Screen?) : Screen(Component.literal("HUD Editor")) {

    private var huds = listOf<HUDSetting>()
    private var selected: HUDSetting? = null
    private var dragging = false
    private var grabX = 0
    private var grabY = 0
    private var linking = false
    /** HUDs right clicked off here: kept in the editor, in red, so a right click turns them back on. */
    private val offHere = LinkedHashSet<HUDSetting>()
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
        huds = ModuleManager.hudSettingsCache.filter { (shown(it) || it in offHere) && (filter == null || it.module.category == filter) }
        selected = null
        swappedTime = System.currentTimeMillis()
    }

    override fun init() {
        for (setting in huds) setting.hud.clampToScreen()
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        selected?.let { scale(it.hud, verticalAmount.sign.toFloat() * SCALE_STEP) }
        return true
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val x = event.x().toInt()
        val y = event.y().toInt()
        val hit = huds.lastOrNull { val h = it.hud; x in h.x..h.x + boxW(it) && y in h.y..h.y + boxH(it) }
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            hit?.let(::toggle)
            return true
        }
        val from = selected
        if (linking && from != null) {
            linking = false
            if (hit != null && hit !== from) from.hud.linkTo(hit.hud)
            return true
        }
        selected = hit
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
        snap(hud)
        hud.clampToScreen()
        return true
    }

    /** Pulls [hud] onto the nearest edge of another HUD within the snap distance, on each axis. */
    private fun snap(hud: HudElement) {
        val reach = com.adm.features.impl.render.ClickGUIModule.hudSnap
        if (reach <= 0) return
        val w = hud.scaledWidth
        val h = hud.scaledHeight
        var bestX: Int? = null
        var bestY: Int? = null
        for (other in huds) {
            val o = other.hud
            if (o === hud || o.scaledWidth == 0) continue
            for (c in intArrayOf(o.x, o.x + o.scaledWidth, o.x - w, o.x + o.scaledWidth - w))
                if (kotlin.math.abs(c - hud.x) <= reach && (bestX == null || kotlin.math.abs(c - hud.x) < kotlin.math.abs(bestX - hud.x))) bestX = c
            for (c in intArrayOf(o.y, o.y + o.scaledHeight, o.y - h, o.y + o.scaledHeight - h))
                if (kotlin.math.abs(c - hud.y) <= reach && (bestY == null || kotlin.math.abs(c - hud.y) < kotlin.math.abs(bestY - hud.y))) bestY = c
        }
        bestX?.let { hud.x = it }
        bestY?.let { hud.y = it }
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
                huds.forEach(CoreHuds::reset)
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
            key == InputConstants.KEY_R -> CoreHuds.reset(setting)
            key == InputConstants.KEY_L -> if (hud.link != null) hud.unlink() else linking = !linking
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

    private fun boxW(s: HUDSetting) = maxOf(s.hud.scaledWidth, if (shown(s)) 0 else MIN_OFF_W)
    private fun boxH(s: HUDSetting) = maxOf(s.hud.scaledHeight, if (shown(s)) 0 else MIN_OFF_H)

    /** On or off in the config: the HUD's own switch, or its module's for a HUD without one. */
    private fun toggle(s: HUDSetting) {
        val toggleable = runCatching { HUDSetting::class.java.getDeclaredField("toggleable").apply { isAccessible = true }.getBoolean(s) }.getOrDefault(true)
        if (toggleable) s.hud.enabled = !s.hud.enabled else s.module.toggle()
        offHere += huds.filter { !shown(it) }
    }

    // ADM's grid runs out from the screen's centre.
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

        drawCrosshair(graphics, width / 2, height / 2)

        val longest = INFO.maxOf { font.width(it) }
        for (i in INFO.indices) {
            val y = height - (font.lineHeight + 1) * (INFO.size - i)
            graphics.text(font, INFO[i], 1, y, WHITE, true)
            graphics.text(font, KEYS[i], 7 + longest, y, WHITE, true)
        }

        for (s in huds) {
            if (shown(s)) continue
            val h = s.hud
            graphics.fill(h.x, h.y, h.x + boxW(s), h.y + boxH(s), OFF_COLOR)
            graphics.text(font, s.name, h.x + 2, h.y + 2, WHITE, true)
        }

        selected?.let {
            val hud = it.hud
            graphics.fill(hud.x - MARGIN, hud.y - MARGIN, hud.x + hud.scaledWidth + MARGIN, hud.y + hud.scaledHeight + MARGIN, HIGHLIGHT)
            graphics.text(font, it.name, hud.x + (hud.scaledWidth - font.width(it.name)) / 2, hud.y - font.lineHeight - MARGIN, WHITE, true)
        }

        for (s in huds) {
            val child = s.hud
            val parent = child.parent() ?: continue
            drawLink(graphics, child.x + child.scaledWidth / 2, child.y + child.scaledHeight / 2, parent.x + parent.scaledWidth / 2, parent.y + parent.scaledHeight / 2)
        }

        HudLayer.render(graphics, true, mouseX, mouseY)

        if (linking) selected?.let {
            val text = "Click a HUD to link ${it.name} to"
            graphics.text(font, text, (width - font.width(text)) / 2, 6, LINK_COLOR, true)
        }

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

    // On the grid's centre lines, which sit half a pixel back.
    private fun drawCrosshair(g: GuiGraphicsExtractor, cx: Int, cy: Int) {
        val pose = g.pose()
        pose.pushMatrix()
        pose.translate(-0.5f, -0.5f)
        g.fill(cx - CROSSHAIR_SIZE, cy, cx + CROSSHAIR_SIZE + 1, cy + 1, WHITE)
        g.fill(cx, cy - CROSSHAIR_SIZE, cx + 1, cy, WHITE)
        g.fill(cx, cy + 1, cx + 1, cy + CROSSHAIR_SIZE + 1, WHITE)
        pose.popMatrix()
    }

    private fun drawLink(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int) {
        val steps = maxOf(kotlin.math.abs(x1 - x0), kotlin.math.abs(y1 - y0)) / 3
        for (i in 0..steps) {
            val px = x0 + (x1 - x0) * i / maxOf(steps, 1)
            val py = y0 + (y1 - y0) * i / maxOf(steps, 1)
            g.fill(px, py, px + 1, py + 1, LINK_COLOR)
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
        // Read by HUDSetting.isEnabled while ADM composes its HUDs, so a category switch recomposes them.
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

        private const val CROSSHAIR_SIZE = 4
        private const val GRID = 20
        private const val GRID_COLOR = 0xFF004444.toInt()
        private const val HIGHLIGHT = 0x99FFFFFF.toInt()
        private const val LINK_COLOR = 0xFF55FFFF.toInt()
        private const val OFF_COLOR = 0x99FF2020.toInt()
        private const val MIN_OFF_W = 40
        private const val MIN_OFF_H = 12
        private const val WHITE = -1
        private const val MARGIN = 2
        private const val MOVE_STEP = 2
        private const val SCALE_STEP = 0.02f
        private const val KEY_SCALE_STEP = 0.1f
        private const val DISPLAY_TIME = 1250

        private val KEYS = arrayOf("t + ctrl", "r", "r + ctrl + shift", "g + ctrl", "s", "h + ctrl", "v + ctrl", "tab", "l", "right click", "scroll / + -")
        private val INFO = arrayOf(
            "toggle category", "reset component", "reset all editable components", "toggle grid",
            "snap to closest grid", "center selected horizontally", "center selected vertically",
            "cycle selected component", "link / unlink selected", "turn component on / off", "scale selected component",
        )
    }
}
