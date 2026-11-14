package com.engineerclient.gui

import com.engineerclient.EngineerClient.mc
import com.engineerclient.splits.OdinSplitsLook
import com.mojang.blaze3d.platform.InputConstants
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.ModuleManager
import com.odtheking.odin.features.impl.render.ClickGUIModule
import com.odtheking.odin.utils.Color.Companion.hsbMax
import com.odtheking.odin.utils.render.Corners
import com.odtheking.odin.utils.render.roundedRectOutlined
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

object AdmClickGui : Screen(Component.literal("ADM")) {

    private var scale = 1f
    private val vw get() = (mc.window.guiScaledWidth / scale).toInt()
    private val vh get() = (mc.window.guiScaledHeight / scale).toInt()

    private val search = TextField(24)
    private val moduleViews = HashMap<Module, ModuleView>()
    private var panels = mutableListOf<Panel>()
    private var full = mutableListOf<Panel>()
    private var solo: Module? = null
    private var builtFor = -1
    private var hits = ArrayList<Hit>()

    var focus: Any? = null
        private set
    var hoveredView: SettingView? = null
    private var dragMove: ((Double, Double) -> Unit)? = null
    private var dragEnd: (() -> Unit)? = null

    private var tipText: String? = null
    private var tipX = 0
    private var tipY = 0
    private var tipShown: String? = null
    private var tipSince = 0L

    fun focus(target: Any) {
        if (focus === target) return
        unfocus()
        focus = target
    }

    fun unfocus() {
        val was = focus ?: return
        focus = null
        if (was is TextField) was.onDone?.invoke()
    }

    fun drag(move: (Double, Double) -> Unit, end: () -> Unit = {}) {
        dragMove = move
        dragEnd = end
    }

    fun offerTooltip(text: String, x: Int, y: Int) {
        tipText = text
        tipX = x
        tipY = y
    }

    /** Opens the menu showing only [module], expanded. */
    fun open(module: Module) {
        solo = module
        mc.setScreenAndShow(this)
    }

    private fun view(module: Module) = moduleViews.getOrPut(module) { ModuleView(module) }

    override fun init() {
        scale = ClickGUIModule.clickGuiScale / mc.window.guiScale
        if (builtFor != ModuleManager.modules.size) {
            full = Panel.build(::view).toMutableList()
            builtFor = ModuleManager.modules.size
        }
        val one = solo
        panels = if (one == null) full
        else mutableListOf(Panel(one.name, listOf(view(one).also { it.expanded = true }), (vw - Theme.WIDTH) / 2, 20))
        focus = search
        runCatching { OdinSplitsLook.refreshPlaceholders() }
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val mx = mouseX / scale.toDouble()
        val my = mouseY / scale.toDouble()
        val frameHits = ArrayList<Hit>()
        val ui = Ui(graphics, mx, my, frameHits)
        val query = search.text.trim()
        val sx0 = vw / 2 - SEARCH_W / 2
        val sy0 = vh - SEARCH_BOTTOM
        val overSearch = mx >= sx0 && mx < sx0 + SEARCH_W && my >= sy0 && my < sy0 + SEARCH_H
        val top = if (overSearch || dragMove != null) null else panels.lastOrNull { it.bounds.contains(mx, my) }
        hoveredView = null
        tipText = null

        graphics.pose().pushMatrix()
        graphics.pose().scale(scale, scale)
        for (panel in panels) {
            ui.hoverEnabled = panel === top
            panel.draw(ui, vw, vh, query)
        }
        ui.hoverEnabled = dragMove == null
        search.draw(ui, sx0, sy0, sx0 + SEARCH_W, sy0 + SEARCH_H, "Search...", centered = true, radius = 6f)
        ui.text("§lADM", 6, vh - 14, ClickGUIModule.clickGUIColor.rgba)
        drawTooltip(ui)
        graphics.pose().popMatrix()
        hits = frameHits
    }

    private fun drawTooltip(ui: Ui) {
        val text = tipText
        if (text != tipShown) {
            tipShown = text
            tipSince = System.currentTimeMillis()
        }
        if (text == null || System.currentTimeMillis() - tipSince < TOOLTIP_DELAY) return
        val lines = font.split(Component.literal(text), 200)
        val w = (lines.maxOfOrNull { font.width(it) } ?: 0) + 16
        val h = lines.size * font.lineHeight + 16
        val x = (tipX + 10).coerceIn(0, (vw - w).coerceAtLeast(0))
        val y = tipY.coerceIn(0, (vh - h).coerceAtLeast(0))
        ui.g.nextStratum()
        ui.g.roundedRectOutlined(x, y, x + w, y + h, Theme.surface.rgba, Theme.accent.hsbMax().rgba, 1.5f, Corners(Theme.RADIUS))
        lines.forEachIndexed { i, line -> ui.g.text(font, line, x + 8, y + 8 + i * font.lineHeight, Theme.WHITE, false) }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val x = event.x() / scale
        val y = event.y() / scale
        panels.lastOrNull { it.bounds.contains(x, y) }?.let { if (panels.last() !== it) { panels.remove(it); panels.add(it) } }
        val hit = hits.asReversed().firstOrNull { it.rect.contains(x, y) }
        if (focus != null && hit?.owner !== focus) unfocus()
        hit?.onPress?.invoke(event.button(), x, y)
        return true
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        dragMove?.invoke(event.x() / scale, event.y() / scale)
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        dragEnd?.invoke()
        dragMove = null
        dragEnd = null
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        panels.lastOrNull { it.bounds.contains(mouseX / scale, mouseY / scale) }?.scrollBy(verticalAmount)
        return true
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        val f = focus
        if (f is TextField) return f.char(event)
        if (f == null && event.isAllowedChatCharacter && !event.codepointAsString().isBlank()) {
            focus(search)
            return search.char(event)
        }
        return false
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        when (val f = focus) {
            is KeybindView -> {
                f.bind(event)
                return true
            }
            is TextField -> {
                if (event.key() == InputConstants.KEY_ESCAPE && f === search) return super.keyPressed(event)
                if (f.key(event)) return true
            }
        }
        if (event.key() == InputConstants.KEY_F && event.hasControlDownWithQuirk()) {
            focus(search)
            return true
        }
        if (hoveredView?.key(event, true) == true) return true
        return super.keyPressed(event)
    }

    override fun removed() {
        solo = null
        unfocus()
        dragMove = null
        dragEnd = null
        ModuleManager.saveConfigurations()
        super.removed()
    }

    override fun isPauseScreen(): Boolean = false

    private const val SEARCH_W = 120
    private const val SEARCH_H = 20
    private const val SEARCH_BOTTOM = 45
    private const val TOOLTIP_DELAY = 500L
}
