package com.engineerclient.misc

import com.engineerclient.misc.UpdateCheck.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** UpdateCheck's reading of update.json and how hard each older version is told. */
class UpdateCheckTest {
    private fun u(v: String, hard: String = "", now: String = "") = UpdateCheck.Update(v, "", hard, now)

    @Test fun versionsCompareAsNumbers() {
        assertEquals(-1, UpdateCheck.compare("1.0.9", "1.0.13"))
        assertEquals(1, UpdateCheck.compare("1.1", "1.0.13"))
        assertEquals(0, UpdateCheck.compare("1.0", "1.0.0"))
    }

    @Test fun levels() {
        assertEquals(Level.NONE, UpdateCheck.level("1.0.13", u("1.0.13")))
        assertEquals(Level.NONE, UpdateCheck.level("1.0.14", u("1.0.13", hard = "1.0.13", now = "1.0.13")))
        assertEquals(Level.SOFT, UpdateCheck.level("1.0.12", u("1.0.13")))
        // hard and now hold for every version below them, whatever the newest release's own level.
        assertEquals(Level.SOFT, UpdateCheck.level("1.0.13", u("1.0.15", hard = "1.0.13")))
        assertEquals(Level.HARD, UpdateCheck.level("1.0.12", u("1.0.15", hard = "1.0.13")))
        assertEquals(Level.NOW, UpdateCheck.level("1.0.11", u("1.0.15", hard = "1.0.13", now = "1.0.12")))
        assertEquals(Level.HARD, UpdateCheck.level("1.0.12", u("1.0.15", hard = "1.0.13", now = "1.0.12")))
    }

    @Test fun parse() {
        val p = UpdateCheck.parse("""{"version":"1.0.14","note":"§cneeds Odin 0.4.0","hard":"1.0.14","now":""}""")!!
        assertEquals("1.0.14", p.version); assertEquals("needs Odin 0.4.0", p.note); assertEquals("1.0.14", p.hard); assertEquals("", p.now)
        assertNull(UpdateCheck.parse("""{"version":"latest"}"""))
        assertNull(UpdateCheck.parse("""{"version":"1.0.14","hard":"x"}"""))
        assertNull(UpdateCheck.parse("not json"))
    }
}
