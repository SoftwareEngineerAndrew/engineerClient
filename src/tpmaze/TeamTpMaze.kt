package com.engineerclient.tpmaze

import com.engineerclient.EngineerClient
import com.engineerclient.pov.PoseShare
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.adm.AdmMod.mc
import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.Setting
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.clickgui.settings.impl.DropdownSetting
import com.adm.events.EntityEvent
import com.adm.events.LevelEvent
import com.adm.events.RenderExtractEvent
import com.adm.events.RoomEnterEvent
import com.adm.events.TickEvent
import com.adm.events.core.EventBus
import com.adm.events.core.on
import com.adm.features.impl.dungeon.map.tile.DungeonRoom
import com.adm.features.impl.dungeon.puzzlesolvers.PuzzleSolvers
import com.adm.features.impl.render.ClickGUIModule
import com.adm.utils.Color.Companion.withAlpha
import com.adm.utils.Colors
import com.adm.utils.network.webSocket
import com.adm.utils.render.drawFilledBox
import com.adm.utils.render.drawText
import com.adm.utils.render.drawTracer
import com.adm.utils.render.drawWireFrameBox
import com.adm.utils.skyblock.LocationUtils
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.adm.utils.skyblock.dungeon.Puzzle
import com.adm.utils.skyblock.dungeon.PuzzleStatus
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.world.entity.Relative
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Team TP Maze, on ADM's Puzzle Solvers: one map of the Teleport Maze for the whole party, so a team stacked in the maze (leaping
 * in to whoever is there when it's the last room) splits up instead of trying the same pads.
 *
 * Every pad pair anyone learns goes into a shared [MazeMap] in pad numbers, which are the same on every client however the room is
 * turned ([MazePads]): your own teleports (the pad you stood on, the pad you landed beside, and where the landing turned you to
 * look, which is always at the final pad), teammates' teleports seen in the world (a guess until their own client says), and the
 * maps of teammates who run this too, over ADM's party link ([com.engineerclient.party.OdinLink]) and ADM's websocket for the rest.
 * The socket is ADM's lobby channel, a second one beside ADM's map
 * sync like [com.engineerclient.clearbreakdown.OdinSync]: the messages carry none of the fields ADM's map or Melody read, so their
 * handlers drop them. Each message is the sender's whole map (at most 15 pairs), sent when it changes; a client that comes in asks
 * for everyone's.
 *
 * From the map, [MazePlan] gives everyone in the maze a pad: the final pad once you're in its cell, a known way to its cell, else an
 * unused pad of your cell split by name between the teammates there who run this, else known pads to the nearest cell with unused ones. Your pad
 * is drawn with a tracer; teammates' pads in your cell carry their names; used pads say where they lead. ADM's own TP Maze solver
 * is switched off (TpMazeRenderMixin) and its settings are replaced by these, in the same spot.
 */
object TeamTpMaze {
    private val dropdown = DropdownSetting("Team TP Maze", desc = "Settings for the shared TP Maze map. Added by engineerClient.")
    private val on = BooleanSetting("Team Maze Solver", true,
        desc = "One TP Maze map for the whole party: every pad pair anyone learns is shared with the party, teammates in the same cell are given different unused pads, and you're shown yours. Replaces ADM's TP Maze solver. Added by engineerClient.").withDependency { dropdown.value }
    private val tracer = BooleanSetting("Team Maze Tracer", true, desc = "A line to the pad you should take next.").withDependency { dropdown.value && on.value }
    private val labels = BooleanSetting("Team Maze Labels", true,
        desc = "Over each used pad, where it leads; over a teammate's next pad in your cell, their name.").withDependency { dropdown.value && on.value }
    // ADM saves colours by setting name: these two are new names so the new defaults reach anyone who has the old ones saved.
    private val nextColor = ColorSetting("Recommended Pad Color", Colors.MINECRAFT_YELLOW.withAlpha(.6f), true,
        desc = "The pad you should take next and the line to it, and the outlines while the answer could still be one of a few.").withDependency { dropdown.value && on.value }
    private val finalColor = ColorSetting("Known Answer Color", Colors.MINECRAFT_GREEN.withAlpha(.6f), true,
        desc = "The pad into the end once it's known, and the line to it.").withDependency { dropdown.value && on.value }
    private val usedColor = ColorSetting("Used Pad Color", Colors.MINECRAFT_RED.withAlpha(.35f), true,
        desc = "Pads the party already knows the way of.").withDependency { dropdown.value && on.value }
    private val mateColor = ColorSetting("Teammate Pad Color", Colors.MINECRAFT_AQUA.withAlpha(.45f), true,
        desc = "A teammate's next pad in your cell.").withDependency { dropdown.value && on.value }

    private const val ROOM = "Teleport Maze"
    /** The top of an end-portal frame. */
    private const val FRAME_TOP = 0.8125
    private const val LANDING_Y = 69.5
    /** How far from a frame's middle a player still stands on it (the recordings: within 1.0 when on top of it). */
    private const val REACH = 1.2

    private var map = MazeMap()
    private var frame: RoomFrame? = null
    private var mazeRoom: DungeonRoom? = null
    private var plan: Map<String, MazePlan.Advice> = emptyMap()
    private var where: Map<String, Int> = emptyMap()
    /** The map changed since we last sent it; ticks until we may send again. */
    private var dirty = false
    private var cooldown = 0
    private var helloSent = false
    private var connected = false
    /** Ticks since we asked for the connection, while it isn't open: past [RETRY] we ask again. */
    private var waiting = 0
    private const val RETRY = 200
    /** Teammates whose client has said something on the channel: only they are given pads (anyone else won't see theirs). */
    private val heard = HashSet<String>()
    /** Teammates' names, and each one's last server position, while we're in the maze. */
    private val mates = HashSet<String>()
    private val last = HashMap<Int, Vec3>()
    /** The map changed or we left the maze since the plan was made. */
    private var stale = true
    private val seenNow = HashMap<String, Int>()

    // Pad boxes for the room's frame, and what each pad shows, made again only when the plan or the map changes.
    private var padsFrame: RoomFrame? = null
    private var padsRoom: DungeonRoom? = null
    private val padBoxes = arrayOfNulls<AABB>(30)
    private val padTops = arrayOfNulls<Vec3>(30)
    private val padAims = arrayOfNulls<Vec3>(30)
    private const val NONE = 0
    private const val FINAL = 1
    private const val NEXT = 2
    private const val MATE = 3
    private const val USED = 4
    private val padKind = IntArray(30)
    private val padOutlined = BooleanArray(30)
    private val padLabel = arrayOfNulls<String>(30)
    private val padLabelIsName = BooleanArray(30)
    private var minePad = -1

    internal val socket = webSocket {
        onMessage { text -> mc.execute { EngineerClient.safely("team tp maze message") { this@TeamTpMaze.receive(text) } } }
    }

    private val self: String get() = mc.player?.name?.string ?: ""

    fun install() {
        replaceCoreSettings(listOf(dropdown, on, tracer, labels, nextColor, finalColor, usedColor, mateColor))
        on<LevelEvent.Load> { EngineerClient.safely("team tp maze reset") { reset() } }
        on<RoomEnterEvent> { EngineerClient.safely("team tp maze room") { room?.takeIf { it.data?.name == ROOM }?.let { enter(it) } } }
        // A teammate's: every server position of theirs comes through here, so the last one is where they stood.
        on<EntityEvent.Move> {
            if (!inMaze()) return@on
            val e = entity as? Player ?: return@on
            if (e === mc.player || e.name.string !in mates) return@on
            val from = last.put(e.id, newPos) ?: return@on
            EngineerClient.safely("team tp maze teammate") { teleport(from.x, from.z, newPos.x, newPos.y, newPos.z, yRot.toDouble(), exact = false) }
        }
        on<TickEvent.End> { EngineerClient.safely("team tp maze tick") { tick() } }
        on<RenderExtractEvent> { EngineerClient.safely("team tp maze render") { render(this) } }
        EventBus.subscribe(this)
    }

    /** TpMazeTeleportMixin: our own teleport, on the client thread just before the game moves us, so where we stood is still known. */
    @JvmStatic
    fun ownTeleport(packet: ClientboundPlayerPositionPacket) {
        val p = mc.player ?: return
        val c = packet.change; val rel = packet.relatives
        val x = if (Relative.X in rel) p.x + c.position.x else c.position.x
        val y = if (Relative.Y in rel) p.y + c.position.y else c.position.y
        val z = if (Relative.Z in rel) p.z + c.position.z else c.position.z
        if (!inMaze()) {
            if (kotlin.math.abs(y - LANDING_Y) < 0.01 && DungeonUtils.inDungeons) log("skipped: room ${DungeonUtils.currentRoomName}, maze ${mazeRoom != null}, active ${active()}")
            return
        }
        val yaw = if (Relative.Y_ROT in rel) p.yRot + c.yRot else c.yRot
        EngineerClient.safely("team tp maze teleport") { teleport(p.x, p.z, x, y, z, yaw.toDouble(), exact = true) }
    }

    /** ADM's TP Maze settings, whose solver is switched off (TpMazeRenderMixin): ours go in their place in Puzzle Solvers. */
    private val CORE_MAZE = listOf("mazeDropDown", "tpMaze", "mazeColorOne", "mazeColorMultiple", "mazeColorVisited", "mazeShowTracer", "mazeTracerColor", "mazeReset")

    private fun replaceCoreSettings(ours: List<Setting<*>>) {
        val old = CORE_MAZE.mapNotNull { n ->
            runCatching { PuzzleSolvers::class.java.getDeclaredField("$n\$delegate").apply { isAccessible = true }.get(PuzzleSolvers) }.getOrNull()
        }.toSet()
        val settings = PuzzleSolvers.settings
        if (old.isEmpty() || settings.values.none { it in old }) { for (s in ours) PuzzleSolvers.registerSetting(s); return }
        val rebuilt = LinkedHashMap<String, Setting<*>>()
        var placed = false
        for ((k, v) in settings) {
            if (v in old) { if (!placed) { for (s in ours) rebuilt[s.name] = s; placed = true }; continue }
            rebuilt[k] = v
        }
        settings.clear(); settings.putAll(rebuilt)
    }

    private fun active(): Boolean = on.value && PuzzleSolvers.enabled && DungeonUtils.inDungeons && Puzzle.TP_MAZE.status != PuzzleStatus.Completed

    private fun inMaze(): Boolean = DungeonUtils.currentRoomName == ROOM && mazeRoom != null && active()

    private fun reset() {
        map = MazeMap(); frame = null; mazeRoom = null; plan = emptyMap(); where = emptyMap()
        dirty = false; cooldown = 0; heard.clear(); mates.clear(); last.clear(); stale = true
        padsFrame = null; padsRoom = null; minePad = -1; padKind.fill(NONE); padOutlined.fill(false); padLabel.fill(null)
        disconnect()
    }

    private fun enter(room: DungeonRoom) {
        mazeRoom = room
        frameOf(room)?.let { f -> if (frame?.sameAs(f) != true) frame = f }
        if (on.value && !connected) connect()
    }

    private fun connect() {
        val lobby = LocationUtils.lobbyId ?: return
        socket.connect("${ClickGUIModule.webSocketUrl}$lobby")
        connected = true; helloSent = false; waiting = 0
    }

    private fun disconnect() {
        if (connected) socket.shutdown()
        connected = false; helloSent = false; waiting = 0
    }

    /** The room's frame from three of its blocks, once ADM has found its corner and turn (all zero until then). */
    private fun frameOf(room: DungeonRoom): RoomFrame? {
        val o = room.getRealCoords(BlockPos(0, 69, 0))
        val x = room.getRealCoords(BlockPos(1, 69, 0))
        val z = room.getRealCoords(BlockPos(0, 69, 1))
        if (o == BlockPos.ZERO || x == BlockPos.ZERO) return null
        return RoomFrame(o.x, o.z, x.x - o.x, x.z - o.z, z.x - o.x, z.z - o.z)
    }

    /**
     * A teleport from (fromX, fromZ) to (x, y, z) looking [yaw] (world): a pad's if it lands on a landing cell's middle at slab height
     * and turns you to look at a pad, or into the end. [exact]: our own; a teammate's seen from outside is a guess.
     */
    private fun teleport(fromX: Double, fromZ: Double, x: Double, y: Double, z: Double, yaw: Double, exact: Boolean) {
        val f = frame ?: return log("no frame: to %.2f %.2f %.2f".format(x, y, z))
        if (kotlin.math.abs(y - LANDING_Y) > 0.01) return
        val (rx, rz) = f.toRoom(x, z).let { it[0] to it[1] }
        val land = MazePads.landingAt(rx, rz)
        val (ox, oz) = f.toRoom(fromX, fromZ).let { it[0] to it[1] }
        if (land < 0) return log("no landing: room %.2f %.2f from %.2f %.2f".format(rx, rz, ox, oz))
        val changed = map.teleport(ox, oz, land, f.yawToRoom(yaw), exact, REACH)
        log("${if (exact) "own" else "mate"} %s -> %s yaw %.1f from %.2f %.2f: %s".format(
            MazePads.padUnder(ox, oz, REACH).let { if (it >= 0) MazePads.label(it) else "?" }, MazePads.label(land), f.yawToRoom(yaw), ox, oz, if (changed) "recorded" else "nothing new"))
        if (changed) { dirty = true; stale = true }
    }

    private fun log(s: String) = com.engineerclient.rotation.EcLog.log("TPMAZE", s)

    private fun tick() {
        if (!active()) { if (connected && DungeonUtils.inDungeons && Puzzle.TP_MAZE.status == PuzzleStatus.Completed) disconnect(); return }
        val room = mazeRoom ?: return
        if (frame == null) frame = frameOf(room) ?: return
        val f = frame!!
        if (DungeonUtils.currentRoomName != ROOM) {
            if (where.isNotEmpty() || plan.isNotEmpty()) { plan = emptyMap(); where = emptyMap() }
            last.clear(); stale = true; send(); return
        }
        mates.clear()
        for (t in DungeonUtils.dungeonTeammatesNoSelf) mates.add(t.name)
        // Everyone in the maze who plays by the plan, and where: us, and the teammates whose client we've heard from.
        val w = seenNow
        w.clear()
        mc.player?.let { p -> f.toRoom(p.x, p.z).let { r -> MazePads.regionAt(r[0], r[1]).takeIf { it >= 0 }?.let { w[self] = it } } }
        for (t in DungeonUtils.dungeonTeammatesNoSelf) {
            val e = t.entity ?: continue
            if (t.isDead || t.name !in heard) continue
            f.toRoom(e.x, e.z).let { r -> MazePads.regionAt(r[0], r[1]).takeIf { it >= 0 }?.let { w[t.name] = it } }
        }
        if (stale || w != where) {
            stale = false
            where = HashMap(w)
            plan = MazePlan.plan(map, where)
            buildView()
        }
        send()
    }

    private fun buildView() {
        val mine = plan[self]
        val myCell = where[self]
        val fin = map.final
        val maybe = map.candidates()
        val outline = fin == null && maybe.size <= 4
        minePad = mine?.pad ?: -1
        padKind.fill(NONE)
        padOutlined.fill(false)
        padLabel.fill(null)
        for ((name, a) in plan.entries.sortedBy { it.key }) {
            if (name == self || where[name] != myCell || a.pad == minePad) continue
            padLabel[a.pad] = padLabel[a.pad]?.let { "$it, $name" } ?: name
        }
        for (p in 0 until 30) {
            padLabelIsName[p] = padLabel[p] != null
            padKind[p] = when {
                p == fin -> FINAL
                p == minePad -> NEXT
                padLabelIsName[p] -> MATE
                map.known(p) && p < 28 -> USED
                else -> NONE
            }
            if (outline && p in maybe) padOutlined[p] = true
            if (!padLabelIsName[p] && p < 28 && p != minePad)
                padLabel[p] = map.partnerOf(p)?.let { q -> "→ ${if (q == MazePads.START) "start" else MazePads.label(q)}" }
        }
    }

    private fun placePads(room: DungeonRoom) {
        val f = frame
        if (padsRoom === room && padsFrame === f) return
        for (p in 0 until 30) {
            val b = room.getRealCoords(BlockPos(MazePads.FRAMES[p][0], 69, MazePads.FRAMES[p][1]))
            padBoxes[p] = AABB(b.x.toDouble(), b.y.toDouble(), b.z.toDouble(), b.x + 1.0, b.y + FRAME_TOP, b.z + 1.0)
            padTops[p] = Vec3(b.x + 0.5, b.y + FRAME_TOP + 0.6, b.z + 0.5)
            padAims[p] = Vec3(b.x + 0.5, b.y + FRAME_TOP, b.z + 0.5)
        }
        padsRoom = room
        padsFrame = f
    }

    private fun send() {
        // The websocket: say hello once it's open (everyone answers with their map), then our map whenever it changes.
        if (connected && !socket.connected && ++waiting > RETRY) connect()
        // Through the party link first (OdinLink), so it works with the socket down.
        val linked = PoseShare.linkedCount > 0
        if (connected && (socket.connected || linked)) {
            if (socket.connected) waiting = 0
            if (!helloSent) { helloSent = socket.send(message(hello = true)) || linked; if (helloSent) dirty = false }
            if (cooldown > 0) cooldown--
            else if (dirty) {
                // Nothing to tell (a hello we can't add to): stay quiet.
                if (map.links(sureOnly = true).isEmpty() && map.exit == null && map.looks().isEmpty()) dirty = false
                else if (socket.send(message(hello = false)) || linked) { dirty = false; cooldown = 4 }
            }
        }
    }

    // ---- the websocket ----------------------------------------------------------------------------------------------------------

    /** Our map as a message. No "playerName", "username" or "roomName": ADM's map and Melody handlers drop it. */
    private fun message(hello: Boolean): String {
        val o = JsonObject()
        o.addProperty("ecTpMaze", 1)
        o.addProperty("from", self)
        frame?.anchor?.let { a -> o.add("at", JsonArray().apply { add(a[0]); add(a[1]) }) }
        if (hello) o.addProperty("hello", true)
        o.add("links", JsonArray().apply { for (l in map.links(sureOnly = true)) add(JsonArray().apply { add(l[0]); add(l[1]) }) })
        map.exit?.let { o.addProperty("exit", it) }
        o.add("looks", JsonArray().apply { for ((land, yaw) in map.looks()) add(JsonArray().apply { add(land); add(Math.round(yaw * 100) / 100.0) }) })
        return o.toString()
    }

    private fun receive(text: String) {
        val o = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return
        if (o.get("ecTpMaze")?.asInt != 1) return
        val from = o.get("from")?.asString ?: return
        if (from == self || DungeonUtils.dungeonTeammates.none { it.name == from }) return
        heard += from
        // Their maze is ours: the same room corner.
        val at = o.getAsJsonArray("at")
        val anchor = frame?.anchor
        if (at != null && anchor != null && (at.size() != 2 || at[0].asInt != anchor[0] || at[1].asInt != anchor[1])) return
        val links = o.getAsJsonArray("links")?.mapNotNull { e -> e.asJsonArray.takeIf { it.size() == 2 }?.let { intArrayOf(it[0].asInt, it[1].asInt) } }.orEmpty()
        val exit = o.get("exit")?.asInt
        val looks = o.getAsJsonArray("looks")?.mapNotNull { e -> e.asJsonArray.takeIf { it.size() == 2 }?.let { it[0].asInt to it[1].asDouble } }?.toMap().orEmpty()
        if (map.merge(links, exit, looks)) { dirty = true; stale = true }
        // Someone just came in: everyone sends their map (ours goes out on the next tick).
        if (o.get("hello")?.asBoolean == true) dirty = true
    }

    // ---- drawing ----------------------------------------------------------------------------------------------------------------

    private fun render(event: RenderExtractEvent) {
        if (!active() || DungeonUtils.currentRoomName != ROOM) return
        val room = mazeRoom ?: return
        placePads(room)
        val showLabels = labels.value
        for (p in 0 until 30) {
            val box = padBoxes[p] ?: continue
            when (padKind[p]) {
                FINAL -> event.drawFilledBox(box, finalColor.value, false)
                NEXT -> event.drawFilledBox(box, nextColor.value, false)
                MATE -> event.drawFilledBox(box, mateColor.value, false)
                USED -> event.drawFilledBox(box, usedColor.value, true)
            }
            if (padOutlined[p]) event.drawWireFrameBox(box, nextColor.value, 2f, false)
            if (showLabels) padLabel[p]?.let { text ->
                if (padLabelIsName[p]) event.drawText(text, padTops[p]!!, 1f, false) else event.drawText(text, padTops[p]!!, 0.8f, true)
            }
        }
        if (tracer.value && minePad >= 0) padAims[minePad]?.let { event.drawTracer(it, if (padKind[minePad] == FINAL) finalColor.value else nextColor.value, false) }
    }
}
