package com.engineerclient.debug

import com.engineerclient.EngineerClient
import com.engineerclient.misc.ScoreboardLines
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.world.scores.DisplaySlot
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * TEMPORARY — records the sidebar to find why the scoreboard hider hides a dungeon party member.
 * Remove this file and its one register() line in EngineerClient once that is fixed.
 *
 * Twice a second it reads the whole sidebar and, when anything changed, appends a snapshot to
 * logs/engineerclient/scoreboard-<stamp>.log: every score (hidden holders and lines past the
 * fifteen drawn included), its raw text with § codes as &, the plain text the hider matches,
 * and whether the hider's rules match it. It checks the rules directly, so it records the same
 * verdicts whether Hide Scoreboard Lines is on or not. Party members come from Odin; a line that
 * names one and is matched by the hider is flagged "!! TEAMMATE HIDDEN".
 */
object ScoreboardRecorder {

    private const val EVERY_TICKS = 10
    private const val MAX_BYTES = 20L * 1024 * 1024

    private var file: Path? = null
    private var last: String? = null
    private var ticks = 0
    private var full = false

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register {
            if (++ticks % EVERY_TICKS != 0 || full) return@register
            EngineerClient.safely("scoreboard recorder") { sample() }
        }
    }

    private fun sample() {
        val mc = EngineerClient.mc
        val scoreboard = mc.level?.scoreboard ?: return
        val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return

        val drawn = ScoreboardLines.sidebarEntries(scoreboard, objective).toSet()
        val all = scoreboard.listPlayerScores(objective)
            .sortedWith(compareByDescending<net.minecraft.world.scores.PlayerScoreEntry> { it.value() }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.owner() })
        val team = if (DungeonUtils.inDungeons) DungeonUtils.dungeonTeammates.map { it.name to it.isDead } else emptyList()

        val body = StringBuilder()
        body.append("title: ").append(ScoreboardLines.toLegacy(objective.displayName).replace('§', '&')).append('\n')
        body.append("dungeon: ").append(DungeonUtils.inDungeons).append("  floor: ").append(DungeonUtils.floor?.name ?: "-")
            .append("  boss: ").append(DungeonUtils.inBoss).append('\n')
        body.append("party: ").append(team.joinToString(", ") { (n, dead) -> if (dead) "$n(dead)" else n }).append('\n')
        body.append("hider on: ").append(ScoreboardLines.hideLines).append("  also hide: \"").append(ScoreboardLines.customPatterns).append("\"\n")
        all.forEachIndexed { i, entry ->
            val component = ScoreboardLines.lineText(scoreboard, entry)
            val raw = ScoreboardLines.toLegacy(component).replace('§', '&')
            val plain = ScoreboardLines.plain(component)
            val hit = ScoreboardLines.hides(plain)
            val state = when {
                entry.isHidden -> "holder"
                entry !in drawn -> "past15"
                hit -> "HIDE"
                else -> "show"
            }
            val mate = team.firstOrNull { (n, _) -> plain.contains(n, ignoreCase = true) }?.first
            body.append(String.format("%2d %6s %-6s | %s | %s | owner=%s", i, entry.value(), state, raw, plain, entry.owner()))
            if (mate != null && hit && entry in drawn) body.append("   !! TEAMMATE HIDDEN: ").append(mate)
            body.append('\n')
        }
        // Teammates with no line at all: not a hider problem, but worth seeing next to one.
        val lines = all.map { ScoreboardLines.plain(ScoreboardLines.lineText(scoreboard, it)) }
        team.filter { (n, _) -> lines.none { it.contains(n, ignoreCase = true) } }
            .forEach { (n, _) -> body.append("   (no sidebar line names ").append(n).append(")\n") }

        val snapshot = body.toString()
        if (snapshot == last) return
        last = snapshot
        write("=== ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))}\n$snapshot")
    }

    private fun write(text: String) {
        val path = file ?: EngineerClient.mc.gameDirectory.toPath().resolve("logs").resolve("engineerclient")
            .also { Files.createDirectories(it) }
            .resolve("scoreboard-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".log")
            .also { file = it; EngineerClient.logger.info("[ec] scoreboard recorder writing $it") }
        Files.writeString(path, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        if (Files.size(path) > MAX_BYTES) {
            full = true
            Files.writeString(path, "=== stopped at ${MAX_BYTES / 1024 / 1024} MB\n", StandardOpenOption.APPEND)
        }
    }
}
