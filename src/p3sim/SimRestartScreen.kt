package com.engineerclient.p3sim

import com.engineerclient.EngineerClient.mc
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.network.chat.Component
import net.minecraft.client.gui.screens.Screen

/**
 * The sim's main menu: Esc > P3 Sim Menu, the SkyBlock Menu star (hotbar), `/p3sim` or the
 * keybind. Restart P3 in the middle (where the cursor already is from the Esc menu), your jobs per
 * section on the left, the skill on the right. Everything else is the full menu ([SimScreen]).
 */
class SimRestartScreen : Screen(Component.literal("P3 Sim")) {
    override fun init() {
        super.init()
        addRenderableWidget(Button.builder(Component.literal("§aRestart P3")) {
            mc.gui.setScreen(null)
            SimServer.run("restart") { Fight.start(Fight.Start.P3) }
        }.bounds(width / 2 - 50, height / 2 - 10, 100, 20).build())

        // Left: what you do in each section.
        val left = 10
        var y = height / 2 - 2 * ROW
        for (s in 1..4) {
            val mine = P3Plan.jobsIn(s).filter { P3Plan.isMine(it) }.joinToString(", ") { P3Plan.short(it) }
            addRenderableWidget(StringWidget(left, y, 160, 20, Component.literal("§6§lS$s §f" + mine.ifEmpty { "§8—" }), font))
            y += ROW
        }

        // Right: the skill, the chosen one highlighted.
        val right = width - 10 - SKILL_W
        y = height / 2 - P3Plan.SKILLS.size * ROW / 2
        P3Plan.SKILLS.forEachIndexed { i, name ->
            addRenderableWidget(Button.builder(Component.literal(if (i == P3Plan.skill) "§a§n$name" else name)) {
                P3Plan.chooseSkill(i)
                rebuildWidgets()
            }.bounds(right, y, SKILL_W, 20).build())
            y += ROW
        }
    }

    override fun isPauseScreen(): Boolean = false

    private companion object {
        const val ROW = 24
        const val SKILL_W = 90
    }
}
