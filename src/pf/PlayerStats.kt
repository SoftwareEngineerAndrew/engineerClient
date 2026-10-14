package com.engineerclient.pf

import com.engineerclient.EngineerClient
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.odtheking.odin.OdinMod
import com.odtheking.odin.utils.calculateDungeonLevel
import com.odtheking.odin.utils.network.hypixelapi.HypixelData
import com.odtheking.odin.utils.network.hypixelapi.RequestUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The player-stats store behind Party Finder Stats and Hub Nametag Stats: Catacombs XP, secret
 * count and every floor's S+ time (both modes) per player.
 *
 * Same data as Odin's Better Party Finder autokick: `RequestUtils.getProfile` (Odin's own API,
 * cached by Odin), Catacombs level from `dungeons.dungeon_types.catacombs.experience`,
 * `dungeons.secrets`, and the S+ fastest times.
 *
 * With Devonian installed ([DevonianBridge]) its session cache answers first and misses go through
 * its faster fetcher (Odin's profile fetch stays as the fallback), and everything Devonian fetches
 * for its own features — it prefetches whole Party Finder listings — lands in this store too.
 *
 * Stats change rarely and the same players show up again and again, so everything is kept on disk
 * for a day: `config/engineerclient/pfstats.json`. Odin's own profile cache only lives five
 * minutes, in memory.
 */
object PlayerStats {

    /** What [lookup] can say about a player right now. */
    sealed interface Lookup
    object Loading : Lookup
    class Failed(val at: Long) : Lookup
    class Ready(val stats: Stats) : Lookup

    /** What a stat line needs, small enough to keep on disk for everyone you have ever seen. */
    data class Stats(
        val cataXp: Double = 0.0,
        val secrets: Long = 0,
        /** "f"/"m" → floor "0".."7" → S+ ms. */
        val sPlus: Map<String, Map<String, Double>> = emptyMap(),
        val fetchedAt: Long = 0,
    )

    private const val RETRY_FAILED_MS = 60_000L
    private const val STATS_TTL_MS = 24 * 60 * 60 * 1000L
    private const val MAX_STORED = 2000
    private const val DEVONIAN_GRACE_MS = 6_000L

    /** Lower-case IGN → state. Loaded from disk once; a Ready entry older than a day is refetched. */
    private val cache = ConcurrentHashMap<String, Lookup>()
    private val loaded = AtomicBoolean(false)
    private val dirty = AtomicBoolean(false)
    private val refreshing = ConcurrentHashMap.newKeySet<String>()
    private val gson = GsonBuilder().create()
    private val file = EngineerClient.mc.gameDirectory.toPath().resolve("config").resolve("engineerclient").resolve("pfstats.json")

    /**
     * The current state for [name], fetching in the background as needed: an unknown player starts
     * Loading, a failed fetch retries after a minute, a Ready entry older than a day is refreshed
     * while its old numbers keep showing.
     */
    fun lookup(name: String): Lookup {
        if (loaded.compareAndSet(false, true)) {
            load()
            // Everything Devonian fetches for its own features flows into this store too.
            DevonianBridge.onResult(::offer)
        }
        val key = name.lowercase()
        val entry = cache[key]
        val now = System.currentTimeMillis()
        if (entry == null || (entry is Failed && now - entry.at > RETRY_FAILED_MS)) {
            DevonianBridge.cached(name)?.let { stats ->
                offer(name, stats)
                return cache.getValue(key)
            }
            cache[key] = Loading
            fetch(name, key, tryDevonian = true)
            return Loading
        }
        // A day old: refresh in the background, keep showing what we have. Straight through Odin —
        // a background refresh is not latency-sensitive, and its bookkeeping (the refreshing set)
        // stays with the one fetcher that always answers.
        if (entry is Ready && now - entry.stats.fetchedAt > STATS_TTL_MS && refreshing.add(key)) fetch(name, key, tryDevonian = false)
        return entry
    }

    /** A result from the Devonian feed or cache: into the store and onto disk. */
    private fun offer(name: String, stats: Stats) {
        val key = name.lowercase()
        cache[key] = Ready(stats)
        refreshing -= key
        dirty.set(true)
        OdinMod.scope.launch { if (dirty.compareAndSet(true, false)) save() }
    }

    private fun fetch(name: String, key: String, tryDevonian: Boolean) {
        if (tryDevonian && DevonianBridge.request(name)) {
            // The result lands through the Devonian feed; if it has not after a grace period
            // (dropped from their queue, backend down), fall through to Odin's own fetch.
            OdinMod.scope.launch {
                delay(DEVONIAN_GRACE_MS)
                if (cache[key] is Loading) odinFetch(name, key)
            }
            return
        }
        odinFetch(name, key)
    }

    private fun odinFetch(name: String, key: String) {
        OdinMod.scope.launch {
            val result = runCatching { RequestUtils.getProfile(name) }.getOrElse { Result.failure(it) }
            val fresh = result.getOrNull()?.memberData?.let { Ready(toStats(it)) }
            when {
                fresh != null -> { cache[key] = fresh; dirty.set(true) }
                // A failed refresh keeps yesterday's numbers rather than replacing them with "?".
                cache[key] !is Ready -> cache[key] = Failed(System.currentTimeMillis())
            }
            refreshing -= key
            if (dirty.compareAndSet(true, false)) save()
        }
    }

    private fun toStats(member: HypixelData.MemberData): Stats {
        val d = member.dungeons.dungeonTypes
        fun times(pick: (HypixelData.DungeonTypeData) -> Map<String, Number>) =
            mapOf("f" to pick(d.catacombs).mapValues { it.value.toDouble() }, "m" to pick(d.mastermode).mapValues { it.value.toDouble() })
        return Stats(
            cataXp = d.catacombs.experience,
            secrets = member.dungeons.secrets,
            sPlus = times { it.fastestTimeSPlus },
            fetchedAt = System.currentTimeMillis(),
        )
    }

    // ------------------------------------------------------------------ formatting
    //
    // Shared by every view so a player's numbers read the same in the Party Finder tooltip and over
    // their head in the hub.

    /** Catacombs level with one decimal, e.g. "47.3". */
    fun cataStr(stats: Stats): String = String.format(Locale.ROOT, "%.1f", calculateDungeonLevel(stats.cataXp))

    /** Secrets in thousands past 1k, e.g. "41.2k". */
    fun secretsStr(count: Long): String = when {
        count >= 100_000 -> String.format(Locale.ROOT, "%.0fk", count / 1000.0)
        count >= 1_000 -> String.format(Locale.ROOT, "%.1fk", count / 1000.0)
        else -> count.toString()
    }

    /** S+ time as "m:ss", or "§7—" when the floor is unknown or has none. [mode] is "f"/"m", [floor] "0".."7". */
    fun pbStr(stats: Stats, mode: String, floor: String?): String {
        if (floor == null) return "§7—"
        val ms = stats.sPlus[mode]?.get(floor)
        if (ms == null || ms <= 0) return "§7—"
        val total = (ms / 1000).toLong()
        return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
    }

    // ------------------------------------------------------------------ disk

    private fun load() {
        EngineerClient.safely("player stats load") {
            if (!Files.exists(file)) return@safely
            val type = object : TypeToken<Map<String, Stats>>() {}.type
            val stored: Map<String, Stats> = gson.fromJson(Files.readString(file), type) ?: return@safely
            stored.forEach { (key, stats) -> cache[key] = Ready(stats) }
            EngineerClient.logger.info("[ec] player stats: ${stored.size} players from disk")
        }
    }

    /** Off the render thread (called from a fetch coroutine). Atomic rename, oldest evicted past the cap. */
    private fun save() {
        EngineerClient.safely("player stats save") {
            val ready = cache.entries.mapNotNull { (k, v) -> (v as? Ready)?.let { k to it.stats } }
                .sortedByDescending { it.second.fetchedAt }
                .take(MAX_STORED)
                .toMap()
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling("pfstats.json.tmp")
            Files.writeString(tmp, gson.toJson(ready))
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
    }

    /** For a debug readout: how many players are known. */
    fun cached(): Int = cache.count { it.value is Ready }
}
