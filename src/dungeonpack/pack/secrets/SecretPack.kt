package com.engineerclient.dungeonpack.pack.secrets

import com.engineerclient.dungeonpack.pack.*
import com.google.gson.JsonObject
import java.io.InputStream

/** How a secret comes into the world (ads-handoffs results/secrets.md 1): at Mort's line, when its room is discovered, on approach, or on a Redstone Key socket's use. */
enum class SecretSpawn { START, DISCOVERY, APPROACH, USE }

/** `chest lockedChest essence item bat key`, as the research names them (`key`: the Redstone Key, counted on its socket's use). */
enum class SecretType(val word: String) {
    CHEST("chest"), LOCKED_CHEST("lockedChest"), ESSENCE("essence"), ITEM("item"), BAT("bat"), KEY("key");

    companion object { fun of(w: String?): SecretType? = entries.firstOrNull { it.word == w } }
}

/**
 * One secret site of a design, canonical frame (y absolute). [pos] is where it really is; [was] the rooms pack's own spot when that is
 * elsewhere (a block baked there must go too). [worldFacing] is a chest's facing in the WORLD for each room rotation (SOUTH WEST NORTH
 * EAST, [com.engineerclient.dungeonpack.pack.Rot.q]): Hypixel turns some with the room and not others (results/secrets-facing.json);
 * [worldRotation] the same for a skull's 0-15 rotation. [radius] is the approach sphere; [lever] the lever that unlocks a locked chest.
 */
class SecretSite(
    val id: String, val type: SecretType, val pos: Pos, val spawn: SecretSpawn, val radius: Double?, val was: Pos?, val lever: Pos?,
    val worldFacing: List<String>?, val worldRotation: List<Int>?,
)

/** A secret lever: where, and its world block state for each room rotation (Hypixel's levers mostly keep one world facing). */
class SecretLever(val pos: Pos, val worldStates: List<String>)

/**
 * A Redstone Key room: [site] is the key secret's id; the key skull stands at one of [skulls] (drawn per run) with its world rotations;
 * [socket] is the redstone block it is used on ([placeSocket]: put there at Mort, the capture has air); [head] `top` (a floor head on
 * the socket) or `face` (a wall head on the clicked face); [cells] are what the use changes, canonical states.
 */
class KeyRoom(val site: String, val skulls: List<Pair<Pos, List<Int>>>, val socket: Pos, val placeSocket: Boolean, val head: String, val cells: List<Pair<Pos, String>>)

/** A design's secrets: the printed maximum (the action bar's `N/M`), the sites, the levers, the Redstone Key. */
class DesignSecrets(val max: Int, val sites: List<SecretSite>, val levers: List<SecretLever>, val key: KeyRoom?)

/**
 * The secrets pack `secrets.json.gz` (built by ads-handoffs scripts/secrets/pack.cjs from results/secrets-sites.json, secrets-facing.json
 * and secret-item-stacks.json): every design's secret sites, levers, printed maximum and Redstone Key, the secret-item pool with
 * Hypixel's floor stacks, the Mimic rule.
 */
class SecretPack(
    val designs: Map<String, DesignSecrets>,
    /** The item pool (name, share), drawn per spawn (results 3). */
    val items: List<Pair<String, Double>>,
    /** Hypixel's floor stack of each item, as Minecraft's item JSON (the world side decodes it). */
    val stacks: Map<String, JsonObject>,
    val checkEvery: Int,
    val mimicFloors: Set<String>,
    /** P(no Mimic) = a / (n + b), n the dungeon's locked chests (results 2). */
    val mimicA: Double,
    val mimicB: Double,
) {
    fun design(id: String): DesignSecrets? = designs[id]

    companion object {
        const val FORMAT = "ads-secrets"
        const val VERSION = 2
        private const val PATH = "/assets/engineerclient/dungeonpack/packs/secrets.json.gz"

        /** The copy in the jar. */
        val bundled: SecretPack by lazy { SecretPack::class.java.getResourceAsStream(PATH)!!.use { parse(it) } }

        fun parse(stream: InputStream): SecretPack = parse(readGzipJson(stream))

        private fun ints(o: JsonObject, k: String): List<Int>? = o.arr(k)?.mapNotNull { it.asIntOrNull() }?.takeIf { it.size == 4 }

        fun parse(root: JsonObject): SecretPack {
            if (root.str("format") != FORMAT || root.int("version") != VERSION) throw PackFormatException("not an $FORMAT $VERSION pack")
            val designs = root.obj("designs")?.entrySet()?.associate { (id, v) ->
                val o = v.asObj() ?: throw PackFormatException("$id is not an object")
                id to DesignSecrets(
                    o.int("max") ?: 0,
                    o.arr("sites").objects().mapNotNull { s ->
                        val type = SecretType.of(s.str("t")) ?: return@mapNotNull null
                        val spawn = when (s.str("s")) { "s" -> SecretSpawn.START; "d" -> SecretSpawn.DISCOVERY; "u" -> SecretSpawn.USE; else -> SecretSpawn.APPROACH }
                        SecretSite(s.str("id") ?: "?", type, s.get("p").asPos() ?: return@mapNotNull null, spawn, s.dbl("r"), s.get("was").asPos(), s.get("lever").asPos(),
                            s.arr("fw")?.strings()?.takeIf { it.size == 4 }, ints(s, "rw"))
                    },
                    o.arr("levers").objects().mapNotNull { l ->
                        SecretLever(l.get("p").asPos() ?: return@mapNotNull null, l.arr("states").strings().takeIf { it.size == 4 } ?: return@mapNotNull null)
                    },
                    o.obj("key")?.let { k ->
                        KeyRoom(
                            k.str("site") ?: "key",
                            k.arr("skulls").objects().mapNotNull { s -> (s.get("p").asPos() ?: return@mapNotNull null) to (ints(s, "rw") ?: listOf(0, 4, 8, 12)) },
                            k.get("socket").asPos() ?: throw PackFormatException("$id: a key room without a socket"),
                            k.bool("place") ?: false,
                            k.str("head") ?: "top",
                            k.arr("cells")?.mapNotNull { c ->
                                val a = c.asArr() ?: return@mapNotNull null
                                if (a.size() < 4) return@mapNotNull null
                                Pos(a[0].asIntOrNull() ?: return@mapNotNull null, a[1].asIntOrNull() ?: return@mapNotNull null, a[2].asIntOrNull() ?: return@mapNotNull null) to (a[3].asStr() ?: return@mapNotNull null)
                            }.orEmpty(),
                        )
                    },
                )
            }.orEmpty()
            val items = root.arr("items")?.mapNotNull { e -> e.asArr()?.takeIf { it.size() == 2 }?.let { (it[0].asStr() ?: return@mapNotNull null) to (it[1].asDoubleOrNull() ?: 0.0) } }.orEmpty()
            val stacks = root.obj("stacks")?.entrySet()?.mapNotNull { (k, v) -> v.asObj()?.let { k to it } }?.toMap().orEmpty()
            val rules = root.obj("rules")
            val mimic = rules?.obj("mimic")
            return SecretPack(designs, items, stacks, rules?.int("checkEvery") ?: 5, mimic?.arr("floors").strings().toSet(), mimic?.dbl("a") ?: 1.31, mimic?.dbl("b") ?: 2.0)
        }
    }
}
