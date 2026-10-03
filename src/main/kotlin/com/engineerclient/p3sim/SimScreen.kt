package com.engineerclient.p3sim

import com.engineerclient.EngineerClient.mc
import com.odtheking.odin.features.ModuleManager
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.layouts.FrameLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import java.util.Locale

/**
 * The sim's menu: Esc > P3 Sim Menu, the SkyBlock Menu star (hotbar), `/p3sim` or the keybind.
 * Start buttons on top of every tab; tabs for your plan (which jobs are yours, the bots' times,
 * presets), the early enters (who, where, when, the leap menu order), every setting, and teleports.
 * Start and teleport buttons close it; everything else saves at once and keeps it open.
 */
class SimScreen : Screen(Component.literal("P3 Sim")) {
    private lateinit var layout: LinearLayout

    override fun init() {
        super.init()
        layout = LinearLayout.vertical().spacing(3)
        layout.defaultCellSetting().alignHorizontallyCenter()

        text("§6§lP3 Sim §8· §7${status()}")
        row(TABS.mapIndexed { i, name -> change(if (i == tab) "§e§n$name" else name, 80) { tab = i } })
        row(Fight.Start.entries.filter { it != Fight.Start.S1 }.map { s -> button(s.label.substringBefore(' '), 36) { server { Fight.start(s) } } } +
            button("§aRestart", 46) { server { Fight.start(Fight.lastStart) } } +
            button("§cStop", 34) { server { Fight.end() } })
        when (tab) {
            0 -> planTab()
            1 -> earlyEnterTab()
            2 -> settingsTab()
            else -> teleportTab()
        }

        layout.visitWidgets(this::addRenderableWidget)
        repositionElements()
    }

    // ------------------------------------------------------------------ tabs

    private fun planTab() {
        row(listOf<AbstractWidget>(label("§eSkill", 40)) +
            P3Plan.SKILLS.mapIndexed { i, name -> change(if (i == P3Plan.skill) "§a§n$name" else name, 70) { P3Plan.chooseSkill(i) } } +
            change("Class: ${Roles.label(P3Sim.myClass)}", 90) { P3Sim.classS.value = (P3Sim.classS.value + 1) % 5 })
        // The roles, by class (yours highlighted).
        val roles = P3Plan.preset().roles
        for ((c, role) in roles) text((if (c == P3Sim.myClass) "§b§l${Roles.label(c)} §b(you)§7: §f" else "§7${Roles.label(c)}: §8") + role)
        text("§7Click: yours §a✔§7 or a bot's (letter: which). §8* stack: yours if it's in your role.")
        for (s in 1..4) {
            val jobs = P3Plan.jobsIn(s)
            row(listOf<AbstractWidget>(label("§6§lS$s", 18)) + jobs.map { job ->
                val stack = if (P3Plan.isStack(job)) "*" else ""
                val text = if (P3Plan.isMine(job)) "§a✔ ${short(job)}$stack" else "§7${short(job)}$stack §8${P3Plan.doer(job)?.let { Roles.label(it).take(1) } ?: "?"}"
                change(text, 48) { P3Plan.toggle(job) }
            })
        }
        val extra = mutableListOf<AbstractWidget>(
            change("Reset to my role", 100) { P3Plan.resetMine() },
            change("Bots: ${onOff(P3Sim.bots)}", 64) { P3Sim.botsS.value = !P3Sim.bots },
        )
        if (P3Plan.skill == P3Plan.RANDOM) extra += listOf(
            label("§eBot times", 56),
            change("-", 16) { P3Plan.botMin = (P3Plan.botMin - 0.5).coerceAtLeast(0.0); P3Plan.save() },
            label("§f${sec(P3Plan.botMin)}", 30),
            change("+", 16) { P3Plan.botMin = (P3Plan.botMin + 0.5).coerceAtMost(P3Plan.botMax); P3Plan.save() },
            change("-", 16) { P3Plan.botMax = (P3Plan.botMax - 0.5).coerceAtLeast(P3Plan.botMin); P3Plan.save() },
            label("§f${sec(P3Plan.botMax)}", 30),
            change("+", 16) { P3Plan.botMax = (P3Plan.botMax + 0.5).coerceAtMost(60.0); P3Plan.save() },
        )
        row(extra)
    }

    private fun earlyEnterTab() {
        text("§7Who early-enters comes from the roles (Plan tab). The bot goes to the spot after its last job;")
        text("§7the others pre-leap onto it. Yours: get there and they leap onto you, one after another.")
        for (ee in P3Plan.earlyEnters) {
            val into = if (ee.into == 5) "core" else "S${ee.into}"
            val who = ee.owner?.let { if (ee.byYou) "§bYou (${Roles.label(it)})" else "§a${Roles.label(it)}" } ?: "§8nobody"
            row(listOf(
                label("§f${ee.label} §8→ $into", 60),
                label(who, 100),
                change("Spot: here", 60) { mc.player?.let { ee.spot = Vec3(round1(it.x), Math.floor(it.y * 100) / 100.0, round1(it.z)) }; P3Plan.save() },
                label("§8${"%.1f, %.1f, %.1f".format(Locale.ROOT, ee.spot.x, ee.spot.y, ee.spot.z)}", 110),
            ))
        }
        row(listOf(
            change("Wait for you: ${onOff(P3Plan.waitForYou)}", 110) { P3Plan.waitForYou = !P3Plan.waitForYou; P3Plan.save() },
            change("-", 16) { P3Plan.leapGap = (P3Plan.leapGap - 0.25).coerceAtLeast(0.05); P3Plan.save() },
            label("§fleaps ${sec(P3Plan.leapGap)} apart", 90),
            change("+", 16) { P3Plan.leapGap = (P3Plan.leapGap + 0.25).coerceAtMost(5.0); P3Plan.save() },
        ))
        row(listOf<AbstractWidget>(label("§eLeap menu", 60), change("Sort: ${if (P3Plan.odinSort) "§bOdin" else "§fCustom"}", 80) { P3Plan.odinSort = !P3Plan.odinSort; P3Plan.save() }) +
            P3Plan.botOrder().mapIndexed { i, c -> change("${i + 1}: ${name(c)}", 76) { P3Plan.cycleSlot(i + 1) } })
        text("§8Leap slot: click to swap with the next. Wait for you: a section's last bot job waits")
        text("§8until you're at your early enter for the next section.")
    }

    private fun settingsTab() {
        row(listOf(
            change("Class: ${CLASS_NAMES[P3Sim.classS.value.coerceIn(0, 4)]}", 100) { P3Sim.classS.value = (P3Sim.classS.value + 1) % 5 },
            change("Bots: ${onOff(P3Sim.bots)}", 70) { P3Sim.botsS.value = !P3Sim.bots },
            change("Death ticks: ${listOf("Off", "Warn", "Masks")[P3Sim.deathTicks]}", 110) { P3Sim.deathTicksS.value = (P3Sim.deathTicks + 1) % 3 },
            change("Stop after P3: ${onOff(P3Sim.p3Only)}", 110) { P3Sim.p3OnlyS.value = !P3Sim.p3Only },
        ))
        row(listOf(
            change("Terminals: ${P3Sim.forcedTerminal?.name?.lowercase() ?: "random"}", 120) { P3Sim.terminalS.value = (P3Sim.terminalS.value + 1) % 7 },
            change("Ping: ${P3Sim.ping}ms", 80) { P3Sim.pingS.value = PINGS[(PINGS.indexOf(P3Sim.ping) + 1).mod(PINGS.size)] },
            change("Lava bounce: ${onOff(P3Sim.lava)}", 110) { P3Sim.lavaS.value = !P3Sim.lava },
            change("Section times: ${onOff(P3Sim.showTimes)}", 110) { P3Sim.showTimesS.value = !P3Sim.showTimes },
        ))
        stepper("Speed", "${P3Sim.speed}", { P3Sim.speedS.value = (P3Sim.speed - 10).coerceAtLeast(100) }, { P3Sim.speedS.value = (P3Sim.speed + 10).coerceAtMost(600) })
        stepper("Goldor kill", "${P3Sim.goldorKill} ticks", { P3Sim.goldorKillS.value = (P3Sim.goldorKill - 1).coerceAtLeast(10) }, { P3Sim.goldorKillS.value = (P3Sim.goldorKill + 1).coerceAtMost(120) })
        stepper("Terminator cooldown", "${P3Sim.termCooldown} ticks", { P3Sim.termCooldownS.value = (P3Sim.termCooldown - 1).coerceAtLeast(1) }, { P3Sim.termCooldownS.value = (P3Sim.termCooldown + 1).coerceAtMost(20) })
        stepper("Terminator spread", "±${P3Sim.termSpread}°", { P3Sim.termSpreadS.value = (P3Sim.termSpread - 0.5).coerceAtLeast(0.0) }, { P3Sim.termSpreadS.value = (P3Sim.termSpread + 0.5).coerceAtMost(15.0) })
        stepper("Breaker refill", "${P3Sim.breakerRefill}/s", { P3Sim.breakerRefillS.value = (P3Sim.breakerRefill - 1).coerceAtLeast(1) }, { P3Sim.breakerRefillS.value = (P3Sim.breakerRefill + 1).coerceAtMost(10) })
        stepper("Breaker blocks back", "${P3Sim.breakerRegen}s", { P3Sim.breakerRegenS.value = (P3Sim.breakerRegen - 0.5).coerceAtLeast(1.0) }, { P3Sim.breakerRegenS.value = (P3Sim.breakerRegen + 0.5).coerceAtMost(30.0) })
        row(listOf(
            change("Real masks: ${onOff(P3Sim.realMasks)}", 100) { P3Sim.realMasksS.value = !P3Sim.realMasks; server { Sim.player?.let { Masks.equip(it) } } },
            change("Start in: ${if (P3Sim.wornMaskS.value == 0) "Spirit" else "Bonzo"}", 90) { P3Sim.wornMaskS.value = 1 - P3Sim.wornMaskS.value; server { Sim.player?.let { Masks.equip(it) } } },
            change("Phoenix pet: ${onOff(P3Sim.phoenix)}", 100) { P3Sim.phoenixS.value = !P3Sim.phoenix },
            change("No melodies: ${onOff(P3Sim.noMelodies)}", 100) { P3Sim.noMelodiesS.value = !P3Sim.noMelodies },
        ))
        row(listOf(
            change("Start on join: ${onOff(P3Sim.autoStart)}", 110) { P3Sim.autoStartS.value = !P3Sim.autoStart },
            button("Reset Items", 80) { server { Sim.player?.let { SimItems.giveHotbar(it, Fight.phase !is P1Maxor && Fight.phase !is P2Storm) } } },
            button("§7Leave", 60) { SimWorld.leave() },
        ))
        text("§8These are also in Odin's click GUI (Engineer Client > P3 Sim).")
    }

    private fun teleportTab() {
        Spots.teleports.chunked(4).forEach { chunk ->
            row(chunk.map { spot -> button(spot.name, 96) { server { Sim.player?.let { Sim.tp(it, spot.x, spot.y, spot.z, spot.yaw, spot.pitch) } } } })
        }
        row(P3Plan.earlyEnters.map { ee -> button("${ee.label} spot", 70) { server { Sim.player?.let { Sim.tp(it, ee.spot.x, ee.spot.y, ee.spot.z) } } } })
    }

    // ------------------------------------------------------------------ pieces

    private fun name(c: com.odtheking.odin.utils.skyblock.dungeon.DungeonClass) = c.name.lowercase().replaceFirstChar { it.uppercase() }

    /** "S1 T1" -> "T1", "S1 east lever" -> "E lever", "gate 2" -> "Gate". */
    private fun short(job: String): String {
        if (job.startsWith("gate")) return "Gate"
        val s = job.substringAfter(' ')
        return when {
            s.endsWith(" lever") -> s.first().uppercase() + " lever"
            else -> s
        }
    }

    private fun round1(v: Double) = Math.round(v * 10) / 10.0

    private fun sec(v: Double) = "%.1fs".format(Locale.ROOT, v)

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

    private fun text(t: String) { layout.addChild(StringWidget(Component.literal(t), font)) }

    private fun row(widgets: List<AbstractWidget>) {
        val r = layout.addChild(LinearLayout.horizontal().spacing(2))
        widgets.forEach { r.addChild(it) }
    }

    private fun label(t: String, w: Int) = StringWidget(w, 20, Component.literal(t), font)

    private fun stepper(name: String, value: String, down: () -> Unit, up: () -> Unit) =
        row(listOf(label("§7$name", 110), change("-", 16, down), label("§f$value", 70), change("+", 16, up)))

    /** Closes the menu and does it. */
    private fun button(label: String, w: Int, run: () -> Unit): Button =
        Button.builder(Component.literal(label)) { onClose(); run() }.width(w).build()

    /** Changes something: saved, and the menu stays open (rebuilt) to show it. */
    private fun change(label: String, w: Int, run: () -> Unit): Button =
        Button.builder(Component.literal(label)) {
            run()
            ModuleManager.saveConfigurations()
            rebuildWidgets()
        }.width(w).build()

    private fun server(run: () -> Unit) = SimServer.run("menu") { run() }

    override fun repositionElements() {
        layout.arrangeElements()
        FrameLayout.centerInRectangle(layout, rectangle)
    }

    override fun isPauseScreen(): Boolean = false

    companion object {
        private val TABS = listOf("Plan", "Early Enters", "Settings", "Teleport")
        private val CLASS_NAMES = listOf("Healer", "Berserk", "Archer", "Tank", "Mage")
        private val PINGS = listOf(0, 50, 100, 150, 200, 300)

        /** The tab you were on (kept between openings). */
        private var tab = 0
    }
}
