package com.engineerclient.p3sim.clear.secrets

import com.engineerclient.p3sim.pack.Pos
import com.google.gson.JsonParser

/**
 * Where and how near each item, bat and skull secret spawns, measured from ADR recordings into `ads/secret-spawns.json`
 * (tools/secret-spawns): Hypixel checks every 5 ticks and brings a secret in at the first check that finds a party member inside its
 * room within its radius, measured in 3D from the player's feet. Keyed by the rooms pack's design id and secret id; positions in the
 * design's canonical frame. A secret the file does not have (or one that appears on an event, radius null) uses the type's default.
 */
object SpawnRanges {
    /** One measured site: [radius] in blocks (null: it appears on an event, not on approach), [pos] where it really spawns. */
    class Site(val type: String, val radius: Double?, val pos: Pos)

    class Table(val sites: Map<String, Site>, val defaults: Map<String, Double>) {
        fun of(design: String?, secret: String): Site? = design?.let { sites["$it|$secret"] }
        /** The type's typical radius (the median of the measured ones): for secrets with none of their own. */
        fun default(type: String): Double? = defaults[type]
    }

    val EMPTY = Table(emptyMap(), emptyMap())

    /** The table bundled in the jar; empty (every secret on its type's default setting) when it is missing or unreadable. */
    val bundled: Table by lazy {
        try {
            val text = SpawnRanges::class.java.getResourceAsStream("/assets/engineerclient/ads/secret-spawns.json")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return@lazy EMPTY
            parse(text)
        } catch (t: Throwable) { org.slf4j.LoggerFactory.getLogger("engineerclient").warn("[ads] secret spawns: ${t.message}"); EMPTY }
    }

    /** Reads the file's text. Sites the rooms pack has no id for (`"secret": null`) are left out: nothing in a run stands there. */
    fun parse(text: String): Table {
        val root = JsonParser.parseString(text).asJsonObject
        require(root.get("format")?.asString == "ads-secret-spawns") { "not an ads-secret-spawns file" }
        val defaults = root.getAsJsonObject("defaults")?.entrySet()?.filter { it.value.isJsonPrimitive }?.associate { it.key to it.value.asDouble }.orEmpty()
        val sites = HashMap<String, Site>()
        for (e in root.getAsJsonArray("sites")) {
            val o = e.asJsonObject
            val secret = o.get("secret")?.takeIf { !it.isJsonNull }?.asString ?: continue
            val p = o.getAsJsonArray("pos")
            val r = o.get("r")?.takeIf { !it.isJsonNull }?.asDouble
            sites["${o.get("design").asString}|$secret"] = Site(o.get("type").asString, r, Pos(p[0].asInt, p[1].asInt, p[2].asInt))
        }
        return Table(sites, defaults)
    }
}
