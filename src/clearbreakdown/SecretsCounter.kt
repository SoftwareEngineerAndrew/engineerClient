package com.engineerclient.clearbreakdown

import com.odtheking.odin.OdinMod.scope
import com.odtheking.odin.utils.handlers.schedule
import com.odtheking.odin.utils.modMessage
import com.odtheking.odin.utils.network.hypixelapi.RequestUtils
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Odin's Secrets Counter: each teammate's secrets this run from the API, at the end of any run, won or not. */
object SecretsCounter {
    private val secretsBaseline = ConcurrentHashMap<String, Long>()
    private var snapshotDone = false

    private val dungeonStartRegex = Regex("^\\[NPC] Mort: Good luck\\.$")
    private val dungeonEndRegex = Regex("^\\s*(?:Master Mode )?(?:The )?Catacombs - (?:Entrance|Floor .{1,3})$")

    fun reset() {
        snapshotDone = false
    }

    fun onChat(message: String) {
        if (dungeonEndRegex.containsMatchIn(message)) schedule(30) { fetchAndDisplay() }

        if (!dungeonStartRegex.containsMatchIn(message) || snapshotDone) return
        val teammates = DungeonUtils.dungeonTeammatesNoSelf.toList().ifEmpty { return }
        snapshotDone = true
        scope.launch {
            for ((teammateName) in teammates) {
                if (secretsBaseline.containsKey(teammateName)) continue
                RequestUtils.pullSecrets(teammateName).onSuccess { secrets ->
                    secretsBaseline[teammateName] = secrets
                }
            }
        }
    }

    private fun fetchAndDisplay() {
        val teammates = DungeonUtils.dungeonTeammatesNoSelf.toList().ifEmpty { return }
        val secretsDelta = mutableMapOf<String, Long?>()

        secretsBaseline.keys.retainAll(teammates.map { it.name }.toSet())

        scope.launch {
            for ((teammateName) in teammates) {
                val baseline = secretsBaseline[teammateName]
                RequestUtils.pullSecrets(teammateName).onSuccess { newSecrets ->
                    secretsDelta[teammateName] = if (baseline != null) (newSecrets - baseline).coerceAtLeast(0L) else null
                    secretsBaseline[teammateName] = newSecrets
                }
            }
            display(teammates, secretsDelta)
        }
    }

    private fun display(teammates: List<DungeonPlayer>, secretsDelta: Map<String, Long?>) {
        teammates
            .sortedWith(compareBy<DungeonPlayer> { it.clazz.ordinal }.thenByDescending { secretsDelta[it.name] ?: -1L })
            .forEach { player ->
                val delta = secretsDelta[player.name]
                if (delta == null) modMessage("§${player.clazz.colorCode}${player.name} §7-> §cFailed to get secrets")
                else modMessage("§${player.clazz.colorCode}${player.name} §7-> §f$delta Secrets")
            }
    }
}
