package com.engineerclient.p3sim.run

import com.engineerclient.p3sim.clear.bots.BotsModule
import com.engineerclient.p3sim.clear.damage.DamageModule
import com.engineerclient.p3sim.clear.mobs.MobsModule
import com.engineerclient.p3sim.run.api.HudRegistry
import com.engineerclient.p3sim.run.api.Services
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The fixed module list and the fixed service wiring: nobody edits them, so they must stay exactly as documented. */
class ModulesTest {
    private fun same(a: Any?, b: Any?) = assertTrue(a === b, "$a is not $b")

    @Test fun moduleOrderIsTheDocumentedOne() {
        assertEquals(
            listOf("engine", "layout", "doors", "mobs", "damage", "secrets", "score", "puzzles", "hardpuzzles", "traps", "bots", "blood", "hud", "ui"),
            AdsModules.all.map { it.id },
        )
        assertEquals(AdsModules.all.size, AdsModules.all.map { it.id }.toSet().size, "ids are unique")
        assertTrue(AdsModules.all.all { it.owner.isNotBlank() })
        assertEquals(setOf("engine", "layout", "doors", "mobs", "damage", "secrets", "puzzles", "traps", "bots", "blood", "hud", "savepoints"), AdsModules.all.map { it.owner }.toSet())
    }

    @Test fun servicesPointAtTheirModules() {
        same(MobsModule, Services.mobs)
        same(DamageModule, Services.damage)
        same(BotsModule, Services.bots)
        same(AdsModules.byId("traps"), Services.traps)
        same(AdsModules.byId("hud"), Services.channels)
        same(AdsModules.byId("doors"), Services.doors)
        same(AdsModules.byId("secrets"), Services.secrets)
        same(AdsModules.byId("score"), Services.score)
        same(AdsModules.byId("puzzles"), Services.puzzles)
        same(AdsModules.byId("layout"), Services.layout)
    }

    @Test fun unfilledModulesAnswerWithHarmlessDefaults() {
        val run = Run("r", RunConfig(seed = 1), TestPlans.plan(), TestPlans.emptyPacks(), systems = emptyList())
        assertEquals(0, Services.mobs.requiredAlive(0))
        assertTrue(Services.mobs.inRoom(0).isEmpty())
        assertEquals(100.0, Services.damage.hpPercent(run, java.util.UUID(0, 1)))
        assertEquals(null, Services.secrets.breakerRefusal(run, 0, 0, 0))
        assertEquals(false, Services.traps.abilitiesDisabled(run, java.util.UUID(0, 1)))
        assertEquals(0, Services.score.endScreen(run).score)
        assertTrue(Services.bots.botIds(run).isEmpty())
        assertTrue(Services.doors.keyPlan(run.plan).witherRooms.isEmpty())
    }

    @Test fun commandRegistryHasTheBuiltinsAndNoDuplicates() {
        val names = AdsCommands.dev.map { it.name }
        assertTrue("packs" in names && "events" in names, names.toString())
        assertEquals(names.size, names.toSet().size)
        assertTrue(AdsCommands.dev.all { it.usage.startsWith("/ads dev ") }, "usage lines read as commands")
    }

    @Test fun noTwoModulesShareACommandName() {
        // The registry keeps the first of two equal names and drops the rest, so a clash would silently hide a module's command.
        for ((label, pick) in listOf<Pair<String, (AdsModule) -> List<AdsCommand>>>("dev" to { it.devCommands() }, "run" to { it.runCommands() })) {
            val all = AdsModules.all.flatMap { m -> pick(m).map { m.id to it } }
            val dupes = all.groupBy { it.second.name }.filter { it.value.size > 1 }.map { (n, l) -> "$n in ${l.joinToString { it.first }}" }
            assertTrue(dupes.isEmpty(), "two modules define the same /ads $label command: $dupes")
        }
    }

    @Test fun everyCommandUsageNamesItsOwnCommand() {
        for ((label, list) in listOf("dev" to AdsCommands.dev, "run" to AdsCommands.run)) for (c in list) assertTrue(c.usage.startsWith("/ads $label ${c.name}"), "${c.name}: ${c.usage}")
    }

    @Test fun hudRegistryStartsEmpty() {
        assertTrue(HudRegistry.sidebar.isEmpty() && HudRegistry.tab.isEmpty() && HudRegistry.map.isEmpty())
    }
}
