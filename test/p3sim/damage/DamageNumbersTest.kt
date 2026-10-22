package com.engineerclient.p3sim.damage

import com.engineerclient.p3sim.clear.damage.DamageNumbers
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DamageNumbersTest {
    @Test fun namesAreHypixels() {
        // Recorded stands (ADR): a plain hit and two crits, colour codes as sent.
        assertEquals("§726,707,180", DamageNumbers.name(26_707_180.4, false))
        assertEquals("§f✧1§r§e3§r§6,§r§c78§r§f5,§r§e5§r§62§r§c9✧", DamageNumbers.name(13_785_529.0, true))
        assertEquals("§f✧2§r§e,§r§63§r§c86§r§f,4§r§e5§r§64§r§c✧", DamageNumbers.name(2_386_454.0, true))
    }

    @Test fun placedNearTheMobAtTheMeasuredSpread() {
        val rnd = Random(3)
        val across = ArrayList<Double>(); val up = ArrayList<Double>()
        repeat(4000) { val p = DamageNumbers.place(0.0, 0.0, 0.0, rnd); across += Math.hypot(p[0], p[2]); up += p[1] }
        across.sort(); up.sort()
        assertTrue(across[2000] in 0.6..1.0, "median across ${across[2000]}")
        assertTrue(up[2000] in 1.0..1.3, "median up ${up[2000]}")
        assertTrue(up[400] in -0.45..-0.2 && up[3600] in 1.9..2.15, "up p10 ${up[400]} p90 ${up[3600]}")
    }
}
