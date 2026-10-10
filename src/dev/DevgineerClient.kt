package com.devgineerclient

import com.devgineerclient.bossrecorder.BossRecorder
import com.devgineerclient.maxor.MaxorCrystals
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
        // Odin's addon path: own ClickGUI panel, own config file (config/odin/addons/devgineerclient.json).
        ModuleManager.registerModules(ModuleConfig("devgineerclient.json"), DungeonRecorder, BossRecorder, MaxorCrystals, DungeonSplits)
        safely("pace targets") { PaceTargets.install() }
        safely("witherborn") { Witherborn.register() }

        // On by default, existing installs included (once: turning it off sticks).
        safely("boss recorder default") { BossRecorder.enableByDefault() }
        safely("maxor crystals default") { MaxorCrystals.enableByDefault() }
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
        mc.schedule { mc.gui.chat.addClientSystemMessage(Component.literal(msg)) }
    }

    fun chat(msg: Component) {
        mc.schedule { mc.gui.chat.addClientSystemMessage(msg) }
    }

    /** What every line the mod says in chat starts with. */
    const val PREFIX = "§8[§6DC§8] "

    /** A line from the mod, with its [PREFIX]. Anything the mod says goes through here. */
    fun msg(text: String) = chat(PREFIX + text)

    fun msg(text: Component) = chat(Component.literal(PREFIX).append(text))
}
