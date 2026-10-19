package com.engineerclient.p3sim.state

import com.engineerclient.p3sim.clear.state.*
import com.engineerclient.p3sim.pack.DungeonFrame
import com.engineerclient.p3sim.pack.Pos
import com.engineerclient.p3sim.pack.Rot
import com.engineerclient.p3sim.pack.runstate.ChestClear
import com.engineerclient.p3sim.pack.runstate.Dist
import com.engineerclient.p3sim.run.*
import com.engineerclient.p3sim.run.api.DoorOpenResult
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

/** The whole state module for one run, headless: the countdown, the doors, the keys, the room marks, with a recording stand-in for the world. */
class StateControllerTest {
    private class Rig(seed: Long = 7, cfg: StateConfig = StateConfig(), rules: StateRules = StateRules(), plan: DungeonPlan = StatePlans.plan()) {
        val run = StatePlans.run(seed, plan)
        val fx = RecordingEffects()
        val events = ArrayList<RunEvent>()
        val c = StateController(run, rules, cfg, fx)
        var tick = 100

        init {
            fx.now = { run.t }
            run.bus.subscribe { events += it }
            run.begin(tick)
            c.start()
            // Route bus events to the controller as the module does.
            run.bus.subscribe { c.onEvent(it) }
        }

        /** Advances to run tick [t] one server tick at a time. */
        fun until(t: Int) { while (run.t < t) { tick++; run.tick(tick); c.tick() } }

        inline fun <reified E : RunEvent> all() = events.filterIsInstance<E>()
        fun room(i: Int) = run.state.rooms[i]
        fun door(i: Int) = run.state.doors[i]
    }

    @Test fun theCountdownRunsOnTheMeasuredClock() {
        val r = Rig()
        // delay 60 + 60 + 20 lead: T0 is 140 ticks after the run began
        assertEquals(240, r.run.t0Tick)
        assertEquals(-140, r.run.t)
        val a = r.run.state.players
        StatePlans.addPlayer(r.run, "Ann"); StatePlans.addPlayer(r.run, "Bo", bot = true)
        r.until(300)
        val lines = r.fx.plainChat()
        fun at(text: String) = lines.single { it.second == text }.first
        assertEquals(-80, at("Starting in 4 seconds."))
        assertEquals(-60, at("Starting in 3 seconds."))
        assertEquals(-40, at("Starting in 2 seconds."))
        assertEquals(-20, at("Starting in 1 second."))
        assertEquals(0, at("[NPC] Mort: Here, I found this map when I first entered the dungeon."))
        assertEquals(40, at("[NPC] Mort: You should find it useful if you get lost."))
        assertEquals(70, at("[NPC] Mort: Good luck."))
        assertTrue(lines.any { it.second == "Ann is now ready!" } && lines.any { it.second == "Bo is now ready!" })
        assertTrue(lines.single { it.second == "Ann is now ready!" }.first < -80)
        assertEquals(RunStage.CLEAR, r.run.stage, "Mort's first line starts the clear")
        assertEquals(listOf(StateText.COUNTDOWN_TICK), r.fx.sounds.filter { it == StateText.COUNTDOWN_TICK }.distinct())
        assertEquals(4, r.fx.sounds.count { it == StateText.COUNTDOWN_TICK }); assertEquals(3, r.fx.sounds.count { it == StateText.MORT_LINE })
        assertTrue(a.isNotEmpty())
    }

    @Test fun theEntranceAndFairyDoorsFallByThemselves() {
        val r = Rig()
        r.until(40)
        // entrance door index 0: barrier 22 ticks after "1 second" (t=-20) = t 2, air 15 ticks later; the free Fairy door (index 2) one tick before
        val entrance = r.fx.bodies.filter { it.second == 0 }
        val free = r.fx.bodies.filter { it.second == 2 }
        assertEquals(listOf(Triple(2, 0, BodyBlocks.BARRIER), Triple(17, 0, BodyBlocks.AIR)), entrance)
        assertEquals(listOf(Triple(1, 2, BodyBlocks.BARRIER), Triple(15, 2, BodyBlocks.AIR)), free)
        assertEquals(DoorPhase.OPEN, r.door(0).phase); assertEquals(DoorPhase.OPEN, r.door(2).phase)
        assertEquals(DoorPhase.CLOSED, r.door(1).phase, "a paid wither door stays shut"); assertEquals(DoorPhase.CLOSED, r.door(5).phase)
        assertEquals(2, r.door(0).openedAt)
        assertEquals(listOf(0, 2), r.all<DoorOpened>().map { it.door }.sorted())
        assertEquals(listOf(0, 2), r.all<DoorFell>().map { it.door }.sorted())
        assertTrue(r.fx.plainChat().none { "opened a WITHER door" in it.second }, "the free door prints no line")
    }

    @Test fun doorsShowTheRoomsBesideThem() {
        val r = Rig()
        r.until(4)
        assertEquals(CheckMark.NONE, r.room(0).check); assertEquals(CheckMark.NONE, r.room(1).check)
        assertEquals(CheckMark.NONE, r.room(2).check, "the free door shows the room before the Fairy room")
        assertEquals(CheckMark.GREEN, r.room(3).check, "the Fairy room goes straight from undiscovered to green")
        assertEquals(CheckMark.UNDISCOVERED, r.room(4).check)
        assertEquals(3, r.run.state.counters.opened, "A, B and the Fairy room; the entrance never counts")
        r.until(10)
        assertEquals(CheckMark.GREEN, r.room(0).check, "the entrance turns green 5 ticks after it appears")
        // opened = tiles of discovered rooms but the entrance (A, B, Fairy); completed = the Fairy room
        assertEquals(3, r.run.state.counters.opened); assertEquals(1, r.run.state.counters.completed)
        assertEquals(3, r.run.state.counters.clearedPct, "round(100 * 1 / 36)")
        assertEquals(listOf(RoomDiscovered(0, 0).javaClass), r.all<RoomDiscovered>().map { it.javaClass }.distinct())
    }

    @Test fun keysEqualDoorsOpened() {
        val r = Rig()
        r.until(10)
        val ann = StatePlans.addPlayer(r.run, "Ann")
        ann.x = -185.0 + 32 * 1; ann.z = -185.0        // in room A
        r.c.beginFall(0, null)                          // (already open: nothing)
        // A's key: the mobs module asks, then drops it where the last mob died
        assertEquals(KeyKind.WITHER, r.c.keyForRoom(1))
        r.c.dropKey(1, ann.x + 0.5, ann.z)
        assertNull(r.c.keyForRoom(1), "a room drops one key")
        r.until(11)
        assertEquals(1, r.run.state.keys.witherHeld)
        assertTrue(r.fx.stands.isEmpty(), "the stand is gone once picked up")
        assertTrue(r.fx.plainChat().any { it.second == "[MVP+] Ann has obtained Wither Key!" })
        assertEquals(1, r.all<KeyPicked>().size)
        // the first paid door (A-B, index 1)
        assertEquals(DoorOpenResult.OPENED, r.c.tryOpen(1, ann.uuid))
        assertEquals(0, r.run.state.keys.witherHeld)
        assertTrue(r.fx.plainChat().any { it.second == "Ann opened a WITHER door!" })
        assertEquals(DoorPhase.FALLING, r.door(1).phase)
        assertEquals(DoorOpenResult.ALREADY_OPEN, r.c.tryOpen(1, ann.uuid))
        val openT = r.run.t
        assertTrue(r.fx.bodies.none { it.second == 1 }, "a key door writes no barrier in the chat tick")
        assertEquals(1, r.fx.debris.last(), "the falling-block animation starts at the open")
        r.until(openT + 1)
        assertEquals(openT + 1, r.fx.bodies.first { it.second == 1 }.first, "barrier one tick after the chat")
        r.until(openT + 15)
        assertEquals(DoorPhase.OPEN, r.door(1).phase)
        val body = r.fx.bodies.filter { it.second == 1 }
        assertEquals(14, body[1].first - body[0].first, "barrier for 14 ticks")
        assertEquals(DoorPhase.OPEN, r.door(1).phase)
        assertEquals(r.run.state.keys.witherObtained, r.run.state.keys.witherUsed)
        assertTrue(r.c.ledger.consistent())
    }

    @Test fun aDoorWithoutAKeyStaysShut() {
        val r = Rig()
        r.until(5)
        val ann = StatePlans.addPlayer(r.run, "Ann")
        assertEquals(DoorOpenResult.NO_KEY, r.c.tryOpen(1, ann.uuid))
        assertEquals(DoorPhase.CLOSED, r.door(1).phase)
        assertEquals("You do not have the key for this door!", r.fx.plainChat().last().second)
        assertEquals(StateText.NO_KEY, r.fx.sounds.last())
        assertEquals(1, r.all<DoorLocked>().size)
        r.c.touch(1, ann.uuid)           // a swing in the same tick is answered too, as on Hypixel
        assertEquals(2, r.all<DoorLocked>().size)
        assertEquals(2, r.fx.sounds.count { it == StateText.NO_KEY })
        r.until(6); r.c.touch(1, ann.uuid)
        assertEquals(3, r.all<DoorLocked>().size)
        assertEquals(DoorOpenResult.NOT_A_DOOR, r.c.tryOpen(99, ann.uuid))
        assertEquals(DoorOpenResult.ALREADY_OPEN, r.c.tryOpen(0, ann.uuid), "the entrance door falls by itself")
    }

    @Test fun theBloodDoorNeedsTheBloodKeyAndStartsTheWatcherStage() {
        val r = Rig()
        r.until(5)
        val ann = StatePlans.addPlayer(r.run, "Ann")
        assertEquals(DoorOpenResult.NO_KEY, r.c.tryOpen(5, ann.uuid))
        r.c.ledger.pick(KeyKind.WITHER)
        assertEquals(DoorOpenResult.NO_KEY, r.c.tryOpen(5, ann.uuid), "a wither key does not open the blood door")
        r.c.ledger.pick(KeyKind.BLOOD)
        val before = r.fx.chat.size
        assertEquals(DoorOpenResult.OPENED, r.c.tryOpen(5, ann.uuid))
        assertEquals(listOf("The BLOOD DOOR has been opened!", "A shiver runs down your spine..."), r.fx.plainChat().drop(before).map { it.second })
        assertEquals(listOf(StateText.DOOR_OPENED, StateText.BLOOD_DOOR), r.fx.sounds.takeLast(2), "the pling, then the ghast")
        assertEquals(1, r.all<BloodDoorOpened>().size)
        assertEquals(r.run.t, r.run.state.milestones.bloodOpenT)
        assertEquals(RunStage.CLEAR, r.run.stage, "the blood module, not this one, asks for the BLOOD stage")
        assertEquals(CheckMark.NONE, r.room(6).check, "the blood room appears when its door falls")
        r.run.requestStage(RunStage.BLOOD)
        assertEquals(CheckMark.RED, r.room(6).check, "red during the Watcher stage")
        r.run.bus.post(WatcherDone(r.run.t))
        assertEquals(CheckMark.RED, r.room(6).check, "the pass line does not turn the room green: the check comes with the portal")
    }

    /** Opens the blood room's stage the way the blood module does and returns the run tick of the Watcher's pass line. */
    private fun Rig.passLine(): Int {
        until(5); c.discover(6)
        run.requestStage(RunStage.BLOOD)
        assertEquals(CheckMark.RED, room(6).check)
        until(run.t + 10)
        run.bus.post(WatcherDone(run.t))
        return run.t
    }

    @Test fun theBloodRoomTurnsGreenNinetyTicksAfterThePassLineWhenNoPortalIsAnnounced() {
        val r = Rig(cfg = StateConfig(synthesiseClears = false))        // no other room finishes by itself and moves the counters
        val pass = r.passLine()
        val before = r.run.state.counters.completed
        r.until(pass + 89)
        assertEquals(CheckMark.RED, r.room(6).check)
        assertEquals(before, r.run.state.counters.completed)
        r.until(pass + 90)
        assertEquals(CheckMark.GREEN, r.room(6).check)
        assertEquals(before + 1, r.run.state.counters.completed, "Completed Rooms and Cleared % follow the check")
        assertEquals(pass + 90, r.room(6).tGreen)
        assertEquals(1, r.all<RoomGreen>().count { it.room == 6 })
    }

    @Test fun aPortalNoticeFromTheBloodModuleBringsTheCheckToOneTickAfterIt() {
        val r = Rig()
        val pass = r.passLine()
        r.until(pass + 80)
        r.run.bus.post(ModuleEvent(r.run.t, "blood", "portal"))
        r.until(pass + 80)
        assertEquals(CheckMark.RED, r.room(6).check, "not in the tick of the portal")
        r.until(pass + 81)
        assertEquals(CheckMark.GREEN, r.room(6).check)
        // a notice from some other module with the same name means nothing
        val r2 = Rig()
        val pass2 = r2.passLine()
        r2.run.bus.post(ModuleEvent(r2.run.t, "bots", "portal"))
        r2.until(pass2 + 5)
        assertEquals(CheckMark.RED, r2.room(6).check)
    }

    @Test fun aPortalThatOpensAfterTheTimerChangesNothing() {
        val r = Rig()
        val pass = r.passLine()
        r.until(pass + 100)
        assertEquals(CheckMark.GREEN, r.room(6).check)
        val green = r.room(6).tGreen
        r.run.bus.post(ModuleEvent(r.run.t, "blood", "portal"))
        r.until(r.run.t + 5)
        assertEquals(green, r.room(6).tGreen)
        assertEquals(1, r.all<RoomGreen>().count { it.room == 6 })
    }

    @Test fun theBossEntryTurnsTheBloodRoomGreenAtOnce() {
        val r = Rig()
        val pass = r.passLine()
        r.until(pass + 20)
        assertEquals(CheckMark.RED, r.room(6).check)
        r.run.requestStage(RunStage.BOSS_ENTRY)
        assertEquals(CheckMark.GREEN, r.room(6).check, "nobody enters the boss with the blood room still red")
        r.until(pass + 200)
        assertEquals(1, r.all<RoomGreen>().count { it.room == 6 })
    }

    @Test fun aPendingBloodCheckSurvivesASavepointRestore() {
        val r = Rig()
        val pass = r.passLine()
        r.until(pass + 30)
        val snap = r.c.snapshot()
        r.c.restore(snap)
        r.until(pass + 89); assertEquals(CheckMark.RED, r.room(6).check)
        r.until(pass + 90); assertEquals(CheckMark.GREEN, r.room(6).check)
        // a restore from before the pass line has nothing pending
        val r2 = Rig()
        r2.until(5); r2.c.discover(6); r2.run.requestStage(RunStage.BLOOD)
        val early = r2.c.snapshot()
        r2.run.bus.post(WatcherDone(r2.run.t))
        r2.c.restore(early)
        r2.until(r2.run.t + 200)
        assertEquals(CheckMark.RED, r2.room(6).check)
    }

    @Test fun aKeyStandIsTakenByWhoeverComesClose() {
        val r = Rig(cfg = StateConfig(pickupRadius = 2.0))
        r.until(5)
        val far = StatePlans.addPlayer(r.run, "Far", x = -153.0, z = -185.0)
        r.c.dropKey(1, -150.0, -185.0)
        r.until(15)
        assertEquals(1, r.c.standList().size, "nobody is close")
        far.x = -151.0
        r.until(16)
        assertTrue(r.c.standList().isEmpty()); assertEquals(1, r.run.state.keys.witherHeld)
    }

    @Test fun aGhostCannotTakeAKey() {
        val r = Rig()
        r.until(5)
        val g = StatePlans.addPlayer(r.run, "Ghost", x = -150.0, z = -185.0); g.ghost = true
        r.c.dropKey(1, -150.0, -185.0)
        r.until(20)
        assertEquals(1, r.c.standList().size)
    }

    @Test fun anUnclaimedStandGoesToTheNearestAfterTheSafetyTime() {
        val r = Rig(rules = StateRules(autoCollectTicks = 30))
        r.until(5)
        StatePlans.addPlayer(r.run, "Near", x = -120.0, z = -185.0)
        r.c.dropKey(1, -150.0, -185.0)
        r.until(34); assertEquals(1, r.c.standList().size)
        r.until(36); assertTrue(r.c.standList().isEmpty()); assertEquals(1, r.run.state.keys.witherHeld)
    }

    @Test fun theKillerTakesTheKeyTheTickItDrops() {
        // pickupInstantFrac 1.0: the killer, standing nowhere near, still has it at once
        val r = Rig(rules = StateRules(pickupInstantFrac = 1.0))
        r.until(5)
        val ann = StatePlans.addPlayer(r.run, "Ann", x = -100.0, z = -100.0)
        val mob = MobRef(UUID.randomUUID(), "x", 1, starred = true, required = true)
        r.run.state.rooms[1].mobsRequired = 1; r.run.state.rooms[1].mobsRequiredAlive = 0
        r.run.bus.post(MobKilled(r.run.t, mob, ann.uuid))
        r.c.dropKey(1, -150.0, -185.0)
        assertTrue(r.c.standList().isEmpty()); assertEquals(1, r.run.state.keys.witherHeld)
        assertEquals(ann.uuid, r.all<KeyPicked>().single().player)
    }

    @Test fun theBloodKeyStampsItsMilestone() {
        val r = Rig(rules = StateRules(pickupInstantFrac = 0.0))
        r.until(5)
        StatePlans.addPlayer(r.run, "Ann", x = -90.0, z = -185.0)       // in room D
        r.c.dropKey(5, -90.0, -185.0)
        r.until(6)
        assertTrue(r.run.state.keys.bloodHeld)
        assertEquals(r.run.t, r.run.state.milestones.bloodKeyT)
        assertEquals(listOf(StateText.KEY_OBTAINED, StateText.KEY_SHUTTER), r.fx.sounds.takeLast(2), "the orb, then the vault shutter")
        assertTrue(r.fx.plainChat().any { it.second == "[MVP+] Ann has obtained Blood Key!" })
    }

    @Test fun steppingIntoARoomPostsTheEntryAndDiscoversIt() {
        val r = Rig()
        r.until(1)
        val ann = StatePlans.addPlayer(r.run, "Ann", x = DungeonFrame.tileCentre(4) + 0.5, z = DungeonFrame.tileCentre(0) + 0.5)   // room C
        r.until(2)
        assertEquals(4, ann.roomIndex)
        assertEquals(CheckMark.NONE, r.room(4).check)
        assertTrue(ann.uuid in r.room(4).occupants && ann.uuid in r.room(4).visitors)
        assertEquals(listOf(4), r.all<RoomEntered>().map { it.room })
        // across the door gap and into D
        ann.x = -200.0 + 32 * 4 + 31.5; r.until(3)
        assertEquals(4, ann.roomIndex, "a door gap belongs to neither room: the player keeps the one they came from")
        ann.x = DungeonFrame.tileCentre(5) + 0.5; r.until(4)
        assertEquals(5, ann.roomIndex); assertTrue(ann.uuid !in r.room(4).occupants && ann.uuid in r.room(5).occupants)
        // out of the dungeon (the boss arena side)
        ann.x = 40.0; ann.z = 40.0; r.until(5)
        assertNull(ann.roomIndex); assertTrue(r.room(5).occupants.isEmpty())
    }

    @Test fun aRoomTurnsWhiteWhenItsMobsAreDeadThenGreenWithItsSecrets() {
        val r = Rig(cfg = StateConfig(synthesiseClears = false))
        r.until(5)
        val ann = StatePlans.addPlayer(r.run, "Ann", x = -153.0, z = -185.0)  // room A (2 secrets)
        r.until(6)
        val a = r.room(1)
        a.mobsRequired = 2; a.mobsRequiredAlive = 2
        r.until(40)
        assertEquals(CheckMark.NONE, a.check)
        a.mobsRequiredAlive = 1
        r.until(41); assertEquals(CheckMark.NONE, a.check)
        a.mobsRequiredAlive = 0
        r.run.bus.post(MobKilled(r.run.t, MobRef(UUID.randomUUID(), "x", 1, true, true), ann.uuid))
        assertEquals(CheckMark.WHITE, a.check)
        assertEquals(1 + 1 /* the Fairy room */, r.run.state.counters.completed)
        a.secretsFound = 1; r.run.bus.post(SecretFound(r.run.t, 1, SecretKind.CHEST, ann.uuid, 0, 0, 0))
        assertEquals(CheckMark.WHITE, a.check)
        a.secretsFound = 2; r.run.bus.post(SecretFound(r.run.t, 1, SecretKind.ITEM, ann.uuid, 0, 0, 0))
        assertEquals(CheckMark.GREEN, a.check)
        assertEquals(listOf(1), r.all<RoomCleared>().map { it.room }); assertEquals(1, r.all<RoomGreen>().count { it.room == 1 })
    }

    @Test fun aBotsGreenNoticeNeverMakesARoomGreenWithSecretsMissing() {
        val r = Rig(cfg = StateConfig(synthesiseClears = false))
        r.until(5)
        r.c.discover(1)
        r.room(1).secretsFound = 1   // 1 of 2; no secrets module in the headless rig to count the rest
        r.run.bus.post(ModuleEvent(r.run.t, "bots", "clear.green", mapOf("room" to 1)))
        assertNotEquals(CheckMark.GREEN, r.room(1).check, "green needs every secret")
        r.room(1).secretsFound = 2
        r.run.bus.post(ModuleEvent(r.run.t, "bots", "clear.green", mapOf("room" to 1)))
        assertEquals(CheckMark.GREEN, r.room(1).check)
    }

    @Test fun aRoomWithNoSecretsGoesStraightToGreen() {
        val r = Rig(cfg = StateConfig(synthesiseClears = false))
        r.until(5)
        r.c.discover(2)
        r.room(2).mobsRequired = 1; r.room(2).mobsRequiredAlive = 0
        r.until(7)       // the poll finds the kills even without the event
        assertEquals(CheckMark.GREEN, r.room(2).check)
        assertTrue(r.all<RoomCleared>().none { it.room == 2 }, "no white step: none to green")
    }

    @Test fun allSecretsFoundBeforeTheMobsDieGoesGreenWhenTheyDo() {
        val r = Rig(cfg = StateConfig(synthesiseClears = false))
        r.until(5)
        r.c.discover(1); r.room(1).secretsFound = 2
        r.c.objectiveDone(1)
        assertEquals(CheckMark.GREEN, r.room(1).check)
    }

    @Test fun aRoomWithNoMobBecomesWhiteOnTheSchedule() {
        val r = Rig()
        r.until(5)
        r.c.discover(4)          // room C: 3 secrets, no mobs recorded
        val at = r.run.t
        r.until(at + 49); assertEquals(CheckMark.NONE, r.room(4).check)
        r.until(at + 51); assertEquals(CheckMark.WHITE, r.room(4).check, "the pooled median is 50 ticks")
    }

    @Test fun synthesisedClearsCanBeTurnedOff() {
        val r = Rig(cfg = StateConfig(synthesiseClears = false))
        r.until(5); r.c.discover(4)
        r.until(r.run.t + 400)
        assertEquals(CheckMark.NONE, r.room(4).check)
    }

    @Test fun aRoomWithMobsAliveIsNotSynthesised() {
        val r = Rig()
        r.until(5); r.c.discover(4)
        r.room(4).mobsRequired = 3; r.room(4).mobsRequiredAlive = 3
        r.until(r.run.t + 400)
        assertEquals(CheckMark.NONE, r.room(4).check)
    }

    @Test fun aKeyRoomThatFinishesWithoutAKeyDropsOneAtItsCentre() {
        // A room with mobs to kill: the mobs module gets its chance first.
        val r = Rig()
        r.until(5); r.c.discover(4); r.room(4).mobsRequired = 1
        r.c.objectiveDone(4)
        assertTrue(r.c.standList().isEmpty(), "the mobs module gets its chance first")
        r.until(r.run.t + 5)
        val s = r.c.standList().single()
        assertEquals(4, s.room); assertEquals(KeyKind.WITHER, s.kind)
        assertEquals(r.c.centre(4).first, s.x)
        assertEquals(1, r.all<KeyDropped>().size)
        // ... and when the mobs module did drop it, there is no second one
        val r2 = Rig()
        r2.until(5); r2.c.discover(4); r2.room(4).mobsRequired = 1; r2.c.objectiveDone(4); r2.c.dropKey(4, -60.0, -185.0)
        r2.until(r2.run.t + 10)
        assertEquals(1, r2.all<KeyDropped>().size)
    }

    @Test fun aKeyRoomWithNoMobToKillGivesItsKeyAtOnceToWhoeverIsInIt() {
        val r = Rig()
        val (x, z) = r.c.centre(4)
        val bo = StatePlans.addPlayer(r.run, "Bo", bot = true, x = x, z = z)
        r.until(5)
        r.c.objectiveDone(4)
        assertTrue(r.c.standList().isEmpty(), "taken in the same tick")
        assertEquals(bo.uuid, r.all<KeyPicked>().single().player)
        // Nobody in the room: it lies at the centre for whoever comes.
        val r2 = Rig()
        r2.until(5); r2.c.discover(4); r2.c.objectiveDone(4)
        assertEquals(4, r2.c.standList().single().room)
    }

    @Test fun aKeyABotKilledForAndLeftIsTakenAfterTheMeasuredWait() {
        // pickupInstantFrac 0.0: never at once; the bot, far from the stand, has it within the measured wait (p95 26 ticks).
        val r = Rig(rules = StateRules(pickupInstantFrac = 0.0))
        r.until(5)
        val bo = StatePlans.addPlayer(r.run, "Bo", bot = true, x = -100.0, z = -100.0)
        r.run.state.rooms[1].mobsRequired = 1; r.run.state.rooms[1].mobsRequiredAlive = 0
        r.run.bus.post(MobKilled(r.run.t, MobRef(UUID.randomUUID(), "x", 1, starred = true, required = true), bo.uuid))
        r.c.dropKey(1, -150.0, -185.0)
        assertEquals(1, r.c.standList().size, "not at once")
        r.until(r.run.t + 27)
        assertTrue(r.c.standList().isEmpty())
        assertEquals(bo.uuid, r.all<KeyPicked>().single().player)
        // A person who killed for it walks over: nothing is given to them.
        val r2 = Rig(rules = StateRules(pickupInstantFrac = 0.0))
        r2.until(5)
        val ann = StatePlans.addPlayer(r2.run, "Ann", x = -100.0, z = -100.0)
        r2.run.state.rooms[1].mobsRequired = 1; r2.run.state.rooms[1].mobsRequiredAlive = 0
        r2.run.bus.post(MobKilled(r2.run.t, MobRef(UUID.randomUUID(), "x", 1, starred = true, required = true), ann.uuid))
        r2.c.dropKey(1, -150.0, -185.0)
        r2.until(r2.run.t + 40)
        assertEquals(1, r2.c.standList().size)
    }

    // ---- the trap room's end chest -------------------------------------------------------------------------------------

    /** A pack rule for the invented trap room: its end chest, then WHITE after a fixed [gap] ticks. */
    private fun chestRules(gap: Double = 3.0) = StateRules(chestClears = mapOf(StatePlans.TRAP_NAME to ChestClear(
        StatePlans.TRAP_NAME, "s2", StatePlans.END_CHEST, Dist(20, doubleArrayOf(5.0, 50.0, 95.0), doubleArrayOf(gap, gap, gap), gap, gap), 20)))

    private fun Rig.chestAt(cell: Pos): Triple<Int, Int, Int> = run.plan.rooms[2].placement!!.toWorld(cell.x, cell.y, cell.z).let { Triple(it.x, it.y, it.z) }

    private fun Rig.open(cell: Pos, found: Int) {
        val (x, y, z) = chestAt(cell)
        room(2).secretsFound = found                  // the secrets module counts first, then posts
        run.bus.post(SecretFound(run.t, 2, SecretKind.CHEST, null, x, y, z))
    }

    @Test fun aTrapRoomIsFinishedByItsEndChestNotByATimer() {
        val r = Rig(rules = chestRules(), plan = StatePlans.trapPlan(Rot.SOUTH))
        r.until(5); r.c.discover(2)
        assertEquals(CheckMark.NONE, r.room(2).check)
        r.until(r.run.t + 1000)
        assertEquals(CheckMark.NONE, r.room(2).check, "standing around finishes nothing: the timer is not used")
        r.open(StatePlans.SIDE_CHEST, 1)
        r.until(r.run.t + 100)
        assertEquals(CheckMark.NONE, r.room(2).check, "the other chest of the room changes nothing")
        val opened = r.run.t
        r.open(StatePlans.END_CHEST, 2)
        r.until(opened + 2); assertEquals(CheckMark.NONE, r.room(2).check)
        r.until(opened + 3); assertEquals(CheckMark.WHITE, r.room(2).check, "white after the measured gap")
        assertEquals(opened + 3, r.room(2).tWhite)
        // green needs the last secret as for any room
        r.open(StatePlans.END_CHEST, 3)     // (a stand-in for the bat)
        assertEquals(CheckMark.GREEN, r.room(2).check)
    }

    @Test fun theEndChestOpenedLastGoesStraightToGreen() {
        val r = Rig(rules = chestRules(gap = 2.0), plan = StatePlans.trapPlan(Rot.SOUTH))
        r.until(5); r.c.discover(2)
        r.open(StatePlans.END_CHEST, 3)      // every secret found by the time the chest finishes the room
        r.until(r.run.t + 2)
        assertEquals(CheckMark.GREEN, r.room(2).check)
        assertTrue(r.all<RoomCleared>().none { it.room == 2 }, "no white step: none to green")
    }

    @Test fun anInstantGapFinishesTheRoomInTheTickOfTheOpen() {
        val r = Rig(rules = chestRules(gap = 0.0), plan = StatePlans.trapPlan(Rot.SOUTH))
        r.until(5); r.c.discover(2)
        r.open(StatePlans.END_CHEST, 1)
        assertEquals(CheckMark.WHITE, r.room(2).check)
    }

    @Test fun theEndChestFinishesTheRoomWhateverTheSynthesiseSetting() {
        val r = Rig(cfg = StateConfig(synthesiseClears = false), rules = chestRules(), plan = StatePlans.trapPlan(Rot.SOUTH))
        r.until(5); r.c.discover(2)
        r.open(StatePlans.END_CHEST, 1)
        r.until(r.run.t + 3)
        assertEquals(CheckMark.WHITE, r.room(2).check)
    }

    @Test fun theChestIsTurnedWithTheRoomsPlacement() {
        val south = Rig(rules = chestRules(), plan = StatePlans.trapPlan(Rot.SOUTH)).chestAt(StatePlans.END_CHEST)
        for (rot in Rot.entries) {
            val r = Rig(rules = chestRules(), plan = StatePlans.trapPlan(rot))
            r.until(5); r.c.discover(2)
            if (rot != Rot.SOUTH) {
                assertNotEquals(south, r.chestAt(StatePlans.END_CHEST), "$rot turns the room")
                // the chest's spot in an unrotated room is not the chest of this one
                r.room(2).secretsFound = 1
                r.run.bus.post(SecretFound(r.run.t, 2, SecretKind.CHEST, null, south.first, south.second, south.third))
                r.until(r.run.t + 10)
                assertEquals(CheckMark.NONE, r.room(2).check, "$rot: an unrotated cell is not the end chest")
            }
            r.open(StatePlans.END_CHEST, 1)
            r.until(r.run.t + 3)
            assertEquals(CheckMark.WHITE, r.room(2).check, "$rot: the turned end chest finishes the room")
        }
    }

    @Test fun aTrapRoomTheBotsFinishIsNotFinishedTwice() {
        val r = Rig(rules = chestRules(), plan = StatePlans.trapPlan(Rot.NORTH))
        r.until(5); r.c.discover(2)
        r.open(StatePlans.END_CHEST, 1)
        r.run.bus.post(ModuleEvent(r.run.t, "bots", "clear.white", mapOf("room" to 2)))
        assertEquals(CheckMark.WHITE, r.room(2).check)
        val white = r.room(2).tWhite
        r.until(r.run.t + 10)
        assertEquals(white, r.room(2).tWhite)
        assertEquals(1, r.all<RoomCleared>().count { it.room == 2 })
    }

    @Test fun aChestOpenedAfterTheRoomIsDoneChangesNothing() {
        val r = Rig(rules = chestRules(), plan = StatePlans.trapPlan(Rot.SOUTH))
        r.until(5)
        r.c.discover(2); r.c.objectiveDone(2)
        val marks = r.all<RoomCleared>().size
        r.open(StatePlans.END_CHEST, 1)
        r.until(r.run.t + 10)
        assertEquals(marks, r.all<RoomCleared>().size)
    }

    @Test fun aTrapRoomWithoutAPackRuleStillFinishesOnTheTimer() {
        val r = Rig(plan = StatePlans.trapPlan(Rot.SOUTH))       // default rules: no chestClears
        r.until(5)
        val at = r.room(2).tDiscovered!!                         // the free door showed the room at t=1
        r.until(at + 49); assertEquals(CheckMark.NONE, r.room(2).check)
        r.until(at + 51); assertEquals(CheckMark.WHITE, r.room(2).check, "the old pooled timer, so an older pack still plays")
    }

    @Test fun aPendingChestFinishSurvivesASavepointRestore() {
        val r = Rig(rules = chestRules(gap = 5.0), plan = StatePlans.trapPlan(Rot.EAST))
        r.until(5); r.c.discover(2)
        r.open(StatePlans.END_CHEST, 1)
        val opened = r.run.t
        r.until(opened + 2)
        val snap = r.c.snapshot()
        r.c.restore(snap)
        r.until(opened + 4); assertEquals(CheckMark.NONE, r.room(2).check)
        r.until(opened + 5); assertEquals(CheckMark.WHITE, r.room(2).check)
    }

    @Test fun puzzlesFailAndSolve() {
        val r = Rig(cfg = StateConfig(synthesiseClears = false))
        r.until(5); r.c.discover(2)
        r.run.bus.post(PuzzleFailed(r.run.t, 0, 2, null, "wrong"))
        assertEquals(CheckMark.RED, r.room(2).check)
        assertEquals(1, r.run.state.counters.completed, "red does not count as completed (only the Fairy room does)")
        r.run.bus.post(PuzzleSolved(r.run.t, 0, 2, null))
        assertEquals(CheckMark.GREEN, r.room(2).check)
    }

    @Test fun theEntranceNeverCountsAndNeitherDoTheRoomsNotYetSeen() {
        val r = Rig()
        r.until(30)
        val (opened, completed) = RoomChecks.counters(r.run.state.rooms)
        assertEquals(3, opened); assertEquals(1, completed)
        assertEquals(36, r.run.state.counters.total)
    }

    @Test fun aSavepointRestoreRebuildsDoorsStandsAndTheReplay() {
        val r = Rig(rules = StateRules(pickupInstantFrac = 0.0))
        r.until(20)
        r.c.dropKey(1, -150.0, -185.0)
        val snap = r.c.snapshot()
        // the world after a restore: the region put every door back closed; RunState says which had fallen
        r.fx.bodies.clear(); r.fx.stands.clear()
        r.c.restore(snap)
        assertEquals(1, r.fx.stands.size, "the stand is made again")
        val opened = r.fx.bodies.filter { it.third == BodyBlocks.AIR }.map { it.second }.sorted()
        assertEquals(listOf(0, 2), opened, "the doors that were open are written open again")
        assertEquals(KeyKind.WITHER.let { null }, r.c.keyForRoom(1), "room A's key already dropped")
        assertEquals(20, r.c.lastT)
    }

    @Test fun aRestoreInTheMiddleOfAFallResumesIt() {
        val r = Rig()
        r.until(1)                     // the free door has just become barrier (t=1), the entrance door is next
        val snap = r.c.snapshot()
        r.fx.bodies.clear()
        r.c.restore(snap)
        assertTrue(r.fx.bodies.any { it.second == 2 && it.third == BodyBlocks.BARRIER })
        r.until(40)
        assertEquals(DoorPhase.OPEN, r.door(2).phase)
        assertTrue(r.fx.bodies.any { it.second == 2 && it.third == BodyBlocks.AIR })
    }

    @Test fun theEntranceIsExcludedEvenWhenGreen() {
        val r = Rig()
        r.until(30)
        assertEquals(CheckMark.GREEN, r.room(0).check)
        assertEquals(1, r.run.state.counters.completed)
    }
}
