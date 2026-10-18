package com.engineerclient.p3sim.run

import com.engineerclient.p3sim.run.api.MobSpec
import com.engineerclient.p3sim.run.plugin.AdsPlugin
import com.engineerclient.p3sim.run.plugin.AdsPlugins
import com.engineerclient.p3sim.run.plugin.BloodAssignment
import com.engineerclient.p3sim.run.plugin.BloodCamp
import com.engineerclient.p3sim.run.plugin.BloodCampFactory
import com.engineerclient.p3sim.run.plugin.BloodCampHost
import com.engineerclient.p3sim.run.plugin.BloodRoomSpec
import com.engineerclient.p3sim.run.plugin.HeadSlot
import com.engineerclient.p3sim.run.plugin.PartyStrategy
import com.engineerclient.p3sim.run.plugin.StrategyContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The plug-in seam's selection rules, with invented plug-ins: everything optional, a broken plug-in never breaks the game. */
class PluginSeamTest {
    @AfterEach fun cleanup() = AdsPlugins.installForTest(emptyList())

    private val room = object : BloodRoomSpec {
        override val roomIndex = 3; override val designId = "Blood_A"; override val buildKey: String? = null
        override val doorX = 0.0; override val doorY = 70.0; override val doorZ = 0.0
        override val watcherX = 1.0; override val watcherY = 70.0; override val watcherZ = 1.0
        override val heads: List<HeadSlot> = emptyList(); override val mobCells: List<DoubleArray> = emptyList()
    }

    private class NoCamp : BloodCamp { override fun start() {}; override fun tick() {}; override val finished = true }

    private fun factory(supports: Boolean) = object : BloodCampFactory {
        override fun supports(room: BloodRoomSpec) = supports
        override fun create(host: BloodCampHost): BloodCamp = NoCamp()
    }

    private fun plugin(pid: String, camp: BloodCampFactory? = null, strategies: List<PartyStrategy> = emptyList(), version: Int = 1) = object : AdsPlugin {
        override val id = pid
        override val apiVersion = version
        override fun bloodCamp() = camp
        override fun partyStrategies() = strategies
    }

    @Test fun noPluginsMeansNeutralDefaults() {
        AdsPlugins.installForTest(emptyList())
        assertNull(AdsPlugins.bloodCamp(room))
        assertEquals(emptyList(), AdsPlugins.strategies())
        assertNull(AdsPlugins.strategy("anything"))
    }

    @Test fun firstSupportingFactoryWins() {
        val yes = factory(true)
        AdsPlugins.installForTest(listOf(plugin("none"), plugin("no", factory(false)), plugin("yes", yes), plugin("later", factory(true))))
        assertSame(yes, AdsPlugins.bloodCamp(room))
    }

    @Test fun aFailingPluginIsSkipped() {
        val good = factory(true)
        val broken = object : AdsPlugin {
            override val id = "broken"
            override fun bloodCamp(): BloodCampFactory = error("boom")
            override fun partyStrategies(): List<PartyStrategy> = error("boom")
        }
        AdsPlugins.installForTest(listOf(broken, plugin("good", good)))
        assertSame(good, AdsPlugins.bloodCamp(room))
        assertEquals(emptyList(), AdsPlugins.strategies())
    }

    @Test fun strategiesAreCollectedAndFoundById() {
        val s = object : PartyStrategy {
            override val id = "preset-1"; override val label = "One"
            override fun bloodAssignment(ctx: StrategyContext) = BloodAssignment(seat = 2, arriveAtT = 600)
        }
        AdsPlugins.installForTest(listOf(plugin("p", strategies = listOf(s))))
        assertSame(s, AdsPlugins.strategy("preset-1"))
        assertEquals(1, AdsPlugins.strategies().size)
        assertNull(BloodAssignment.NOBODY.seat)
    }

    @Test fun mobSpecDefaults() {
        val m = MobSpec("crypt_x", 1.0, 70.0, 2.0)
        assertEquals(null, m.hp); assertEquals(false, m.required)
    }
}
