package com.engineerclient.terminals

import com.engineerclient.EngineerClient.mc
import com.engineerclient.practice.TermInfo
import com.adm.events.EntityEvent
import com.adm.events.LevelEvent
import com.adm.events.MessageEvent
import com.adm.events.RenderExtractEvent
import com.adm.events.core.EventBus
import com.adm.events.core.on
import com.adm.utils.Colors
import com.adm.utils.addVec
import com.adm.utils.containsOneOf
import com.adm.utils.render.*
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.adm.utils.skyblock.dungeon.M7Phases
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.AABB

/** What's left in P3: waypoints on inactive terminals, devices and levers, and each section's count. */
object TermStatus {
    enum class Show(private val label: String, val terminals: Boolean, val devices: Boolean, val levers: Boolean) {
        All("All", true, true, true),
        TermsAndLevers("Terms & Levers", true, false, true),
        Terminals("Terminals", true, false, false),
        Devices("Devices", false, true, false),
        Levers("Levers", false, false, true);
        override fun toString() = label
    }

    enum class Look(private val label: String, val box: Boolean, val text: Boolean, val beacon: Boolean) {
        Full("Box, Text & Beacon", true, true, true),
        BoxText("Box & Text", true, true, false),
        Box("Box", true, false, false),
        Text("Text", false, true, false);
        override fun toString() = label
    }

    private enum class Kind(val label: String?) {
        Terminal("Terminal"), Device("Device"), Lever("Lever"), Other(null);

        val shown get() = when (this) {
            Terminal -> Terminals.waypointsFor.terminals
            Device -> Terminals.waypointsFor.devices
            Lever -> Terminals.waypointsFor.levers
            Other -> false
        }

        companion object {
            fun of(name: String): Kind? = when (name) {
                "Inactive Terminal" -> Terminal
                "Inactive" -> Device
                "Not Activated" -> Lever
                else -> if (name.containsOneOf("Inactive", "Not Activated", "CLICK HERE", ignoreCase = true)) Other else null
            }
        }
    }

    private val inactive = hashMapOf<ArmorStand, Kind>()
    private var firstInSection = false
    private var shouldRender = false
    private var isComplete = false
    private var lastCompleted = 0
    private var device = false
    private var terminals = 0
    private var gate = false
    private var section = 1
    private var levers = 0
    private var wasOn = false
    private var namesHidden = false

    private val completedRegex = Regex("^(.{1,16}) (activated|completed) a (terminal|lever|device)! \\((\\d)/(\\d)\\)$")
    private val goldorRegex = Regex("^\\[BOSS] Goldor: Who dares trespass into my domain\\?$")
    private val coreRegex = Regex("^The Core entrance is opening!$")
    private val gateRegex = Regex("^The gate has been destroyed!$")

    fun drawInfo(g: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int> {
        if (!example && !(Terminals.statusOn && DungeonUtils.inBoss && shouldRender)) return 0 to 0
        val width = g.textDim("§6Levers ${if (levers == 2) "§a" else "§c"}${levers}§8/§a2", 0, 0, Colors.WHITE).first
        val needed = if (section == 2) 5 else 4
        g.text("§6Terms ${if (terminals == needed) "§a" else "§c"}${terminals}§8/§a$needed", 0, 9, Colors.WHITE)
        g.text("§6Device ${if (device) "§a✔" else "§c✘"}", 0, 18, Colors.WHITE)
        g.text("§6Gate ${if (gate) "§a✔" else "§c✘"}", 0, 27, Colors.WHITE)
        return width to 36
    }

    fun drawSection(g: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int> {
        val n = if (example) 1 else if (!Terminals.statusOn) return 0 to 0 else TermInfo.sectionNumber() ?: return 0 to 0
        return g.line("§5Section: §f$n")
    }

    fun drawProgress(g: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int> {
        val (done, total) = if (example) 3 to 7 else if (!Terminals.statusOn) return 0 to 0 else TermInfo.sectionProgress() ?: return 0 to 0
        if (!Terminals.colorProgress) return g.line("§a(§c$done§a/$total)")
        val c = when {
            done >= total -> "§6§l"
            total - done == 1 -> "§a"
            done >= 3 -> "§e"
            else -> "§c"
        }
        return g.line("§f($c$done§f/§a$total§f)")
    }

    private fun GuiGraphicsExtractor.line(s: String): Pair<Int, Int> {
        text(s, 0, 0, Colors.WHITE, true)
        return mc.font.width(s) to 9
    }

    fun install() {
        on<EntityEvent.SetData> {
            if (!Terminals.statusOn) return@on
            val stand = entity as? ArmorStand ?: return@on
            if (DungeonUtils.getF7Phase() == M7Phases.P3) track(stand)
        }
        on<EntityEvent.Remove> { (entity as? ArmorStand)?.let { inactive.remove(it) } }

        on<MessageEvent.Chat> {
            if (!DungeonUtils.inBoss) return@on
            when {
                completedRegex.matches(message) -> {
                    val m = completedRegex.find(message) ?: return@on
                    val completed = (m.groupValues[4].toIntOrNull() ?: 0).also { if (it == 1) firstInSection = true }
                    if (completed == (m.groupValues[5].toIntOrNull() ?: 0)) {
                        if (gate) newSection() else isComplete = true
                        return@on
                    }
                    when (m.groupValues[3]) {
                        "lever" -> levers++
                        "terminal" -> terminals++
                        "device" -> if (!firstInSection || lastCompleted != completed) device = true
                    }
                    lastCompleted = completed
                }
                gateRegex.matches(message) -> { gate = true; if (isComplete) newSection() }
                goldorRegex.matches(message) -> { shouldRender = true; resetState() }
                coreRegex.matches(message) -> { inactive.clear(); shouldRender = false; resetState() }
            }
        }

        on<LevelEvent.Load> { inactive.clear(); shouldRender = false; resetState() }

        on<RenderExtractEvent> {
            val on = Terminals.statusOn
            if (on != wasOn) {
                wasOn = on
                // Stands named while Status was off were never tracked.
                if (on && DungeonUtils.getF7Phase() == M7Phases.P3) mc.level?.entitiesForRendering()?.forEach { (it as? ArmorStand)?.let(::track) }
            }
            if (inactive.isEmpty()) return@on
            val hide = on && Terminals.hideDefaultNames
            if (hide != namesHidden) {
                namesHidden = hide
                for (stand in inactive.keys) stand.isCustomNameVisible = !hide
            }
            if (!on || !Terminals.waypoints || DungeonUtils.getF7Phase() != M7Phases.P3) return@on
            inactive.forEach { (stand, kind) ->
                val label = kind.label?.takeIf { kind.shown } ?: return@forEach
                val look = Terminals.waypointStyle
                if (look.box) {
                    val box = if (kind == Kind.Lever) AABB.unitCubeFromLowerCorner(stand.position().addVec(-0.5, z = -0.5)) else stand.boundingBox
                    drawWireFrameBox(box, Terminals.waypointColor, depth = !Terminals.waypointsThroughWalls)
                }
                if (look.text) drawText(label, stand.position().addVec(y = 2.0), 1.5f, true)
                if (look.beacon) drawBeaconBeam(stand.blockPosition(), Terminals.waypointColor)
            }
        }
        EventBus.subscribe(this)
    }

    private fun track(stand: ArmorStand) {
        val kind = Kind.of(stand.name.string) ?: return run { inactive.remove(stand) }
        inactive[stand] = kind
        if (Terminals.hideDefaultNames) stand.isCustomNameVisible = false
    }

    private fun resetState() {
        firstInSection = false; lastCompleted = 0; isComplete = false; device = false
        terminals = 0; gate = false; section = 1; levers = 0
    }

    private fun newSection() {
        firstInSection = false; isComplete = false; device = false
        terminals = 0; gate = false; levers = 0; section++
    }
}
