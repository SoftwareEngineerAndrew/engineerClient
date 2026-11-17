package com.engineerclient.terminals

import com.engineerclient.party.TermProgress
import com.engineerclient.practice.TerminalHitboxes
import com.mojang.blaze3d.platform.InputConstants
import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.*
import com.adm.features.Category
import com.adm.features.Module
import com.adm.utils.Color
import com.adm.utils.Color.Companion.darker
import com.adm.utils.Color.Companion.withAlpha
import com.adm.utils.Colors
import com.adm.utils.PersonalBest
import com.adm.utils.createSoundSettings
import com.adm.utils.modMessage
import net.minecraft.world.inventory.Slot

/** Every P3 terminal feature, one section each; the logic lives in the section objects ([TermSolver], [TermTimes], ...). */
object Terminals : Module(
    name = "Terminals",
    key = null,
    category = Category.custom("Engineer Client", 860, 10),
    description = "Floor 7 terminals: solver, times, party progress, sounds, status, titles, hitboxes and the simulator.",
    toggled = true,
) {
    // ------------------------------------------------------------------ Solver
    private val solverDrop by DropdownSetting("Solver", true, desc = "The terminal solver.")
    val solver by BooleanSetting("Enable Solver", true, desc = "Shows what to click and only lets the right clicks through.").withDependency { solverDrop }
    val style by SelectorSetting("Solver Style", Style.CUSTOM, desc = "Custom draws its own grid; Vanilla marks the slots in the normal window.").withDependency { solverDrop }
    val customSize by NumberSetting("Custom Size", 2f, 1.0..4.0, 0.1, desc = "Size of the custom grid.").withDependency { solverDrop && style == Style.CUSTOM }
    val melodySize by NumberSetting("Melody Size", 1.5f, 1.0..4.0, 0.1, desc = "Size of the custom melody grid.").withDependency { solverDrop && style == Style.CUSTOM }
    val gap by NumberSetting("Slot Gap", 2, 0..8, 1, desc = "Gap between custom grid slots.").withDependency { solverDrop && style == Style.CUSTOM }
    val roundness by NumberSetting("Roundness", 4f, 0.0..12.0, 1.0, desc = "Corner roundness of the custom grid.").withDependency { solverDrop && style == Style.CUSTOM }
    val instantGui by BooleanSetting("Instant GUI", false, desc = "Draws the grid as a terminal opens and fills in each slot the moment Hypixel sends it.").withDependency { solverDrop && style == Style.CUSTOM }
    val vanillaScale by NumberSetting("Vanilla Scale", 3, 1..6, 1, desc = "GUI scale while a terminal is open (6 is auto).").withDependency { solverDrop && style == Style.VANILLA }
    val hideItems by BooleanSetting("Hide Items", true, desc = "Vanilla: only the slots to click are drawn.").withDependency { solverDrop && style == Style.VANILLA }
    val rubixMode by SelectorSetting("Rubix Mode", RubixMode.FEWEST_CLICKS, desc = "Fewest Clicks and Least Movement mix in right clicks; Least Movement marks the next pane on the shortest mouse path.").withDependency { solverDrop }
    val melodySolver by BooleanSetting("Melody Solver", true, desc = "Solves melody too.").withDependency { solverDrop }
    val numberText by BooleanSetting("Number Text", true, desc = "Numbers: each pane's number on it.").withDependency { solverDrop }
    private val hideNumbers by BooleanSetting("Hide Numbers' Numbers", false, desc = "Numbers: hides the 1-10, leaving only the order colours (/termsim too).").withDependency { solverDrop }
    private val numbersShown by NumberSetting("Show Next Numbers", 0, 0..10, 1, desc = "The next this many panes still show their number (0: none).").withDependency { solverDrop && hideNumbers }
    val clickLimit by BooleanSetting("Click Limit", true, desc = "Hypixel takes 5 terminal clicks per 500 ms; one it would drop isn't sent.").withDependency { solverDrop }
    val resyncTimeout by NumberSetting("Resync Timeout", 0, 0..1000, 25, desc = "A click Hypixel hasn't answered after this long is undone (0: from your ping).", unit = "ms").withDependency { solverDrop }

    private val colorsDrop by DropdownSetting("Solver Colors", false, desc = "Solver colors.").withDependency { solverDrop }
    private fun colors() = solverDrop && colorsDrop
    val backgroundColor by ColorSetting("Background", Colors.gray26.withAlpha(0.5f), true, desc = "Behind the grid.").withDependency { colors() }
    val panesColor by ColorSetting("Panes", Colors.MINECRAFT_GREEN, true, desc = "Correct all the panes.").withDependency { colors() }
    val selectColor by ColorSetting("Select", Colors.MINECRAFT_GREEN, true, desc = "Select all the items.").withDependency { colors() }
    val startsWithColor by ColorSetting("Starts With", Colors.MINECRAFT_GREEN, true, desc = "What starts with.").withDependency { colors() }
    val rubixText by ColorSetting("Rubix Text", Colors.WHITE, true, desc = "Rubix click counts.").withDependency { colors() }
    val rubix1 by ColorSetting("Rubix 1", Colors.MINECRAFT_GREEN, true, desc = "One left click.").withDependency { colors() }
    val rubix2 by ColorSetting("Rubix 2", Colors.MINECRAFT_GREEN.darker(0.5f), true, desc = "Two left clicks.").withDependency { colors() }
    val rubixMinus1 by ColorSetting("Rubix -1", Colors.MINECRAFT_DARK_RED, true, desc = "One right click.").withDependency { colors() }
    val rubixMinus2 by ColorSetting("Rubix -2", Colors.MINECRAFT_DARK_RED.darker(0.5f), true, desc = "Two right clicks.").withDependency { colors() }
    val numbersText by ColorSetting("Numbers Text", Colors.WHITE, true, desc = "Numbers' numbers.").withDependency { colors() }
    val numbers1 by ColorSetting("Numbers 1", Colors.MINECRAFT_GREEN, true, desc = "The next pane.").withDependency { colors() }
    val numbers2 by ColorSetting("Numbers 2", Colors.MINECRAFT_GREEN.darker(0.5f), true, desc = "The one after.").withDependency { colors() }
    val numbers3 by ColorSetting("Numbers 3", Colors.MINECRAFT_GREEN.darker(0.5f).darker(0.5f), true, desc = "Third.").withDependency { colors() }
    val numbers4 by ColorSetting("Numbers 4", Colors.MINECRAFT_GREEN.darker(0.5f).darker(0.5f).darker(0.5f), true, desc = "Fourth.").withDependency { colors() }
    val melodyColumn by ColorSetting("Melody Column", Colors.MINECRAFT_DARK_PURPLE, true, desc = "The marks above and below the column to click in.").withDependency { colors() }
    val melodyPointer by ColorSetting("Melody Pointer", Colors.MINECRAFT_GREEN, true, desc = "The moving pane, and the button when it lines up.").withDependency { colors() }
    val melodyBackground by ColorSetting("Melody Background", Colors.gray38, true, desc = "The lanes and the buttons.").withDependency { colors() }

    // ------------------------------------------------------------------ Times
    private val timesDrop by DropdownSetting("Times", false, desc = "Terminal times and section splits.")
    val times by BooleanSetting("Enable Times", true, desc = "Times terminals and sections.").withDependency { timesDrop }
    val chatTimes by BooleanSetting("Chat Times", true, desc = "Each terminal's time and PB in chat.").withDependency { timesDrop }
    val sectionSplits by BooleanSetting("Section Splits", true, desc = "Section times on the completion lines, and every section's when terms end.").withDependency { timesDrop }
    val realTime by BooleanSetting("Real Time", true, desc = "Real time instead of server ticks.").withDependency { timesDrop }
    val twoDecimals by BooleanSetting("Two Decimals", false, desc = "Times as 5.12s, not 5.123s (PBs stay exact).").withDependency { timesDrop }
    private val resetPbs by ActionSetting("Reset PBs", desc = "Resets the terminal PBs.") {
        terminalPBs.reset()
        modMessage("§6Terminal PBs §fhave been reset.")
    }.withDependency { timesDrop }

    // ------------------------------------------------------------------ Progress
    private val progressDrop by DropdownSetting("Progress", false, desc = "Teammates' terminals and Simon Says as they do them.")
    val progress by BooleanSetting("Enable Progress", true, desc = "Shows teammates' terminal and Simon Says progress (shared over the party link).").withDependency { progressDrop }
    val progressLabels by BooleanSetting("Terminal Labels", true, desc = "Over each terminal a teammate has open: who, and the countdown.").withDependency { progressDrop }
    val progressLabelSize by NumberSetting("Label Size", 1.5f, 0.5..4.0, 0.1f, desc = "Size of the terminal labels.").withDependency { progressDrop }
    val progressChatMelody by BooleanSetting("Chat Melody", true, desc = "Teammates who only post melody progress in party chat get a row too.").withDependency { progressDrop }
    val progressCountdown by BooleanSetting("Countdown", true, desc = "A countdown to when each should be done, from ADR history.").withDependency { progressDrop }
    private val termProgressHud by HUD("Terminal Progress", "Each teammate on a terminal: name, progress and countdown.", true, 10, 200, 1f) { TermProgress.drawTerms(this, it) }
    private val ssProgressHud by HUD("Simon Says Progress", "Who's on Simon Says, how far and the countdown.", true, 10, 250, 1f) { TermProgress.drawSs(this, it) }

    // ------------------------------------------------------------------ Sounds
    private val soundsDrop by DropdownSetting("Sounds", false, desc = "Terminal sounds.")
    val sounds by BooleanSetting("Enable Sounds", false, desc = "Replaces terminal sounds.").withDependency { soundsDrop }
    val clickSounds by BooleanSetting("Click Sounds", true, desc = "Your own sound for every correct click.").withDependency { soundsDrop }
    val clickSound = createSoundSettings("Click Sound", "entity.blaze.hurt") { soundsDrop && clickSounds }
    val completeSounds by BooleanSetting("Complete Sounds", false, desc = "A sound when you finish a terminal.").withDependency { soundsDrop }
    val completeSound = createSoundSettings("Completion Sound", "entity.experience_orb.pickup") { soundsDrop && completeSounds }
    val melodyAlert by BooleanSetting("Melody Click Alert", false, desc = "A sound while melody's pane lines up: row 1 slot 1, and each row one slot right of the last.").withDependency { soundsDrop }
    val melodyVignette by BooleanSetting("Melody Alert Vignette", true, desc = "The screen's edges glow while the alert plays.").withDependency { soundsDrop && melodyAlert }
    val melodyVignetteColor by ColorSetting("Melody Vignette Color", Colors.MINECRAFT_GREEN.withAlpha(0.55f), true, desc = "The glow's color and strength.").withDependency { soundsDrop && melodyAlert && melodyVignette }
    val melodyRepeat by NumberSetting("Melody Alert Repeat", 2, 1..10, 1, desc = "While it can be clicked, the sound plays again this often.", unit = " ticks").withDependency { soundsDrop && melodyAlert }
    val melodySound = createSoundSettings("Melody Alert Sound", "block.note_block.pling") { soundsDrop && melodyAlert }

    // ------------------------------------------------------------------ Status
    private val statusDrop by DropdownSetting("Status", false, desc = "Inactive terminals, devices and levers, and section progress.")
    val status by BooleanSetting("Enable Status", false, desc = "Marks what's left in P3 and counts each section.").withDependency { statusDrop }
    private val termInfoHud by HUD("Term Info", "The section's terms, levers, device and gate.", true, 10, 10, 1f) { TermStatus.drawInfo(this, it) }
    private val currentSectionHud by HUD("Current Section", "The terminal section you're on.", true, 900, 520, 1f) { TermStatus.drawSection(this, it) }
    private val sectionProgressHud by HUD("Section Progress", "The current section's tasks done.", true, 420, 180, 3.6f) { TermStatus.drawProgress(this, it) }
    val colorProgress by BooleanSetting("Color Progress", true, desc = "Section progress goes red, yellow, green, gold as it fills.").withDependency { statusDrop }
    val waypoints by BooleanSetting("Waypoints", true, desc = "Marks inactive terminals, devices and levers in the world.").withDependency { statusDrop }
    val waypointsFor by SelectorSetting("Waypoints For", TermStatus.Show.All, desc = "Which inactive ones to mark.").withDependency { statusDrop && waypoints }
    val waypointStyle by SelectorSetting("Waypoint Style", TermStatus.Look.Full, desc = "How they're drawn.").withDependency { statusDrop && waypoints }
    val waypointColor by ColorSetting("Waypoint Color", Colors.MINECRAFT_YELLOW, true, desc = "Waypoint color.").withDependency { statusDrop && waypoints }
    val waypointsThroughWalls by BooleanSetting("Waypoints Through Walls", true, desc = "Waypoints show through walls.").withDependency { statusDrop && waypoints }
    val hideDefaultNames by BooleanSetting("Hide Default Names", true, desc = "Hides Hypixel's names over inactive ones.").withDependency { statusDrop }

    // ------------------------------------------------------------------ Titles
    private val titlesDrop by DropdownSetting("Titles", false, desc = "Custom completion titles.")
    val titles by BooleanSetting("Enable Titles", false, desc = "Replaces Hypixel's terminal, lever and device titles.").withDependency { titlesDrop }
    val selfOnly by BooleanSetting("Self Only", false, desc = "Only your own.").withDependency { titlesDrop }
    val useSubtitle by BooleanSetting("Use Subtitle", true, desc = "As a subtitle instead of a title.").withDependency { titlesDrop }
    val replaceTerminal by BooleanSetting("Replace Terminal", true, desc = "Terminal titles.").withDependency { titlesDrop }
    val replaceLever by BooleanSetting("Replace Lever", true, desc = "Lever titles.").withDependency { titlesDrop }
    val replaceDevice by BooleanSetting("Replace Device", true, desc = "Device titles.").withDependency { titlesDrop }
    val customTitle by StringSetting("Custom Title", "&6{name} &8[&c{current}&8/&a{total}&8]", 128,
        "{name}, {action}, {type}, {current}, {total}; & color codes.", placeholder = "&6{name} &8[&c{current}&8/&a{total}&8]").withDependency { titlesDrop }

    // ------------------------------------------------------------------ Hitboxes
    private val hitboxesDrop by DropdownSetting("Hitboxes", false, desc = "Each terminal's clickable box.")
    val hitboxes by BooleanSetting("Enable Hitboxes", true, desc = "Outlines each P3 terminal's clickable box while terminals run.").withDependency { hitboxesDrop }
    val aimColor by ColorSetting("Section Aiming", Color(0, 255, 0, 1f), true, desc = "This section's, crosshair on it.").withDependency { hitboxesDrop }
    val readyColor by ColorSetting("Section In Range", Color(0, 140, 0, 1f), true, desc = "This section's, in reach.").withDependency { hitboxesDrop }
    val farColor by ColorSetting("Section Out Of Range", Color(255, 255, 0, 1f), true, desc = "This section's, out of reach.").withDependency { hitboxesDrop }
    val idleAimColor by ColorSetting("Other Section Aiming", Color(255, 0, 0, 1f), true, desc = "Another section's, crosshair on it.").withDependency { hitboxesDrop }
    val idleNearColor by ColorSetting("Other Section In Range", Color(140, 0, 0, 1f), true, desc = "Another section's, in reach.").withDependency { hitboxesDrop }
    val idleColor by ColorSetting("Other Section Out Of Range", Color(255, 128, 0, 1f), true, desc = "Another section's, out of reach.").withDependency { hitboxesDrop }
    val hitboxFill by BooleanSetting("Terminal Fill", false, desc = "Fills the boxes faintly.").withDependency { hitboxesDrop }
    val hitboxWalls by BooleanSetting("Terminals Through Walls", true, desc = "Drawn over blocks in the way.").withDependency { hitboxesDrop }

    // ------------------------------------------------------------------ Simulator
    private val simDrop by DropdownSetting("Simulator", false, desc = "The terminal simulator (/termsim).")
    val simPing by NumberSetting("Simulator Ping", 0, 0..500, 10, desc = "Delay before the simulator answers a click.", unit = "ms").withDependency { simDrop }
    val hoverTerms by BooleanSetting("Hover Terms", false, desc = "Hovering the slot to click clicks it (simulator only).").withDependency { simDrop }
    private val openSim by ActionSetting("Open Simulator", desc = "Opens the simulator.") { TermSimulator.open() }.withDependency { simDrop }
    private val simKey by KeybindSetting("Simulator Key", InputConstants.UNKNOWN, "Opens the simulator.").onPress { TermSimulator.open() }.withDependency { simDrop }

    val terminalPBs = PersonalBest(this, "TerminalPBs")
    val termSimPBs = PersonalBest(this, "TermsimPBs")

    val solverOn get() = enabled && solver
    val timesOn get() = enabled && times
    val progressOn get() = enabled && progress
    val soundsOn get() = enabled && sounds
    val statusOn get() = enabled && status
    val titlesOn get() = enabled && titles
    val hitboxesOn get() = enabled && hitboxes

    @JvmStatic
    fun hidesTerminalNumbers(): Boolean = enabled && hideNumbers

    /** Only the terminal's own slots: the inventory below keeps its stack sizes. */
    @JvmStatic
    fun hidesTerminalNumber(slot: Slot): Boolean {
        if (!hidesTerminalNumbers()) return false
        val term = TerminalUtils.currentTerm ?: return false
        return term.type == TerminalTypes.NUMBERS && slot.index < term.type.windowSize && !showsTerminalNumber(slot.index, term.solution)
    }

    /** Whether [slotIndex] is one of the next Show Next Numbers clicks in [solution]. */
    @JvmStatic
    fun showsTerminalNumber(slotIndex: Int, solution: List<Int>): Boolean = solution.indexOf(slotIndex) in 0 until numbersShown

    enum class Style(private val label: String) {
        CUSTOM("Custom"), VANILLA("Vanilla");
        override fun toString() = label
    }

    enum class RubixMode(private val label: String) {
        FEWEST_CLICKS("Fewest Clicks"), LEFT_CLICKS_ONLY("Left Clicks Only"), LEAST_MOVEMENT("Least Movement"), LEFT_LEAST_MOVEMENT("Left Clicks, Least Movement");
        override fun toString() = label
    }

    fun install() {
        TerminalUtils.install()
        TermSolver.install()
        ClickLimit.install()
        TermTimes.install()
        TermSounds.install()
        TermStatus.install()
        TermTitles.install()
        TermProgress.install()
        TerminalHitboxes.install()
    }
}
