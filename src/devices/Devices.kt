package com.engineerclient.devices

import com.engineerclient.practice.SimonSaysPractice
import com.mojang.blaze3d.platform.InputConstants
import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.*
import com.adm.events.TickEvent
import com.adm.events.core.EventBus
import com.adm.events.core.on
import com.adm.features.Category
import com.adm.features.Module
import com.adm.utils.Color.Companion.withAlpha
import com.adm.utils.Colors
import com.adm.utils.createSoundSettings
import com.adm.utils.render.BoxStyle

/** The P3 device solvers and SS Practice, one section each; the logic lives in the section objects ([DevSimonSays], [SimonSaysPractice], [DevArrowAlign], [DevArrows]). */
object Devices : Module(
    name = "Devices",
    key = null,
    category = Category.custom("Engineer Client", 860, 10),
    description = "Floor 7 devices: P3 Start Countdown, Simon Says (and SS Practice), Arrow Align and the Arrows Device (i4).",
    toggled = true,
) {
    // ------------------------------------------------------------------ P3 Start
    private val startDrop by DropdownSetting("P3 Start", false, desc = "The start of P3 at devices 1-3.")
    val startCountdown by BooleanSetting("Start Countdown", true, desc = "Over devices 1-3, time until your clicks count, after your ping.").withDependency { startDrop }

    // ------------------------------------------------------------------ Simon Says
    private val ssDrop by DropdownSetting("Simon Says", true, desc = "The Simon Says device (S1).")
    val simonSays by BooleanSetting("Enable Simon Says", true, desc = "Shows which buttons to press, in order.").withDependency { ssDrop }
    val ssFirstColor by ColorSetting("First Color", Colors.MINECRAFT_GREEN.withAlpha(0.5f), true, desc = "The next button.").withDependency { ssDrop }
    val ssSecondColor by ColorSetting("Second Color", Colors.MINECRAFT_GOLD.withAlpha(0.5f), true, desc = "The one after.").withDependency { ssDrop }
    val ssThirdColor by ColorSetting("Third Color", Colors.MINECRAFT_RED.withAlpha(0.5f), true, desc = "The rest.").withDependency { ssDrop }
    val ssStyle by SelectorSetting("Box Style", BoxStyle.FILLED_OUTLINE, desc = "How the buttons are boxed.").withDependency { ssDrop }
    val announceProgress by BooleanSetting("Announce Progress", true, desc = "Party chat when you finish a round.").withDependency { ssDrop }
    val ssRoundCountdown by BooleanSetting("Round Countdown", true, desc = "Over the first button, time until rounds 2-4 take clicks, after your ping.").withDependency { ssDrop }
    val ssCountdownSeconds by BooleanSetting("Countdown In Seconds", false, desc = "Seconds in steps of 0.05 instead of ticks.").withDependency { ssDrop && ssRoundCountdown }
    val ssQueueClicks by BooleanSetting("Queue Clicks", true, desc = "One press per server tick; the rest wait, so lag can't drop them.").withDependency { ssDrop }
    val ssBlockWrong by BooleanSetting("Block Wrong Clicks", false, desc = "Blocks wrong buttons; sneak overrides.").withDependency { ssDrop }
    val blockWrongStart by BooleanSetting("Block Wrong on Start", false, desc = "Blocks start button spam in the first phase.").withDependency { ssDrop }
    val maxStartClicks by NumberSetting("Max Start Clicks", 4, 1..10, 1, desc = "Start button clicks allowed in the first phase.").withDependency { ssDrop && blockWrongStart }
    val customClickSounds by BooleanSetting("Custom Click Sounds", false, desc = "Sounds for allowed and blocked clicks.").withDependency { ssDrop }
    val correctSound = createSoundSettings("Correct Sound", "entity.experience_orb.pickup") { ssDrop && customClickSounds }
    val wrongSound = createSoundSettings("Wrong Sound", "entity.blaze.hurt") { ssDrop && customClickSounds && ssBlockWrong }
    val stageMessages by BooleanSetting("Stage Messages", false, desc = "A message each time a stage is done.").withDependency { ssDrop }
    val stageTarget by SelectorSetting("Stage Message To", Target.PARTY, desc = "Party chat or only you.").withDependency { ssDrop && stageMessages }
    val breakAlert by BooleanSetting("Break Alert", false, desc = "Alerts when the device breaks.").withDependency { ssDrop }
    val breakParty by BooleanSetting("Break Party Message", true, desc = "SS Broke! in party chat.").withDependency { ssDrop && breakAlert }
    val breakTitle by BooleanSetting("Break Title", true, desc = "SS BROKE! on screen.").withDependency { ssDrop && breakAlert }
    val breakSound by BooleanSetting("Break Sound", true, desc = "An anvil sound.").withDependency { ssDrop && breakAlert }
    val restartMessage by BooleanSetting("Restart Message", false, desc = "SS Started Again! in party chat after a break.").withDependency { ssDrop }

    // ------------------------------------------------------------------ SS Practice
    private val ssPracticeDrop by DropdownSetting("SS Practice", false, desc = "A client side Simon Says to practice anywhere.")
    val ssPractice by BooleanSetting("Enable SS Practice", true, desc = "Summons F7's Simon Says in front of you; the blocks and clicks never reach the server.").withDependency { ssPracticeDrop }
    private val summonKey by KeybindSetting("Summon Keybind", InputConstants.UNKNOWN, "Summons the device in front of you, and takes it away again (Infinileap in hand: /termsim inf).").onPress { SimonSaysPractice.summonKey() }.withDependency { ssPracticeDrop }
    val showSpeed by NumberSetting("Show Speed", 1.0, 1.0..3.0, 0.25, desc = "How fast the lights are shown (1x is the game's speed).").withDependency { ssPracticeDrop }
    val instantShow by BooleanSetting("Instant Show", false, desc = "The whole sequence at once, no lights shown.").withDependency { ssPracticeDrop }
    val fullBlockHitboxes by BooleanSetting("Full Block Hitboxes", false, desc = "Each practice button clicks as its whole block face.").withDependency { ssPracticeDrop }
    val triggerBot by BooleanSetting("Trigger Bot", false, desc = "Practice device only: presses the button under your crosshair when it's next.").withDependency { ssPracticeDrop }
    val followYou by BooleanSetting("Follow You", false, desc = "The device stays in front of you as you move.").withDependency { ssPracticeDrop }
    val practiceSize by NumberSetting("Practice Size", 1.0, 1.0..3.0, 0.5, desc = "How big (and as many times farther) the device is drawn.").withDependency { ssPracticeDrop }

    // ------------------------------------------------------------------ Arrow Align
    private val alignDrop by DropdownSetting("Arrow Align", false, desc = "The Arrow Align device (S3).")
    val arrowAlign by BooleanSetting("Enable Arrow Align", true, desc = "Shows how many clicks each frame needs.").withDependency { alignDrop }
    val alignBlockWrong by BooleanSetting("Align Block Wrong Clicks", true, desc = "Blocks clicks on frames already right; sneak overrides.").withDependency { alignDrop }
    val preventKey by KeybindSetting("Prevent Blocking", InputConstants.KEY_LSHIFT, "Wrong clicks go through while held.").withDependency { alignDrop && alignBlockWrong }
    val invertKey by BooleanSetting("Invert Key", false, desc = "Wrong clicks are blocked only while the key is held.").withDependency { alignDrop && alignBlockWrong }

    // ------------------------------------------------------------------ Arrows Device
    private val arrowsDrop by DropdownSetting("Arrows Device", false, desc = "The Arrows Device (i4, S4).")
    val arrowsDevice by BooleanSetting("Enable Arrows Device", true, desc = "Marks hit blocks and the target.").withDependency { arrowsDrop }
    val markedColor by ColorSetting("Marked Position", Colors.MINECRAFT_AQUA.withAlpha(0.5f), true, desc = "Blocks already hit.").withDependency { arrowsDrop }
    val targetColor by ColorSetting("Target Position", Colors.MINECRAFT_LIGHT_PURPLE.withAlpha(0.5f), true, desc = "The block to hit.").withDependency { arrowsDrop }
    val depthCheck by BooleanSetting("Depth Check", true, desc = "Marked blocks show through walls.").withDependency { arrowsDrop }
    val completeAlert by BooleanSetting("Device Complete Alert", true, desc = "An alert when the device is done.").withDependency { arrowsDrop }
    val showAimPositions by BooleanSetting("Show Aim Positions", false, desc = "Cubes where to aim (Terminator); hidden while Devgineer's i4 Aim is on.").withDependency { arrowsDrop }
    val firstAimColor by ColorSetting("First Aim Position", Colors.MINECRAFT_GREEN.withAlpha(0.5f), true, desc = "First aim.").withDependency { arrowsDrop && showAimPositions }
    val secondAimColor by ColorSetting("Second Aim Position", Colors.MINECRAFT_GOLD.withAlpha(0.5f), true, desc = "Second aim.").withDependency { arrowsDrop && showAimPositions }
    val thirdAimColor by ColorSetting("Third Aim Position", Colors.MINECRAFT_RED.withAlpha(0.5f), true, desc = "Third aim.").withDependency { arrowsDrop && showAimPositions }
    private val resetArrows by ActionSetting("Reset Arrows Device", desc = "Resets the solver.") { DevArrows.reset() }.withDependency { arrowsDrop }

    val startCountdownOn get() = enabled && startCountdown
    val simonSaysOn get() = enabled && simonSays
    val ssRoundCountdownOn get() = simonSaysOn && ssRoundCountdown
    val arrowAlignOn get() = enabled && arrowAlign
    val arrowsOn get() = enabled && arrowsDevice
    val ssPracticeOn get() = enabled && ssPractice

    enum class Target(private val label: String) {
        PARTY("Party"), SELF("Self");
        override fun toString() = label
    }

    init {
        on<TickEvent.End> { syncSections() }
    }

    override fun onDisable() {
        super.onDisable()
        syncSections()
    }

    private fun syncSections() {
        sync(DevArrowAlign, arrowAlignOn) { DevArrowAlign.forget() }
        sync(DevArrows, arrowsOn) { DevArrows.restart() }
        sync(com.engineerclient.misc.SimonSaysAlerts, simonSaysOn) { com.engineerclient.misc.SimonSaysAlerts.reset() }
    }

    private inline fun sync(section: Any, on: Boolean, reset: () -> Unit) {
        if (on == section in EventBus.activeSubscribers) return
        if (on) { reset(); EventBus.subscribe(section) } else EventBus.unsubscribe(section)
    }

    fun install() {
        DevStartCountdown.install()
        DevSimonSays.install()
        DevSsRoundCountdown.install()
        DevArrowAlign.install()
        DevArrows.install()
        SimonSaysPractice.install()
    }
}
