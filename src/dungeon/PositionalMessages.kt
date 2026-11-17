package com.engineerclient.dungeon

import com.engineerclient.practice.TermInfo
import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.NumberSetting
import com.adm.events.LevelEvent
import com.adm.events.MessageEvent
import com.adm.events.TickEvent
import com.adm.events.core.on
import com.adm.features.Category
import com.adm.features.Module
import com.adm.utils.Colors
import com.adm.utils.render.text
import com.adm.utils.sendCommand
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.world.phys.AABB

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
        alert = "§6$sender§e is $verb$where!"
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

        on<TickEvent.End> {
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
            phase = OUTSIDE
            alertTicksLeft = 0
            soundsLeft = 0
            markAll(true)
        }
    }
}
