package com.bloodrushwaypoints.rotation

/**
 * Where the four phase-3 sections are in the world.
 *
 * The F7/M7 boss room always generates at the same coordinates, so these need no calibration.
 * The boxes are inherited from the FatesLeap mod's `PhaseTracker` (same author, same floor) and
 * have not been independently re-measured — if a leap cue fires in the wrong section, these are
 * the first thing to check.
 *
 * This is only ever used for display timing: whether the person you are leaping to has actually
 * arrived. Role assignment stays entirely chat-driven, so a wrong box can make a highlight early
 * or late but can never desync who holds what.
 */
object P3Sections {

    /** Two opposite corners per section, in section order: x1 y1 z1 x2 y2 z2. */
    private val BOXES = arrayOf(
        doubleArrayOf(113.0, 160.0, 48.0, 89.0, 100.0, 122.0),
        doubleArrayOf(91.0, 160.0, 145.0, 19.0, 100.0, 121.0),
        doubleArrayOf(-6.0, 160.0, 123.0, 19.0, 100.0, 50.0),
        doubleArrayOf(17.0, 160.0, 27.0, 90.0, 100.0, 50.0),
    )

    /** Which section (1-4) contains this point, or 0 for none of them. */
    fun sectionAt(x: Double, y: Double, z: Double): Int {
        BOXES.forEachIndexed { i, b ->
            if (within(x, b[0], b[3]) && within(y, b[1], b[4]) && within(z, b[2], b[5])) return i + 1
        }
        return 0
    }

    fun isInSection(section: Int, x: Double, y: Double, z: Double): Boolean =
        section in 1..BOXES.size && sectionAt(x, y, z) == section

    private fun within(v: Double, a: Double, b: Double) = v >= minOf(a, b) && v <= maxOf(a, b)
}
