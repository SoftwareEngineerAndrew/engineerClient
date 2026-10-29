package com.engineerclient.dungeonpack.pack.mobs

import com.engineerclient.dungeonpack.pack.*
import com.engineerclient.dungeonpack.pack.rooms.checkHeader
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path

/*
 * The "Mob tables" data pack, version 1 (FORMAT-mobs.md): mob archetypes, name modifiers, the per-run HP scale, per-room
 * spawn data and the player damage tables. Coordinates are the rooms pack's canonical room frame with absolute y.
 * This file is the model and the loader only. The pure functions of FORMAT-mobs 11.3 (formatHp, nametagLegacy,
 * legacyToComponent, rollRoom, sampleMelee, drawHpScale, uuidV2) belong to the mobs work item (the rebuild).
 * Nothing here is blood-camp data: the pack carries none.
 */

/** FORMAT-mobs 3: a skin to give a disguised body. `value` is the base64 textures property, null when the pack lacks it. */
data class MobSkin(val id: String, val value: String?, val signature: String?, val slim: Boolean = false, val variants: List<MobSkin> = emptyList())

/** FORMAT-mobs 3.3: how the nametag stand is placed and written. `head` is the legacy-coded prefix (ends in the star part). */
data class MobTag(
    val idOffset: Int, val dy: Double, val head: String, val nameColor: String, val bold: Boolean,
    /** The modifier is written as its own segment (the trap-room Deathmite): `head + modColor + Word + " §r" + §8§l + name` (FORMAT-mobs 3.3). */
    val modSplit: Boolean = false,
)

/** FORMAT-mobs 9: a sleeper (Fels) wakes when a player body enters a sphere of [radius] blocks. */
data class MobWake(val radius: Double, val trigger: String)

/** Equipment worn by an archetype (Shadow Assassin). */
data class MobEquipment(val mainhand: String?, val mainhandName: String?, val feet: String?)

/** FORMAT-mobs 3. `index` is the position in [MobPack.archetypes]; rooms refer to archetypes by it. */
class MobArchetype(
    val index: Int,
    val id: String,
    val name: String,
    /** `clear`, `miniboss` or `special`. */
    val cls: String,
    /** Body entity type id, `minecraft:zombie` ... `minecraft:player` for a disguise. */
    val entity: String,
    val disguise: Boolean,
    val skin: MobSkin?,
    val bodyName: String?,
    val equipment: MobEquipment?,
    /** Base HP per floor key (`F7`, `M7`). */
    val hp: Map<String, Long>,
    val star: Boolean,
    val tag: MobTag?,
    /** `static`, `sleeper`, `patrol` or `teleport`. */
    val movement: String,
    val wake: MobWake?,
    /** Per-archetype override of the global modifier weights; null = use the global table. */
    val modifierWeights: Map<String, Double>?,
    val seen: Map<String, Int>,
) {
    fun hpFor(floor: String): Long? = hp[floor]
}

/** FORMAT-mobs 3.6: a name prefix word ("" = none) with its weight, HP factor and colours. `weight` null for a floor = reuse F7. */
data class MobModifier(
    val id: String, val weight: Map<String, Double?>, val hpFactor: Double, val nameColor: String?, val hpColor: String?,
    /** False for Flaming: the head loses its trailing `§r`, so the word inherits the star's gold (FORMAT-mobs 3.3 and 3.6). */
    val headReset: Boolean = true,
    /** Plain row only: the HP colour once hurt (`e`) and the share of max HP at or below which it applies (0.5). */
    val hpColorHurt: String? = null,
    val hpThreshold: Double? = null,
)

/** FORMAT-mobs 4: the per-run multiplier on every base HP: draw `values[i]` with probability `counts[i] / n`. */
class HpScaleTable(val n: Int, val values: DoubleArray, val counts: IntArray)

/** FORMAT-mobs 5: count statistics of mobs per recorded visit. */
data class CountStats(val mean: Double, val p50: Double, val p90: Double, val max: Double, val hist: Map<Int, Int>)

/** One independent-roll spawn cell: "in `occ` of the visits, a mob of [arch] stood at ([x], [y], [z])" (FORMAT-mobs 5.1). */
data class MobCell(val x: Int, val y: Int, val z: Int, val arch: Int, val star: Boolean, val occ: Int)

/** One mob of a recorded visit (FORMAT-mobs 5.2). */
data class MobSpawn(val x: Int, val y: Int, val z: Int, val arch: Int, val star: Boolean)

/** One recorded visit: the full list of mobs seen in a room. `fl` is `F7`, `M7` or `?` (matches both). */
class MobLayout(val src: String, val fl: String, val mobs: List<MobSpawn>)

/** FORMAT-mobs 5: everything the pack knows about one room, keyed by the room's in-game name. */
class RoomMobs(
    val name: String,
    val designs: List<String>,
    /** `mob`, `miniboss` or `none`: how the room is cleared. */
    val clear: String,
    val visitsAdr: Int,
    val visitsCd: Int,
    val starred: CountStats?,
    val unstarred: CountStats?,
    val cells: List<MobCell>,
    val layouts: List<MobLayout>,
    /** Other spellings of the room's name (FORMAT-mobs 1, 5); the join tries `name`, then each of these, then `norm`. */
    val aliases: List<String> = emptyList(),
    /** The design type word (`normal`, `puzzle`, `trap`, ...) or null. */
    val type: String? = null,
) {
    /** Visits behind the numbers (`n` of FORMAT-mobs 5.1). */
    val visits: Int get() = visitsAdr + visitsCd
}

/** FORMAT-mobs 8: a room cleared by killing a miniboss, with its spawn cell and closed patrol loop (feet cells). */
data class Miniboss(val room: String, val arch: Int, val cell: Pos?, val src: String?, val hpFactor: Double?, val loop: List<Pos>?)

/** FORMAT-mobs 7: a melee-hit table. `quantiles` are percentiles 0..100 of the hit size (percent of max HP), `atoms` exact values with their probability. */
class MeleeTable(val n: Int, val perMinute: Double, val quantiles: java.util.SortedMap<Double, Double>, val atoms: List<Atom>, val provisional: Boolean) {
    data class Atom(val value: Double, val p: Double)
}

/** Percentile summary of one archetype's hits (`meleeByArchetype`, `projectile`). */
data class HitQuantiles(val n: Int, val p10: Double, val p50: Double, val p90: Double)

/** FORMAT-mobs 7. */
class PlayerDamageTable(
    val unit: String?,
    /** Hits closer together than this many ticks are ignored (10, measured). */
    val immunityTicks: Int,
    /** Pooled melee table per floor key. */
    val melee: Map<String, MeleeTable>,
    /** Floor key -> archetype id -> table; overrides the pooled table when present (n >= 30 only). */
    val meleeByArchetype: Map<String, Map<String, HitQuantiles>>,
    val projectile: Map<String, HitQuantiles>,
    /** Traps hit for exactly this percent of max HP, true damage (15.0). */
    val trapPercent: Double,
    val trapKinds: List<String>,
)

/** The loaded mob tables. */
class MobPack internal constructor(
    val version: Int,
    val archetypes: List<MobArchetype>,
    val modifiers: List<MobModifier>,
    val hpScale: Map<String, HpScaleTable>,
    val rooms: List<RoomMobs>,
    val minibosses: List<Miniboss>,
    val playerDamage: PlayerDamageTable?,
    val gaps: List<String>,
) {
    private val byArchetypeId = archetypes.associateBy { it.id }
    private val byArchetypeName = archetypes.associateBy { it.name }
    private val byRoomName = rooms.associateBy { it.name }
    private val byNormRoomName = rooms.associateBy { norm(it.name) }
    private val byRoomAlias: Map<String, RoomMobs> = buildMap { for (r in rooms) for (a in r.aliases) putIfAbsent(a.replace("\u200B", ""), r) }

    fun archetype(index: Int): MobArchetype? = archetypes.getOrNull(index)
    fun archetypeById(id: String): MobArchetype? = byArchetypeId[id]
    fun byName(name: String): MobArchetype? = byArchetypeName[name]

    /**
     * The spawn data of a room by name: exact (after stripping U+200B), then through [aliasOf] (the rooms pack's alias
     * table, applied to the name), then the normalised key of FORMAT-mobs 1 (`norm`). Null when the room has none
     * (the sim then spawns nothing there and logs one line).
     */
    fun room(name: String, aliasOf: ((String) -> String?)? = null): RoomMobs? {
        val n = name.replace("​", "")
        byRoomName[n]?.let { return it }
        byRoomAlias[n]?.let { return it }
        aliasOf?.invoke(n)?.let { a -> byRoomName[a]?.let { return it }; byRoomAlias[a]?.let { return it }; byNormRoomName[norm(a)]?.let { return it } }
        return byNormRoomName[norm(n)]
    }

    fun modifier(id: String): MobModifier? = modifiers.firstOrNull { it.id == id }
    fun hpScaleFor(floor: String): HpScaleTable? = hpScale[floor]
    fun minibossesOf(room: String): List<Miniboss> = minibosses.filter { it.room == room }

    companion object {
        const val FORMAT = "ads-mobs"
        const val SUPPORTED_VERSION = 1

        /** FORMAT-mobs 1 fallback join key. */
        fun norm(n: String): String = n.replace("​", "").replace(" ", "_").replace("-", "_")

        /** Loads `mobs.json.gz` from [dir]. Null, with the reason in [report], when missing/unreadable/wrong format. Never throws. */
        fun load(dir: Path, report: PackReport = PackReport()): MobPack? = load(PackSource.ofDir(dir), report)

        /** [load] from a resolved [PackSource] (the jar's copy or the override folder's). */
        fun load(source: PackSource, report: PackReport = PackReport()): MobPack? {
            val path = source.file(PackFiles.MOBS)
            if (path == null) { report.error("The mob tables ${PackFiles.MOBS} are missing from ${source.missingFrom}."); return null }
            val root = try { Files.newInputStream(path).use { readGzipJson(it) } } catch (e: Exception) {
                report.error("The mob tables ${PackFiles.MOBS} are unreadable${source.note(PackFiles.MOBS)}: ${e.message}"); return null
            }
            return fromJson(root, report)
        }

        fun fromJson(root: JsonObject, report: PackReport = PackReport()): MobPack? {
            if (!checkHeader(root, FORMAT, SUPPORTED_VERSION, PackFiles.MOBS, report)) return null
            val archetypes = ArrayList<MobArchetype>()
            for ((i, a) in root.arr("archetypes").objects().withIndex()) {
                try { archetypes += parseArchetype(i, a) } catch (e: Exception) {
                    // Keep the index space intact: rooms refer to archetypes by position.
                    report.warn("Mob archetype #$i was skipped (${a.str("id") ?: "no id"}): ${e.message}")
                    archetypes += MobArchetype(i, a.str("id") ?: "broken_$i", a.str("name") ?: "?", "special", "minecraft:zombie", false, null, null, null, emptyMap(), false, null, "static", null, null, emptyMap())
                }
            }
            val modifiers = root.arr("modifiers").objects().map { m ->
                MobModifier(m.str("id") ?: "", doubleMap(m.obj("weight")), m.dbl("hpFactor") ?: 1.0, m.str("nameColor"), m.str("hpColor"),
                    m.bool("headReset") ?: true, m.str("hpColorHurt"), m.dbl("hpThreshold"))
            }
            val hpScale = HashMap<String, HpScaleTable>()
            root.obj("hpScale")?.entrySet()?.forEach { (k, v) ->
                val o = v.asObj() ?: return@forEach
                val values = o.arr("values").doubles(); val counts = o.arr("counts").ints()
                if (values.isNotEmpty() && values.size == counts.size) hpScale[k] = HpScaleTable(o.int("n") ?: counts.sum(), values, counts)
            }
            val rooms = ArrayList<RoomMobs>()
            for (r in root.arr("rooms").objects()) {
                try { rooms += parseRoom(r, archetypes.size) } catch (e: Exception) { report.warn("Mob data of room ${r.str("name") ?: "?"} was skipped: ${e.message}") }
            }
            val minibosses = root.arr("minibosses").objects().mapNotNull { m ->
                Miniboss(m.str("room") ?: return@mapNotNull null, m.int("arch") ?: return@mapNotNull null, m.get("cell").asPos(), m.str("src"), m.dbl("hpFactor"),
                    m.arr("loop")?.let { l -> l.mapNotNull { it.asPos() } })
            }
            if (archetypes.isEmpty()) report.warn("The mob tables have no archetypes.")
            return MobPack(root.int("version") ?: 1, archetypes, modifiers, hpScale, rooms, minibosses, root.obj("playerDamage")?.let { parseDamage(it) }, root.arr("gaps").strings())
        }

        private fun doubleMap(o: JsonObject?): Map<String, Double?> = o?.entrySet()?.associate { (k, v) -> k to v.asDoubleOrNull() }.orEmpty()

        private fun parseArchetype(index: Int, a: JsonObject): MobArchetype {
            val id = a.str("id") ?: throw PackFormatException("no id")
            val tag = a.obj("tag")?.let { t ->
                MobTag(t.int("idOffset") ?: 1, t.dbl("dy") ?: 2.09, t.str("head") ?: "", t.str("nameColor") ?: "§c", t.bool("bold") ?: false, t.bool("modSplit") ?: false)
            }
            return MobArchetype(
                index = index, id = id, name = a.str("name") ?: id, cls = a.str("cls") ?: "clear",
                entity = a.str("entity") ?: "minecraft:zombie", disguise = a.bool("disguise") ?: false,
                skin = a.obj("skin")?.let { s -> parseSkin(s, withVariants = true) },
                bodyName = a.str("bodyName"),
                equipment = a.obj("equipment")?.let { e -> MobEquipment(e.str("mainhand"), e.str("mainhandName"), e.str("feet")) },
                hp = a.obj("hp")?.entrySet()?.mapNotNull { (k, v) -> v.asLongOrNull()?.let { k to it } }?.toMap().orEmpty(),
                star = a.bool("star") ?: true,
                tag = tag,
                movement = a.str("movement") ?: "static",
                wake = a.obj("wake")?.let { w -> MobWake(w.dbl("radius") ?: 5.0, w.str("trigger") ?: "player-body") },
                modifierWeights = a.obj("modifierWeights")?.entrySet()?.mapNotNull { (k, v) -> v.asDoubleOrNull()?.let { k to it } }?.toMap(),
                seen = a.obj("seen")?.entrySet()?.mapNotNull { (k, v) -> v.asIntOrNull()?.let { k to it } }?.toMap().orEmpty(),
            )
        }

        private fun parseSkin(s: JsonObject, withVariants: Boolean): MobSkin {
            val value = s.str("value")
            // The rebuilt textures property says "slim" in its metadata (a decoded value is not needed here: the pack writes it compactly).
            val slim = value?.let { v -> try { String(java.util.Base64.getDecoder().decode(v), Charsets.UTF_8).contains("\"slim\"") } catch (e: Exception) { false } } ?: false
            val variants = if (withVariants) s.arr("variants").objects().map { parseSkin(it, withVariants = false) } else emptyList()
            return MobSkin(s.str("id") ?: "", value, s.str("signature"), slim, variants)
        }

        private fun stats(o: JsonObject?): CountStats? = o?.let {
            CountStats(it.dbl("mean") ?: 0.0, it.dbl("p50") ?: 0.0, it.dbl("p90") ?: 0.0, it.dbl("max") ?: 0.0,
                it.obj("hist")?.entrySet()?.mapNotNull { (k, v) -> k.toIntOrNull()?.let { kk -> v.asIntOrNull()?.let { kk to it } } }?.toMap().orEmpty())
        }

        private fun parseRoom(r: JsonObject, archetypeCount: Int): RoomMobs {
            val name = (r.str("name") ?: throw PackFormatException("no name")).replace("​", "")
            val cells = r.arr("cells")?.mapNotNull { c ->
                val a = c.asArr() ?: return@mapNotNull null
                if (a.size() < 6) return@mapNotNull null
                val arch = a[3].asIntOrNull() ?: return@mapNotNull null
                if (arch !in 0 until archetypeCount) return@mapNotNull null
                MobCell(a[0].asIntOrNull() ?: return@mapNotNull null, a[1].asIntOrNull() ?: return@mapNotNull null, a[2].asIntOrNull() ?: return@mapNotNull null,
                    arch, (a[4].asIntOrNull() ?: 0) != 0, a[5].asIntOrNull() ?: 1)
            }.orEmpty()
            val layouts = r.arr("layouts").objects().map { l ->
                MobLayout(l.str("src") ?: "adr", l.str("fl") ?: "?", l.arr("m")?.mapNotNull { m ->
                    val a = m.asArr() ?: return@mapNotNull null
                    if (a.size() < 5) return@mapNotNull null
                    val arch = a[3].asIntOrNull() ?: return@mapNotNull null
                    if (arch !in 0 until archetypeCount) return@mapNotNull null
                    MobSpawn(a[0].asIntOrNull() ?: return@mapNotNull null, a[1].asIntOrNull() ?: return@mapNotNull null, a[2].asIntOrNull() ?: return@mapNotNull null,
                        arch, (a[4].asIntOrNull() ?: 0) != 0)
                }.orEmpty())
            }
            val visits = r.obj("visits")
            return RoomMobs(name, r.arr("designs").strings(), r.str("clear") ?: "none", visits?.int("adr") ?: 0, visits?.int("cd") ?: 0,
                stats(r.obj("starred")), stats(r.obj("unstarred")), cells, layouts, r.arr("aliases").strings().map { it.replace("\u200B", "") }, r.str("type"))
        }

        private fun hit(o: JsonObject?): HitQuantiles? = o?.let { HitQuantiles(it.int("n") ?: 0, it.dbl("p10") ?: 0.0, it.dbl("p50") ?: 0.0, it.dbl("p90") ?: 0.0) }

        private fun meleeTable(o: JsonObject): MeleeTable {
            val q = java.util.TreeMap<Double, Double>()
            o.obj("quantiles")?.entrySet()?.forEach { (k, v) -> val kk = k.toDoubleOrNull(); val vv = v.asDoubleOrNull(); if (kk != null && vv != null) q[kk] = vv }
            return MeleeTable(o.int("n") ?: 0, o.dbl("perMinute") ?: 0.0, q,
                o.arr("atoms").objects().mapNotNull { a -> MeleeTable.Atom(a.dbl("value") ?: return@mapNotNull null, a.dbl("p") ?: return@mapNotNull null) },
                o.bool("provisional") ?: false)
        }

        private fun parseDamage(o: JsonObject): PlayerDamageTable {
            val melee = o.obj("melee")?.entrySet()?.mapNotNull { (k, v) -> v.asObj()?.let { k to meleeTable(it) } }?.toMap().orEmpty()
            val byArch = o.obj("meleeByArchetype")?.entrySet()?.mapNotNull { (k, v) ->
                v.asObj()?.let { fl -> k to fl.entrySet().mapNotNull { (id, t) -> hit(t.asObj())?.let { id to it } }.toMap() }
            }?.toMap().orEmpty()
            val projectile = o.obj("projectile")?.entrySet()?.mapNotNull { (k, v) -> hit(v.asObj())?.let { k to it } }?.toMap().orEmpty()
            val trap = o.obj("trap")
            return PlayerDamageTable(o.str("unit"), o.int("immunityTicks") ?: 10, melee, byArch, projectile, trap?.dbl("percent") ?: 15.0, trap?.arr("kinds").strings())
        }
    }
}
