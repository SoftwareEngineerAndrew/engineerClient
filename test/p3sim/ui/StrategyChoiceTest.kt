package com.engineerclient.p3sim.clear.ui

import com.engineerclient.p3sim.clear.bots.NeutralStrategy
import com.engineerclient.p3sim.run.LayoutSource
import com.engineerclient.p3sim.run.plugin.BloodAssignment
import com.engineerclient.p3sim.run.plugin.PartyStrategy
import com.engineerclient.p3sim.run.plugin.StrategyContext
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StrategyChoiceTest {
    private class Preset(override val id: String, override val label: String, override val description: String = "") : PartyStrategy {
        override fun bloodAssignment(ctx: StrategyContext): BloodAssignment = BloodAssignment.NOBODY
    }

    private val none: List<PartyStrategy> = listOf(NeutralStrategy)
    private val first = Preset("test-first", "First preset", "The first one.")
    private val second = Preset("test-second", "Second preset")
    private val withPlugin: List<PartyStrategy> = listOf(NeutralStrategy, first, second)

    @Test fun autoIsTheFirstPresetOfAPluginAndNeutralWithoutOne() {
        assertEquals("test-first", StrategyChoice.resolve("auto", withPlugin))
        assertNull(StrategyChoice.resolve("auto", none))
        assertEquals("test-first", StrategyChoice.resolve(null, withPlugin))
    }

    @Test fun neutralStaysSelectableAndANamedPresetIsKept() {
        assertNull(StrategyChoice.resolve("neutral", withPlugin))
        assertEquals("test-second", StrategyChoice.resolve("test-second", withPlugin))
    }

    @Test fun aSavedPresetThatNoPluginOffersAnyMoreCountsAsAuto() {
        assertEquals("test-first", StrategyChoice.resolve("gone", withPlugin))
        assertNull(StrategyChoice.resolve("gone", none))
        assertEquals("neutral", StrategyChoice.next("gone", none)) // as if it were auto, the first stop of the cycle
    }

    @Test fun wordsAreMatchedByIdOrLabelIgnoringCase() {
        assertEquals("test-first", StrategyChoice.normalize("TEST-FIRST", withPlugin))
        assertEquals("test-second", StrategyChoice.normalize("second preset", withPlugin))
        assertEquals("auto", StrategyChoice.normalize(" Auto ", withPlugin))
        assertEquals("neutral", StrategyChoice.normalize("NEUTRAL", none))
        assertNull(StrategyChoice.normalize("nope", withPlugin))
        assertNull(StrategyChoice.normalize("", withPlugin)); assertNull(StrategyChoice.normalize(null, withPlugin))
    }

    @Test fun theButtonCyclesThroughEveryChoiceAndBackToAuto() {
        assertEquals(listOf("auto", "neutral", "test-first", "test-second"), StrategyChoice.choices(withPlugin))
        var c = StrategyChoice.AUTO
        val seen = ArrayList<String>()
        repeat(5) { c = StrategyChoice.next(c, withPlugin); seen += c }
        assertEquals(listOf("neutral", "test-first", "test-second", "auto", "neutral"), seen)
    }

    @Test fun thereIsOnlyAChoiceWhenAPluginOffersAPreset() {
        assertFalse(StrategyChoice.hasPresets(none))
        assertTrue(StrategyChoice.hasPresets(withPlugin))
    }

    @Test fun labelsAndTooltipsSayWhatEachChoiceDoes() {
        assertEquals("Auto: First preset", StrategyChoice.label("auto", withPlugin))
        assertEquals("Auto: Neutral", StrategyChoice.label("auto", none))
        assertEquals("Neutral", StrategyChoice.label("neutral", withPlugin))
        assertEquals("Second preset", StrategyChoice.label("test-second", withPlugin))
        assertTrue(StrategyChoice.about("test-first", withPlugin).startsWith("The first one."))
        assertTrue(StrategyChoice.about("auto", withPlugin).contains("First preset"))
        assertTrue(StrategyChoice.about("test-second", withPlugin).isNotBlank())
    }

    @Test fun theDefaultsMakeTheConfigStrategy() {
        // The default run settings pick auto, so a plug-in's first preset applies without anyone choosing it.
        assertEquals("test-first", RunDefaults().config(LayoutSource.Random, 1, withPlugin).strategy)
        assertNull(RunDefaults().config(LayoutSource.Random, 1, none).strategy)
        assertNull(RunDefaults(strategy = "neutral").config(LayoutSource.Random, 1, withPlugin).strategy)
        assertEquals("test-second", RunDefaults(strategy = "test-second").config(LayoutSource.Random, 1, withPlugin).strategy)
        assertEquals(StrategyChoice.AUTO, RunDefaults().strategy)
    }
}
