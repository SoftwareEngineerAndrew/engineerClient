package com.engineerclient.splits

import com.engineerclient.EngineerClient
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
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
import net.minecraft.client.gui.GuiGraphicsExtractor
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
 * The run's splits — a copy of the team's EngineerSplits — and one sub-split HUD per section of the
 * run, each with a dropdown for how much it shows:
 *
 *  - Compact: one row, the section's times left to right, `Name: 1.52s | 0.21s | ...`.
 *  - Detailed: the same, vertical and labelled, `Move > 8.12s (8.00s)`.
 *  - Extreme: Detailed plus every extra moment known about the section.
 *
 * [SplitTracker] times the splits, [SubSplitTracker] the 25 boss steps, [BloodRunDetail] the rush
 * room by room and [BossDetail] everything else; this module feeds them chat, the two clocks and
 * what it sees in the world, and draws the result.
 */
object DungeonSplits : Module(
    name = "Sub Splits",
    category = Category.custom("Engineer Client"),
    description = "EngineerSplits, and a sub-split HUD per section of the run on the real and server-tick clocks.",
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

    private val LEVELS = listOf("Off", "Compact", "Detailed", "Extreme")

    private val splitsHud by HUD("Splits", "EngineerSplits: the run, phase by phase.") { example ->
        if (example) return@HUD draw(this, listOf(
            "§3Pace §b> §33m 8.2s §8(§73m 8.2s§8)", "§aOpen §b> §a59.00s §8(§759.00s§8)",
            "§cBlood §b> §c30.10s §8(§730.00s§8)", "§dPortal §b> §d4.20s §8(§74.20s§8)",
            "§9Enter §b> §91m 33.3s §8(§71m 33.2s§8)", "§5Maxor §b> §525.50s §8(§725.50s§8)",
        ))
        draw(this, tracker.lines(now()))
    }

    private val scorecardHud by HUD("Scorecard Splits", "The whole run as a table: each split's total, then its sub splits.", true, 10, 150, 1f) { example ->
        if (example) return@HUD scorecard(this, listOf(
            "§a21.2\t§c3.5\t§c6.3\t§c2.2\t§c4.7\t§64.6", "§c62.0", "§d3.1\t§53.0\t§60.0",
            "§525.2\t§32.2\t§62.0\t§36.5", "§b45.9\t§60.4\t§c0.1\t§63.5\t§c0.2", "§620.9\t§811.9\t§85.2\t§83.9\t§84.1",
            "§e7.7\t§51.2\t§32.5\t§c2.9", "§c30.7\t§a9.8",
        ))
        scorecard(this, card.rows(tracker.splits(), now(), blood.roomTicks(), blood.over, subs.forSplit(SplitTracker.TERMS)))
    }

    private val cardDebug by BooleanSetting("Scorecard Debug", false, desc = "Says in chat each moment the scorecard picks up, and what it read it from — for checking the new ones (portal, leaps, Goldor's first hit, Storm breaking free).")
    private val card = Scorecard().also { c -> c.onEvent = { what -> if (cardDebug) modMessage("§8[scorecard] §7$what") } }

    /**
     * Each section's settings together, in the order they show in the ClickGUI: its detail level,
     * then (blood rush only) the Total row toggle, then its HUD with its own on/off toggle. The
     * HUDs are made up front because a HUD has to exist before the run that fills it.
     */
    private val levels = HashMap<Section, SelectorSetting>()
    private lateinit var totalRow: BooleanSetting

    init {
        for (s in SECTIONS) {
            levels[s] = registerSetting(SelectorSetting("${s.name} Detail", "Compact", LEVELS, desc = "How much the ${s.name} sub-split HUD shows."))
            if (s.window == SplitTracker.OPEN) totalRow = registerSetting(
                BooleanSetting("Blood Rush Total Row", true, desc = "The averages row at the bottom of the compact blood rush splits.")
            )
            registerSetting(
                HUD("${s.name} Sub Splits", "What happened inside ${s.name}.", true, 0, 0, 1f) { example ->
                    if (example) return@HUD draw(this, if (s.window == SplitTracker.OPEN) listOf(
                        "§5Hallway: \t§81.52s\t§70.21s\t§c0.06s\t§40.52s\t§62.31s",
                        "§dDino: \t§811.52s\t§70.21s\t§c0.06s\t§410.52s\t§622.31s",
                    ) else listOf("${s.colour}${s.name}: §68.12s §8| §52.28s §8| §c11.52s"))
                    draw(this, subLines(s))
                }
            )
        }
    }

    private fun level(s: Section) = BloodRunDetail.Level.entries[levels[s]?.value ?: 1]

    // What the world shows, watched only while it can matter.
    private val barriers = mutableListOf<Pair<Int, Int>>()
    private val cleared = mutableListOf<Pair<Int, Int>>()
    private val keysSeen = HashSet<Int>()
    private val crystalsSeen = HashSet<Int>()
    private val watchedMobs = HashMap<Int, Pair<String, Stamp>>()

    init {
        on<LevelEvent.Load> {
            tracker.reset(); subs.reset(); detail.reset(); boss.reset(); blood.reset(); card.reset(); pinnedStorm = null
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
            if (open(SplitTracker.PORTAL) && updated.block == Blocks.NETHER_PORTAL) card.onPortal(now())
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
                if (KEY.containsMatchIn(name)) { keysSeen += e.id; blood.onKeySpawned(now()) }
            }

            // Goldor's leap ends when the last teammate is inside the core.
            if ((subs.watchingCore || open(SplitTracker.GOLDOR)) && everyoneInCore(level)) {
                subs.onEveryoneInCore(now()); card.onEveryoneInCore(now())
            }

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
                    if (watchedMobs.put(e.id, name to at) == null) boss.onMobSpawn(at, name)
                }
            }
        }
        on<EntityEvent.Remove> {
            val (name, spawned) = watchedMobs.remove(entity.id) ?: return@on
            val at = now()
            boss.onMobGone(at, name, at.realMs - spawned.realMs)
        }

        // A wither hurt: Goldor's hits and Necron's first. Hits carry no attacker, so uncredited.
        onReceive<ClientboundDamageEventPacket> {
            val id = entityId()
            EngineerClient.mc.execute {
                val e = EngineerClient.mc.level?.getEntity(id) as? WitherBoss ?: return@execute
                val at = now()
                if (open(SplitTracker.GOLDOR)) { boss.onBossHit(SplitTracker.GOLDOR, at); card.onGoldorHit(at, "damage packet") }
                else if (open(SplitTracker.NECRON) && e.isAlive) boss.onBossHit(SplitTracker.NECRON, at)
            }
        }

        // A wither's hurt sound while Goldor is up: the other way his first hit can show.
        onReceive<ClientboundSoundPacket> {
            val id = sound.value().location().path
            if (id != "entity.wither.hurt") return@onReceive
            EngineerClient.mc.execute { if (open(SplitTracker.GOLDOR)) card.onGoldorHit(now(), "hurt sound") }
        }
    }

    /**
     * Every living teammate inside the core. Your original box stopped at y 112, but the fight goes
     * down to the core's floor (players stood at y 64-90 in the recorded runs), so it now reaches
     * all the way down. A teammate out of render distance counts as not in.
     */
    private fun everyoneInCore(level: net.minecraft.client.multiplayer.ClientLevel): Boolean {
        val alive = DungeonUtils.dungeonTeammates.filter { !it.isDead }
        if (alive.isEmpty()) return false
        return alive.all { mate ->
            val p = mate.entity ?: level.players().firstOrNull { it.name.string == mate.name }
            p != null && p.x >= 39 && p.x < 71 && p.y < 155.5 && p.z >= 54 && p.z < 118
        }
    }

    /** Storm's wither and where the crush pinned him. */
    private var pinnedStorm: Pair<Int, net.minecraft.world.phys.Vec3>? = null

    /**
     * Storm after a crush: the wither nearest his name tag (or you, if the tag is out of sight),
     * and the moment he is a block and a half from where the crush caught him.
     */
    private fun watchStorm(level: net.minecraft.client.multiplayer.ClientLevel) {
        val pinned = pinnedStorm
        if (pinned == null) {
            val withers = level.entitiesForRendering().filterIsInstance<WitherBoss>()
            val tag = level.entitiesForRendering().firstOrNull { it is ArmorStand && it.customName?.string?.contains("Storm") == true }
            val anchor = tag ?: mc.player ?: return
            val storm = withers.minByOrNull { it.distanceToSqr(anchor) } ?: return
            pinnedStorm = storm.id to storm.position()
            return
        }
        val e = level.getEntity(pinned.first) ?: return
        val dx = e.x - pinned.second.x; val dz = e.z - pinned.second.z
        if (dx * dx + dz * dz > 1.5 * 1.5) { card.onStormMoved(now()); pinnedStorm = null }
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
    private class Row(val label: String, val at: Stamp, val ms: Long, val ticks: Long, val who: String = "")

    private fun subLines(s: Section): List<String> {
        val level = level(s)
        if (level == BloodRunDetail.Level.OFF) return emptyList()
        val now = now()
        if (s.window == SplitTracker.OPEN) return blood.lines(level, now, totalRow.enabled)

        val split = tracker.split(s.window) ?: return emptyList()
        val since = { at: Stamp -> Row("", at, at.realMs - split.start.realMs, (at.tick - split.start.tick).toLong()) }

        // The boss's own steps each run until the next; the Watcher's and Portal's are moments,
        // timed from the start of the split.
        val boss = subs.forSplit(s.window)
        val steps = boss.map { st ->
            val stop = st.stop ?: now
            Row(st.label, st.start, stop.realMs - st.start.realMs, (stop.tick - st.start.tick).toLong())
        } + detail.lines(s.window).filter { it.step }.map { e -> since(e.at).let { Row(e.label, e.at, it.ms, it.ticks) } }

        if (level == BloodRunDetail.Level.COMPACT) {
            if (steps.isEmpty()) return emptyList()
            return listOf(s.colour + s.name + ": " + steps.joinToString(" §8| ") {
                it.label.take(2).replace('&', '§') + SplitFormat.seconds(it.ms)
            })
        }

        var rows = steps
        if (level == BloodRunDetail.Level.EXTREME) {
            rows = rows + detail.lines(s.window).filter { !it.step }.map { e -> since(e.at).let { Row(e.label, e.at, it.ms, it.ticks, e.who) } }
        }
        return rows.sortedBy { it.at.realMs }.map { r ->
            SplitFormat.line(r.label, r.ms, r.ticks) + if (r.who.isEmpty()) "" else " §7" + r.who
        }
    }

    private fun draw(gfx: GuiGraphicsExtractor, lines: List<String>): Pair<Int, Int> {
        if (lines.isEmpty()) return 0 to 0
        if (lines.any { '\t' in it }) return table(gfx, lines)
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
    private fun table(gfx: GuiGraphicsExtractor, lines: List<String>, rightFirst: Boolean = false, barsFrom: Int = 2, bar: String = "§8|", firstBarAlways: Boolean = false): Pair<Int, Int> {
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
                val x = if (c == 0 && rightFirst) widths[0] - mc.font.width(cell) else starts[c]
                gfx.text(cell, x, y, Colors.WHITE, shadow = true)
                if (c >= barsFrom && !(firstBarAlways && c == barsFrom)) gfx.text(bar, starts[c] - space - barW, y, Colors.WHITE, shadow = true)
            }
        }
        return maxOf(starts[cols - 1] + widths[cols - 1], if (firstBarAlways && cols > barsFrom) starts[barsFrom] else 0) to lines.size * LINE_HEIGHT
    }
}
