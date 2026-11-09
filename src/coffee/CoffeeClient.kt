package com.coffeeclient

import com.coffeeclient.misc.CameraOffset
import com.coffeeclient.misc.OldBlocking
import com.coffeeclient.misc.HighlightLevers
import com.coffeeclient.misc.Termsim
import com.coffeeclient.misc.TermInfoHud
import com.coffeeclient.misc.Timers2
import com.coffeeclient.misc.PreRequeue
import com.coffeeclient.misc.RandomStuff
import com.coffeeclient.misc.SoundEditor
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
        // Odin's addon path: own ClickGUI panel ("Coffee Client") and own config file
        // (config/odin/addons/coffeeclient.json).
        val modules = arrayOf(PreRequeue, CameraOffset, OldBlocking, SoundEditor, RandomStuff, Termsim, HighlightLevers, TermInfoHud, Timers2)
        ModuleManager.registerModules(ModuleConfig("coffeeclient.json"), *modules)
        // A module made with toggled = true would start on but off Odin's event bus.
        safely("subscribe enabled") { com.engineerclient.EngineerClient.subscribeEnabled(modules.asList()) }

        // On by default, existing installs included (once: turning it off sticks).
        safely("termsim default") { Termsim.enableByDefault() }
        safely("term info look") { TermInfoHud.adoptOdinLook() }
        safely("cursor reset") { com.coffeeclient.misc.CursorReset.register() }
        safely("no pack") { com.coffeeclient.misc.NoPack.register() }

        logger.info("[cc] initialized")
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
