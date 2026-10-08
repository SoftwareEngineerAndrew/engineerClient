package com.engineerclient.p3sim

import com.engineerclient.EngineerClient.mc
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * The sim's main menu: Esc > P3 Sim Menu, the SkyBlock Menu star (hotbar), `/p3sim` or the
 * keybind. Nothing on it but one big Restart in the middle of the screen, where the cursor already
 * is when it opens from the Esc menu. Everything else is the full menu ([SimScreen]), under Save and
 * Quit in the Esc menu.
 */
class SimRestartScreen : Screen(Component.literal("P3 Sim")) {
    override fun init() {
        super.init()
        addRenderableWidget(Button.builder(Component.literal("§a§lRestart")) {
            mc.gui.setScreen(null)
            SimServer.run("restart") { Fight.start(Fight.lastStart) }
        }.bounds(width / 2 - W / 2, height / 2 - H / 2, W, H).build())
    }

    override fun isPauseScreen(): Boolean = false

    private companion object {
        const val W = 200
        const val H = 60
    }
}
