package com.engineerclient.dungeon

import com.engineerclient.misc.OdinMelodyChat
import com.engineerclient.practice.TermInfo
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.sendCommand
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.phys.AABB

object GoldorHelper : Module(
    name = "Goldor Helper",
    category = Category.BOSS,
    description = "P3 helpers: positional party messages and alerts, melody alert, current section and section progress.",
    key = null,
) {
    private val positional by BooleanSetting("Positional Messages", true, desc = "Says in party chat when you reach SS, an early enter, core or tunnel.")
    private val locationAlerts by BooleanSetting("Location Alerts", true, desc = "Shows teammates' \"At ...\" party messages on screen.")
    private val hideOwn by BooleanSetting("Hide Your Own", true, desc = "No location alert for your own messages.").withDependency { locationAlerts }
    private val alertTicks by NumberSetting("Alert Duration", 15, 1..20, 1, desc = "How long a location alert stays, in client ticks.").withDependency { locationAlerts }
    private val soundRepeats by NumberSetting("Sound Repeats", 10, 0..20, 1, desc = "How many times the alert sound plays.").withDependency { locationAlerts }
    private val sound = SoundOption(this, "minecraft:block.note_block.pling")
    private val colorProgress by BooleanSetting("Color Progress", true, desc = "Section progress goes red, yellow, green, gold as it fills.")

    private val locationHud by HUD("Location Alert", "A teammate's location message.", false, 300, 90, 2.1f) { example ->
        if (example) return@HUD draw("§6Someone§e is At <location>!")
        if (!locationAlerts || alertLeft <= 0) return@HUD 0 to 0
        draw(alertText)
    }
    private val melodyHud by HUD("Melody Alert", "Who has melody and how far along they are.", true, 270, 45, 3.6f) { example ->
        if (example) return@HUD draw("§5§lARCHER§r§d has melody! 1/3")
        melodyText()?.let { draw(it) } ?: (0 to 0)
    }
    private val sectionHud by HUD("Current Section", "The terminal section you're on.", true, 900, 520, 1f) { example ->
        val n = if (example) 1 else TermInfo.sectionNumber() ?: return@HUD 0 to 0
        draw("§5Section: §f$n")
    }
    private val progressHud by HUD("Section Progress", "The current section's tasks done.", true, 420, 180, 3.6f) { example ->
        val (done, total) = if (example) 3 to 7 else TermInfo.sectionProgress() ?: return@HUD 0 to 0
        draw(progressText(done, total))
    }

    private class Spot(val message: String, val checks: List<String>, val box: AABB, val sections: Set<Int>) {
        var sent = true
        var sentByMe = true
        fun reset(to: Boolean) { sent = to; sentByMe = to }
    }

    private val SS = Spot("At SS!", listOf("ss"), AABB(107.0, 120.0, 93.0, 110.0, 121.0, 95.0), setOf(0, 1))
    private val SPOTS = listOf(
        SS,
        Spot("At EE2!", listOf("ee2", "early enter 2"), AABB(57.0, 109.0, 130.0, 59.0, 110.0, 132.0), setOf(1, 2)),
        Spot("At High EE2!", listOf("high ee2", "highee2", "hee2"), AABB(59.0, 132.0, 138.0, 62.0, 133.0, 140.0), setOf(1, 2)),
        Spot("At EE3!", listOf("ee3", "early enter 3", "early entry 3"), AABB(1.0, 109.0, 103.0, 3.0, 110.0, 106.0), setOf(2, 3)),
        Spot("At Core!", listOf("core"), AABB(53.0, 115.0, 51.0, 56.0, 116.0, 54.0), setOf(2, 3, 4)),
        Spot("Inside Goldor Tunnel!", listOf("tunnel"), AABB(52.0, 114.0, 55.0, 57.0, 116.0, 58.0), setOf(4, 5)),
        Spot("At 2 Safespot!", listOf("2 safespot", "ee2 safespot", "safespot ee2", "s2 safespot", "safespot s2"), AABB(46.0, 109.0, 121.987, 49.0, 110.0, 121.988), setOf(1, 2)),
        Spot("At 3 Safespot!", listOf("3 safespot", "ee3 safespot", "freaky ee3", "freak ee", "safespot s3", "safespot 3"), AABB(18.0, 121.0, 91.0, 19.0, 126.0, 99.0), setOf(2, 3)),
        Spot("At Split ee2!", listOf("split ee2", "splitee2", "mage term"), AABB(58.0, 119.0, 124.0, 60.0, 123.0, 126.0), setOf(1, 2)),
        Spot("At High 2 Safespot!", listOf("2 safespot"), AABB(57.0, 127.0, 143.7, 70.0, 133.0, 146.7), setOf(1, 2)),
    )

    private const val STORM_START = "[BOSS] Storm: Pathetic Maxor, just like expected."
    private const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"
    private const val CORE_OPEN = "The Core entrance is opening!"
    private val PARTY = Regex("^Party > (?:\\[[^]]*] )?(\\w{1,16}): (.*)$")
    private val AT = Regex("^(At |Inside )")

    /** 2 Storm, 3 terminals, 0 otherwise. */
    private var phase = 0
    private var alertText = ""
    private var alertLeft = 0
    private var soundsLeft = 0

    private fun GuiGraphicsExtractor.draw(s: String): Pair<Int, Int> {
        text(s, 0, 0, Colors.WHITE, true)
        return mc.font.width(s) to 9
    }

    private fun progressText(done: Int, total: Int): String {
        if (!colorProgress) return "§a(§c$done§a/$total)"
        val c = when {
            done >= total -> "§6§l"
            total - done == 1 -> "§a"
            done >= 3 -> "§e"
            else -> "§c"
        }
        return "§f($c$done§f/§a$total§f)"
    }

    private fun melodyText(): String? {
        val (name, heard) = OdinMelodyChat.heard().maxByOrNull { it.value.percent } ?: return null
        val who = DungeonUtils.dungeonTeammates.firstOrNull { it.name.equals(name, true) }?.clazz?.name ?: name
        return "§5§l$who§r§d has melody! ${minOf(heard.percent / 33, 2)}/3"
    }

    private fun inSection(n: Int): Boolean {
        if (n == 0) return phase == 2 || phase == 3
        return phase == 3 && TermInfo.sectionNumber() == n
    }

    /** Someone already called this spot: true. Otherwise marks it called. */
    private fun alreadyCalled(location: String): Boolean {
        val lower = location.lowercase()
        for (spot in SPOTS) {
            if (spot.checks.none { lower.contains(it) }) continue
            if (spot.sent) return true
            spot.sent = true
            return false
        }
        return false
    }

    private fun onParty(sender: String, text: String) {
        if (!locationAlerts || !DungeonUtils.inDungeons) return
        val trimmed = text.trim()
        val formatted = if (trimmed.length < 2) text else trimmed.take(1).uppercase() + trimmed.drop(1).lowercase()
        val action = AT.find(formatted)?.groupValues?.get(1) ?: return
        val location = formatted.substring(formatted.indexOf(action) + action.length).replace(Regex("§."), "")
        if (alreadyCalled(location)) return
        if (hideOwn && sender == mc.user.name) return
        alertText = "§6$sender§e is $action$location!"
        alertLeft = alertTicks
        soundsLeft = soundRepeats
    }

    init {
        on<MessageEvent.Chat> {
            when (message) {
                STORM_START -> { phase = 2; SS.reset(false) }
                GOLDOR_START -> { phase = 3; SPOTS.forEach { if (it !== SS) it.reset(false) } }
                CORE_OPEN -> { phase = 0; SPOTS.forEach { it.reset(true) } }
                else -> PARTY.matchEntire(message)?.let { onParty(it.groupValues[1], it.groupValues[2]) }
            }
        }

        on<TickEvent.End> {
            if (alertLeft > 0) alertLeft--
            if (soundsLeft > 0) { soundsLeft--; sound.play() }
            if (!positional || phase == 0) return@on
            val p = mc.player ?: return@on
            for (spot in SPOTS) {
                if (spot.sent || spot.sentByMe || spot.sections.none(::inSection) || !spot.box.contains(p.position())) continue
                sendCommand("pc ${spot.message}")
                spot.sentByMe = true
            }
        }

        on<LevelEvent.Load> { phase = 0; alertLeft = 0; soundsLeft = 0; SPOTS.forEach { it.reset(true) } }
    }
}
