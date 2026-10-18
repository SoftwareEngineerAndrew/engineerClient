package com.engineerclient.p3sim.clear.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UiModuleTest {
    @Test fun theRunCommandsAreTheDocumentedOnes() {
        val cmds = UiModule.runCommands()
        assertEquals(listOf("start", "random", "seed", "layout", "restart", "savepoint", "savepoints", "restore"), cmds.map { it.name })
        assertEquals(cmds.size, cmds.map { it.name }.toSet().size)
        for (c in cmds) assertTrue(c.usage.startsWith("/ads run ${c.name}"), c.usage)
    }

    @Test fun startingNeedsNoSimButRestartingDoes() {
        val byName = UiModule.runCommands().associateBy { it.name }
        for (n in listOf("start", "random", "seed", "layout")) { assertTrue(!byName[n]!!.requiresSim && byName[n]!!.client != null && byName[n]!!.server == null, n) }
        for (n in listOf("restart", "savepoint", "savepoints", "restore")) { assertTrue(byName[n]!!.requiresSim && byName[n]!!.server != null, n) }
    }

    @Test fun seedsAreShortAndPositive() {
        repeat(200) { val s = UiModule.drawSeed(); assertTrue(s in 0..999_999_999L) }
    }

    @Test fun settingsFallBackToPlainDefaultsBeforeTheyExist() {
        assertEquals(RunDefaults(), UiSettings.defaults())
    }
}
