package com.coffeeclient

import com.coffeeclient.leap.LeapExtras
import com.coffeeclient.misc.CameraOffset
import com.coffeeclient.misc.EntityDistance
import com.coffeeclient.misc.Termsim
import com.coffeeclient.misc.PreRequeue
import com.coffeeclient.misc.RandomStuff
import com.coffeeclient.misc.SoundEditor
import com.coffeeclient.misc.SpeedHud
import com.coffeeclient.pf.BetterPFMenu
import com.odtheking.odin.config.ModuleConfig
import com.odtheking.odin.features.ModuleManager
import net.fabricmc.api.ClientModInitializer
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object CoffeeClient : ClientModInitializer {

    val logger: Logger = LoggerFactory.getLogger("coffeeclient")
    val mc: Minecraft get() = Minecraft.getInstance()

    override fun onInitializeClient() {
        // No saved module settings yet: a fresh install.
        val firstRun = !java.nio.file.Files.exists(mc.gameDirectory.toPath().resolve("config/odin/addons/coffeeclient.json"))

        safely("leap extras settings") { adoptLeapExtrasSettings() }

        // Odin's addon path: own ClickGUI panel ("Coffee Client") and own config file
        // (config/odin/addons/coffeeclient.json).
        ModuleManager.registerModules(ModuleConfig("coffeeclient.json"), PreRequeue, BetterPFMenu, CameraOffset, LeapExtras, SpeedHud, SoundEditor, EntityDistance, RandomStuff, Termsim)

        // Modules default OFF and only ModuleConfig.load() toggles saved state — on a
        // fresh install nothing has saved state yet, so turn these on once.
        if (firstRun) {
            if (!RandomStuff.enabled) RandomStuff.toggle()
            if (!LeapExtras.enabled) LeapExtras.toggle()
            ModuleManager.saveConfigurations()
        }

        // On by default, existing installs included (once: turning it off sticks).
        safely("speed hud default") { SpeedHud.enableByDefault() }
        safely("termsim default") { Termsim.enableByDefault() }
        safely("witherborn") { com.coffeeclient.misc.Witherborn.register() }
        safely("cursor reset") { com.coffeeclient.misc.CursorReset.register() }

        logger.info("[cc] initialized")
    }

    /**
     * Leap Extras used to be an Engineer Client module: the first time it loads here, its saved
     * settings are copied over from engineerclient.json so they carry across.
     */
    private fun adoptLeapExtrasSettings() {
        val dir = mc.gameDirectory.toPath().resolve("config/odin/addons")
        val ours = dir.resolve("coffeeclient.json")
        val theirs = dir.resolve("engineerclient.json")
        if (!java.nio.file.Files.exists(ours) || !java.nio.file.Files.exists(theirs)) return
        val target = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(ours)).asJsonArray
        if (target.any { it.asJsonObject.get("name")?.asString == "Leap Extras" }) return
        val source = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(theirs)).asJsonArray
        val entry = source.firstOrNull { it.asJsonObject.get("name")?.asString == "Leap Extras" } ?: return
        target.add(entry)
        java.nio.file.Files.writeString(ours, com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(target))
        logger.info("[cc] Leap Extras settings copied from engineerclient.json")
    }

    /** Every handler that runs inside Odin's bus or a coroutine must not be able to take Odin down with it. */
    inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            logger.error("[cc] $what failed", t)
        }
    }

    fun chat(msg: String) {
        mc.schedule { mc.gui.hud.chat.addClientSystemMessage(Component.literal(msg)) }
    }

    /** What every line the mod says in chat starts with. */
    const val PREFIX = "§8[§6CC§8] "

    /** A line from the mod, with its [PREFIX]. Anything the mod says goes through here. */
    fun msg(text: String) = chat(PREFIX + text)
}
