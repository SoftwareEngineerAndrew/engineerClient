package com.engineerclient.misc

import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.M7Phases
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.player.PlayerSkin
import java.util.Locale

/**
 * F7 P1/P2: Maxor and Storm aggro onto whoever is closest, so this lists the party by distance
 * to the boss, closest (the one holding aggro) in green. Hidden outside those two phases.
 *
 * The boss is the nearest live WitherBoss to you, preferring one whose name mentions the boss -
 * the other withers sit in their own arenas further down, so nearest is the one you're fighting.
 * If none is found the HUD says so rather than going blank, so a detection miss is visible.
 */
object AgroLeaderboard : Module(
    name = "Agro Leaderboard",
    category = Category.custom("Blood Rush"),
    description = "In F7 P1/P2, lists the party by distance to Maxor/Storm. Closest (who has aggro) is green.",
) {
    private class Entry(val name: String, val skin: PlayerSkin?, val distance: Double)

    private var bossName: String? = null
    private var bossFound = false
    private var entries: List<Entry> = emptyList()

    private const val LINE_HEIGHT = 10
    private const val HEAD_SIZE = 8

    private val leaderboardHud by HUD("Agro Leaderboard", "Party ordered by distance to Maxor/Storm.", toggleable = false) { example ->
        if (example) return@HUD draw(this, "Maxor", true, listOf(
            Entry("undonecoffee", mc.player?.skin, 4.2), Entry("Teammate", null, 9.8), Entry("Another", null, 15.1),
        ))
        val name = bossName ?: return@HUD 0 to 0
        draw(this, name, bossFound, entries)
    }

    init {
        on<TickEvent.End> {
            bossName = when (DungeonUtils.getF7Phase()) {
                M7Phases.P1 -> "Maxor"
                M7Phases.P2 -> "Storm"
                else -> null
            }
            val name = bossName ?: run { entries = emptyList(); return@on }
            val me = mc.player ?: return@on

            val withers = level.entitiesForRendering().filterIsInstance<WitherBoss>().filter { it.isAlive }
            val boss = withers.filter { it.name.string.contains(name, true) }.minByOrNull { it.distanceToSqr(me) }
                ?: withers.minByOrNull { it.distanceToSqr(me) }
            bossFound = boss != null
            if (boss == null) { entries = emptyList(); return@on }

            entries = DungeonUtils.dungeonTeammates.mapNotNull { teammate ->
                if (teammate.isDead) return@mapNotNull null
                val player = teammate.entity?.takeIf { it.isAlive }
                    ?: level.players().firstOrNull { it.name.string == teammate.name }
                    ?: return@mapNotNull null
                Entry(teammate.name, teammate.playerSkin, player.distanceTo(boss).toDouble())
            }.sortedBy { it.distance }
        }
    }

    private fun draw(gfx: GuiGraphicsExtractor, boss: String, found: Boolean, list: List<Entry>): Pair<Int, Int> {
        val header = if (found) "§c$boss Aggro" else "§c$boss §7not found"
        gfx.text(header, 0, 0, Colors.WHITE)
        var width = mc.font.width(header)
        var y = LINE_HEIGHT + 2
        list.forEachIndexed { i, entry ->
            entry.skin?.let { PlayerFaceExtractor.extractRenderState(gfx, it, 0, y, HEAD_SIZE) }
            val line = "${i + 1}. ${entry.name} §7${String.format(Locale.ROOT, "%.1f", entry.distance)}m"
            gfx.text(line, HEAD_SIZE + 3, y, if (i == 0) Colors.MINECRAFT_GREEN else Colors.WHITE)
            width = maxOf(width, HEAD_SIZE + 3 + mc.font.width(line))
            y += LINE_HEIGHT
        }
        return width to y
    }
}
