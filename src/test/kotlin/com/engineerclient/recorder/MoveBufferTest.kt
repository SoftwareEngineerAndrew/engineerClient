package com.engineerclient.recorder

import kotlin.test.Test
import kotlin.test.assertEquals

class MoveBufferTest {

    private fun rows(b: MoveBuffer) = StringBuilder().also { b.appendRows(it) }.toString()

    @Test
    fun `rows keep nulls, interpolation and snaps`() {
        val b = MoveBuffer()
        b.add(5, 1.5, 2.0, -3.25, 90f, 0f, MoveBuffer.POS or MoveBuffer.YROT or MoveBuffer.XROT or MoveBuffer.INTERP)
        b.add(6, 0.0, 0.0, 0.0, 45.5f, 0f, MoveBuffer.YROT)
        b.add(7, 100.0, 64.0, 100.0, 0f, -10f, MoveBuffer.POS or MoveBuffer.YROT or MoveBuffer.XROT or MoveBuffer.SNAP)
        assertEquals("[5,1.5,2.0,-3.25,90.0,0.0,1],[6,null,null,null,45.5,null,0],[7,100.0,64.0,100.0,0.0,-10.0,0,1]", rows(b))
    }

    @Test
    fun `a taken buffer is private and the live one starts over`() {
        val b = MoveBuffer()
        for (i in 0 until 200) b.add(i, i.toDouble(), 0.0, 0.0, 0f, 0f, MoveBuffer.POS)
        val t = b.take()
        assertEquals(0, b.count)
        b.add(999, 9.0, 9.0, 9.0, 0f, 0f, MoveBuffer.POS)
        assertEquals(200, t.count)
        assertEquals("[0,0.0,0.0,0.0,null,null,0]", rows(t).substringBefore("],[") + "]")
    }

    @Test
    fun `entity rows can share one array`() {
        val a = DoubleArray(EntRows.WIDTH) { Double.NaN }.also { it[0] = 1.0; it[1] = 2.0; it[2] = 3.0; it[13] = 0.0; it[14] = 0.0 }
        val two = a + a.copyOf().also { it[0] = 7.0 }
        val one = StringBuilder().also { EntRows.append(it, 1, a) }.toString()
        val off = StringBuilder().also { EntRows.append(it, 1, two, 0) }.toString()
        assertEquals(one, off)
        val second = StringBuilder().also { EntRows.append(it, 2, two, EntRows.WIDTH) }.toString()
        assertEquals("[2,7.0", second.substring(0, 6))
    }
}
