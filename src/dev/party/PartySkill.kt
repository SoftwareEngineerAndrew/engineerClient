package com.devgineerclient.party

import com.devgineerclient.DevgineerClient
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.odtheking.odin.utils.skyblock.dungeon.terminals.TerminalTypes
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

/**
 * How long each player takes, from config/devgineerclient/party-skill.json (tools/partyskill/build.py over ADR runs): each
 * terminal type, open to done, and Simon Says, first start press to done. A player's own median once they have [MIN_RUNS]
 * of it, otherwise everyone's. Read again whenever the file changes.
 */
object PartySkill {
    private const val MIN_RUNS = 3
    private val file by lazy { FabricLoader.getInstance().configDir.resolve("devgineerclient").resolve("party-skill.json") }
    @Volatile private var table: JsonObject? = null
    private var readAt = -1L

    /** Whether there is a table to go by at all. */
    val loaded get() = load() != null

    /** Ms [name] takes on a [type] terminal, open to done; [own] whether it's their own history. */
    fun term(name: String, type: TerminalTypes): Pair<Long, Boolean>? {
        val t = load() ?: return null
        median(t.getAsJsonObject("players")?.getAsJsonObject(name.lowercase())?.getAsJsonObject("t")?.getAsJsonArray(type.name))?.let { return it to true }
        return median(t.getAsJsonObject("types")?.getAsJsonArray(type.name), 1)?.let { it to false }
    }

    /** Ms [name] takes on Simon Says, first start press to done; [own] whether it's their own history. */
    fun ss(name: String): Pair<Long, Boolean>? {
        val t = load() ?: return null
        median(t.getAsJsonObject("players")?.getAsJsonObject(name.lowercase())?.getAsJsonArray("ss"))?.let { return it to true }
        return median(t.getAsJsonArray("ss"), 1)?.let { it to false }
    }

    /** [median ms, count] as the tool writes it, when there are enough. */
    private fun median(a: com.google.gson.JsonArray?, min: Int = MIN_RUNS): Long? {
        if (a == null || a.size() < 2 || a[0].isJsonNull || a[1].asInt < min) return null
        return a[0].asLong.takeIf { it > 0 }
    }

    private fun load(): JsonObject? {
        DevgineerClient.safely("party skill") {
            val at = if (Files.exists(file)) Files.getLastModifiedTime(file).toMillis() else 0L
            if (at != readAt) {
                readAt = at
                table = if (at == 0L) null else JsonParser.parseString(Files.readString(file)).asJsonObject.takeIf { it["format"]?.asInt == 1 }
                if (table != null) DevgineerClient.logger.info("[dc] party skill: ${table!!.getAsJsonObject("players")?.size() ?: 0} players from ${table!!["runs"]} runs")
            }
        }
        return table
    }
}
