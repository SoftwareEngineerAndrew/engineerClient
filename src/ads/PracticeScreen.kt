package com.engineerclient.ads

import com.engineerclient.EngineerClient
import com.engineerclient.p3sim.SimWorld
import com.engineerclient.p3sim.clear.ui.LayoutBrowserModel
import com.engineerclient.p3sim.clear.ui.LayoutBrowserScreen
import com.engineerclient.p3sim.clear.ui.RunStarter
import com.engineerclient.p3sim.clear.ui.UiModule
import com.engineerclient.p3sim.clear.ui.UiSettings
import com.engineerclient.p3sim.run.LagPreset
import com.engineerclient.p3sim.run.LayoutSource
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
 *
 * Full clear has two dungeons to pick from: Engineer Client's recorded one (its full run: recorded layouts, bots, lag,
 * started through [RunStarter]) and Rustclear's engine (F7 and M7, seeded maps).
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
    /** The recorded run's seed, while its layout is "Seed". */
    private var runSeedBox: EditBox? = null
    /** What the option under the mouse does, shown in the footer this frame. */
    private var hover: String? = null
    /** The recorded run's choices besides the layout (party, lag, rules), kept in the full run's settings. */
    private var run = UiSettings.defaults()
    private var searchBox: EditBox? = null
    private val rooms: List<String> by lazy { runCatching { EnginePractice.roomNames() }.getOrDefault(emptyList()) }
    private var roomScroll = 0
    private var roomMaxScroll = 0
    /** The room list's window in the last frame (x, y, w, h), for the mouse wheel. */
    private var roomView: IntArray? = null
    /** Why the last Start didn't open anything, shown in the footer until the next one. */
    private var error: String? = null
    /** The recorded run's starter's last line (a missing data pack, the run it is building), in the footer. */
    private var status: String? = null

    override fun init() {
        layoutPanel(Int.MAX_VALUE, Int.MAX_VALUE)
        seedBox = null
        runSeedBox = null
        searchBox = null
        val x = contentX()
        if (recordedClear()) {
            if (settings.runLayout == "seed") runSeedBox = input(x + 66, layoutRowY() + 20 + 3, 110, "Seed", 18).also { box ->
                box.value = settings.runSeed
                box.setResponder { settings.runSeed = it }
                addRenderableWidget(box)
            }
        } else if (mode == Mode.FULL_CLEAR || mode == Mode.BLOOD_RUSH) {
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
    /** Where the floor row is: under Full clear's Engine row. */
    private fun floorY() = optionsY() + if (mode == Mode.FULL_CLEAR) 20 else 0
    private fun seedRowY() = floorY() + 40
    private fun layoutRowY() = floorY() + 20
    /** Full clear on Engineer Client's recorded dungeon (its full run), not Rustclear's engine. */
    private fun recordedClear() = mode == Mode.FULL_CLEAR && settings.clearEngine != "engine"
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
        hover = null
        when (mode) {
            Mode.FULL_CLEAR -> {
                engineRow(g, mx, my, x)
                floorRow(g, mx, my, x)
                if (recordedClear()) recordedRows(g, mx, my, x, w) else seedRow(g, mx, my, x, w)
            }
            Mode.BLOOD_RUSH -> { floorRow(g, mx, my, x); seedRow(g, mx, my, x, w) }
            Mode.ROOM -> { floorRow(g, mx, my, x); roomPicker(g, mx, my, x, w, by - 8) }
            Mode.P3 -> p3Notes(g, x, w)
            else -> floorRow(g, mx, my, x)
        }

        rect(g, x, by - 5, w, 1, Theme.PANEL_EDGE)
        val back = button(g, mx, my, "Back", px + pw - 8, by, Style.SECONDARY) { onClose() }
        button(g, mx, my, "Start", back - 4, by, Style.PRIMARY, startAction())
        val hint = error ?: hover ?: status ?: when {
            mode == Mode.P3 -> "Opens the P3 Sim world."
            recordedClear() -> "Builds the dungeon in a fresh world. Esc > ADS Menu restarts the room or the run."
            else -> "Solo, in its own world. /rs restarts it."
        }
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

    private fun floor(): Floor = if (mode == Mode.P5) Floor.M7 else if (recordedClear()) Floor.F7 else Floor.of(settings.floor)

    private fun floorRow(g: GuiGraphicsExtractor, mx: Int, my: Int, x: Int) {
        val y = floorY()
        text(g, "Floor", x, y + 3, Theme.TEXT)
        var cx = x + 60
        for (f in Floor.entries) {
            // Wither King is only on M7, and the recorded dungeon only has F7: the other stays greyed out.
            val action = if ((mode == Mode.P5 && f == Floor.F7) || (recordedClear() && f == Floor.M7)) null else Runnable { settings.floor = f.code }
            cx += option(g, mx, my, f.code, cx, y, floor() == f, if (recordedClear() && f == Floor.M7) "The recorded dungeon is F7 only; Rustclear's engine has M7." else null, action) + 4
        }
    }

    /** A [choice] whose [about] shows in the footer while the mouse is on it. */
    private fun option(g: GuiGraphicsExtractor, mx: Int, my: Int, label: String, x: Int, y: Int, on: Boolean, about: String?, action: Runnable?): Int {
        if (about != null && over(mx, my, x, y, choiceWidth(label), 13)) hover = about
        return choice(g, mx, my, label, x, y, on, action)
    }

    private fun engineRow(g: GuiGraphicsExtractor, mx: Int, my: Int, x: Int) {
        val y = optionsY()
        text(g, "Engine", x, y + 3, Theme.TEXT)
        var cx = x + 60
        fun pick(engine: String) = Runnable { if (settings.clearEngine != engine) { settings.clearEngine = engine; error = null; rebuildWidgets() } }
        cx += option(g, mx, my, "Engineer Client", cx, y, recordedClear(),
            "Recorded Hypixel layouts with bot teammates, server lag and the run's rules. F7.", pick("recorded")) + 4
        option(g, mx, my, "Rustclear", cx, y, !recordedClear(),
            "Rustclear's dungeon engine: a planned map from a seed, F7 or M7, solo.", pick("engine"))
    }

    /** Engineer Client's full run: the layout, the party, the lag and the rules. */
    private fun recordedRows(g: GuiGraphicsExtractor, mx: Int, my: Int, x: Int, w: Int) {
        val c = x + 60
        var y = layoutRowY()
        text(g, "Layout", x, y + 3, Theme.TEXT)
        var cx = c
        for ((key, label, about) in listOf(
            Triple("random", "Random", "A random recorded layout. Its seed is shown when the run starts, to repeat it."),
            Triple("seed", "Seed", "The layout a seed number picks: the same seed gives the same layout and the same run."),
            Triple("recorded", "Recorded", "A layout from a recorded run, chosen in the browser by its rooms, puzzles and time."),
        )) cx += option(g, mx, my, label, cx, y, settings.runLayout == key, about, Runnable {
            if (settings.runLayout != key) { settings.runLayout = key; error = null; rebuildWidgets() }
        }) + 4

        y += 20
        when (settings.runLayout) {
            "seed" -> {
                val box = runSeedBox
                if (box != null) inputFrame(g, c, y, 122, box.isFocused, false)
                option(g, mx, my, "New", c + 128, y + 1, false, "A fresh random seed.", Runnable {
                    settings.runSeed = UiModule.drawSeed().toString()
                    runSeedBox?.value = settings.runSeed
                })
            }
            "recorded" -> {
                val id = settings.runLayoutId
                val label = if (id == null) "Choose a layout..." else LayoutBrowserModel.title(id)
                option(g, mx, my, fit(label, w - 70), c, y, id != null, "Opens the browser of recorded layouts.", Runnable {
                    mc().gui.setScreen(LayoutBrowserScreen(this, { settings.runLayoutId }) { chosen ->
                        settings.runLayoutId = chosen
                        PracticeSettings.save()
                    })
                })
            }
            else -> text(g, fit("Each Start picks a recorded layout at random.", w - 60), c, y + 3, Theme.DIM)
        }

        y += 24
        text(g, "Party", x, y + 3, Theme.TEXT)
        cx = c
        val size = if (run.bots) run.partySize else 1
        for (n in 1..5) cx += option(g, mx, my, "$n", cx, y, size == n,
            if (n == 1) "You alone: no bots, the party list is just you." else "$n players: you and ${n - 1} bot${if (n > 2) "s" else ""} (the classes nobody plays).",
            Runnable { store(run.copy(partySize = n, bots = n > 1)) }) + 4
        option(g, mx, my, "Bots", cx + 6, y, run.bots, "Teammate bots walk the dungeon and check off their rooms on the measured schedule. Off is a party of 1.",
            Runnable { val on = !run.bots; store(run.copy(bots = on, partySize = if (on && run.partySize < 2) 5 else run.partySize)) })

        y += 20
        text(g, "Lag", x, y + 3, Theme.TEXT)
        cx = c
        for (p in LagPreset.entries) cx += option(g, mx, my, p.label, cx, y, run.lag == p,
            if (p == LagPreset.OFF) "No lag: the server never stalls." else "${p.label}: about ${p.lossPerMinute} ticks lost a minute, as the server stalls and catches up.${if (p == LagPreset.HIGH_SLOT) " An instance slot above 200." else ""}",
            Runnable { store(run.copy(lag = p)) }) + 4

        y += 20
        text(g, "Rules", x, y + 3, Theme.TEXT)
        cx = c
        cx += option(g, mx, my, "No deaths", cx, y, run.noDeaths, "You can't die in the clear: your health stops at its last point instead.",
            Runnable { store(run.copy(noDeaths = !run.noDeaths)) }) + 4
        option(g, mx, my, "Mob HP per run", cx, y, run.mobHpFromPack, "On: each run draws its mob HP scale from the recorded per-run table. Off: every mob has its base HP.",
            Runnable { store(run.copy(mobHpFromPack = !run.mobHpFromPack)) })
    }

    private fun store(d: com.engineerclient.p3sim.clear.ui.RunDefaults) {
        run = d
        UiSettings.store(d)
        FullRunSettings.save()
    }

    private fun mc() = net.minecraft.client.Minecraft.getInstance()

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
            "Esc > ADS Menu, or /ads menu, once you're in.",
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
        if (recordedClear()) return when (settings.runLayout) {
            "seed" -> if (settings.runSeed.trim().toLongOrNull() == null) null else Runnable { start() }
            "recorded" -> if (settings.runLayoutId == null) null else Runnable { start() }
            else -> Runnable { start() }
        }
        if ((mode == Mode.FULL_CLEAR || mode == Mode.BLOOD_RUSH) && settings.fixedSeed && PracticeSettings.parsedSeed() == null) return null
        return Runnable { start() }
    }

    private fun start() {
        PracticeSettings.save()
        if (mode == Mode.P3) {
            SimWorld.open()
            return
        }
        if (recordedClear()) { startRecorded(); return }
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

    /** Engineer Client's full run, through its own starter (the data packs, the layout pick, then a fresh world). */
    private fun startRecorded() {
        val layout = when (settings.runLayout) {
            "seed" -> LayoutSource.Seed(settings.runSeed.trim().toLongOrNull() ?: return)
            "recorded" -> LayoutSource.Recorded(settings.runLayoutId ?: return)
            else -> LayoutSource.Random
        }
        val seed = if (layout is LayoutSource.Seed) layout.seed else UiModule.drawSeed()
        error = null
        status = null
        // Its lines go to chat; a refusal (no data pack, an unknown layout) also shows in the footer, as this screen stays open.
        RunStarter.start(run.config(layout, seed)) { line ->
            EngineerClient.msg("§7$line")
            mc().execute { if (mc().gui.screen() === this) status = line }
        }
    }

    override fun onClose() {
        PracticeSettings.save()
        back()
    }

    companion object {
        /** Opens the page on Full clear with Engineer Client's recorded dungeon (`/ads run start`), over whatever screen is up. */
        fun openRecordedClear() {
            val mc = net.minecraft.client.Minecraft.getInstance()
            PracticeSettings.data.mode = Mode.FULL_CLEAR.name
            PracticeSettings.data.clearEngine = "recorded"
            mc.execute { mc.gui.setScreen(PracticeScreen(mc.gui.screen() ?: net.minecraft.client.gui.screens.TitleScreen())) }
        }

        private fun label(room: String) = room.replace('_', ' ')

        private fun describe(mode: Mode) = when (mode) {
            Mode.FULL_CLEAR -> "A whole dungeon from Mort to the boss: Engineer Client's recorded layouts with bots and lag, or Rustclear's engine with a seed."
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
