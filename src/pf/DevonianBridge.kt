package com.engineerclient.pf

import com.engineerclient.EngineerClient
import net.fabricmc.loader.api.FabricLoader

/**
 * Feeds [PlayerStats] from Devonian's dungeon-stats API when the mod is installed.
 *
 * Devonian keeps a session cache of exactly the numbers a stat line needs (cata XP, secrets,
 * per-floor S+ times for both modes) behind its own backend, and it prefetches every member it
 * sees in the Party Finder — so by the time a tooltip or a hub nametag asks, the answer is usually
 * already in memory. Three touchpoints:
 *
 * - [cached]: Devonian's in-memory result for a player, converted, or null.
 * - [request]: hand a fetch to Devonian's queue (faster than a full Hypixel profile through Odin's
 *   API; [PlayerStats] falls back to Odin when no answer arrives).
 * - [onResult]: every result Devonian fetches — its own Party Finder prefetches included — so they
 *   all land in the store and its day-long disk copy without being asked for.
 *
 * Every Devonian reference lives in the nested [Impl] object, a separate class file: with the mod
 * absent nothing here loads a Devonian class. Any throw (an ABI change in a Devonian update)
 * disables the bridge for the session and logs once; [PlayerStats] then just uses Odin's path.
 *
 * Devonian's per-floor keys are `floor_N` / `floor_N_ms` under its `s_plus` map.
 */
object DevonianBridge {

    val available: Boolean = FabricLoader.getInstance().isModLoaded("devonian")

    @Volatile private var broken = false

    private inline fun <T> guarded(what: String, block: () -> T): T? {
        if (!available || broken) return null
        return try {
            block()
        } catch (t: Throwable) {
            broken = true
            EngineerClient.logger.error("[ec] devonian bridge disabled: $what failed", t)
            null
        }
    }

    /** Devonian's cached stats for [name], or null (mod absent, cache miss, failed result). */
    fun cached(name: String): PlayerStats.Stats? = guarded("cached") { Impl.cached(name) }

    /** Queue [name] on Devonian's fetcher; true when handed over (the result arrives via [onResult]). */
    fun request(name: String): Boolean = guarded("request") { Impl.request(name); true } ?: false

    /** Every stats result Devonian fetches, converted, for as long as the session lives. */
    fun onResult(consumer: (name: String, stats: PlayerStats.Stats) -> Unit) {
        guarded("onResult") { Impl.onResult(consumer) }
    }

    private object Impl {

        fun cached(name: String): PlayerStats.Stats? =
            convert(com.github.synnerz.devonian.api.dungeon.DungeonsApi.player(name))

        fun request(name: String) =
            com.github.synnerz.devonian.api.dungeon.DungeonsApi.requestPlayer(name)

        fun onResult(consumer: (String, PlayerStats.Stats) -> Unit) {
            com.github.synnerz.devonian.api.dungeon.DungeonsApi.on { name, result ->
                EngineerClient.safely("devonian result") { convert(result)?.let { consumer(name, it) } }
            }
        }

        private fun convert(result: com.github.synnerz.devonian.api.dungeon.DungeonsApi.DungeonsApiResult?): PlayerStats.Stats? {
            val data = result?.takeIf { it.success }?.data ?: return null
            // "floor_7" is the display key; the machine value sits beside it as "floor_7_ms".
            fun sPlus(pb: Map<String, Map<String, String>>?): Map<String, Double> {
                val inner = pb?.get("s_plus") ?: return emptyMap()
                val out = HashMap<String, Double>()
                for ((k, v) in inner) {
                    if (!k.endsWith("_ms")) continue
                    v.toDoubleOrNull()?.let { out[k.removePrefix("floor_").removeSuffix("_ms")] = it }
                }
                return out
            }
            return PlayerStats.Stats(
                cataXp = data.cataXP,
                secrets = data.secrets.toLong(),
                sPlus = mapOf("f" to sPlus(data.personal_best_normal), "m" to sPlus(data.personal_best_master)),
                fetchedAt = System.currentTimeMillis(),
            )
        }
    }
}
