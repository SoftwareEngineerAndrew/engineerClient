package com.adm.utils.network.hypixelapi

import com.adm.features.impl.render.ClickGUIModule.hypixelApiUrl
import com.adm.utils.network.WebUtils
import com.adm.utils.network.WebUtils.fetchJson
import com.adm.utils.network.WebUtils.fetchReader
import com.google.gson.stream.JsonReader
import java.util.concurrent.ConcurrentHashMap

object RequestUtils {

    private class TtlCache<T : Any>(private val ttlMs: Long = 10 * 60 * 1000L) {
        private class Entry<T>(val value: T, val timestamp: Long = System.currentTimeMillis())

        private val entries = ConcurrentHashMap<String, Entry<T>>()

        operator fun get(name: String): T? {
            prune()
            return entries[name.lowercase()]?.value
        }

        operator fun set(name: String, value: T) {
            prune()
            entries[name.lowercase()] = Entry(value)
        }

        private fun prune() {
            val now = System.currentTimeMillis()
            entries.values.removeIf { now - it.timestamp >= ttlMs }
        }
    }

    private val uuidCache = TtlCache<UuidData>()
    private val playerCache = TtlCache<HypixelData.PlayerInfo>()

    private fun getServer(endpoint: EndPoint, uuid: String): String =
        hypixelApiUrl + endpoint.name.lowercase() + "/" + uuid

    suspend fun getProfile(name: String): Result<HypixelData.PlayerInfo> {
        playerCache[name]?.let { return Result.success(it) }
        val uuidData = getUuid(name).getOrElse { return Result.failure(it) }

        // Only the selected profile's own member is read, off the stream: whole responses run to megabytes.
        return fetchReader(getServer(EndPoint.GET, uuidData.id)) { HypixelData.readSelected(JsonReader(it), uuidData.id) }.mapCatching { data ->
            data.cause?.let { throw Exception("Failed to get hypixel data: $it") }
            val member = data.member
            val kept = HypixelData.ProfilesData(profileList = member?.let { listOf(HypixelData.Profiles(mapOf(uuidData.id to it), true)) }.orEmpty())
            HypixelData.PlayerInfo(kept, uuidData.id, uuidData.name).also {
                if (data.anyProfiles) playerCache[name] = it
            }
        }
    }

    suspend fun getUuid(name: String): Result<UuidData> {
        uuidCache[name]?.let { return Result.success(it) }

        return (fetchJson<UuidData>("https://mowojang.seraph.si/users/profiles/minecraft/$name").takeIf { it.isSuccess }
            ?: fetchJson<UuidData>("https://api.minecraftservices.com/minecraft/profile/lookup/name/$name"))
            .onSuccess { uuidCache[name] = it }
    }

    /** Looks up up to ten names per request; names it misses are left to [getUuid]. */
    suspend fun prefetchUuids(names: Collection<String>) {
        val missing = names.filter { uuidCache[it] == null }
        for (chunk in missing.chunked(10)) {
            val body = WebUtils.gson.toJson(chunk)
            val found = WebUtils.postData("https://api.minecraftservices.com/minecraft/profile/lookup/bulk/byname", body)
                .mapCatching { WebUtils.gson.fromJson(it, Array<UuidData>::class.java) }.getOrNull() ?: continue
            for (data in found) if (!(data.id as String?).isNullOrEmpty() && (data.name as String?) != null) uuidCache[data.name] = data
        }
    }

    suspend fun pullSecrets(name: String): Result<Long> {
        val uuidData = getUuid(name).getOrElse { return Result.failure(it) }
        return fetchJson<Long>(getServer(EndPoint.SECRETS, uuidData.id))
    }

    private enum class EndPoint { SECRETS, GET }
    data class UuidData(val name: String, val id: String)
}
