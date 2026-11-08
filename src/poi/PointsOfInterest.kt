package com.engineerclient.poi

/**
 * Spots in the F7/M7 boss that matter to the team: for now the early enters, where someone stands in the next P3 section
 * before its gate is open, so the rest can leap to them and be through early. Each has a stable [Poi.id] (it goes over
 * the party link, [PoiWire], so a number is never reused for another spot), the section it gets you into ([Poi.opens])
 * and the boxes a player's feet are in when they're on it.
 *
 * The boxes are Positional Messages' usual /posmsg boxes (the ee2, ee3 and "out of core" ones Andrew's 26.1.2 instances
 * have), widened to where players were really standing when teammates leapt to them in Andrew's ADR recordings
 * (2026-11-07: 824 P3s, a leap counted when its "Leaped to X!" came while the leaper was still in the section before):
 *  - EE2: on top of S2's device (Lights), 380 of 694 leaps from S1 into S2 at (60-62, 132, 139-140), the rest up the
 *    device to y 137 and x 66; and the S2 floor by the 3rd terminal (40-49, 109, 122-124), "ee2 safe spot".
 *  - EE3: low, at the S3 side of the S2/S3 corner, 200 of 416 leaps from S2 into S3 at (1-4, 109-110, 100-107), and where
 *    players said "at ee3!" (3-8, 109-111, 109-113); high, on the S3 wall's ledge (19, 122, 91) and where they said
 *    "3 safespot" (12-16, 123-126, 94-98).
 *  - EE4: just outside the core on S4's side, (54-55, 115, 45-50) and where they said "at core!" (54, 115, 50-54); it
 *    stops short of z 54, where Recore's core box starts.
 */
object PointsOfInterest {
    /** A box of feet positions, corners in either order: inclusive, except its far z side (so EE4's stops where the core starts). */
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
        Box(55.0, 130.0, 135.0, 66.5, 141.0, 144.0, "device top"),
        Box(38.0, 108.0, 121.0, 50.0, 112.0, 126.0, "safe spot"),
    ))

    val EE3 = Poi(2, "ee3", "EE3", 3, listOf(
        Box(-1.0, 108.0, 99.0, 9.0, 112.5, 114.0, "low"),
        Box(11.0, 120.0, 89.0, 20.0, 130.0, 101.0, "high"),
    ))

    val EE4 = Poi(3, "ee4", "EE4", 4, listOf(
        Box(50.0, 114.0, 43.0, 59.0, 121.0, 54.0, "outside core"),
    ))

    val ALL: List<Poi> = listOf(EE2, EE3, EE4)

    init {
        // A number on the wire means one spot for good.
        check(ALL.map { it.id }.toSet().size == ALL.size && ALL.none { it.id == 0 || it.id > 255 })
    }

    fun byId(id: Int): Poi? = ALL.firstOrNull { it.id == id }

    /** The spot whose box [x], [y], [z] are in, or null. */
    fun at(x: Double, y: Double, z: Double): Poi? = ALL.firstOrNull { it.contains(x, y, z) }

    /** The early enter into [section] (2-4), or null. */
    fun into(section: Int): Poi? = ALL.firstOrNull { it.opens == section }
}
