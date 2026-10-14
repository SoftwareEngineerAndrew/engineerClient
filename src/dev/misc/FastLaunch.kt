package com.devgineerclient.misc

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module

/**
 * The switches for this client's launch-speed pieces (EarlyJoin, FastExit, LaunchTimeline). Each piece asks
 * here when it would act, so turning one off takes effect from the next launch (or at once where it can).
 */
object FastLaunch : Module(
    name = "Fast Launch",
    key = null,
    category = Category.custom("Devgineer Client", 860, 10),
    description = "Launch-speed helpers for the f7 launcher: joining while textures load, a quick quit, launch timings.",
) {
    private val earlyJoinSetting by BooleanSetting("Early Join", true, desc = "Connect to the join-on-launch server while textures are still loading; only the switch into the world waits for them.")
    private val fastExitSetting by BooleanSetting("Fast Exit", true, desc = "End dtmap's image watcher on quit, so the game closes at once instead of after ~16 s.")
    private val timelineSetting by BooleanSetting("Launch Timeline", true, desc = "Write each launch's timings to logs/launch-timeline.log.")
    private val buildChatSetting by BooleanSetting("Build In Chat", true, desc = "On reaching Skyblock, a chat line with the build you're on and how long the launch took.")

    /** Whether the module has had its one-time switch-on (on by default, existing installs too). */
    private var switchedOn by BooleanSetting("Switched On", false, desc = "").hide()

    val earlyJoin get() = enabled && earlyJoinSetting
    val fastExit get() = enabled && fastExitSetting
    val timeline get() = enabled && timelineSetting
    val buildChat get() = enabled && buildChatSetting

    fun enableByDefault() {
        if (switchedOn) return
        switchedOn = true
        if (!enabled) toggle()
        com.odtheking.odin.features.ModuleManager.saveConfigurations()
    }
}
