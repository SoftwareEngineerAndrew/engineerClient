package com.engineerclient.dungeon

import com.engineerclient.practice.TermInfo
import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.ActionSetting
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.ListSetting
import com.adm.clickgui.settings.impl.NumberSetting
import com.adm.events.LevelEvent
import com.adm.events.MessageEvent
import com.adm.events.RenderExtractEvent
import com.adm.events.TickEvent
import com.adm.events.core.on
import com.adm.features.Category
import com.adm.features.Module
import com.adm.utils.Color
import com.adm.utils.Colors
import com.adm.utils.render.drawCylinder
import com.adm.utils.render.drawText
import com.adm.utils.render.drawWireFrameBox
import com.adm.utils.render.text
import com.adm.utils.sendCommand
import com.adm.utils.skyblock.dungeon.DungeonClass
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.IdentityHashMap

object PositionalMessages : Module(
    name = "Positional Messages",
    category = Category.BOSS,
    description = "Says where you are in P3 in party chat, and shows teammates' locations on screen.",
    key = null,
) {
    private val positional by BooleanSetting("Positional Messages", true, desc = "Says in party chat when you reach SS, an early enter, core or tunnel.")
    private val locationAlerts by BooleanSetting("Location Alerts", true, desc = "Shows teammates' \"At ...\" party messages on screen.")
    private val hideOwn by BooleanSetting("Hide Your Own", true, desc = "No location alert for your own messages.").withDependency { locationAlerts }
    private val alertTicks by NumberSetting("Alert Duration", 15, 1..20, 1, desc = "How long a location alert stays, in client ticks.").withDependency { locationAlerts }
    private val soundRepeats by NumberSetting("Sound Repeats", 10, 0..20, 1, desc = "How many times the alert sound plays.").withDependency { locationAlerts }
    private val sound = SoundOption(this, "minecraft:block.note_block.pling")

    private val customOn by BooleanSetting("Custom Messages", true, desc = "Your own messages from /posmsg, said in party chat when you reach their spot.")
    private val onlyDungeons by BooleanSetting("Only In Dungeons", true, desc = "Custom messages only in dungeons.").withDependency { customOn }
    private val oncePerWorld by BooleanSetting("Once Per World", false, desc = "Each custom message at most once per world.").withDependency { customOn }
    private val showPositions by BooleanSetting("Show Positions", true, desc = "Draws the custom messages' spots.").withDependency { customOn }
    private val throughWalls by BooleanSetting("Through Walls", false, desc = "Spots show through walls.").withDependency { customOn && showPositions }
    private val showText by BooleanSetting("Show Message Text", true, desc = "The message over its spot.").withDependency { customOn && showPositions }
    private val manager by ActionSetting("Open Message Manager", desc = "Add, edit and remove custom messages.") {
        val previous = mc.gui.screen()
        mc.schedule { mc.gui.setScreen(PosMsgScreen(previous)) }
    }.withDependency { customOn }

    /** A spot within [distance] of x y z, or with x2 y2 z2 the box between the corners. Delay in ms. */
    class PosMsg(
        var x: Double, var y: Double, var z: Double,
        var x2: Double? = null, var y2: Double? = null, var z2: Double? = null,
        var delay: Long = 0, var distance: Double? = 1.0,
        var color: String = "WHITE", var message: String = "",
    ) {
        val isBox get() = x2 != null && y2 != null && z2 != null
        fun box() = AABB(x, y, z, x2 ?: x, y2 ?: y, z2 ?: z)
        fun contains(px: Double, py: Double, pz: Double) =
            if (isBox) box().contains(px, py, pz) else Vec3(x, y, z).distanceTo(Vec3(px, py, pz)) <= (distance ?: 0.0)
    }

    val messages by ListSetting("Pos Messages", mutableListOf<PosMsg>())
    private val sent = IdentityHashMap<PosMsg, Boolean>()
    private val due = IdentityHashMap<PosMsg, Long>()

    private val COLORS = mapOf(
        "DARKBLUE" to 0x0000AA, "DARKGREEN" to 0x00AA00, "DARKAQUA" to 0x00AAAA, "DARKRED" to 0xAA0000, "DARKPURPLE" to 0xAA00AA,
        "GOLD" to 0xFFAA00, "GRAY" to 0xAAAAAA, "DARKGRAY" to 0x555555, "BLUE" to 0x5555FF, "GREEN" to 0x55FF55, "AQUA" to 0x55FFFF,
        "RED" to 0xFF5555, "LIGHTPURPLE" to 0xFF55FF, "YELLOW" to 0xFFFF55, "WHITE" to 0xFFFFFF, "BLACK" to 0x000000,
    )
    val colorNames get() = COLORS.keys
    private val colorCache = HashMap<String, Color>()
    private val HEX = Regex("^#?([A-Fa-f0-9]{6}|[A-Fa-f0-9]{8})$")

    /** ARGB, or null for a name or hex this doesn't know. */
    fun parseColor(text: String): Int? {
        HEX.matchEntire(text.trim())?.let { m ->
            val hex = m.groupValues[1]
            return if (hex.length == 6) (0xFF000000 or hex.toLong(16)).toInt() else (hex.substring(6).toLong(16) shl 24 or hex.substring(0, 6).toLong(16)).toInt()
        }
        return COLORS[text.trim().uppercase().replace(" ", "").replace("_", "")]?.let { it or 0xFF000000.toInt() }
    }

    fun changed() {
        sent.clear(); due.clear(); colorCache.clear()
        com.adm.features.ModuleManager.saveConfigurations()
    }

    private fun customTick() {
        if (!customOn || (onlyDungeons && !DungeonUtils.inDungeons)) return
        val player = mc.player ?: return
        val now = System.currentTimeMillis()
        for (m in messages) {
            if (m.contains(player.x, player.y, player.z)) {
                if (sent[m] != true) { sent[m] = true; due[m] = now + m.delay }
                val at = due[m] ?: continue
                if (now >= at) { due.remove(m); if (m.message.isNotBlank()) sendCommand("pc ${m.message}") }
            } else {
                if (due.remove(m) != null || !oncePerWorld) sent[m] = false
            }
        }
    }

    private const val EXAMPLE = "§6Someone§e is At <location>!"
    private val NONE = 0 to 0

    private val locationHud by HUD("Location Alert", "A teammate's location message.", false, 300, 90, 2.1f) { example ->
        when {
            example -> {
                text(EXAMPLE, 0, 0, Colors.WHITE, true)
                mc.font.width(EXAMPLE) to 9
            }
            !locationAlerts || alertTicksLeft <= 0 -> NONE
            else -> {
                text(alert, 0, 0, Colors.WHITE, true)
                alertSize
            }
        }
    }

    // Bit 0 of [phases] is "Storm or terminals", bit n is terminal section n.
    private class Place(val call: String, val box: AABB, val phases: Int, vararg val names: String) {
        var heard = true
        var said = true
    }

    private const val STORM_OR_TERMINALS = 1
    private fun sections(vararg n: Int): Int = n.fold(0) { acc, s -> acc or (1 shl s) }

    private val SS = Place("At SS!", AABB(107.0, 120.0, 93.0, 110.0, 121.0, 95.0), sections(0, 1), "ss")
    private val PLACES = arrayOf(
        SS,
        Place("At EE2!", AABB(57.0, 109.0, 130.0, 59.0, 110.0, 132.0), sections(1, 2), "ee2", "early enter 2"),
        Place("At High EE2!", AABB(59.0, 132.0, 138.0, 62.0, 133.0, 140.0), sections(1, 2), "high ee2", "highee2", "hee2"),
        Place("At EE3!", AABB(1.0, 109.0, 103.0, 3.0, 110.0, 106.0), sections(2, 3), "ee3", "early enter 3", "early entry 3"),
        Place("At Core!", AABB(53.0, 115.0, 51.0, 56.0, 116.0, 54.0), sections(2, 3, 4), "core"),
        Place("Inside Goldor Tunnel!", AABB(52.0, 114.0, 55.0, 57.0, 116.0, 58.0), sections(4, 5), "tunnel"),
        Place("At 2 Safespot!", AABB(46.0, 109.0, 121.987, 49.0, 110.0, 121.988), sections(1, 2), "2 safespot", "ee2 safespot", "safespot ee2", "s2 safespot", "safespot s2"),
        Place("At 3 Safespot!", AABB(18.0, 121.0, 91.0, 19.0, 126.0, 99.0), sections(2, 3), "3 safespot", "ee3 safespot", "freaky ee3", "freak ee", "safespot s3", "safespot 3"),
        Place("At Split ee2!", AABB(58.0, 119.0, 124.0, 60.0, 123.0, 126.0), sections(1, 2), "split ee2", "splitee2", "mage term"),
        Place("At High 2 Safespot!", AABB(57.0, 127.0, 143.7, 70.0, 133.0, 146.7), sections(1, 2), "high 2 safespot", "2 safespot"),
    )

    private const val STORM_START = "[BOSS] Storm: Pathetic Maxor, just like expected."
    private const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"
    private const val CORE_OPEN = "The Core entrance is opening!"
    private const val PARTY_PREFIX = "Party > "
    private val PARTY = Regex("^Party > (?:\\[[^]]*] )?(\\w{1,16}): (.*)$")
    private val COLOR_CODE = Regex("§.")

    private const val OUTSIDE = 0
    private const val STORM = 2
    private const val TERMINALS = 3
    private var phase = OUTSIDE

    private var alert = ""
    private var alertSize = NONE
    private var alertTicksLeft = 0
    private var soundsLeft = 0

    private fun markAll(value: Boolean) {
        for (p in PLACES) { p.heard = value; p.said = value }
    }

    // The most specific name wins, so "high ee2" is High EE2 and not EE2.
    private fun placeIn(text: String): Place? {
        var best: Place? = null
        var bestLength = 0
        for (p in PLACES) for (name in p.names) {
            if (name.length > bestLength && text.contains(name)) {
                best = p
                bestLength = name.length
            }
        }
        return best
    }

    private fun onPartyLine(sender: String, said: String) {
        if (!locationAlerts || !DungeonUtils.inDungeons) return
        val body = said.trim()
        val line = if (body.length < 2) said else body.substring(0, 1).uppercase() + body.substring(1).lowercase()
        val verb = when {
            line.startsWith("At ") -> "At "
            line.startsWith("Inside ") -> "Inside "
            else -> return
        }
        val where = COLOR_CODE.replace(line.substring(verb.length), "")
        val place = placeIn(where.lowercase())
        if (place != null) {
            if (place.heard) return
            place.heard = true
        }
        if (hideOwn && sender == mc.user.name) return
        val code = DungeonUtils.dungeonTeammates.firstOrNull { it.name == sender }?.clazz?.takeIf { it != DungeonClass.EMPTY }?.colorCode ?: '6'
        alert = "§$code$sender§e is $verb$where!"
        alertSize = mc.font.width(alert) to 9
        alertTicksLeft = alertTicks
        soundsLeft = soundRepeats
    }

    private fun activePhases(): Int {
        if (phase != STORM && phase != TERMINALS) return 0
        if (phase == STORM) return STORM_OR_TERMINALS
        val section = TermInfo.sectionNumber() ?: return STORM_OR_TERMINALS
        return if (section in 1..30) STORM_OR_TERMINALS or (1 shl section) else STORM_OR_TERMINALS
    }

    init {
        on<MessageEvent.Chat> {
            when (message) {
                STORM_START -> { phase = STORM; SS.heard = false; SS.said = false }
                GOLDOR_START -> {
                    phase = TERMINALS
                    for (p in PLACES) if (p !== SS) { p.heard = false; p.said = false }
                }
                CORE_OPEN -> { phase = OUTSIDE; markAll(true) }
                else -> if (message.startsWith(PARTY_PREFIX)) {
                    PARTY.matchEntire(message)?.let { onPartyLine(it.groupValues[1], it.groupValues[2]) }
                }
            }
        }

        on<RenderExtractEvent> {
            if (!customOn || !showPositions || (onlyDungeons && !DungeonUtils.inDungeons)) return@on
            for (m in messages) {
                if (colorCache.size > 256) colorCache.clear()
                val color = colorCache.getOrPut(m.color) { Color(parseColor(m.color) ?: -1) }
                val top = if (m.isBox) {
                    val b = m.box()
                    drawWireFrameBox(b, color, 2f, depth = !throughWalls)
                    Vec3(b.center.x, b.maxY + 0.3, b.center.z)
                } else {
                    drawCylinder(Vec3(m.x, m.y, m.z), (m.distance ?: 0.0).toFloat(), 0.2f, color, thickness = 2f, depth = !throughWalls)
                    Vec3(m.x, m.y + 0.5, m.z)
                }
                if (showText && m.message.isNotBlank()) drawText(m.message, top, 1f, !throughWalls)
            }
        }

        on<TickEvent.End> {
            customTick()
            if (alertTicksLeft > 0) alertTicksLeft--
            if (soundsLeft > 0) { soundsLeft--; sound.play() }
            if (!positional || phase == OUTSIDE) return@on
            val player = mc.player ?: return@on
            var active = -1
            for (p in PLACES) {
                if (p.heard || p.said) continue
                if (active == -1) active = activePhases()
                if ((p.phases and active) == 0 || !p.box.contains(player.x, player.y, player.z)) continue
                sendCommand("pc ${p.call}")
                p.said = true
            }
        }

        on<LevelEvent.Load> {
            sent.clear(); due.clear()
            phase = OUTSIDE
            alertTicksLeft = 0
            soundsLeft = 0
            markAll(true)
        }
    }
}
