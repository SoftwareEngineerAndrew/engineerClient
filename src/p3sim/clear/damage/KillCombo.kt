package com.engineerclient.p3sim.clear.damage

import java.util.UUID

/**
 * The Kill Combo of a Grandma Wolf pet, as a player reads it in chat (ADR, 495 runs): each kill adds one, a tier line at 5, 10, 15, 20,
 * 25, 30, 50 and 75 kills, and 60 ticks without a kill end it with "Your Kill Combo has expired! You reached a N Kill Combo!" (only for a
 * combo of 5 or more; the shortest recorded gap from the last tier line to the expiry is exactly 60 ticks). Pure; the caller sends the lines.
 */
class KillCombo {
    private class State(var count: Int, var lastKill: Int)

    private val combos = HashMap<UUID, State>()

    /** A kill by [player] on run tick [t]: the tier line it reaches, or null. */
    fun kill(player: UUID, t: Int): String? {
        val s = combos[player]?.takeIf { t - it.lastKill < TIMEOUT } ?: State(0, t).also { combos[player] = it }
        s.count++
        s.lastKill = t
        return TIERS[s.count]
    }

    /** The combos that ran out by [t], each with its expiry line (null for one under 5 kills, which ends without a word). */
    fun expire(t: Int): List<Pair<UUID, String?>> {
        val out = ArrayList<Pair<UUID, String?>>()
        val it = combos.entries.iterator()
        while (it.hasNext()) {
            val (id, s) = it.next()
            if (t - s.lastKill < TIMEOUT) continue
            it.remove()
            out += id to (if (s.count >= 5) "§cYour Kill Combo has expired! You reached a ${s.count} Kill Combo!" else null)
        }
        return out
    }

    fun clear() = combos.clear()

    companion object {
        const val TIMEOUT = 60
        private const val MF3 = "§r§b+3%  Magic Find"
        private const val COINS = "§r§8+§r§610 §r§7coins per kill"
        val TIERS: Map<Int, String> = mapOf(
            5 to "§a§l+5 Kill Combo $MF3",
            10 to "§a§l+10 Kill Combo $COINS",
            15 to "§9§l+15 Kill Combo $MF3",
            20 to "§5§l+20 Kill Combo §r§3+15☯ Combat Wisdom",
            25 to "§5§l+25 Kill Combo $MF3",
            30 to "§6§l+30 Kill Combo $COINS",
            50 to "§6§l+50 Kill Combo §r§b+5%  Magic Find",
            75 to "§6§l+75 Kill Combo",
        )
    }
}
