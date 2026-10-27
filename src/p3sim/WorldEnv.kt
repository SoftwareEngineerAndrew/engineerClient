package com.engineerclient.p3sim

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

/**
 * The shape of the ADS world's sky, as Hypixel's dungeon worlds have it: the overworld dimension type with the 0 to 255 height range (16 chunk sections,
 * the range the boss arena and the dungeon were captured in) and no sky light at all. A vanilla overworld is -64 to 319 with sky light on, so a room
 * with a doorway or a gap in its roof was bright in daylight on the sim and dark on Hypixel, whose server sends sky light 0 in every cell.
 *
 * The dimension type is a registry entry: the game sends it to the client by its registry id, so it cannot be made up in code (a direct holder
 * cannot be encoded in the login packet). It is changed the way a data pack changes it: [SimWorld] writes a small data pack into the world's own
 * `datapacks` folder before the world is created, and the world's data configuration lists it. The pack holds one file, `minecraft:overworld`
 * made from the game's own copy with the few values below changed, so a game version that adds a field to the dimension type still loads. The sim
 * world is deleted and built again on every open, so the pack never outlives it.
 *
 * Pure (a file write and a JSON edit): tested headless. The Minecraft side is [com.engineerclient.p3sim.SimWorld.installEnvPack].
 */
object WorldEnv {
    /** The folder name of the pack inside the world's `datapacks` folder. */
    const val PACK_NAME = "ads-env"

    /** The pack's id in a world's data configuration: the game names a pack found in a world folder `file/<folder>`. */
    const val PACK_ID = "file/$PACK_NAME"

    /** The lowest block y and the number of blocks from it (Hypixel's dungeon world: 0 to 255). */
    const val MIN_Y = 0
    const val HEIGHT = 256

    /** The mood sound (`ambient.cave`) the vanilla overworld plays in the dark: with no sky light every enclosed room counts as dark, and Hypixel never plays it. */
    private const val AMBIENT_SOUNDS_KEY = "minecraft:audio/ambient_sounds"

    /**
     * The overworld dimension type of the ADS world, made from the game's own [vanilla] file: no sky light, `min_y` 0, `height` and `logical_height`
     * 256, `ambient_light` 0, and the cave mood sound taken out of its attributes. Everything else stays as the game ships it. Throws when [vanilla]
     * is not a JSON object.
     */
    fun dimensionTypeJson(vanilla: String): String {
        val o = JsonParser.parseString(vanilla).asJsonObject
        o.addProperty("has_skylight", false)
        o.addProperty("has_ceiling", false)
        o.addProperty("min_y", MIN_Y)
        o.addProperty("height", HEIGHT)
        o.addProperty("logical_height", HEIGHT)
        o.addProperty("ambient_light", 0.0)
        (o.get("attributes") as? JsonObject)?.remove(AMBIENT_SOUNDS_KEY)
        return GSON.toJson(o)
    }

    /**
     * `pack.mcmeta` for a data pack that fits exactly the running game: from format [major].[minor] up to any minor of [major]
     * (`SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA)`).
     */
    fun packMetaJson(major: Int, minor: Int): String {
        val pack = JsonObject()
        pack.addProperty("description", "ADS world settings")
        pack.add("min_format", GSON.toJsonTree(intArrayOf(major, minor)))
        pack.addProperty("max_format", major)
        val root = JsonObject()
        root.add("pack", pack)
        return GSON.toJson(root)
    }

    /**
     * Writes the pack into [worldDir]`/datapacks/ads-env/` (the world folder, which does not need to exist yet): `pack.mcmeta` for format
     * [packMajor].[packMinor] and `data/minecraft/dimension_type/overworld.json` from [vanillaDimensionJson]. Anything already in the pack folder
     * is replaced. Returns the pack folder.
     */
    fun installPack(worldDir: Path, vanillaDimensionJson: String, packMajor: Int, packMinor: Int): Path {
        val root = worldDir.resolve("datapacks").resolve(PACK_NAME)
        // Built before anything is written, so a bad file leaves no half pack for the game to trip over.
        val dimension = dimensionTypeJson(vanillaDimensionJson)
        val meta = packMetaJson(packMajor, packMinor)
        val dimFile = root.resolve("data").resolve("minecraft").resolve("dimension_type").resolve("overworld.json")
        Files.createDirectories(dimFile.parent)
        Files.writeString(root.resolve("pack.mcmeta"), meta)
        Files.writeString(dimFile, dimension)
        return root
    }

    /** The chunks (x, z) that cover a box of blocks from ([minX], [minZ]), [width] blocks east and [depth] blocks south (the boss arena's, say). */
    fun chunksOf(minX: Int, minZ: Int, width: Int, depth: Int): List<Pair<Int, Int>> {
        if (width <= 0 || depth <= 0) return emptyList()
        val out = ArrayList<Pair<Int, Int>>()
        for (cx in (minX shr 4)..((minX + width - 1) shr 4)) for (cz in (minZ shr 4)..((minZ + depth - 1) shr 4)) out += cx to cz
        return out
    }

    private val GSON = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
}
