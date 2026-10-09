package com.engineerclient.p3sim

import com.engineerclient.EngineerClient.mc
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component
import net.minecraft.client.gui.screens.Screen

/**
 * The sim's main menu: Esc > P3 Sim Menu, the SkyBlock Menu star (hotbar), `/p3sim` or the
 * keybind. Restart P3 in the middle (where the cursor already is from the Esc menu), Stop above it,
 * the Helper and the leap sort on its left, the skill on its right; under them Roles (your class and
 * each section's jobs), Settings and Teleport, each a menu of its own ([SimSubmenu]). Every button
 * says what it does when hovered. Everything else is the full menu ([SimScreen]).
 */
class SimRestartScreen : Screen(Component.literal("P3 Sim")) {
    override fun init() {
        super.init()
        val cx = width / 2
        val cy = height / 2
        val left = cx - RESTART_W / 2 - GAP - SIDE_W
        val right = cx + RESTART_W / 2 + GAP
        addRenderableWidget(Button.builder(Component.literal("§aRestart P3")) {
            mc.setScreen(null)
            SimServer.run("restart") { Fight.start(Fight.Start.P3) }
        }.tooltip(tip("Starts P3 over from its beginning, everything reset (Stop: ends it).")).bounds(cx - RESTART_W / 2, cy - 10, RESTART_W, 20).build())
        addRenderableWidget(Button.builder(Component.literal("§cStop")) {
            mc.setScreen(null)
            SimServer.run("stop") { Fight.end() }
        }.tooltip(tip("Ends the run: the fight stops and the splits clear.")).bounds(cx - RESTART_W / 2, cy - 34, RESTART_W, 20).build())

        // Left of Restart: the Helper (the bot on your stack does its share of it too).
        addRenderableWidget(Button.builder(Component.literal("Helper: " + if (P3Plan.helper) "§aON" else "§cOFF")) {
            P3Plan.helper = !P3Plan.helper
            P3Plan.save()
            rebuildWidgets()
        }.tooltip(tip("On: the bots help with your stacks: when you're on a stack's terminal, the bot on its other one does the stack's lever (and gate) too, whoever gets there first. Off: your stacks are all yours. (Quality PF and Optimal PF.)")).bounds(left, cy - 10, SIDE_W, 20).build())
        // Above it, level with Stop: the leap menu's sorting (Odin's, or your own slot order from the full menu).
        addRenderableWidget(Button.builder(Component.literal("Leap Sort: " + if (P3Plan.odinSort) "§bOdin" else "§fCustom")) {
            P3Plan.odinSort = !P3Plan.odinSort
            P3Plan.save()
            rebuildWidgets()
        }.tooltip(tip("The leap menu's order. Odin: as Odin's Leap Menu sorts it (by class). Custom: your own slot order, set in the full menu's Early Enters tab.")).bounds(left, cy - 34, SIDE_W, 20).build())

        // Right: the skill, against Restart, the chosen one highlighted (its P3 time on hover).
        // Shown names only: the presets keep theirs (best runs and stats are saved under them).
        val skills = listOf(
            Triple(0, "Normal PF", "The bots play at a normal party finder pace: P3 in about 34 s."),
            Triple(1, "Quality PF", "The bots play at a quality party finder pace: P3 in about 28 s."),
            Triple(2, "Optimal PF", "The bots play at an optimal party finder pace: P3 in about 22 s."),
            Triple(P3Plan.RANDOM + 1, "Theoretical", "The bots play a route planner's roles and times: P3 in about 18 s."),
        )
        var y = cy - skills.size * ROW / 2 + 2
        for ((i, name, about) in skills) {
            addRenderableWidget(Button.builder(Component.literal(if (i == P3Plan.skill) "§a§n$name" else name)) {
                P3Plan.chooseSkill(i)
                rebuildWidgets()
            }.tooltip(tip("$about Your jobs become your class's role in it (Roles changes them).")).bounds(right, y, SIDE_W, 20).build())
            y += ROW
        }

        // Under it all, one under each column: the three menus.
        y += GAP - 4
        addRenderableWidget(Button.builder(Component.literal("Roles")) { mc.setScreen(SimRolesScreen()) }
            .tooltip(tip("Your class, and which of each section's jobs are yours or a bot's.")).bounds(left, y, SIDE_W, 20).build())
        addRenderableWidget(Button.builder(Component.literal("Settings")) { mc.setScreen(SimSettingsScreen()) }
            .tooltip(tip("Death ticks, terminals, your speed and your hotbar.")).bounds(cx - RESTART_W / 2, y, RESTART_W, 20).build())
        addRenderableWidget(Button.builder(Component.literal("Teleport")) { mc.setScreen(SimTeleportScreen()) }
            .tooltip(tip("Every place in the arena: section starts, devices, pads, phases, early-enter spots.")).bounds(right, y, SIDE_W, 20).build())
    }

    override fun isPauseScreen(): Boolean = false

    private fun tip(text: String) = Tooltip.create(Component.literal(text))

    private companion object {
        const val RESTART_W = 100
        const val GAP = 10
        const val ROW = 24
        const val SIDE_W = 90
    }
}
