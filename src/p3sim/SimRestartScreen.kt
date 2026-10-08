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
        addRenderableWidget(Button.builder(Component.literal("§cStop")) {
            mc.gui.setScreen(null)
            SimServer.run("stop") { Fight.end() }
        }.bounds(cx - RESTART_W / 2, cy - 34, RESTART_W, 20).build())

        // Left of Restart: the Helper (the bot on your stack does its share of it too).
        addRenderableWidget(Button.builder(Component.literal("Helper: " + if (P3Plan.helper) "§aON" else "§cOFF")) {
            P3Plan.helper = !P3Plan.helper
            P3Plan.save()
            rebuildWidgets()
        }.bounds(cx - RESTART_W / 2 - GAP - SKILL_W, cy - 10, SKILL_W, 20).build())
        // Above it, level with Stop: the leap menu's sorting (Odin's, or your own slot order from the full menu).
        addRenderableWidget(Button.builder(Component.literal("Leap Sort: " + if (P3Plan.odinSort) "§bOdin" else "§fCustom")) {
            P3Plan.odinSort = !P3Plan.odinSort
            P3Plan.save()
            rebuildWidgets()
        }.bounds(cx - RESTART_W / 2 - GAP - SKILL_W, cy - 34, SKILL_W, 20).build())

        // Below Restart and the skills: every job of each section, in columns that mean the same in
        // every section (terminals 1-5, then L, R, D, G), the grid centred. Green: yours; grey and a
        // letter: the bot's that does it; * a stack.
        val jobW = 34
        val x0 = cx - (LABEL_W + 9 * (jobW + 2)) / 2 + LABEL_W
        var y = cy + 3 * ROW + GAP
        for (s in 1..4) {
            addRenderableWidget(StringWidget(x0 - LABEL_W, y, LABEL_W, 20, Component.literal("§6§lS$s"), font))
            for (job in P3Plan.jobsIn(s)) {
                val short = P3Plan.short(job)
                val col = when (short) { "L" -> 5; "R" -> 6; "D" -> 7; "G" -> 8; else -> ((short.toIntOrNull() ?: 1) - 1).coerceIn(0, 4) }
                val stack = if (P3Plan.isStack(job)) "*" else ""
                val text = if (P3Plan.isMine(job)) "§a$short$stack"
                    else "§7$short$stack§8${P3Plan.doer(job)?.let { Roles.label(it).take(1) } ?: "?"}"
                addRenderableWidget(Button.builder(Component.literal(text)) { P3Plan.toggle(job); rebuildWidgets() }
                    .bounds(x0 + col * (jobW + 2), y, jobW, 20).build())
            }
            y += ROW
        }

        // Right: the skill, against Restart, the chosen one highlighted, each with its P3 time.
        // Shown names only: the presets keep theirs (best runs and stats are saved under them).
        val skills = listOf(
            Triple(0, "Normal PF", "34s"), Triple(1, "Quality PF", "28s"),
            Triple(2, "Optimal PF", "22s"), Triple(P3Plan.RANDOM + 1, "Theoretical", "18s"),
        )
        val right = cx + RESTART_W / 2 + GAP
        y = cy - skills.size * ROW / 2 + 2
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
