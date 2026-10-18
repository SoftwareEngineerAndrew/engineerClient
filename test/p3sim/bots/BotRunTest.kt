package com.engineerclient.p3sim.bots

import com.engineerclient.p3sim.clear.bots.*
import com.engineerclient.p3sim.pack.PackReport
import com.engineerclient.p3sim.pack.PackSet
import com.engineerclient.p3sim.pack.layouts.DoorKind
import com.engineerclient.p3sim.run.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The bots module inside a headless Run (no world: bodies are never drawn, every service answers its default), and the pure pieces around it. */
class BotRunTest {
    private val human = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val dungeon = BotFixtures.dungeon()
    private val packs = PackSet(Path.of("/nowhere"), null, null, null, BotFixtures.tablesPack(), PackReport())

    @AfterEach fun reset() { BotsModule.onRunEnd(Run("x", RunConfig(), dungeon, packs, systems = emptyList()), EndReason.STOPPED) }

    private fun run(seed: Long = 4, config: RunConfig = RunConfig(seed = seed, bots = true, partySize = 5)): Run {
        val r = Run("t", config, dungeon, packs, systems = listOf(BotsModule))
        r.state.players[human] = PlayerRecord(human, "Human", "HEALER", false)
        r.begin(1000)
        return r
    }

    private fun tickTo(r: Run, t: Int) { var s = r.serverTick; r.setT0(r.t0Tick ?: 1000); while (r.t < t) { s++; r.tick(s) } }

    @Test fun botsTakeTheClassesNoHumanPlaysAndJoinTheParty() {
        val r = run()
        val bots = r.state.players.values.filter { it.isBot }
        assertEquals(4, bots.size)
        assertEquals(setOf("MAGE", "BERSERK", "ARCHER", "TANK"), bots.map { it.dungeonClass }.toSet())
        assertEquals(bots.map { it.uuid }.toSet(), Services_botIds(r).toSet())
    }

    @Test fun botsAreSeparatePlayersWithNamesRanksAndLevelsOfTheirOwn() {
        val r = run()
        val bots = r.state.players.values.filter { it.isBot }
        assertEquals(4, bots.map { it.name.lowercase() }.toSet().size, "four different names: ${bots.map { it.name }}")
        assertTrue(bots.none { it.name in BotRoster.CLASSES.map { c -> BotRoster.label(c) } }, "no class word as a name")
        assertTrue(bots.all { Regex("[A-Za-z0-9_]{3,16}").matches(it.name) })
        assertTrue(bots.all { it.uuid.version() == 4 }, "profile ids of real accounts are version 4")
        for (b in bots) {
            assertTrue((b.extras["sbLevel"] as Int) in 40..571 && (b.extras["classLevel"] as Int) in 30..50)
            assertTrue((b.extras["rankBracket"] as String).contains("["))
            assertTrue(b.extras["glyph"] is String, "the glyph key is always written ('' for none)")
        }
        // The same seed gives the same party.
        val again = run().state.players.values.filter { it.isBot }.map { it.name }
        assertEquals(bots.map { it.name }, again)
        assertTrue(run(seed = 99).state.players.values.filter { it.isBot }.map { it.name } != again, "another seed, other names")
    }

    @Test fun theClassWordLookStaysAvailable() {
        val roster = BotRoster.build(listOf(Triple(human, "H", "HEALER")), true, 5)
        assertEquals(listOf("Mage", "Berserk", "Archer", "Tank"), roster.bots.map { roster.names.getValue(it.index) })
        assertEquals(BotRoster.botUuid("MAGE"), roster.uuids.getValue(roster.bots.first().index), "the boss sim's own id")
    }

    @Test fun everyBotHasItsOwnFullHealthNumber() {
        val r = run()
        val maxes = r.state.players.values.filter { it.isBot }.map { (it.extras["maxHp"] as? Int) ?: 0 }
        assertTrue(maxes.all { it in 14_000..54_000 }, "max health $maxes")
        assertTrue(maxes.toSet().size > 1, "not one shared number")
    }

    @Test fun aGhostBotIsOffTheTeamUntilItIsRevived() {
        val r = run()
        tickTo(r, 100)
        val s = BotsModule.session!!
        val bot = s.roster.bots.first()
        val uuid = s.roster.uuids.getValue(bot.index)
        val rec = r.state.players.getValue(uuid)
        assertNotNull(s.plan.track(bot.index))
        rec.ghost = true
        r.bus.post(PlayerDied(r.t, uuid, DamageSource.MELEE, null))
        tickTo(r, 130)
        assertNull(BotsModule.session!!.plan.track(bot.index), "the re-plan leaves the ghost out")
        val frozen = rec.x to rec.z
        tickTo(r, 400)
        assertEquals(frozen, rec.x to rec.z, "a ghost stays where it fell")
        val anyDoor = s.plan.doors.values.first().door
        assertTrue(!s.world.openDoor(bot.index, anyDoor) && !s.world.killRequired(bot.index, 1, 1.0), "a ghost's hands do nothing")
        rec.ghost = false
        r.bus.post(PlayerRevived(r.t, uuid, null))
        tickTo(r, 430)
        assertNotNull(BotsModule.session!!.plan.track(bot.index), "back on the team after the revive")
    }

    private fun nearBot(s: BotsModule.Session): Pair<Int, Pt> = s.roster.bots.map { it.index }.let { seats -> seats.first().let { it to s.pos.getValue(it) } }

    @Test fun aKeyDropNearABotSendsItWalkingAndOneFarAwaySendsNobody() {
        val r = run()
        tickTo(r, 100)
        val s = BotsModule.session!!
        val (seat, p) = nearBot(s)
        r.bus.post(KeyDropped(r.t, KeyKind.WITHER, 1, p.x + 3.0, 70.0, p.z))
        assertTrue(seat in s.excursion || s.excursion.isNotEmpty(), "a bot within 6 blocks steps onto the stand")
        s.excursion.clear(); s.stands.clear()
        r.bus.post(KeyDropped(r.t, KeyKind.BLOOD, 5, p.x + 60.0, 70.0, p.z))
        assertTrue(s.excursion.isEmpty(), "nobody is pulled across the dungeon to a stand")
    }

    private fun Services_botIds(r: Run) = com.engineerclient.p3sim.run.api.Services.bots.botIds(r)

    @Test fun botsOffMeansNoBots() {
        val r = run(config = RunConfig(seed = 1, bots = false))
        assertTrue(r.state.players.values.none { it.isBot })
        assertTrue(Services_botIds(r).isEmpty())
    }

    @Test fun aSmallerPartyHasFewerBotsNeverFewerHumans() {
        val r = run(config = RunConfig(seed = 1, bots = true, partySize = 3))
        assertEquals(2, r.state.players.values.count { it.isBot })
        assertEquals(1, r.state.players.values.count { !it.isBot })
    }

    @Test fun botsMoveAlongThePlanAndTheirRecordsFollow() {
        val r = run()
        val start = r.state.players.values.filter { it.isBot }.associate { it.uuid to (it.x to it.z) }
        tickTo(r, 300)
        val moved = r.state.players.values.filter { it.isBot }.count { (it.x to it.z) != start[it.uuid] }
        assertTrue(moved >= 3, "the bots have walked ($moved of 4)")
        val plan = BotsModule.session!!.plan
        for (b in r.state.players.values.filter { it.isBot }) {
            val seat = BotsModule.session!!.roster.seatOf(b.uuid)!!.index
            val want = plan.track(seat)!!.positionAt(r.t)
            assertEquals(want.x, b.x, 1e-6); assertEquals(want.z, b.z, 1e-6)
        }
    }

    @Test fun roomsTheServicesCannotClearAreAskedForOnce() {
        val r = run()
        val seen = ArrayList<ModuleEvent>()
        r.bus.on<ModuleEvent> { if (it.source == "bots") seen += it }
        tickTo(r, 1500)
        val whites = seen.filter { it.name == "clear.white" }.map { it.data["room"] as Int }
        assertTrue(whites.isNotEmpty(), "with no mobs service the bots ask the doors module for the white marks")
        assertEquals(whites.size, whites.toSet().size, "once per room")
    }

    @Test fun theHumanTakingARoomMakesTheBotsRedraw() {
        val r = run()
        tickTo(r, 20)
        val before = BotsModule.session!!.plan
        val victim = before.rooms.values.first { !it.soft && it.arriveT > 100 }
        r.bus.post(RoomEntered(r.t, victim.room, human))
        tickTo(r, 60)
        val after = BotsModule.session!!.plan
        assertTrue(after !== before, "the plan was redrawn")
        assertTrue(after.builtAt > 0)
        assertTrue(victim.room !in after.rooms.keys || after.rooms.getValue(victim.room).seat != victim.seat, "the bot gave the room up")
        assertTrue(BotsModule.session!!.kept.any { it.contains("took room ${victim.room}") })
    }

    @Test fun anOverdueRoomOfTheHumansIsCoveredByBots() {
        val r = run()
        val s = BotsModule.session!!
        val soft = s.plan.rooms.values.firstOrNull { it.soft }
        assertNotNull(soft, "the human seat has rooms in the first plan")
        tickTo(r, soft.arriveT + BotTuning.COVER_AFTER_TICKS + 100)
        assertTrue(s.covered)
        assertTrue(BotsModule.session!!.plan.rooms.values.none { it.soft }, "after covering, every room is a bot's")
    }

    @Test fun ownerOfRoomNamesTheBotOrTheHuman() {
        val r = run()
        val s = BotsModule.session!!
        for (rp in s.plan.rooms.values) {
            val owner = com.engineerclient.p3sim.run.api.Services.bots.ownerOfRoom(r, rp.room)
            assertNotNull(owner)
            assertEquals(rp.soft, owner == human)
        }
        assertNull(com.engineerclient.p3sim.run.api.Services.bots.ownerOfRoom(r, 3), "the Fairy room has no owner")
    }

    @Test fun theClearEndingRemovesTheBotsPlan() {
        val r = run()
        tickTo(r, 50)
        r.requestStage(RunStage.CLEAR); r.requestStage(RunStage.BLOOD); r.requestStage(RunStage.BOSS_ENTRY)
        val pos = r.state.players.values.filter { it.isBot }.map { it.x }
        tickTo(r, 200)
        assertEquals(pos, r.state.players.values.filter { it.isBot }.map { it.x }, "no more movement once the boss begins")
        assertEquals(4, r.state.players.values.count { it.isBot }, "the party records stay for the HUD")
    }

    @Test fun anUnknownStrategyFallsBackToNeutral() {
        val r = run(config = RunConfig(seed = 1, bots = true, partySize = 5, strategy = "no-such-preset"))
        assertEquals(null, BotsModule.session!!.plan.milestones.bloodSeat)
    }

    // ---- the pure pieces --------------------------------------------------------------------------------------------------

    @Test fun rosterGivesHumansTheirOwnClassAndDoesNotRepeatOne() {
        val a = UUID.randomUUID(); val b = UUID.randomUUID()
        val roster = BotRoster.build(listOf(Triple(a, "A", "TANK"), Triple(b, "B", "TANK")), true, 5)
        assertEquals(listOf("TANK", "HEALER"), roster.humans.map { it.dungeonClass }, "the second TANK gets a free class")
        assertEquals(3, roster.bots.size)
        assertEquals(roster.seats.map { it.index }, listOf(0, 1, 2, 3, 4))
        assertEquals(5, roster.seats.map { it.dungeonClass }.toSet().size)
        assertTrue(roster.seats.take(2).all { it.isHuman })
    }

    @Test fun botOrderIsKept() {
        val roster = BotRoster.build(listOf(Triple(UUID.randomUUID(), "A", "MAGE")), true, 5, listOf("TANK", "ARCHER", "BERSERK", "HEALER"))
        assertEquals(listOf("TANK", "ARCHER", "BERSERK", "HEALER"), roster.bots.map { it.dungeonClass })
    }

    @Test fun secretKindsMapEveryPackWord() {
        assertEquals(SecretKind.CHEST, SecretKinds.of("chest")); assertEquals(SecretKind.ITEM, SecretKinds.of("item")); assertEquals(SecretKind.BAT, SecretKinds.of("bat"))
        assertEquals(SecretKind.ESSENCE, SecretKinds.of("essence")); assertEquals(SecretKind.REDSTONE_KEY, SecretKinds.of("redstoneKey")); assertEquals(SecretKind.LOCKED_CHEST, SecretKinds.of("lockedChest"))
        assertEquals(SecretKind.CHEST, SecretKinds.of(null))
    }

    @Test fun replanStateReadsTheHub() {
        val bus = RunBus()
        val state = RunState(dungeon, bus) { 500 }
        val roster = BotRoster.build(listOf(Triple(human, "H", "HEALER")), true, 5)
        state.setCheck(8, CheckMark.WHITE); state.setCheck(10, CheckMark.GREEN)
        val wither = dungeon.doors.first { it.kind == DoorKind.WITHER }
        state.door(wither.index)!!.also { it.phase = DoorPhase.FALLING; it.openedAt = 300 }
        state.keys.witherHeld = 1
        state.room(9)!!.occupants += human
        val seatPos = roster.bots.associate { it.index to Pt(-100.0, -100.0) }
        val st = ReplanState.build(state, 500, null, roster, seatPos, mapOf(12 to 450, 7 to -200), covered = false, openerHint = 2)
        assertEquals(setOf(8, 10), st.white); assertEquals(setOf(10), st.green)
        assertEquals(300 + BotTuning.DOOR_PASSABLE_AFTER, st.passableAt[wither.index])
        assertEquals(1, st.witherKeys)
        assertTrue(9 in st.humanRooms, "the room the human stands in")
        assertTrue(12 in st.humanRooms, "a room the human went into a moment ago")
        assertTrue(7 !in st.humanRooms, "a room long ago is forgotten")
        assertTrue(st.humanSeatActive); assertEquals(2, st.openerHint)
        assertEquals(setOf(1, 2, 3, 4), st.seatFreeAt.keys)
        assertTrue(!ReplanState.build(state, 500, null, roster, seatPos, emptyMap(), covered = true, openerHint = null).humanSeatActive)
    }

    @Test fun aMidRoomBotKeepsItsRoomThroughAReplan() {
        val bus = RunBus()
        val state = RunState(dungeon, bus) { 0 }
        val roster = BotRoster.build(listOf(Triple(human, "H", "HEALER")), true, 5)
        val plan = ClearPlanner(PlannerInput(dungeon, BotFixtures.tables(), roster.seats, kotlin.random.Random(3), state = PlanState(0, humanSeatActive = false))).build()
        val inRoom = plan.rooms.values.filter { !it.soft && (it.greenT ?: it.whiteT ?: 0) > it.arriveT + 20 && it.arriveT > 40 }.minBy { it.arriveT }
        val now = inRoom.arriveT + 10
        val st = ReplanState.build(state, now, plan, roster, roster.bots.associate { it.index to Pt(0.0, 0.0) }, emptyMap(), covered = true, openerHint = null)
        val c = st.commits.firstOrNull { it.room == inRoom.room }
        assertNotNull(c, "the bot part-way through ${inRoom.room} keeps it")
        assertEquals(inRoom.seat, c.seat)
    }

    @Test fun theDevCommandGateReadsLayoutsAndTablesFromAPackSet() {
        val layouts = com.engineerclient.p3sim.pack.layouts.LayoutPack.fromJson(com.engineerclient.p3sim.pack.Fixtures.json(BotFixtures.layoutJson()))
        val set = PackSet(Path.of("/nowhere"), null, layouts, null, BotFixtures.tablesPack(), PackReport())
        val lines = BotCommands.gate(set, 30, 0.5)
        assertTrue(lines.first().contains("30 plans"), lines.toString())
        assertTrue(lines.any { it.contains("blood door open") })
        val noTables = PackSet(Path.of("/nowhere"), null, layouts, null, null, PackReport())
        assertTrue(BotCommands.gate(noTables, 30, 0.5).single().contains("run-state tables"))
    }
}
