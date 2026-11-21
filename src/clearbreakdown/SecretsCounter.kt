package com.engineerclient.clearbreakdown

import com.adm.AdmMod.mc
import com.adm.AdmMod.scope
import com.adm.utils.handlers.schedule
import com.adm.utils.modMessage
import com.adm.utils.network.hypixelapi.RequestUtils
import com.adm.utils.skyblock.dungeon.DungeonPlayer
import com.adm.utils.skyblock.dungeon.DungeonUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** ADM's Secrets Counter: each player's secrets this run at the end of any run, won or not; yours from the stats box, teammates' from the API. */
object SecretsCounter {
    /** Each teammate's lifetime secrets when last asked: the run start, or the end of the run. */
    private val totals = ConcurrentHashMap<String, Long>()
    private var startTaken = false
    @Volatile private var own: Long? = null

    private const val START_LINE = "[NPC] Mort: Good luck."
    private const val END_MARK = "Catacombs - "
    private const val OWN_MARK = "Secrets Found: "
    private val startRegex = Regex("^\\[NPC] Mort: Good luck\\.$")
    private val endRegex = Regex("^\\s*(?:Master Mode )?(?:The )?Catacombs - (?:Entrance|Floor .{1,3})$")
    private val ownRegex = Regex("^\\s*Secrets Found: (\\d+)$")

    fun reset() {
        startTaken = false
        own = null
    }

    fun onChat(message: String) {
        if (message.contains(END_MARK) && endRegex.containsMatchIn(message)) {
            own = null
            schedule(30) { report() }
        }
        if (message.contains(OWN_MARK)) ownRegex.find(message)?.let { own = it.groupValues[1].toLongOrNull() }
        if (!startTaken && message.startsWith(START_LINE) && startRegex.containsMatchIn(message)) takeStart()
    }

    private fun takeStart() {
        val team = DungeonUtils.dungeonTeammatesNoSelf.toList().ifEmpty { return }
        startTaken = true
        scope.launch {
            for (player in team) {
                RequestUtils.pullSecrets(player.name).onSuccess { totals[player.name] = it }
            }
        }
    }

    private fun report() {
        val team = DungeonUtils.dungeonTeammatesNoSelf.toList()
        val selfName = mc.player?.gameProfile?.name
        val self = DungeonUtils.dungeonTeammates.firstOrNull { it.name == selfName }
        val mine = own
        val withSelf = if (self != null && mine != null) team + self else team
        if (withSelf.isEmpty()) return
        totals.keys.retainAll(team.mapTo(HashSet()) { it.name })
        val before = HashMap<String, Long?>().apply { for (p in team) put(p.name, totals[p.name]) }

        scope.launch {
            val gained = HashMap<String, Long?>()
            for (player in team) pull(player.name, before[player.name], gained)
            // The API can lag the run's end: a teammate at 0 is asked once more.
            val zero = team.filter { gained[it.name] == 0L }
            if (zero.isNotEmpty()) {
                delay(4000)
                for (player in zero) pull(player.name, before[player.name], gained)
            }
            if (self != null && mine != null) gained[self.name] = mine
            show(withSelf, gained)
        }
    }

    private suspend fun pull(name: String, before: Long?, gained: HashMap<String, Long?>) {
        RequestUtils.pullSecrets(name).onSuccess { now ->
            gained[name] = if (before != null) (now - before).coerceAtLeast(0L) else null
            totals[name] = now
        }
    }

    private fun show(team: List<DungeonPlayer>, gained: Map<String, Long?>) {
        for (player in team.sortedWith(compareBy<DungeonPlayer> { it.clazz.ordinal }.thenByDescending { gained[it.name] ?: -1L })) {
            val n = gained[player.name]
            modMessage("${player.clazz.hexCode}${player.name} §7-> " + if (n == null) "§cFailed to get secrets" else "§f$n Secrets")
        }
    }
}
