package com.engineerclient.ads

import com.engineerclient.EngineerClient
import com.engineerclient.p3sim.SimWorld
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.rustclear.mod.Floor
import net.rustclear.mod.ui.RustclearScreen
import net.rustclear.mod.ui.Theme

/**
 * The ADS menu's Practice page: a solo world set up for one thing. The rail picks what (a whole clear, one room,
 * blood, or a boss phase); the right side holds that one's options; Start opens it. Everything but P3 is the
 * dungeon engine's world ([EnginePractice]); P3 is the P3 Sim's own, whose in-world menu has its options.
 */
class PracticeScreen(parent: Screen) : RustclearScreen(Component.literal("ADS practice"), parent) {

    enum class Mode(val title: String, val summary: String, val color: Int, val boss: Boolean = false) {
        FULL_CLEAR("Full clear", "A whole run", Theme.NORMAL),
        ROOM("Single room", "One room, one door", Theme.PUZZLE),
        BLOOD_RUSH("Blood rush", "Entrance to blood door", Theme.BLOOD),
        BLOOD_CAMP("Blood camp", "Watcher to portal", Theme.BLOOD),
        P1("P1 Maxor", "Crystals and lasers", Theme.MINIBOSS, true),
        P2("P2 Storm", "Pillars and crushers", Theme.MINIBOSS, true),
        P3("P3 Goldor", "Terminals, P3 Sim", Theme.GOLD, true),
        P4("P4 Necron", "The platform fight", Theme.MINIBOSS, true),
        P5("P5 Wither King", "Relics and dragons, M7", Theme.MINIBOSS, true),
    }

    private val settings get() = PracticeSettings.data
    private var mode = Mode.entries.firstOrNull { it.name == settings.mode } ?: Mode.FULL_CLEAR

    private var seedBox: EditBox? = null
    private var searchBox: EditBox? = null
    private val rooms: List<String> by lazy { runCatching { EnginePractice.roomNames() }.getOrDefault(emptyList()) }
    private var roomScroll = 0
    private var roomMaxScroll = 0
    /** The room list's window in the last frame (x, y, w, h), for the mouse wheel. */
    private var roomView: IntArray? = null
    /** Why the last Start didn't open anything, shown in the footer until the next one. */
    private var error: String? = null

    override fun init() {
        layoutPanel(Int.MAX_VALUE, Int.MAX_VALUE)
        seedBox = null
        searchBox = null
        val x = contentX()
        if (mode == Mode.FULL_CLEAR || mode == Mode.BLOOD_RUSH) {
            seedBox = input(x + 6, seedRowY() + 3, 130, "Seed", 24).also { box ->
                box.value = settings.seed
                box.setResponder { settings.seed = it }
                addRenderableWidget(box)
            }
        }
        if (mode == Mode.ROOM) {
            searchBox = input(x + 14, roomSearchY() + 3, 104, "Search rooms", 32).also { box ->
                box.setResponder { roomScroll = 0 }
                addRenderableWidget(box)
            }
        }
    }

    private fun railW() = 132
    private fun contentX() = px + railW() + 12
    private fun contentW() = pw - railW() - 24
    private fun optionsY() = py + 32 + 44
    private fun seedRowY() = optionsY() + 40
    private fun roomSearchY() = optionsY() + 34

    private fun pick(m: Mode) {
        if (m == mode) return
        mode = m
        settings.mode = m.name
        error = null
        rebuildWidgets()
    }

    override fun extractFrame(g: GuiGraphicsExtractor, mx: Int, my: Int, delta: Float) {
        panel(g)
        header(g, "Practice", if (mode == Mode.P3) "P3 SIM" else floor().code, Theme.GOLD)
        railBackground(g, railW())
        drawRail(g, mx, my)

        val x = contentX()
        val w = contentW()
        val by = py + ph - 23
        title(g, mode.title, x, py + 32)
        var ly = py + 46
        for (line in wrap(describe(mode), w).take(2)) {
            text(g, line, x, ly, Theme.MUTED)
            ly += 10
        }
        when (mode) {
            Mode.FULL_CLEAR, Mode.BLOOD_RUSH -> { floorRow(g, mx, my, x); seedRow(g, mx, my, x, w) }
            Mode.ROOM -> { floorRow(g, mx, my, x); roomPicker(g, mx, my, x, w, by - 8) }
            Mode.P3 -> p3Notes(g, x, w)
            else -> floorRow(g, mx, my, x)
        }

        rect(g, x, by - 5, w, 1, Theme.PANEL_EDGE)
        val back = button(g, mx, my, "Back", px + pw - 8, by, Style.SECONDARY) { onClose() }
        button(g, mx, my, "Start", back - 4, by, Style.PRIMARY, startAction())
        val hint = error ?: if (mode == Mode.P3) "Opens the P3 Sim world." else "Solo, in its own world. /rs restarts it."
        text(g, fit(hint, w - 110), x, by + 5, if (error != null) Theme.BAD else Theme.DIM)
    }

    // ---------------------------------------------------------------- rail

    private fun drawRail(g: GuiGraphicsExtractor, mx: Int, my: Int) {
        var y = py + 30
        fun section(label: String) {
            text(g, label, px + 11, y + 2, Theme.DIM)
            y += 13
        }
        section("DUNGEON")
        for (m in Mode.entries.filter { !it.boss }) {
            railItem(g, mx, my, px + 5, y, railW() - 10, m == mode, m.title, m.summary, m.color, Theme.DIM) { pick(m) }
            y += 28
        }
        y += 4
        section("BOSS")
        for (m in Mode.entries.filter { it.boss }) {
            railItem(g, mx, my, px + 5, y, railW() - 10, m == mode, m.title, m.summary, m.color, Theme.DIM) { pick(m) }
            y += 28
        }
    }

    // ---------------------------------------------------------------- options

    private fun floor(): Floor = if (mode == Mode.P5) Floor.M7 else Floor.of(settings.floor)

    private fun floorRow(g: GuiGraphicsExtractor, mx: Int, my: Int, x: Int) {
        val y = optionsY()
        text(g, "Floor", x, y + 3, Theme.TEXT)
        var cx = x + 60
        for (f in Floor.entries) {
            // Wither King is only on M7: F7 stays greyed out there.
            val action = if (mode == Mode.P5 && f == Floor.F7) null else Runnable { settings.floor = f.code }
            cx += choice(g, mx, my, f.code, cx, y, floor() == f, action) + 4
        }
    }

    private fun seedRow(g: GuiGraphicsExtractor, mx: Int, my: Int, x: Int, w: Int) {
        val y = optionsY() + 20
        text(g, "Map", x, y + 3, Theme.TEXT)
        var cx = x + 60
        cx += choice(g, mx, my, "New map each time", cx, y, !settings.fixedSeed) { settings.fixedSeed = false } + 4
        choice(g, mx, my, "Same seed", cx, y, settings.fixedSeed) { settings.fixedSeed = true }

        val box = seedBox ?: return
        val sy = seedRowY()
        box.visible = settings.fixedSeed
        if (!settings.fixedSeed) {
            text(g, fit("Every Start plans a new dungeon. Its seed shows in chat, to come back to it.", w), x, sy + 3, Theme.DIM)
            return
        }
        inputFrame(g, x, sy, 142, box.isFocused, false)
        val last = runCatching { EnginePractice.lastSeed() }.getOrNull() ?: settings.lastSeed
        if (last != null) {
            chip(g, mx, my, "Use last: $last", x + 150, sy + 1, false) {
                settings.seed = last.toString()
                box.value = settings.seed
            }
        }
        text(g, fit("The same seed builds the same layout, rooms, puzzles and secrets.", w), x, sy + 20, Theme.DIM)
    }

    private fun roomPicker(g: GuiGraphicsExtractor, mx: Int, my: Int, x: Int, w: Int, bottom: Int) {
        val doorY = optionsY() + 20
        text(g, "Enter from", x, doorY + 3, Theme.TEXT)
        var cx = x + 60
        for (door in listOf("north", "east", "south", "west", "random")) {
            cx += choice(g, mx, my, door.replaceFirstChar { it.uppercase() }, cx, doorY, settings.door == door) { settings.door = door } + 4
        }

        val sy = roomSearchY()
        inputFrame(g, x, sy, 122, searchBox?.isFocused == true, true)
        settings.room?.let { textRight(g, fit("Room: ${label(it)}", w - 130), x + w, sy + 3, Theme.GOLD) }
        val query = searchBox?.value?.trim()?.lowercase().orEmpty()
        val shown = rooms.filter { query.isEmpty() || label(it).lowercase().contains(query) }

        // The rooms as a grid of choices, three across, scrolled with the wheel.
        val top = sy + 20
        val viewH = bottom - top
        val cols = 3
        val cellW = (w - 6) / cols
        val rowH = 15
        val rows = (shown.size + cols - 1) / cols
        roomMaxScroll = maxOf(0, rows * rowH - viewH)
        roomScroll = roomScroll.coerceIn(0, roomMaxScroll)
        roomView = intArrayOf(x, top, w, viewH)
        if (shown.isEmpty()) {
            text(g, if (rooms.isEmpty()) "No rooms found in the dungeon data." else "No room matches.", x, top + 3, Theme.DIM)
            return
        }
        beginClip(g, x, top, w, viewH)
        val inView = over(mx, my, x, top, w, viewH)
        shown.forEachIndexed { i, name ->
            val rx = x + (i % cols) * cellW
            val ry = top + (i / cols) * rowH - roomScroll
            if (ry + rowH < top || ry > top + viewH) return@forEachIndexed
            val on = settings.room == name
            val hover = inView && over(mx, my, rx, ry, cellW - 4, 13)
            round(g, rx, ry, cellW - 4, 13, if (on) Theme.alpha(Theme.GOLD, 0x30) else if (hover) Theme.ROW_HOVER else Theme.RAIL)
            text(g, fit(label(name), cellW - 14), rx + 5, ry + 3, if (on) Theme.GOLD else if (hover) Theme.TEXT else Theme.MUTED)
            click(rx, ry, cellW - 4, 13) { settings.room = name }
        }
        endClip(g)
        scrollbar(g, x + w + 5, top, viewH, viewH, rows * rowH, roomScroll, roomMaxScroll)
    }

    private fun p3Notes(g: GuiGraphicsExtractor, x: Int, w: Int) {
        var y = optionsY()
        val notes = listOf(
            "Your role, the section to start from, and spots to teleport to are in the P3 Sim's own menu:",
            "Esc > ADS Menu, or /ads, once you're in.",
        )
        for (note in notes) for (line in wrap(note, w)) {
            text(g, line, x, y, Theme.MUTED)
            y += 10
        }
    }

    override fun mouseScrolled(mx: Double, my: Double, scrollX: Double, scrollY: Double): Boolean {
        val v = roomView
        if (mode == Mode.ROOM && v != null && mx >= v[0] && mx < v[0] + v[2] && my >= v[1] && my < v[1] + v[3]) {
            roomScroll = (roomScroll - Math.signum(scrollY).toInt() * 15).coerceIn(0, roomMaxScroll)
            return true
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY)
    }

    // ---------------------------------------------------------------- starting

    /** What Start does now, or null (greyed out) while something it needs isn't picked. */
    private fun startAction(): Runnable? {
        if (mode == Mode.ROOM && settings.room == null) return null
        if ((mode == Mode.FULL_CLEAR || mode == Mode.BLOOD_RUSH) && settings.fixedSeed && PracticeSettings.parsedSeed() == null) return null
        return Runnable { start() }
    }

    private fun start() {
        PracticeSettings.save()
        if (mode == Mode.P3) {
            SimWorld.open()
            return
        }
        val seed = if (settings.fixedSeed) PracticeSettings.parsedSeed() else null
        val req = when (mode) {
            Mode.FULL_CLEAR -> PracticeRequest.FullClear(floor(), seed)
            Mode.ROOM -> PracticeRequest.Room(floor(), settings.room ?: return, settings.door)
            Mode.BLOOD_RUSH -> PracticeRequest.BloodRush(floor(), seed)
            Mode.BLOOD_CAMP -> PracticeRequest.BloodCamp(floor())
            Mode.P1 -> PracticeRequest.Boss(floor(), PracticeRequest.Phase.P1)
            Mode.P2 -> PracticeRequest.Boss(floor(), PracticeRequest.Phase.P2)
            Mode.P4 -> PracticeRequest.Boss(floor(), PracticeRequest.Phase.P4)
            Mode.P5 -> PracticeRequest.Boss(Floor.M7, PracticeRequest.Phase.P5)
            Mode.P3 -> return
        }
        try {
            EnginePractice.start(req)
        } catch (t: Throwable) {
            EngineerClient.logger.warn("[ads] practice didn't start", t)
            error = "Couldn't start: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    override fun onClose() {
        PracticeSettings.save()
        back()
    }

    private companion object {
        fun label(room: String) = room.replace('_', ' ')

        fun describe(mode: Mode) = when (mode) {
            Mode.FULL_CLEAR -> "A whole dungeon from Mort to the boss. Leave the map random, or give a seed to run the same one again."
            Mode.ROOM -> "One room built on its own. Pick the room and the door you come in through; /rs puts it back."
            Mode.BLOOD_RUSH -> "From the entrance to the blood door with the blood key in hand, timed. Wither doors still need their keys."
            Mode.BLOOD_CAMP -> "In the blood room as the Watcher starts the camp, timed until you take the portal."
            Mode.P1 -> "Straight into Maxor. Ends when Storm would start."
            Mode.P2 -> "Straight into Storm. Ends when Goldor would start."
            Mode.P3 -> "The P3 Sim: terminals, devices and lanes with bots or your party, in its own world."
            Mode.P4 -> "Dropped into Necron's platform as P3 ends."
            Mode.P5 -> "Wither King with relics and dragons. Master Mode only."
        }
    }
}
