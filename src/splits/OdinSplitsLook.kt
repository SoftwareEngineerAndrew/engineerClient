package com.engineerclient.splits

import com.engineerclient.EngineerClient
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.ActionSetting
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.DropdownSetting
import com.odtheking.odin.clickgui.settings.impl.HUDSetting
import com.odtheking.odin.clickgui.settings.impl.HudElement
import com.odtheking.odin.clickgui.settings.impl.KeybindSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.clickgui.settings.impl.StringSetting
import com.odtheking.odin.features.ModuleManager
import com.odtheking.odin.features.impl.skyblock.Splits
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.skyblock.Island
import com.odtheking.odin.utils.skyblock.LocationUtils
import com.odtheking.odin.utils.skyblock.SplitsManager
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.Floor
import com.odtheking.odin.utils.skyblock.dungeonSplits
import com.odtheking.odin.utils.skyblock.floor7SplitGroup
import com.odtheking.odin.utils.skyblock.kuudraSplitsGroup
import com.odtheking.odin.utils.skyblock.kuudraT5SplitsGroup
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * The splits HUD: Odin's Splits, drawn in Blade's look with ADM's Pace and Lag, and a switch for
 * every split. Its settings live in Odin's Splits module, so Odin saves them and its HUDs keep their
 * place; `HudElementMixin` asks [render] for what to draw. Which splits there are stays Odin's.
 */
object OdinSplitsLook {

    private enum class PaceFloor { F7, M7 }
    enum class PaceFrom(private val label: String) { RECENT("Recent Runs"), TARGETS("Targets"); override fun toString() = label }

    private val colourByTime = BooleanSetting("Color Based Off Time", true,
        desc = "Floor 7 split times coloured by how fast they were (dark green to black), here and in Sub Splits. Off: each time in its split's own colour. Added by engineerClient.")

    private val showPb = BooleanSetting("Show PB", false,
        desc = "Times that tie or beat your best for that split in gold. Off: they get their colour from the time like any other. Added by engineerClient.")
        .withDependency { colourByTime.value }

    private val shownSplits = DropdownSetting("Shown Splits", desc = "Which splits the HUD shows, each on its own.")

    private fun toggle(name: String, desc: String) = BooleanSetting("Show $name", true, desc = desc).withDependency { shownSplits.value }

    private val showLag = toggle("Lag", "The time lost to lag so far this run (floor 7).")
    private val showPace = toggle("Pace", "The projected finish.")

    /** One switch per split: the clear and floor 7 by ADM's names, F1-F6 bosses and Kuudra by Odin's. */
    private val runToggles = listOf("Open", "Blood", "Portal", "Enter", "Maxor", "Storm", "Terms", "S1", "S2", "S3", "S4", "Goldor", "Necron", "Dragons")
        .associateWith { toggle(it, if (it.startsWith("S") && it.length == 2) "Terminal section ${it[1]}; needs Sub Splits on." else "The $it split.") }
    private val bossToggles by lazy {
        dungeonSplits.subList(1, 7).flatten().map { strip(it.name) }.distinct()
            .associateWith { toggle(if (it == "Cleared") "Boss Cleared" else it, "F1-F6: the $it split.") }
    }
    private val kuudraToggles by lazy {
        (kuudraT5SplitsGroup + kuudraSplitsGroup).map { strip(it.name) }.filter { it != "Total" }.distinct()
            .associateWith { toggle("Kuudra $it", "Kuudra: the $it split.") }
    }

    private fun strip(s: String) = s.replace(Regex("§."), "").trim()

    private fun shown(label: String, place: EngineerLook.Place): Boolean {
        val name = strip(label)
        // Pace and Lag have their own switches in the options; Kuudra has no Enter.
        if (name == "Pace" || name == "Lag") return true
        if (place == EngineerLook.Place.OTHER) return kuudraToggles[name]?.value ?: false
        return (runToggles[name] ?: bossToggles[name])?.value ?: true
    }

    private val enterAfterEntry = BooleanSetting("Enter After Entry", true, desc = "Only show Enter once you are in the boss.")
        .withDependency { shownSplits.value && runToggles.getValue("Enter").value }

    private val pace = DropdownSetting("Pace", desc = "The target times Pace projects the finish from.")

    private val paceFrom = SelectorSetting("Pace From", PaceFrom.RECENT,
        desc = "What Pace counts each split still to come as. Recent Runs: your typical time for it, the median of your last 20 (until 5 are kept, its target), so Pace is where this run will likely finish. Targets: the boxes below (or PBs), which add up to faster than you usually run.")
        .withDependency { pace.value }

    private val paceFloor = SelectorSetting("Pace Targets", PaceFloor.F7,
        desc = "Which floor's targets the boxes below are for. Pace uses the ones for the floor you are on; other floors and Kuudra have no targets, so there Pace is the time so far.")
        .withDependency { pace.value }

    private fun boxes(floor: String, master: Boolean, index: Int) = EngineerLook.targetLabels(master).map { name ->
        StringSetting("$floor $name", "", 12, desc = "How long $name should take on $floor, in seconds (61.5) or minutes (1:01.5). Blank uses your PB for it, shown grayed in the box (on F7 without a PB, its dark green time).", placeholder = "")
            .withDependency { pace.value && paceFloor.value.ordinal == index }
    }

    private val f7 = boxes("F7", false, 0)
    private val m7 = boxes("M7", true, 1)

    private val fillFromPbs = ActionSetting("Fill From PBs", desc = "Fills the targets shown (F7 or M7) with your PB for each split, fixed from now on (blank boxes follow your PBs as they improve). Each is that split's best ever, so together they add up to faster than any run you have done.") {
        fillFromPbs()
    }.withDependency { pace.value }

    /**
     * Adds the settings to Odin's Splits. Odin read its config before any of them existed, so it is
     * read again now to pick up what was saved for them; reading is idempotent (a module is only
     * toggled if its saved state differs), so nothing else changes.
     *
     * Also rearranges Splits' settings: Look first, Current Split HUD last, and no Keybind (taken
     * out of the cache Odin fires keybinds from as well, so a saved key can't toggle Splits).
     */
    fun install() {
        dragonsOnRelics()
        SubSplitGrades.byTime = { colourByTime.value }
        SubSplitGrades.showBest = { showPb.value }
        val toggles = listOf(showPace, showLag) + runToggles.values + bossToggles.values + kuudraToggles.values
        for (s in listOf(colourByTime, showPb, shownSplits) + toggles + listOf(enterAfterEntry, pace, paceFrom, paceFloor) + f7 + m7 + fillFromPbs) Splits.registerSetting(s)
        (Splits.settings.remove("Keybind") as? KeybindSetting)?.let { ModuleManager.keybindSettingsCache.remove(it) }
        // The Show switches replace Odin's own Boss Entry toggle.
        Splits.settings.remove("Boss Entry Split")
        val all = LinkedHashMap(Splits.settings)
        val last = all.remove(CURRENT_SPLIT_HUD)
        Splits.settings.clear()
        all.remove(colourByTime.name)?.let { Splits.settings[colourByTime.name] = it }
        all.remove(showPb.name)?.let { Splits.settings[showPb.name] = it }
        Splits.settings.putAll(all)
        last?.let { Splits.settings[CURRENT_SPLIT_HUD] = it }
        ModuleManager.loadConfigurations()
    }

    private const val CURRENT_SPLIT_HUD = "Current Split HUD"

    // --- Necron's end ----------------------------------------------------------------------------

    /**
     * M7 only. Odin's splits end Necron's split and start "Cleared" (the Dragons) on "[BOSS] Necron:
     * All this, for nothing...", which he stopped saying with Hypixel's boss update of 5 Oct 2026, so
     * the Dragons never started. Odin's floor 7 splits are a public list: its last split is made to
     * start on the Wither King's first line instead, said once every relic is placed - Necron's split
     * runs through the relics, as Sub Splits' does. F7 never says it: Necron runs to the run's end.
     */
    private fun dragonsOnRelics() {
        val i = floor7SplitGroup.indexOfFirst { it.regex.pattern.contains("All this, for nothing") }
        if (i < 0) return
        floor7SplitGroup[i] = floor7SplitGroup[i].copy(regex = Regex(Regex.escape(SplitTracker.RELICS_PLACED)))
    }

    private fun fillFromPbs() {
        val master = paceFloor.value == PaceFloor.M7
        val boxes = if (master) m7 else f7
        var filled = 0
        boxes.forEachIndexed { i, box ->
            val pb = pb(i, master) ?: return@forEachIndexed
            box.value = EngineerLook.formatSeconds(pb.ms / 1000.0)
            filled++
        }
        ModuleManager.saveConfigurations()
        EngineerClient.msg(if (filled == 0) "§7No ${if (master) "M7" else "F7"} PBs yet - finish a run first." else "§aFilled §f$filled §atargets from your ${if (master) "M7" else "F7"} PBs.")
    }

    // --- drawing -----------------------------------------------------------------------------

    private var failed = false

    /**
     * What Odin's [hud] draws instead, if it is one of Splits' two: its size, as Odin's own drawing
     * returns it. Null lets Odin draw it - any other HUD, or (once, logged) an error here.
     */
    @JvmStatic
    fun render(hud: HudElement, g: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int>? {
        if (failed) return null
        val display = (Splits.settings["Splits Display HUD"] as? HUDSetting)?.value
        val current = (Splits.settings[CURRENT_SPLIT_HUD] as? HUDSetting)?.value
        if (hud !== display && hud !== current) return null
        return try {
            if (hud === display) drawDisplay(g, example) else drawCurrent(g, example)
        } catch (t: Throwable) {
            failed = true
            EngineerClient.logger.error("[ec] Splits look failed - back to Odin's look for this session", t)
            null
        }
    }

    private fun bool(name: String, fallback: Boolean) = (Splits.settings[name] as? BooleanSetting)?.value ?: fallback

    private fun options() = EngineerLook.Options(
        bossEntry = true,
        show0 = bool("Show 0 splits", false),
        showTicks = Splits.showTickTime,
        enterAfterEntry = enterAfterEntry.value,
        showPace = showPace.value,
        showLag = showLag.value,
    )

    /** Where the rows are from, and whether it is master mode. */
    private fun place(): Pair<EngineerLook.Place, Boolean> {
        if (LocationUtils.currentArea != Island.Dungeon) return EngineerLook.Place.OTHER to false
        val floor = DungeonUtils.floor ?: return EngineerLook.Place.DUNGEON to false
        val master = floor.name.startsWith("M")
        return (if (floor.floorNumber == 7) EngineerLook.Place.FLOOR7 else EngineerLook.Place.DUNGEON) to master
    }

    /**
     * The targets for the floor, as seconds; null off floor 7. A blank box (or one that isn't a
     * time) follows the Odin PB for that split, live, so it keeps up as the PB improves.
     */
    private fun targets(place: EngineerLook.Place, master: Boolean): List<Double?>? {
        if (place != EngineerLook.Place.FLOOR7) return null
        return (if (master) m7 else f7).indices.map { i ->
            (typical(i, master) ?: target(i, master) ?: darkGreen(i, master))?.let { it.ms / 1000.0 }
        }
    }

    /**
     * Split [i]'s Pace target ([EngineerLook.targetLabels] order) on F7 or M7: its box, else the PB
     * for it, else null.
     */
    private fun target(i: Int, master: Boolean): SplitPace.Clocks? {
        (if (master) m7 else f7).getOrNull(i)?.let { box -> EngineerLook.parseSeconds(box.value)?.let { return secs(it) } }
        return pb(i, master)
    }

    /** [label]'s (SplitTracker's) Pace target for SplitPace: its box, else the PB, else null (its dark green). */
    fun paceTarget(label: String, master: Boolean): SplitPace.Clocks? =
        SplitPace.ORDER.indexOf(label).takeIf { it >= 0 }?.let { typical(it, master) ?: target(it, master) }

    /** With Pace From Recent Runs, split [i]'s typical time (the median of your recent ones), else null. */
    private fun typical(i: Int, master: Boolean): SplitPace.Clocks? {
        if (paceFrom.value != PaceFrom.RECENT) return null
        val label = SplitPace.ALL.getOrNull(i) ?: return null
        return DungeonSplits.typical(if (master) "M7" else "F7", label)
    }

    /**
     * The PB for split [i]: the faster of the best kept by Sub Splits (timed by its own tracker, on
     * the split's own clock) and Odin's PB, each only if it is a real time - not under the split's
     * floor. Odin's F7 PBs can't be taken as they are: its F7 Necron split never ends (it waits for
     * a line Necron no longer says), and old configs can carry a 0.01 s Portal.
     */
    private fun pb(i: Int, master: Boolean): SplitPace.Clocks? {
        val floor = if (master) "M7" else "F7"
        val id = SplitPace.ALL.getOrNull(i)?.let { SplitPace.SPLIT_IDS[it] }
        val ours = id?.let { sid -> DungeonSplits.bestOf(floor, sid)?.takeIf { SubSplitGrades.canBeBest(sid, it, floor) }?.let { SplitPace.clocks(sid, it) } }
        val odin = PB_NAMES.getOrNull(i)?.let { Splits.dungeonPBsList[(if (master) Floor.M7 else Floor.F7).ordinal].get(it) }
            ?.let { secs(it.toDouble()) }
            ?.takeIf { c -> if (id != null) SubSplitGrades.canBeBest(id, SubSplitGrades.value(id, c.ms, c.ticks), floor) else c.ms >= 1000 }
        return listOfNotNull(ours, odin).minByOrNull { it.ms }
    }

    /** F7's dark green for split [i] (SplitPace), what Pace counts it as with no box and no PB; none on M7. */
    private fun darkGreen(i: Int, master: Boolean): SplitPace.Clocks? =
        if (master) null else SplitPace.ORDER.getOrNull(i)?.let { SplitPace.ref(it) }

    private fun secs(s: Double) = SplitPace.Clocks(Math.round(s * 1000), Math.round(s * 20))

    private val placeholderField = runCatching { StringSetting::class.java.getDeclaredField("placeholder").apply { isAccessible = true } }.getOrNull()

    /**
     * Grays each blank target box's stand-in into it: the PB, or (F7 without one) the dark green
     * time. Run as the Click GUI opens, so it is current whenever the boxes show.
     */
    @JvmStatic
    fun refreshPlaceholders() {
        val field = placeholderField ?: return
        for ((master, boxes) in listOf(false to f7, true to m7)) boxes.forEachIndexed { i, box ->
            val text = pb(i, master)?.let { "PB " + EngineerLook.formatSeconds(it.ms / 1000.0) }
                ?: darkGreen(i, master)?.let { EngineerLook.formatSeconds(it.ms / 1000.0) + " (no PB)" } ?: ""
            runCatching { field.set(box, text) }
        }
    }

    /** The F7 Pace target for the Odin split named [name] (seconds; a blank box the PB), null with neither. */
    fun f7Target(name: String): Double? {
        val i = PB_NAMES.indexOf(name).takeIf { it >= 0 } ?: return null
        return targets(EngineerLook.Place.FLOOR7, false)?.getOrNull(i)
    }

    /** Odin keys a PB by the split's name as it has it, colour codes included. */
    private val PB_NAMES by lazy { listOf("§2Blood Open", "§bBlood Clear", "§dPortal Entry") + floor7SplitGroup.map { it.name } }

    /**
     * A floor 7 split's time colour (SubSplitGrades): bands from the recorded F7 runs, gold for a
     * best (kept with the sub splits' bests), on M7 gold only.
     */
    private fun grade(name: String, ms: Long, ticks: Long, over: Boolean, master: Boolean): String? {
        val id = SubSplitGrades.MAIN_SPLITS[name.replace(Regex("§."), "").trim()] ?: return null
        val floor = if (master) "M7" else "F7"
        val value = SubSplitGrades.value(id, ms, ticks)
        DungeonSplits.recordBest(floor, id, value, over)
        return SubSplitGrades.colour(id, value, over, DungeonSplits.bestOf(floor, id), floor, "")
            .takeIf { it.isNotEmpty() }
    }

    private fun rows(): List<EngineerLook.Row> =
        SplitsManager.currentRows().map { EngineerLook.Row(it.name, it.time, it.tickTime, it.isCurrent) }

    /** A floor 7 run part way through Goldor, for the HUD editor. */
    private fun exampleRows(): List<EngineerLook.Row> {
        val names = listOf("§2Blood Open", "§bBlood Clear", "§dPortal Entry") + floor7SplitGroup.map { it.name } + "§1Total"
        val secs = listOf(59.0, 30.1, 4.2, 25.5, 45.9, 35.0, 3.1, 0.0, 0.0, 0.0)
        return names.mapIndexed { i, n -> EngineerLook.Row(n, (secs[i] * 1000).toLong(), (secs[i] * 20).toLong() - (if (secs[i] > 0) 1 else 0), i == 6) }
    }

    private const val LINE = 9

    /** The terminal sections as lines, for after Terms: live from Sub Splits, or examples for the HUD editor. */
    private fun sectionLines(example: Boolean, opts: EngineerLook.Options): List<EngineerLook.Line> {
        val times = if (example) listOf(8_700L to 174L, 7_700L to 154L) else DungeonSplits.termSections()
        return times.mapIndexedNotNull { i, (ms, ticks) ->
            val label = "§6S${i + 1}"
            if (!shown(label, EngineerLook.Place.FLOOR7)) return@mapIndexedNotNull null
            EngineerLook.Line(label, "§6", SplitFormat.seconds(ms), if (opts.showTicks) SplitFormat.seconds(ticks * 50) else null)
        }
    }

    /** Blade's look: the name in its colour on the left, real time in green and server time in gray on the right. */
    private fun drawDisplay(g: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int> {
        val (place, master) = if (example) EngineerLook.Place.FLOOR7 to (paceFloor.value == PaceFloor.M7) else place()
        val rows = if (example) exampleRows() else rows()
        val opts = options()
        val all = EngineerLook.lines(rows, opts, place, master, targets(if (example) EngineerLook.Place.FLOOR7 else place, master),
            if (example || place != EngineerLook.Place.FLOOR7) null else { n, ms, t, over -> grade(n, ms, t, over, master) },
            if (example || place != EngineerLook.Place.FLOOR7 || master) null else DungeonSplits.pace()?.let { it.ms to it.ticks },
            if (example || place != EngineerLook.Place.FLOOR7) null else DungeonSplits.lag())
        val lines = ArrayList<EngineerLook.Line>()
        for (l in all) {
            if (shown(l.label, place)) lines += l
            if (place == EngineerLook.Place.FLOOR7 && strip(l.label) == "Terms") lines += sectionLines(example, opts)
        }
        if (lines.isEmpty()) return 0 to 0
        val font = EngineerClient.mc.font

        fun time(l: EngineerLook.Line) = (if (l.graded) l.colour else "§a") + l.time + (l.ticks?.let { " §8(§7$it§8)" } ?: "")

        // Fixed Width sizes both columns for the longest the run can get, so the HUD never changes width mid-run.
        val fixed = bool("Fixed Width", true)
        val labels = if (fixed) EngineerLook.allLabels(rows, opts, place, master).filter { shown(it, place) } + lines.map { it.label } else lines.map { it.label }
        val nameW = labels.maxOf { font.width(it) }
        val widest = if (opts.showTicks) "§a59m 59.9s §8(§759m 59.9s§8)" else "§a59m 59.9s"
        val timeW = maxOf(if (fixed) font.width(widest) else 0, lines.maxOf { font.width(time(it)) })
        val width = nameW + GAP + timeW
        lines.forEachIndexed { i, l ->
            val y = i * LINE
            g.text(l.label, 0, y, Colors.WHITE)
            val s = time(l)
            g.text(s, width - font.width(s), y, Colors.WHITE)
        }
        return width to lines.size * LINE
    }

    private const val GAP = 8

    /** Odin's Current Split HUD: the running split, centred on the HUD's position as Odin centres its own. */
    private fun drawCurrent(g: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int> {
        val (place, master) = if (example) EngineerLook.Place.FLOOR7 to false else place()
        val line = EngineerLook.currentLine(if (example) exampleRows() else rows(), options(), place, master) ?: return 0 to 0
        val s = EngineerLook.text(line)
        val w = EngineerClient.mc.font.width(s) + 2
        g.text(s, -w / 2, 0, Colors.WHITE)
        return w to LINE
    }
}
