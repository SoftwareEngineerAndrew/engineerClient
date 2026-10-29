package com.engineerclient.dungeonpack.rooms

import com.engineerclient.dungeonpack.pack.DungeonFrame
import com.engineerclient.dungeonpack.pack.doors.DoorPack
import com.engineerclient.dungeonpack.pack.doors.DoorUnit
import com.engineerclient.dungeonpack.pack.layouts.DoorKind
import kotlin.math.abs
import kotlin.random.Random

/**
 * Hypixel's door, as blocks (ads-handoffs results/room-doors.md): one UNIT pasted over the gap column and three planes into each room
 * (dd -3..3 across the gap, offsets a 13..17 along the side, y 66..73), then a floor drawn at y 68 under dd -2..2 x a 12..18. The unit is
 * one of six ordinary styles picked 1 in 6 per door, independently; a wither or fairy door writes its style's key cells (heads) over it.
 * The entrance and blood doors have their own units; [floor] says how the floor is drawn. The body of the kind fills the opening ([DoorGeometry.bodyEdits]).
 *
 * Cells are in the S-axis frame of the pack; an E-axis door turns them three quarter turns (`z = min + 30 - a`).
 */
object DoorUnits {
    const val FLOOR_Y = 68
    const val DD_MAX = 3
    const val A_MIN = 13
    const val A_MAX = 17

    /** World (x, z) of the unit cell ([dd], [a]) of [slot]. */
    fun cell(slot: Slot, dd: Int, a: Int): Pair<Int, Int> = when (slot.axis) {
        'S' -> (DungeonFrame.tileMin(slot.tx) + a) to (DungeonFrame.tileMin(slot.tz) + 31 + dd)
        else -> (DungeonFrame.tileMin(slot.tx) + 31 + dd) to (DungeonFrame.tileMin(slot.tz) + 30 - a)
    }

    /** Quarter turns from the pack's frame to [slot]'s. */
    fun turns(slot: Slot): Int = if (slot.axis == 'S') 0 else 3

    /** One door's own randomness: a seed gives the same dungeon every time. */
    fun random(slot: Slot, seed: Long): Random = Random(seed xor ((slot.axis.code * 36 + slot.tx * 6 + slot.tz + 1).toLong() * -0x61c8864680b583ebL))

    /** The unit of a door of [kind]: the entrance's or blood's own, else one of the six styles, uniformly. */
    fun unitFor(pack: DoorPack, kind: DoorKind, rnd: Random): DoorUnit = when (kind) {
        DoorKind.ENTRANCE -> pack.entrance
        DoorKind.BLOOD -> pack.blood
        else -> pack.styles[rnd.nextInt(pack.styles.size)]
    }

    /**
     * The edits of one connected door: the unit (with the key heads on a wither or fairy door), the floor, then the body. [floorAt] is the
     * rooms' block at a world cell of y 68 as pasted (their open or closed look), [keepAt] the block the room has there without a door
     * (its closed look); both null for air or no room.
     */
    fun edits(pack: DoorPack, slot: Slot, kind: DoorKind, seed: Long, keepAt: (Int, Int) -> Block? = { _, _ -> null },
              floorAt: (Int, Int) -> Block?): List<Edit> = buildList {
        val rnd = random(slot, seed)
        val unit = unitFor(pack, kind, rnd)
        val key = kind == DoorKind.WITHER || kind == DoorKind.FAIRY
        val head = when { unit === pack.blood -> pack.bloodHead; key -> pack.keyHead; else -> pack.plainHead }
        val q = turns(slot)
        val cells = if (key) unit.cells.associateBy { Triple(it.dd, it.a, it.y) } + unit.keyCells.associateBy { Triple(it.dd, it.a, it.y) }
            else unit.cells.associateBy { Triple(it.dd, it.a, it.y) }
        for (c in cells.values) {
            val (x, z) = cell(slot, c.dd, c.a)
            add(Edit(x, c.y, z, c.state, q, if ("player_" in c.state) head else null))
        }
        addAll(floor(slot, unit, rnd, keepAt, floorAt))
        addAll(DoorGeometry.bodyEdits(slot, kind))
    }

    /** Chance that a cell of the floor's outer columns (a 12 and 18, all five planes) keeps the block already there, air in the gap (n 8,907, +-1.0 %). */
    const val OUTER_KEEP = 0.535
    /** Chance that a cell under the pillars (a 13 and 17, all five planes) is the style's floor block, [styleFloor] (n 20,254, +-0.7 %). */
    const val PILLAR_STYLE = 0.29
    /** Chance that an opening cell (a 14..16) in a room plane (dd +-1, +-2) keeps the room's own block (n 4,104, +-1.1 %); never in the gap. */
    const val OPEN_KEEP = 0.167

    /** The block a style lays under its pillars: its own material; PA_SB polished andesite under the arch planes, stone bricks under the frame. */
    fun styleFloor(unit: DoorUnit, dd: Int): String = "minecraft:" + when (unit.name) {
        "SPRUCE" -> "spruce_planks"
        "BLOOD" -> "nether_bricks"
        "COBBLE_ACACIA" -> "cobblestone"
        "PA_SB" -> if (abs(dd) == 2) "polished_andesite" else "stone_bricks"
        else -> "stone_bricks"
    }

    /**
     * The door floor (y 68, dd -2..2 x a 12..18), cell by cell, as Hypixel draws it (ads-handoffs results/room-doors-floor.md, 2,774 doors
     * of 140 runs). The sources are the six room cells in front of the opening: a 14..16 of both rooms' depth-2 rows (dd +-3). A drawn cell
     * takes one of their DISTINCT blocks, uniformly (a block three of the six share counts once), a half slab as its full block, a stair with
     * its facing (vanilla gives it its shape after). Before the draw a cell may keep the block it has ([OUTER_KEEP], [OPEN_KEEP]) or take
     * the style's ([PILLAR_STYLE]). Against the recordings a floor drawn this way agrees with Hypixel's, cell for cell, as often as two draws
     * of the rule agree with each other (ratio 0.98-1.02 in every class of cell), so nothing measurable is left out.
     *
     * UNMEASURED: sources of air or fluid (no recorded door had one); they are skipped, and stone stands in when none is left.
     */
    fun floor(slot: Slot, unit: DoorUnit, rnd: Random, keepAt: (Int, Int) -> Block?, floorAt: (Int, Int) -> Block?): List<Edit> = buildList {
        val sources = buildList {
            for (dd in listOf(-DD_MAX, DD_MAX)) for (a in A_MIN + 1..A_MAX - 1) {
                val (x, z) = cell(slot, dd, a)
                floorAt(x, z)?.let { b -> drawable(b)?.let { add(it) } }
            }
        }.distinctBy { it.kind() }.ifEmpty { listOf(Block(STONE)) }
        for (dd in -2..2) for (a in 12..18) {
            val (x, z) = cell(slot, dd, a)
            val u = rnd.nextDouble()
            val kept = when (a) {
                12, 18 -> u < OUTER_KEEP
                A_MIN, A_MAX -> false
                else -> dd != 0 && u < OPEN_KEEP
            }
            val b = when {
                kept -> if (dd == 0) Block(AIR) else keepAt(x, z) ?: Block(AIR)
                (a == A_MIN || a == A_MAX) && u < PILLAR_STYLE -> Block(styleFloor(unit, dd))
                else -> sources[rnd.nextInt(sources.size)]
            }
            add(Edit(x, FLOOR_Y, z, b.state, b.quarterTurns))
        }
    }

    /** A source as it is copied: a half slab becomes its full block; air and fluids are no source. */
    private fun drawable(b: Block): Block? {
        val name = b.state.substringBefore('[')
        if (name in NOT_SOURCES) return null
        if (!name.endsWith("_slab") || "type=double" in b.state) return b
        val m = name.removePrefix("minecraft:").removeSuffix("_slab")
        return Block("minecraft:" + when {
            m in WOODS -> "${m}_planks"
            m.endsWith("brick") -> "${m}s"
            m == "quartz" -> "quartz_block"
            else -> m
        })
    }

    /** What makes two sources the same block: the state without its stair shape, and the turn only where the state has a direction. */
    private fun Block.kind(): String {
        val s = state.replace(SHAPE, "")
        return if (DIRECTED.containsMatchIn(s)) "$s@${quarterTurns.mod(4)}" else s
    }

    private val SHAPE = Regex(",?shape=\\w+")
    private val DIRECTED = Regex("facing=|axis=|rotation=")
    private val WOODS = setOf("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak")
    private val NOT_SOURCES = setOf("minecraft:air", "minecraft:cave_air", "minecraft:water", "minecraft:lava")

    /**
     * The cells Hypixel sends again, unchanged, with each step of a body's fall (barrier, then air), in the same section update: the top
     * of the opening in the arch planes, dd +-2 x a 14..16 at y 72 (stairs or iron bars by style). DR, 289 doors: every one at both
     * steps; nothing else round the body is sent, and no shape changes (the bars keep their connection to the fallen body).
     */
    fun fallResend(slot: Slot): List<Triple<Int, Int, Int>> = buildList {
        for (dd in listOf(-2, 2)) for (a in A_MIN + 1..A_MAX - 1) { val (x, z) = cell(slot, dd, a); add(Triple(x, 72, z)) }
    }

    /**
     * The cells whose connections vanilla recomputes after the paste (walls, bars, stairs; fire and vines without support go): the unit
     * and its floor with one cell round them. Every door's box lies inside one chunk (the gap is 7 blocks from a chunk edge).
     */
    fun shapeCells(slot: Slot): List<Triple<Int, Int, Int>> = buildList {
        for (dd in -DD_MAX - 1..DD_MAX + 1) for (a in 12..18) {
            val (x, z) = cell(slot, dd, a)
            for (y in 65..74) add(Triple(x, y, z))
        }
    }

    private const val AIR = "minecraft:air"
    private const val STONE = "minecraft:stone"
}
