package com.engineerclient.splits

import com.engineerclient.EngineerClient
import com.odtheking.odin.clickgui.settings.Setting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
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
import com.odtheking.odin.utils.modMessage
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
    category = Category.custom("Engineer Client"),
    description = "A sub-split HUD per section of the run, and the Scorecard, on the real and server-tick clocks. The splits themselves are Odin's Splits (Look: Engineer Splits).",
) {

    private val CONTROL_CODES = Regex("§.")
    private val tracker = SplitTracker()
    private val subs = SubSplitTracker()
    private val detail = SplitDetail()
    private val boss = BossDetail(detail)
    private val blood = BloodRunDetail()

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

    private val LEVELS = listOf("Compact", "Detailed", "Debug")

    // The run's splits themselves are Odin's Splits now, in the Engineer Splits look
    // (OdinSplitsLook); [tracker] still times the phases the sub splits and scorecard hang off.

    private val scorecardHud by HUD("Scorecard Splits", "The whole run as a table: each split's total, then its sub splits.", true, 10, 150, 1f) { example ->
        if (example) return@HUD scorecard(this, listOf(
            "§a21.2\t§c3.5\t§c6.3\t§c2.2\t§c4.7\t§64.6", "§c62.0\t§723.4\t§54.8\t§c33.8", "§d3.1\t§53.0\t§60.0",
            "§525.2\t§32.2\t§62.0\t§36.5", "§b45.9\t§60.4\t§c0.1\t§63.5\t§c0.2", "§620.9\t§811.9\t§85.2\t§83.9\t§84.1",
            "§e7.7\t§51.2\t§32.5\t§c2.9", "§c30.7\t§a9.8",
        ))
        scorecard(this, card.rows(tracker.splits(), now(), blood.roomTicks(), blood.over, subs.forSplit(SplitTracker.TERMS)))
    }

    private val cardDebug by BooleanSetting("Scorecard Debug", false, desc = "Says in chat each moment the scorecard picks up, and what it read it from — for checking the new ones (portal, leaps, Goldor's first hit, Storm breaking free).")
    private val card = Scorecard().also { c -> c.onEvent = { what -> if (cardDebug) modMessage("§8[scorecard] §7$what") } }

    /**
     * Each section's settings together, in the order they show in the ClickGUI: its HUD with its own
     * on/off toggle, then under it its detail level and (blood rush only) the Total row toggle. The
     * HUDs are made up front because a HUD has to exist before the run that fills it.
     */
    private val levels = HashMap<Section, SelectorSetting>()
    private val huds = HashMap<Section, HUDSetting>()
    private lateinit var totalRow: BooleanSetting
    private lateinit var bloodHideInBoss: BooleanSetting

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
                    ) else listOf("${s.colour}${s.name}: §68.12s §8| §52.28s §8| §c11.52s"))
                    draw(this, subLines(s))
                }
            )
            levels[s] = registerSetting(SelectorSetting("${s.name} Detail", "Compact", LEVELS, desc = "How much the ${s.name} sub-split HUD shows. Debug adds every extra moment known about it."))
                .withDependency { hudOn(s) }
            if (s.window == SplitTracker.OPEN) totalRow = registerSetting(
                BooleanSetting("Blood Rush Total Row", true, desc = "The averages row at the bottom of the compact blood rush splits.")
            ).withDependency { hudOn(s) && level(s) == BloodRunDetail.Level.COMPACT }
            if (s.window == SplitTracker.OPEN) bloodHideInBoss = registerSetting(
                BooleanSetting("Blood Rush Hide In Boss", false, desc = "Hides the blood rush sub splits once you are in the boss.")
            ).withDependency { hudOn(s) }
        }
    }

    private fun level(s: Section) = BloodRunDetail.Level.entries[levels[s]?.value ?: 0]

    // What the world shows, watched only while it can matter.
    private val barriers = mutableListOf<Pair<Int, Int>>()
    private val cleared = mutableListOf<Pair<Int, Int>>()
    private val keysSeen = HashSet<Int>()
    private val crystalsSeen = HashSet<Int>()
    private val watchedMobs = HashMap<Int, Pair<String, Stamp>>()

    init {
        on<LevelEvent.Load> {
            tracker.reset(); subs.reset(); detail.reset(); boss.reset(); blood.reset(); card.reset(); pinnedStorm = null
            goldorAt = null; goldorMoved = false; necronAt = null; goldorBar = null
            portalSeen = false; goldorHitNoted = false; coreUnseenNoted = false; watcherAt = null; watcherNotSeenNoted = false
            maxorAt = null; maxorCheck = null
            barriers.clear(); cleared.clear(); keysSeen.clear(); crystalsSeen.clear(); watchedMobs.clear()
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
            // Simon Says: a button on the device's face, or its start button, pressed.
            if (open(SplitTracker.TERMS) && pos.x == 110 && updated.block == Blocks.STONE_BUTTON &&
                old.block == Blocks.STONE_BUTTON && updated.getValue(BlockStateProperties.POWERED)
            ) {
                val face = pos.y in 120..123 && pos.z in 92..95
                if (face || (pos.y == 121 && pos.z == 91)) boss.onSimonPress(now(), nearestTeammate(110.5, pos.y + 0.5, pos.z + 0.5))
            }
        }

        on<TickEvent.End> {
            if (!DungeonUtils.inDungeons) return@on
            if (barriers.size >= DoorBlocks.DOOR_BLOCKS) door(barriers) { at, a, b -> blood.onDoorStart(at, a, b) }
            if (cleared.size >= DoorBlocks.DOOR_BLOCKS) door(cleared) { at, a, b -> blood.onDoorDown(at, a, b) }
            barriers.clear(); cleared.clear()

            // The key is an armor stand named "Wither Key"; it appears where the last mob died.
            if (blood.active) for (e in level.entitiesForRendering()) {
                if (e !is ArmorStand || e.id in keysSeen) continue
                val name = e.customName?.string ?: continue
                if (KEY.containsMatchIn(name)) { keysSeen += e.id; blood.onKeySpawned(now(), distanceTo(e)) }
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
                }
                // The Watcher's mobs are player entities that are not on the team.
                e is Player && open(SplitTracker.BLOOD) && e.name.string !in teamNames() -> {
                    val name = e.name.string.trim()
                    val at = now()
                    if (watchedMobs.put(e.id, name to at) == null) boss.onMobSpawn(at, name, distanceTo(e))
                }
            }
        }
        on<EntityEvent.Remove> {
            if (entity is WitherBoss && open(SplitTracker.MAXOR)) {
                card.onMaxorGone(now())
                val d = distanceTo(entity)
                if (d <= 48) subs.onMaxorDead(now())
                boss.extra(SplitTracker.MAXOR, now(), "§5wither gone", BossDetail.blocks(d) + " away" +
                    if (d > 48) " - probably out of view, not his death" else " - his death, 1-2 s before Storm speaks")
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
     * fight starts (148-223 ticks in, recorded), and the first DPS ends when he is back on it.
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
    private fun door(blocks: List<Pair<Int, Int>>, sink: (Stamp, BloodRunDetail.MapRoom?, BloodRunDetail.MapRoom?) -> Unit) {
        for (d in DoorBlocks.doors(blocks)) sink(now(), room(d.a.first, d.a.second), room(d.b.first, d.b.second))
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
    private val KEY = Regex("""(?:Wither|Blood) Key""")

    /** One timed thing in a section: its label, when it started, and how long it has run. */
    private class Row(val label: String, val at: Stamp, val ms: Long, val ticks: Long, val who: String = "", val note: String = "")

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
        if (moving && subs.waitingForMaxorMove) subs.onMaxorMoved(now())
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
        val boss = subs.forSplit(s.window)
        // In Debug each boss step says how it ended: the line, timed wait or check that started the next.
        val ends = subs.endSources(s.window)
        // Maxor's are each the time since Maxor started, at the end of that step (so far, for the
        // one running): where in the fight each stun and DPS landed, rather than how long each took.
        val fromStart = s.window == SplitTracker.MAXOR
        val steps = boss.mapIndexed { i, st ->
            val stop = st.stop ?: now
            val from = if (fromStart) split.start else st.start
            Row(st.label, st.start, stop.realMs - from.realMs, (stop.tick - from.tick).toLong(), note = "ended by " + ends.getOrElse(i) { "?" })
        } + detail.lines(s.window).filter { it.step }.map { e -> since(e.at).let { Row(e.label, e.at, it.ms, it.ticks, note = e.note) } }

        if (level == BloodRunDetail.Level.COMPACT) {
            if (steps.isEmpty()) return emptyList()
            return listOf(s.colour + s.name + ": " + steps.joinToString(" §8| ") {
                it.label.take(2).replace('&', '§') + SplitFormat.seconds(it.ms)
            })
        }

        val debug = level == BloodRunDetail.Level.DEBUG
        var rows = steps
        if (debug) {
            rows = rows + detail.lines(s.window).filter { !it.step }.map { e -> since(e.at).let { Row(e.label, e.at, it.ms, it.ticks, e.who, e.note) } }
        }
        val out = rows.sortedBy { it.at.realMs }.map { r ->
            SplitFormat.line(r.label, r.ms, r.ticks) + (if (r.who.isEmpty()) "" else " §7" + r.who) +
                (if (debug && r.note.isNotEmpty()) " §8· " + r.note else "")
        }.toMutableList()
        if (debug) out += debugFooter(s, split, now)
        return out
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
        SplitTracker.ANIMATION to "\"All this, for nothing...\"",
    )
    private val NEXT_SPLIT = mapOf(
        SplitTracker.BLOOD to SplitTracker.PORTAL, SplitTracker.PORTAL to SplitTracker.MAXOR, SplitTracker.MAXOR to SplitTracker.STORM,
        SplitTracker.STORM to SplitTracker.TERMS, SplitTracker.TERMS to SplitTracker.GOLDOR, SplitTracker.GOLDOR to SplitTracker.NECRON,
        SplitTracker.NECRON to SplitTracker.ANIMATION,
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
