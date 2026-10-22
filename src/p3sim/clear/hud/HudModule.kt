package com.engineerclient.p3sim.clear.hud

import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.run.AdsCommand
import com.engineerclient.p3sim.run.AdsModule
import com.engineerclient.p3sim.run.EndReason
import com.engineerclient.p3sim.run.MobDamaged
import com.engineerclient.p3sim.run.PlayerJoined
import com.engineerclient.p3sim.run.PlayerLeft
import com.engineerclient.p3sim.run.PuzzleFailed
import com.engineerclient.p3sim.run.Run
import com.engineerclient.p3sim.run.RunEvent
import com.engineerclient.p3sim.run.api.Audience
import com.engineerclient.p3sim.run.api.ChannelService
import com.engineerclient.p3sim.run.api.HudRegistry
import com.engineerclient.ads.settings.BooleanSetting
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * The client channels as Hypixel sends them, driven from RunState: the sidebar (`team_N` teams), the 80 tab entries, the dungeon map
 * item (128x128 frames and markers), the action bar, plus the chat / title / action-bar API the other modules call through
 * `Services.channels`. Unmodified Odin, Devonian and Blade Addons read these as they would on Hypixel.
 *
 * It runs last in the module order, so every surface shows the end-of-tick state. Cadence (recorded): the sidebar when a line changes
 * (the clock line moves every 20 ticks), the tab rows once a second, map markers every 5 ticks and a full frame only when a pixel
 * changed, the action bar every 10 ticks. All state here is cosmetic and rebuilt from RunState, so a savepoint restore needs nothing.
 *
 * The pure formatting is in [SidebarModel], [TabModel], [MapModel], [ActionBarModel]; the packets in [HudPackets].
 * FILLED BY: the `hud` work item (owns `src/p3sim/clear/hud/` and below).
 */
object HudModule : AdsModule, ChannelService {
    /** A replay: the server tick (mod the refresh) the tab refreshes on, the recording's (Hypixel's tab counters change once a second, on one tick of it); null: 0. */
    @Volatile var tabPhase: ((Run) -> Int?)? = null

    override val id = "hud"
    override val owner = "hud"

    private val log = org.slf4j.LoggerFactory.getLogger("engineerclient")

    // Settings (created in registerSettings: the object must stay cheap to initialise).
    lateinit var sidebarOn: BooleanSetting
    lateinit var tabOn: BooleanSetting
    lateinit var mapOn: BooleanSetting
    lateinit var barOn: BooleanSetting

    override fun registerSettings(host: com.engineerclient.ads.settings.Settings) {
        sidebarOn = host.registerSetting(BooleanSetting("Hud Sidebar", true, desc = "Send the Hypixel dungeon sidebar during a run (floor, time, cleared percent, party). Mods read the floor and the cleared percent from it."))
        tabOn = host.registerSetting(BooleanSetting("Hud Tab List", true, desc = "Send the Hypixel dungeon tab list during a run (party, secrets, rooms, puzzles). Mods read the party and the stats from it."))
        mapOn = host.registerSetting(BooleanSetting("Hud Map", true, desc = "Send the dungeon map to the Magical Map during a run (rooms, doors, check marks, party markers)."))
        barOn = host.registerSetting(BooleanSetting("Hud Action Bar", true, desc = "Send the action bar during a run (health, defense, mana, and the secrets of the room you stand in)."))
    }

    private fun enabled(s: () -> BooleanSetting): Boolean = try { s().value } catch (_: UninitializedPropertyAccessException) { true }

    private val sidebarEnabled get() = enabled { sidebarOn }
    private val tabEnabled get() = enabled { tabOn }
    private val mapEnabled get() = enabled { mapOn }
    private val barEnabled get() = enabled { barOn }

    /** Ticks between the sidebar refreshes that carry a changed key count. */
    private const val SIDEBAR_REFRESH_TICKS = 20

    /** The channels of the run that is on (server thread only); null when no run. */
    private var session: Session? = null

    // ---- the API other modules call (ChannelService) ----------------------------------------------------------------

    /** A chat line as Hypixel's styled system chat: `§` codes become styles, so the plain text is what mods parse. */
    override fun chat(text: String, to: Audience) = each(to) { Sim.chatStyled(text) }

    override fun title(title: String, subtitle: String, fadeIn: Int, stay: Int, fadeOut: Int, to: Audience) =
        each(to) { Sim.title(title, subtitle, fadeIn, stay, fadeOut) }

    override fun actionBarSegment(key: String, text: String, priority: Int, ttlTicks: Int, to: Audience) {
        val s = session ?: return
        when (to) {
            Audience.Everyone -> s.segments.set(key, text, priority, s.run.serverTick, ttlTicks, null)
            is Audience.Only -> s.segments.set(key, text, priority, s.run.serverTick, ttlTicks, to.player)
            is Audience.AllBut -> for (p in Sim.players()) if (p.uuid != to.player) s.segments.set(key, text, priority, s.run.serverTick, ttlTicks, p.uuid)
        }
    }

    override fun clearActionBarSegment(key: String) { session?.segments?.clear(key) }

    private fun each(to: Audience, block: () -> Unit) {
        when (to) {
            Audience.Everyone -> Sim.everyone(block)
            is Audience.Only -> Sim.players().firstOrNull { it.uuid == to.player }?.let { p -> Sim.asPlayer(p) { block() } }
            is Audience.AllBut -> Sim.players().filter { it.uuid != to.player }.forEach { p -> Sim.asPlayer(p) { block() } }
        }
    }

    // ---- run lifecycle ----------------------------------------------------------------------------------------------

    override fun onRunStart(run: Run) {
        session?.let { runCatching { it.close() } }
        session = Session(run)
    }

    override fun onTick(run: Run) {
        val s = session?.takeIf { it.run === run } ?: return
        s.tick()
    }

    override fun onEvent(run: Run, event: RunEvent) {
        val s = session ?: return
        when (event) {
            is MobDamaged -> s.teamDamage += Math.max(0.0, event.amount).toLong()
            is PlayerJoined, is PlayerLeft -> s.membershipChanged = true
            is PuzzleFailed -> s.puzzleFailed(event.puzzle, event.player)
            else -> {}
        }
    }

    override fun onRunEnd(run: Run, reason: EndReason) {
        val s = session?.takeIf { it.run === run } ?: return
        runCatching { s.close() }
        session = null
    }

    // ---- /ads dev hud -----------------------------------------------------------------------------------------------

    override fun devCommands(): List<AdsCommand> = listOf(
        AdsCommand(
            "hud", "/ads dev hud <dump|bar <text>|chat <text>|title <text>>",
            "Shows what the sidebar, tab list, map and action bar are sending (dump), or tries the chat, title and action bar API.",
            server = { ctx ->
                val run = ctx.run
                val s = session
                when (ctx.arg(0)) {
                    "dump" -> if (run == null || s == null) ctx.reply("No run is on, so the channels are not sending anything.") else ctx.reply(s.dump(ctx.player))
                    "bar" -> if (run == null) ctx.reply("No run is on.") else {
                        actionBarSegment("dev-test", "§6" + ctx.args.drop(1).joinToString(" ").ifBlank { "Test segment" }, ActionBarModel.REPLACES_DEFENSE, 60, Audience.Only(ctx.player.uuid))
                        ctx.reply("Action bar segment set for 3 seconds (it replaces the defense segment).")
                    }
                    "chat" -> chat("§a" + ctx.args.drop(1).joinToString(" ").ifBlank { "Test line" }, Audience.Only(ctx.player.uuid))
                    "title" -> title("§e" + ctx.args.drop(1).joinToString(" ").ifBlank { "Test title" }, "§7Test subtitle", 0, 30, 5, Audience.Only(ctx.player.uuid))
                    else -> ctx.reply("Usage: /ads dev hud dump | bar <text> | chat <text> | title <text>")
                }
            },
        ),
    )

    // ---- the per-run channels ---------------------------------------------------------------------------------------

    /** What one viewer has been sent, so only changes go out. */
    private class ViewerHud {
        var sidebar: Map<Int, String>? = null
        var tabRows: List<String>? = null
        var tabFooter: String? = null
        var unlisted: Set<UUID> = emptySet()
        var bots: Set<UUID> = emptySet()
        var frame: ByteArray? = null
        var dirty = true
    }

    private class Session(val run: Run) {
        val segments = BarSegments()
        var teamDamage = 0L
        var teamHealing = 0L
        var membershipChanged = false
        private val viewers = HashMap<UUID, ViewerHud>()
        private val stats: HudStats = statsFor(run)
        private val date: String = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("MM/dd/yy"))
        private val tabRefresh: Int = run.packs.runstate?.counters?.tabRefreshTicks?.takeIf { it > 0 } ?: HudConstants.TAB_REFRESH_TICKS
        private val colours = run.packs.runstate?.raw?.getAsJsonObject("hud")?.getAsJsonObject("clearedColour")
        private val begin = run.serverTick
        private var warned = HashSet<String>()

        /** Who failed each puzzle (by index in the state's puzzle list), from [PuzzleFailed]; the tab row of a failed puzzle names them. */
        private val failedBy = HashMap<Int, String>()

        /** The Revive Stones each human carries, counted again at every tab refresh (null: not counted yet). */
        private var stones: Map<UUID, Int>? = null

        /** One tick's gathered inputs. */
        private fun context(): HudContext = HudContext(
            state = run.state, stage = run.stage, floor = run.config.floor, t = run.t, t0Known = run.t0Tick != null,
            sinceBegin = run.serverTick - begin, stats = stats, teamDamage = teamDamage, teamHealing = teamHealing, dateText = date,
            clearedGoldFrom = band("gold", HudConstants.CLEARED_GOLD_FROM), clearedGreenFrom = band("green", HudConstants.CLEARED_GREEN_FROM),
            extraSidebar = if (HudRegistry.sidebar.isEmpty()) emptyList() else HudRegistry.sidebar.flatMap { guarded("sidebar contributor") { it.lines(run) } ?: emptyList() },
            extraTab = if (HudRegistry.tab.isEmpty()) emptyList() else HudRegistry.tab.flatMap { guarded("tab contributor") { it.rows(run) } ?: emptyList() },
            reviveStones = stones ?: emptyMap(), failedBy = if (failedBy.isEmpty()) emptyMap() else HashMap(failedBy),
        )

        /** Remembers who failed puzzle [index] (nobody: the row goes without a name) and has the tab redrawn at once. */
        fun puzzleFailed(index: Int, player: UUID?) {
            if (index < 0) return
            val name = player?.let { id -> run.state.players[id]?.name ?: Sim.players().firstOrNull { it.uuid == id }?.gameProfile?.name }
            if (name != null) failedBy[index] = name else failedBy.remove(index)
            for (v in viewers.values) v.dirty = true
        }

        /** The Revive Stones in [p]'s inventory (the tab counts what a member carries). */
        private fun carriedStones(p: ServerPlayer): Int {
            var n = 0
            val inv = p.inventory
            for (i in 0 until inv.containerSize) {
                val stack = inv.getItem(i)
                if (!stack.isEmpty && com.engineerclient.p3sim.SimItems.idOf(stack) == com.engineerclient.p3sim.clear.damage.DamageModule.REVIVE_STONE) n += stack.count
            }
            return n
        }

        private fun band(name: String, default: Int): Int =
            colours?.getAsJsonObject(name)?.get("fromPct")?.takeIf { it.isJsonPrimitive }?.asInt ?: default

        private inline fun <T> guarded(what: String, block: () -> T): T? = try { block() } catch (e: Throwable) {
            if (warned.add(what)) log.warn("[ads] hud: $what failed", e)
            null
        }

        fun tick() {
            val players = Sim.players()
            if (players.isEmpty()) return
            val phase = tabPhase?.invoke(run) ?: 0
            val tabNow = Math.floorMod(run.serverTick - phase, tabRefresh) == 0
            if (stones == null || tabNow) stones = players.associate { it.uuid to carriedStones(it) }
            val ctx = context()
            val tick = run.serverTick
            // Only when the count changed (someone left): a join adds its viewer below, which changes the count on the next tick.
            if (viewers.size != players.size) viewers.keys.retainAll(players.mapTo(HashSet()) { it.uuid })
            val realIds = lazy(LazyThreadSafetyMode.NONE) { players.mapTo(HashSet()) { it.uuid } }
            val raster = if (ctx.started && ctx.stage.inDungeon && mapEnabled && tick % HudConstants.MAP_TICKS == 0) guarded("map render") { renderMap(ctx) } else null
            for (p in players) {
                val v = viewers.getOrPut(p.uuid) { ViewerHud() }
                if (membershipChanged) v.dirty = true
                if (sidebarEnabled) guarded("sidebar") { sidebar(p, v, ctx, tick) }
                if (tabEnabled) guarded("tab") { tab(p, v, ctx, players, realIds, tabNow) }
                // The recorded map stops a few ticks before the boss entry and sends nothing during the fight.
                if (mapEnabled && ctx.started && ctx.stage.inDungeon) guarded("map") { map(p, v, ctx, raster, tick) }
                if (barEnabled && tick % HudConstants.ACTION_BAR_TICKS == 0) guarded("action bar") { bar(p, ctx, tick) }
            }
            membershipChanged = false
            for (v in viewers.values) v.dirty = false
        }

        private fun send(p: ServerPlayer, packets: Iterable<Packet<ClientGamePacketListener>?>) { for (pk in packets) if (pk != null) p.connection.send(pk) }

        private fun sidebar(p: ServerPlayer, v: ViewerHud, ctx: HudContext, tick: Int) {
            val prev = v.sidebar
            // A key count changes on the next sidebar refresh (about every 20 ticks), not in the tick the key arrives.
            val next = SidebarModel.holdKeys(prev, SidebarDiff.scored(SidebarModel.lines(ctx, p.uuid)), tick % SIDEBAR_REFRESH_TICKS == 0)
            if (prev == null) { send(p, HudPackets.sidebarShow()); send(p, HudPackets.sidebarOps(SidebarDiff.diff(emptyMap(), next))) }
            else {
                val ops = SidebarDiff.diff(prev, next)
                if (!ops.isEmpty) send(p, HudPackets.sidebarOps(ops))
            }
            v.sidebar = next
        }

        private fun tab(p: ServerPlayer, v: ViewerHud, ctx: HudContext, online: List<ServerPlayer>, realIds: Lazy<Set<UUID>>, refresh: Boolean) {
            if (!HudPackets.tabWorks) return
            val first = v.tabRows == null
            if (!first && !v.dirty && !refresh) return
            val rows = TabModel.rows(ctx, p.uuid)
            val footer = TabModel.footer(ctx)
            if (first) {
                send(p, listOf(HudPackets.tabAddFakes(rows)))
                send(p, listOf(HudPackets.tabHeaderFooter(TabModel.HEADER, footer)))
            } else {
                val prev = v.tabRows!!
                val changed = rows.indices.filter { prev[it] != rows[it] }
                send(p, listOf(HudPackets.tabText(rows, changed)))
                if (footer != v.tabFooter) send(p, listOf(HudPackets.tabHeaderFooter(TabModel.HEADER, footer)))
            }
            v.tabRows = rows
            v.tabFooter = footer
            // Real players are hidden from the visible list (only the 80 rows show); bots get unlisted entries so mods can look them up.
            val toHide = online.filter { it.uuid !in v.unlisted }
            if (toHide.isNotEmpty()) { send(p, listOf(HudPackets.tabListed(toHide, false))); v.unlisted = v.unlisted + toHide.map { it.uuid } }
            val bots = run.state.players.values.filter { it.isBot && it.uuid !in realIds.value }.associate { it.uuid to it.name }
            val newBots = bots.filterKeys { it !in v.bots }
            if (newBots.isNotEmpty()) send(p, listOf(HudPackets.tabAddBots(newBots.map { it.key to it.value })))
            val goneBots = v.bots.filter { it !in bots }
            if (goneBots.isNotEmpty()) send(p, listOf(HudPackets.tabRemoveIds(goneBots)))
            v.bots = bots.keys
        }

        private fun renderMap(ctx: HudContext): ByteArray {
            val canvas = ByteMapCanvas()
            MapModel.renderInto(ctx.state, canvas)
            for (c in HudRegistry.map) guarded("map contributor") { c.paint(run, canvas) }
            return canvas.bytes
        }

        private fun yawOf(id: UUID): Float {
            Sim.players().firstOrNull { it.uuid == id }?.let { return it.yRot }
            return (run.state.players[id]?.extras?.get("yaw") as? Number)?.toFloat() ?: 0f
        }

        private fun map(p: ServerPlayer, v: ViewerHud, ctx: HudContext, raster: ByteArray?, tick: Int) {
            if (tick % HudConstants.MAP_TICKS != 0 && v.frame != null) return
            val picture = raster ?: guarded("map render") { renderMap(ctx) } ?: return
            val marks = MapModel.decorations(ctx, p.uuid, ::yawOf)
            val last = v.frame
            // Recorded: a full frame carries no decorations; they follow in a packet of their own in the same tick.
            if (last == null || !last.contentEquals(picture)) {
                send(p, listOf(HudPackets.mapFrame(picture, emptyList()), HudPackets.mapMarks(marks)))
                v.frame = picture
            } else send(p, listOf(HudPackets.mapMarks(marks)))
        }

        private fun bar(p: ServerPlayer, ctx: HudContext, tick: Int) {
            val me = ctx.state.players[p.uuid]
            val line = ActionBarModel.compose(stats, me, ctx.state, segments.live(tick, p.uuid))
            send(p, listOf(HudPackets.actionBar(line)))
        }

        /** A readable copy of everything sent to [p], for `/ads dev hud dump`; also written to the log folder's hud file. */
        fun dump(p: ServerPlayer): String {
            val ctx = context()
            val sb = StringBuilder()
            sb.append("stage=${ctx.stage} t=${ctx.t} started=${ctx.started}\n--- sidebar (top first) ---\n")
            for (l in SidebarModel.lines(ctx, p.uuid)) sb.append(HudFormat.strip(l)).append('\n')
            sb.append("--- tab (80 rows) ---\n")
            for ((i, r) in TabModel.rows(ctx, p.uuid).withIndex()) sb.append(i).append(": ").append(r).append('\n')
            sb.append("--- action bar ---\n").append(HudFormat.strip(ActionBarModel.compose(stats, ctx.state.players[p.uuid], ctx.state, segments.live(run.serverTick, p.uuid)))).append('\n')
            sb.append("--- map ---\n")
            val px = MapModel.render(ctx.state)
            for (y in 0 until MapModel.SIZE step 2) {
                for (x in 0 until MapModel.SIZE step 1) {
                    val c = px[y * MapModel.SIZE + x].toInt()
                    sb.append(when (c) { 0 -> ' '; MapModel.C_UNKNOWN -> '.'; MapModel.C_QUESTION -> '?'; MapModel.C_GREEN -> '+'; MapModel.C_WHITE -> 'o'; else -> '#' })
                }
                sb.append('\n')
            }
            val file = net.fabricmc.loader.api.FabricLoader.getInstance().gameDir.resolve("config/engineerclient/ads/hud-dump.txt")
            runCatching { java.nio.file.Files.createDirectories(file.parent); java.nio.file.Files.writeString(file, sb.toString()) }
            return "HUD dump written to $file (sidebar ${SidebarModel.lines(ctx, p.uuid).size} lines, tab 80 rows)."
        }

        /** Takes everything off the clients: the sidebar, the fake tab rows (real players listed again), the bots' entries. */
        fun close() {
            val players = Sim.players()
            for (p in players) {
                val v = viewers[p.uuid] ?: continue
                runCatching {
                    if (v.sidebar != null) {
                        send(p, HudPackets.sidebarTeamsRemove(v.sidebar!!.keys))
                        p.connection.send(HudPackets.sidebarHide())
                    }
                    if (v.tabRows != null) {
                        p.connection.send(HudPackets.tabRemoveFakes())
                        send(p, listOf(HudPackets.tabListed(players.filter { it.uuid in v.unlisted }, true)))
                        send(p, listOf(HudPackets.tabRemoveIds(v.bots.toList())))
                        p.connection.send(HudPackets.tabHeaderFooter("", ""))
                    }
                }
            }
            viewers.clear()
        }

        private fun statsFor(run: Run): HudStats {
            val presets = run.packs.runstate?.sbStatPresets.orEmpty()
            val pick = presets.firstOrNull { it.id == run.config.statsPreset } ?: presets.firstOrNull { it.id == "dev-p50" } ?: presets.firstOrNull { it.floor == 7 }
            return if (pick == null) HudStats.DEFAULT else HudStats(pick.maxHp, pick.defense, pick.maxMana, pick.vitality ?: HudConstants.DEFAULT_VITALITY)
        }
    }

}
