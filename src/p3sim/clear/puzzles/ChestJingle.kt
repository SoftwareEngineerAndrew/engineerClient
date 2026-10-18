package com.engineerclient.p3sim.clear.puzzles

/** The harp notes after a reward chest opens: five, every 5 ticks, starting 3 ticks after the lid signal (measured; the hard puzzles' chests do the same). */
object ChestJingle {
    val PITCHES = floatArrayOf(0.7936508f, 0.8888889f, 1.0f, 1.0952381f, 1.1904762f)
    const val FIRST_NOTE = 3
    const val NOTE_GAP = 5
}
