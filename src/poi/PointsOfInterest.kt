package com.engineerclient.poi

/**
 * Spots in the F7/M7 boss that matter to the team: for now the early enters, where someone stands in the next P3 section
 * before its gate is open, so the rest can leap to them and be through early. Each has a stable [Poi.id] (it goes over
 * the party link, [PoiWire], so a number is never reused for another spot), the section it gets you into ([Poi.opens])
 * and the boxes a player's feet are in when they're on it.
 *
 * Each box is the spot's standable ground, from the boss's own blocks (P3 Sim's arena.bin, the boss as built), and the air
 * just above it: someone there is standing on the spot or will land on it, and so will whoever leaps to them. Not where
 * recorded leaps went (people leap to the wrong place too). Every box was checked by dropping a player through it at
 * points across it: all land on the spot, none in the lava or anywhere lower.
 *  - EE2: the quartz platform in front of S2's device (Lights), feet at 132: x 57-61 along its front (z 138-139) and
 *    x 59-61 back to the levers (z 140-142). Not the S2 floor by the 3rd terminal (Andrew, 2026-11-07: not an early enter).
 *  - EE3: low, past S2's gate on S3's side, the walkway over the lava by the S2 / S3 corner (x 1-2, z 98-107), feet at
 *    109, where they wait. Not the walkway they come in on, a jump short of it (z 111-112, x 8-9; Andrew, 2026-11-07: not
 *    an early enter).
 *  - [EE3_HIGH]: the slab ledge on S3's east wall (x 18, z 91-99), feet at 121.5. A niche strategy some parties use,
 *    decided ahead of time, so EE Highlight only counts it when its own setting is on.
 *  - EE4: the walkway from S4's corridor up to the core door (x 53-55, z 43-50), feet at 115, and the step in front of the
 *    door (z 51-53); it stops at z 54, the door, where Recore's core box starts.
 */
object PointsOfInterest {
    /**
     * A box of feet positions, corners in either order: inclusive, except its far z side (so EE4's stops where the core
     * starts).
     */
    class Box(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double, private val label: String) {
        val minX = minOf(x0, x1); val maxX = maxOf(x0, x1)
        val minY = minOf(y0, y1); val maxY = maxOf(y0, y1)
        val minZ = minOf(z0, z1); val maxZ = maxOf(z0, z1)
        fun contains(x: Double, y: Double, z: Double) = x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z < maxZ
        override fun toString() = label
    }

    /** One spot: [opens] is the section standing on it puts you in (2-4). */
    class Poi(val id: Int, val key: String, val label: String, val opens: Int, val boxes: List<Box>) {
        fun contains(x: Double, y: Double, z: Double) = boxes.any { it.contains(x, y, z) }
        override fun toString() = label
    }

    val EE2 = Poi(1, "ee2", "EE2", 2, listOf(
        Box(57.0, 131.5, 138.0, 62.0, 136.0, 140.0, "platform"),
        Box(59.0, 131.5, 140.0, 62.0, 136.0, 143.0, "platform, by the levers"),
    ))

    val EE3 = Poi(2, "ee3", "EE3", 3, listOf(
        Box(1.0, 108.5, 98.0, 3.0, 112.0, 108.0, "walkway"),
    ))

    val EE3_HIGH = Poi(4, "ee3high", "EE3 high", 3, listOf(
        Box(18.0, 121.5, 91.0, 19.0, 125.0, 100.0, "ledge"),
    ))

    val EE4 = Poi(3, "ee4", "EE4", 4, listOf(
        Box(53.0, 114.5, 43.0, 56.0, 119.0, 51.0, "walkway"),
        Box(51.0, 113.5, 51.0, 58.0, 119.0, 53.0, "by the core door"),
        Box(52.0, 114.5, 53.0, 57.0, 119.0, 54.0, "by the core door"),
    ))

    val ALL: List<Poi> = listOf(EE2, EE3, EE3_HIGH, EE4)

    init {
        // A number on the wire means one spot for good (5 up are free; 1-4 taken).
        check(ALL.map { it.id }.toSet().size == ALL.size && ALL.none { it.id == 0 || it.id > 255 })
    }

    fun byId(id: Int): Poi? = ALL.firstOrNull { it.id == id }

    /** The spot whose box [x], [y], [z] are in, or null. */
    fun at(x: Double, y: Double, z: Double): Poi? = ALL.firstOrNull { it.contains(x, y, z) }
}
