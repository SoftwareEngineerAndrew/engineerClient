package com.devgineerclient

import com.devgineerclient.betterrecorder.BetterRecorder
import com.devgineerclient.bossrecorder.BossRecorder
import com.devgineerclient.maxor.MaxorCrystals
import com.devgineerclient.misc.FastLaunch
import com.devgineerclient.perf.PerfMonitor
import com.devgineerclient.recorder.DungeonRecorder
import com.devgineerclient.splits.DungeonSplits
import com.devgineerclient.splits.PaceTargets
import com.devgineerclient.splits.Witherborn
import com.odtheking.odin.config.ModuleConfig
import com.odtheking.odin.features.ModuleManager
import net.fabricmc.api.ClientModInitializer
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object DevgineerClient : ClientModInitializer {

    val logger: Logger = LoggerFactory.getLogger("devgineerclient")
    val mc: Minecraft get() = Minecraft.getInstance()

    override fun onInitializeClient() {
        safely("launch timeline") { com.devgineerclient.misc.LaunchTimeline.install() }
        // Odin's addon path: own ClickGUI panel, own config file (config/odin/addons/devgineerclient.json).
        val modules = arrayOf(DungeonRecorder, BetterRecorder, BossRecorder, MaxorCrystals, DungeonSplits, com.devgineerclient.practice.I4Aims, FastLaunch, com.devgineerclient.misc.RenderTweaks, com.devgineerclient.misc.SoundCollector, PerfMonitor)
        ModuleManager.registerModules(ModuleConfig("devgineerclient.json"), *modules)
        // A module made with toggled = true would start on but off Odin's event bus.
        safely("subscribe enabled") { com.engineerclient.EngineerClient.subscribeEnabled(modules.asList()) }
        // On by default, existing installs included (once: turning it off sticks). After registration, so saved settings are read.
        safely("fast launch default") { FastLaunch.enableByDefault() }
        safely("perf monitor default") { PerfMonitor.enableByDefault() }
        safely("maxor crystals default") { MaxorCrystals.enableByDefault() }
        safely("boss recorder default") { BossRecorder.enableByDefault() }
        safely("better recorder default") { BetterRecorder.enableByDefault() }
        safely("class warmup") { com.devgineerclient.misc.ClassWarmup.install() }
        safely("early join") { com.devgineerclient.misc.EarlyJoin.install() }
        safely("f7 control") { com.devgineerclient.misc.F7Control.install() }
        safely("fast exit") { com.devgineerclient.misc.FastExit.install() }
        safely("edt") { com.devgineerclient.misc.Edt.install() }
        safely("sound collector") { com.devgineerclient.misc.SoundCollector.install() }
        safely("pace targets") { PaceTargets.install() }
        safely("witherborn") { Witherborn.register() }
    }

    /** Every handler that runs inside Odin's bus or a coroutine must not be able to take Odin down with it. */
    inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            logger.error("[dc] $what failed", t)
        }
    }

    fun chat(msg: String) {
        mc.schedule { mc.gui.hud.chat.addClientSystemMessage(Component.literal(msg)) }
    }

    fun chat(msg: Component) {
        mc.schedule { mc.gui.hud.chat.addClientSystemMessage(msg) }
    }

    /** What every line the mod says in chat starts with. */
    const val PREFIX = "§8[§6DC§8] "

    /** A line from the mod, with its [PREFIX]. Anything the mod says goes through here. */
    fun msg(text: String) = chat(PREFIX + text)

    fun msg(text: Component) = chat(Component.literal(PREFIX).append(text))
}
