package com.engineerclient.p3sim.clear.ui

import com.engineerclient.p3sim.clear.bots.NeutralStrategy
import com.engineerclient.p3sim.run.LagPreset
import com.engineerclient.p3sim.run.LayoutSource
import com.engineerclient.p3sim.run.plugin.BloodAssignment
import com.engineerclient.p3sim.run.plugin.PartyStrategy
import com.engineerclient.p3sim.run.plugin.StrategyContext
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunArgsTest {
    private fun ok(mode: String, vararg args: String, defaults: RunDefaults = RunDefaults()): com.engineerclient.p3sim.run.RunConfig {
        val p = RunArgs.parse(mode, args.toList(), defaults, listOf(NeutralStrategy)) { 777L }
        assertIs<RunArgs.Parsed.Ok>(p, (p as? RunArgs.Parsed.Bad)?.message)
        return p.config
    }
    private fun bad(mode: String, vararg args: String): String {
        val p = RunArgs.parse(mode, args.toList(), RunDefaults(), listOf(NeutralStrategy)) { 777L }
        assertIs<RunArgs.Parsed.Bad>(p)
        return p.message
    }

    @Test fun randomDrawsASeedAndUsesTheDefaults() {
        val c = ok("random")
        assertEquals(LayoutSource.Random, c.layout)
        assertEquals(777L, c.seed)
        assertEquals(5, c.partySize); assertTrue(c.bots); assertEquals(LagPreset.OFF, c.lag)
        assertEquals(1.0, c.mobHpScale)
    }

    @Test fun seedPicksTheLayoutAndSeedsTheRun() {
        val c = ok("seed", "12345")
        assertEquals(LayoutSource.Seed(12345), c.layout)
        assertEquals(12345L, c.seed)
        assertTrue(bad("seed").contains("number"))
        assertTrue(bad("seed", "abc").contains("number"))
    }

    @Test fun layoutTakesAnId() {
        val c = ok("layout", "2000-01-01")
        assertEquals(LayoutSource.Recorded("2000-01-01"), c.layout)
        assertEquals(777L, c.seed)
        assertTrue(bad("layout").contains("id"))
    }

    @Test fun optionsOverrideTheDefaultsInAnyOrder() {
        val c = ok("random", "lag", "bad", "party", "3", "nodeaths", "hp", "pack")
        assertEquals(LagPreset.BAD, c.lag); assertEquals(3, c.partySize); assertTrue(c.noDeaths); assertNull(c.mobHpScale)
        val d = ok("seed", "9", "nodeaths", "off", "bots", "off", defaults = RunDefaults(noDeaths = true))
        assertEquals(false, d.noDeaths); assertEquals(false, d.bots); assertEquals(1, d.partySize)
    }

    @Test fun aPartyOfOneIsNoBotsAndABiggerOneTurnsThemOn() {
        val alone = ok("random", "party", "1")
        assertEquals(false, alone.bots); assertEquals(1, alone.partySize)
        val three = ok("random", "party", "3", defaults = RunDefaults(bots = false))
        assertEquals(true, three.bots); assertEquals(3, three.partySize)
        assertEquals(1, ok("random", "nobots").partySize)
    }

    @Test fun lagNamesIgnoreCaseAndSeparators() {
        assertEquals(LagPreset.HIGH_SLOT, RunArgs.lag("High slot"))
        assertEquals(LagPreset.HIGH_SLOT, RunArgs.lag("high_slot"))
        assertEquals(LagPreset.HIGH_SLOT, RunArgs.lag("HIGHSLOT"))
        assertEquals(LagPreset.TYPICAL, RunArgs.lag("typical"))
        assertNull(RunArgs.lag("awful")); assertNull(RunArgs.lag(null))
    }

    @Test fun mistakesAreSaidInPlainWords() {
        assertTrue(bad("random", "party", "9").contains("1 to 5"))
        assertTrue(bad("random", "lag").contains("lag"))
        assertTrue(bad("random", "banana").contains("banana"))
        assertTrue(bad("random", "bots", "maybe").contains("on or off"))
        assertTrue(bad("nonsense").isNotEmpty())
    }

    @Test fun defaultsWithBotsOffAreAPartyOfOne() {
        assertEquals(1, RunDefaults(bots = false, partySize = 4).config(LayoutSource.Random, 1, listOf(NeutralStrategy)).partySize)
    }

    @Test fun theDescriptionNamesLayoutPartyAndRules() {
        val s = RunArgs.describe(ok("seed", "42", "party", "1", "lag", "good", "nodeaths"))
        assertTrue(s.contains("seed 42") && s.contains("you alone") && s.contains("lag Good") && s.contains("no deaths"), s)
        assertTrue(RunArgs.describe(ok("random")).contains("random layout (seed 777)"))
    }

    private class Preset(override val id: String, override val label: String) : PartyStrategy {
        override fun bloodAssignment(ctx: StrategyContext): BloodAssignment = BloodAssignment.NOBODY
    }
    private val offered: List<PartyStrategy> = listOf(NeutralStrategy, Preset("test-first", "First preset"), Preset("test-steady", "Steady preset"))

    private fun okWith(mode: String, vararg args: String, defaults: RunDefaults = RunDefaults()): com.engineerclient.p3sim.run.RunConfig {
        val p = RunArgs.parse(mode, args.toList(), defaults, offered) { 777L }
        assertIs<RunArgs.Parsed.Ok>(p, (p as? RunArgs.Parsed.Bad)?.message)
        return p.config
    }

    @Test fun aPartyStrategyCanBeNamed() {
        assertEquals("test-steady", okWith("random", "strategy", "test-steady").strategy)
        assertEquals("test-steady", okWith("random", "strategy", "Steady preset").strategy)
        assertTrue(bad("random", "strategy").contains("strategy id"))
        assertTrue(RunArgs.describe(okWith("random", "strategy", "test-steady")).contains("party strategy test-steady"))
    }

    @Test fun withNoStrategyNamedTheDefaultIsAutoWhichIsTheFirstPluginPreset() {
        // A default run sends a bot to the blood room whenever a plug-in offers a preset; the player does not have to ask.
        assertEquals("test-first", okWith("random").strategy)
        assertTrue(RunArgs.describe(okWith("random")).contains("party strategy test-first"))
        assertEquals(null, ok("random").strategy) // no plug-in installed: the neutral strategy
    }

    @Test fun neutralAndAutoCanBeChosenByWord() {
        assertEquals(null, okWith("random", "strategy", "neutral").strategy)
        assertEquals("test-first", okWith("random", "strategy", "auto", defaults = RunDefaults(strategy = "neutral")).strategy)
        assertFalse(RunArgs.describe(okWith("random", "strategy", "neutral")).contains("party strategy"))
    }

    @Test fun theRunSettingsChoiceIsKeptUnlessTheCommandNamesAnother() {
        val saved = RunDefaults(strategy = "test-steady")
        assertEquals("test-steady", okWith("random", defaults = saved).strategy)
        assertEquals("test-first", okWith("random", "strategy", "test-first", defaults = saved).strategy)
    }

    @Test fun anUnknownStrategyIsRefusedAndTheChoicesAreListed() {
        val p = RunArgs.parse("random", listOf("strategy", "banana"), RunDefaults(), offered) { 777L }
        assertIs<RunArgs.Parsed.Bad>(p)
        assertTrue(p.message.contains("banana") && p.message.contains("auto") && p.message.contains("test-steady"), p.message)
    }
}
