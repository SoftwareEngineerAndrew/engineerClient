package com.engineerclient.p3sim

import com.engineerclient.index
import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.odtheking.odin.features.ModuleManager
import net.minecraft.client.gui.components.AbstractSliderButton
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.layouts.FrameLayout
import net.minecraft.client.gui.layouts.LayoutElement
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.util.Locale

/**
 * The main menu's own menus ([SimRestartScreen]: Roles, Settings, Teleport): rows centred on the
 * screen, a tooltip on everything, Back (or Esc) to the main menu. A change saves at once and
 * shows in place; a teleport closes the menu.
 */
abstract class SimSubmenu(name: String) : Screen(Component.literal(name)) {
    private lateinit var layout: LinearLayout

    /** The rows under the title. */
    protected abstract fun build()

    /** Rows start at one left edge (a form: labels on the left, lined up), not each centred. */
    protected open val leftAligned = false

    override fun init() {
        super.init()
        layout = LinearLayout.vertical().spacing(3)
        layout.defaultCellSetting().alignHorizontallyCenter()
        text("§6§lP3 Sim §8· §e${title.string}")
        build()
        layout.addChild(Button.builder(Component.literal("Back")) { onClose() }.tooltip(tip("Back to the P3 Sim menu (Esc does it too).")).width(80).build())
        layout.visitWidgets(this::addRenderableWidget)
        repositionElements()
    }

    /** Esc and Back: the main menu, not the game. */
    override fun onClose() = mc.gui.setScreen(SimRestartScreen())

    override fun repositionElements() {
        layout.arrangeElements()
        FrameLayout.centerInRectangle(layout, rectangle)
    }

    override fun isPauseScreen(): Boolean = false

    // ------------------------------------------------------------------ pieces

    protected fun text(t: String) { layout.addChild(StringWidget(Component.literal(t), font)) }

    protected fun row(widgets: List<LayoutElement>) {
        val r = LinearLayout.horizontal().spacing(2)
        if (leftAligned) layout.addChild(r) { it.alignHorizontallyLeft() } else layout.addChild(r)
        widgets.forEach { r.addChild(it) }
    }

    protected fun tip(text: String) = Tooltip.create(Component.literal(text))

    /** Text centred in a cell [w] wide, [about] on hover. */
    protected fun label(t: String, w: Int, about: String? = null): LayoutElement {
        val cell = FrameLayout().setMinDimensions(w, 20)
        if (t.isNotEmpty()) cell.addChild(StringWidget(Component.literal(t), font).also { if (about != null) it.setTooltip(tip(about)) })
        return cell
    }

    /** Changes something: saved, and the menu stays open (rebuilt) to show it. */
    protected fun change(label: String, w: Int, about: String, run: () -> Unit): Button =
        Button.builder(Component.literal(label)) {
            run()
            ModuleManager.saveConfigurations()
            rebuildWidgets()
        }.tooltip(tip(about)).width(w).build()

    /** Closes the menu, back in the game, and does it. */
    protected fun act(label: String, w: Int, about: String, run: () -> Unit): Button =
        Button.builder(Component.literal(label)) { mc.gui.setScreen(null); run() }.tooltip(tip(about)).width(w).build()

    /** One of a few choices: the chosen one green and underlined. */
    protected fun pick(name: String, on: Boolean) = if (on) "§a§n$name" else name

    protected fun onOff(b: Boolean) = if (b) "§aON" else "§cOFF"

    protected fun server(run: () -> Unit) = SimServer.run("menu") { run() }
}

/**
 * Roles: your class, and each section's jobs in fixed columns (terminals 1-5, then the left and right
 * lever, the device, the gate), the same in every section. Each says who does it: you (green), or the
 * bot's class; a click makes it yours or the bot's.
 */
class SimRolesScreen : SimSubmenu("Roles") {
    override fun build() {
        val skill = P3Plan.skillName()
        val roles = P3Plan.preset().roles
        text("§7$skill §8· §7your role as ${Roles.label(P3Sim.myClass)}: §f${roles[P3Sim.myClass] ?: "none"}")
        // Your class: a bot plays each of the other four.
        row(listOf(label("§eYour class", LABEL_W, P3Sim.classS.description)) + Party.CLASSES.mapIndexed { i, c ->
            change(pick(Roles.label(c), c == P3Sim.myClass), 54,
                "Play as ${Roles.label(c)}: your jobs become its $skill role (${roles[c] ?: "none"}); a bot plays each of the other classes.") {
                P3Sim.classS.index = i
            }
        })

        row(listOf(label("", LABEL_W)) + COLUMNS.map { (head, about) -> label("§e$head", JOB_W, about) })
        for (s in 1..4) {
            val cells = arrayOfNulls<String>(COLUMNS.size)
            for (job in P3Plan.jobsIn(s)) cells[column(job)] = job
            row(listOf(label("§6§lS$s", LABEL_W, "Section $s's jobs.")) + cells.map { job -> if (job == null) label("", JOB_W) else jobButton(job) })
        }
        text("§aYou§7: yours  §8·  §7a class: its bot does it  §8·  §e*§7: a stack (two roles have it)")
        row(listOf(change("Reset to my role", 110, "Your jobs back to the ${Roles.label(P3Sim.myClass)}'s $skill role, as if you had never clicked one.") { P3Plan.resetMine() }))

        // Spots, for this skill and class only: stand there, look the way you want, click.
        val me = Roles.label(P3Sim.myClass)
        text("§eSpots §8· §7as $me in $skill §8(green: set; stand there, look, click)")
        val spawn = P3Plan.customSpot("spawn")
        row(listOf(
            label("§eSpawn", LABEL_W, "Where Restart P3 puts you."),
            change(if (spawn != null) "§aSet here" else "Set here", 80,
                "Restart P3 puts you where you stand now, facing as you are (as $me in $skill). Now: ${spawn?.let { at(it) } ?: "your first S1 job's spot"}.") { here()?.let { P3Plan.setCustomSpot("spawn", it) } },
            change("Default", 60, "Back to your first S1 job's spot (as $me in $skill).") { P3Plan.setCustomSpot("spawn", null) },
        ))
        row(listOf<LayoutElement>(label("§eEarly enters", LABEL_W, "Where each early enter stands, whoever does it.")) + P3Plan.earlyEnters.map { ee ->
            val set = P3Plan.customSpot(ee.key) != null
            val who = ee.owner?.let { if (ee.byYou) "you" else "the ${Roles.label(it)} bot" } ?: "nobody in $skill"
            change(if (set) "§a${ee.label}" else ee.label, 48,
                "${ee.label} (${who}) stands where you stand now, facing as you are (as $me in $skill). Now: ${at(P3Plan.eeSpot(ee))}.") { here()?.let { P3Plan.setCustomSpot(ee.key, it) } }
        } + change("Defaults", 60, "Every early enter back to its usual spot (as $me in $skill).") { P3Plan.earlyEnters.forEach { P3Plan.setCustomSpot(it.key, null) } })
    }

    /** Where you stand and look now (a tenth of a block; y to the hundredth, so a slab's height stays). */
    private fun here(): Spots.Spot? = mc.player?.let {
        Spots.Spot("set", Math.round(it.x * 10) / 10.0, Math.floor(it.y * 100) / 100.0, Math.round(it.z * 10) / 10.0, it.yRot, it.xRot)
    }

    private fun at(p: Spots.Spot) = "%.1f, %.1f, %.1f".format(Locale.ROOT, p.x, p.y, p.z)

    private fun jobButton(job: String): Button {
        val mine = P3Plan.isMine(job)
        val doer = P3Plan.doer(job)
        val stack = if (P3Plan.isStack(job)) "§e*" else ""
        val text = if (mine) "§aYou$stack" else "§7${doer?.let { Roles.label(it).take(4) } ?: "§8any"}$stack"
        val owners = P3Plan.plan().owners[job].orEmpty()
        val who = when {
            mine -> "Yours."
            doer != null -> "The ${Roles.label(doer)} bot does it."
            else -> "Whichever bot is least busy does it."
        }
        val shared = if (owners.size > 1) " A stack: in the ${owners.joinToString(" and ") { Roles.label(it) }} roles (yours if one is you, else the first one's)." else ""
        val click = if (mine) "Click: the bot's." else "Click: yours."
        return change(text, JOB_W, "${jobName(job)}. $who$shared $click") { P3Plan.toggle(job) }
    }

    private companion object {
        const val LABEL_W = 60
        const val JOB_W = 40

        /** Terminals 1-5, then L, R, D, G, as [P3Plan.short] names them. */
        val COLUMNS = (1..5).map { "$it" to "Terminal $it." } + listOf(
            "L" to "The left lever, coming in along the track (S1 west, S2 high, S3 west, S4 low).",
            "R" to "The right lever (S1 east, S2 low, S3 east, S4 high).",
            "D" to "The section's device: S1 Simon Says, S2 Lights, S3 Arrows, S4 Target (i4).",
            "G" to "The section's gate: blown with Superboom or a Dungeonbreaker (or open by itself 5 s after the section ends).",
        )

        fun column(job: String): Int = when (val short = P3Plan.short(job)) {
            "L" -> 5; "R" -> 6; "D" -> 7; "G" -> 8
            else -> ((short.toIntOrNull() ?: 1) - 1).coerceIn(0, 4)
        }

        /** "S1 T2" -> "S1 terminal 2", "gate 3" -> "S3 gate", "S2 Lights" -> "S2 device (Lights)"; levers as they are. */
        fun jobName(job: String): String {
            if (job.startsWith("gate ")) return "S${job.removePrefix("gate ")} gate"
            val s = job.substringBefore(' ')
            return when (P3Plan.short(job)) {
                "L", "R", "G" -> job
                "D" -> "$s device (${job.substringAfter(' ').let { if (it == "SS") "Simon Says" else it }})"
                else -> "$s terminal ${P3Plan.short(job)}"
            }
        }
    }
}

/**
 * Settings: death ticks, terminals (random, with or without melodies, or every one the same type),
 * your speed and your hotbar. The rest are in the full menu and Odin's click GUI.
 */
class SimSettingsScreen : SimSubmenu("Settings") {
    override val leftAligned = true

    override fun build() {
        row(listOf(label("§eDeath Ticks", LABEL_W, P3Sim.deathTicksS.description)) + DEATH_TICKS.mapIndexed { i, (name, about) ->
            change(pick(name, P3Sim.deathTicks == i), 60, about) { P3Sim.deathTicksS.index = i }
        })

        // Terminals: random (melodies or not) or one type for all, the type below (greyed while random).
        val forced = P3Sim.forcedTerminal
        if (forced != null) lastType = forced
        row(listOf(
            label("§eTerminals", LABEL_W, P3Sim.terminalS.description),
            change(pick("Random", forced == null), 60, "Each terminal a random type, as on Hypixel.") { P3Sim.terminalS.index = 0 },
            change(pick("Specific", forced != null), 60, "Every terminal the one type chosen below.") { P3Sim.terminalS.index = lastType.ordinal + 1 },
            change("No Melodies: ${onOff(P3Sim.noMelodies)}", 100, P3Sim.noMelodiesS.description) { P3Sim.noMelodiesS.value = !P3Sim.noMelodies }
                .also { it.active = forced == null },
        ))
        row(listOf(label("", LABEL_W)) + Terminals.Type.entries.map { t ->
            change(pick(TYPE_NAMES.getValue(t).first, forced == t), 62, TYPE_NAMES.getValue(t).second) { lastType = t; P3Sim.terminalS.index = t.ordinal + 1 }
                .also { it.active = forced != null }
        })

        row(listOf(
            label("§eSpeed", LABEL_W, P3Sim.speedS.description),
            change("-", 16, "10 less speed.") { setSpeed(P3Sim.speed - 10) },
            SpeedSlider(180),
            change("+", 16, "10 more speed.") { setSpeed(P3Sim.speed + 10) },
        ))

        // Your hotbar: P1/P2's or P3's, whichever part of the fight you are in (HotbarLayout).
        val p3Part = Fight.phase !is P1Maxor && Fight.phase !is P2Storm
        val part = if (p3Part) "P3" else "P1/P2"
        val saved = HotbarLayout.has(p3Part)
        row(listOf(
            label("§eHotbar", LABEL_W, "Your own item layout: every $part hotbar reset (a start, Reset Items) lays your items out as you saved them." +
                if (saved) " Saved." else " Not saved yet: the sim's default."),
            act("Save $part Hotbar", 120, "Saves where your items are right now (hotbar and inventory), the slot you hold, what you wear and your pet. Arrange them first.") {
                server { Sim.player?.let { EngineerClient.msg(HotbarLayout.save(it, p3Part)) } }
            },
            act("Default", 60, "Forgets your saved $part layout: its resets go back to the sim's default.") {
                server { EngineerClient.msg(HotbarLayout.reset(p3Part)) }
            }.also { it.active = saved },
        ))
    }

    private fun setSpeed(v: Int) {
        val s = v.coerceIn(MIN_SPEED, MAX_SPEED)
        if (s == P3Sim.speed) return
        P3Sim.speedS.value = s
        // Now, not at the next start: Black Cat's +100 and the speed cap come with it (Fight.applySpeed).
        server { Sim.player?.let { Fight.applySpeed(it) } }
    }

    /** Odin's setting saves when the menu closes; dragging the slider would write it every step. */
    override fun removed() {
        super.removed()
        ModuleManager.saveConfigurations()
    }

    /** Your speed, 100 to 750 in steps of 10. */
    private inner class SpeedSlider(w: Int) : AbstractSliderButton(0, 0, w, 20, Component.empty(), (P3Sim.speed - MIN_SPEED) / (MAX_SPEED - MIN_SPEED).toDouble()) {
        init {
            updateMessage()
            setTooltip(tip(P3Sim.speedS.description))
        }
        private fun speed() = MIN_SPEED + Math.round(value * (MAX_SPEED - MIN_SPEED) / 10).toInt() * 10
        override fun updateMessage() {
            message = Component.literal("Speed: ${speed()}" + if (P3Sim.phoenix) " §8(Phoenix out)" else " §8(${speed() + 100} with Black Cat)")
        }
        override fun applyValue() = setSpeed(speed())
    }

    private companion object {
        const val LABEL_W = 64
        const val MIN_SPEED = 100
        const val MAX_SPEED = 750

        /** The type Specific picks: the one last chosen or set. */
        var lastType = Terminals.Type.ORDER

        val DEATH_TICKS = listOf(
            "Off" to "No death ticks: being ahead of Goldor never hurts.",
            "Warn" to "A death tick only says so: Goldor's line and a title. Nothing is used up and you don't die.",
            "Masks" to "As on Hypixel: your Spirit Mask, Bonzo's Mask and Phoenix save you; with none left you die (back to the section's start).",
        )

        val TYPE_NAMES = mapOf(
            Terminals.Type.ORDER to ("Order" to "Click in order!: the panes 1 to 14 in order."),
            Terminals.Type.PANES to ("Panes" to "Correct all the panes!: every red pane to green."),
            Terminals.Type.RUBIX to ("Rubix" to "Change all to same color!: every pane to one colour."),
            Terminals.Type.STARTS to ("Starts With" to "What starts with: 'X'?: every item whose name starts with the letter."),
            Terminals.Type.SELECT to ("Select" to "Select all the X items!: every item of the colour."),
            Terminals.Type.MELODY to ("Melody" to "Melody: each row's button as the pane passes it."),
        )
    }
}

/** Teleport: every place in the arena ([Spots]), in groups, and the early-enter spots. A click closes the menu and takes you there. */
class SimTeleportScreen : SimSubmenu("Teleport") {
    override fun build() {
        for ((group, spots) in Spots.teleportGroups) {
            text("§e$group")
            spots.chunked(4).forEach { chunk ->
                row(chunk.map { spot ->
                    act(spot.name, SPOT_W, "To ${spot.name}: ${xyz(spot.x, spot.y, spot.z)}, facing ${facing(spot.yaw)}.") { tp(spot.x, spot.y, spot.z, spot.yaw, spot.pitch) }
                })
            }
        }
        text("§eEarly Enters")
        row(P3Plan.earlyEnters.map { ee ->
            val who = ee.owner?.let { if (ee.byYou) "yours" else "the ${Roles.label(it)}'s" } ?: "nobody's in this skill"
            val s = P3Plan.eeSpot(ee)
            act(ee.label, 60, "To the ${ee.label} spot (${who}): ${xyz(s.x, s.y, s.z)}. Set it in Roles.") {
                tp(s.x, s.y, s.z, s.yaw, s.pitch)
            }
        })
    }

    private fun tp(x: Double, y: Double, z: Double, yaw: Float?, pitch: Float?) = server {
        GhostCapture.invalidate("a menu teleport")
        Sim.player?.let { Sim.tp(it, x, y, z, yaw, pitch) }
    }

    private fun xyz(x: Double, y: Double, z: Double) = "%.1f, %.1f, %.1f".format(Locale.ROOT, x, y, z)

    private fun facing(yaw: Float) = when (Math.floorMod(Math.round(yaw / 90f), 4)) { 0 -> "south"; 1 -> "west"; 2 -> "north"; else -> "east" }

    private companion object {
        /** Wide enough for the longest name, "Red pad (P3 drop)". */
        const val SPOT_W = 100
    }
}
