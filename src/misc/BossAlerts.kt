package com.engineerclient.misc

import com.engineerclient.DeadPlayers
import com.engineerclient.EngineerClient
import com.engineerclient.poi.PointsOfInterest
import com.engineerclient.practice.LeapTeammates
import com.engineerclient.practice.TermInfo
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
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.M7Phases
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Three small alerts for the F7/M7 boss, each its own setting and HUD:
 *
 *  - Storm First Death Time: counts server ticks from Storm's first line until he is dead, and when "Storm is enraged!"
 *    arrives says in chat how long that took, and shows it for 2 s.
 *  - Melody Warning: in P3, a teammate's party message with a percentage ("Melody terminal 66%") means they are in the Melody
 *    terminal; shows who and how far (n/3), until they finish a terminal or the section ends.
 *  - Leap Count: while you stand on an early enter spot ([PointsOfInterest]), how many teammates are on it with you.
 *    Red until all but one of the teammates you expect there have arrived, then blue.
 */
object BossAlerts : Module(
    name = "Boss Alerts",
    category = Category.custom("Engineer Client", 860, 10),
    description = "Storm's first death time, a warning when a teammate is in the Melody terminal, and a count of teammates on the early enter you stand on.",
    key = null,
) {
    private val stormTime by BooleanSetting("Storm First Death Time", true, desc = "Says in chat how long Storm took to die (from his first line) when he becomes enraged, and shows it on screen for 2 s.")
    private val melody by BooleanSetting("Melody Warning", true, desc = "In P3, shows when a teammate is in the Melody terminal and how far along they are (from their party message), until they finish a terminal.")
    private val leapCount by BooleanSetting("Leap Count", true, desc = "While you stand on an early enter spot, shows how many teammates are on it with you, out of how many you expect there.")
    private val ee2Players by NumberSetting("EE2 Players", 4, 1..4, 1, desc = "How many teammates you expect to leap to you on EE2 (not counting you), for Leap Count.")
    private val ee3Players by NumberSetting("EE3 Players", 3, 1..4, 1, desc = "How many teammates you expect to leap to you on EE3 and EE3 High (not counting you), for Leap Count.")
    private val ee4Players by NumberSetting("EE4 Players", 4, 1..4, 1, desc = "How many teammates you expect to leap to you on EE4 (not counting you), for Leap Count.")

    private val stormHud by HUD("Storm Death Time HUD", "How long Storm took to die, for 2 s after he becomes enraged.", true, 400, 200, 2f) { example ->
        val shown = example || (stormTime && stormShownUntil - serverTicks > 0 && stormDiedAt >= 0)
        if (!shown) return@HUD 0 to 0
        draw("§bStorm died at: §f" + seconds(if (example) 31 * 20 + 7 else stormDiedAt))
    }

    private val melodyHud by HUD("Melody Warning HUD", "Which teammate has melody and how far along they are.", true, 400, 240, 2f) { example ->
        if (example) return@HUD draw("§b§lMAGE §r§dhas melody! 2/3")
        val name = melodyName
        if (!melody || name == null) return@HUD 0 to 0
        val mate = DungeonUtils.dungeonTeammates.firstOrNull { it.name.equals(name, ignoreCase = true) }
        val clazz = mate?.clazz?.takeIf { it != DungeonClass.EMPTY }
        val who = if (clazz != null) "§${clazz.colorCode}§l${clazz.name}" else "§f§l$name"
        draw("$who §r§dhas melody! ${minOf(melodyPercent / 33, 2)}/3")
    }

    private val leapHud by HUD("Leap Count HUD", "How many teammates have leapt to the early enter you are on.", true, 400, 280, 2f) { example ->
        if (example) return@HUD draw("§c2/4 Leaped")
        val max = leapMax
        if (!leapCount || max == 0) return@HUD 0 to 0
        draw((if (leapNow >= max - 1) "§9" else "§c") + "$leapNow/$max Leaped")
    }

    private const val STORM_START = "[BOSS] Storm: Pathetic Maxor, just like expected."
    private const val STORM_DEAD = "[BOSS] Storm: I should have known that I stood no chance."
    private const val STORM_ENRAGED = "⚠ Storm is enraged! ⚠"
    /** How long the death time stays on screen, in server ticks. */
    private const val STORM_SHOW = 40

    /** "Party > [RANK] name: message", the rank bracket absent for unranked players. */
    private val PARTY = Regex("^Party > (?:\\[[^]]*] )?(\\w{1,16}): (.*)$")
    private val PERCENT = Regex("(\\d+)%")
    private val TERMINAL = Regex("^(\\w{1,16}) activated a terminal! \\(\\d+/\\d+\\)$")

    private var serverTicks = 0
    /** Server ticks since Storm's first line; null while he isn't being counted (before it, or once he is dead). */
    private var stormTicks: Int? = null
    private var stormDiedAt = -1
    private var stormShownUntil = 0

    private var melodyName: String? = null
    private var melodyPercent = 0
    private var section = 0

    private var leapMax = 0
    private var leapNow = 0

    private fun reset() {
        stormTicks = null; stormDiedAt = -1; stormShownUntil = 0
        clearMelody(); section = 0
        leapMax = 0; leapNow = 0
    }

    private fun clearMelody() { melodyName = null; melodyPercent = 0 }

    private fun seconds(ticks: Int): String = String.format(java.util.Locale.ROOT, "%.2fs", ticks * 0.05)

    private fun GuiGraphicsExtractor.draw(s: String): Pair<Int, Int> {
        text(s, 0, 0, Colors.WHITE, shadow = true)
        return mc.font.width(s) to 9
    }

    private fun chat(message: String) {
        if (stormTime) when (message) {
            STORM_START -> stormTicks = 0
            STORM_DEAD -> stormTicks = null
            STORM_ENRAGED -> stormTicks?.let {
                stormDiedAt = it
                stormShownUntil = serverTicks + STORM_SHOW
                EngineerClient.chat("Storm died at: ${seconds(it)}")
            }
        }
        if (!melody || !inP3()) return
        TERMINAL.matchEntire(message)?.let { if (it.groupValues[1].equals(melodyName, ignoreCase = true)) clearMelody(); return }
        val party = PARTY.matchEntire(message) ?: return
        val sender = party.groupValues[1]
        if (sender.equals(mc.user.name, ignoreCase = true)) return
        val percent = PERCENT.find(party.groupValues[2])?.groupValues?.get(1)?.toIntOrNull() ?: return
        // The furthest progress wins, so a stray number from someone else doesn't move it back.
        if (percent > melodyPercent) { melodyName = sender; melodyPercent = percent }
    }

    private fun inP3() = DungeonUtils.inBoss && DungeonUtils.getF7Phase() == M7Phases.P3

    /** Leap Count's numbers for this tick: the spot you stand on, and the living teammates in view on it. */
    private fun countLeaps() {
        leapMax = 0; leapNow = 0
        val me = mc.player ?: return
        val level = mc.level ?: return
        if (!DungeonUtils.inBoss || me.isSpectator || DeadPlayers.isDead(me.name.string) || LeapTeammates.inFlight(me)) return
        val spot = PointsOfInterest.at(me.x, me.y, me.z) ?: return
        leapMax = when (spot) {
            PointsOfInterest.EE2 -> ee2Players
            PointsOfInterest.EE4 -> ee4Players
            else -> ee3Players
        }
        leapNow = DungeonUtils.leapTeammates.count { m ->
            if (m.name == me.name.string || DeadPlayers.isDead(m)) return@count false
            val e = LeapTeammates.entity(m, level) ?: return@count false
            !LeapTeammates.inFlight(e) && spot.contains(e.x, e.y, e.z)
        }
    }

    init {
        on<LevelEvent.Load> { reset() }

        on<MessageEvent.Chat> { EngineerClient.safely("boss alerts chat") { chat(message) } }

        on<TickEvent.Server> { serverTicks++; stormTicks = stormTicks?.plus(1) }

        on<TickEvent.End> {
            EngineerClient.safely("boss alerts tick") {
                val sec = if (inP3()) TermInfo.sectionNumber() ?: 0 else 0
                if (sec != section) { section = sec; clearMelody() }
                if (leapCount) countLeaps() else { leapMax = 0; leapNow = 0 }
            }
        }
    }
}
