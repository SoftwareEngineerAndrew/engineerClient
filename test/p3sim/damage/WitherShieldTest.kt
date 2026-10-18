package com.engineerclient.p3sim.clear.damage

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WitherShieldTest {
    @Test fun aShieldLastsAHundredTicksAndShowsSixteen() {
        val s = WitherShield(50.0)
        assertEquals(0.0, s.absorption(0))
        assertTrue(s.start(10))
        assertEquals(16.0, s.absorption(10))
        assertTrue(s.active(109))
        assertFalse(s.active(110))
        assertEquals(0.0, s.absorption(110))
    }

    @Test fun noNewShieldWhileOneIsUp() {
        val s = WitherShield(50.0)
        s.start(0)
        assertFalse(s.start(50))
        assertTrue(s.start(100))
    }

    @Test fun hitsLowerTheBarBeforeHealthIsTouched() {
        val s = WitherShield(40.0)
        s.start(0)
        assertEquals(0.0, s.absorb(5, 10.0))
        assertEquals(12.0, s.absorption(5))
        // 30 left in the pool: a 45 hit overflows by 15
        assertEquals(15.0, s.absorb(6, 45.0))
        assertFalse(s.active(7))
        assertEquals(5.0, s.absorb(8, 5.0))
    }

    @Test fun withoutAShieldTheWholeHitGoesToHealth() {
        assertEquals(7.0, WitherShield(40.0).absorb(0, 7.0))
    }
}
