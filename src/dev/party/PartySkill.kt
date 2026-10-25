package com.devgineerclient.party

import com.devgineerclient.DevgineerClient
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.odtheking.odin.utils.skyblock.dungeon.terminals.TerminalTypes
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

/**
 * How long each player takes, from config/devgineerclient/party-skill.json (tools/partyskill/build.py over ADR runs): each
 * terminal type open to done, its first click and the gap between clicks after, Simon Says first start to done and the gap
 * between presses. A player's own median once they have [MIN_RUNS] of it; otherwise everyone's, scaled by how much faster or
 * slower than everyone they are on that terminal type when that much is known. Read again whenever the file changes.
 */
object PartySkill {
    private const val MIN_RUNS = 3
    private val file by lazy { FabricLoader.getInstance().configDir.resolve("devgineerclient").resolve("party-skill.json") }
    @Volatile private var table: JsonObject? = null
    private var readAt = -1L
    /** When the file's time was last looked at: at most every [CHECK_MS]. */
    private var checkedAt = 0L
    private const val CHECK_MS = 5000L

    /** Ms [name] takes on a [type] terminal, open to done. */
    fun solveMs(name: String, type: TerminalTypes): Double? = own(name, "t", type) ?: everyone("types", type)

    /** Ms from opening a [type] terminal to [name]'s first click. */
    fun firstMs(name: String, type: TerminalTypes): Double? = own(name, "f", type) ?: everyone("first", type)?.times(speed(name, type))

    /** Ms between [name]'s clicks on a [type] terminal. */
    fun clickMs(name: String, type: TerminalTypes): Double? = own(name, "c", type) ?: everyone("click", type)?.times(speed(name, type))

    /** Ms between [name]'s presses within a Simon Says round. */
    fun pressMs(name: String): Double? = median(player(name)?.getAsJsonArray("press")) ?: median(load()?.getAsJsonArray("press"), 1)

    /** [name]'s time on [type] over everyone's (1 when not known). */
    private fun speed(name: String, type: TerminalTypes): Double {
        val mine = own(name, "t", type) ?: return 1.0
        val all = everyone("types", type) ?: return 1.0
        return (mine / all).coerceIn(0.5, 2.0)
    }

    private fun player(name: String): JsonObject? = load()?.getAsJsonObject("players")?.getAsJsonObject(name.lowercase())
    private fun own(name: String, field: String, type: TerminalTypes) = median(player(name)?.getAsJsonObject(field)?.getAsJsonArray(type.name))
    private fun everyone(field: String, type: TerminalTypes) = median(load()?.getAsJsonObject(field)?.getAsJsonArray(type.name), 1)

    /** [median ms, count] as the tool writes it, when there are enough. */
    private fun median(a: JsonArray?, min: Int = MIN_RUNS): Double? {
        if (a == null || a.size() < 2 || a[0].isJsonNull || a[1].asInt < min) return null
        return a[0].asDouble.takeIf { it > 0 }
    }

    private fun load(): JsonObject? {
        val now = System.currentTimeMillis()
        if (now - checkedAt < CHECK_MS) return table
        checkedAt = now
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
