package com.engineerclient.tpmaze

import com.engineerclient.EngineerClient
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.odtheking.odin.OdinMod.mc
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.DropdownSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.RoomEnterEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.impl.dungeon.map.tile.DungeonRoom
import com.odtheking.odin.features.impl.dungeon.puzzlesolvers.PuzzleSolvers
import com.odtheking.odin.features.impl.render.ClickGUIModule
import com.odtheking.odin.utils.Color.Companion.withAlpha
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.network.webSocket
import com.odtheking.odin.utils.render.drawFilledBox
import com.odtheking.odin.utils.render.drawText
import com.odtheking.odin.utils.render.drawTracer
import com.odtheking.odin.utils.render.drawWireFrameBox
import com.odtheking.odin.utils.skyblock.LocationUtils
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.Puzzle
import com.odtheking.odin.utils.skyblock.dungeon.PuzzleStatus
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket
import net.minecraft.world.entity.Relative
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Team TP Maze, on Odin's Puzzle Solvers: one map of the Teleport Maze for the whole party, so a team stacked in the maze (leaping
 * in to whoever is there when it's the last room) splits up instead of trying the same pads.
 *
 * Every pad pair anyone learns goes into a shared [MazeMap] in pad numbers, which are the same on every client however the room is
 * turned ([MazePads]): your own teleports (the pad you stood on, the pad you landed beside, and where the landing turned you to
 * look, which is always at the final pad), teammates' teleports seen in the world (a guess until their own client says), and the
 * maps of teammates who run this too, over Odin's websocket. The connection is Odin's lobby channel, a second one beside Odin's map
 * sync like [com.engineerclient.clearbreakdown.OdinSync]: the messages carry none of the fields Odin's map or Melody read, so their
 * handlers drop them. Each message is the sender's whole map (at most 15 pairs), sent when it changes; a client that comes in asks
 * for everyone's.
 *
 * From the map, [MazePlan] gives everyone in the maze a pad: the final pad once you're in its cell, a known way to its cell, else an
 * unused pad of your cell split by name between the teammates there who run this, else known pads to the nearest cell with unused ones. Your pad
 * is drawn with a tracer; teammates' pads in your cell carry their names; used pads say where they lead. Odin's own TP Maze boxes
 * are left out while this draws (TpMazeRenderMixin).
 */
object TeamTpMaze {
    private val dropdown = DropdownSetting("Team TP Maze", desc = "Settings for the shared TP Maze map. Added by engineerClient.")
    private val on = BooleanSetting("Team Maze Solver", true,
        desc = "One TP Maze map for the whole party: every pad pair anyone learns is shared over Odin's websocket, teammates in the same cell are given different unused pads, and you're shown yours. Draws instead of Odin's TP Maze boxes. Added by engineerClient.").withDependency { dropdown.value }
    private val tracer = BooleanSetting("Team Maze Tracer", true, desc = "A line to the pad you should take next.").withDependency { dropdown.value && on.value }
    private val labels = BooleanSetting("Team Maze Labels", true,
        desc = "Over each used pad, where it leads; over a teammate's next pad in your cell, their name.").withDependency { dropdown.value && on.value }
    private val nextColor = ColorSetting("Your Pad Color", Colors.MINECRAFT_GREEN.withAlpha(.6f), true, desc = "The pad you should take next.").withDependency { dropdown.value && on.value }
    private val finalColor = ColorSetting("Final Pad Color", Colors.MINECRAFT_GOLD.withAlpha(.6f), true,
        desc = "The pad into the end (an outline while it could still be one of a few).").withDependency { dropdown.value && on.value }
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

    private val socket = webSocket {
        onMessage { text -> mc.execute { EngineerClient.safely("team tp maze message") { receive(text) } } }
    }

    private val self: String get() = mc.player?.name?.string ?: ""

    fun install() {
        for (s in listOf(dropdown, on, tracer, labels, nextColor, finalColor, usedColor, mateColor)) PuzzleSolvers.registerSetting(s)
        on<LevelEvent.Load> { EngineerClient.safely("team tp maze reset") { reset() } }
        on<RoomEnterEvent> { EngineerClient.safely("team tp maze room") { room?.takeIf { it.data?.name == ROOM }?.let { enter(it) } } }
        // Teleports arrive on the network thread, before the game moves anyone: where they stood is read now, the rest on the client thread.
        onReceive<ClientboundPlayerPositionPacket> {
            if (!active()) return@onReceive
            val p = mc.player ?: return@onReceive
            val c = change
            val x = if (Relative.X in relatives) p.x + c.position.x else c.position.x
            val y = if (Relative.Y in relatives) p.y + c.position.y else c.position.y
            val z = if (Relative.Z in relatives) p.z + c.position.z else c.position.z
            val yaw = if (Relative.Y_ROT in relatives) p.yRot + c.yRot else c.yRot
            val fromX = p.x; val fromZ = p.z
            mc.execute { EngineerClient.safely("team tp maze teleport") { teleport(fromX, fromZ, x, y, z, yaw.toDouble(), exact = true) } }
        }
        // A teammate's teleport, seen in the world: one of these two packets, depending on how the server sends it.
        onReceive<ClientboundTeleportEntityPacket> {
            val e = teammate(id) ?: return@onReceive
            val c = change
            val x = if (Relative.X in relatives) e.x + c.position.x else c.position.x
            val y = if (Relative.Y in relatives) e.y + c.position.y else c.position.y
            val z = if (Relative.Z in relatives) e.z + c.position.z else c.position.z
            val yaw = if (Relative.Y_ROT in relatives) e.yRot + c.yRot else c.yRot
            seen(e, x, y, z, yaw)
        }
        onReceive<ClientboundEntityPositionSyncPacket> {
            if (!hasPosition() || !hasRotation()) return@onReceive
            val e = teammate(id) ?: return@onReceive
            val to = position.endPosition()
            seen(e, to.x, to.y, to.z, yRot)
        }
        on<TickEvent.End> { EngineerClient.safely("team tp maze tick") { tick() } }
        on<RenderExtractEvent> { EngineerClient.safely("team tp maze render") { render(this) } }
        EventBus.subscribe(this)
    }

    /** A teammate (not us) by entity id, while this is on. */
    private fun teammate(id: Int): Player? {
        if (!active()) return null
        val e = mc.level?.getEntity(id) as? Player ?: return null
        return e.takeIf { it !== mc.player && DungeonUtils.dungeonTeammatesNoSelf.any { t -> t.name == it.name.string } }
    }

    /** A teammate's teleport from where the server last had them (the entity itself may still be drawn easing there) to (x, y, z). */
    private fun seen(e: Player, x: Double, y: Double, z: Double, yaw: Float) {
        val from = e.positionCodec.base
        mc.execute { EngineerClient.safely("team tp maze teammate") { teleport(from.x, from.z, x, y, z, yaw.toDouble(), exact = false) } }
    }

    /** Odin's own TP Maze boxes are left out (TpMazeRenderMixin) while this draws. */
    @JvmStatic fun drawsInstead(): Boolean = active() && frame != null

    private fun active(): Boolean = on.value && PuzzleSolvers.enabled && DungeonUtils.inDungeons && Puzzle.TP_MAZE.status != PuzzleStatus.Completed

    private fun reset() {
        map = MazeMap(); frame = null; mazeRoom = null; plan = emptyMap(); where = emptyMap()
        dirty = false; cooldown = 0; heard.clear()
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

    /** The room's frame from three of its blocks, once Odin has found its corner and turn (all zero until then). */
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
        val f = frame ?: return
        if (kotlin.math.abs(y - LANDING_Y) > 0.01) return
        val (rx, rz) = f.toRoom(x, z).let { it[0] to it[1] }
        val land = MazePads.landingAt(rx, rz)
        if (land < 0) return
        val (ox, oz) = f.toRoom(fromX, fromZ).let { it[0] to it[1] }
        // A pad's teleport starts in the maze (a leap onto a teammate standing on a landing cell doesn't), and no pad leads into its own
        // cell: a "landing" where you already were (a setback, a position resync) is no pad's.
        val origin = MazePads.regionAt(ox, oz)
        if (origin < 0 || kotlin.math.hypot(ox - rx, oz - rz) < 1.5 || origin == MazePads.cell(land)) return
        val from = MazePads.padUnder(ox, oz, REACH)
        val ryaw = f.yawToRoom(yaw)
        var changed = false
        if (land == MazePads.END) {
            if (from in 0 until 28) changed = map.link(from, MazePads.END, exact)
        } else {
            // A setback (Hypixel putting you back) keeps your own yaw: it looks at no pad, and isn't a pad's teleport.
            if (MazePads.aim(land, ryaw).isEmpty()) return
            changed = map.look(land, ryaw)
            if (from >= 0) changed = map.link(from, land, exact) or changed
        }
        if (changed) dirty = true
    }

    private fun tick() {
        if (!active()) { if (connected && DungeonUtils.inDungeons && Puzzle.TP_MAZE.status == PuzzleStatus.Completed) disconnect(); return }
        val room = mazeRoom ?: return
        if (frame == null) frame = frameOf(room) ?: return
        val f = frame!!
        // Everyone in the maze who plays by the plan, and where: us, and the teammates whose client we've heard from.
        val w = HashMap<String, Int>()
        mc.player?.let { p -> f.toRoom(p.x, p.z).let { r -> MazePads.regionAt(r[0], r[1]).takeIf { it >= 0 }?.let { w[self] = it } } }
        for (t in DungeonUtils.dungeonTeammatesNoSelf) {
            val e = t.entity ?: continue
            if (t.isDead || t.name !in heard) continue
            f.toRoom(e.x, e.z).let { r -> MazePads.regionAt(r[0], r[1]).takeIf { it >= 0 }?.let { w[t.name] = it } }
        }
        where = w
        plan = MazePlan.plan(map, w)
        // The websocket: say hello once it's open (everyone answers with their map), then our map whenever it changes.
        if (connected && !socket.connected && ++waiting > RETRY) connect()
        if (connected && socket.connected) {
            waiting = 0
            if (!helloSent) { helloSent = socket.send(message(hello = true)); if (helloSent) dirty = false }
            if (cooldown > 0) cooldown--
            else if (dirty) {
                // Nothing to tell (a hello we can't add to): stay quiet.
                if (map.links(sureOnly = true).isEmpty() && map.exit == null && map.looks().isEmpty()) dirty = false
                else if (socket.send(message(hello = false))) { dirty = false; cooldown = 4 }
            }
        }
    }

    // ---- the websocket ----------------------------------------------------------------------------------------------------------

    /** Our map as a message. No "playerName", "username" or "roomName": Odin's map and Melody handlers drop it. */
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
        if (map.merge(links, exit, looks)) dirty = true
        // Someone just came in: everyone sends their map (ours goes out on the next tick).
        if (o.get("hello")?.asBoolean == true) dirty = true
    }

    // ---- drawing ----------------------------------------------------------------------------------------------------------------

    private fun render(event: RenderExtractEvent) {
        if (!active() || DungeonUtils.currentRoomName != ROOM) return
        val room = mazeRoom ?: return
        val mine = plan[self]
        val myCell = where[self]
        val fin = map.final
        val maybe = map.candidates().toSet()
        val mates = plan.filter { (name, a) -> name != self && where[name] == myCell && a.pad != mine?.pad }
        for (p in 0 until 30) {
            val b = room.getRealCoords(BlockPos(MazePads.FRAMES[p][0], 69, MazePads.FRAMES[p][1]))
            val box = AABB(b.x.toDouble(), b.y.toDouble(), b.z.toDouble(), b.x + 1.0, b.y + FRAME_TOP, b.z + 1.0)
            val top = Vec3(b.x + 0.5, b.y + FRAME_TOP + 0.6, b.z + 0.5)
            when {
                p == mine?.pad -> event.drawFilledBox(box, nextColor.value, false)
                p == fin -> event.drawFilledBox(box, finalColor.value, false)
                mates.values.any { it.pad == p } -> event.drawFilledBox(box, mateColor.value, false)
                map.known(p) && p < 28 -> event.drawFilledBox(box, usedColor.value, true)
            }
            if (fin == null && p in maybe && maybe.size <= 4) event.drawWireFrameBox(box, finalColor.value, 2f, false)
            if (labels.value) {
                val who = mates.filter { it.value.pad == p }.keys.sorted()
                when {
                    who.isNotEmpty() -> event.drawText(who.joinToString(", "), top, 1f, false)
                    p < 28 && p != mine?.pad -> map.partnerOf(p)?.let { q -> event.drawText("→ ${if (q == MazePads.START) "start" else MazePads.label(q)}", top, 0.8f, true) }
                }
            }
        }
        if (tracer.value && mine != null) {
            val b = room.getRealCoords(BlockPos(MazePads.FRAMES[mine.pad][0], 69, MazePads.FRAMES[mine.pad][1]))
            event.drawTracer(Vec3(b.x + 0.5, b.y + FRAME_TOP, b.z + 0.5), nextColor.value, false)
        }
    }
}
