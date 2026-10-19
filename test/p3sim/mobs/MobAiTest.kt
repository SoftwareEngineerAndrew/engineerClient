package com.engineerclient.p3sim.mobs

import com.engineerclient.p3sim.clear.mobs.AiAction
import com.engineerclient.p3sim.clear.mobs.AiKind
import com.engineerclient.p3sim.clear.mobs.AiParams
import com.engineerclient.p3sim.clear.mobs.AiState
import com.engineerclient.p3sim.clear.mobs.AiTarget
import com.engineerclient.p3sim.clear.mobs.MobAi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MobAiTest {
    private val p = AiParams()
    private fun tgt(d: Double) = AiTarget(d, 0.0, d)

    @Test fun aStaticMobStandsStillAndOnlyHitsWhatIsAdjacent() {
        val s = AiState()
        assertEquals(AiAction.Idle, MobAi.step(AiKind.MELEE, false, 0.0, 0.0, 0.3, tgt(5.0), s, null, p))
        assertEquals(AiAction.Idle, MobAi.step(AiKind.MELEE, false, 0.0, 0.0, 0.3, null, s, null, p))
        assertEquals(AiAction.Idle, MobAi.step(AiKind.MELEE, false, 0.0, 0.0, 0.3, tgt(2.0), s, null, p))
        assertEquals(AiAction.Melee, MobAi.step(AiKind.MELEE, false, 0.0, 0.0, 0.3, tgt(1.2), s, null, p))
        // The swing starts a cooldown.
        assertEquals(AiAction.Idle, MobAi.step(AiKind.MELEE, false, 0.0, 0.0, 0.3, tgt(1.2), s, null, p))
    }

    @Test fun staticReachZeroTurnsStaticAttacksOff() {
        assertEquals(AiAction.Idle, MobAi.step(AiKind.MELEE, false, 0.0, 0.0, 0.3, tgt(1.0), AiState(), null, p.copy(staticReach = 0.0)))
    }

    @Test fun aMoverChasesWhatItSeesInsideTheFollowRangeAndGivesUpPastIt() {
        val s = AiState()
        assertEquals(AiAction.Idle, MobAi.step(AiKind.MELEE, true, 0.0, 0.0, 0.3, tgt(40.0), s, null, p))
        // Unseen, or not looking this tick: no chase.
        assertEquals(AiAction.Idle, MobAi.step(AiKind.MELEE, true, 0.0, 0.0, 0.3, AiTarget(8.0, 0.0, 8.0, seen = false), s, null, p))
        assertEquals(AiAction.Idle, MobAi.step(AiKind.MELEE, true, 0.0, 0.0, 0.3, AiTarget(8.0, 0.0, 8.0), s, null, p, search = false))
        val a = MobAi.step(AiKind.MELEE, true, 0.0, 0.0, 0.3, AiTarget(8.0, 0.0, 8.0), s, null, p)
        assertIs<AiAction.MoveTo>(a); assertEquals(8.0, a.x); assertEquals(0.3, a.speed)
        // Still chasing at 30 (inside the follow range), done at 40.
        assertIs<AiAction.MoveTo>(MobAi.step(AiKind.MELEE, true, 0.0, 0.0, 0.3, AiTarget(30.0, 0.0, 30.0), s, null, p))
        assertEquals(AiAction.Idle, MobAi.step(AiKind.MELEE, true, 0.0, 0.0, 0.3, tgt(40.0), s, null, p))
    }

    @Test fun aChaserForgetsATargetUnseenForThreeSeconds() {
        val s = AiState()
        assertIs<AiAction.MoveTo>(MobAi.step(AiKind.MELEE, true, 0.0, 0.0, 0.3, tgt(10.0), s, null, p))
        repeat(60) { assertIs<AiAction.MoveTo>(MobAi.step(AiKind.MELEE, true, 0.0, 0.0, 0.3, AiTarget(10.0, 0.0, 10.0, seen = false), s, null, p, search = false)) }
        assertEquals(AiAction.Idle, MobAi.step(AiKind.MELEE, true, 0.0, 0.0, 0.3, AiTarget(10.0, 0.0, 10.0, seen = false), s, null, p, search = false))
    }

    @Test fun meleeSwingsOnACadence() {
        val s = AiState()
        var swings = 0
        repeat(100) { if (MobAi.step(AiKind.MELEE, true, 0.0, 0.0, 0.3, tgt(1.2), s, null, p) == AiAction.Melee) swings++ }
        assertEquals(5, swings)   // 100 ticks / 20
    }

    @Test fun archersStopAtRangeAndShoot() {
        val s = AiState()
        assertIs<AiAction.MoveTo>(MobAi.step(AiKind.RANGED, true, 0.0, 0.0, 0.27, tgt(11.9).let { AiTarget(11.9, 0.0, 11.9) }, s, null, p.copy(rangedRange = 8.0)))
        // In range: it draws, and the first shot comes one interval later (1.8: 20 + 40 x 10 / 15 = 46 ticks), then every interval.
        val s2 = AiState()
        val shots = ArrayList<Int>()
        for (t in 0 until 200) if (MobAi.step(AiKind.RANGED, true, 0.0, 0.0, 0.27, tgt(10.0), s2, null, p) == AiAction.Shoot) shots += t
        assertEquals(46, MobAi.shotInterval(10.0, p))
        assertEquals(listOf(46, 92, 138, 184), shots)
        // A shooter that never walks still shoots what it sees, and nothing it does not.
        val s3 = AiState()
        var n = 0
        repeat(100) { if (MobAi.step(AiKind.RANGED, false, 0.0, 0.0, 0.0, AiTarget(5.0, 0.0, 5.0, seen = it % 2 == 0 && false), s3, null, p) == AiAction.Shoot) n++ }
        assertEquals(0, n)
        repeat(100) { if (MobAi.step(AiKind.RANGED, false, 0.0, 0.0, 0.0, tgt(5.0), s3, null, p) == AiAction.Shoot) n++ }
        assertTrue(n >= 2)
    }

    @Test fun theAssassinAppearsBehindItsTarget() {
        val s = AiState()
        var teleport: AiAction.Teleport? = null
        repeat(120) { val a = MobAi.step(AiKind.TELEPORT, true, 0.0, 0.0, 0.3, AiTarget(10.0, 0.0, 10.0), s, null, p.copy(assassinTeleportEvery = 100)); if (a is AiAction.Teleport && teleport == null) teleport = a }
        val t = teleport!!
        assertTrue(t.x > 10.0, "behind = past the target, away from the mob")
    }

    @Test fun patrolWalksTheLoopAndWrapsAround() {
        val s = AiState()
        val loop = listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(10.0, 0.0), doubleArrayOf(10.0, 10.0))
        var a = MobAi.step(AiKind.MELEE, true, 0.2, 0.0, 0.3, null, s, loop, p)
        assertIs<AiAction.MoveTo>(a); assertEquals(10.0, a.x)   // point 0 reached: on to point 1
        s.loopIndex = 2
        a = MobAi.step(AiKind.MELEE, true, 10.0, 9.5, 0.3, null, s, loop, p)
        assertIs<AiAction.MoveTo>(a)   // at point 2 (10,10): next is point 0
        assertEquals(0.0, a.x)
    }

    @Test fun kindsAndSpeedsFollowTheBody() {
        assertEquals(AiKind.MELEE, MobAi.kindOf("shadow_assassin", "minecraft:player", "teleport"))
        assertEquals(AiKind.TELEPORT, MobAi.kindOf("shadow_assassin", "minecraft:player", "teleport", teleportOn = true))
        assertEquals(AiKind.RANGED, MobAi.kindOf("super_archer", "minecraft:skeleton", "static"))
        assertEquals(AiKind.RANGED, MobAi.kindOf("withermancer", "minecraft:wither_skeleton", "static"))
        assertEquals(AiKind.MELEE, MobAi.kindOf("zombie_knight", "minecraft:zombie", "static"))
        assertEquals(0.27, MobAi.speedOf("minecraft:wither_skeleton", p))
        // Each archetype has its own measured speed; a Speedy one is faster; an unknown one takes its body's.
        assertEquals(0.137, MobAi.speedFor("skeleton_master", "minecraft:skeleton", false, p))
        assertEquals(0.278 * 1.5, MobAi.speedFor("zombie_soldier", "minecraft:zombie", true, p), 1e-9)
        assertEquals(0.28, MobAi.speedFor("unknown_zombie", "minecraft:zombie", false, p))
    }

    @Test fun theAssassinWalksByDefault() {
        val s = AiState()
        repeat(300) { assertTrue(MobAi.step(AiKind.MELEE, true, 0.0, 0.0, 0.3, AiTarget(10.0, 0.0, 10.0), s, null, p) !is AiAction.Teleport) }
        assertEquals(0, p.assassinTeleportEvery)
    }
}
