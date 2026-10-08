package com.engineerclient.p3sim

import com.engineerclient.EngineerClient.mc
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.network.chat.Component
import net.minecraft.client.gui.screens.Screen

/**
 * The sim's main menu: Esc > P3 Sim Menu, the SkyBlock Menu star (hotbar), `/p3sim` or the
 * keybind. Restart P3 in the middle (where the cursor already is from the Esc menu), each
 * section's jobs on its left (click: yours or a bot's, as in the full menu's Plan tab), the skill on
 * its right. Everything else is the full menu ([SimScreen]).
 */
class SimRestartScreen : Screen(Component.literal("P3 Sim")) {
    override fun init() {
        super.init()
        val cx = width / 2
        val cy = height / 2
        addRenderableWidget(Button.builder(Component.literal("§aRestart P3")) {
            mc.gui.setScreen(null)
            SimServer.run("restart") { Fight.start(Fight.Start.P3) }
        }.bounds(cx - RESTART_W / 2, cy - 10, RESTART_W, 20).build())

        // Left: every job of each section, right-aligned against Restart. Green: yours; grey and a
        // letter: the bot's that does it; * a stack.
        val leftEdge = cx - RESTART_W / 2 - GAP
        val widest = (1..4).maxOf { P3Plan.jobsIn(it).size }
        val jobW = ((leftEdge - 4 - LABEL_W) / widest - 2).coerceIn(22, 48)
        var y = cy - 2 * ROW + 2
        for (s in 1..4) {
            val jobs = P3Plan.jobsIn(s)
            var x = leftEdge - jobs.size * (jobW + 2) + 2
            addRenderableWidget(StringWidget(x - LABEL_W, y, LABEL_W, 20, Component.literal("§6§lS$s"), font))
            for (job in jobs) {
                val stack = if (P3Plan.isStack(job)) "*" else ""
                val text = if (P3Plan.isMine(job)) "§a${P3Plan.short(job)}$stack"
                    else "§7${P3Plan.short(job)}$stack§8${P3Plan.doer(job)?.let { Roles.label(it).take(1) } ?: "?"}"
                addRenderableWidget(Button.builder(Component.literal(text)) { P3Plan.toggle(job); rebuildWidgets() }
                    .bounds(x, y, jobW, 20).build())
                x += jobW + 2
            }
            y += ROW
        }

        // Right: the skill, against Restart, the chosen one highlighted.
        val right = cx + RESTART_W / 2 + GAP
        y = cy - P3Plan.SKILLS.size * ROW / 2 + 2
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
        const val RESTART_W = 100
        const val GAP = 10
        const val LABEL_W = 18
        const val ROW = 24
        const val SKILL_W = 90
    }
}
