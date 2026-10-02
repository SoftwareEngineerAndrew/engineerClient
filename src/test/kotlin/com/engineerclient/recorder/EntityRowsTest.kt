package com.engineerclient.recorder

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Per-tick entity rows (changed only, exact) and the drawn set's change tracking. */
class EntityRowsTest {

    private fun row(x: Double, interp: Boolean = false, living: Boolean = true) = DoubleArray(EntRows.WIDTH).also {
        it[0] = x; it[1] = 64.0; it[2] = -3.25
        it[3] = x - 0.1; it[4] = 64.0; it[5] = -3.25
        it[6] = 90.5f.toDouble(); it[7] = (-12.3f).toDouble(); it[8] = 91f.toDouble(); it[9] = if (living) 0.1f.toDouble() else Double.NaN
        it[13] = 1.0; it[14] = if (interp) 1.0 else 0.0
        it[15] = x; it[16] = 64.0; it[17] = -3.25
        it[18] = if (living) 20f.toDouble() else Double.NaN
        it[19] = if (interp) x + 1 else Double.NaN; it[20] = if (interp) 65.0 else Double.NaN; it[21] = if (interp) -3.0 else Double.NaN
    }

    @Test
    fun `only changed rows count, bit for bit`() {
        val r = EntRows()
        assertTrue(r.changed(1, row(1.0)))
        assertFalse(r.changed(1, row(1.0)))
        assertTrue(r.changed(1, row(1.0 + Math.ulp(1.0))))
        assertFalse(r.changed(2, row(5.0).also { r.changed(2, it) }))
        r.remove(1)
        assertTrue(r.changed(1, row(1.0 + Math.ulp(1.0))))
    }

    @Test
    fun `a row keeps exact doubles, floats as floats, and nulls for what is not there`() {
        val sb = StringBuilder()
        EntRows.append(sb, 42, row(0.1 + 0.2, living = false))
        val a = JsonParser.parseString(sb.toString()).asJsonArray
        assertEquals(20, a.size())
        assertEquals(42, a[0].asInt)
        assertEquals(0.1 + 0.2, a[1].asDouble)
        assertEquals("90.5", a[7].toString())
        assertEquals("-12.3", a[8].toString())
        assertTrue(a[10].isJsonNull)
        assertEquals(1, a[14].asInt)
        assertTrue(a[19].isJsonNull)
    }

    @Test
    fun `an interpolating row carries its target`() {
        val sb = StringBuilder()
        EntRows.append(sb, 1, row(2.0, interp = true))
        val a = JsonParser.parseString(sb.toString()).asJsonArray
        assertEquals(23, a.size())
        assertEquals(listOf(3.0, 65.0, -3.0), (20..22).map { a[it].asDouble })
    }

    @Test
    fun `drawn ids are one set per tick, tags and outlines only when they change`() {
        val d = DrawnTracker<String>()
        assertNull(d.flush { _, _ -> })
        d.add(1, "Boss", 0xFF0000, true); d.add(2, null, 0, false); d.add(1, "Boss", 0xFF0000, true)
        val tags = ArrayList<Pair<Int, String?>>(); val outs = ArrayList<Triple<Int, Int, Boolean>>()
        assertEquals(listOf(1, 2), d.flush { id, t -> tags += id to t }!!.toList())
        d.flushOutlines { id, c, g -> outs += Triple(id, c, g) }
        assertEquals(listOf<Pair<Int, String?>>(1 to "Boss"), tags)
        assertEquals(listOf(Triple(1, 0xFF0000, true), Triple(2, 0, false)), outs)

        tags.clear(); outs.clear()
        d.add(1, "Boss", 0xFF0000, true)
        d.flush { id, t -> tags += id to t }; d.flushOutlines { id, c, g -> outs += Triple(id, c, g) }
        assertTrue(tags.isEmpty()); assertTrue(outs.isEmpty())

        // Not drawn for a tick: nothing forgotten. Drawn again without its tag: null.
        d.add(2, null, 0, false); d.flush { _, _ -> }; d.flushOutlines { _, _, _ -> }
        d.add(1, null, 0xFF0000, false)
        d.flush { id, t -> tags += id to t }; d.flushOutlines { id, c, g -> outs += Triple(id, c, g) }
        assertEquals(listOf<Pair<Int, String?>>(1 to null), tags)
        assertEquals(listOf(Triple(1, 0xFF0000, false)), outs)
    }
}
