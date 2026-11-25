package com.adm.features.impl.boss

import com.engineerclient.dungeon.ClearInfo
import com.adm.events.LevelEvent
import com.adm.events.MessageEvent
import com.adm.events.core.EventBus
import com.adm.events.core.on
import com.adm.utils.*
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent

/** Clear Info's Extra Stats: Hypixel's end of run stats as one message. */
object ExtraStats {
    enum class TeamStats(val personal: Boolean = false, val team: Boolean = false) {
        OFF,
        PERSONAL(personal = true),
        TEAM(team = true),
        BOTH(personal = true, team = true)
    }

    private const val SHOW_EXTRA_STATS = "                             > EXTRA STATS <"
    // Every stats line starts, after its indent, with one of these.
    private const val LEADS = "☠T+MCS▬ADE"

    private val defeated = Regex("^\\s*☠ Defeated (.+) in 0?([\\dhms ]+?)\\s*(\\(NEW RECORD!\\))?$")
    private val teamScore = Regex("^\\s*Team Score: (\\d+) \\((.{1,2})\\)\\s?(\\(NEW RECORD!\\))?$")
    private val experience = Regex("^\\s*(\\+[\\d,.]+\\s?\\w+ Experience)\\s?(?:\\(.+\\))?$")
    private val bitsLine = Regex("^\\s*(\\+\\d+ Bits)$")
    private val damageLine = Regex("^\\s*(Total Damage as .+: ([\\d,.]+)\\s?(\\(NEW RECORD!\\))?)$")
    private val healLine = Regex("^\\s*(Ally Healing: ([\\d,.]+)\\s?(\\(NEW RECORD!\\))?)$")
    private val killsLine = Regex("^\\s*(Enemies Killed: (\\d+)\\s?(\\(NEW RECORD!\\))?)$")
    private val deathsLine = Regex("^\\s*(Deaths: (\\d+))$")
    private val secretsLine = Regex("^\\s*(Secrets Found: (\\d+))$")

    // Hidden but not read.
    private val hiddenOnly = arrayOf(
        Regex("^\\s*(Master Mode)? ?(?:The)? Catacombs - (Entrance|Floor .{1,3})$"),
        Regex("^▬+$"),
        Regex("^\\s*\\+0 Experience \\(No Class Milestone Reached\\)$"),
        Regex("^\\s*The Catacombs - .+ Stats$"),
        Regex("^\\s*Master Mode Catacombs - .+ Stats$"),
        Regex("^\\s*Master Mode The Catacombs - .+ Stats$"),
    )

    private val run = RunStats()
    /** How many summaries have printed: Clear Breakdown posts after this run's. */
    @Volatile var summaries = 0
        private set

    fun install() {
        on<MessageEvent.Chat> {
            if (!DungeonUtils.inDungeons || !ClearInfo.extraStatsOn) return@on
            if (message == SHOW_EXTRA_STATS) return@on sendCommand("showextrastats")
            if (!mayBeStatsLine(message)) return@on
            if (read(message)) cancel()
            else for (r in hiddenOnly) if (r.matches(message)) return@on cancel()
        }

        on<LevelEvent.Load> { run.reset() }
        EventBus.subscribe(this)
    }

    // Java's \s: these six, nothing else.
    private fun mayBeStatsLine(s: String): Boolean {
        for (c in s) {
            if (c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r') continue
            return LEADS.indexOf(c) >= 0
        }
        return false
    }

    private fun read(line: String): Boolean {
        defeated.find(line)?.let {
            run.timePB = it.groupValues[3].isNotEmpty()
            run.bossKilled = it.groupValues[1]
            return it.value.length == line.length
        }
        teamScore.find(line)?.let {
            run.score = it.groupValues[1].toIntOrNull() ?: 0
            run.scorePB = it.groupValues[3].isNotEmpty()
            run.scoreLetter = it.groupValues[2]
            return it.value.length == line.length
        }
        experience.find(line)?.let {
            run.xp.add("§3${it.groupValues[1].replace("Experience", "EXP").replace("Catacombs", "Cata")}")
            return it.value.length == line.length
        }
        bitsLine.find(line)?.let {
            run.bits = it.groupValues[1]
            return it.value.length == line.length
        }
        damageLine.find(line)?.let {
            run.damage = formatNumber(it.groupValues[2]) + pb(it.groupValues[3])
            run.combatStats.add("§e${it.groupValues[1]}")
            return it.value.length == line.length
        }
        healLine.find(line)?.let {
            run.heal = formatNumber(it.groupValues[2]) + pb(it.groupValues[3])
            run.combatStats.add("§a${it.groupValues[1]}")
            return it.value.length == line.length
        }
        killsLine.find(line)?.let {
            run.enemyKill = (it.groupValues[2].toIntOrNull() ?: 0).toString() + pb(it.groupValues[3])
            // after the damage line when Hypixel sent one
            run.combatStats.add(minOf(1, run.combatStats.size), "§b${it.groupValues[1]}")
            return it.value.length == line.length
        }
        deathsLine.find(line)?.let {
            run.deaths = it.groupValues[2].toIntOrNull() ?: 0
            if (ClearInfo.teamStats.personal) run.skillStats.add("§c${it.groupValues[1]}")
            return it.value.length == line.length
        }
        secretsLine.find(line)?.let {
            run.secretsFound = it.groupValues[2].toIntOrNull() ?: 0
            val mode = ClearInfo.teamStats
            if (mode.personal) run.skillStats.add(0, "§b${it.groupValues[1]}")
            if (mode == TeamStats.BOTH) run.skillStats.add("")
            if (mode.team) {
                run.skillStats.add("§bTeam Secrets Found: ${DungeonUtils.secretCount}")
                run.skillStats.add("§6Crypt: ${DungeonUtils.cryptCount}")
                run.skillStats.add("§cTeam Deaths: ${DungeonUtils.deathCount}")
            }
            printSummary()
            return it.value.length == line.length
        }
        return false
    }

    private fun pb(record: String) = if (record.isNotEmpty()) "(NEW PB!)" else ""

    private fun withDetails(text: String, details: List<String>): Component {
        val joined = details.joinToString("\n")
        return Component.literal(getCenteredText(text)).withStyle {
            it.withClickEvent(ClickEvent.SuggestCommand(joined)).withHoverEvent(HoverEvent.ShowText(Component.literal(joined)))
        }
    }

    private fun printSummary() {
        val floor = DungeonUtils.floor
        val time = DungeonUtils.dungeonTime
        val boss = run.bossKilled
        val result = if (boss == null) "§c§lFAILED §a- §e$time"
            else "§aDefeated §c$boss §ain §e$time${if (run.timePB) " §d§l(NEW RECORD!)" else ""}"
        val bits = run.bits
        val scoreText = "§aScore: §6${run.score} §a(§b${run.scoreLetter}§a)${if (run.scorePB) " §d§l(NEW RECORD!)" else ""}" +
            if (bits != null && ClearInfo.showBits) "    §b$bits" else ""

        val out = Component.literal(getChatBreak())
            .append("\n\n")
            .append(getCenteredText((if (floor?.isMM == true) "§cMaster Mode" else "§cThe Catacombs") + " §r- §e${floor?.name}"))
            .append("\n\n")
            .append(getCenteredText(result))
            .append("\n")
            .append(getCenteredText(scoreText))
            .append("\n")

        val xp = run.xp
        out.append(withDetails("${xp.firstOrNull() ?: ""}${if (ClearInfo.showClassEXP) "  ${xp.getOrNull(1) ?: ""}" else ""}", xp)).append("\n")

        if (ClearInfo.showCombatStats)
            out.append(withDetails("§e${run.damage}§r-§b${run.enemyKill}§r-§a${run.heal}", run.combatStats)).append("\n")

        val personal = "§b${run.secretsFound}§r-§c${run.deaths}"
        val team = "§b${DungeonUtils.secretCount}§r-§6${DungeonUtils.cryptCount}§r-§c${DungeonUtils.deathCount}"
        val statsText = when (ClearInfo.teamStats) {
            TeamStats.OFF -> null
            TeamStats.PERSONAL -> personal
            TeamStats.TEAM -> team
            TeamStats.BOTH -> "$personal §r/ $team"
        }
        if (statsText != null) out.append(withDetails(statsText, run.skillStats)).append("\n")

        if (ClearInfo.showTeammates) {
            val others = DungeonUtils.dungeonTeammatesNoSelf
            out.append(hexText(getCenteredText(
                if (others.isEmpty()) "§3Solo" else others.joinToString(separator = "§r§f, ") { "${it.clazz.hexCode}${it.name}" }
            ))).append("\n")
        }

        out.append("\n").append(Component.literal(getChatBreak()))
        modMessage(out, "")
        summaries++
        run.reset()
    }

    private class RunStats {
        var score = 0
        var scoreLetter: String? = null
        var bossKilled: String? = null
        val xp = ArrayList<String>()
        var timePB = false
        var scorePB = false
        val combatStats = ArrayList<String>()
        var damage = "0"
        var enemyKill = "0"
        var heal = "0"
        val skillStats = ArrayList<String>()
        var deaths = 0
        var secretsFound = 0
        var bits: String? = null

        fun reset() {
            score = 0; scoreLetter = null; bossKilled = null
            timePB = false; scorePB = false
            xp.clear(); combatStats.clear(); skillStats.clear()
            damage = "0"; enemyKill = "0"; heal = "0"
            deaths = 0; secretsFound = 0; bits = null
        }
    }
}
