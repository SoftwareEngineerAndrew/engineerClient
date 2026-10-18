package com.engineerclient.p3sim.run

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The module-order dispatch behind RunInput: first non-null answer wins, a throwing module is skipped. */
class RunInputTest {
    private class M(override val id: String, val log: MutableList<String>, val answer: String?, val boom: Boolean = false) : AdsModule {
        override val owner = id
        fun call(): String? { log += id; if (boom) error("boom"); return answer }
    }

    @Test fun theFirstAnswerWinsAndLaterModulesAreNotAsked() {
        val log = ArrayList<String>()
        val ms = listOf(M("a", log, null), M("b", log, "B"), M("c", log, "C"))
        assertEquals("B", RunInput.ask(ms, "x") { (it as M).call() })
        assertEquals(listOf("a", "b"), log)
    }

    @Test fun aThrowingModuleHasNoOpinion() {
        val log = ArrayList<String>()
        val ms = listOf(M("a", log, "A", boom = true), M("b", log, "B"))
        assertEquals("B", RunInput.ask(ms, "x") { (it as M).call() })
        assertEquals(listOf("a", "b"), log)
    }

    @Test fun nobodyAnsweringIsNull() {
        val log = ArrayList<String>()
        assertNull(RunInput.ask(listOf(M("a", log, null), M("b", log, null)), "x") { (it as M).call() })
        assertEquals(listOf("a", "b"), log)
    }
}
