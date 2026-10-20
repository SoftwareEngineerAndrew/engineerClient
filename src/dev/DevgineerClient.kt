package com.devgineerclient

import com.devgineerclient.bossrecorder.BossRecorder
import com.devgineerclient.maxor.MaxorCrystals
import com.devgineerclient.recorder.DungeonRecorder
import com.devgineerclient.splits.DungeonSplits
import com.devgineerclient.splits.PaceTargets
import com.devgineerclient.splits.Witherborn
import com.devgineerclient.waypoints.PosMsgSetup
import com.devgineerclient.waypoints.PositionalMessages
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
        // Modules that moved here from Engineer Client keep their settings (copied once, before Odin reads this file).
        safely("adopt settings") { adoptSettings("Dynamic Term Roles", "i4 Aim") }
        safely("posmsg migration") { PosMsgSetup.migrate(mc.gameDirectory.toPath().resolve("config").resolve("odin")) }
        // Odin's addon path: own ClickGUI panel, own config file (config/odin/addons/devgineerclient.json).
        ModuleManager.registerModules(ModuleConfig("devgineerclient.json"), DungeonRecorder, com.devgineerclient.betterrecorder.BetterRecorder, BossRecorder, MaxorCrystals, DungeonSplits, PositionalMessages, com.devgineerclient.practice.I4Aims, com.devgineerclient.rotation.P3Rotation, com.devgineerclient.misc.FastLaunch, com.devgineerclient.misc.RenderTweaks)
        // Fast Launch pieces: after registration, so they can read its (loaded) settings.
        safely("fast launch default") { com.devgineerclient.misc.FastLaunch.enableByDefault() }
        safely("early join") { com.devgineerclient.misc.EarlyJoin.install() }
        safely("f7 control") { com.devgineerclient.misc.F7Control.install() }
        safely("fast exit") { com.devgineerclient.misc.FastExit.install() }
        safely("leap highlight") { com.devgineerclient.rotation.LeapHighlight.register() }
        safely("role vignette") { com.devgineerclient.rotation.RoleVignette.register() }
        safely("rotation commands") { com.devgineerclient.rotation.RotationCommands.install() }
        safely("edt") { com.devgineerclient.misc.Edt.install() }
        safely("posmsg") { PosMsgSetup.install() }
        safely("pace targets") { PaceTargets.install() }
        safely("witherborn") { Witherborn.register() }

        // On by default, existing installs included (once: turning it off sticks).
        safely("boss recorder default") { BossRecorder.enableByDefault() }
        safely("better recorder default") { com.devgineerclient.betterrecorder.BetterRecorder.enableByDefault() }
        safely("maxor crystals default") { MaxorCrystals.enableByDefault() }
    }

    /** Copies these modules' entries from engineerclient.json into devgineerclient.json where this file has none yet. */
    private fun adoptSettings(vararg names: String) {
        val dir = mc.gameDirectory.toPath().resolve("config/odin/addons")
        val theirs = dir.resolve("engineerclient.json")
        if (!java.nio.file.Files.exists(theirs)) return
        val ours = dir.resolve("devgineerclient.json")
        val target = if (java.nio.file.Files.exists(ours)) com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(ours)).asJsonArray else com.google.gson.JsonArray()
        val source = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(theirs)).asJsonArray
        var changed = false
        for (name in names) {
            if (target.any { it.asJsonObject.get("name")?.asString == name }) continue
            val entry = source.firstOrNull { it.asJsonObject.get("name")?.asString == name } ?: continue
            target.add(entry)
            changed = true
        }
        if (changed) java.nio.file.Files.writeString(ours, com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(target))
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
