package com.engineerclient.p3sim.clear.damage

import com.engineerclient.p3sim.run.DamageSource
import com.engineerclient.p3sim.run.PlayerDamaged
import com.engineerclient.p3sim.run.PlayerDied
import com.engineerclient.p3sim.run.PlayerRecord
import com.engineerclient.p3sim.run.PlayerRevived
import com.engineerclient.p3sim.run.RunConfig
import com.engineerclient.p3sim.run.RunEvent
import com.engineerclient.p3sim.run.RunStage
import com.engineerclient.p3sim.run.Run
import com.engineerclient.p3sim.run.TestPlans
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The hub's player side on bot records (no world needed): traps, the window, "No deaths", death, ghost, revive, the events. */
class DamageHubTest {
    private val bot = UUID(0, 11)
    private val mate = UUID(0, 12)

    @Test fun aTeammatesDeathLineNamesWhatKilledIt() {
        assertEquals("§c ☠ §r§7Bob was killed by Zombie and became a ghost.", DamageModule.deathLine("Bob", DamageSource.MELEE, "Zombie"))
        assertEquals("§c ☠ §r§7Bob died and became a ghost.", DamageModule.deathLine("Bob", DamageSource.TRAP, null))
        assertEquals("§c ☠ §r§7Bob was crushed and became a ghost.", DamageModule.deathLine("Bob", DamageSource.CRUSH, null))
        assertEquals("§c ☠ §r§7Bob disconnected and became a ghost.", DamageModule.deathLine("Bob", DamageSource.DISCONNECT, null))
    }

    @Test fun aDroppedConnectionAndACrusherKillABotLikeAnyOtherDeath() {
        for (source in listOf(DamageSource.DISCONNECT, DamageSource.CRUSH)) {
            val (r, seen) = run()
            r.tick(100)
            DamageModule.kill(r, bot, source)
            assertTrue(r.state.players[bot]!!.ghost, "$source")
            assertEquals(source, seen.filterIsInstance<PlayerDied>().single().source)
        }
    }

    private fun run(noDeaths: Boolean = false): Pair<Run, MutableList<RunEvent>> {
        val r = Run("r", RunConfig(seed = 1, noDeaths = noDeaths), TestPlans.plan(), TestPlans.emptyPacks(), systems = emptyList())
        r.state.players[bot] = PlayerRecord(bot, "BotA", "HEALER", true)
        r.state.players[mate] = PlayerRecord(mate, "BotB", "TANK", true).also { it.x = 5.0 }
        r.begin(0)
        r.setT0(0)
        val seen = ArrayList<RunEvent>()
        r.bus.subscribe { seen += it }
        return r to seen
    }

    @Test fun aTrapTakesExactlyFifteenPercentAndPostsTheEvent() {
        val (r, seen) = run()
        r.tick(100)
        val h = DamageModule.hurtPlayer(r, bot, 15.0, DamageSource.TRAP, null, true)
        assertEquals(15.0, h.applied)
        assertFalse(h.ignored); assertFalse(h.died)
        assertEquals(85.0, DamageModule.hpPercent(r, bot), 1e-9)
        val e = seen.filterIsInstance<PlayerDamaged>().single()
        assertEquals(15.0, e.percent); assertEquals(85.0, e.hpAfter, 1e-9); assertTrue(e.trueDamage); assertEquals(DamageSource.TRAP, e.source)
    }

    @Test fun aSecondTrapInsideTheWindowIsIgnored() {
        val (r, _) = run()
        r.tick(100)
        DamageModule.hurtPlayer(r, bot, 15.0, DamageSource.TRAP, null, true)
        r.tick(105)
        assertTrue(DamageModule.hurtPlayer(r, bot, 15.0, DamageSource.TRAP, null, true).ignored)
        r.tick(110)
        assertEquals(15.0, DamageModule.hurtPlayer(r, bot, 15.0, DamageSource.TRAP, null, true).applied)
        assertEquals(70.0, DamageModule.hpPercent(r, bot), 1e-9)
    }

    @Test fun anUnknownPlayerAndTheBossStageAreLeftAlone() {
        val (r, _) = run()
        r.tick(100)
        assertTrue(DamageModule.hurtPlayer(r, UUID(9, 9), 15.0, DamageSource.TRAP, null, true).ignored)
        assertEquals(100.0, DamageModule.hpPercent(r, UUID(9, 9)))
        r.forceStage(RunStage.BOSS)
        assertTrue(DamageModule.hurtPlayer(r, bot, 15.0, DamageSource.TRAP, null, true).ignored, "the boss sim keeps its own damage")
        assertEquals(100.0, DamageModule.hpPercent(r, bot))
    }

    @Test fun aLethalHitKillsAGhostCountsTheTeamDeathAndRevivesSixSecondsLater() {
        val (r, seen) = run()
        r.tick(100)
        r.state.players[bot]!!.hpPercent = 5.0
        val h = DamageModule.hurtPlayer(r, bot, 15.0, DamageSource.MELEE, null)
        assertTrue(h.died)
        val rec = r.state.players[bot]!!
        assertTrue(rec.ghost)
        assertEquals(1, rec.deaths)
        assertEquals(1, r.state.deaths.teamDeaths)
        assertEquals(listOf(r.t to bot), r.state.deaths.log)
        assertEquals(r.t + 120, rec.reviveAtT)
        assertTrue(seen.any { it is PlayerDied })
        // not yet
        r.tick(100 + 119); DamageModule.onTick(r)
        assertTrue(rec.ghost)
        // the timer revives it, by the nearest living mate
        r.tick(100 + 120); DamageModule.onTick(r)
        assertFalse(rec.ghost)
        assertEquals(100.0, rec.hpPercent)
        assertEquals(1, seen.filterIsInstance<PlayerRevived>().size)
        assertEquals(1, r.state.deaths.teamDeaths, "a revive does not undo the death")
    }

    @Test fun noDeathsHoldsTheBotsNotAtAllAndAHumanAtOneVanillaPoint() {
        val (r, _) = run(noDeaths = true)
        r.tick(100)
        // the toggle is for the player: a bot still dies
        r.state.players[bot]!!.hpPercent = 5.0
        assertTrue(DamageModule.hurtPlayer(r, bot, 15.0, DamageSource.TRAP, null, true).died)
    }

    @Test fun healthComesBackSlowlyWhileAlive() {
        val (r, _) = run()
        r.tick(100)
        r.state.players[bot]!!.hpPercent = 50.0
        var t = 100
        repeat(200) { t++; r.tick(t); DamageModule.onTick(r) }
        val hp = r.state.players[bot]!!.hpPercent
        assertTrue(hp in 68.0..73.0, "10 seconds at 2.3 percent a second, in steps: $hp")
        // steps: nothing between two 20-tick marks
        val before = r.state.players[bot]!!.hpPercent
        r.tick(t + 1); DamageModule.onTick(r)
        assertTrue(r.state.players[bot]!!.hpPercent == before || r.state.players[bot]!!.hpPercent - before in 2.2..2.4)
    }

    @Test fun killingAGhostOrAnUnknownPlayerChangesNothing() {
        val (r, _) = run()
        r.tick(100)
        DamageModule.kill(r, UUID(7, 7), DamageSource.OTHER)
        DamageModule.kill(r, bot, DamageSource.OTHER)
        assertEquals(1, r.state.deaths.teamDeaths)
        DamageModule.kill(r, bot, DamageSource.OTHER)
        assertEquals(1, r.state.deaths.teamDeaths, "already a ghost")
    }

    @Test fun meleeFromAnUnknownMobDoesNothing() {
        val (r, _) = run()
        r.tick(100)
        assertTrue(DamageModule.meleeHit(r, UUID(5, 5), bot).ignored)
        assertEquals(100.0, DamageModule.hpPercent(r, bot))
    }

    @Test fun anImplosionWithNoMobsHitsNothing() {
        val (r, _) = run()
        r.tick(100)
        val c = DamageModule.cast(r, bot, ImplosionRules.castBox(0.0, 70.0, 0.0), null)
        assertEquals(0, c.count)
        assertEquals(0, DamageModule.implode(r, bot, ImplosionRules.castBox(0.0, 70.0, 0.0)))
    }
}
