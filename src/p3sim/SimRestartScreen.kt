package com.engineerclient.p3sim

import com.engineerclient.EngineerClient.mc
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.network.chat.Component
import net.minecraft.client.gui.screens.Screen

/**
 * The sim's main menu: Esc > P3 Sim Menu, the SkyBlock Menu star (hotbar), `/p3sim` or the
 * keybind. Restart P3 in the middle (where the cursor already is from the Esc menu), each
 * section's jobs below it (click: yours or a bot's, as in the full menu's Plan tab), the skill on
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

        // Below Restart: every job of each section, centred. Green: yours; grey and a letter: the
        // bot's that does it; * a stack.
        val jobW = 34
        var y = cy + 10 + GAP
        for (s in 1..4) {
            val jobs = P3Plan.jobsIn(s)
            var x = cx - (LABEL_W + jobs.size * (jobW + 2)) / 2 + LABEL_W
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

        // Right: the skill, against Restart, the chosen one highlighted, each with its P3 time.
        // Shown names only: the presets keep theirs (best runs and stats are saved under them).
        val skills = listOf(
            Triple(0, "Normal PF", "34s"), Triple(1, "Quality PF", "28s"),
            Triple(2, "Optimal PF", "22s"), Triple(P3Plan.RANDOM + 1, "Theoretical", "18s"),
        )
        // Up and out of the plan's way: the last one ends level with Restart's bottom.
        val right = cx + RESTART_W / 2 + GAP + 20
        y = cy + 10 - skills.size * ROW + 4
        for ((i, name, time) in skills) {
            addRenderableWidget(Button.builder(Component.literal(if (i == P3Plan.skill) "§a§n$name" else name)) {
                P3Plan.chooseSkill(i)
                rebuildWidgets()
            }.bounds(right, y, SKILL_W, 20).build())
            addRenderableWidget(StringWidget(right + SKILL_W + 4, y, 24, 20, Component.literal("§8$time"), font))
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
