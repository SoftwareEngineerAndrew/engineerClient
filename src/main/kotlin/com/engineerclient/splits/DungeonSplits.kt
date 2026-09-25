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
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import kotlin.math.abs

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
            tracker.reset(); subs.reset(); detail.reset(); boss.reset(); blood.reset()
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
                    subs.onChat(text, at)
                    boss.onChat(text, at)
                    blood.onChat(text, at)
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
            if (barriers.size >= DOOR_BLOCKS) door(barriers) { at, a, b -> blood.onDoorStart(at, a, b) }
            if (cleared.size >= DOOR_BLOCKS) door(cleared) { at, a, b -> blood.onDoorDown(at, a, b) }
            barriers.clear(); cleared.clear()

            // The key is an armor stand named "Wither Key"; it appears where the last mob died.
            if (blood.active) for (e in level.entitiesForRendering()) {
                if (e !is ArmorStand || e.id in keysSeen) continue
                val name = e.customName?.string ?: continue
                if (KEY.containsMatchIn(name)) { keysSeen += e.id; blood.onKeySpawned(now()) }
            }

            // Goldor's leap ends when the last teammate is inside the core.
            if (subs.watchingCore) {
                val alive = DungeonUtils.dungeonTeammates.filter { !it.isDead }
                if (alive.isNotEmpty()) {
                    val inCore = alive.count { mate ->
                        val p = mate.entity ?: level.players().firstOrNull { it.name.string == mate.name }
                        p != null && p.x >= 39 && p.x < 71 && p.y >= 112 && p.y < 155.5 && p.z >= 54 && p.z < 118
                    }
                    if (inCore >= alive.size) subs.onEveryoneInCore(now())
                }
            }
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
                if (open(SplitTracker.GOLDOR)) boss.onBossHit(SplitTracker.GOLDOR, at)
                else if (open(SplitTracker.NECRON) && e.isAlive) boss.onBossHit(SplitTracker.NECRON, at)
            }
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
    private fun door(blocks: List<Pair<Int, Int>>, sink: (Stamp, BloodRunDetail.MapRoom?, BloodRunDetail.MapRoom?) -> Unit) {
        val tx = (blocks.sumOf { it.first }.toDouble() / blocks.size + 185) / 32
        val tz = (blocks.sumOf { it.second }.toDouble() / blocks.size + 185) / 32
        val a = room(Math.floor(tx + 0.01).toInt(), Math.floor(tz + 0.01).toInt())
        val b = room(Math.ceil(tx - 0.01).toInt(), Math.ceil(tz - 0.01).toInt())
        if (abs(tx - Math.round(tx)) < 0.1 && abs(tz - Math.round(tz)) < 0.1) return // not between two tiles
        sink(now(), a, b)
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

    private const val DOOR_BLOCKS = 30
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
     * Tab-separated rows drawn as a table: the first column left-aligned, the rest right-aligned in
     * columns as wide as their widest cell, with a dark-grey `|` at the same x on every row. Text
     * padded with spaces cannot do this — a digit and a space are different widths.
     */
    private fun table(gfx: GuiGraphicsExtractor, lines: List<String>): Pair<Int, Int> {
        val rows = lines.map { it.split('\t') }
        val cols = rows.maxOf { it.size }
        val widths = IntArray(cols) { c -> rows.maxOf { r -> r.getOrNull(c)?.let(mc.font::width) ?: 0 } }
        val space = mc.font.width(" ")
        val bar = mc.font.width("|")
        // Where each column starts: the name, then each time with a " | " in front of it.
        val starts = IntArray(cols)
        for (c in 1 until cols) starts[c] = starts[c - 1] + widths[c - 1] + if (c == 1) 0 else space * 2 + bar
        rows.forEachIndexed { i, row ->
            val y = i * LINE_HEIGHT
            row.forEachIndexed { c, cell ->
                if (cell.isEmpty()) return@forEachIndexed
                val x = if (c == 0) 0 else starts[c] + widths[c] - mc.font.width(cell)
                gfx.text(cell, x, y, Colors.WHITE, shadow = true)
                if (c >= 2) gfx.text("§8|", starts[c] - space - bar, y, Colors.WHITE, shadow = true)
            }
        }
        return starts[cols - 1] + widths[cols - 1] to lines.size * LINE_HEIGHT
    }
}
