package com.engineerclient.p3sim

import com.engineerclient.EngineerClient.mc
import com.odtheking.odin.features.ModuleManager
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.layouts.FrameLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * The sim's menu (the SkyBlock Menu star, `/p3sim` or the keybind): start any phase or section,
 * teleport anywhere that matters, and the few settings worth changing mid-practice. One click does
 * it and closes the menu; settings stay open so you can flip several.
 */
class SimScreen : Screen(Component.literal("P3 Sim")) {
    private lateinit var layout: LinearLayout

    override fun init() {
        super.init()
        layout = LinearLayout.vertical().spacing(4)
        layout.defaultCellSetting().alignHorizontallyCenter()

        layout.addChild(StringWidget(Component.literal("§6§lP3 Sim §8· §7${status()}"), font))
        layout.addChild(StringWidget(Component.literal("§7Your role: §f${Party.myRole.label}"), font))
        Party.myJobs().chunked(2).forEach { layout.addChild(StringWidget(Component.literal("§7" + it.joinToString(" §8|§7 ")), font)) }

        label("§eStart")
        row(Fight.Start.entries.filter { it != Fight.Start.S1 }.map { s -> button(s.label.substringBefore(' '), 40) { server { Fight.start(s) } } } +
            button("§aRestart", 50) { server { Fight.start(Fight.lastStart) } } +
            button("§cStop", 40) { server { Fight.end() } })

        label("§eTeleport")
        Spots.teleports.chunked(5).forEach { chunk ->
            row(chunk.map { spot -> button(spot.name, 92) { server { Sim.player?.let { Sim.tp(it, spot.x, spot.y, spot.z, spot.yaw, spot.pitch) } } } })
        }

        label("§eSettings")
        row(listOf(
            setting("Role: ${Party.myRole.label}", 110) { P3Sim.roleS.value = (P3Sim.roleS.value + 1) % 5 },
            setting("Bots: ${onOff(P3Sim.bots)}", 70) { P3Sim.botsS.value = !P3Sim.bots },
            setting("Death ticks: ${listOf("Off", "Warn", "Masks")[P3Sim.deathTicks]}", 120) { P3Sim.deathTicksS.value = (P3Sim.deathTicks + 1) % 3 },
            setting("Stop after P3: ${onOff(P3Sim.p3Only)}", 110) { P3Sim.p3OnlyS.value = !P3Sim.p3Only },
        ))
        row(listOf(
            setting("Terminals: ${P3Sim.forcedTerminal?.name?.lowercase() ?: "random"}", 130) { P3Sim.terminalS.value = (P3Sim.terminalS.value + 1) % 7 },
            setting("Ping: ${P3Sim.ping}ms", 80) { P3Sim.pingS.value = PINGS[(PINGS.indexOf(P3Sim.ping) + 1).mod(PINGS.size)] },
            button("Reset Items", 80) { server { Sim.player?.let { SimItems.giveHotbar(it, Fight.phase !is P1Maxor && Fight.phase !is P2Storm) } } },
            button("§7Leave", 60) { SimWorld.leave() },
        ))
        layout.addChild(StringWidget(Component.literal("§8Role changes apply from the next start."), font))

        layout.visitWidgets(this::addRenderableWidget)
        repositionElements()
    }

    private fun onOff(b: Boolean) = if (b) "§aON" else "§cOFF"

    private fun status(): String {
        val p = Fight.phase ?: return "idle"
        val s = when (p) {
            is GoldorPhase -> p.status()
            is P1Maxor -> p.status()
            is P2Storm -> p.status()
            is P4Necron -> p.status()
            else -> p.name
        }
        return "§f${p.name}§7 ${s} §8· §7${Masks.status()}"
    }

    private fun label(text: String) { layout.addChild(StringWidget(Component.literal(text), font)) }

    private fun row(buttons: List<Button>) {
        val r = layout.addChild(LinearLayout.horizontal().spacing(3))
        buttons.forEach { r.addChild(it) }
    }

    private fun button(label: String, w: Int, run: () -> Unit): Button =
        Button.builder(Component.literal(label)) { onClose(); run() }.width(w).build()

    /** A setting: saved, and the menu stays open (rebuilt) to show it. */
    private fun setting(label: String, w: Int, change: () -> Unit): Button =
        Button.builder(Component.literal(label)) {
            change()
            ModuleManager.saveConfigurations()
            mc.setScreen(SimScreen())
        }.width(w).build()

    private fun server(run: () -> Unit) = SimServer.run("menu") { run() }

    override fun repositionElements() {
        layout.arrangeElements()
        FrameLayout.centerInRectangle(layout, rectangle)
    }

    override fun isPauseScreen(): Boolean = false

    companion object {
        private val PINGS = listOf(0, 50, 100, 150, 200, 300)
    }
}
