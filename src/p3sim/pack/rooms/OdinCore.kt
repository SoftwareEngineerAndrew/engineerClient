package com.engineerclient.p3sim.pack.rooms

/**
 * Odin's room core (`WorldScan.getRoomCore`, FORMAT-rooms 8.2): the hash of a tile's CENTRE column that Odin looks up in its
 * `rooms.json` to name a room. Pure, so the layout module and its tests can check headless that a pasted room will be
 * recognised, without starting the game. Mirrors the reference `odin_core` of the rooms pack tooling, which reproduced 245 of
 * Odin's 272 cores from real captures (the other 27 are variants the pack could not see).
 *
 * The column is read from y 140 down to 12. Above the first solid block a `0` is written per layer (gold block counts as air
 * there); from the first other block on, `Block{<name>}` per layer (no properties; air is `Block{minecraft:air}`), skipping
 * oak planks and chests; when air sits under two or more bedrock layers below y 69 the rest of the column is `0` characters and the
 * scan stops. The core is the Java `String.hashCode` of the result. Anything added above a room's roof in this column, or a
 * missing bedrock floor, changes the core and Odin then names a different room (or none).
 */
object OdinCore {
    private const val AIR = "minecraft:air"

    /** The core of the column whose block name at height y is `name(y)` (properties stripped; null or the air name for air). */
    fun hash(name: (Int) -> String?): Int {
        val sb = StringBuilder()
        var found = false
        var bedrock = 0
        var y = 140
        while (y >= 12) {
            val b = name(y)?.takeIf { it != AIR }
            if (!found) {
                if (b != null && b != "minecraft:gold_block") found = true else sb.append('0')
            }
            if (found) {
                if (b == null && bedrock >= 2 && y < 69) { repeat(y - 11) { sb.append('0') }; break }
                if (b == "minecraft:bedrock") bedrock++
                else {
                    bedrock = 0
                    if (b == "minecraft:oak_planks" || b == "minecraft:trapped_chest" || b == "minecraft:chest") { y--; continue }
                }
                sb.append("Block{").append(b ?: AIR).append('}')
            }
            y--
        }
        return sb.toString().hashCode()
    }

    /** Like [hash] for a column given as full state strings (`minecraft:chest[facing=north]`): the properties are stripped first. */
    fun ofStates(state: (Int) -> String?): Int = hash { y -> state(y)?.substringBefore('[') }

    /** The core of canonical column ([x], [z]) of [volume]. Rotation does not change it: a tile centre maps to a tile centre. */
    fun ofVolume(volume: RoomVolume, x: Int, z: Int): Int = ofStates { y -> if (volume.contains(x, y, z)) volume.stateAt(x, y, z) else null }

    /** The cores of every tile centre of a canonical room of the given tile cells, in the order of [tiles]. */
    fun ofRoom(volume: RoomVolume, tiles: List<com.engineerclient.p3sim.pack.Tile>): List<Int> = tiles.map { ofVolume(volume, 32 * it.x + 15, 32 * it.z + 15) }
}
