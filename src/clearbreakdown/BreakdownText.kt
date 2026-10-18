package com.engineerclient.clearbreakdown

/** One player's line, in parts: the module puts a hover on [rooms] and on [secrets]. */
data class LineParts(val name: String, val rooms: String, val roomsHover: String, val secrets: String, val secretsHover: String)

/**
 * The words of a breakdown line:
 *
 *     Andrew: 3-5 12 secrets
 *
 * solo clears - all clears, then the run's secrets from the API ("?" when it didn't answer). The
 * rooms hover lists the rooms in clear order, the secrets hover what was found where ("~" for an
 * estimate, then what no room explains). Legacy § colours, like the rest of the mod's chat.
 */
object BreakdownText {
    fun line(name: String, colour: Char, clears: List<Clear>, secrets: PlayerSecrets?): LineParts {
        val rooms = "§f${clears.count { it.solo }}§7-§f${clears.size}"
        val roomsHover = if (clears.isEmpty()) "§7No rooms cleared"
        else clears.withIndex().joinToString("\n") { (i, c) -> "§7${i + 1}. §f${c.room}${if (c.solo) " §a(solo)" else ""}" }

        val total = secrets?.total
        val seen = secrets?.rooms.orEmpty().map { "§f${if (it.estimate) "~" else ""}${it.count} §7${it.room}" }
        val secretsHover = when {
            total == null -> (listOf("§7Hypixel API didn't answer; seen in rooms:") + seen).joinToString("\n")
            seen.isEmpty() && secrets.unknown == 0 -> "§7No secrets"
            else -> (seen + if (secrets.unknown > 0) listOf("§f${secrets.unknown} §7in unknown rooms") else emptyList()).joinToString("\n")
        }
        return LineParts("§$colour$name§7: ", rooms, roomsHover, "§f${total ?: "?"} §7secrets", secretsHover)
    }
}
