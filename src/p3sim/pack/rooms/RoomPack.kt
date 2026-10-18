package com.engineerclient.p3sim.pack.rooms

import com.engineerclient.p3sim.pack.*
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/*
 * The "Room library" data pack, version 1 (FORMAT-rooms.md): every room design's blocks and features in the canonical
 * room frame. This file is the model and the loader; the placement law is [Placement]; the blob decoders are
 * [RoomBlobs]. Nothing here touches Minecraft. Filled by the skeleton, owned by the layout work item afterwards.
 */

/** Where a blob sits in `rooms.blocks.bin` (the index's `vol`). */
data class BlobRef(val kind: String, val off: Long, val len: Int, val crc32: String?, val nonAir: Int)

/** Where a design's blocks came from (informational). */
data class RoomSource(val blocks: String?, val file: String?, val ref: String?, val captureRotation: Int?)

/** FORMAT-rooms 7.3: one tile side that borders outside the room. `open`: all 12 opening cells are air in the base volume. */
data class DoorSlot(val tile: Tile, val side: Char, val open: Boolean, val seal: SlotSeal?)

/** The blocks that fill a slot when it is a wall: `cells` are `(x, y, z, state)` in the canonical frame. */
data class SlotSeal(val src: String?, val incomplete: Boolean, val cells: List<SealCell>)
data class SealCell(val pos: Pos, val state: String)

/** FORMAT-rooms 7.4. `type` is `skull chest trapped_chest sign hanging_sign banner dispenser dropper bed other`. */
data class RoomBlockEntity(val pos: Pos, val type: String, val tex: Int?, val text: List<String>?, val marker: Boolean, val src: String?)

/** FORMAT-rooms 7.4: a skull texture. `role` is `essence redstoneKey decor unknown`. */
data class SkullTexture(val id: String, val value: String?, val profileId: String?, val role: String)

/** FORMAT-rooms 7.5. `type` is `chest item bat essence redstoneKey lockedChest`; `bucket` keeps the source's name. */
data class RoomSecret(
    val id: String, val type: String, val bucket: String?, val pos: Pos, val facing: String?,
    val lever: Pos?, val alt: List<Pos>, val baked: Boolean, val src: String?,
)

/** FORMAT-rooms 7.6: a lever and the cells that vanish when it is flipped. */
data class RoomLever(val pos: Pos, val opens: List<Pos>)

/** FORMAT-rooms 7.8. `slab` is `[x0,y0,z0,x1,y1,z1]` with absolute y. */
data class RoomCrusher(val id: String, val slab: IntArray, val axis: String?, val sign: Int, val sweep: Int, val material: String?)

/** A tripwire hook in the volume (derived by scanning), `facing` canonical. */
data class RoomTripwire(val pos: Pos, val facing: String?)

/** FORMAT-rooms 7.9: `kind` and the named anchor cells. */
data class RoomPuzzle(val kind: String, val anchors: Map<String, List<Pos>>)

/** FORMAT-rooms 8.3: a variant is a patch over the base volume, with the Odin core it produces when known. */
data class RoomVariant(val id: String, val kind: String, val core: Int?, val srcFile: String?, val changedCells: Int, val vol: BlobRef)

/** One row of the pack's `cores` table: the lookup from an Odin core (or a recorded `Name|ROT|core` key) to a design and variant. */
data class CoreEntry(val core: Int, val design: String, val variant: String?)

/** One room design (FORMAT-rooms 7.2). All coordinates canonical; `y` absolute. */
class RoomDesign(
    val id: String,
    val name: String,
    val family: String,
    val buildKey: String?,
    /** `normal puzzle rare yellow trap fairy entrance blood`. */
    val type: String,
    /** `1x1 1x2 1x3 1x4 2x2 L`. */
    val shape: String,
    val tiles: List<Tile>,
    val w: Int, val d: Int, val h: Int, val y0: Int,
    val roofY: Int,
    val rotations: List<Rot>,
    /** Door set, 4 chars in the order N, E, S, W (1x1 designs); null for other shapes. */
    val doors: String?,
    val source: RoomSource?,
    val vol: BlobRef?,
    val slots: List<DoorSlot>,
    val blockEntities: List<RoomBlockEntity>,
    val secrets: List<RoomSecret>,
    val levers: List<RoomLever>,
    val crypts: List<List<Pos>>,
    val superboomWalls: List<List<Pos>>,
    val fallingBlocks: List<Pos>,
    val crushers: List<RoomCrusher>,
    val tripwires: List<RoomTripwire>,
    val puzzle: RoomPuzzle?,
    /** A copy of the Odin `rooms.json` entry (name, type, secrets, crypts, cores, ...); null if the design has none. */
    val odin: JsonObject?,
    val variants: List<RoomVariant>,
    val gaps: List<String>,
    /** The Odin cores the BASE volume produces (the centre column of each tile; FORMAT-rooms 8.2, an extractor extra); empty when the index lacks them. */
    val baseCores: List<Int> = emptyList(),
    /** `mob`, `miniboss` or `none`: how the room is cleared (the rooms_index `clear`), or null. */
    val clear: String? = null,
) {
    /** How many secrets Odin says the room has (`odin.secrets`, or `maxSecrets` in older exports); null when unknown. */
    val odinSecrets: Int? get() = odin?.int("secrets") ?: odin?.int("maxSecrets")

    /** The Fairy room is never rotated by the world (FORMAT-rooms 4.5). */
    val isFairy: Boolean get() = type == "fairy" || family == "Fairy"

    /**
     * Where this design stands for the layout's rotation word and world tiles (FORMAT-rooms 4). Throws
     * [PlacementException] when the rotation is not one the design lists, or the tile law fails.
     */
    fun place(rot: Rot, worldTiles: List<Tile>): Placement {
        val used = if (isFairy) Rot.SOUTH else rot
        if (rotations.isNotEmpty() && used !in rotations) throw PlacementException("$id is never placed $used (it occurs as ${rotations.joinToString()})")
        return Placement.of(shape, w, d, used, worldTiles, fairy = isFairy)
    }
}

/**
 * The loaded room library. Volumes decode on first use and are cached; [volume] is safe to call from the chunk
 * generator's worker threads. Close it when the sim server stops ([close]).
 */
class RoomPack internal constructor(
    val version: Int,
    val mc: String?,
    val generated: String?,
    val designs: List<RoomDesign>,
    val textures: List<SkullTexture>,
    val cores: List<CoreEntry>,
    val aliases: Map<String, String>,
    private val blobs: BlobSource?,
    private val report: PackReport,
) : java.io.Closeable {
    private val byId: Map<String, RoomDesign> = designs.associateBy { it.id }
    private val byFamily: Map<String, List<RoomDesign>> = designs.groupBy { it.family }
    private val byName: Map<String, List<RoomDesign>> = designs.groupBy { it.name }
    private val coreIndex: Map<Int, CoreEntry> = cores.associateBy { it.core }
    private val volumes = ConcurrentHashMap<String, RoomVolume>()
    private val failed = ConcurrentHashMap.newKeySet<String>()

    fun design(id: String): RoomDesign? = byId[id]

    /** Every build of a family (`Entrance` -> Entrance_Open, Entrance_Closed), in pack order. */
    fun designsOfFamily(family: String): List<RoomDesign> = byFamily[family].orEmpty()

    /** Every design with this in-game name (builds share a name). */
    fun designsByName(name: String): List<RoomDesign> = byName[normalise(name)].orEmpty()

    /** The design and variant that produce this Odin core, or null (FORMAT-rooms 8.2). */
    fun designByCore(core: Int): CoreEntry? = coreIndex[core]

    /** The family a room name belongs to: through the pack's `aliases`, else the name with spaces as underscores. */
    fun familyOf(name: String): String {
        val n = normalise(name)
        return aliases[n] ?: aliases[name] ?: n.replace(' ', '_')
    }

    fun texture(index: Int): SkullTexture? = textures.getOrNull(index)

    /**
     * The design's base block volume, decoded on first use. Null (with one message in the report) when the blob is
     * missing, fails its crc, or does not decode: that design is unavailable, the rest of the pack is not affected.
     */
    fun volume(design: RoomDesign): RoomVolume? {
        volumes[design.id]?.let { return it }
        if (design.id in failed) return null
        val ref = design.vol
        val source = blobs
        if (ref == null || source == null) { failed += design.id; report.warn("Room ${design.id} has no block data."); return null }
        return try {
            val bytes = source.read(ref.off, ref.len)
            ref.crc32?.let { if (RoomBlobs.crc32Hex(bytes) != it.lowercase()) throw PackFormatException("checksum mismatch") }
            if (ref.kind != "volume") throw PackFormatException("the blob is a ${ref.kind}, not a volume")
            val v = RoomBlobs.decodeVolume(bytes)
            if (v.w != design.w || v.d != design.d || v.h != design.h || v.y0 != design.y0) {
                throw PackFormatException("the volume is ${v.w}x${v.d}x${v.h} at y${v.y0}, the index says ${design.w}x${design.d}x${design.h} at y${design.y0}")
            }
            volumes.putIfAbsent(design.id, v) ?: v
        } catch (e: Exception) {
            failed += design.id
            report.warn("Room ${design.id} is unavailable: ${e.message}")
            null
        }
    }

    /**
     * The volume a room is built from: the variant [variantId] of [design] when given and found, else the base volume (with a
     * warning in the report when a named variant is missing). Variants are not cached; the base is. Null when unavailable.
     */
    fun volumeFor(design: RoomDesign, variantId: String?): RoomVolume? {
        if (variantId == null) return volume(design)
        val v = design.variants.firstOrNull { it.id == variantId }
        if (v == null) { report.warn("Room ${design.id} has no variant $variantId; the base build is used."); return volume(design) }
        return variantVolume(design, v) ?: volume(design)
    }

    /** The volume with a variant's patch applied (FORMAT-rooms 8.3); not cached. Null when unavailable. */
    fun variantVolume(design: RoomDesign, variant: RoomVariant): RoomVolume? {
        val base = volume(design) ?: return null
        val source = blobs ?: return null
        return try {
            val bytes = source.read(variant.vol.off, variant.vol.len)
            variant.vol.crc32?.let { if (RoomBlobs.crc32Hex(bytes) != it.lowercase()) throw PackFormatException("checksum mismatch") }
            base.withPatch(RoomBlobs.decodePatch(bytes))
        } catch (e: Exception) {
            report.warn("Variant ${variant.id} of ${design.id} is unavailable: ${e.message}")
            null
        }
    }

    /** Frees the file handle. */
    override fun close() { blobs?.close() }

    companion object {
        const val FORMAT = "ads-rooms"
        const val SUPPORTED_VERSION = 1

        /** U+200B stripped (FORMAT-rooms 9). */
        internal fun normalise(name: String): String = name.replace("​", "")

        /**
         * Loads `rooms.index.json.gz` and opens `rooms.blocks.bin` from [dir]. Returns null, with the reason in
         * [report], when the folder or a file is missing or unreadable, the format or version is wrong, or the
         * blocks file does not match the index. Never throws.
         */
        fun load(dir: Path, report: PackReport = PackReport()): RoomPack? = load(PackSource.ofDir(dir), report)

        /** [load] from a resolved [PackSource]: each of the two files may be the jar's copy or the override folder's. */
        fun load(source: PackSource, report: PackReport = PackReport()): RoomPack? {
            val indexPath = source.file(PackFiles.ROOMS_INDEX)
            if (indexPath == null) { report.error("The room library ${PackFiles.ROOMS_INDEX} is missing from ${source.missingFrom}."); return null }
            val root = try {
                Files.newInputStream(indexPath).use { readGzipJson(it) }
            } catch (e: Exception) { report.error("The room library ${PackFiles.ROOMS_INDEX} is unreadable${source.note(PackFiles.ROOMS_INDEX)}: ${e.message}"); return null }
            if (!checkHeader(root, FORMAT, SUPPORTED_VERSION, PackFiles.ROOMS_INDEX, report)) return null

            val blocksName = root.str("blocksFile") ?: PackFiles.ROOMS_BLOCKS
            val blocksPath = source.file(blocksName)
            if (blocksPath == null) { report.error("The room library's block file $blocksName is missing from ${source.missingFrom}."); return null }
            val size = Files.size(blocksPath)
            root.long("blocksFileBytes")?.let {
                if (it != size) { report.error("$blocksName is $size bytes but the index expects $it. The room library is incomplete or its two files do not belong together; an override folder must hold both room files, from the same build."); return null }
            }
            root.str("blocksFileCrc32")?.let { want ->
                val got = try { fileCrc32(blocksPath) } catch (e: Exception) { report.error("$blocksName is unreadable${source.note(blocksName)}: ${e.message}"); return null }
                if (got != want.lowercase()) { report.error("$blocksName does not match its index (checksum $got, expected $want). An override folder must hold both room files, from the same build."); return null }
            }
            val blobSource = try { FileBlobSource(blocksPath) } catch (e: Exception) { report.error("$blocksName cannot be opened${source.note(blocksName)}: ${e.message}"); return null }
            val pack = fromIndex(root, blobSource, report)
            if (pack == null) blobSource.close()
            return pack
        }

        /** Builds the pack from an already parsed index and a blob source (tests, tools). Null when the header is wrong. */
        fun fromIndex(root: JsonObject, blobs: BlobSource?, report: PackReport = PackReport()): RoomPack? {
            if (!checkHeader(root, FORMAT, SUPPORTED_VERSION, PackFiles.ROOMS_INDEX, report)) return null
            val textures = root.arr("textures").objects().mapNotNull { t ->
                val id = t.str("id") ?: return@mapNotNull null
                SkullTexture(id, t.str("value"), t.str("profileId"), t.str("role") ?: "unknown")
            }
            val designs = ArrayList<RoomDesign>()
            for (o in root.arr("designs").objects()) {
                try { designs += parseDesign(o) } catch (e: Exception) { report.warn("A room design was skipped (${o.str("id") ?: "no id"}): ${e.message}") }
            }
            val cores = root.arr("cores").objects().mapNotNull { c ->
                CoreEntry(c.int("core") ?: return@mapNotNull null, c.str("design") ?: return@mapNotNull null, c.str("variant"))
            }
            val aliases = root.obj("aliases")?.entrySet()?.mapNotNull { (k, v) -> v.asStr()?.let { normalise(k) to it } }?.toMap().orEmpty()
            if (designs.isEmpty()) report.warn("The room library has no designs.")
            return RoomPack(root.int("version") ?: 1, root.str("mc"), root.str("generated"), designs, textures, cores, aliases, blobs, report)
        }

        private fun parseDesign(o: JsonObject): RoomDesign {
            val id = o.str("id") ?: throw PackFormatException("no id")
            val shape = o.str("shape") ?: throw PackFormatException("no shape")
            return RoomDesign(
                id = id,
                name = normalise(o.str("name") ?: id.replace('_', ' ')),
                family = o.str("family") ?: id,
                buildKey = o.str("buildKey"),
                type = o.str("type") ?: "normal",
                shape = shape,
                tiles = o.get("tiles").asTiles(),
                w = o.int("W") ?: throw PackFormatException("no W"),
                d = o.int("D") ?: throw PackFormatException("no D"),
                h = o.int("H") ?: 0,
                y0 = o.int("y0") ?: 0,
                roofY = o.int("roofY") ?: 0,
                rotations = o.arr("rotations").strings().mapNotNull { Rot.fromWord(it) },
                doors = o.str("doors"),
                source = o.obj("source")?.let { RoomSource(it.str("blocks"), it.str("file"), it.str("ref"), it.int("captureRotation")) },
                vol = o.obj("vol")?.let { parseBlob(it) },
                slots = o.arr("slots").objects().mapNotNull { s ->
                    DoorSlot(s.get("tile").asTile() ?: return@mapNotNull null, (s.str("side") ?: return@mapNotNull null).firstOrNull() ?: return@mapNotNull null,
                        s.bool("open") ?: false, s.obj("seal")?.let { parseSeal(it) })
                },
                blockEntities = o.arr("blockEntities").objects().mapNotNull { b ->
                    val x = b.int("x") ?: return@mapNotNull null; val y = b.int("y") ?: return@mapNotNull null; val z = b.int("z") ?: return@mapNotNull null
                    RoomBlockEntity(Pos(x, y, z), b.str("type") ?: "other", b.int("tex"), b.arr("text")?.strings(), b.bool("marker") ?: false, b.str("src"))
                },
                secrets = o.arr("secrets").objects().mapNotNull { s ->
                    val x = s.int("x") ?: return@mapNotNull null; val y = s.int("y") ?: return@mapNotNull null; val z = s.int("z") ?: return@mapNotNull null
                    RoomSecret(s.str("id") ?: "s?", s.str("type") ?: "chest", s.str("bucket"), Pos(x, y, z), s.str("facing"),
                        s.get("lever").asPos(), s.get("alt").asPosList(), s.bool("baked") ?: false, s.str("src"))
                },
                levers = o.arr("levers").objects().mapNotNull { l -> RoomLever(l.get("pos").asPos() ?: return@mapNotNull null, l.get("opens").asPosList()) },
                crypts = o.arr("crypts")?.map { it.asPosList() }.orEmpty(),
                superboomWalls = o.arr("superboomWalls")?.map { it.asPosList() }.orEmpty(),
                fallingBlocks = o.get("fallingBlocks").asPosList(),
                crushers = o.arr("crushers").objects().map { c ->
                    RoomCrusher(c.str("id") ?: "crusher", c.arr("slab").ints(), c.str("axis"), c.int("sign") ?: 1, c.int("sweep") ?: 0, c.str("material"))
                },
                tripwires = o.arr("tripwires")?.mapNotNull { t ->
                    val a = t.asArr() ?: return@mapNotNull null
                    if (a.size() < 3) return@mapNotNull null
                    RoomTripwire(Pos(a[0].asIntOrNull() ?: return@mapNotNull null, a[1].asIntOrNull() ?: return@mapNotNull null, a[2].asIntOrNull() ?: return@mapNotNull null),
                        a.getOrNull(3).asStr())
                }.orEmpty(),
                puzzle = o.obj("puzzle")?.let { p ->
                    RoomPuzzle(p.str("kind") ?: "unknown", p.obj("anchors")?.entrySet()?.associate { (k, v) -> k to anchorList(v) }.orEmpty())
                },
                odin = o.obj("odin"),
                variants = o.arr("variants").objects().mapNotNull { v ->
                    RoomVariant(v.str("id") ?: return@mapNotNull null, v.str("kind") ?: "state", v.int("core"), v.str("srcFile"), v.int("changedCells") ?: 0,
                        parseBlob(v.obj("vol") ?: return@mapNotNull null))
                },
                gaps = o.arr("gaps").strings(),
                baseCores = o.arr("baseCores")?.mapNotNull { it.asIntOrNull() }.orEmpty(),
                clear = o.str("clear"),
            )
        }

        /** An anchor is one `[x,y,z]` or a list of them. */
        private fun anchorList(v: com.google.gson.JsonElement): List<Pos> {
            v.asPos()?.let { return listOf(it) }
            return v.asPosList()
        }

        private fun com.google.gson.JsonArray.getOrNull(i: Int): com.google.gson.JsonElement? = if (i < size()) get(i) else null

        private fun parseBlob(o: JsonObject) = BlobRef(o.str("kind") ?: "volume", o.long("off") ?: 0, o.int("len") ?: 0, o.str("crc32"), o.int("nonAir") ?: 0)

        private fun parseSeal(o: JsonObject) = SlotSeal(
            o.str("src"), o.bool("incomplete") ?: false,
            o.arr("cells")?.mapNotNull { c ->
                val a = c.asArr() ?: return@mapNotNull null
                if (a.size() < 4) return@mapNotNull null
                SealCell(Pos(a[0].asIntOrNull() ?: return@mapNotNull null, a[1].asIntOrNull() ?: return@mapNotNull null, a[2].asIntOrNull() ?: return@mapNotNull null), a[3].asStr() ?: return@mapNotNull null)
            }.orEmpty(),
        )

        private fun fileCrc32(path: Path): String {
            val c = java.util.zip.CRC32()
            Files.newInputStream(path).use { s -> val buf = ByteArray(1 shl 16); while (true) { val n = s.read(buf); if (n < 0) break; c.update(buf, 0, n) } }
            return "%08x".format(c.value)
        }
    }
}

/**
 * Shared header check for every pack: `format` must match and `version` must be the supported one. A newer version is
 * refused with a message naming both versions (the pack specs, section 1).
 */
internal fun checkHeader(root: JsonObject, format: String, supported: Int, file: String, report: PackReport): Boolean {
    val f = root.str("format")
    if (f != format) { report.error("$file is not a '$format' pack (its format says '${f ?: "nothing"}')."); return false }
    val v = root.int("version")
    if (v == null) { report.error("$file has no version."); return false }
    if (v > supported) { report.error("$file is version $v but this ADS reads version $supported. Update the mod or use an older pack."); return false }
    if (v < supported) { report.error("$file is version $v, older than the version $supported this ADS reads."); return false }
    return true
}
