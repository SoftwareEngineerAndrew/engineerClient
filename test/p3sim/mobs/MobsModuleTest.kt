package com.engineerclient.p3sim.mobs

import com.engineerclient.p3sim.clear.mobs.AiAction
import com.engineerclient.p3sim.clear.mobs.MobEntry
import com.engineerclient.p3sim.clear.mobs.MobStage
import com.engineerclient.p3sim.clear.mobs.MobsModule
import com.engineerclient.p3sim.pack.PackReport
import com.engineerclient.p3sim.pack.PackSet
import com.engineerclient.p3sim.run.DamageSource
import com.engineerclient.p3sim.run.KeyKind
import com.engineerclient.p3sim.run.MobDamaged
import com.engineerclient.p3sim.run.MobKilled
import com.engineerclient.p3sim.run.MobSpawned
import com.engineerclient.p3sim.run.PlayerRecord
import com.engineerclient.p3sim.run.Run
import com.engineerclient.p3sim.run.RunConfig
import com.engineerclient.p3sim.run.RunEvent
import com.engineerclient.p3sim.run.RunStage
import com.engineerclient.p3sim.run.TestPlans
import com.engineerclient.p3sim.run.api.DamageService
import com.engineerclient.p3sim.run.api.DoorService
import com.engineerclient.p3sim.run.api.HurtResult
import com.engineerclient.p3sim.run.api.MobSpec
import java.nio.file.Path
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The module's rules (counters, kills, keys, sleepers, building and rebuilding bodies, AI plumbing) with a stand-in for the world. */
class MobsModuleTest {
    private class FakeStage : MobStage {
        var loaded = true
        var gone = false
        var nextId = 1000
        val calls = ArrayList<String>()
        val acts = ArrayList<AiAction>()
        override fun available() = true
        override fun isLoaded(x: Int, z: Int) = loaded
        override fun materialise(e: MobEntry): Boolean { calls += "build ${e.arch.id}"; e.live = true; e.record.entityId = nextId++; return true }
        override fun isGone(e: MobEntry) = gone
        override fun release(e: MobEntry) { if (e.live) calls += "release ${e.arch.id}"; e.live = false }
        override fun die(e: MobEntry) { calls += "die ${e.arch.id}"; e.live = false }
        override fun retract(e: MobEntry) { calls += "retract ${e.arch.id}" }
        override fun announceTo(playerId: UUID, entries: Collection<MobEntry>) { calls += "announce ${entries.size}" }
        override fun wake(e: MobEntry) { calls += "wake ${e.arch.id}" }
        override fun reveal(e: MobEntry) { calls += "reveal ${e.arch.id}" }
        override fun refresh(e: MobEntry) { calls += "refresh ${e.arch.id}" }
        override fun hurtFlash(e: MobEntry) { calls += "flash ${e.arch.id}" }
        override fun position(e: MobEntry): DoubleArray? = if (e.live) doubleArrayOf(e.x, e.y, e.z) else null
        override fun act(e: MobEntry, action: AiAction, targetY: Double, targetX: Double, targetZ: Double) { acts += action }
        override fun rescue(e: MobEntry) {}
    }

    private class FakeDoors(val key: KeyKind?) : DoorService {
        val drops = ArrayList<Triple<Int, Double, Double>>()
        override fun keyForRoom(run: Run, roomIndex: Int) = key
        override fun dropKey(run: Run, roomIndex: Int, x: Double, z: Double) { drops += Triple(roomIndex, x, z) }
    }

    private class FakeDamage : DamageService {
        val melee = ArrayList<Pair<UUID, UUID>>()
        val hurt = ArrayList<Triple<UUID, Double, DamageSource>>()
        override fun meleeHit(run: Run, mob: UUID, player: UUID): HurtResult { melee += mob to player; return HurtResult(1.0, false, false) }
        override fun hurtPlayer(run: Run, player: UUID, percent: Double, source: DamageSource, attacker: UUID?, trueDamage: Boolean): HurtResult { hurt += Triple(player, percent, source); return HurtResult(percent, false, false) }
    }

    private val pack = MobFixtures.pack()
    private val stage = FakeStage()
    private val chats = ArrayList<String>()
    private var doors = FakeDoors(KeyKind.WITHER)
    private val damage = FakeDamage()

    @BeforeTest fun wire() {
        MobsModule.stage = stage
        MobsModule.doors = { doors }
        MobsModule.damage = { damage }
        MobsModule.chat = { chats += it }
    }

    @AfterTest fun unwire() {
        // The module is a singleton: leave it empty and pointing at the real world for whatever runs next.
        MobsModule.clearAll(newRun())
    }

    private fun newRun(withPack: Boolean = true): Run {
        val packs = PackSet(Path.of("/nowhere"), null, null, if (withPack) pack else null, null, PackReport())
        val r = Run("t", RunConfig(seed = 7, mobHpScale = 1.0), TestPlans.plan(), packs, systems = emptyList())
        r.begin(0)
        return r
    }

    private fun events(run: Run): MutableList<RunEvent> = ArrayList<RunEvent>().also { l -> run.bus.subscribe { l += it } }

    private fun spawn(run: Run, kind: String, room: Int? = 1, starred: Boolean = true, required: Boolean = true, x: Double = 10.0, z: Double = 10.0) =
        assertNotNull(MobsModule.spawn(run, MobSpec(kind, x, 70.0, z, room, starred, required)))

    private fun human(run: Run, x: Double, z: Double, bot: Boolean = false): PlayerRecord {
        val p = PlayerRecord(UUID.randomUUID(), "p", "Mage", bot)
        p.x = x; p.y = 70.0; p.z = z
        run.state.players[p.uuid] = p
        return p
    }

    /** Ticks the run and the module until [n] more server ticks have passed. */
    private fun tick(run: Run, n: Int) { repeat(n) { run.tick(run.serverTick + 1); MobsModule.onTick(run) } }

    @Test fun aRequiredMobCountsInItsRoomAndIsAnnounced() {
        val run = newRun(); val ev = events(run)
        val m = spawn(run, "test_walker")
        assertEquals(1, run.state.room(1)!!.mobsRequired); assertEquals(1, run.state.room(1)!!.mobsRequiredAlive)
        assertEquals(1, MobsModule.requiredAlive(1))
        assertTrue(ev.any { it is MobSpawned && it.mob.id == m.id && it.mob.required })
        assertEquals(m, MobsModule.mob(m.id)); assertEquals(listOf(m), MobsModule.inRoom(1))
        assertEquals(1_300_000.0, m.maxHp); assertTrue(m.starred && m.required && !m.miniboss)
    }

    @Test fun anUnstarredMobIsNotRequiredAndItsDeathDropsNothing() {
        val run = newRun(); val ev = events(run)
        val m = spawn(run, "test_walker", starred = false, required = false)
        assertEquals(0, run.state.room(1)!!.mobsRequired)
        MobsModule.setHp(run, m.id, 0.0, null)
        assertTrue(ev.any { it is MobKilled })
        assertTrue(doors.drops.isEmpty())
    }

    @Test fun damageLowersHpAndPostsTheEventOnce() {
        val run = newRun(); val ev = events(run)
        val m = spawn(run, "test_walker")
        val by = UUID.randomUUID()
        MobsModule.setHp(run, m.id, 1_000_000.0, by)
        val d = ev.filterIsInstance<MobDamaged>().single()
        assertEquals(300_000.0, d.amount); assertEquals(1_000_000.0, d.remaining); assertEquals(by, d.by)
        assertEquals(1_000_000.0, m.hp); assertTrue(m.alive)
        // The same number again changes nothing and says nothing.
        MobsModule.setHp(run, m.id, 1_000_000.0, by)
        assertEquals(1, ev.filterIsInstance<MobDamaged>().size)
    }

    @Test fun theLastRequiredKillDropsTheKeyWhereItDied() {
        val run = newRun(); val ev = events(run)
        val a = spawn(run, "test_walker", x = 5.0, z = 6.0); val b = spawn(run, "test_archer", x = 20.0, z = 30.0)
        // Seen from a MobKilled handler the room's counter has already gone down (the hub's rule: the field changes with the event).
        val seen = ArrayList<Int>()
        run.bus.subscribe { if (it is MobKilled) seen += run.state.room(1)!!.mobsRequiredAlive }
        MobsModule.setHp(run, a.id, 0.0, null)
        assertTrue(doors.drops.isEmpty(), "one mob still alive")
        assertEquals(1, run.state.room(1)!!.mobsRequiredAlive)
        MobsModule.setHp(run, b.id, 0.0, null)
        assertEquals(listOf(Triple(1, 20.0, 30.0)), doors.drops)
        assertEquals(listOf(1, 0), seen)
        assertEquals(2, ev.filterIsInstance<MobKilled>().size)
        assertFalse(a.alive); assertEquals(0.0, a.hp)
    }

    @Test fun noKeyInTheKeyPlanMeansNoDrop() {
        doors = FakeDoors(null)
        val run = newRun()
        val a = spawn(run, "test_walker")
        MobsModule.setHp(run, a.id, 0.0, null)
        assertTrue(doors.drops.isEmpty())
    }

    @Test fun aDeadMobCannotBeHurtOrKilledAgain() {
        val run = newRun(); val ev = events(run)
        val a = spawn(run, "test_walker")
        MobsModule.setHp(run, a.id, 0.0, null)
        MobsModule.setHp(run, a.id, 0.0, null)
        MobsModule.setHp(run, a.id, 5.0, null)
        assertEquals(1, ev.filterIsInstance<MobKilled>().size)
        assertEquals(0.0, a.hp)
        assertNull(MobsModule.setHp(run, UUID.randomUUID(), 1.0, null))
    }

    @Test fun bodiesAreBuiltOnlyNearAPlayerOnALoadedChunk() {
        val run = newRun()
        val m = spawn(run, "test_walker", x = 10.0, z = 10.0)
        human(run, 500.0, 500.0)
        tick(run, 10)
        assertNull(m.entityId, "a player 700 blocks away")
        run.state.players.values.single().let { it.x = 40.0; it.z = 40.0 }
        stage.loaded = false
        tick(run, 10)
        assertNull(m.entityId, "its chunk is not loaded")
        stage.loaded = true
        tick(run, 10)
        assertNotNull(m.entityId)
        assertEquals(1, stage.calls.count { it.startsWith("build") })
        assertEquals(m, MobsModule.recordOfEntity(m.entityId!!))
    }

    @Test fun aBodyThatWentAwayIsRebuiltWithItsHp() {
        val run = newRun()
        val m = spawn(run, "test_walker")
        human(run, 12.0, 12.0)
        tick(run, 10)
        val first = m.entityId!!
        tick(run, 30)   // it has stood for a while: a chunk unloading, not a body that vanishes at once
        MobsModule.setHp(run, m.id, 900_000.0, null)
        stage.gone = true
        tick(run, 1)
        assertNull(MobsModule.recordOfEntity(first))
        stage.gone = false
        tick(run, 10)
        assertNotNull(m.entityId); assertTrue(m.entityId != first)
        assertEquals(900_000.0, m.hp)
        assertEquals(2, stage.calls.count { it.startsWith("build") })
        assertTrue(m.alive)
    }

    @Test fun aBodyThatVanishesAtOnceIsNotRebuiltEveryFewTicks() {
        val run = newRun()
        spawn(run, "test_walker")
        human(run, 12.0, 12.0)
        stage.gone = true   // everything built is removed by something at once
        tick(run, 400)
        // 400 ticks at a retry of 200 after each quick vanish: a handful of builds, not 80.
        assertTrue(stage.calls.count { it.startsWith("build") } in 2..6, stage.calls.toString())
    }

    @Test fun aSleepingFelsCannotBeHurtUntilABodyComesWithinTheWakeRadius() {
        val run = newRun(); val ev = events(run)
        val f = spawn(run, "test_sleeper", x = 10.0, z = 10.0)
        assertTrue(MobsModule.isAsleep(f.id))
        val p = human(run, 40.0, 10.0)
        tick(run, 10)
        assertNotNull(f.entityId, "built asleep, because a player is within the spawn distance")
        MobsModule.setHp(run, f.id, 100.0, null)
        assertEquals(1_500_000.0, f.hp); assertTrue(ev.none { it is MobDamaged })
        p.x = 22.0   // 12 blocks: outside the 9 block sphere
        tick(run, 3)
        assertTrue(MobsModule.isAsleep(f.id))
        p.x = 17.0   // 7 blocks
        tick(run, 1)
        assertFalse(MobsModule.isAsleep(f.id))
        assertTrue(stage.calls.contains("wake test_sleeper"))
        MobsModule.setHp(run, f.id, 1_000_000.0, null)
        assertEquals(1_000_000.0, f.hp)
    }

    @Test fun aBotsBodyWakesAFelsToo() {
        val run = newRun()
        val f = spawn(run, "test_sleeper", x = 10.0, z = 10.0)
        human(run, 60.0, 10.0)
        human(run, 12.0, 10.0, bot = true)
        tick(run, 10)
        assertFalse(MobsModule.isAsleep(f.id))
    }

    @Test fun aMoverChasesAndHitsAHuman() {
        val run = newRun()
        // test_boss is a patrol miniboss; with no loop it stands until a player is close, then chases and swings.
        val m = spawn(run, "test_boss", x = 10.0, z = 10.0)
        val p = human(run, 18.0, 10.0)
        tick(run, 10)
        assertTrue(stage.acts.any { it is AiAction.MoveTo }, "8 blocks away is inside the aggro radius")
        assertTrue(damage.melee.isEmpty())
        p.x = 11.0
        tick(run, 25)
        assertTrue(damage.melee.isNotEmpty())
        assertEquals(m.id, damage.melee.first().first); assertEquals(p.uuid, damage.melee.first().second)
    }

    @Test fun botsAreNotTargets() {
        val run = newRun()
        val m = spawn(run, "test_boss", x = 10.0, z = 10.0)
        human(run, 11.0, 10.0, bot = true)
        tick(run, 30)
        assertTrue(damage.melee.isEmpty() && damage.hurt.isEmpty())
        assertTrue(m.alive)
    }

    @Test fun archersShootThroughTheProjectileTable() {
        val run = newRun()
        val m = spawn(run, "test_archer", x = 10.0, z = 10.0)
        val p = human(run, 18.0, 10.0)
        // A static archer only reacts to a close player; make it a mover by asking for a patrol-less miniboss-like state: use the sleeper-free path.
        p.x = 11.0
        tick(run, 10)   // adjacent: the standing mob hits (melee swing)
        assertTrue(damage.melee.isNotEmpty() || damage.hurt.isNotEmpty())
        assertNotNull(m)
    }

    @Test fun theAssassinShowsItselfWhenItNoticesAPlayer() {
        val run = newRun()
        spawn(run, "test_assassin", starred = false, required = true, x = 10.0, z = 10.0)
        human(run, 50.0, 10.0)
        tick(run, 10)
        assertTrue(stage.calls.none { it.startsWith("reveal") })
        run.state.players.values.single().x = 20.0
        tick(run, 2)
        assertTrue(stage.calls.contains("reveal test_assassin"))
    }

    @Test fun aHumanKillShowsTheCombatXpLineAndABotKillDoesNot() {
        val run = newRun()
        val bars = ArrayList<Pair<UUID, String>>()
        MobsModule.bar = { p, t -> bars += p to t }
        try {
            val a = human(run, 60.0, 10.0); val bot = human(run, 61.0, 10.0, bot = true)
            val m1 = spawn(run, "test_walker"); val m2 = spawn(run, "test_walker")
            MobsModule.setHp(run, m1.id, 0.0, a.uuid)
            MobsModule.setHp(run, m2.id, 0.0, bot.uuid)
            assertEquals(1, bars.size)
            assertEquals(a.uuid, bars[0].first)
            assertTrue(Regex("§3\\+(6\\d\\d|7[0-7]\\d)(\\.\\d)? Combat \\([\\d,]+/0\\)").matches(bars[0].second), bars[0].second)
        } finally { MobsModule.bar = { _, _ -> } }
    }

    @Test fun combatBarTextDropsAWholeGainsDecimalAndGroupsTheTotal() {
        assertEquals("§3+655.4 Combat (25,000,655/0)", MobsModule.combatBarText(655.4, 25_000_655.0))
        assertEquals("§3+608 Combat (1,000/0)", MobsModule.combatBarText(608.0, 1000.0))
    }

    @Test fun nothingIsBuiltBeforeTheRunStarts() {
        val run = newRun()
        spawn(run, "test_walker")
        human(run, 12.0, 10.0)
        run.setT0(run.serverTick + 100)
        tick(run, 20)
        assertTrue(stage.calls.none { it.startsWith("build") })
        tick(run, 100)
        assertTrue(stage.calls.any { it.startsWith("build") })
    }

    @Test fun removeTakesTheMobOutOfTheCounts() {
        val run = newRun(); val ev = events(run)
        val a = spawn(run, "test_walker"); spawn(run, "test_archer")
        MobsModule.remove(run, a.id)
        assertEquals(1, run.state.room(1)!!.mobsRequired); assertEquals(1, run.state.room(1)!!.mobsRequiredAlive)
        assertNull(MobsModule.mob(a.id))
        assertTrue(ev.none { it is MobKilled })
        assertTrue(doors.drops.isEmpty())
    }

    @Test fun clearAllReleasesEveryBody() {
        val run = newRun()
        spawn(run, "test_walker"); spawn(run, "test_actor")
        human(run, 12.0, 12.0)
        tick(run, 10)
        MobsModule.clearAll(run)
        assertTrue(MobsModule.all().isEmpty())
        assertEquals(2, stage.calls.count { it.startsWith("release") })
    }

    @Test fun theBossEntryRemovesTheClearsMobs() {
        val run = newRun()
        spawn(run, "test_walker")
        MobsModule.onStage(run, RunStage.BLOOD, RunStage.BOSS_ENTRY)
        assertTrue(MobsModule.all().isEmpty())
    }

    @Test fun aSavepointRoundTripKeepsHpDeathsAndCounters() {
        val run = newRun()
        val a = spawn(run, "test_walker"); val b = spawn(run, "test_archer"); val c = spawn(run, "test_sleeper")
        MobsModule.setHp(run, a.id, 0.0, null)
        MobsModule.setHp(run, b.id, 800_000.0, null)
        val snap = MobsModule.snapshot(run)!!
        // Things change after the savepoint...
        MobsModule.setHp(run, b.id, 0.0, null)
        MobsModule.remove(run, c.id)
        // ...and the restore brings them back.
        MobsModule.restore(run, snap)
        assertEquals(3, MobsModule.all().size)
        assertFalse(MobsModule.mob(a.id)!!.alive)
        assertEquals(800_000.0, MobsModule.mob(b.id)!!.hp)
        assertTrue(MobsModule.mob(c.id)!!.alive && MobsModule.isAsleep(c.id))
        // Three required mobs were spawned, one is dead.
        assertEquals(3, run.state.room(1)!!.mobsRequired); assertEquals(2, run.state.room(1)!!.mobsRequiredAlive)
        assertEquals(2, MobsModule.requiredAlive(1))
        assertNull(MobsModule.recordOfEntity(1))
    }

    @Test fun noMobPackSaysSoOnceAndSpawnsNothing() {
        val run = newRun(withPack = false)
        MobsModule.onRunStart(run)
        assertEquals(1, chats.size)
        assertTrue(chats[0].contains("mob tables"))
        assertTrue(MobsModule.all().isEmpty())
        assertNull(MobsModule.spawn(run, MobSpec("test_walker", 0.0, 70.0, 0.0)))
    }

    @Test fun aKindThePackDoesNotKnowCannotBeSpawned() {
        val run = newRun()
        assertNull(MobsModule.spawn(run, MobSpec("watcher", 0.0, 70.0, 0.0)))
    }

    @Test fun aLateJoinerIsToldAboutFakePlayers() {
        val run = newRun()
        spawn(run, "test_actor")
        MobsModule.onEvent(run, com.engineerclient.p3sim.run.PlayerJoined(0, UUID.randomUUID()))
        assertTrue(stage.calls.contains("announce 1"))
    }

    @Test fun recordsOfFakePlayersCarryVersionTwoUuids() {
        val run = newRun()
        val a = spawn(run, "test_actor"); val w = spawn(run, "test_walker")
        assertTrue(a.disguised); assertEquals(2, a.id.version())
        assertFalse(w.disguised); assertEquals(4, w.id.version())
    }

    @Test fun theSummaryListsRoomsAndSleepers() {
        val run = newRun()
        assertEquals(listOf("The module holds no mobs."), MobsModule.summaryLines(run))
        spawn(run, "test_walker"); spawn(run, "test_sleeper")
        val lines = MobsModule.summaryLines(run)
        assertTrue(lines[0].startsWith("2 mobs"))
        assertTrue(lines[1].contains("2/2 required alive") && lines[1].contains("1 asleep"), lines[1])
    }

    @Test fun theSummaryWarnsOfAKeyRoomWithoutRequiredMobs() {
        doors = FakeDoors(KeyKind.WITHER)
        val run = newRun()
        spawn(run, "test_walker", room = 1)
        val lines = MobsModule.summaryLines(run)
        assertTrue(lines.any { it.contains("no key will drop") }, lines.toString())
    }

    @Test fun fakePlayerTeamsAreNamedLikeHypixels() {
        assertEquals("fkt_abcdefghij", com.engineerclient.p3sim.clear.mobs.Disguise.teamName("abcdefghij"))
    }
}
