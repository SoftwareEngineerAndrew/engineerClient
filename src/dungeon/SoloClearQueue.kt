package com.engineerclient.dungeon

import com.engineerclient.EngineerClient
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.brigadier.arguments.StringArgumentType
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.KeybindSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.StringSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.ModuleManager
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.utils.sendCommand
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.Floor
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal

object SoloClearQueue : Module(
    name = "Solo Clear Queue",
    category = Category.DUNGEON,
    description = "Requeues a solo F7 or M7 run that isn't worth finishing.",
    key = null,
) {
    private val autoSend by BooleanSetting("Auto Send", true, desc = "Sends /joininstance when a trigger fires; off only alerts.")
    private val requeueKey by KeybindSetting("Requeue Key", InputConstants.UNKNOWN, "Sends /joininstance for this floor.").onPress { manualRequeue() }
    private val fivePuzzles by BooleanSetting("Five Puzzle Trigger", true, desc = "Fires on a 5 puzzle map.")
    private val mapCycler by BooleanSetting("Map Cycler", false, desc = "At 19.5s, fires on too many secrets or a blacklisted room.")
    private val max2 by NumberSetting("2 Puzzle Max Secrets", 0, 0..120, 1, desc = "Most total secrets on a 2 puzzle map, 0 for no limit.").withDependency { mapCycler }
    private val max3 by NumberSetting("3 Puzzle Max Secrets", 0, 0..120, 1, desc = "Most total secrets on a 3 puzzle map, 0 for no limit.").withDependency { mapCycler }
    private val max4 by NumberSetting("4 Puzzle Max Secrets", 0, 0..120, 1, desc = "Most total secrets on a 4 puzzle map, 0 for no limit.").withDependency { mapCycler }
    private val max5 by NumberSetting("5 Puzzle Max Secrets", 0, 0..120, 1, desc = "Most total secrets on a 5 puzzle map, 0 for no limit.").withDependency { mapCycler }
    private val blacklist by StringSetting("Room Blacklist", "", 256, desc = "Room names, comma separated.", placeholder = "").withDependency { mapCycler }
    private val blacklistMin by NumberSetting("Blacklist Min Secrets", 0, 0..120, 1, desc = "Ignore the blacklist on maps with fewer secrets, 0 to always use it.").withDependency { mapCycler }
    private val pbTrigger by BooleanSetting("PB Trigger", false, desc = "Fires when the run time passes your PB.")
    private var pbF7 by StringSetting("F7 PB Time", "", 8, desc = "m:ss, or /scq pb.", placeholder = "5:00").withDependency { pbTrigger }
    private var pbM7 by StringSetting("M7 PB Time", "", 8, desc = "m:ss, or /scq pb.", placeholder = "5:00").withDependency { pbTrigger }
    private val maxRetries by NumberSetting("Max Retries", 3, 0..10, 1, desc = "Resends after a cooldown reply.")
    private val retryDelay by NumberSetting("Retry Delay", 2, 1..15, 1, desc = "Wait before a resend.", unit = "s")
    private val chatMessages by BooleanSetting("Chat Messages", true, desc = "A chat line for each trigger and send.")
    private val sound = SoundOption(this, "block.note_block.pling")

    private const val MORT = "[NPC] Mort: Here, I found this map when I first entered the dungeon."
    private val TIME_PART = Regex("""(\d+)([hms])""")
    private val SECONDS = Regex("""(\d{1,3})\s*s(?:ec|econds?)?\b""")

    private var fired = false
    private var mapChecked = false
    private var startedAt = 0L
    private var command: String? = null
    private var attempts = 0
    private var retryAt = 0L

    init {
        on<LevelEvent.Load> { reset() }

        on<MessageEvent.Chat> {
            if (message == MORT) startedAt = System.currentTimeMillis()
            else onReply(message.lowercase())
        }

        on<TickEvent.End> {
            retry()
            if (fired || !soloSeven() || DungeonUtils.inBoss) return@on
            if (fivePuzzles && puzzles() >= 5) return@on trigger("5 Puzzles")
            val t = elapsed() ?: return@on
            if (mapCycler && !mapChecked && t >= 19.5) {
                mapChecked = true
                checkMap()?.let { return@on trigger(it) }
            }
            if (pbTrigger) {
                val pb = parse(if (DungeonUtils.floor == Floor.M7) pbM7 else pbF7) ?: return@on
                if (t > pb) trigger("PB Pace Lost")
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

    private fun elapsed(): Double? {
        val tab = TIME_PART.findAll(DungeonUtils.dungeonTime).sumOf { m ->
            m.groupValues[1].toInt() * when (m.groupValues[2]) { "h" -> 3600; "m" -> 60; else -> 1 }
        }
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
        val m = Regex("""^\s*(\d{1,2}):([0-5]\d)\s*$""").find(s) ?: return null
        return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
    }

    private fun say(text: String) { if (chatMessages) EngineerClient.msg(text) }
}
