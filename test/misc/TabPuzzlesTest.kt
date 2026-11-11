package com.engineerclient.misc

import kotlin.test.Test
import kotlin.test.assertEquals

class TabPuzzlesTest {
    private val tab = listOf(
        "Party (5)", " Builder [M]",
        "Puzzles: (4)",
        " ???: [✦]",
        " Ice Fill: [✔] (Builder)",
        " ???: [✦]",
        " Quiz: [✖]",
        "Account Info",
    )

    @Test fun everyLineKeepsItsPlace() {
        val s = TabPuzzles.parse(tab)
        assertEquals(4, s.count)
        assertEquals(listOf(null, "Ice Fill", null, "Quiz"), s.slots.map { it.name })
        assertEquals(listOf(TabPuzzles.Status.INCOMPLETE, TabPuzzles.Status.COMPLETED, TabPuzzles.Status.INCOMPLETE, TabPuzzles.Status.FAILED), s.slots.map { it.status })
    }

    @Test fun overlayFollowsTheTabNotTheOrderFound() {
        // The first "???" is found after Ice Fill: it stays on top, where the tab list has it.
        val later = TabPuzzles.parse(tab.map { if (it == " ???: [✦]") " Blaze: [✦]" else it })
        assertEquals(
            listOf("Puzzles(4):" to null, "Blaze" to false, "Ice Fill" to true, "Blaze" to false, "Quiz" to false),
            TabPuzzles.overlayLines(later),
        )
    }

    @Test fun linesNotInTheTabYetAreQuestionMarks() {
        val s = TabPuzzles.Section(3, listOf(TabPuzzles.Slot("Quiz", TabPuzzles.Status.COMPLETED)))
        assertEquals(listOf("Puzzles(3):" to null, "Quiz" to true, "???" to false, "???" to false), TabPuzzles.overlayLines(s))
    }
}
