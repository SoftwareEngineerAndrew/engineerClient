package com.engineerclient.p3sim.clear.mobs

import com.google.gson.JsonParser

/**
 * Hypixel's signed `textures` values for the skins the sim's fake players wear (the mob pack's, the Watcher's mobs', the Shadow
 * Assassin's), as the Dungeon Recorder caught them in player-info packets: Mojang's signature over each value, so the client takes
 * the skin as secure and draws it. Built by the research tooling into `ads/skins.json` (`{hash: [value, signature]}`).
 */
object SignedSkins {
    private val table: Map<String, Pair<String, String>> by lazy {
        try {
            val text = SignedSkins::class.java.getResourceAsStream("/assets/engineerclient/ads/skins.json")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return@lazy emptyMap()
            JsonParser.parseString(text).asJsonObject.entrySet().associate { (k, v) -> k to (v.asJsonArray[0].asString to v.asJsonArray[1].asString) }
        } catch (t: Throwable) { org.slf4j.LoggerFactory.getLogger("engineerclient").warn("[ads] signed skins: ${t.message}"); emptyMap() }
    }

    /** The signed value and signature for texture [hash] (leading zeros or a 16-character prefix are fine), or null. */
    fun of(hash: String): Pair<String, String>? {
        val k = hash.trimStart('0')
        return table[k] ?: if (k.length < 60) table.entries.firstOrNull { it.key.startsWith(k) }?.value else null
    }
}
