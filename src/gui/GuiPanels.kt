package com.engineerclient.gui

import com.engineerclient.EngineerClient.mc
import com.mojang.blaze3d.platform.InputConstants
import com.odtheking.odin.clickgui.settings.RenderableSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.ModuleManager
import com.odtheking.odin.features.impl.render.ClickGUIModule
import com.odtheking.odin.utils.Color.Companion.brighter
import com.odtheking.odin.utils.Color.Companion.darker
import com.odtheking.odin.utils.render.Corners
import com.odtheking.odin.utils.render.roundedRect
import com.odtheking.odin.utils.render.roundedShadow
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Which panel each module sits in: its own category, or for an addon's own panel, the closest of the usual ones. */
object Placement {
    val standard: List<Category> get() = listOf(Category.DUNGEON, Category.BOSS, Category.RENDER, Category.SKYBLOCK, Category.NETHER)
    const val OTHER = "Misc"

    private val byName = mapOf(
        "ADR" to "Dungeon", "Clear Breakdown" to "Dungeon", "Block Instaclear" to "Dungeon", "Item Highlight" to "Dungeon",
        "Puzzle Overlay" to "Dungeon", "Timers" to "Dungeon", "POV Previews" to "Dungeon", "Sub Splits" to "Dungeon",
        "BR Roles" to "Dungeon", "Pre-Requeue" to "Dungeon", "Better Recorder" to "Dungeon", "Dungeon Recorder" to "Dungeon",
        "All Sub Splits" to "Dungeon",
        "Agro Sphere" to "Boss", "LCM Melee Warning" to "Boss", "ADS" to "Boss", "Term Progress" to "Boss", "EE Highlight" to "Boss",
        "Recore" to "Boss", "SS Practice" to "Boss", "Terminal Hitboxes" to "Boss", "Highlight Levers" to "Boss", "Term Info" to "Boss",
        "Termsim" to "Boss", "Timers2" to "Boss", "Boss Recorder" to "Boss", "Maxor Crystals" to "Boss", "i4 Aim" to "Boss",
        "Health & Mana" to "Render", "Chunk Culling" to "Render", "Camera Offset" to "Render", "Old Blocking" to "Render",
        "Render Tweaks" to "Render", "Performance Monitor" to "Render",
        "Random Stuff" to "Skyblock", "Legit Zpew" to "Skyblock", "Coffee Random Stuff" to "Skyblock", "Quiet Media" to "Skyblock",
        "Sound Editor" to "Skyblock", "Sound Collector" to "Skyblock", "Fast Launch" to "Skyblock",
    )

    fun of(module: Module): String {
        if (module.category in standard) return module.category.name
        byName[module.name]?.let { return it }
        val pkg = module.javaClass.packageName
        for ((word, panel) in listOf("dungeon" to "Dungeon", "boss" to "Boss", "nether" to "Nether", "kuudra" to "Nether", "render" to "Render", "skyblock" to "Skyblock"))
            if (word in pkg) return panel
        return OTHER
    }
}

class ModuleView(val module: Module) {
    val views = module.settings.values.filterIsInstance<RenderableSetting<*>>().map(SettingView::of)
    var expanded = false
    private val expand = Anim(250f)
    private val toggled = Anim(150f, module.enabled)
    private val hover = Anim(150f)
    private val shown = Anim(150f, true)

    private var shownP = 1f
    private var expandP = 0f
    private val settingP = FloatArray(views.size)
    private var settingsHeight = 0

    /** Updates the animations for this frame and returns the height to lay it out at. */
    fun measure(query: String): Int {
        val nameMatch = query.isEmpty() || module.name.contains(query, true)
        val settingMatch = if (query.isEmpty() || nameMatch) emptySet()
        else views.filter { it.setting.isVisible && it.setting.name.contains(query, true) }.toSet()
        shownP = shown.update(nameMatch || settingMatch.isNotEmpty())
        expandP = expand.update(views.isNotEmpty() && (expanded || settingMatch.isNotEmpty()))
        settingsHeight = 0
        views.forEachIndexed { i, v ->
            settingP[i] = v.shown.update(v.setting.isVisible && (settingMatch.isEmpty() || v in settingMatch))
            settingsHeight += (v.height() * settingP[i]).roundToInt()
        }
        return ((Theme.ROW + settingsHeight * expandP) * shownP).roundToInt()
    }

    fun draw(ui: Ui, x: Int, y: Int, height: Int) {
        if (height <= 0) return
        ui.clipped(x, y, x + Theme.WIDTH, y + height) {
            header(ui, x, y)
            if (expandP <= 0f) return@clipped
            var sy = y + Theme.ROW
            ui.clipped(x, sy, x + Theme.WIDTH, sy + (settingsHeight * expandP).roundToInt()) {
                views.forEachIndexed { i, v ->
                    val p = settingP[i]
                    if (p <= 0f) return@forEachIndexed
                    val full = v.height()
                    val h = (full * p).roundToInt()
                    if (h > 0) {
                        if (ui.hovered(x, sy, x + Theme.WIDTH, sy + h)) AdmClickGui.hoveredView = v
                        if (p < 1f) ui.clipped(x, sy, x + Theme.WIDTH, sy + h) { v.draw(ui, x, sy) } else v.draw(ui, x, sy)
                    }
                    sy += h
                }
            }
        }
    }

    private fun header(ui: Ui, x: Int, y: Int) {
        val t = toggled.update(module.enabled)
        val h = hover.update(ui.hovered(x, y, x + Theme.WIDTH, y + Theme.ROW))
        var color = blend(Theme.background.rgba, Theme.accent.rgba, t)
        if (h > 0f) color = blend(color, blend(Theme.background.brighter(1.4f).rgba, Theme.accent.darker(0.8f).rgba, t), h)
        ui.g.fill(x, y, x + Theme.WIDTH, y + Theme.ROW, color)
        ui.textCentered(module.name, x, y, x + Theme.WIDTH, y + Theme.ROW)
        if (views.isNotEmpty()) {
            val dots = if (expanded) "-" else "+"
            ui.text(dots, x + Theme.WIDTH - Theme.PAD - ui.font.width(dots), y + (Theme.ROW - 8) / 2, 0x80FFFFFF.toInt())
        }
        ui.tooltip(x, y, x + Theme.WIDTH, y + Theme.ROW, module.description)
        ui.click(x, y, x + Theme.WIDTH, y + Theme.ROW, right = { if (views.isNotEmpty()) expanded = !expanded }) { module.toggle() }
    }
}

class Panel(val name: String, val modules: List<ModuleView>, defaultX: Int, defaultY: Int) {
    val data: ClickGUIModule.PanelData = ClickGUIModule.panelSetting.getOrPut(name) { ClickGUIModule.PanelData(defaultX, defaultY) }
    private val extend = Anim(250f, data.extended)
    private var scroll = 0f
    private val smooth = Follow(0f)
    private var maxScroll = 0
    var bounds = Rect(0, 0, 0, 0)
        private set

    fun scrollBy(amount: Double) {
        if (!data.extended) return
        scroll = (scroll - amount.toFloat() * 24f).coerceIn(0f, maxScroll.toFloat())
    }

    fun draw(ui: Ui, vw: Int, vh: Int, query: String) {
        val x = data.x.coerceIn(0, maxX(vw))
        val y = data.y.coerceIn(0, maxY(vh))
        val heights = IntArray(modules.size) { modules[it].measure(query) }
        val total = heights.sum()
        val ext = extend.update(data.extended)
        val room = max(MIN_LIST, vh - y - Theme.ROW - Theme.CAP - 4)
        val visible = min(total, room)
        maxScroll = max(0, total - visible)
        scroll = scroll.coerceIn(0f, maxScroll.toFloat())
        val offset = smooth.update(scroll).roundToInt()
        val shown = (visible * ext).roundToInt()
        val bottom = y + Theme.ROW + shown + Theme.CAP
        bounds = Rect(x, y, x + Theme.WIDTH, bottom)

        ui.g.roundedShadow(x, y, x + Theme.WIDTH, bottom, Theme.shadow, 12f, Corners(Theme.RADIUS))
        ui.hit(x, y, x + Theme.WIDTH, bottom) { _, _, _ -> true }

        ui.g.roundedRect(x, y, x + Theme.WIDTH, y + Theme.ROW, Theme.background.rgba, Corners.top(Theme.RADIUS))
        ui.textCentered("§l$name", x, y, x + Theme.WIDTH, y + Theme.ROW)
        ui.hit(x, y, x + Theme.WIDTH, y + Theme.ROW) { button, mx, my ->
            when (button) {
                InputConstants.MOUSE_BUTTON_RIGHT -> {
                    data.extended = !data.extended
                    scroll = 0f
                }
                InputConstants.MOUSE_BUTTON_LEFT -> {
                    val grabX = mx - x
                    val grabY = my - y
                    AdmClickGui.drag({ dx, dy ->
                        data.x = (dx - grabX).toInt().coerceIn(0, maxX(vw))
                        data.y = (dy - grabY).toInt().coerceIn(0, maxY(vh))
                    })
                }
                else -> return@hit false
            }
            true
        }

        var lastShown: ModuleView? = null
        if (shown > 0) ui.clipped(x, y + Theme.ROW, x + Theme.WIDTH, y + Theme.ROW + shown) {
            ui.g.fill(x, y + Theme.ROW, x + Theme.WIDTH, y + Theme.ROW + shown, Theme.background.rgba)
            var my = y + Theme.ROW - offset
            modules.forEachIndexed { i, m ->
                val h = heights[i]
                if (h > 0) lastShown = m
                if (h > 0 && my + h > y + Theme.ROW && my < y + Theme.ROW + shown) m.draw(ui, x, my, h)
                my += h
            }
            if (maxScroll > 0) {
                val barH = max(12, shown * visible / total)
                val barY = y + Theme.ROW + ((shown - barH) * (offset.toFloat() / maxScroll)).roundToInt()
                ui.g.roundedRect(x + Theme.WIDTH - 3, barY, x + Theme.WIDTH - 1, barY + barH, Theme.accent.rgba, 1f)
            }
        }

        val accented = shown > 0 && offset >= maxScroll && lastShown?.module?.enabled == true
        ui.g.roundedRect(x, bottom - Theme.CAP, x + Theme.WIDTH, bottom, (if (accented) Theme.accent else Theme.background).rgba, Corners.bottom(Theme.RADIUS))
    }

    private fun maxX(vw: Int) = max(0, vw - Theme.WIDTH)
    private fun maxY(vh: Int) = max(0, vh - Theme.ROW - Theme.CAP - MIN_LIST)

    companion object {
        const val MIN_LIST = 72

        fun build(views: (Module) -> ModuleView): List<Panel> {
            val grouped = LinkedHashMap<String, MutableList<Module>>()
            for (c in Placement.standard) grouped[c.name] = mutableListOf()
            for (m in ModuleManager.modules.values) grouped.getOrPut(Placement.of(m)) { mutableListOf() }.add(m)
            return grouped.filterValues { it.isNotEmpty() }.map { (name, list) ->
                val cat = Placement.standard.firstOrNull { it.name == name }
                val x = cat?.x ?: (Placement.standard.maxOf { it.x } + 170)
                Panel(name, list.sortedByDescending { mc.font.width(it.name) }.map(views), x, cat?.y ?: 10)
            }
        }
    }
}
