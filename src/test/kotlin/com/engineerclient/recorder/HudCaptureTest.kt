package com.engineerclient.recorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HudCaptureTest {

    @Test
    fun rowDiffReportsEveryRowFirst() {
        val d = RowDiff()
        val r = d.diff(listOf("a", "b", "c"))
        assertEquals(listOf(0, 1, 2), r.changed)
        assertTrue(r.countChanged)
    }

    @Test
    fun rowDiffReportsOnlyChangedRows() {
        val d = RowDiff()
        d.diff(listOf("a", "b", "c"))
        val same = d.diff(listOf("a", "b", "c"))
        assertEquals(emptyList(), same.changed)
        assertFalse(same.countChanged)
        val one = d.diff(listOf("a", "x", "c"))
        assertEquals(listOf(1), one.changed)
        assertFalse(one.countChanged)
    }

    @Test
    fun rowDiffGrowAndShrink() {
        val d = RowDiff()
        d.diff(listOf("a", "b"))
        val grow = d.diff(listOf("a", "b", "c", "d"))
        assertEquals(listOf(2, 3), grow.changed)
        assertTrue(grow.countChanged)
        val shrink = d.diff(listOf("a"))
        assertEquals(emptyList(), shrink.changed)
        assertTrue(shrink.countChanged)
    }

    @Test
    fun rowDiffResetStartsOver() {
        val d = RowDiff()
        d.diff(listOf("a", "b"))
        d.reset()
        assertEquals(listOf(0, 1), d.diff(listOf("a", "b")).changed)
    }

    @Test
    fun repeatCounterCollapsesRepeats() {
        val c = RepeatCounter<String>()
        assertEquals(0, c.offer("hp 100"))
        assertNull(c.offer("hp 100"))
        assertNull(c.offer("hp 100"))
        assertEquals(2, c.offer("hp 90"))
        assertEquals(0, c.offer("hp 100"))
    }

    @Test
    fun repeatCounterResetForgetsLast() {
        val c = RepeatCounter<String>()
        c.offer("x"); c.offer("x")
        c.reset()
        assertEquals(0, c.offer("x"))
    }
}
