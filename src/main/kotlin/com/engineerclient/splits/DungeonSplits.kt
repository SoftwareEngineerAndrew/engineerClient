package com.engineerclient.splits

import com.engineerclient.EngineerClient
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
import net.minecraft.ChatFormatting
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

    private val levels = SECTIONS.associateWith { s ->
        registerSetting(SelectorSetting("${s.name} Detail", "Compact", LEVELS, desc = "How much the ${s.name} sub-split HUD shows."))
    }

    private fun level(s: Section) = BloodRunDetail.Level.entries[levels[s]?.value ?: 1]

    /** Made up front: a HUD has to exist before the run that fills it. */
    private val subHuds = SECTIONS.associateWith { s ->
        registerSetting(
            HUD("${s.name} Sub Splits", "What happened inside ${s.name}.", false, 0, 0, 1f) { example ->
                if (example) return@HUD draw(this, if (s.window == SplitTracker.OPEN) listOf(
                    "§5Hallway: §71.52s §8| §80.21s §8| §c0.06s §8| §40.52s §8| §62.31s",
                    "§dDino: §71.52s §8| §80.21s §8| §c0.06s §8| §40.52s §8| §62.31s",
                ) else listOf("${s.colour}${s.name}: §68.12s §8| §52.28s §8| §c11.52s"))
                draw(this, subLines(s))
            }
        )
    }

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
        if (s.window == SplitTracker.OPEN) return blood.lines(level, now)

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
        lines.forEachIndexed { i, line ->
            val y = i * LINE_HEIGHT
            shadow(gfx, line, y)
            gfx.text(mc.font, line, 0, y, WHITE, false)
        }
        return lines.maxOf { mc.font.width(it) } + 1 to lines.size * LINE_HEIGHT
    }

    /**
     * The text shadow, drawn by hand: vanilla's own never showed up in game (something in the mod
     * list drops it), so each coloured piece is drawn again one pixel down and right at a quarter
     * of its brightness — exactly what vanilla's shadow is.
     */
    private fun shadow(gfx: GuiGraphicsExtractor, line: String, y: Int) {
        var x = 1
        var rgb = 0xFFFFFF
        for (piece in line.split('§').withIndex()) {
            var text = piece.value
            if (piece.index > 0 && text.isNotEmpty()) {
                ChatFormatting.getByCode(text[0])?.let { f -> f.color?.let { rgb = it } ?: run { if (f == ChatFormatting.RESET) rgb = 0xFFFFFF } }
                text = text.substring(1)
            }
            if (text.isEmpty()) continue
            val dark = ((rgb shr 16 and 0xFF) / 4 shl 16) or ((rgb shr 8 and 0xFF) / 4 shl 8) or ((rgb and 0xFF) / 4)
            gfx.text(mc.font, text, x, y + 1, 0xFF000000.toInt() or dark, false)
            x += mc.font.width(text)
        }
    }

    private const val WHITE = 0xFFFFFFFF.toInt()
}
