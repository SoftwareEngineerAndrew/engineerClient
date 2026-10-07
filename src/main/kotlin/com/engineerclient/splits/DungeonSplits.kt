package com.engineerclient.splits

import com.engineerclient.EngineerClient
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ActionSetting
import com.odtheking.odin.clickgui.settings.impl.StringSetting
import com.odtheking.odin.clickgui.settings.impl.HUDSetting
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
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.protocol.game.ClientboundBossEventPacket
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.item.PrimedTnt
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.BlockStateProperties

/**
 * One sub-split HUD per section of the run (the splits themselves are Odin's Splits, in the Engineer
 * Splits look - see [OdinSplitsLook]), each with a dropdown for how much it shows:
 *
 *  - Compact: one row, the section's times left to right, `Name: 1.52s | 0.21s | ...`.
 *  - Detailed: the same, vertical and labelled, `Move > 8.12s (8.00s)`.
 *  - Debug: Detailed plus every extra moment known about the section.
 *
 * [SplitTracker] times the splits, [SubSplitTracker] the 25 boss steps, [BloodRunDetail] the rush
 * room by room and [BossDetail] everything else; this module feeds them chat, the two clocks and
 * what it sees in the world, and draws the result.
 */
object DungeonSplits : Module(
    name = "Sub Splits",
    category = Category.custom("Engineer Client", 860, 10),
    description = "A sub-split HUD per section of the run, and the Scorecard, on the real and server-tick clocks. The splits themselves are Odin's Splits (Look: Engineer Splits).",
    key = null,
) {

    private val CONTROL_CODES = Regex("§.")
    private val tracker = SplitTracker()
    private val subs = SubSplitTracker()
    private val detail = SplitDetail()
    private val boss = BossDetail(detail)
    private val blood = BloodRunDetail()
    private val necronCue = NecronHitCue()

    private var serverTicks = 0
    private fun now() = Stamp(System.currentTimeMillis(), serverTicks)

    /** One sub-split HUD: its name, the split whose window it covers, and the colour of its name. */
    private class Section(val name: String, val window: String, val colour: String)

    private val SECTIONS = listOf(
        Section("Blood Rush", SplitTracker.OPEN, "§a"),
        Section("Watcher", SplitTracker.BLOOD, "§c"),
        Section("Portal", SplitTracker.PORTAL, "§d"),
        // The phase headers' colours from EngineerSubSplits.
        Section("Maxor", SplitTracker.MAXOR, "§a"),
        Section("Storm", SplitTracker.STORM, "§b"),
        Section("Terminals", SplitTracker.TERMS, "§6"),
        Section("Goldor", SplitTracker.GOLDOR, "§e"),
        Section("Necron", SplitTracker.NECRON, "§c"),
    )

    // The run's splits themselves are Odin's Splits now, in the Engineer Splits look
    // (OdinSplitsLook); [tracker] still times the phases the sub splits and scorecard hang off.

    private val scorecardHud by HUD("Scorecard Splits", "The whole run as a table: each split's total, then its sub splits.", true, 10, 150, 1f) { example ->
        if (example) return@HUD scorecard(this, listOf(
            "§a21.2\t§c3.5\t§c6.3\t§c2.2\t§c4.7\t§64.6", "§e63.40\t§e22.60\t§e4.10\t§e36.30\t§e0.40", "§d3.1\t§53.0\t§60.0",
            "§e25.85\t§29.75\t§20.55\t§210.15\t§e0.30\t§75.10", "§c46.40\t§734.35\t§20.60\t§c0.45\t§e4.60\t§e1.30\t§60.10\t§75.10",
            "§e47.20\t§e12.90\t§a9.40\t§c13.80\t§e8.90", "§e7.90\t§03.40\t§24.50",
            "§230.35\t§77.95\t§e0.70\t§77.85\t§e3.30\t§60.05\t§77.55\t§73.10",
            "§3Pace 4:58 §8(4:57)", "§8Lag §71.35s",
        ))
        val now = now()
        val rows = card.rows(tracker.splits(), now, blood.roomTicks(), blood.over, subs.forSplit(SplitTracker.TERMS)) { scorecardCells(it, now) }
        // Pace against your targets (F7: your PBs, else the dark green times), real time first; and the time lost to lag.
        val extra = if (rows.isEmpty()) emptyList() else listOfNotNull(
            pace(now)?.let { "§3Pace " + SplitPace.mss(it.ms) + " §8(" + SplitPace.mss(it.ticks * 50) + ")" },
            "§8Lag §7" + SplitFormat.seconds(SplitPace.lag(tracker.splits(), now)),
        )
        scorecard(this, rows + extra)
    }

    /** Each boss sub split's best time, per floor (SubSplitGrades: ticks, or ms for the real-time ones). */
    private var bestsF7 by StringSetting("Sub Split Bests F7", "", 2048, desc = "", placeholder = "").hide()
    private var bestsM7 by StringSetting("Sub Split Bests M7", "", 2048, desc = "", placeholder = "").hide()
    private val resetBests by ActionSetting("Reset Sub Split Bests", desc = "Forgets every boss sub split's best time (the gold ones), on F7 and M7.") {
        bestsF7 = ""; bestsM7 = ""
        com.odtheking.odin.features.ModuleManager.saveConfigurations()
        EngineerClient.msg("§7Sub split bests cleared.")
    }

    private val cardDebug by BooleanSetting("Scorecard Debug", false, desc = "Says in chat each moment the scorecard picks up, and what it read it from — for checking the new ones (portal, leaps, Goldor's first hit, Storm breaking free).")
    private val card = Scorecard().also { c -> c.onEvent = { what -> if (cardDebug) EngineerClient.msg("§8[scorecard] §7$what") } }

    /**
     * Each section's settings together, in the order they show in the ClickGUI: its HUD with its own
     * on/off toggle, then under it its detail level and (blood rush only) the Total row toggle. The
     * HUDs are made up front because a HUD has to exist before the run that fills it.
     */
    private val levels = HashMap<Section, SelectorSetting<BloodRunDetail.Level>>()
    private val huds = HashMap<Section, HUDSetting>()
    private lateinit var totalRow: BooleanSetting
    private lateinit var bloodHideInBoss: BooleanSetting
    private lateinit var hitCue: BooleanSetting

    /** The Necron hit cue, under his sub splits while his fight is on. */
    private fun cueLines(s: Section): List<String> =
        if (s.window != SplitTracker.NECRON || !hitCue.enabled) emptyList() else listOfNotNull(necronCue.line(serverTicks))

    /** A section's HUD is on: its detail settings only show then. */
    private fun hudOn(s: Section) = huds[s]?.value?.enabled == true

    init {
        for (s in SECTIONS) {
            // The HUD toggle first, its detail settings under it.
            huds[s] = registerSetting(
                HUD("${s.name} Sub Splits", "What happened inside ${s.name}.", true, 0, 0, 1f) { example ->
                    if (example) return@HUD draw(this, if (s.window == SplitTracker.OPEN) listOf(
                        "§70.52s §8| \t§c1.73s \t§5Hallway: \t§62.31s",
                        "\t§411.73s \t§dDino: \t§622.31s",
                    ) else listOf("${s.colour}${s.name}: §68.12s §8| §52.28s §8| §c11.52s") +
                        (if (s.window == SplitTracker.NECRON && hitCue.enabled) listOf("§6§lHIT NOW §e20% §7· §f0.45s §7left") else emptyList()))
                    draw(this, subLines(s) + cueLines(s))
                }
            )
            levels[s] = registerSetting(SelectorSetting("${s.name} Detail", BloodRunDetail.Level.COMPACT, desc = "How much the ${s.name} sub-split HUD shows. Debug adds every extra moment known about it."))
                .withDependency { hudOn(s) }
            if (s.window == SplitTracker.OPEN) totalRow = registerSetting(
                BooleanSetting("Blood Rush Total Row", true, desc = "The averages row at the bottom of the compact blood rush splits.")
            ).withDependency { hudOn(s) && level(s) == BloodRunDetail.Level.COMPACT }
            if (s.window == SplitTracker.NECRON) hitCue = registerSetting(
                BooleanSetting("Necron Hit Cue", true, desc = "A line under the Necron sub splits saying when to hit him: his 3 hits that matter (20%, 55%, 20%), each counted down, called while its window is open, and marked on time or late. Other hits don't change the time.")
            ).withDependency { hudOn(s) }
            if (s.window == SplitTracker.OPEN) bloodHideInBoss = registerSetting(
                BooleanSetting("Blood Rush Hide In Boss", false, desc = "Hides the blood rush sub splits once you are in the boss.")
            ).withDependency { hudOn(s) }
        }
    }

    private fun level(s: Section) = levels[s]?.value ?: BloodRunDetail.Level.COMPACT

    // What the world shows, watched only while it can matter.
    private val barriers = mutableListOf<Pair<Int, Int>>()
    private val cleared = mutableListOf<Pair<Int, Int>>()
    private val keysSeen = HashSet<Int>()
    private val crystalsSeen = HashSet<Int>()
    private val watchedMobs = HashMap<Int, Pair<String, Stamp>>()

    /** Forgets the run (world load, or a P3 Sim restart). */
    private fun resetRun() {
        bestsChecked.clear(); realRun = true
        tracker.reset(); subs.reset(); detail.reset(); boss.reset(); blood.reset(); card.reset(); necronCue.reset(); pinnedStorm = null
        goldorAt = null; goldorMoved = false; necronAt = null; necronId = null; goldorBar = null
        portalSeen = false; goldorHitNoted = false; coreUnseenNoted = false; watcherAt = null; watcherNotSeenNoted = false
        maxorAt = null; maxorCheck = null
        barriers.clear(); cleared.clear(); keysSeen.clear(); crystalsSeen.clear(); watchedMobs.clear(); necronTnt.clear()
    }

    /**
     * The P3 Sim starting a fight at [label]'s phase (client thread, before its lines arrive): a
     * fresh run, in Odin's Splits ([SimOdinSplits]) and here (the scorecard), whose earlier phases
     * are your Pace targets. [termsDone]: sections already done when starting partway through the
     * terminals (now, as no Goldor line comes then).
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
        // ODIN-03: a P3 / S1 start comes with Storm's end (5.1 s to Goldor's "Who dares" line), and on
        // Hypixel Odin's Storm row is running through it: start Odin there, that far into Storm (your
        // Storm Pace target, or its dark green - 41.3 s since Hypixel's boss update, was a fixed 46.2 s -
        // less the lead-in) so "Storm took" reads a real time. The 5.1 s is the sim's (p3sim Fight):
        // the game's is 3.1 s since the update.
        if (label == SplitTracker.TERMS && termsDone == 0) {
            val storm = SimOdinSplits.odinName(SplitTracker.STORM)
            if (storm != null) {
                val whole = SimOdinSplits.target(storm).takeIf { it > 0 } ?: SplitPace.ref(SplitTracker.STORM)?.ms ?: 41_300L
                SimOdinSplits.start(storm, (whole - 5_100L).coerceAtLeast(0L))
            }
        } else SimOdinSplits.odinName(label)?.let { SimOdinSplits.start(it, headClock.ms) }
        if (termsDone > 0) {
            val at = now()
            tracker.startAt(label, before, at, headClock)
            // The sections before at your Pace times, so the section steps run too.
            if (head.size == termsDone) {
                val back = IntArray(termsDone + 1)
                for (s in termsDone downTo 1) back[s - 1] = back[s] + head[s - 1].ticks.toInt()
                subs.startTerms(termsDone + 1, back.map { at.minus(it) })
            }
        } else tracker.startAt(label, before)
    }

    private fun Stamp.minus(ticks: Int) = Stamp(realMs - ticks * 50L, tick - ticks)

    init {
        on<LevelEvent.Load> {
            resetRun()
            serverTicks = 0
        }

        // Odin's server tick: the server's own clock, which falls behind when it lags.
        on<TickEvent.Server> { serverTicks++; subs.onServerTick() }

        // Chat straight off the network, before any mod can hide it — chat cleaners drop exactly
        // the terminal and gate lines the splits are timed from.
        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (overlay) return@onReceive
            val text = content.string.replace(CONTROL_CODES, "")
            val at = now()
            EngineerClient.mc.execute {
                EngineerClient.safely("splits chat") {
                    if (!DungeonUtils.inDungeons) return@safely
                    if (text in MAXOR_LASER) { maxorCheck = at.tick + 20 to false; maxorCheckLine = at.tick }
                    else if (text == MAXOR_ENRAGED) { maxorCheck = at.tick + 20 to true; maxorCheckLine = at.tick }
                    tracker.onChat(text, at)
                    card.onChat(text, at)
                    subs.onChat(text, at)
                    necronCue.onChat(text, at.tick)
                    boss.onChat(text, at)
                    blood.onChat(text, at)
                }
            }
        }

        // A door falling: its 36 blocks turn to barrier as it starts, and those barriers to air
        // when it is down. Nothing else in the rush does either 36 at a time.
        on<BlockUpdateEvent> {
            // The portal out of the blood room opening, a few seconds after the Watcher lets you go.
            if (open(SplitTracker.PORTAL) && updated.block == Blocks.NETHER_PORTAL) {
                card.onPortal(now())
                if (!portalSeen) {
                    portalSeen = true
                    boss.extra(SplitTracker.PORTAL, now(), "§dportal appeared", "its blocks seen - out of render distance this is missing")
                }
            }
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
            // Simon Says: a button on the device's face, or its start button, pressed.
            if (open(SplitTracker.TERMS) && pos.x == 110 && updated.block == Blocks.STONE_BUTTON &&
                old.block == Blocks.STONE_BUTTON && updated.getValue(BlockStateProperties.POWERED)
            ) {
                val face = pos.y in 120..123 && pos.z in 92..95
                if (face || (pos.y == 121 && pos.z == 91)) {
                    val who = nearestTeammate(110.5, pos.y + 0.5, pos.z + 0.5)
                    boss.onSimonPress(now(), who)
                }
            }
        }

        on<TickEvent.End> {
            EngineerClient.safely("sim odin splits") { SimOdinSplits.tick() }
            if (!DungeonUtils.inDungeons) return@on
            EngineerClient.safely("split bests") { recordSplitBests() }
            if (barriers.size >= DoorBlocks.DOOR_BLOCKS) door(barriers, "start") { at, a, b -> blood.onDoorStart(at, a, b) }
            if (cleared.size >= DoorBlocks.DOOR_BLOCKS) door(cleared, "down") { at, a, b -> blood.onDoorDown(at, a, b) }
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
                necron.positionCodec.base.let {
                    subs.onNecronPosition(now(), it.distanceTo(NECRON_MID))
                    necronCue.onPosition(serverTicks, it.distanceTo(NECRON_MID))
                }
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
                if (subs.watchingCore || card.waitingForCore) boss.extra(SplitTracker.GOLDOR, now(), "§5everyone in", "every teammate seen inside the core")
                subs.onEveryoneInCore(now(), "every teammate seen inside the core")
                card.onEveryoneInCore(now(), "players in the core box")
            } else if (inCore == null && !coreUnseenNoted && (subs.watchingCore || card.waitingForCore)) {
                coreUnseenNoted = true
                boss.extra(SplitTracker.GOLDOR, now(), "§8can't see everyone", "out of render distance: " + unseenTeammates(level).joinToString() + " - waiting on Goldor moving instead")
            }
            // The backup, only when the box can't tell (someone out of render distance): Goldor
            // starting to move, which he does once everyone is in.
            if (open(SplitTracker.GOLDOR) && card.waitingForCore) watchGoldor(level, trusted = inCore == null) else goldorAt = null
            if (open(SplitTracker.NECRON) && card.necronWatch) watchNecron(level) else necronAt = null

            // Maxor: his wither starting to move ends Move; after a laser or enrage line, Debug
            // notes when he was seen freezing or moving again.
            if (open(SplitTracker.MAXOR)) watchMaxor(level) else maxorAt = null

            // The Watcher moving off his starting spot, once his first spawns are out.
            if (open(SplitTracker.BLOOD) && card.waitingForWatcher) watchWatcher(level)

            // Storm pinned by a crush: the DPS window is over when he moves off it.
            if (open(SplitTracker.STORM) && card.stormPinned) watchStorm(level) else pinnedStorm = null
        }

        on<EntityEvent.Add> {
            val e = entity
            when {
                e is EndCrystal && open(SplitTracker.MAXOR) && crystalsSeen.add(e.id) -> {
                    // Fresh crystals sit on the upper platforms (y 238), placed ones on the lower (y 224).
                    val placed = e.y < 231
                    boss.onCrystal(now(), placed, if (placed) nearestTeammate(e.x, e.y, e.z) else null)
                    // Back on top 41 ticks after a laser hit: a hit an ability kept quiet.
                    if (!placed) subs.onTopCrystal(now())
                }
                // Necron's death: the burst of TNT he dies in.
                e is PrimedTnt && open(SplitTracker.NECRON) -> onNecronTnt(now())
                // The Watcher's mobs are player entities that are not on the team.
                e is Player && open(SplitTracker.BLOOD) && e.name.string !in teamNames() -> {
                    val name = e.name.string.trim()
                    val at = now()
                    if (watchedMobs.put(e.id, name to at) == null) {
                        boss.onMobSpawn(at, name, distanceTo(e)); subs.onBloodMobSpawn(at)
                    }
                }
            }
        }
        on<EntityEvent.Remove> {
            // Necron's wither going, close by: the backup for his death when the TNT wasn't seen.
            if (entity is WitherBoss && open(SplitTracker.NECRON) && entity.id == necronId && distanceTo(entity) <= 48) {
                necronDead(now().minus(NECRON_GONE), "his wither going, $NECRON_GONE ticks after his death - its TNT burst wasn't seen")
            }
            if (entity is WitherBoss && open(SplitTracker.MAXOR)) {
                card.onMaxorGone(now())
                val d = distanceTo(entity)
                if (d <= 48) subs.onMaxorDead(now())
                boss.extra(SplitTracker.MAXOR, now(), "§5wither gone", BossDetail.blocks(d) + " away" +
                    if (d > 48) " - probably out of view, not his death" else " - his death, under 1 s before Storm speaks")
            }
            val (name, spawned) = watchedMobs.remove(entity.id) ?: return@on
            val at = now()
            boss.onMobGone(at, name, at.realMs - spawned.realMs, distanceTo(entity))
        }

        // A wither hurt: Goldor's hits and Necron's first. Hits carry no attacker, so uncredited.
        onReceive<ClientboundDamageEventPacket> {
            val id = entityId()
            EngineerClient.mc.execute {
                val e = EngineerClient.mc.level?.getEntity(id) as? WitherBoss ?: return@execute
                val at = now()
                if (open(SplitTracker.GOLDOR)) { boss.onBossHit(SplitTracker.GOLDOR, at); goldorHit(at, "damage packet (he was in view)") }
                else if (open(SplitTracker.NECRON) && e.isAlive) boss.onBossHit(SplitTracker.NECRON, at)
            }
        }

        // Goldor's boss bar: the first time it drops after the core opens is his first hit.
        onReceive<ClientboundBossEventPacket> {
            dispatch(object : ClientboundBossEventPacket.Handler {
                override fun add(id: java.util.UUID, name: net.minecraft.network.chat.Component, progress: Float, color: net.minecraft.world.BossEvent.BossBarColor,
                                 overlay: net.minecraft.world.BossEvent.BossBarOverlay, darken: Boolean, music: Boolean, fog: Boolean) {
                    if (name.string.contains("Goldor")) goldorBar = id to progress
                }
                override fun updateName(id: java.util.UUID, name: net.minecraft.network.chat.Component) {
                    if (name.string.contains("Goldor") && goldorBar?.first != id) goldorBar = id to 1f
                }
                override fun updateProgress(id: java.util.UUID, progress: Float) {
                    val bar = goldorBar ?: return
                    if (bar.first != id) return
                    val dropped = progress < bar.second - 0.0005f
                    goldorBar = id to progress
                    if (dropped) EngineerClient.mc.execute { if (open(SplitTracker.GOLDOR)) goldorHit(now(), "his boss bar dropping") }
                }
            })
        }

        // A wither's hurt sound while Goldor is up: the other way his first hit can show.
        onReceive<ClientboundSoundPacket> {
            val id = sound.value().location().path
            if (id != "entity.wither.hurt") return@onReceive
            EngineerClient.mc.execute { if (open(SplitTracker.GOLDOR)) goldorHit(now(), "a wither hurt sound") }
        }
    }

    /**
     * Every living teammate inside the core — the main way everyone-in is found: true, false (a
     * teammate you can see is outside), or null when someone is out of render distance and
     * everyone you can see is in, which the box can't decide. Your original box stopped at y 112,
     * but the fight goes down to the core's floor (players stood at y 64-90 in the recorded runs),
     * so it now reaches all the way down.
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

    /** Goldor's boss bar and its last progress. */
    @Volatile private var goldorBar: Pair<java.util.UUID, Float>? = null

    /** The TNT seen in the last 2 server ticks of Necron's fight, for his death's burst. */
    private val necronTnt = ArrayDeque<Stamp>()

    /**
     * A TNT appearing during Necron's fight. He dies in a burst of them, 3 or more within 2 server
     * ticks (10 split 9 + 1 across two ticks at times), which since Hypixel's boss update (5 Oct
     * 2026) is the clearest sign of his death: "All this, for nothing..." is never said. It came in
     * 37 of 38 F7 recordings since (the other had no TNT at all) and all 5 M7, EXTRA STATS 38-50
     * ticks after it on F7; the single TNT seen earlier in his fight (~236 and ~309 ticks in) never
     * come 3 at a time.
     */
    private fun onNecronTnt(at: Stamp) {
        necronTnt.addLast(at)
        while (necronTnt.first().tick < at.tick - (DEATH_BURST_TICKS - 1)) necronTnt.removeFirst()
        if (necronTnt.size < DEATH_BURST_TNT) return
        val first = necronTnt.first()
        necronTnt.clear()
        necronDead(first, "the burst of TNT he dies in")
    }

    /**
     * Necron dead at [at]. On F7 that is all: his split runs on to the run's end, there being no
     * end animation since the update. On M7 his fight is over and the Wither King's is next, so his
     * steps end here, and Odin's own Necron split (which ended on "All this, for nothing...") is
     * handed that line.
     */
    private fun necronDead(at: Stamp, how: String) {
        necronCue.onDeath()
        boss.extra(SplitTracker.NECRON, at, "§cdead", "$how - he no longer says \"All this, for nothing...\"")
        if (DungeonUtils.floor?.name?.startsWith("M") == true) {
            subs.onNecronDeath(at)
            OdinSplitsLook.onNecronDead()
        }
    }

    /** Necron's wither, as last found, for the backup when his burst is missed. */
    private var necronId: Int? = null

    private val DEATH_BURST_TNT = 3
    private val DEATH_BURST_TICKS = 2
    /**
     * His wither is removed 20-21 server ticks after the burst (24 of 24 F7 runs since the update
     * where it was seen go). The one recording with no TNT at all still had it, 19 before the score.
     */
    private val NECRON_GONE = 20

    /** Storm's wither and where the crush pinned him. */
    private var pinnedStorm: Pair<Int, net.minecraft.world.phys.Vec3>? = null

    /**
     * Storm after a crush: the wither nearest his name tag (or you, if the tag is out of sight),
     * and the moment he is a block and a half from where the crush caught him.
     */
    private fun watchStorm(level: net.minecraft.client.multiplayer.ClientLevel) {
        val pinned = pinnedStorm
        if (pinned == null) {
            val storm = bossWither(level, "Storm") ?: return
            pinnedStorm = storm.id to storm.position()
            return
        }
        val e = level.getEntity(pinned.first) ?: return
        val dx = e.x - pinned.second.x; val dz = e.z - pinned.second.z
        val dy = e.y - pinned.second.y
        // He does not move at all while he is being DPSed; any movement is the window over.
        if (dx * dx + dy * dy + dz * dz > 0.1 * 0.1) {
            card.onStormMoved(now()); pinnedStorm = null
            boss.extra(SplitTracker.STORM, now(), "§bmoved off the crush", "his wither seen moving " + BossDetail.blocks(distanceTo(e)) + " away")
        }
    }

    /** A boss's wither: the one nearest the name tag carrying [name], or nearest you without one. */
    private fun bossWither(level: net.minecraft.client.multiplayer.ClientLevel, name: String): WitherBoss? {
        val withers = level.entitiesForRendering().filterIsInstance<WitherBoss>()
        val tag = level.entitiesForRendering().firstOrNull { it is ArmorStand && it.customName?.string?.contains(name) == true }
        val anchor = tag ?: mc.player ?: return null
        val found = withers.minByOrNull { it.distanceToSqr(anchor) }
        return found
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
                card.onEveryoneInCore(now(), "Goldor moved, someone out of sight")
                subs.onEveryoneInCore(now(), "Goldor starting to move - someone was out of render distance, so the core box couldn't tell")
                boss.extra(SplitTracker.GOLDOR, now(), "§5everyone in", "Goldor started moving (0-5 ticks after the last one in, in the recordings)")
            }
            goldorMoved = true
        }
    }

    /** Necron and mid, where he starts his fight. */
    private var necronAt: Pair<Int, net.minecraft.world.phys.Vec3>? = null

    /**
     * Necron off mid and back: he stays on mid through his opening animation, leaves it when the
     * fight starts (81-84 ticks in since Hypixel's boss update, 159-164 before), and the first DPS
     * ends when he is back on it.
     */
    private fun watchNecron(level: net.minecraft.client.multiplayer.ClientLevel) {
        val at = necronAt
        if (at == null) { bossWither(level, "Necron")?.let { necronAt = it.id to it.position() }; return }
        val e = level.getEntity(at.first) ?: return
        val d = e.position().distanceTo(at.second)
        if (!card.necronOff && d > 0.5) {
            card.onNecronOffMid(now())
            boss.extra(SplitTracker.NECRON, now(), "§cleft mid", "his wither seen moving " + BossDetail.blocks(distanceTo(e)) + " away")
        } else if (card.necronOff && card.necronWatch && d < 0.2) {
            card.onNecronBackAtMid(now())
            boss.extra(SplitTracker.NECRON, now(), "§cback at mid", "his wither seen back where he started")
        }
    }

    private fun open(label: String) = tracker.split(label)?.stop == null && tracker.split(label) != null

    private fun teamNames(): Set<String> =
        DungeonUtils.dungeonTeammates.mapTo(HashSet()) { it.name }.also { set -> mc.player?.let { set += it.name.string } }

    private fun nearestTeammate(x: Double, y: Double, z: Double): String? {
        val team = teamNames()
        return mc.level?.players()?.filter { it.name.string in team }?.minByOrNull { it.distanceToSqr(x, y, z) }?.name?.string
    }

    /**
     * A door's blocks, handed to [sink] with the two rooms either side of it. A door sits halfway
     * between two map tiles, which are 32 blocks apart with the grid's first at -185.
     */
    /** Each door among [blocks] ([DoorBlocks]), handed to [sink] with the rooms either side of it. */
    private fun door(blocks: List<Pair<Int, Int>>, phase: String, sink: (Stamp, BloodRunDetail.MapRoom?, BloodRunDetail.MapRoom?) -> Unit) {
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
    private val KEY = Regex("""(?:Wither|Blood) Key""")

    /** One timed thing in a section: its label, when it started, and how long it has run. */
    private class Row(val label: String, val at: Stamp, val ms: Long, val ticks: Long, val who: String = "", val note: String = "",
                      /** A graded boss step: the colour of its time, and whether that time is real (else ticks). */
                      val grade: String? = null, val real: Boolean = false)

    /** Maxor's wither: its id, where it was last tick, and whether it was moving. */
    private var maxorAt: Triple<Int, net.minecraft.world.phys.Vec3, Boolean>? = null
    /** After a laser (freeze) or enrage (move) line: until when, and what to look for. */
    private var maxorCheck: Pair<Int, Boolean>? = null
    private var maxorCheckLine = 0

    /**
     * Maxor's wither, every tick of his split. He stands still through his intro and starts moving
     * 46 ticks after "DON'T DISAPPOINT ME" - that ends Move. A laser line freezes him 4 ticks later
     * and the enrage line gets him moving again 1-3 ticks later (every recorded run); the chat lines
     * are the moments themselves, so these only confirm them, in Debug.
     */
    private fun watchMaxor(level: net.minecraft.client.multiplayer.ClientLevel) {
        val prev = maxorAt
        val e = (prev?.let { level.getEntity(it.first) } ?: bossWither(level, "Maxor")) ?: run { maxorAt = null; return }
        val moving = prev != null && prev.first == e.id && e.position().distanceTo(prev.second) > 0.03
        maxorAt = Triple(e.id, e.position(), moving)
        if (prev == null || prev.first != e.id) return
        val check = maxorCheck ?: return
        if (serverTicks > check.first) {
            boss.extra(SplitTracker.MAXOR, now(), "§8not seen " + (if (check.second) "moving" else "freezing"), "his wither out of view, or it didn't happen")
            maxorCheck = null
        } else if (moving == check.second && moving != prev.third) {
            boss.extra(SplitTracker.MAXOR, now(), if (moving) "§5moving again" else "§5froze",
                "his wither seen, " + (serverTicks - maxorCheckLine) + " ticks after the line")
            maxorCheck = null
        }
    }

    /** The Watcher: his id and where he was last tick. */
    private var watcherAt: Pair<Int, net.minecraft.world.phys.Vec3>? = null
    private var watcherNotSeenNoted = false

    /**
     * The Watcher's move, the way Devonian times it: once his dialog is over ("Let's see how you
     * can handle this."), the first tick he moves at least 45 server ticks after that line - the
     * wait skips his settling right as he says it. 55-148 ticks after the line in the recorded runs,
     * depending on the camp, so there is nothing to count it from: without him in view it stays
     * blank, and Debug says so. He is the zombie in one of his skins (Odin's Blood Camp list).
     */
    private fun watchWatcher(level: net.minecraft.client.multiplayer.ClientLevel) {
        val handle = card.watcherHandle ?: return
        val prev = watcherAt
        val e = (prev?.let { level.getEntity(it.first) }
            ?: level.entitiesForRendering().firstOrNull { it is net.minecraft.world.entity.monster.zombie.Zombie && isWatcherHead(it) })
        if (e == null) {
            watcherAt = null
            if (!watcherNotSeenNoted && serverTicks - handle.tick >= 200) {
                watcherNotSeenNoted = true
                boss.extra(SplitTracker.BLOOD, now(), "§8watcher not in view", "his move can't be seen, so it stays blank")
            }
            return
        }
        watcherAt = e.id to e.position()
        if (prev == null || prev.first != e.id || serverTicks - handle.tick < 45) return
        if (e.position().distanceTo(prev.second) > 0.001) {
            card.onWatcherMoved(now(), "seen")
            subs.onWatcherMoved(now())
            boss.extra(SplitTracker.BLOOD, now(), "§5watcher moved", "seen, " + (serverTicks - handle.tick) + " ticks after \"handle this\" - " + BossDetail.blocks(distanceTo(e)) + " away")
        }
    }

    private val WATCHER_SKINS = listOf("5662b6fb4b8b", "2739d7f4e66a", "bf6e1e7ed365", "4cec40008e1c", "b37dd18b5983", "f5f0d78fe38d", "51967db5e319", "9fd61e8055f6", "e5c1dc47a04c")

    private fun isWatcherHead(e: net.minecraft.world.entity.Entity): Boolean {
        val head = (e as? net.minecraft.world.entity.LivingEntity)?.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD) ?: return false
        val tex = head.texture ?: return false
        val decoded = runCatching { String(java.util.Base64.getDecoder().decode(tex)) }.getOrNull() ?: return false
        return WATCHER_SKINS.any { it in decoded }
    }

    private val MAXOR_LASER = setOf("[BOSS] Maxor: THAT BEAM! IT HURTS! IT HURTS!!", "[BOSS] Maxor: YOU TRICKED ME!")
    private const val MAXOR_ENRAGED = "⚠ Maxor is enraged! ⚠"

    // Debug's one-time notes.
    private var portalSeen = false
    private var goldorHitNoted = false
    private var coreUnseenNoted = false

    /** Goldor's first hit, however it showed: the scorecard's, and Debug's note of how. */
    private fun goldorHit(at: Stamp, how: String) {
        card.onGoldorHit(at, how)
        if (!goldorHitNoted) { goldorHitNoted = true; boss.extra(SplitTracker.GOLDOR, at, "§efirst hit", how) }
    }

    private fun distanceTo(e: net.minecraft.world.entity.Entity): Double = mc.player?.distanceTo(e)?.toDouble() ?: 0.0

    /** Living teammates the game isn't showing you. */
    private fun unseenTeammates(level: net.minecraft.client.multiplayer.ClientLevel): List<String> =
        DungeonUtils.dungeonTeammates.filter { !it.isDead && (it.entity ?: level.players().firstOrNull { p -> p.name.string == it.name }) == null }.map { it.name }

    private fun subLines(s: Section): List<String> {
        val level = level(s)
        val now = now()
        if (s.window == SplitTracker.OPEN) {
            if (bloodHideInBoss.enabled && DungeonUtils.inBoss) return emptyList()
            return blood.lines(level, now, totalRow.enabled)
        }

        val split = tracker.split(s.window) ?: return emptyList()
        val since = { at: Stamp -> Row("", at, at.realMs - split.start.realMs, (at.tick - split.start.tick).toLong()) }

        // The boss's own steps each run until the next; the Watcher's and Portal's are moments,
        // timed from the start of the split.
        val steps = gradedSteps(s.window, split, now) +
            detail.lines(s.window).filter { it.step }.map { e -> since(e.at).let { Row(e.label, e.at, it.ms, it.ticks, note = e.note) } }

        if (level == BloodRunDetail.Level.COMPACT) {
            if (steps.isEmpty()) return emptyList()
            return listOf(s.colour + s.name + ": " + steps.joinToString(" §8| ") {
                if (it.grade != null) it.grade + SplitFormat.seconds(if (it.real) it.ms else it.ticks * 50)
                else it.label.take(2).replace('&', '§') + SplitFormat.seconds(it.ms)
            })
        }

        val debug = level == BloodRunDetail.Level.DEBUG
        var rows = steps
        if (debug) {
            rows = rows + detail.lines(s.window).filter { !it.step }.map { e -> since(e.at).let { Row(e.label, e.at, it.ms, it.ticks, e.who, e.note) } }
        }
        val out = rows.sortedBy { it.at.realMs }.map { r ->
            (if (r.grade != null) graded(r) else SplitFormat.line(r.label, r.ms, r.ticks)) + (if (r.who.isEmpty()) "" else " §7" + r.who) +
                (if (debug && r.note.isNotEmpty()) " §8· " + r.note else "")
        }.toMutableList()
        if (debug) out += debugFooter(s, split, now)
        return out
    }

    /**
     * [window]'s boss steps, each graded on its own clock (SubSplitGrades): bands from the recorded
     * F7 runs, gold for a best, gray for a step that never varies. A new best is saved here. In Debug
     * each step says how it ended: the line, timed wait or check that started the next.
     */
    private fun gradedSteps(window: String, split: Split, now: Stamp): List<Row> {
        val boss = subs.forSplit(window)
        val ends = subs.endSources(window)
        val ids = subs.idsForSplit(window)
        val floor = DungeonUtils.floor?.name
        val bests = bests(floor)
        var newBest = false
        val steps = boss.mapIndexed { i, st ->
            val stop = st.stop ?: now
            val ms = stop.realMs - st.start.realMs
            val ticks = (stop.tick - st.start.tick).toLong()
            val id = ids.getOrElse(i) { "" }
            val end = ends.getOrElse(i) { "?" }
            val value = SubSplitGrades.value(id, ms, ticks, (stop.tick - split.start.tick).toLong())
            // A step ended by a moment further on (the ones between unseen) is not a real time.
            val finished = st.stop != null && !end.contains("never seen")
            if (finished && floor != null && SubSplitGrades.canBeBest(id, value) && value < (validBest(id, bests[id]) ?: Long.MAX_VALUE)) {
                bests[id] = value; newBest = true
            }
            val grade = SubSplitGrades.colour(id, value, finished, validBest(id, bests[id]), floor == "F7", st.label.take(2).replace('&', '§'))
            Row(st.label, st.start, ms, ticks, note = "ended by $end", grade = grade, real = SubSplitGrades.clock(id) == SubSplitGrades.Clock.REAL)
        }
        if (newBest) saveBests(floor, bests)
        return steps
    }

    /**
     * The scorecard's row for a boss split: its total, graded like Odin's split (the best only read
     * here; Odin's split keeps it), then its graded sub splits, two decimals each. Null for the
     * rows the scorecard draws itself (the blood rush and the portal).
     */
    private fun scorecardCells(split: Split, now: Stamp): List<String>? {
        val id = SCORECARD_SPLITS[split.label] ?: return null
        val floor = DungeonUtils.floor?.name
        val stop = split.stop ?: now
        val ms = stop.realMs - split.start.realMs
        val ticks = (stop.tick - split.start.tick).toLong()
        val value = SubSplitGrades.value(id, ms, ticks)
        val real = SubSplitGrades.clock(id) == SubSplitGrades.Clock.REAL
        val total = SubSplitGrades.colour(id, value, split.stop != null, validBest(id, bests(floor)[id]), floor == "F7", split.label.take(2).replace('&', '§')) +
            SplitFormat.seconds(if (real) ms else ticks * 50).removeSuffix("s")
        return listOf(total) + gradedSteps(split.label, split, now).map {
            it.grade + SplitFormat.seconds(if (it.real) it.ms else it.ticks * 50).removeSuffix("s")
        }
    }

    private val SCORECARD_SPLITS = mapOf(
        SplitTracker.BLOOD to "split.blood", SplitTracker.MAXOR to "split.maxor", SplitTracker.STORM to "split.storm",
        SplitTracker.TERMS to "split.terms", SplitTracker.GOLDOR to "split.goldor", SplitTracker.NECRON to "split.necron",
    )

    /**
     * A graded boss step: its name in its own colour, then its time on the clock it is graded on, in
     * its grade's colour, and the other clock in brackets.
     */
    private fun graded(r: Row): String {
        val name = r.label.take(2).replace('&', '§') + r.label.drop(2)
        val main = if (r.real) r.ms else r.ticks * 50
        val other = if (r.real) r.ticks * 50 else r.ms
        return "$name §b> ${r.grade}${SplitFormat.seconds(main)} §8(§7${SplitFormat.seconds(other)}§8)"
    }

    /**
     * The run's pace ([SplitPace]) against your Pace targets - each split's box, else your PB, else
     * its dark green - on F7 once the run has started; null otherwise (no dark green times off F7).
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

    /** False for a P3 Sim run: the phases before its start are your targets, not times. */
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

    /** Time lost to lag so far on the tick-timed splits, or null before the run starts. */
    fun lag(now: Stamp = now()): Long? = tracker.splits().takeIf { it.isNotEmpty() }?.let { SplitPace.lag(it, now) }

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

    /**
     * Debug's closing lines for a section: the chat lines the split itself runs between, and how
     * far the server's clock fell behind real time over it - the one thing that moves every time in
     * it at once.
     */
    private fun debugFooter(s: Section, split: Split, now: Stamp): List<String> {
        val out = mutableListOf("§8· split: from ${SPLIT_STARTS[s.window]} to " +
            (if (split.stop == null) "now (running)" else SPLIT_STARTS[NEXT_SPLIT[s.window]] ?: "the next split"))
        val end = split.stop ?: now
        val lag = (end.realMs - split.start.realMs) - (end.tick - split.start.tick) * 50L
        out += if (kotlin.math.abs(lag) < 100) "§8· lag: none to speak of" else
            "§8· lag: server " + SplitFormat.seconds(kotlin.math.abs(lag)) + (if (lag > 0) " behind" else " ahead of") + " real time - the (bracketed) times are the server's"
        if (s.window in BOSS_SPLITS && subs.forSplit(s.window).isEmpty()) out += "§8· no steps: the boss's first line wasn't seen"
        return out
    }

    /** The chat line each split starts on (and so the one before it ends on). */
    private val SPLIT_STARTS = mapOf(
        SplitTracker.BLOOD to "the Watcher's first line",
        SplitTracker.PORTAL to "\"You have proven yourself\"",
        SplitTracker.MAXOR to "Maxor's first line",
        SplitTracker.STORM to "Storm's first line",
        SplitTracker.TERMS to "Goldor's first line",
        SplitTracker.GOLDOR to "\"The Core entrance is opening!\"",
        SplitTracker.NECRON to "Necron's first line",
        RUN_END to "the run's end (EXTRA STATS)",
    )
    private const val RUN_END = "end"
    private val NEXT_SPLIT = mapOf(
        SplitTracker.BLOOD to SplitTracker.PORTAL, SplitTracker.PORTAL to SplitTracker.MAXOR, SplitTracker.MAXOR to SplitTracker.STORM,
        SplitTracker.STORM to SplitTracker.TERMS, SplitTracker.TERMS to SplitTracker.GOLDOR, SplitTracker.GOLDOR to SplitTracker.NECRON,
        SplitTracker.NECRON to RUN_END,
    )
    private val BOSS_SPLITS = setOf(SplitTracker.MAXOR, SplitTracker.STORM, SplitTracker.TERMS, SplitTracker.GOLDOR, SplitTracker.NECRON)

    private fun draw(gfx: GuiGraphicsExtractor, lines: List<String>): Pair<Int, Int> {
        if (lines.isEmpty()) return 0 to 0
        // The compact blood rush: door | key, name, total - the times right-aligned so they line
        // up, the name left-aligned; the cells carry their own spacing and the door its bar.
        if (lines.any { '\t' in it }) return table(gfx, lines, right = setOf(0, 1, 3), barsFrom = Int.MAX_VALUE)
        lines.forEachIndexed { i, line -> gfx.text(line, 0, i * LINE_HEIGHT, Colors.WHITE, shadow = true) }
        return lines.maxOf { mc.font.width(it) } to lines.size * LINE_HEIGHT
    }

    /**
     * The scorecard: its first column (the splits) right-aligned, a light-grey bar before every
     * other — and after the first on every row, sub splits or not.
     */
    private fun scorecard(gfx: GuiGraphicsExtractor, lines: List<String>): Pair<Int, Int> =
        if (lines.isEmpty()) 0 to 0
        else table(gfx, lines.map { if ('\t' in it) it else it + "\t" }, rightFirst = true, barsFrom = 1, bar = "§7|", firstBarAlways = true)

    /**
     * Tab-separated rows drawn as a table: every column as wide as its widest cell and left-aligned
     * (the first right-aligned if [rightFirst]), with a `|` at the same x on every row in front of
     * each column from [barsFrom] on. Text padded with spaces cannot do this — a digit and a space
     * are different widths.
     */
    private fun table(gfx: GuiGraphicsExtractor, lines: List<String>, rightFirst: Boolean = false, barsFrom: Int = 2, bar: String = "§8|", firstBarAlways: Boolean = false, right: Set<Int> = emptySet()): Pair<Int, Int> {
        val rows = lines.map { it.split('\t') }
        val cols = rows.maxOf { it.size }
        val widths = IntArray(cols) { c -> rows.maxOf { r -> r.getOrNull(c)?.let(mc.font::width) ?: 0 } }
        val space = mc.font.width(" ")
        val barW = mc.font.width("|")
        // Where each column starts, with " | " in front of the ones that have a bar.
        val starts = IntArray(cols)
        for (c in 1 until cols) starts[c] = starts[c - 1] + widths[c - 1] + if (c < barsFrom) 0 else space * 2 + barW
        rows.forEachIndexed { i, row ->
            val y = i * LINE_HEIGHT
            if (firstBarAlways && cols > barsFrom) gfx.text(bar, starts[barsFrom] - space - barW, y, Colors.WHITE, shadow = true)
            row.forEachIndexed { c, cell ->
                if (cell.isEmpty()) return@forEachIndexed
                val x = if ((c == 0 && rightFirst) || c in right) starts[c] + widths[c] - mc.font.width(cell) else starts[c]
                gfx.text(cell, x, y, Colors.WHITE, shadow = true)
                if (c >= barsFrom && !(firstBarAlways && c == barsFrom)) gfx.text(bar, starts[c] - space - barW, y, Colors.WHITE, shadow = true)
            }
        }
        return maxOf(starts[cols - 1] + widths[cols - 1], if (firstBarAlways && cols > barsFrom) starts[barsFrom] else 0) to lines.size * LINE_HEIGHT
    }
}
