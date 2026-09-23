package com.engineerclient.misc

import com.engineerclient.misc.AgroSphere.Member
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgroSphereTest {

    @Test
    fun `someone else closest - sphere goes through them`() {
        val r = AgroSphere.radius(listOf(Member(true, 12.0), Member(false, 5.0), Member(false, 9.0)))!!
        assertEquals(5.0, r.radius); assertEquals(false, r.youHaveAggro)
    }

    @Test
    fun `you closest - sphere goes through the 2nd closest`() {
        val r = AgroSphere.radius(listOf(Member(false, 9.0), Member(true, 3.0), Member(false, 7.5)))!!
        assertEquals(7.5, r.radius); assertEquals(true, r.youHaveAggro)
    }

    @Test
    fun `alone or empty - nothing to draw`() {
        assertNull(AgroSphere.radius(listOf(Member(true, 3.0))))
        assertNull(AgroSphere.radius(emptyList()))
    }

    @Test
    fun `you are not in the list - closest teammate is used`() {
        assertEquals(4.0, AgroSphere.radius(listOf(Member(false, 6.0), Member(false, 4.0)))!!.radius)
    }
}
