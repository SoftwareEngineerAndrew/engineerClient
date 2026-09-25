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
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.ItemEntity

/**
 * Devonian's dungeon splits, the whole run on one HUD: the clear (blood door, the Watcher, boss
 * entry) and then the boss's phases, each counting up live and freezing when it ends.
 *
 * Alongside it, one HUD per split — "Blood Sub Splits", "Terminals Sub Splits" and so on — listing
 * what happened inside that split and how far into it. Those are a data-gathering exercise: we do
 * not yet know which moments are worth timing, so [SplitEvents] recognises everything the dungeon
 * announces and the useful ones get picked out once there is a run's worth to look at. They are all
 * off by default; turn on the one you are studying.
 *
 * Both clocks are shown by default, the way Devonian's "Both" format does it: real time, then the
 * server's own tick time in brackets. They come apart when the server lags — the tick time is what
 * the run is actually judged on, and the gap between them is how much lag you ate.
 *
 * [SplitTracker] holds the timing; this module only supplies it with chat, the clocks and the
 * Watcher's first movement, and draws the result.
 */
object DungeonSplits : Module(
    name = "Sub Splits",
    category = Category.custom("Engineer Client"),
    description = "Devonian's run, boss and Watcher splits: each section timed on the real clock and the server's tick clock.",
) {
    private val detailMode by SelectorSetting("Sub Split Detail", "Steps", listOf("Steps", "Everything"), desc = "Steps: the boss's named sub-splits. Everything: every moment of the split the game reported, which is noisy on purpose - it is how you find out what is worth keeping.")

    private val CONTROL_CODES = Regex("\u00a7.")
    private val tracker = SplitTracker()
    private val subs = SubSplitTracker()
    private val detail = SplitDetail()
    private val boss = BossDetail(detail)
    private val blood = BloodRunDetail(detail)

    /** The most recent block-to-air while a door is coming down, and how long to keep watching. */
    private var doorFallAt: Stamp? = null
    private var doorFallUntil = 0

    /** Mobs being timed: entity id -> the split it spawned in, its name, and when. */
    private val watchedMobs = HashMap<Int, Triple<String, String, Stamp>>()
    private var serverTicks = 0
    private val COLOUR_CODE = Regex("&.")

    private fun now() = Stamp(System.currentTimeMillis(), serverTicks)

    private val splitsHud by HUD("Splits", "The whole run: the clear, then the boss's phases.") { example ->
        if (example) return@HUD draw(this, listOf("§4Blood§r§f: §a31.24s §7(§b31.05s§7)", "§9Boss Entry§r§f: §a1m 12.30s §7(§b1m 12.05s§7)", "§5Maxor§r§f: §a26.10s §7(§b26.10s§7)", "§6Terminals§r§f: §a1m 09.40s §7(§b1m 09.40s§7)"))
        draw(this, tracker.splits().map { SplitFormat.line(it, now(), SplitClock.BOTH) })
    }

    /**
     * One HUD per split, made up front from every label a run can produce — a HUD has to exist
     * before the run that would fill it, and Odin registers a setting the same way whether it came
     * from a `by` delegate or from here.
     */
    private val subHuds = SplitTracker.ALL_LABELS.associateWith { label ->
        val plain = label.replace(COLOUR_CODE, "")
        registerSetting(
            HUD("$plain Sub Splits", "What happened inside the $plain split, and how far into it.", false, 0, 0, 1f) { example ->
                if (example) return@HUD if (label == SplitTracker.BLOOD) drawColumns(this, listOf(
                    listOf("§fEntrance", "§f0.75s §7(§b0.75s§7)§f door fell", "§f7.20s §7(§b7.20s§7)§f key picked up §7(Bob)"),
                    listOf("§fWater Board", "§f0.80s §7(§b0.80s§7)§f door fell", "§f6.40s §7(§b6.40s§7)§f key picked up §7(Sue)"),
                )) else draw(this, listOf("§6Move§r§f: §a8.12s §7(§b8.00s§7)", "§5Stun§r§f: §a2.28s §7(§b2.25s§7)", "§cDps§r§f: §a11.52s §7(§b11.25s§7)"))
                if (label == SplitTracker.BLOOD && detailMode == 1) drawColumns(this, bloodColumns())
                else draw(this, subLines(label))
            }
        )
    }

    init {
        on<LevelEvent.Load> {
            tracker.reset(); subs.reset(); detail.reset(); boss.reset(); blood.reset()
            watchedMobs.clear(); serverTicks = 0
        }

        // Odin's server tick: the server's own clock, which falls behind the client's 20 a second
        // when it lags. That is the clock a run is judged on.
        on<TickEvent.Server> { serverTicks++; subs.onServerTick() }

        // Chat straight off the network, before any mod can hide it: chat cleaners drop exactly the
        // terminal, device and gate lines the sections are timed from. Handed to the client thread,
        // where the tick count is read.
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
                    if (DOOR_OPENED.containsMatchIn(text)) doorFallUntil = serverTicks + DOOR_FALL_TICKS

                }
            }
        }

        // Goldor's leap ends when the last teammate is inside the core. Only looked for while the
        // sequence says the team is on its way there, so it costs nothing the rest of the run.
        on<TickEvent.End> {
            if (DungeonUtils.inDungeons) blood.onRoom(DungeonUtils.currentRoomName)
            // A door falls over about a second; the last of its blocks to turn to air is when it is
            // down. Only watched in the few seconds after a door is opened, so ordinary mining
            // elsewhere in the dungeon cannot be mistaken for it.
            val fell = doorFallAt
            if (fell != null && serverTicks > doorFallUntil) { doorFallAt = null; blood.onDoorFell(fell) }
        }

        // The Watcher's waves: how long a mob stayed up. Nothing reports who killed it - Minecraft
        // sends no mob death message and no damage attribution - so this times the entity's life
        // and says nothing about who ended it.
        on<BlockUpdateEvent> {
            if (serverTicks <= doorFallUntil && updated.isAir) doorFallAt = now()
        }

        on<EntityEvent.Add> {
            (entity as? ItemEntity)?.let { item ->
                if (KEY_ITEM.containsMatchIn(item.item.hoverName.string)) blood.onKeyDropped(now())
            }
            val split = mobSplit() ?: return@on
            val e = entity as? LivingEntity ?: return@on
            val name = e.customName?.string?.replace(CONTROL_CODES, "")?.takeIf { it.isNotBlank() } ?: return@on
            val at = now()
            if (watchedMobs.put(e.id, Triple(split, name, at)) == null) boss.onMobSpawn(split, at, name)
        }
        on<EntityEvent.Remove> {
            val (split, name, spawned) = watchedMobs.remove(entity.id) ?: return@on
            val at = now()
            boss.onMobGone(split, at, name, at.realMs - spawned.realMs)
        }

        on<TickEvent.End> {
            if (!subs.watchingCore) return@on
            val alive = DungeonUtils.dungeonTeammates.filter { !it.isDead }
            if (alive.isEmpty()) return@on
            val inCore = alive.count { mate ->
                val p = mate.entity ?: level.players().firstOrNull { it.name.string == mate.name }
                p != null && p.x >= 39 && p.x < 71 && p.y >= 112 && p.y < 155.5 && p.z >= 54 && p.z < 118
            }
            if (inCore >= alive.size) subs.onEveryoneInCore(now())
        }
    }

    /**
     * Which split a mob spawning right now belongs to: the blood rush or the Watcher fight. Null
     * anywhere else, so the rest of the run costs nothing.
     */
    private fun mobSplit(): String? = tracker.splits()
        .lastOrNull { it.stop == null && (it.label == SplitTracker.BLOOD || it.label == SplitTracker.WATCHER) }
        ?.label

    private const val LINE_HEIGHT = 10
    private const val COLUMN_GAP = 12

    /** A wither or blood door takes about this long to finish falling. */
    private const val DOOR_FALL_TICKS = 60
    private val DOOR_OPENED = Regex("""opened a WITHER door!$|^The BLOOD DOOR has been opened!$""")
    private val KEY_ITEM = Regex("""(?:Wither|Blood) Key""")

    /**
     * A split's events as "+<time into the split> <what happened>". The offset is what makes these
     * comparable between runs, so it leads.
     */
    private fun subLines(label: String): List<String> {
        val split = tracker.splits().firstOrNull { it.label == label } ?: return emptyList()
        // The boss phases have a real breakdown, ported from the team's own module. The clear's
        // splits have none, so those HUDs keep listing whatever the dungeon announced.
        val now = now()
        val steps = subs.forSplit(label)
        if (detailMode == 0) return steps.map { SplitFormat.line(it, now, SplitClock.BOTH) }

        // Everything: the phase's own steps and everything reported inside it, in the order it
        // happened and never cut short - the whole point is to see the lot.
        if (label == SplitTracker.BLOOD) return detail.lines(label).map { it.label }.filter { it.isNotBlank() }
        val stepLines = steps.map { it.start to SplitFormat.line(it, now, SplitClock.BOTH) }
        val detailLines = detail.lines(label).map { event ->
            if (event.raw) return@map event.at to event.label
            val real = SplitFormat.time(event.at.realMs - split.start.realMs, true)
            val ticks = SplitFormat.time((event.at.tick - split.start.tick) * 50L, true)
            event.at to "§a$real §7(§b$ticks§7) §f" + event.label
        }
        return (stepLines + detailLines).sortedBy { it.first.realMs }.map { it.second }
    }

    /**
     * The blood rush a column per room, in the order they were run, with the averages as the last
     * column. The blank line each room's block ends with is what separates them.
     */
    private fun bloodColumns(): List<List<String>> {
        val columns = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()
        for (entry in detail.lines(SplitTracker.BLOOD)) {
            if (entry.label.isBlank()) {
                if (current.isNotEmpty()) { columns += current; current = mutableListOf() }
            } else current += entry.label
        }
        if (current.isNotEmpty()) columns += current
        return columns
    }

    /** Columns side by side, each as wide as its widest line. */
    private fun drawColumns(gfx: GuiGraphicsExtractor, columns: List<List<String>>): Pair<Int, Int> {
        if (columns.isEmpty()) return 0 to 0
        var x = 0
        var height = 0
        for (column in columns) {
            column.forEachIndexed { i, line -> gfx.text(line, x, i * LINE_HEIGHT, Colors.WHITE) }
            x += (column.maxOfOrNull { mc.font.width(it) } ?: 0) + COLUMN_GAP
            height = maxOf(height, column.size * LINE_HEIGHT)
        }
        return (x - COLUMN_GAP) to height
    }

    private fun draw(gfx: GuiGraphicsExtractor, lines: List<String>): Pair<Int, Int> {
        if (lines.isEmpty()) return 0 to 0
        var width = 0
        lines.forEachIndexed { i, line ->
            gfx.text(line, 0, i * LINE_HEIGHT, Colors.WHITE)
            width = maxOf(width, mc.font.width(line))
        }
        return width to lines.size * LINE_HEIGHT
    }
}
