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

    private val extraStats = PostDungeonStats()

    private val terminalCompleteRegex = Regex("^\\s*☠ Defeated (.+) in 0?([\\dhms ]+?)\\s*(\\(NEW RECORD!\\))?$")
    private val teamScoreRegex = Regex("^\\s*Team Score: (\\d+) \\((.{1,2})\\)\\s?(\\(NEW RECORD!\\))?$")
    private val expRegex = Regex("^\\s*(\\+[\\d,.]+\\s?\\w+ Experience)\\s?(?:\\(.+\\))?$")
    private val bitsRegex = Regex("^\\s*(\\+\\d+ Bits)$")
    private val damageRegex = Regex("^\\s*(Total Damage as .+: ([\\d,.]+)\\s?(\\(NEW RECORD!\\))?)$")
    private val healRegex = Regex("^\\s*(Ally Healing: ([\\d,.]+)\\s?(\\(NEW RECORD!\\))?)$")
    private val enemyKillRegex = Regex("^\\s*(Enemies Killed: (\\d+)\\s?(\\(NEW RECORD!\\))?)$")
    private val deathsRegex = Regex("^\\s*(Deaths: (\\d+))$")
    private val secretsRegex = Regex("^\\s*(Secrets Found: (\\d+))$")
    private val extraStatsRegex = Regex(" {29}> EXTRA STATS <")

    private val cancelRegexes = listOf(
        Regex(" {29}> EXTRA STATS <"),
        Regex("^\\s*☠ Defeated (.+) in 0?([\\dhms ]+?)\\s*(\\(NEW RECORD!\\))?$"),
        Regex("^\\s*Team Score: \\d+ \\(.{1,2}\\)\\s?(?:\\(NEW RECORD!\\))?$"),
        Regex("^\\s*(\\+[\\d,.]+\\s?\\w+ Experience)\\s?(?:\\(.+\\))?$"),
        Regex("^\\s*(Master Mode)? ?(?:The)? Catacombs - (Entrance|Floor .{1,3})$"),
        Regex("^\\s*Secrets Found: \\d+$"),
        Regex("^▬+$"),
        Regex("^\\s*Total Damage as .+: [\\d,.]+\\s?(?:\\(NEW RECORD!\\))?$"),
        Regex("^\\s*Ally Healing: [\\d,.]+\\s?(?:\\(NEW RECORD!\\))?$"),
        Regex("^\\s*\\+0 Experience \\(No Class Milestone Reached\\)$"),
        Regex("^\\s*The Catacombs - .+ Stats$"),
        Regex("^\\s*Deaths: \\d+$"),
        Regex("^\\s*Master Mode Catacombs - .+ Stats$"),
        Regex("^\\s*Master Mode The Catacombs - .+ Stats$"),
        Regex("^\\s*\\+(\\d+) Bits$"),
        Regex("^\\s*Enemies Killed: \\d+\\s?(?:\\(NEW RECORD!\\))?$")
    )

    fun install() {
        on<MessageEvent.Chat> {
            if (!DungeonUtils.inDungeons || !ClearInfo.extraStatsOn) return@on

            if (extraStatsRegex.matches(message)) return@on sendCommand("showextrastats")
            if (cancelRegexes.any { it.matches(message) }) cancel()

            terminalCompleteRegex.find(message)?.let {
                extraStats.timePB = it.groupValues[3].isNotEmpty()
                extraStats.bossKilled = it.groupValues[1]
                return@on
            }

            teamScoreRegex.find(message)?.let {
                extraStats.score = it.groupValues[1].toIntOrNull() ?: 0
                extraStats.scorePB = it.groupValues[3].isNotEmpty()
                extraStats.scoreLetter = it.groupValues[2]
                return@on
            }

            expRegex.find(message)?.let {
                extraStats.xp.add("§3${it.groupValues[1].replace("Experience", "EXP").replace("Catacombs", "Cata")}")
                return@on
            }

            bitsRegex.find(message)?.let {
                extraStats.bits = it.groupValues[1]
                return@on
            }

            damageRegex.find(message)?.let {
                extraStats.damagePB = it.groupValues[3].isNotEmpty()
                extraStats.damage = formatNumber(it.groupValues[2]) + if (extraStats.damagePB) "(NEW PB!)" else ""
                extraStats.combatStats.add("§e${it.groupValues[1]}")
                return@on
            }

            healRegex.find(message)?.let {
                extraStats.healPB = it.groupValues[3].isNotEmpty()
                extraStats.heal = formatNumber(it.groupValues[2]) + if (extraStats.healPB) "(NEW PB!)" else ""
                extraStats.combatStats.add("§a${it.groupValues[1]}")
                return@on
            }

            enemyKillRegex.find(message)?.let {
                extraStats.enemyKillPB = it.groupValues[3].isNotEmpty()
                extraStats.enemyKill = (it.groupValues[2].toIntOrNull() ?: 0).toString() + if (extraStats.enemyKillPB) "(NEW PB!)" else ""
                extraStats.combatStats.add(1, "§b${it.groupValues[1]}")
                return@on
            }

            deathsRegex.find(message)?.let {
                extraStats.deaths = it.groupValues[2].toIntOrNull() ?: 0
                if (ClearInfo.teamStats.personal) extraStats.skillStats.add("§c${it.groupValues[1]}")
                return@on
            }

            secretsRegex.find(message)?.let {
                extraStats.secretsFound = it.groupValues[2].toIntOrNull() ?: 0
                if (ClearInfo.teamStats.personal) extraStats.skillStats.add(0, "§b${it.groupValues[1]}")
                if (ClearInfo.teamStats == TeamStats.BOTH) extraStats.skillStats.add("")
                if (ClearInfo.teamStats.team) {
                    extraStats.skillStats.add("§bTeam Secrets Found: ${DungeonUtils.secretCount}")
                    extraStats.skillStats.add("§6Crypt: ${DungeonUtils.cryptCount}")
                    extraStats.skillStats.add("§cTeam Deaths: ${DungeonUtils.deathCount}")
                }
                printEndStats()
                return@on
            }
        }

        on<LevelEvent.Load> {
            extraStats.reset()
        }
        EventBus.subscribe(this)
    }

    private fun printEndStats() {
        val defeatedText = if (extraStats.bossKilled == null) "§c§lFAILED §a- §e${DungeonUtils.dungeonTime}"
        else "§aDefeated §c${extraStats.bossKilled} §ain §e${DungeonUtils.dungeonTime}${if (extraStats.timePB) " §d§l(NEW RECORD!)" else ""}"

        val message = Component.literal(getChatBreak())
            .append("\n\n")
            .append(getCenteredText((if (DungeonUtils.floor?.isMM == true) "§cMaster Mode" else "§cThe Catacombs") + " §r- §e${DungeonUtils.floor?.name}"))
            .append("\n\n")
            .append(getCenteredText(defeatedText))
            .append("\n")
            .append(getCenteredText("§aScore: §6${extraStats.score} §a(§b${extraStats.scoreLetter}§a)${if (extraStats.scorePB) " §d§l(NEW RECORD!)" else ""}${if (extraStats.bits != null && ClearInfo.showBits) "    §b${extraStats.bits}" else ""}"))
            .append("\n")

        val xpText = "${extraStats.xp.firstOrNull() ?: ""}${if (ClearInfo.showClassEXP) "  ${extraStats.xp.getOrNull(1) ?: ""}" else ""}"
        message.append(
            Component.literal(getCenteredText(xpText)).withStyle {
                it.withClickEvent(ClickEvent.SuggestCommand(extraStats.xp.joinToString("\n")))
                    .withHoverEvent(HoverEvent.ShowText(Component.literal(extraStats.xp.joinToString("\n"))))
            }
        ).append("\n")

        if (ClearInfo.showCombatStats) {
            message.append(
                Component.literal(getCenteredText("§e${extraStats.damage}§r-§b${extraStats.enemyKill}§r-§a${extraStats.heal}")).withStyle {
                    it.withClickEvent(ClickEvent.SuggestCommand(extraStats.combatStats.joinToString("\n")))
                        .withHoverEvent(HoverEvent.ShowText(Component.literal(extraStats.combatStats.joinToString("\n"))))
                }
            ).append("\n")
        }

        if (ClearInfo.teamStats != TeamStats.OFF) {
            val statsText = when (ClearInfo.teamStats) {
                TeamStats.PERSONAL -> "§b${extraStats.secretsFound}§r-§c${extraStats.deaths}"
                TeamStats.TEAM -> "§b${DungeonUtils.secretCount}§r-§6${DungeonUtils.cryptCount}§r-§c${DungeonUtils.deathCount}"
                TeamStats.BOTH -> "§b${extraStats.secretsFound}§r-§c${extraStats.deaths} §r/ §b${DungeonUtils.secretCount}§r-§6${DungeonUtils.cryptCount}§r-§c${DungeonUtils.deathCount}"
                TeamStats.OFF -> ""
            }
            message.append(
                Component.literal(getCenteredText(statsText)).withStyle {
                    it.withClickEvent(ClickEvent.SuggestCommand(extraStats.skillStats.joinToString("\n")))
                        .withHoverEvent(HoverEvent.ShowText(Component.literal(extraStats.skillStats.joinToString("\n"))))
                }
            ).append("\n")
        }

        if (ClearInfo.showTeammates) {
            message.append(
                getCenteredText(
                    if (DungeonUtils.dungeonTeammatesNoSelf.isNotEmpty())
                        DungeonUtils.dungeonTeammatesNoSelf.joinToString(separator = "§r, ") { "§${it.clazz.colorCode}${it.name}" }
                    else "§3Solo"
                )
            ).append("\n")
        }

        message.append("\n").append(Component.literal(getChatBreak()))

        modMessage(message, "")
    }

    private data class PostDungeonStats(
        var score: Int = 0,
        var scoreLetter: String? = null,
        var bossKilled: String? = null,
        var xp: MutableList<String> = mutableListOf(),
        var timePB: Boolean = false,
        var scorePB: Boolean = false,
        var combatStats: MutableList<String> = mutableListOf(),
        var damage: String? = "0",
        var damagePB: Boolean = false,
        var enemyKill: String? = "0",
        var enemyKillPB: Boolean = false,
        var heal: String? = "0",
        var healPB: Boolean = false,
        var skillStats: MutableList<String> = mutableListOf(),
        var deaths: Int = 0,
        var secretsFound: Int = 0,
        var bits: String? = null
    ) {
        fun reset() {
            scoreLetter = null
            bossKilled = null
            secretsFound = 0
            scorePB = false
            timePB = false
            xp.clear()
            score = 0
            deaths = 0
            heal = "0"
            healPB = false
            damage = "0"
            damagePB = false
            enemyKill = "0"
            enemyKillPB = false
            bits = null
            skillStats.clear()
            combatStats.clear()
        }
    }
}