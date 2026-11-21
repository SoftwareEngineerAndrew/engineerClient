package com.engineerclient.lcm

import com.engineerclient.EngineerClient
import com.engineerclient.dungeon.ClearInfo
import com.engineerclient.mappred.FloorRules
import com.adm.AdmMod.mc
import com.adm.features.impl.dungeon.map.DungeonScan
import com.adm.features.impl.dungeon.map.tile.RoomType
import com.adm.utils.skyblock.dungeon.DungeonListener
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.adm.utils.skyblock.dungeon.Floor
import com.adm.utils.skyblock.dungeon.PuzzleStatus
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Clear Info's Map Info HUD: secrets found, still needed for 300 and total, the unknown secrets and the score,
 * then the missing bonus (deaths, Mimic, Prince, bats, crypts); one line when it fits under the map, else two.
 */
object LcmMapInfo {

    private val EXAMPLE = listOf("§b10§7-§e49§7-§c55     §a300", "§7D: §c1  §7M: §c✖  §7P: §c✖  §7B: §c✖  §c0§7/§a5")
    private val NONE = 0 to 0

    private var failed = false

    // The numbers the text is made of; it is rebuilt only when one changes.
    private val state = IntArray(STATE_SIZE)
    private val built = IntArray(STATE_SIZE) { Int.MIN_VALUE }
    private var lines: List<String> = emptyList()
    private var size = NONE

    fun render(g: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int> {
        if (!example && (!DungeonUtils.inDungeons || (ClearInfo.disableInBoss && DungeonUtils.inBoss))) return NONE
        return try {
            if (example) draw(g, EXAMPLE, 2, LcmStyle.mapWidth(true))
            else {
                readState()
                if (!state.contentEquals(built)) rebuild()
                draw(g, lines, 0, LcmStyle.mapWidth(false))
            }
        } catch (t: Throwable) {
            if (!failed) {
                failed = true
                EngineerClient.logger.error("[ec] Map Info failed", t)
            }
            NONE
        }
    }

    // The HUD is placed by its left edge, so it is as wide as the map and each line is centred on the map's middle.
    private fun draw(g: GuiGraphicsExtractor, text: List<String>, gap: Int, width: Int): Pair<Int, Int> {
        val step = mc.font.lineHeight + gap
        for (i in text.indices) g.centeredText(mc.font, text[i], width / 2, i * step, -1)
        val height = text.size * step - gap
        if (size.first != width || size.second != height) size = width to height
        return size
    }

    private fun readState() {
        val stats = DungeonListener.dungeonStats
        val found = stats.secretsFound
        val bats = stats.numBatsKilled
        val crypts = stats.crypts
        val deaths = stats.deaths
        val floorNumber = DungeonUtils.floor?.floorNumber ?: 0
        val factor = secretFactor(DungeonUtils.floor)

        val percent = stats.secretsPercent
        val totalSecrets = if (found == 0 || percent == 0f) 0 else floor(100 / percent * found + 0.5).toInt()

        val bonus = (if (stats.mimicKilled) 2 else 0) + (if (stats.princeKilled) 1 else 0) + bats.coerceAtMost(5) + crypts.coerceAtMost(5)
        val paul = if (DungeonUtils.isPaul) 10 else 0
        val deathPenalty = (deaths * 2 - 1).coerceAtLeast(0)
        val needed = ceil(totalSecrets * factor * (40 - bonus - paul + deathPenalty) / 40f).toInt().coerceAtLeast(0)

        val completed = DungeonListener.scoreRooms
        val total = DungeonListener.roomTotal.takeIf { it != 0 } ?: 36
        val secretScore = if (totalSecrets > 0) floor(found.toDouble() / (totalSecrets.toDouble() * factor) * 40.0).toInt().coerceIn(0, 40) else 0
        val exploration = secretScore + floor(completed.toFloat() / total * 60f).coerceIn(0f, 60f).toInt()
        val skillRooms = floor(completed.toFloat() / total * 80f).coerceIn(0f, 80f).toInt()
        val puzzlePenalty = (stats.puzzleCount - completedPuzzles()) * 10
        val score = exploration + (20 + skillRooms - puzzlePenalty - deathPenalty).coerceIn(20, 100) + 100 + bonus + paul

        val teammates = DungeonListener.dungeonTeammates.size
        val batsPossible = if (teammates > 0) teammates.coerceAtMost(5) else 5

        state[0] = found
        state[1] = (needed - found).coerceAtLeast(0)
        state[2] = totalSecrets
        if (totalSecrets != 0) {
            val unknown = (totalSecrets - knownRoomSecrets()).coerceAtLeast(0)
            state[3] = unknown
            state[4] = if (unknown >= needed) 1 else 0
        } else {
            state[3] = -1
            state[4] = 0
        }
        state[5] = score
        state[6] = deaths
        state[7] = if (!stats.mimicKilled && floorNumber >= 6) 1 else 0
        state[8] = if (!stats.princeKilled && princeLeft(floorNumber)) 1 else 0
        state[9] = if (bats < batsPossible) bats.coerceAtMost(5) else -1
        state[10] = crypts
        state[11] = LcmStyle.rows(false)
    }

    private fun rebuild() {
        state.copyInto(built)
        val secrets = "§b${state[0]}§7-§e${state[1]}§7-§c${state[2]}"
        val score = state[5]
        val scoreText = "§${if (score < 270) "c" else if (score < 300) "e" else "a"}$score"
        val unknown = if (state[3] >= 0) "${if (state[4] == 1) "§a" else "§6"}${state[3]}   " else ""
        val first = "$secrets   $unknown$scoreText"
        val bonus = StringBuilder()
        if (state[6] > 0) bonus.append("§7D: §c").append(state[6]).append("   ")
        if (state[7] == 1) bonus.append("§7M: §c✖   ")
        if (state[8] == 1) bonus.append("§7P: §c✖   ")
        if (state[9] >= 0) bonus.append("§7B: §c").append(state[9]).append("   ")
        if (state[10] < 5) bonus.append("§c").append(state[10]).append("§7/§a5")
        val second = bonus.toString().trim()
        // as wide as the map's rooms (16 each, 4 between) and 5 of background either side
        val rows = state[11]
        val mapWidth = rows * 16 + (rows - 1) * 4 + 10
        lines = if (mc.font.width("$first   $second") < mapWidth) listOf("$secrets   ${if (second.isNotEmpty()) "$second   " else ""}$unknown$scoreText")
            else listOf(first, second)
    }

    private fun completedPuzzles(): Int {
        val puzzles = DungeonListener.puzzles
        var n = 0
        for (i in puzzles.indices) if (puzzles[i].status == PuzzleStatus.Completed) n++
        return n
    }

    // Secrets in every room the map can already name.
    private fun knownRoomSecrets(): Int {
        val rooms = DungeonScan.rooms
        var sum = 0
        for (i in 0 until rooms.size) {
            val room = rooms[i]
            if (LcmStyle.knownName(room) != null) sum += room.data?.maxSecrets ?: 0
        }
        return sum
    }

    private fun secretFactor(floor: Floor?): Float = when (floor) {
        Floor.E, Floor.F1 -> 0.3f
        Floor.F2 -> 0.4f
        Floor.F3 -> 0.5f
        Floor.F4 -> 0.6f
        Floor.F5 -> 0.7f
        Floor.F6 -> 0.85f
        else -> 1f
    }

    // A Prince may be left until every main-grid room but blood is open and none of them is a Prince room.
    private fun princeLeft(floorNumber: Int): Boolean {
        val rooms = DungeonScan.rooms
        for (i in 0 until rooms.size) {
            val room = rooms[i]
            if (LcmStyle.opened(room) && room.name in LcmStyle.princeRooms) return true
        }
        val rules = FloorRules.of(floorNumber) ?: return true
        for (z in 0 until rules.h) for (x in 0 until rules.w) {
            val room = DungeonScan.tiles.getOrNull(z * 6 + x)?.room ?: return true
            if (room.type != RoomType.BLOOD && !LcmStyle.opened(room)) return true
        }
        return false
    }

    private const val STATE_SIZE = 12
}
