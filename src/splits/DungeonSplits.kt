package com.engineerclient.splits

import com.engineerclient.EngineerClient
import com.engineerclient.misc.Witherborn
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.StringSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.BlockUpdateEvent
import com.odtheking.odin.events.EntityEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.features.impl.dungeon.map.tile.RoomType
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.texture
import com.odtheking.odin.utils.sendCommand
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.item.PrimedTnt
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.Blocks

/**
 * The BR (blood rush) and Goldor sub-split HUDs, plus the run tracking behind the Engineer Splits look (the splits
 * themselves are drawn by Odin's Splits module, see [OdinSplitsLook]): bests, Pace and lag.
 *
 * [SplitTracker] times the splits, [SubSplitTracker] the boss steps (Pace's targets) and
 * [BloodRunDetail] the rush room by room; this module feeds them chat, the two clocks (real time
 * and server ticks) and what it sees in the world.
 *
 * The HUD's detail levels: Compact (one row per room), Detailed (labelled, one per line) and Debug
 * (Detailed plus every extra moment known).
 */
object DungeonSplits : Module(
    name = "Sub Splits",
    category = Category.custom("Engineer Client", 860, 10),
    description = "The blood rush sub splits, room by room, and Goldor's: each leap into the core after S4 and Goldor starting to move. The splits themselves are Odin's Splits (Look: Engineer Splits); every other section's sub splits and the Scorecard are in Devgineer Client.",
    key = null,
) {

    private val CONTROL_CODES = Regex("§.")
    private val tracker = SplitTracker()
    private val subs = SubSplitTracker()
    private val blood = BloodRunDetail()

    private var serverTicks = 0
    private fun now() = Stamp(System.currentTimeMillis(), serverTicks)

    /** Each boss sub split's best time, per floor (SubSplitGrades: ticks, or ms for the real-time ones). */
    private var bestsF7 by StringSetting("Sub Split Bests F7", "", 2048, desc = "", placeholder = "").hide()
    private var bestsM7 by StringSetting("Sub Split Bests M7", "", 2048, desc = "", placeholder = "").hide()

    /** Tracks the in-boss moments (the Watcher's dialog, everyone in the core) the watchers below key off. */
    private val moments = BossMoments()

    /**
     * The blood rush HUD's settings together, in the order they show in the ClickGUI: the HUD with
     * its own on/off toggle, then under it its detail level, the Total row toggle and Hide In Boss.
     * The HUD is made up front because a HUD has to exist before the run that fills it.
     */
    private val bloodHud = registerSetting(
        HUD("BR Sub Split", "What happened inside Blood Rush, room by room.", true, 780, 229, 0.9f) { example ->
            if (example) return@HUD draw(this, listOf(
                "§70.52s §8| \t§c1.73s \t§5Hallway: \t§62.31s",
                "\t§411.73s \t§dDino: \t§622.31s",
            ))
            draw(this, bloodLines())
        }
    )

    /** The HUD is on: its detail settings only show then. */
    private fun hudOn() = bloodHud.value.enabled

    private val bloodLevel = registerSetting(SelectorSetting("Blood Rush Detail", BloodRunDetail.Level.COMPACT, desc = "How much the Blood Rush sub-split HUD shows. Debug adds every extra moment known about it."))
        .withDependency { hudOn() }
    private val totalRow = registerSetting(
        BooleanSetting("Blood Rush Total Row", true, desc = "The averages row at the bottom of the compact blood rush splits.")
    ).withDependency { hudOn() && bloodLevel.value == BloodRunDetail.Level.COMPACT }
    private val bloodHideInBoss = registerSetting(
        BooleanSetting("Blood Rush Hide In Boss", false, desc = "Hides the blood rush sub splits once you are in the boss.")
    ).withDependency { hudOn() }

    /** The Goldor sub split: each leap into the core after S4 and Goldor starting to move ([GoldorCore]). */
    private val core = GoldorCore()
    private val goldorHud = registerSetting(
        HUD("Goldor Sub Split", "From the core opening after S4: when each player leapt into the core and when Goldor started moving, in exact server ticks. Up until 10 s into Necron.", true, 750, 10, 1f) { example ->
            if (example) return@HUD draw(this, listOf("§eCore", "§fYou §a0.05s", "§7Teammate §a0.50s", "§7Another §61.35s", "§eGoldor moved §f1.40s §8(+0.05s after the last in)"))
            draw(this, core.lines(serverTicks, mc.player?.name?.string, teamNames().size))
        }
    )
    private val pasteCore = registerSetting(
        BooleanSetting("Paste Last In Core", false, desc = "Says in party chat who was last into the core after S4 and their time (e.g. \"Last in core: Name 1.35s\"), once all five are in. Only when you could see all 4 teammates the whole time, so the time is exact.")
    )

    /** Goldor, where the server last put him and on which tick, for his setting off after the core opens. */
    private var coreGoldor: Triple<Int, net.minecraft.world.phys.Vec3, Int>? = null

    // What the world shows, watched only while it can matter.
    private val barriers = mutableListOf<Pair<Int, Int>>()
    private val cleared = mutableListOf<Pair<Int, Int>>()
    private val keysSeen = HashSet<Int>()
    private val crystalsSeen = HashSet<Int>()
    /** The Watcher's mobs in view, so one is counted once however many times it is added. */
    private val watchedMobs = HashSet<Int>()

    /** Forgets the run (world load, or a P3 Sim restart). */
    private fun resetRun() {
        bestsChecked.clear(); realRun = true
        synchronized(lagSamples) { lagShown = false; lagSamples.clear() }
        tracker.reset(); subs.reset(); blood.reset(); moments.reset()
        core.reset(); coreGoldor = null
        goldorAt = null; goldorMoved = false; necronId = null; watcherAt = null
        barriers.clear(); cleared.clear(); keysSeen.clear(); crystalsSeen.clear(); watchedMobs.clear(); necronTnt.clear()
    }

    /**
     * The P3 Sim starting a fight at [label]'s phase (client thread, before its lines arrive): a
     * fresh run, in Odin's Splits ([SimOdinSplits]) and here, whose earlier phases are taken as their
     * Pace targets. [termsDone]: sections already done when starting partway through the terminals,
     * where no Goldor line comes to start the split.
     */
    fun simStart(label: String, termsDone: Int = 0) {
        resetRun()
        realRun = false
        val before = { l: String ->
            val ms = SimOdinSplits.odinName(l)?.let { SimOdinSplits.target(it) } ?: SplitPace.ref(l)?.ms ?: 0L
            SplitTracker.Clock(ms, (ms / 50).toInt())
        }
        val head = (1..termsDone).mapNotNull { SplitPace.subRef("terms.s$it") }
        val headClock = SplitTracker.Clock(head.sumOf { it.ms }, head.sumOf { it.ticks }.toInt())
        // A P3 / S1 start plays Storm's end first (StormEnd.LEAD, 3 s, to Goldor's "Who dares"
        // line; the game's is 3.1 s since Hypixel's boss update), and on Hypixel Odin's Storm row
        // runs through it: start Odin that far into Storm (the Storm Pace target, or its dark green
        // time of 41.3 s, less the lead-in) so "Storm took" reads a real time.
        if (label == SplitTracker.TERMS && termsDone == 0) {
            val storm = SimOdinSplits.odinName(SplitTracker.STORM)
            if (storm != null) {
                val whole = SimOdinSplits.target(storm).takeIf { it > 0 } ?: SplitPace.ref(SplitTracker.STORM)?.ms ?: 41_300L
                SimOdinSplits.start(storm, (whole - com.engineerclient.p3sim.StormEnd.LEAD * 50L).coerceAtLeast(0L))
            }
        } else SimOdinSplits.odinName(label)?.let { SimOdinSplits.start(it, headClock.ms) }
        if (termsDone > 0) {
            val at = now()
            tracker.startAt(label, before, at, headClock)
            // The sections before, at their Pace times, so the section steps run too.
            if (head.size == termsDone) {
                val back = IntArray(termsDone + 1)
                for (s in termsDone downTo 1) back[s - 1] = back[s] + head[s - 1].ticks.toInt()
                subs.startTerms(termsDone + 1, back.map { at.minus(it) })
            }
        } else tracker.startAt(label, before)
    }

    /** The P3 Sim's Stop (client thread): this run and Odin's both gone, as a world load leaves them. */
    fun simStop() {
        resetRun()
        SimOdinSplits.stop()
    }

    private fun Stamp.minus(ticks: Int) = Stamp(realMs - ticks * 50L, tick - ticks)

    init {
        on<LevelEvent.Load> {
            resetRun()
            serverTicks = 0
        }

        // Odin's server tick: the server's own clock, which falls behind when it lags.
        on<TickEvent.Server> { serverTicks++; subs.onServerTick(); sampleLag() }

        // Chat straight off the network, before any mod can hide it — chat cleaners drop exactly
        // the terminal and gate lines the splits are timed from.
        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (overlay) return@onReceive
            val text = content.string.replace(CONTROL_CODES, "")
            val at = now()
            EngineerClient.mc.execute {
                EngineerClient.safely("splits chat") {
                    if (!DungeonUtils.inDungeons) return@safely
                    tracker.onChat(text, at)
                    moments.onChat(text, at)
                    subs.onChat(text, at)
                    blood.onChat(text, at)
                    if (text == CORE_OPEN) core.onCoreOpen(at.tick)
                    else if (text.startsWith(NECRON_LINE)) core.onNecron(at.tick)
                }
            }
        }

        // A door falling: its 36 blocks turn to barrier as it starts, and those barriers to air
        // when it is down. Nothing else in the rush does either 36 at a time.
        on<BlockUpdateEvent> {
            if (blood.active) {
                if (updated.block == Blocks.BARRIER && old.block != Blocks.BARRIER) barriers += pos.x to pos.z
                else if (old.block == Blocks.BARRIER && updated.isAir) cleared += pos.x to pos.z
            }
            // A terminal section's door: its ~228 barriers turn to air in the tick the section ends
            // (its last completion and its gate both in). Seen whatever chat cleaners hide.
            if (open(SplitTracker.TERMS) && old.block == Blocks.BARRIER && updated.isAir) {
                val section = SECTION_DOORS.indexOfFirst { (x, z) -> pos.x in x && pos.z in z }
                if (section >= 0) sectionDoor[section]++
            }
            // Maxor's beacon turning to bedrock is his kill.
            if (open(SplitTracker.MAXOR) && pos.x == 73 && pos.y == 221 && pos.z == 73 && updated.block == Blocks.BEDROCK) {
                subs.onMaxorKilled(now())
            }
        }

        on<TickEvent.End> {
            EngineerClient.safely("sim odin splits") { SimOdinSplits.tick() }
            if (!DungeonUtils.inDungeons) return@on
            EngineerClient.safely("split bests") { recordSplitBests() }
            if (barriers.size >= DoorBlocks.DOOR_BLOCKS) door(barriers) { at, a, b -> blood.onDoorStart(at, a, b) }
            if (cleared.size >= DoorBlocks.DOOR_BLOCKS) door(cleared) { at, a, b -> blood.onDoorDown(at, a, b) }
            barriers.clear(); cleared.clear()
            for (i in sectionDoor.indices) {
                if (sectionDoor[i] >= SECTION_DOOR_BLOCKS) {
                    subs.onSectionDoor(now(), i + 1)
                }
                sectionDoor[i] = 0
            }

            // Storm and Necron where the server last put them (not where they are drawn, 3 ticks behind).
            if (open(SplitTracker.STORM)) bossWither(level, "Storm")?.positionCodec?.base?.let {
                subs.onStormPosition(now(), it.x, it.y, it.z)
            }
            if (open(SplitTracker.NECRON)) bossWither(level, "Necron")?.let { necron ->
                necronId = necron.id
                subs.onNecronPosition(now(), necron.positionCodec.base.distanceTo(NECRON_MID))
            }

            // The key is an armor stand named "Wither Key"; it appears where the last mob died.
            if (blood.active) for (e in level.entitiesForRendering()) {
                if (e !is ArmorStand || e.id in keysSeen) continue
                val name = e.customName?.string ?: continue
                if (KEY.containsMatchIn(name)) {
                    keysSeen += e.id
                    blood.onKeySpawned(now(), distanceTo(e))
                }
            }

            // Goldor's leap ends when the last teammate is inside the core.
            val inCore = if (subs.watchingCore || open(SplitTracker.GOLDOR)) everyoneInCore(level) else false
            if (inCore == true) {
                subs.onEveryoneInCore(now())
                moments.onEveryoneInCore(now())
            }
            // The backup, only when the box can't tell (someone out of render distance): Goldor
            // starting to move, which he does once everyone is in.
            if (open(SplitTracker.GOLDOR) && moments.waitingForCore) watchGoldor(level, trusted = inCore == null) else goldorAt = null

            if (core.watching) watchCore(level)

            // The Watcher moving off his starting spot, once his first spawns are out.
            if (open(SplitTracker.BLOOD) && moments.waitingForWatcher) watchWatcher(level)
        }

        on<EntityEvent.Add> {
            val e = entity
            when {
                // Fresh crystals sit on the upper platforms (y 238), placed ones on the lower (y 224).
                // Back on top 41 ticks after a laser hit: a hit an ability kept quiet.
                e is EndCrystal && open(SplitTracker.MAXOR) && crystalsSeen.add(e.id) -> if (e.y >= 231) subs.onTopCrystal(now())
                // Necron's death: the burst of TNT he dies in.
                e is PrimedTnt && open(SplitTracker.NECRON) -> onNecronTnt(now())
                // The Watcher's mobs are player entities that are not on the team.
                e is Player && open(SplitTracker.BLOOD) && e.name.string !in teamNames() -> {
                    if (watchedMobs.add(e.id)) subs.onBloodMobSpawn(now())
                }
            }
        }
        on<EntityEvent.Remove> {
            // Necron's wither going, close by: the backup for his death when the TNT wasn't seen.
            if (entity is WitherBoss && open(SplitTracker.NECRON) && entity.id == necronId && distanceTo(entity) <= 48) {
                necronDead(now().minus(NECRON_GONE))
            }
            // A Witherborn wither (full Storm armor) going is not Maxor's death; one far off is
            // probably out of view, not his death.
            if (Witherborn.isBoss(entity) && open(SplitTracker.MAXOR) && distanceTo(entity) <= 48) subs.onMaxorDead(now())
            watchedMobs.remove(entity.id)
        }
    }

    /**
     * Every living teammate inside the core — the main way everyone-in is found: true, false (a
     * teammate you can see is outside), or null when someone is out of render distance and
     * everyone you can see is in, which the box can't decide. The box reaches down to the core's
     * floor, since players stand as low as y 64 during the fight.
     */
    private fun everyoneInCore(level: net.minecraft.client.multiplayer.ClientLevel): Boolean? {
        val alive = DungeonUtils.dungeonTeammates.filter { !it.isDead }
        if (alive.isEmpty()) return false
        var unseen = false
        for (mate in alive) {
            val p = mate.entity ?: level.players().firstOrNull { it.name.string == mate.name }
            if (p == null) { unseen = true; continue }
            if (!(p.x >= 39 && p.x < 71 && p.y < 155.5 && p.z >= 54 && p.z < 118)) return false
        }
        // Everyone you can see is in, but not everyone can be seen: the box can't say.
        return if (unseen) null else true
    }

    /**
     * The Goldor sub split each tick while it is open: every teammate inside the core box, and
     * Goldor setting off for the core. Both where the server last put them (not where they are
     * drawn, a few ticks behind); you where you are. He creeps along his track at ~0.05 blocks a
     * tick until everyone is in, then goes at ~0.65: setting off is the first step faster than
     * [GOLDOR_GO] a tick, timed from the step's start. A jump of blocks at once is him coming into
     * view, not moving.
     */
    private fun watchCore(level: net.minecraft.client.multiplayer.ClientLevel) {
        val me = mc.player
        if (me != null && inCoreBox(me.x, me.y, me.z)) core.onInside(me.name.string, serverTicks)
        for (mate in DungeonUtils.dungeonTeammates) {
            if (mate.isDead) continue
            val p = mate.entity ?: level.players().firstOrNull { it.name.string == mate.name }
            if (p === me) continue
            if (p == null) { core.onUnseen(mate.name); continue }
            val at = p.positionCodec.base
            if (inCoreBox(at.x, at.y, at.z)) core.onInside(mate.name, serverTicks)
        }
        pasteLastIn()
        if (core.goldorMoved != null) return
        val g = coreGoldor
        if (g == null) { bossWither(level, "Goldor")?.let { coreGoldor = Triple(it.id, it.positionCodec.base, serverTicks) }; return }
        val e = level.getEntity(g.first) ?: run { coreGoldor = null; return }
        val at = e.positionCodec.base
        if (at == g.second) return
        val dx = at.x - g.second.x; val dz = at.z - g.second.z
        val d = Math.sqrt(dx * dx + dz * dz)
        val ticks = (serverTicks - g.third).coerceAtLeast(1)
        if (d < 8 && d / ticks > GOLDOR_GO) core.onGoldorMoved(g.third)
        else coreGoldor = Triple(e.id, at, serverTicks)
    }

    /**
     * Paste Last In Core: once all five are in, the last one and their time in party chat - only
     * with four teammates all alive and each in render distance from the core opening until they
     * were in (an unseen one's time could be late). Not in singleplayer (the P3 Sim).
     */
    private fun pasteLastIn() {
        if (!pasteCore.enabled || core.pasted || mc.hasSingleplayerServer()) return
        val mates = DungeonUtils.dungeonTeammates.filter { it.name != mc.player?.name?.string }
        if (mates.size != 4 || core.count < 5) return
        core.pasted = true
        if (core.unseen || mates.any { it.isDead }) return
        val (name, ticks) = core.last() ?: return
        sendCommand("pc Last in core: $name " + GoldorCore.secs(ticks))
    }

    private fun inCoreBox(x: Double, y: Double, z: Double) = x >= 39 && x < 71 && y < 155.5 && z >= 54 && z < 118

    /** The TNT seen in the last 2 server ticks of Necron's fight, for his death's burst. */
    private val necronTnt = ArrayDeque<Stamp>()

    /**
     * A TNT appearing during Necron's fight. He dies in a burst of them, 3 or more within 2 server
     * ticks (sometimes split 9 + 1 across two ticks), which since Hypixel's boss update (5 Oct 2026)
     * is the clearest sign of his death: "All this, for nothing..." is no longer said. On F7, EXTRA
     * STATS follows 38-50 ticks later. The single TNT seen earlier in his fight never come 3 at a
     * time.
     */
    private fun onNecronTnt(at: Stamp) {
        necronTnt.addLast(at)
        while (necronTnt.first().tick < at.tick - (DEATH_BURST_TICKS - 1)) necronTnt.removeFirst()
        if (necronTnt.size < DEATH_BURST_TNT) return
        val first = necronTnt.first()
        necronTnt.clear()
        necronDead(first)
    }

    /**
     * Necron dead at [at]. On F7 that is all: his split runs on to the run's end, there being no
     * end animation since Hypixel's boss update. On M7 the Wither King's fight is next, so his steps
     * end here, and Odin's own Necron split (which ends on "All this, for nothing...") is handed
     * that line.
     */
    private fun necronDead(at: Stamp) {
        if (DungeonUtils.floor?.name?.startsWith("M") == true) {
            subs.onNecronDeath(at)
            OdinSplitsLook.onNecronDead()
            com.engineerclient.misc.Timers.onNecronDead()
        }
    }

    /** Necron's wither, as last found, for the backup when his burst is missed. */
    private var necronId: Int? = null

    private val DEATH_BURST_TNT = 3
    private val DEATH_BURST_TICKS = 2
    /**
     * His wither is removed 20-21 server ticks after the burst, in every recorded run where it was
     * seen go.
     */
    private val NECRON_GONE = 20

    /** A boss's wither: the one nearest the name tag carrying [name], or nearest you without one. */
    private fun bossWither(level: net.minecraft.client.multiplayer.ClientLevel, name: String): WitherBoss? {
        val withers = level.entitiesForRendering().filterIsInstance<WitherBoss>().filter { Witherborn.isBoss(it) }
        val tag = level.entitiesForRendering().firstOrNull { it is ArmorStand && it.customName?.string?.contains(name) == true }
        val anchor = tag ?: mc.player ?: return null
        return withers.minByOrNull { it.distanceToSqr(anchor) }
    }

    /** Goldor and where he waits once the core opens. */
    private var goldorAt: Pair<Int, net.minecraft.world.phys.Vec3>? = null
    private var goldorMoved = false

    /**
     * Everyone in the core, read off Goldor — the backup to the player box, used only when [trusted]
     * (someone is out of render distance, so the box can't tell). Once the core opens he holds still
     * until the last player is in, then starts for the core: within 0-5 ticks of it in every
     * recorded run. Only his first move counts. A jump of blocks at once is him coming into view,
     * not moving, and starts the watch again.
     */
    private fun watchGoldor(level: net.minecraft.client.multiplayer.ClientLevel, trusted: Boolean) {
        if (goldorMoved) return
        val at = goldorAt
        if (at == null) { bossWither(level, "Goldor")?.let { goldorAt = it.id to it.position() }; return }
        val e = level.getEntity(at.first) ?: run { goldorAt = null; return }
        val d = e.position().distanceTo(at.second)
        if (d > 8) goldorAt = e.id to e.position()
        else if (d > 0.1) {
            if (trusted) {
                moments.onEveryoneInCore(now())
                subs.onEveryoneInCore(now())
            }
            goldorMoved = true
        }
    }

    private fun open(label: String) = tracker.split(label)?.stop == null && tracker.split(label) != null

    private fun teamNames(): Set<String> =
        DungeonUtils.dungeonTeammates.mapTo(HashSet()) { it.name }.also { set -> mc.player?.let { set += it.name.string } }

    /** Each door among [blocks] ([DoorBlocks]), handed to [sink] with the rooms either side of it. */
    private fun door(blocks: List<Pair<Int, Int>>, sink: (Stamp, BloodRunDetail.MapRoom?, BloodRunDetail.MapRoom?) -> Unit) {
        for (d in DoorBlocks.doors(blocks)) {
            val a = room(d.a.first, d.a.second); val b = room(d.b.first, d.b.second)
            sink(now(), a, b)
        }
    }

    private fun room(x: Int, z: Int): BloodRunDetail.MapRoom? {
        if (x !in 0..5 || z !in 0..5) return null
        val r = DungeonScan.tiles[x + z * 6].room ?: return null
        return BloodRunDetail.MapRoom(
            id = System.identityHashCode(r).toString(),
            name = r.name ?: return null,
            fairy = r.type == RoomType.FAIRY,
            entrance = r.type == RoomType.ENTRANCE,
        )
    }

    private const val LINE_HEIGHT = 10

    /** The S1/S2, S2/S3 and S3/S4 doors (x, z), each where its gate stands on Goldor's track. */
    private val SECTION_DOORS = listOf(92..108 to 120..125, 15..20 to 124..140, 0..16 to 47..52)
    /** A door is ~228 barriers going at once; nothing else in P3 clears this many there. */
    private const val SECTION_DOOR_BLOCKS = 100
    private val sectionDoor = IntArray(3)
    private val NECRON_MID = net.minecraft.world.phys.Vec3(54.0, 66.0, 76.0)
    /** Blocks a tick: faster is Goldor setting off for the core (he creeps at ~0.05, goes at ~0.65). */
    private const val GOLDOR_GO = 0.25
    private const val CORE_OPEN = "The Core entrance is opening!"
    private const val NECRON_LINE = "[BOSS] Necron: "
    private val KEY = Regex("""(?:Wither|Blood) Key""")

    /** The Watcher: his id and where he was last tick. */
    private var watcherAt: Pair<Int, net.minecraft.world.phys.Vec3>? = null

    /**
     * The Watcher's move, the way Devonian times it: once his dialog is over ("Let's see how you
     * can handle this."), the first tick he moves at least 45 server ticks after that line - the
     * wait skips his settling right as he says it. 55-148 ticks after the line in the recorded runs,
     * depending on the camp, so there is nothing to count it from: without him in view it stays
     * blank. He is the zombie in one of his skins (Odin's Blood Camp list).
     */
    private fun watchWatcher(level: net.minecraft.client.multiplayer.ClientLevel) {
        val handle = moments.watcherHandle ?: return
        val prev = watcherAt
        val e = (prev?.let { level.getEntity(it.first) }
            ?: level.entitiesForRendering().firstOrNull { it is net.minecraft.world.entity.monster.zombie.Zombie && isWatcherHead(it) })
        if (e == null) { watcherAt = null; return }
        watcherAt = e.id to e.position()
        if (prev == null || prev.first != e.id || serverTicks - handle.tick < 45) return
        if (e.position().distanceTo(prev.second) > 0.001) {
            moments.onWatcherMoved(now())
            subs.onWatcherMoved(now())
        }
    }

    private val WATCHER_SKINS = listOf("5662b6fb4b8b", "2739d7f4e66a", "bf6e1e7ed365", "4cec40008e1c", "b37dd18b5983", "f5f0d78fe38d", "51967db5e319", "9fd61e8055f6", "e5c1dc47a04c")

    private fun isWatcherHead(e: net.minecraft.world.entity.Entity): Boolean {
        val head = (e as? net.minecraft.world.entity.LivingEntity)?.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD) ?: return false
        val tex = head.texture ?: return false
        val decoded = runCatching { String(java.util.Base64.getDecoder().decode(tex)) }.getOrNull() ?: return false
        return WATCHER_SKINS.any { it in decoded }
    }

    private fun distanceTo(e: net.minecraft.world.entity.Entity): Double = mc.player?.distanceTo(e)?.toDouble() ?: 0.0

    /** The blood rush HUD's lines, at its detail level; none in the boss with Hide In Boss on. */
    private fun bloodLines(): List<String> {
        if (bloodHideInBoss.enabled && DungeonUtils.inBoss) return emptyList()
        return blood.lines(bloodLevel.value, now(), totalRow.enabled)
    }

    /**
     * The run's pace ([SplitPace]) against the Pace targets - each split's setting, else the PB, else
     * its dark green time - on F7 once the run has started; null otherwise (no dark green times off F7).
     */
    fun pace(now: Stamp = now()): SplitPace.Clocks? {
        if (DungeonUtils.floor?.name != "F7") return null
        val splits = tracker.splits()
        if (splits.isEmpty()) return null
        return SplitPace.pace(splits, { label -> subs.forSplit(label).zip(subs.idsForSplit(label)) { s, id -> SplitPace.Sub(id, s) } }, now,
            { label -> OdinSplitsLook.paceTarget(label, false) })
    }

    /** Splits whose best this run has already been looked at. */
    private val bestsChecked = HashSet<String>()

    /** False for a P3 Sim run: the phases before its start are Pace targets, not times. */
    private var realRun = true

    /**
     * Each split as it ends, as a best for its floor (F7, M7) when it is one - every split, the
     * portal too, whatever the splits HUD shows: Pace's targets are these. Only a split that ran
     * from its own line to the next split's (none missed in between) is a time.
     */
    private fun recordSplitBests() {
        val floor = DungeonUtils.floor?.name?.takeIf { it == "F7" || it == "M7" } ?: return
        if (!realRun) return
        val splits = tracker.splits()
        splits.forEachIndexed { i, split ->
            val stop = split.stop ?: return@forEachIndexed
            if (!bestsChecked.add(split.label)) return@forEachIndexed
            val order = SplitPace.ORDER.indexOf(split.label)
            val next = splits.getOrNull(i + 1)
            val whole = if (next != null) SplitPace.ORDER.indexOf(next.label) == order + 1 else order == SplitPace.ORDER.lastIndex
            val id = SplitPace.SPLIT_IDS[split.label] ?: return@forEachIndexed
            if (!whole) return@forEachIndexed
            recordBest(floor, id, SubSplitGrades.value(id, stop.realMs - split.start.realMs, (stop.tick - split.start.tick).toLong()), true)
        }
    }

    /** Lag as of each of the last second's server ticks, newest last (server ticks come on the network thread). */
    private val lagSamples = ArrayDeque<Long>()
    /** Lag has reached a tick (50 ms) this run: it stays up from then on. */
    private var lagShown = false

    /**
     * Time lost to lag so far on the tick-timed splits, or null while it isn't shown (before the
     * run starts, and until it first reaches a tick). Taken as each server tick comes, not as of
     * now - between ticks it climbed by up to 50 ms and fell back on each, flickering on and off -
     * and the lowest of the last second's, since a tick arriving late over the network isn't lag.
     */
    fun lag(): Long? = synchronized(lagSamples) { lagSamples.minOrNull()?.takeIf { lagShown } }

    private fun sampleLag() {
        val splits = tracker.splits().takeIf { it.isNotEmpty() } ?: return
        val ms = SplitPace.lag(splits, now())
        synchronized(lagSamples) {
            lagSamples.addLast(ms)
            while (lagSamples.size > 20) lagSamples.removeFirst()
            if ((lagSamples.minOrNull() ?: 0) >= 50) lagShown = true
        }
    }

    /** A best kept here, for Odin's own splits too ([OdinSplitsLook]). */
    fun bestOf(floor: String, id: String): Long? = validBest(id, bests(floor)[id])

    /** Records a finished [value] of [id] as the best on [floor] when it is one. */
    fun recordBest(floor: String, id: String, value: Long, finished: Boolean) {
        if (!finished || !SubSplitGrades.canBeBest(id, value)) return
        val bests = bests(floor)
        if (value < (validBest(id, bests[id]) ?: Long.MAX_VALUE)) { bests[id] = value; saveBests(floor, bests) }
    }

    /** A kept best, unless it is under its step's floor (kept before the floor was raised): then none. */
    private fun validBest(id: String, best: Long?): Long? = best?.takeIf { SubSplitGrades.canBeBest(id, it) }

    private fun bests(floor: String?): MutableMap<String, Long> = SubSplitGrades.parseBests(
        when (floor) { "F7" -> bestsF7; "M7" -> bestsM7; else -> "" })

    private fun saveBests(floor: String?, bests: Map<String, Long>) {
        val text = SubSplitGrades.formatBests(bests)
        when (floor) { "F7" -> bestsF7 = text; "M7" -> bestsM7 = text; else -> return }
        com.odtheking.odin.features.ModuleManager.saveConfigurations()
    }

    private fun draw(gfx: GuiGraphicsExtractor, lines: List<String>): Pair<Int, Int> {
        if (lines.isEmpty()) return 0 to 0
        // The compact blood rush: door | key, name, total - the times right-aligned so they line
        // up, the name left-aligned; the cells carry their own spacing and the door its bar.
        if (lines.any { '\t' in it }) return table(gfx, lines, right = setOf(0, 1, 3))
        lines.forEachIndexed { i, line -> gfx.text(line, 0, i * LINE_HEIGHT, Colors.WHITE, shadow = true) }
        return lines.maxOf { mc.font.width(it) } to lines.size * LINE_HEIGHT
    }

    /**
     * Tab-separated rows drawn as a table: every column as wide as its widest cell, left-aligned
     * except the columns in [right]. Text padded with spaces cannot do this — a digit and a space
     * are different widths.
     */
    private fun table(gfx: GuiGraphicsExtractor, lines: List<String>, right: Set<Int>): Pair<Int, Int> {
        val rows = lines.map { it.split('\t') }
        val cols = rows.maxOf { it.size }
        val widths = IntArray(cols) { c -> rows.maxOf { r -> r.getOrNull(c)?.let(mc.font::width) ?: 0 } }
        // Where each column starts.
        val starts = IntArray(cols)
        for (c in 1 until cols) starts[c] = starts[c - 1] + widths[c - 1]
        rows.forEachIndexed { i, row ->
            val y = i * LINE_HEIGHT
            row.forEachIndexed { c, cell ->
                if (cell.isEmpty()) return@forEachIndexed
                val x = if (c in right) starts[c] + widths[c] - mc.font.width(cell) else starts[c]
                gfx.text(cell, x, y, Colors.WHITE, shadow = true)
            }
        }
        return starts[cols - 1] + widths[cols - 1] to lines.size * LINE_HEIGHT
    }
}
