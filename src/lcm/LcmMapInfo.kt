/*
 * The score calculator and score text below are dtMap's (github.com/ricedotwho/dtMap, its Scoreboard.TabData and
 * Score), which are in turn Odin's (github.com/odtheking/Odin, DungeonUtils and DungeonListener).
 *
 * Copyright (c) 2026, rice.who
 * Original code Copyright (c) 2026, odtheking
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that
 * the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the
 *    following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
 *    following disclaimer in the documentation and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote
 *    products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.engineerclient.lcm

import com.engineerclient.EngineerClient
import com.engineerclient.mappred.FloorRules
import com.odtheking.odin.OdinMod.mc
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.HUDSetting
import com.odtheking.odin.features.impl.dungeon.MapInfo
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.features.impl.dungeon.map.tile.RoomType
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.Floor
import com.odtheking.odin.utils.skyblock.dungeon.PuzzleStatus
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.ceil
import kotlin.math.floor

/**
 * LCM Style for Odin's Map Info: its Full Hud drawn the way LCM's map, dtMap, draws its score (Score Calculation),
 * worked out by dtMap's score calculator in its Legit Mode with its default score settings, from the tab list
 * stats Odin reads:
 *
 * - secrets found (aqua), the secrets still missing for 300 (yellow; the bonus score counts, Paul's doesn't) and
 *   the total (red), then the unknown secrets (the total less the rooms someone has walked into: yours, and your
 *   teammates' from Odin's map websocket; green while at least the 300 needs, else gold - dtMap shows it only when
 *   solo clearing) and the score: red under 270, yellow under 300, green from 300
 * - what's still missing from the bonus: deaths, the Mimic (F6/F7), the Prince (until every room but blood is
 *   open on the map and none is a Prince room), bats (until every player in the run has one, at most 5) and
 *   crypts (up to 5)
 * - one line when it's narrower than the map, else the score's line over the bonus's, centred
 *
 * `LcmMapInfoMixin` hands the Full Hud to [render]; Odin's Disable in boss still applies.
 */
object LcmMapInfo {

    private val style = BooleanSetting("LCM Style", false,
        desc = "Draws the Full Hud like LCM's map (dtMap) draws its score, with dtMap's score calculator: secrets found, missing and total, the unknown secrets (in rooms nobody in the party has walked into yet, teammates' from Odin's map websocket), the score, then what's still missing from the bonus (deaths, Mimic, Prince, bats, crypts). Added by engineerClient.")
        .withDependency { (MapInfo.settings["Full Hud"] as? HUDSetting)?.value?.enabled ?: true }

    private var failed = false

    fun install() {
        MapInfo.registerSetting(style)
    }

    /** True while the Full Hud is drawn here instead of by Odin (LcmMapInfoMixin). */
    @JvmStatic
    fun active(): Boolean = style.value

    /** Draws the Full Hud; its size, or null to leave this frame to Odin (it couldn't be drawn). */
    @JvmStatic
    fun render(g: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int>? {
        if (!example && (!DungeonUtils.inDungeons || (disableInBoss() && DungeonUtils.inBoss))) return 0 to 0
        return try {
            if (example) centred(g, listOf("§b10§7-§e49§7-§c55     §a300", "§7D: §c1  §7M: §c✖  §7P: §c✖  §7B: §c✖  §c0§7/§a5"), 2)
            else centred(g, lines(), 0)
        } catch (t: Throwable) {
            if (!failed) {
                failed = true
                EngineerClient.logger.error("[ec] dtMap score failed, Odin draws its Full Hud", t)
            }
            null
        }
    }

    private fun disableInBoss(): Boolean = (MapInfo.settings["Disable in boss"] as? BooleanSetting)?.value ?: true

    /** dtMap's score text (its Score.actualRender): one line if it's narrower than the map, else two. */
    private fun lines(): List<String> {
        val score = calculateScore()
        val totalSecrets = calculateTotalSecrets()
        val secrets = "§b${DungeonUtils.secretCount}§7-§e${calculateMinimumSecrets()}§7-§c$totalSecrets"
        val scoreText = "§${if (score < 270) "c" else if (score < 300) "e" else "a"}$score"
        val unknown = if (totalSecrets != 0) {
            val unknown = (totalSecrets - knownRoomSecrets()).coerceAtLeast(0)
            "${if (unknown >= calculateNeededSecrets()) "§a" else "§6"}$unknown   "
        } else ""
        val line1 = "$secrets   $unknown$scoreText"
        val line2 = buildString {
            if (DungeonUtils.deathCount > 0) append("§7D: §c${DungeonUtils.deathCount}   ")
            if (!DungeonUtils.mimicKilled && floorNumber() >= 6) append("§7M: §c✖   ")
            if (!DungeonUtils.princeKilled && princeLeft()) append("§7P: §c✖   ")
            if (DungeonUtils.batKilled < batsPossible()) append("§7B: §c${DungeonUtils.batKilled.coerceAtMost(5)}   ")
            if (DungeonUtils.cryptCount < 5) append("§c${DungeonUtils.cryptCount}§7/§a5")
        }.trim()
        // as wide as dtMap's map: its rooms down, 16 each and 4 between, and its 5 of background either side
        val rows = LcmStyle.mapSize(false).second
        val mapWidth = rows * 16 + (rows - 1) * 4 + 10
        return if (mc.font.width("$line1   $line2") < mapWidth) listOf("$secrets   ${if (line2.isNotEmpty()) "$line2   " else ""}$unknown$scoreText")
            else listOf(line1, line2)
    }

    /**
     * The secrets in the rooms someone in the party has walked into (the ones the map names): yours, and your
     * teammates' from Odin's map websocket.
     */
    private fun knownRoomSecrets(): Int = DungeonScan.rooms.sumOf { if (it.shouldShowName) it.data?.maxSecrets ?: 0 else 0 }

    /** One bat per player in the run, at most 5 (dtMap: 5, or 1 when solo clearing); 5 until the tab list has the team. */
    private fun batsPossible(): Int = DungeonUtils.dungeonTeammates.size.takeIf { it > 0 }?.coerceAtMost(5) ?: 5

    /** The lines centred on each other, [gap] between them (dtMap's: none, 2 on its example). */
    private fun centred(g: GuiGraphicsExtractor, lines: List<String>, gap: Int): Pair<Int, Int> {
        val width = lines.maxOf { mc.font.width(it) }
        for ((i, line) in lines.withIndex()) g.centeredText(mc.font, line, width / 2, i * (mc.font.lineHeight + gap), -1)
        return width to lines.size * (mc.font.lineHeight + gap) - gap
    }

    private fun floorNumber(): Int = DungeonUtils.floor?.floorNumber ?: 0

    /** dtMap's floor secret factor: the share of the secrets a floor needs for full exploration score. */
    private fun secretFactor(): Float = when (DungeonUtils.floor) {
        Floor.E, Floor.F1 -> 0.3f
        Floor.F2 -> 0.4f
        Floor.F3 -> 0.5f
        Floor.F4 -> 0.6f
        Floor.F5 -> 0.7f
        Floor.F6 -> 0.85f
        else -> 1f
    }

    private fun calculateTotalSecrets(): Int {
        val found = DungeonUtils.secretCount; val percent = DungeonUtils.secretPercentage
        return if (found == 0 || percent == 0f) 0 else floor(100 / percent * found + 0.5).toInt()
    }

    private fun calculateTotalRooms(): Int {
        val completed = DungeonUtils.completedRoomCount; val percent = DungeonUtils.percentCleared
        return if (completed == 0 || percent == 0) 0 else floor((completed / (percent * 0.01).toFloat()) + 0.4).toInt()
    }

    private fun deathPenalty(): Int = (DungeonUtils.deathCount * 2 - 1).coerceAtLeast(0)

    private fun calculateScore(): Int {
        val totalRooms = calculateTotalRooms()
        val completed = DungeonUtils.completedRoomCount + (if (!DungeonUtils.bloodDone) 1 else 0) + if (!DungeonUtils.inBoss) 1 else 0
        val total = if (totalRooms != 0) totalRooms else 36
        val totalSecrets = calculateTotalSecrets()

        val secretScore = if (totalSecrets > 0) {
            floor(DungeonUtils.secretCount.toDouble() / (totalSecrets.toDouble() * secretFactor()) * 40.0).toInt().coerceIn(0, 40)
        } else 0
        val exploration = secretScore + floor(completed.toFloat() / total * 60f).coerceIn(0f, 60f).toInt()

        val skillRooms = floor(completed.toFloat() / total * 80f).coerceIn(0f, 80f).toInt()
        val puzzlePenalty = (DungeonUtils.puzzleCount - DungeonUtils.puzzles.count { it.status == PuzzleStatus.Completed }) * 10

        return exploration + (20 + skillRooms - puzzlePenalty - deathPenalty()).coerceIn(20, 100) + 100 + calculateBonusScoreNoPaul() + calculatePaulScore()
    }

    private fun calculateBonusScoreNoPaul(): Int =
        (if (DungeonUtils.mimicKilled) 2 else 0) + (if (DungeonUtils.princeKilled) 1 else 0) + DungeonUtils.batKilled.coerceAtMost(5) + DungeonUtils.cryptCount.coerceAtMost(5)

    /** dtMap's Paul on Auto: Paul's +10 when he's mayor with his perk. */
    private fun calculatePaulScore(): Int = if (DungeonUtils.isPaul) 10 else 0

    /** The secrets 300 needs in all (dtMap's default: the bonus score so far counts, Paul's doesn't). */
    private fun calculateNeededSecrets(): Int =
        ceil(calculateTotalSecrets() * secretFactor() * (40 - calculateBonusScoreNoPaul() + deathPenalty()) / 40f).toInt().coerceAtLeast(0)

    /** The secrets still missing for 300. */
    private fun calculateMinimumSecrets(): Int = (calculateNeededSecrets() - DungeonUtils.secretCount).coerceAtLeast(0)

    /**
     * dtMap's Legit Mode Prince: there may still be one until the map has every room but blood open (the floor's
     * main grid, all of it; the puzzle column only has puzzles) and none of them is a Prince room.
     */
    private fun princeLeft(): Boolean {
        val rooms = DungeonScan.rooms
        if (rooms.any { LcmStyle.opened(it) && it.name in LcmStyle.princeRooms }) return true
        val rules = FloorRules.of(floorNumber()) ?: return true
        for (z in 0 until rules.h) for (x in 0 until rules.w) {
            val room = DungeonScan.tiles.getOrNull(z * 6 + x)?.room ?: return true
            if (room.type != RoomType.BLOOD && !LcmStyle.opened(room)) return true
        }
        return false
    }
}
