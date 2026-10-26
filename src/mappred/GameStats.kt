package com.engineerclient.mappred

/**
 * What [GameSampler] draws an F7 map's big rooms with (tools/mappred gen-game.mjs writes this file; change that,
 * not this). [MIXES]: each big-room mix of the 2486 complete F7/M7 maps (25 of them with 6 big rooms),
 * as counts of 1x2, 1x3, 1x4, 2x2 and L (the [Shape] order), then how many maps had it. [MIX_RATE]: the chance one
 * try with that mix gets through (one random spot per big room with none clashing, the door tree and its dead
 * ends), per try (tools/mappred placelik.mjs MIXRATE, big/try1). The game makes the map again, mix and all, when a
 * try fails, so a mix is drawn by maps / rate.
 */
object GameStats {
    val MIXES = arrayOf(
        intArrayOf(1, 2, 1, 1, 2, 496),
        intArrayOf(2, 1, 1, 2, 1, 375),
        intArrayOf(1, 2, 0, 2, 2, 366),
        intArrayOf(2, 1, 2, 1, 1, 299),
        intArrayOf(1, 2, 2, 0, 2, 212),
        intArrayOf(2, 2, 1, 2, 0, 135),
        intArrayOf(2, 0, 2, 1, 2, 115),
        intArrayOf(2, 0, 1, 2, 2, 114),
        intArrayOf(1, 2, 1, 2, 1, 71),
        intArrayOf(2, 2, 2, 1, 0, 63),
        intArrayOf(1, 1, 2, 1, 2, 57),
        intArrayOf(1, 1, 1, 2, 2, 49),
        intArrayOf(1, 2, 2, 1, 1, 42),
        intArrayOf(2, 1, 2, 2, 0, 25),
        intArrayOf(2, 0, 2, 2, 1, 23),
        intArrayOf(0, 1, 2, 2, 1, 14),
        intArrayOf(1, 1, 2, 2, 1, 10),
        intArrayOf(0, 0, 2, 2, 2, 7),
        intArrayOf(0, 2, 1, 2, 2, 5),
        intArrayOf(0, 2, 2, 2, 0, 4),
        intArrayOf(0, 2, 2, 1, 2, 3),
        intArrayOf(1, 2, 2, 2, 0, 1),
    )

    val MIX_RATE = doubleArrayOf(
        0.000146641,
        0.000183419,
        0.000168415,
        0.000169566,
        0.000142038,
        0.000217853,
        0.000134868,
        0.000171964,
        0.0000880279,
        0.000201344,
        0.0000642913,
        0.0000680194,
        0.0000720673,
        0.0000896165,
        0.0000747073,
        0.000270991,
        0.0000309437,
        0.000240711,
        0.0000265031,
        0.000294645,
        0.0000245930,
        0.0000390623,
    )
}
