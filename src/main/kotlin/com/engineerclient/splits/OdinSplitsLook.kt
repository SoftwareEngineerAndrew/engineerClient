package com.engineerclient.splits

import com.engineerclient.EngineerClient
import com.odtheking.odin.clickgui.settings.Setting.Companion.withDependency
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
import com.odtheking.odin.utils.skyblock.floor7SplitGroup
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * A "Look" for Odin's own Splits module: Odin's look, or Engineer Splits ([EngineerLook]) - with a
 * Pace line on top, the projected finish from a target time per split.
 *
 * It lives in Odin's Splits settings, not in an engineerClient module, because it is a way of
 * drawing Odin's splits: the settings are registered into Odin's module ([install]), Odin saves and
 * loads them with its own, and its two HUDs keep their place, scale and toggles - only what is drawn
 * inside them changes, through `HudElementMixin` asking [render]. Everything that decides the
 * splits stays Odin's.
 */
object OdinSplitsLook {

    private val look = SelectorSetting("Look", "Odin Splits", listOf("Odin Splits", "Engineer Splits"),
        desc = "Odin Splits, or Engineer Splits: EngineerSplits' lines (Name > time (ticks)) with a Pace line on top, the projected finish from the targets under Pace. Added by engineerClient.")

    /** The Engineer look is picked. */
    val engineer: Boolean get() = look.value == 1

    private val enterAfterEntry = BooleanSetting("Enter After Entry", false, desc = "Engineer Splits: only show the Enter line (Boss Entry) once you are in the boss, not counting up through the clear.")
        .withDependency { engineer && bool("Boss Entry Split", true) }

    private val pace = DropdownSetting("Pace", desc = "The target times Pace projects the finish from.").withDependency { engineer }

    private val paceFloor = SelectorSetting("Pace Targets", "F7", listOf("F7", "M7"),
        desc = "Which floor's targets the boxes below are for. Pace uses the ones for the floor you are on; other floors and Kuudra have no targets, so there Pace is the time so far.")
        .withDependency { engineer && pace.value }

    private fun boxes(floor: String, master: Boolean, index: Int) = EngineerLook.targetLabels(master).map { name ->
        StringSetting("$floor $name", "", 12, desc = "How long $name should take on $floor, in seconds (61.5) or minutes (1:01.5). Blank uses your Odin PB for it (0 without one).")
            .withDependency { engineer && pace.value && paceFloor.value == index }
    }

    private val f7 = boxes("F7", false, 0)
    private val m7 = boxes("M7", true, 1)

    private val fillFromPbs = ActionSetting("Fill From PBs", desc = "Fills the targets shown (F7 or M7) from Odin's personal best for each split. Each is that split's best ever, so together they add up to faster than any run you have done.") {
        fillFromPbs()
    }.withDependency { engineer && pace.value }

    /**
     * Adds the settings to Odin's Splits. Odin read its config before any of them existed, so it is
     * read again now to pick up what was saved for them; reading is idempotent (a module is only
     * toggled if its saved state differs), so nothing else changes.
     *
     * Also rearranges Splits' settings: Look first, Current Split HUD last, and no Keybind (taken
     * out of the cache Odin fires keybinds from as well, so a saved key can't toggle Splits).
     */
    fun install() {
        for (s in listOf(look, enterAfterEntry, pace, paceFloor) + f7 + m7 + fillFromPbs) Splits.registerSetting(s)
        (Splits.settings.remove("Keybind") as? KeybindSetting)?.let { ModuleManager.keybindSettingsCache.remove(it) }
        val all = LinkedHashMap(Splits.settings)
        val last = all.remove(CURRENT_SPLIT_HUD)
        Splits.settings.clear()
        all.remove(look.name)?.let { Splits.settings[look.name] = it }
        Splits.settings.putAll(all)
        last?.let { Splits.settings[CURRENT_SPLIT_HUD] = it }
        ModuleManager.loadConfigurations()
    }

    private const val CURRENT_SPLIT_HUD = "Current Split HUD"

    // --- Necron's end ----------------------------------------------------------------------------

    /**
     * M7 only. Odin's splits end Necron's split (and start "Cleared", the Dragons) on "[BOSS] Necron:
     * All this, for nothing...", which he stopped saying with Hypixel's boss update of 5 Oct 2026.
     * On F7 that is right as it is: there is no end animation any more, so Necron runs to the end of
     * the run. On M7 the Dragons follow him, so at his death (DungeonSplits: the TNT burst he dies
     * in) that line is handed to Odin's SplitsManager as if he had said it.
     * Odin ignores a split's line once that split has its time, so when the line does come (from
     * before the update) whichever is first counts and the other does nothing; off floor 7 no split
     * matches it.
     */
    fun onNecronDead() {
        if (necronEndFailed) return
        try {
            val m = SplitsManager::class.java.getDeclaredMethod("onSplitMessage", String::class.java)
            m.isAccessible = true
            m.invoke(SplitsManager, NECRON_END)
        } catch (t: Throwable) {
            necronEndFailed = true
            EngineerClient.logger.error("[ec] couldn't end Odin's Necron split at his death - it runs to the end of the run", t)
        }
    }

    private var necronEndFailed = false
    private const val NECRON_END = "[BOSS] Necron: All this, for nothing..."

    private fun fillFromPbs() {
        val master = paceFloor.value == 1
        val pbs = Splits.dungeonPBsList[(if (master) Floor.M7 else Floor.F7).ordinal]
        val boxes = if (master) m7 else f7
        var filled = 0
        PB_NAMES.forEachIndexed { i, n ->
            val pb = pbs.get(n) ?: return@forEachIndexed
            boxes.getOrNull(i)?.value = EngineerLook.formatSeconds(pb.toDouble())
            filled++
        }
        ModuleManager.saveConfigurations()
        EngineerClient.msg(if (filled == 0) "§7No ${if (master) "M7" else "F7"} PBs yet - finish a run first." else "§aFilled §f$filled §atargets from your ${if (master) "M7" else "F7"} PBs.")
    }

    // --- drawing -----------------------------------------------------------------------------

    private var failed = false

    /**
     * What Odin's [hud] draws instead, if it is one of Splits' two and the Engineer look is on: its
     * size, as Odin's own drawing returns it. Null lets Odin draw it - any other HUD, the Odin look,
     * or (once, logged) an error here.
     */
    @JvmStatic
    fun render(hud: HudElement, g: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int>? {
        if (!engineer || failed) return null
        val display = (Splits.settings["Splits Display HUD"] as? HUDSetting)?.value
        val current = (Splits.settings[CURRENT_SPLIT_HUD] as? HUDSetting)?.value
        if (hud !== display && hud !== current) return null
        return try {
            if (hud === display) drawDisplay(g, example) else drawCurrent(g, example)
        } catch (t: Throwable) {
            failed = true
            EngineerClient.logger.error("[ec] Engineer Splits look failed - back to Odin's look for this session", t)
            null
        }
    }

    private fun bool(name: String, fallback: Boolean) = (Splits.settings[name] as? BooleanSetting)?.value ?: fallback

    private fun options() = EngineerLook.Options(
        bossEntry = bool("Boss Entry Split", true),
        show0 = bool("Show 0 splits", false),
        showTicks = Splits.showTickTime,
        enterAfterEntry = enterAfterEntry.value,
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
     * time) follows your Odin PB for that split, live, so it keeps up as the PB improves.
     */
    private fun targets(place: EngineerLook.Place, master: Boolean): List<Double?>? {
        if (place != EngineerLook.Place.FLOOR7) return null
        val pbs = Splits.dungeonPBsList[(if (master) Floor.M7 else Floor.F7).ordinal]
        return (if (master) m7 else f7).mapIndexed { i, box ->
            EngineerLook.parseSeconds(box.value) ?: PB_NAMES.getOrNull(i)?.let { pbs.get(it)?.toDouble() }
        }
    }

    /** Your F7 Pace target for the Odin split named [name] (seconds; a blank box your PB), null with neither. */
    fun f7Target(name: String): Double? {
        val i = PB_NAMES.indexOf(name).takeIf { it >= 0 } ?: return null
        return targets(EngineerLook.Place.FLOOR7, false)?.getOrNull(i)
    }

    /** Odin keys a PB by the split's name as it has it, colour codes included. */
    private val PB_NAMES by lazy { listOf("§2Blood Open", "§bBlood Clear", "§dPortal Entry") + floor7SplitGroup.map { it.name } }

    /**
     * A floor 7 split's time colour (SubSplitGrades): bands from the recorded F7 runs, gold for your
     * best (kept with the sub splits' bests), on M7 gold only.
     */
    private fun grade(name: String, ms: Long, ticks: Long, over: Boolean, master: Boolean): String? {
        val id = SubSplitGrades.MAIN_SPLITS[name.replace(Regex("§."), "").trim()] ?: return null
        val floor = if (master) "M7" else "F7"
        val value = SubSplitGrades.value(id, ms, ticks)
        DungeonSplits.recordBest(floor, id, value, over)
        return SubSplitGrades.colour(id, value, over, DungeonSplits.bestOf(floor, id), !master, "")
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

    private fun drawDisplay(g: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int> {
        val (place, master) = if (example) EngineerLook.Place.FLOOR7 to (paceFloor.value == 1) else place()
        val rows = if (example) exampleRows() else rows()
        val opts = options()
        val lines = EngineerLook.lines(rows, opts, place, master, targets(if (example) EngineerLook.Place.FLOOR7 else place, master),
            if (example || place != EngineerLook.Place.FLOOR7) null else { n, ms, t, over -> grade(n, ms, t, over, master) },
            if (example || place != EngineerLook.Place.FLOOR7 || master) null else DungeonSplits.pace()?.let { it.ms to it.ticks },
            if (example || place != EngineerLook.Place.FLOOR7) null else DungeonSplits.lag())
        if (lines.isEmpty()) return 0 to 0
        val font = EngineerClient.mc.font

        if (!bool("Fixed Width", true)) {
            var width = 0
            lines.forEachIndexed { i, l ->
                val s = EngineerLook.text(l)
                g.text(s, 0, i * LINE, Colors.WHITE)
                width = maxOf(width, font.width(s))
            }
            return width to lines.size * LINE
        }

        // Fixed Width: the arrows in one column - each name pushed right up against its arrow, each
        // time (and its tick time) straight after it. The name column is as wide as the widest name
        // the run can have and the rest as wide as the longest time can get, so the HUD never
        // changes width mid-run - what Odin's Fixed Width does for its own look.
        val nameW = EngineerLook.allLabels(rows, opts, place, master).maxOf { font.width(it) }
        val arrow = " §b> "
        val arrowW = font.width(arrow)
        val widest = if (opts.showTicks) "59m 59.9s §8(§759m 59.9s§8)" else "59m 59.9s"
        val restW = maxOf(font.width(widest), lines.maxOf { font.width(it.colour + it.time + (it.ticks?.let { t -> " §8(§7$t§8)" } ?: "")) })
        lines.forEachIndexed { i, l ->
            val y = i * LINE
            g.text(l.label, nameW - font.width(l.label), y, Colors.WHITE)
            g.text(arrow, nameW, y, Colors.WHITE)
            g.text(l.colour + l.time + (l.ticks?.let { " §8(§7$it§8)" } ?: ""), nameW + arrowW, y, Colors.WHITE)
        }
        return nameW + arrowW + restW to lines.size * LINE
    }

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
