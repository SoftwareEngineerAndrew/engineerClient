package com.engineerclient.skyblock

import com.engineerclient.EngineerClient
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.mojang.brigadier.arguments.StringArgumentType
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import java.nio.file.Files
import java.time.LocalDate

object RunsLogger : Module(
    name = "Runs Logger",
    category = Category.DUNGEON,
    description = "Saves every finished run; /runslog <floor> shows your stats.",
    key = null,
) {
    data class Run(
        val date: String = "",
        val at: Long = 0,
        val floor: String = "",
        val seconds: Int = 0,
        val score: Int = 0,
        val rank: String = "",
        val deaths: Int = 0,
        val secrets: Int = 0,
        val pb: Boolean = false,
        val party: List<String> = emptyList(),
    )

    private val file get() = EngineerClient.mc.gameDirectory.toPath().resolve("config").resolve("engineerclient").resolve("runs-log.json")
    private val gson = GsonBuilder().create()
    private val runs: MutableList<Run> by lazy { load() }

    private val scoreRegex = Regex("^\\s*Team Score: (\\d+) \\((.{1,2})\\)")
    private val defeatedRegex = Regex("^\\s*☠ Defeated .+ in (?:(\\d+)h )?(?:(\\d+)m )?(\\d+)s( \\(NEW RECORD!\\))?$")

    private var pending: Run? = null

    init {
        on<MessageEvent.Chat> {
            if (!enabled || !DungeonUtils.inDungeons) return@on
            val text = message.replace(Regex("§."), "")
            scoreRegex.find(text)?.let { m ->
                if (pending != null) return@on
                pending = Run(
                    date = LocalDate.now().toString(),
                    at = System.currentTimeMillis(),
                    floor = DungeonUtils.floor?.name ?: "?",
                    score = m.groupValues[1].toInt(),
                    rank = m.groupValues[2],
                    deaths = DungeonUtils.deathCount,
                    secrets = DungeonUtils.secretCount,
                    party = DungeonUtils.dungeonTeammates.map { it.name },
                )
                return@on
            }
            defeatedRegex.find(text)?.let { m ->
                val run = pending ?: return@on
                val (h, min, s) = (1..3).map { m.groupValues[it].toIntOrNull() ?: 0 }
                save(run.copy(seconds = h * 3600 + min * 60 + s, pb = m.groupValues[4].isNotEmpty()))
            }
        }
        on<LevelEvent.Unload> {
            pending?.let { save(it) }
            pending = null
        }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(literal("runslog")
                .executes { show(DungeonUtils.floor?.name ?: runs.lastOrNull()?.floor ?: "F7", false); 1 }
                .then(argument("floor", StringArgumentType.word())
                    .executes { show(StringArgumentType.getString(it, "floor").uppercase(), false); 1 }
                    .then(literal("today").executes { show(StringArgumentType.getString(it, "floor").uppercase(), true); 1 })))
        }
    }

    private fun save(run: Run) {
        pending = null
        runs.add(run)
        try {
            Files.createDirectories(file.parent)
            Files.writeString(file, gson.toJson(runs))
        } catch (t: Throwable) {
            EngineerClient.logger.warn("[ec] runs log save failed", t)
        }
    }

    private fun load(): MutableList<Run> = try {
        if (Files.exists(file)) gson.fromJson<MutableList<Run>>(Files.readString(file), object : TypeToken<MutableList<Run>>() {}.type) ?: mutableListOf()
        else mutableListOf()
    } catch (t: Throwable) {
        EngineerClient.logger.warn("[ec] runs log read failed", t)
        mutableListOf()
    }

    private fun show(floor: String, today: Boolean) {
        val list = runs.filter { it.floor == floor && (!today || it.date == LocalDate.now().toString()) }
        if (list.isEmpty()) return EngineerClient.msg("§7no logged $floor runs${if (today) " today" else ""}.")
        val sPlus = list.count { it.rank == "S+" }
        val s = list.count { it.rank == "S" }
        val timed = list.filter { it.seconds > 0 }
        val fastest = list.filter { it.rank == "S+" && it.seconds > 0 }.minByOrNull { it.seconds }
        EngineerClient.msg("§b$floor${if (today) " today" else ""}§7: §f${list.size} runs §8| §6S+ $sPlus §8| §eS $s §8| §7other ${list.size - sPlus - s}")
        if (timed.isNotEmpty()) EngineerClient.msg("§7avg §f${fmt(timed.sumOf { it.seconds } / timed.size)}§7, fastest S+ §a${fastest?.let { fmt(it.seconds) } ?: "-"}§7, secrets/run §f${"%.1f".format(list.sumOf { it.secrets }.toDouble() / list.size)}")
        val mates = list.flatMap { it.party }.filter { it != EngineerClient.mc.player?.name?.string }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(5)
        if (mates.isNotEmpty()) EngineerClient.msg("§7most played with: " + mates.joinToString("§7, ") { "§f${it.key} §8(${it.value})" })
    }

    private fun fmt(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)
}
