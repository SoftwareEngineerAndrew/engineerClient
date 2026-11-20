package com.engineerclient.gui

import com.engineerclient.EngineerClient.mc
import com.mojang.blaze3d.platform.InputConstants
import com.adm.clickgui.settings.RenderableSetting
import com.adm.features.Category
import com.adm.features.Module
import com.adm.features.ModuleManager
import com.adm.features.impl.render.ClickGUIModule
import com.adm.utils.Color.Companion.brighter
import com.adm.utils.Color.Companion.darker
import com.adm.utils.render.Corners
import com.adm.utils.render.roundedRect
import com.adm.utils.render.roundedShadow
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Which panel each module sits in. */
object Placement {
    /** Kept out of the panels; opened on their own instead. */
    val HIDDEN = setOf("POV Previews")

    val PANELS = listOf("Dungeons", "Clear", "F7/M7 Boss", "Crimson Isle", "Skyblock", "Render", "AscentClient", "Devgineer Client", "Ashley Client")

    /** Where a panel starts: six to a row. */
    fun home(name: String): Pair<Int, Int> {
        val i = PANELS.indexOf(name).coerceAtLeast(0)
        return 10 + (i % 6) * (Theme.WIDTH + 10) to 10 + (i / 6) * 220
    }

    private val lists = listOf(
        "Dungeons" to listOf("Leap Menu", "Splits", "Sub Splits", "Highlights", "Waypoints", "Better Party Finder", "Class Colors", "Refill", "Mage Beam", "Dungeonbreaker", "Boss Bar", "Dungeon Queue", "Blessing Display", "Salvage Overlay", "Croesus", "Spirit Bear", "Livid Solver", "Terracotta Timer", "ADR", "All Sub Splits", "Maxor Crystals", "Better Recorder", "Dungeon Recorder", "Boss Recorder"),
        "Clear" to listOf("BR Roles", "Dungeon Map", "Clear Info", "Secrets", "Puzzles", "Door Highlight", "Block Instaclear", "Mimic", "Blood Camp"),
        "F7/M7 Boss" to listOf("Terminals", "Devices", "EE Highlight", "Recore", "Tick Timers", "Melody Message", "Positional Messages", "Invincibility Timer", "Wither Dragons", "Relics", "Spring Boots", "Explosive Shot", "Agro Sphere", "i4 Aim", "ADS"),
        "Crimson Isle" to listOf("Build Helper", "Fresh Tools", "Kuudra Info", "Kuudra Tracker", "Misc", "Pearl Waypoints", "Pre-Spot Alert", "Remove Perks", "Supply Helper", "Vesuvius", "Blaze Dagger HUD"),
        "Skyblock" to listOf("Etherwarp", "Lag Warning", "Command Keybinds", "Command Aliases", "LD WD Pet Keybinds", "Item Tweaks", "Item Protection", "Ragnarock", "Gyro Wand", "Player Display", "Pet Display", "Arrows", "Random Stuff", "Enchant Colors", "Chat Commands", "Fast Launch", "Sound Collector"),
        "Render" to listOf("Visibility", "Sounds", "Camera", "Item Animations", "Chunk Culling", "Performance", "Player Size", "Search Bar", "Slot Binds", "Block Overlay", "Colors", "Scrollable Tooltip", "Click GUI", "Render Tweaks"),
    )

    private val byName: Map<String, String> = lists.flatMap { (panel, names) -> names.map { it to panel } }.toMap()

    /** Panels listed in their own order rather than widest name first. */
    private val ORDERED = setOf("Dungeons", "Clear", "F7/M7 Boss", "Skyblock", "Render")

    /** Where [module] sits in its panel: its place in an ordered panel's list (unlisted last), else 0 (widest name first decides). */
    fun rank(module: Module): Int {
        if (of(module) !in ORDERED) return 0
        return order.indexOf(module.name).let { if (it < 0) Int.MAX_VALUE else it }
    }

    private val order: List<String> by lazy { lists.flatMap { it.second } }

    fun of(module: Module): String {
        // The addons' modules keep their own panel, named by the category each gives them.
        when (module.category.name) {
            "Ascent Client" -> return "AscentClient"
            "Devgineer Client", "Ashley Client" -> return module.category.name
        }
        byName[module.name]?.let { return it }
        return when (module.category) {
            Category.DUNGEON -> "Clear"
            Category.BOSS -> "Dungeons"
            Category.NETHER -> "Crimson Isle"
            Category.RENDER -> "Render"
            else -> "Skyblock"
        }
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
    private var settingsHeight = -1

    private var nameX = 0
    private var minusX = 0
    private var plusX = 0
    private var headerT = -1f
    private var headerH = -1f
    private var headerAccent = 0
    private var headerColor = 0

    /** Text offsets, measured again only when the menu opens. */
    fun layout() {
        nameX = (Theme.WIDTH - mc.font.width(module.name)) / 2
        minusX = Theme.WIDTH - Theme.PAD - mc.font.width("-")
        plusX = Theme.WIDTH - Theme.PAD - mc.font.width("+")
    }

    private var matchQuery: String? = null
    private var nameMatch = true
    private var settingMatch: Set<SettingView> = emptySet()

    private fun match(query: String) {
        if (query == matchQuery) return
        matchQuery = query
        nameMatch = query.isEmpty() || module.name.contains(query, true)
        settingMatch = if (nameMatch) emptySet()
            else views.filter { it.setting !is com.adm.clickgui.settings.impl.DropdownSetting && it.setting.name.contains(query, true) }.toSet()
    }

    /** Updates the animations for this frame and returns the height to lay it out at. */
    fun measure(query: String, openMatches: Boolean): Int {
        match(query)
        shownP = shown.update(nameMatch || settingMatch.isNotEmpty())
        expandP = expand.update(views.isNotEmpty() && (expanded || (openMatches && settingMatch.isNotEmpty())))
        // Collapsed and settled: the settings add no height, so their animations can wait until it opens.
        if (expandP > 0f || settingsHeight < 0) {
            settingsHeight = 0
            for (i in views.indices) {
                val v = views[i]
                settingP[i] = v.shown.update(if (settingMatch.isEmpty()) v.setting.isVisible else v in settingMatch)
                settingsHeight += (v.height() * settingP[i]).roundToInt()
            }
        }
        return ((Theme.MODULE + settingsHeight * expandP) * shownP).roundToInt()
    }

    fun draw(ui: Ui, x: Int, y: Int, height: Int) {
        if (height <= 0) return
        ui.clipped(x, y, x + Theme.WIDTH, y + height) {
            header(ui, x, y)
            if (expandP <= 0f) return@clipped
            var sy = y + Theme.MODULE
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
        val h = hover.update(ui.hovered(x, y, x + Theme.WIDTH, y + Theme.MODULE))
        val accent = Theme.accent.rgba
        // The colour only moves while an animation runs or the accent changes.
        if (t != headerT || h != headerH || accent != headerAccent) {
            headerT = t; headerH = h; headerAccent = accent
            var color = blend(Theme.background.rgba, accent, t)
            if (h > 0f) color = blend(color, blend(Theme.background.brighter(1.4f).rgba, Theme.accent.darker(0.8f).rgba, t), h)
            headerColor = color
        }
        ui.g.fill(x, y, x + Theme.WIDTH, y + Theme.MODULE, headerColor)
        ui.text(module.name, x + nameX, y + (Theme.MODULE - 8) / 2)
        if (views.isNotEmpty()) ui.text(if (expanded) "-" else "+", x + if (expanded) minusX else plusX, y + (Theme.MODULE - 8) / 2, 0x80FFFFFF.toInt())
        ui.tooltip(x, y, x + Theme.WIDTH, y + Theme.MODULE, module.description)
        ui.click(x, y, x + Theme.WIDTH, y + Theme.MODULE, right = { if (views.isNotEmpty()) expanded = !expanded }) { module.toggle() }
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
    private val title = "§l$name"
    private var titleX = 0
    private val heights = IntArray(modules.size)

    /** Text offsets, measured again only when the menu opens. */
    fun layout() {
        titleX = (Theme.WIDTH - mc.font.width(title)) / 2
        for (m in modules) m.layout()
    }

    fun scrollBy(amount: Double) {
        if (!data.extended) return
        scroll = (scroll - amount.toFloat() * 24f).coerceIn(0f, maxScroll.toFloat())
    }

    fun draw(ui: Ui, vw: Int, vh: Int, query: String, openMatches: Boolean) {
        val x = data.x.coerceIn(0, maxX(vw))
        val y = data.y.coerceIn(0, maxY(vh))
        var total = 0
        for (i in modules.indices) { heights[i] = modules[i].measure(query, openMatches); total += heights[i] }
        val ext = extend.update(data.extended)
        val room = max(MIN_LIST, vh - y - Theme.ROW - Theme.CAP - 4)
        val visible = min(total, room)
        maxScroll = max(0, total - visible)
        scroll = scroll.coerceIn(0f, maxScroll.toFloat())
        val offset = smooth.update(scroll).roundToInt()
        val shown = (visible * ext).roundToInt()
        val bottom = y + Theme.ROW + shown + Theme.CAP
        bounds = Rect(x, y, x + Theme.WIDTH, bottom)

        ui.g.roundedShadow(x, y, x + Theme.WIDTH, bottom, Theme.shadow, 12f, ALL)
        ui.hit(x, y, x + Theme.WIDTH, bottom) { _, _, _ -> true }

        ui.g.roundedRect(x, y, x + Theme.WIDTH, y + Theme.ROW, Theme.background.rgba, TOP)
        ui.text(title, x + titleX, y + (Theme.ROW - 8) / 2)
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
        ui.g.roundedRect(x, bottom - Theme.CAP, x + Theme.WIDTH, bottom, (if (accented) Theme.accent else Theme.background).rgba, BOTTOM)
    }

    private fun maxX(vw: Int) = max(0, vw - Theme.WIDTH)
    private fun maxY(vh: Int) = max(0, vh - Theme.ROW - Theme.CAP - MIN_LIST)

    companion object {
        const val MIN_LIST = 72
        private val ALL = Corners(Theme.RADIUS)
        private val TOP = Corners.top(Theme.RADIUS)
        private val BOTTOM = Corners.bottom(Theme.RADIUS)

        fun build(views: (Module) -> ModuleView): List<Panel> {
            val grouped = LinkedHashMap<String, MutableList<Module>>()
            // Places saved before the panels were renamed or narrowed would pile them up.
            if ("Six Wide" !in ClickGUIModule.panelSetting) { ClickGUIModule.panelSetting.clear(); ClickGUIModule.panelSetting["Six Wide"] = ClickGUIModule.PanelData(0, 0) }
            for (name in Placement.PANELS) grouped[name] = mutableListOf()
            for (m in ModuleManager.modules.values) if (m.name !in Placement.HIDDEN) grouped.getOrPut(Placement.of(m)) { mutableListOf() }.add(m)
            return grouped.filterValues { it.isNotEmpty() }.map { (name, list) ->
                val (x, y) = Placement.home(name)
                Panel(name, list.sortedWith(compareBy<Module> { Placement.rank(it) }.thenByDescending { mc.font.width(it.name) }).map(views), x, y)
            }
        }
    }
}
