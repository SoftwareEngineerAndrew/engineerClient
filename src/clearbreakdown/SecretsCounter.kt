package com.engineerclient.clearbreakdown

import com.adm.AdmMod.scope
import com.adm.utils.handlers.schedule
import com.adm.utils.modMessage
import com.adm.utils.network.hypixelapi.RequestUtils
import com.adm.utils.skyblock.dungeon.DungeonPlayer
import com.adm.utils.skyblock.dungeon.DungeonUtils
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** ADM's Secrets Counter: each teammate's secrets this run from the API, at the end of any run, won or not. */
object SecretsCounter {
    /** Each teammate's lifetime secrets when last asked: the run start, or the end of their last run with us. */
    private val totals = ConcurrentHashMap<String, Long>()
    private var startTaken = false

    private const val START_LINE = "[NPC] Mort: Good luck."
    private const val END_MARK = "Catacombs - "
    private val startRegex = Regex("^\\[NPC] Mort: Good luck\\.$")
    private val endRegex = Regex("^\\s*(?:Master Mode )?(?:The )?Catacombs - (?:Entrance|Floor .{1,3})$")

    fun reset() {
        startTaken = false
    }

    fun onChat(message: String) {
        if (message.contains(END_MARK) && endRegex.containsMatchIn(message)) schedule(30) { report() }
        if (!startTaken && message.startsWith(START_LINE) && startRegex.containsMatchIn(message)) takeStart()
    }

    private fun takeStart() {
        val team = DungeonUtils.dungeonTeammatesNoSelf.toList().ifEmpty { return }
        startTaken = true
        scope.launch {
            for (player in team) {
                if (totals.containsKey(player.name)) continue
                RequestUtils.pullSecrets(player.name).onSuccess { totals[player.name] = it }
            }
        }
    }

    private fun report() {
        val team = DungeonUtils.dungeonTeammatesNoSelf.toList().ifEmpty { return }
        totals.keys.retainAll(team.mapTo(HashSet()) { it.name })

        scope.launch {
            val gained = HashMap<String, Long?>()
            for (player in team) {
                val before = totals[player.name]
                RequestUtils.pullSecrets(player.name).onSuccess { now ->
                    gained[player.name] = if (before != null) (now - before).coerceAtLeast(0L) else null
                    totals[player.name] = now
                }
            }
            show(team, gained)
        }
    }

    private fun show(team: List<DungeonPlayer>, gained: Map<String, Long?>) {
        for (player in team.sortedWith(compareBy<DungeonPlayer> { it.clazz.ordinal }.thenByDescending { gained[it.name] ?: -1L })) {
            val n = gained[player.name]
            modMessage("§${player.clazz.colorCode}${player.name} §7-> " + if (n == null) "§cFailed to get secrets" else "§f$n Secrets")
        }
    }
}
