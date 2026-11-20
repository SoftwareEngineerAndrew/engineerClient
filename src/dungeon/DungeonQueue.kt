package com.engineerclient.dungeon

import com.engineerclient.EngineerClient
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.brigadier.arguments.StringArgumentType
import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.KeybindSetting
import com.adm.clickgui.settings.impl.NumberSetting
import com.adm.clickgui.settings.impl.StringSetting
import com.adm.events.LevelEvent
import com.adm.events.MessageEvent
import com.adm.events.PartyEvent
import com.adm.events.TickEvent
import com.adm.events.core.on
import com.adm.features.Category
import com.adm.features.Module
import com.adm.features.ModuleManager
import com.adm.features.impl.dungeon.map.DungeonScan
import com.adm.utils.Colors
import com.adm.utils.handlers.schedule
import com.adm.utils.sendCommand
import com.adm.utils.skyblock.PartyUtils
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.adm.utils.skyblock.dungeon.Floor
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal

object DungeonQueue : Module(
    name = "Dungeon Queue",
    category = Category.DUNGEON,
    description = "Requeuing, warp cooldowns and the kicked timer.",
    toggled = true,
) {
    private val warpCooldownHud by HUD("Warp Cooldown", "Time left until you can warp again.", true, 420, 70, 1f) { example ->
        val left = if (example) 2140L else endTime - System.currentTimeMillis()
        if (left <= 0) return@HUD 0 to 0
        val line = cooldownLine.at((left + 50) / 100)
        text(mc.font, line.text, 0, 0, Colors.WHITE.rgba, true)
        line.size
    }
    private val kickedHud by HUD("Kicked Timer", "Time since the kick, for a minute.", false, 10, 10, 1f) { example ->
        val elapsed = if (example) 12340L else System.currentTimeMillis() - kickedAt
        if (!example && (kickedAt == 0L || elapsed > 60_000)) return@HUD 0 to 0
        val line = kickedLine.at((elapsed + 5) / 10)
        text(mc.font, line.text, 0, 0, -1)
        line.size
    }
    private val announceKick by BooleanSetting("Announce Kick", false, desc = "Announce when you get kicked from skyblock.")

    private val autoRequeue by BooleanSetting("Auto Requeue", false, desc = "Automatically starts a new dungeon at the end of a dungeon.")
    private val requeueDelay by NumberSetting("Requeue Delay", 2, 0..30, 1, desc = "The delay in seconds before requeuing.", unit = "s").withDependency { autoRequeue }
    private val disablePartyLeave by BooleanSetting("Disable on leave/kick", true, desc = "Disables the requeue on party leave message.").withDependency { autoRequeue }
    private val requeueMinParty by NumberSetting("Requeue Min Party Size", 1, 1..5, 1, desc = "Skips the requeue when the party is smaller.").withDependency { autoRequeue }

    private val soloClear by BooleanSetting("Solo Clear Queue", false, desc = "Requeues a solo F7 or M7 run that isn't worth finishing.")
    private val autoSend by BooleanSetting("Auto Send", true, desc = "Sends /joininstance when a trigger fires; off only alerts.").withDependency { soloClear }
    private val requeueKey by KeybindSetting("Requeue Key", InputConstants.UNKNOWN, "Sends /joininstance for this floor.").onPress { manualRequeue() }
    private val fivePuzzles by BooleanSetting("Five Puzzle Trigger", true, desc = "Fires on a 5 puzzle map.").withDependency { soloClear }
    private val mapCycler by BooleanSetting("Map Cycler", false, desc = "At 19.5s, fires on too many secrets or a blacklisted room.").withDependency { soloClear }
    private val max2 by NumberSetting("2 Puzzle Max Secrets", 0, 0..120, 1, desc = "Most total secrets on a 2 puzzle map, 0 for no limit.").withDependency { soloClear && mapCycler }
    private val max3 by NumberSetting("3 Puzzle Max Secrets", 0, 0..120, 1, desc = "Most total secrets on a 3 puzzle map, 0 for no limit.").withDependency { soloClear && mapCycler }
    private val max4 by NumberSetting("4 Puzzle Max Secrets", 0, 0..120, 1, desc = "Most total secrets on a 4 puzzle map, 0 for no limit.").withDependency { soloClear && mapCycler }
    private val max5 by NumberSetting("5 Puzzle Max Secrets", 0, 0..120, 1, desc = "Most total secrets on a 5 puzzle map, 0 for no limit.").withDependency { soloClear && mapCycler }
    private val blacklist by StringSetting("Room Blacklist", "", 256, desc = "Room names, comma separated.", placeholder = "").withDependency { soloClear && mapCycler }
    private val blacklistMin by NumberSetting("Blacklist Min Secrets", 0, 0..120, 1, desc = "Ignore the blacklist on maps with fewer secrets, 0 to always use it.").withDependency { soloClear && mapCycler }
    private val pbTrigger by BooleanSetting("PB Trigger", false, desc = "Fires when the run time passes your PB.").withDependency { soloClear }
    private var pbF7 by StringSetting("F7 PB Time", "", 8, desc = "m:ss, or /scq pb.", placeholder = "5:00").withDependency { soloClear && pbTrigger }
    private var pbM7 by StringSetting("M7 PB Time", "", 8, desc = "m:ss, or /scq pb.", placeholder = "5:00").withDependency { soloClear && pbTrigger }
    private val maxRetries by NumberSetting("Max Retries", 3, 0..10, 1, desc = "Resends after a cooldown reply.").withDependency { soloClear }
    private val retryDelay by NumberSetting("Retry Delay", 2, 1..15, 1, desc = "Wait before a resend.", unit = "s").withDependency { soloClear }
    private val chatMessages by BooleanSetting("Chat Messages", true, desc = "A chat line for each trigger and send.").withDependency { soloClear }
    private val sound = SoundOption(this, "block.note_block.pling")

    private const val MORT = "[NPC] Mort: Here, I found this map when I first entered the dungeon."
    private val TIME_PART = Regex("""(\d+)([hms])""")
    private val SECONDS = Regex("""(\d{1,3})\s*s(?:ec|econds?)?\b""")
    private val PB_TIME = Regex("""^\s*(\d{1,2}):([0-5]\d)\s*$""")

    private val timeoutExeceptionRegex = Regex("^Exception Connecting:ReadTimeoutException : null$")
    private const val KICKED_INSTANCE = "You are no longer allowed to access this instance!"
    private const val KICKED_JOINING = "You were kicked while joining that server!"
    private val extraStatsRegex = Regex(" {29}> EXTRA STATS <")
    private val instanceRegex = Regex("(?:\\[[^]]+] )?\\w+ entered (?:MM )?\\w+ Catacombs, (?:Floor \\w+|Entrance)!")
    private val warpRegex = Regex("^Sending to server .+")

    private val cooldownLine = TimerLine("§bWarp Cooldown: §f", 10)
    private val kickedLine = TimerLine("§5Time kicked: §f", 100)

    private class TimerLine(private val prefix: String, private val scale: Int) {
        private var units = Long.MIN_VALUE
        var text = ""
        var size = 0 to 9

        fun at(value: Long): TimerLine {
            if (value == units) return this
            units = value
            val fraction = (value % scale).toString()
            text = buildString {
                append(prefix).append(value / scale).append('.')
                repeat(scale.toString().length - 1 - fraction.length) { append('0') }
                append(fraction).append('s')
            }
            size = mc.font.width(text) to 9
            return this
        }
    }

    var disableRequeue = false
    private var endTime = 0L
    private var kickedAt = 0L

    private var fired = false
    private var mapChecked = false
    private var startedAt = 0L
    private var command: String? = null
    private var attempts = 0
    private var retryAt = 0L

    init {
        on<LevelEvent.Load> { reset(); disableRequeue = false }

        on<PartyEvent.Leave> {
            if (autoRequeue && disablePartyLeave) disableRequeue = true
        }

        on<MessageEvent.Chat> {
            val kicked = message == KICKED_JOINING || message == KICKED_INSTANCE
            if (kicked && System.currentTimeMillis() - kickedAt > 60_000) kickedAt = System.currentTimeMillis()
            // entering a run restarts the 30s even inside a warp's 4s
            if (message.contains(" Catacombs, ") && instanceRegex.containsMatchIn(message)) endTime = System.currentTimeMillis() + 30_000L
            else if (endTime <= System.currentTimeMillis() && message.startsWith("Sending to server ") && warpRegex.containsMatchIn(message)) endTime = System.currentTimeMillis() + 4_000L
            when {
                announceKick && (kicked || message.matches(timeoutExeceptionRegex)) -> sendCommand("pc I was kicked!")
                autoRequeue && message.matches(extraStatsRegex) -> {
                    if (!disableRequeue.also { disableRequeue = false }) schedule(requeueDelay * 20) {
                        if (!disableRequeue && canRequeue()) sendCommand("instancerequeue")
                    }
                }
            }
            if (!soloClear) return@on
            if (message == MORT) startedAt = System.currentTimeMillis()
            else if (command != null) onReply(message.lowercase())
        }

        on<TickEvent.End> {
            if (!soloClear) return@on
            retry()
            if (fired || !soloSeven() || DungeonUtils.inBoss) return@on
            if (fivePuzzles && puzzles() >= 5) return@on trigger("5 Puzzles")
            val cycle = mapCycler && !mapChecked
            if (!cycle && !pbTrigger) return@on
            val t = elapsed() ?: return@on
            if (cycle && t >= 19.5) {
                mapChecked = true
                checkMap()?.let { return@on trigger(it) }
            }
            if (pbTrigger) {
                val pb = pbSeconds(if (DungeonUtils.floor == Floor.M7) pbM7 else pbF7)
                if (pb >= 0 && t > pb) trigger("PB Pace Lost")
            }
        }

        ClientCommandRegistrationCallback.EVENT.register { d, _ ->
            d.register(literal("scq").then(literal("pb").then(argument("time", StringArgumentType.word()).executes { c ->
                setPb(StringArgumentType.getString(c, "time")); 1
            })))
        }
    }

    private fun reset() {
        fired = false; mapChecked = false; startedAt = 0L
        command = null; attempts = 0; retryAt = 0L
    }

    private fun soloSeven() = DungeonUtils.inDungeons && DungeonUtils.dungeonTeammates.size == 1 &&
        (DungeonUtils.floor == Floor.F7 || DungeonUtils.floor == Floor.M7)

    private fun joinCommand() =
        if (DungeonUtils.floor?.isMM == true) "joininstance master_catacombs_floor_seven" else "joininstance catacombs_floor_seven"

    private fun puzzles() = maxOf(DungeonUtils.puzzleCount, DungeonUtils.puzzles.size)

    private var tabTime = ""
    private var tabSeconds = 0

    private var pbInput = ""
    private var pbParsed = -1

    private fun pbSeconds(input: String): Int {
        if (input != pbInput) {
            pbInput = input
            pbParsed = parse(input) ?: -1
        }
        return pbParsed
    }

    private fun elapsed(): Double? {
        val time = DungeonUtils.dungeonTime
        if (time != tabTime) {
            tabTime = time
            tabSeconds = TIME_PART.findAll(time).sumOf { m ->
                m.groupValues[1].toInt() * when (m.groupValues[2]) { "h" -> 3600; "m" -> 60; else -> 1 }
            }
        }
        val tab = tabSeconds
        val own = if (startedAt > 0) (System.currentTimeMillis() - startedAt) / 1000.0 else 0.0
        return maxOf(tab.toDouble(), own).takeIf { it > 0 }
    }

    private fun totalSecrets(): Int =
        DungeonUtils.totalSecrets.takeIf { it > 0 } ?: DungeonScan.rooms.sumOf { it.data?.maxSecrets ?: 0 }

    private fun checkMap(): String? {
        val total = totalSecrets()
        val limit = when (puzzles()) { 2 -> max2; 3 -> max3; 4 -> max4; 5 -> max5; else -> 0 }
        if (limit > 0 && total > limit) return "Too Many Secrets ($total)"
        if (blacklistMin > 0 && total < blacklistMin) return null
        val names = blacklist.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        if (names.isEmpty()) return null
        val hit = DungeonScan.rooms.firstNotNullOfOrNull { r -> r.data?.name?.takeIf { it.lowercase() in names } }
        return hit?.let { "Blacklisted Room: $it" }
    }

    private fun trigger(reason: String) {
        fired = true
        showTitle("&c$reason", 2.0)
        sound.play()
        if (!autoSend) { say("§c$reason"); return }
        val cmd = joinCommand()
        say("§c$reason§7, sending /$cmd")
        command = cmd; attempts = 1; retryAt = 0L
        sendCommand(cmd)
    }

    // A party whose leader is unknown (joined before launch) still requeues.
    private fun canRequeue(): Boolean {
        if (!PartyUtils.isInParty) return requeueMinParty <= 1
        if (PartyUtils.partyLeader != null && !PartyUtils.isLeader()) return false
        return PartyUtils.members.size >= requeueMinParty
    }

    private fun manualRequeue() {
        if (!DungeonUtils.inDungeons) return
        val cmd = joinCommand()
        say("§7Sending /$cmd")
        sendCommand(cmd)
    }

    private fun onReply(msg: String) {
        if (command == null) return
        if (msg.contains("you are already queued") || msg.contains("already queued for this instance")) {
            command = null; retryAt = 0L
            return
        }
        if (!(msg.contains("instance") || msg.contains("queue"))) return
        if (!(msg.contains("cooldown") || msg.contains("wait") || msg.contains("again"))) return
        if (attempts > maxRetries) {
            say("§cGave up after $attempts tries")
            command = null; retryAt = 0L
            return
        }
        val wait = SECONDS.find(msg)?.groupValues?.get(1)?.toLongOrNull()?.let { it * 1000 + 250 } ?: 0L
        retryAt = System.currentTimeMillis() + maxOf(retryDelay * 1000L, wait)
    }

    private fun retry() {
        val cmd = command ?: return
        if (retryAt == 0L || System.currentTimeMillis() < retryAt) return
        retryAt = 0L
        attempts++
        say("§7Retrying /$cmd ($attempts)")
        sendCommand(cmd)
    }

    private fun setPb(input: String) {
        val secs = parse(input)
        if (secs == null) { EngineerClient.msg("§cUse m:ss, like 5:30"); return }
        val m7 = DungeonUtils.inDungeons && DungeonUtils.floor == Floor.M7
        val text = "%d:%02d".format(secs / 60, secs % 60)
        if (m7) pbM7 = text else pbF7 = text
        ModuleManager.saveConfigurations()
        EngineerClient.msg("§7${if (m7) "M7" else "F7"} PB set to §a$text")
    }

    private fun parse(s: String): Int? {
        val m = PB_TIME.find(s) ?: return null
        return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
    }

    private fun say(text: String) { if (chatMessages) EngineerClient.msg(text) }
}
