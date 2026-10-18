package com.engineerclient.p3sim.pack

import com.engineerclient.p3sim.pack.rooms.OdinCore
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Odin's room core on invented columns, written out the long way (FORMAT-rooms 8.2). */
class OdinCoreTest {
    private fun block(n: String) = "Block{minecraft:$n}"

    @Test fun anEmptyColumnIsAllZeros() {
        assertEquals("0".repeat(129).hashCode(), OdinCore.hash { null })
    }

    @Test fun zerosAboveTheFirstSolidThenBlockNames() {
        val col = { y: Int -> if (y <= 100) "minecraft:stone" else null }
        val want = "0".repeat(40) + block("stone").repeat(100 - 12 + 1)
        assertEquals(want.hashCode(), OdinCore.hash(col))
    }

    @Test fun goldAboveTheRoofCountsAsAir() {
        val a = OdinCore.hash { y -> if (y == 120) "minecraft:gold_block" else if (y <= 100) "minecraft:stone" else null }
        val b = OdinCore.hash { y -> if (y <= 100) "minecraft:stone" else null }
        assertEquals(b, a)
    }

    @Test fun planksAndChestsAreSkippedAndPropertiesDoNotMatter() {
        val noisy = OdinCore.ofStates { y -> if (y == 90) "minecraft:chest[facing=north]" else if (y == 80) "minecraft:oak_planks" else if (y <= 100) "minecraft:stone[x=y]" else null }
        val want = "0".repeat(40) + block("stone").repeat(100 - 12 + 1 - 2)   // the two skipped layers write nothing at all
        assertEquals(want.hashCode(), noisy)
    }

    @Test fun twoBedrockLayersThenAirEndTheColumnWithZeros() {
        val col = { y: Int -> when { y >= 70 && y <= 140 -> "minecraft:stone"; y == 68 || y == 67 -> "minecraft:bedrock"; else -> null } }
        val sb = StringBuilder()
        for (y in 140 downTo 70) sb.append(block("stone"))
        sb.append(block("air"))              // y 69: air, no bedrock above it yet
        sb.append(block("bedrock")).append(block("bedrock"))
        sb.append("0".repeat(66 - 11))       // y 66: air under two bedrock layers
        assertEquals(sb.toString().hashCode(), OdinCore.hash(col))
    }

    @Test fun anythingAddedAboveTheRoofChangesTheCore() {
        val base = OdinCore.hash { y -> if (y <= 100) "minecraft:stone" else null }
        assertNotEquals(base, OdinCore.hash { y -> if (y <= 100 || y == 110) "minecraft:stone" else null })
    }
}
