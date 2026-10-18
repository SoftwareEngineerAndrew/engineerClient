package com.engineerclient.p3sim.clear.hud

import com.engineerclient.p3sim.pack.DungeonFrame
import com.engineerclient.p3sim.pack.layouts.DoorKind
import com.engineerclient.p3sim.run.CheckMark
import com.engineerclient.p3sim.run.DoorPhase
import com.engineerclient.p3sim.run.RoomType
import com.engineerclient.p3sim.run.RunState
import com.engineerclient.p3sim.run.api.MapCanvas
import java.util.UUID

/** A [MapCanvas] over a 128 x 128 byte array of map colour ids. */
class ByteMapCanvas(val bytes: ByteArray = ByteArray(MapModel.SIZE * MapModel.SIZE)) : MapCanvas {
    override val size: Int get() = MapModel.SIZE
    override fun set(x: Int, y: Int, color: Int) { if (x in 0 until size && y in 0 until size) bytes[y * size + x] = color.toByte() }
    override fun get(x: Int, y: Int): Int = if (x in 0 until size && y in 0 until size) bytes[y * size + x].toInt() else 0
}

/** One map decoration: a blue marker for a teammate or the frame for the viewer. [x], [y] are the signed bytes of the packet. */
data class MapMark(val frame: Boolean, val x: Int, val y: Int, val rot: Int)

/**
 * The dungeon map as Hypixel draws it (scout-client-state 3.4): a 128 x 128 raster, the 6 x 6 room grid at pixel (5,5), rooms 16
 * pixels square on a 20 pixel pitch, doors 5 x 4 in the gap, rooms of one piece joined by 16 x 4 connectors, check marks as a 9 row
 * glyph in the middle of a room's first tile. Pure; the packet is built in [HudPackets].
 *
 * What Odin reads from it (`MapScan`): pixel 0 must be empty; the entrance is a 16 or 18 wide run of colour 30; a room's type is its
 * corner pixel; its check mark is the pixel at (8, 8) of the tile; a door is the pixel at (18, 8) with an empty pixel at (18, 4).
 * Devonian wants the decoration count to equal the number of living members.
 */
object MapModel {
    const val SIZE = 128
    const val ORIGIN = 5
    const val PITCH = 20
    const val ROOM = 16

    // Map colour bytes (colour id * 4 + brightness) as recorded.
    const val C_EMPTY = 0
    const val C_UNKNOWN = 85
    const val C_QUESTION = 119
    const val C_NORMAL = 63
    const val C_TRAP = 62
    const val C_PUZZLE = 66
    const val C_YELLOW = 74
    const val C_FAIRY = 82
    const val C_BLOOD = 18
    const val C_ENTRANCE = 30
    const val C_GREEN = 30
    const val C_WHITE = 34
    const val C_RED = 18
    const val C_WITHER_DOOR = 119

    fun roomColour(t: RoomType): Int = when (t) {
        RoomType.NORMAL, RoomType.RARE -> C_NORMAL
        RoomType.PUZZLE -> C_PUZZLE
        RoomType.TRAP -> C_TRAP
        RoomType.YELLOW -> C_YELLOW
        RoomType.FAIRY -> C_FAIRY
        RoomType.BLOOD -> C_BLOOD
        RoomType.ENTRANCE -> C_ENTRANCE
    }

    // The check glyph, rows 4 to 12 of the 16 x 16 tile (recorded bitmap of the green check).
    private val CHECK = listOf(
        "...........XX...",
        "..........XXX...",
        ".........XXX....",
        "........XXX.....",
        "....XX.XXX......",
        "....XXXXX.......",
        "....XXXX........",
        ".....XXX........",
        ".....XX.........",
    )
    private const val CHECK_ROW0 = 4

    // The "?" of a room that is known but not entered: the same eleven pixels (tile-local column, row) on every one of 1,567 grey tiles
    // in twelve recorded sessions. Odin reads the pixel at (8, 8).
    private val QUESTION = listOf(6 to 5, 7 to 5, 8 to 5, 5 to 6, 9 to 6, 9 to 7, 8 to 8, 7 to 9, 7 to 10, 7 to 12, 7 to 13)

    /**
     * Whether the map shows [room] in its own colour: the entrance, a room someone has stood in, a room with a mark of its own, or a
     * room whose entrance-side door has fallen (the first door of the run reveals the room behind it before anyone walks in). A
     * room that only had its door fall, such as the Fairy room behind the free door, stays grey until somebody is in it.
     */
    fun isShown(state: RunState, room: Int): Boolean {
        val r = state.rooms[room]
        if (r.plan.type == RoomType.ENTRANCE || r.visitors.isNotEmpty()) return true
        when (r.check) {
            CheckMark.UNDISCOVERED -> return false
            CheckMark.WHITE, CheckMark.GREEN, CheckMark.RED -> return true
            else -> {}
        }
        return state.plan.doors.any { d ->
            d.kind.connected && d.kind != DoorKind.FAIRY && state.doors[d.index].phase != DoorPhase.CLOSED && (d.roomA == room || d.roomB == room) &&
                state.rooms[if (d.roomA == room) d.roomB else d.roomA].let { it.plan.type == RoomType.ENTRANCE || it.visitors.isNotEmpty() }
        }
    }

    /** The picture for the run's current state. A fresh array every call. */
    fun render(state: RunState): ByteArray {
        val canvas = ByteMapCanvas()
        renderInto(state, canvas)
        return canvas.bytes
    }

    fun renderInto(state: RunState, canvas: MapCanvas) {
        val plan = state.plan
        val shown = BooleanArray(plan.rooms.size) { isShown(state, it) }
        // A room next to a shown room but not entered is drawn as ONE grey tile with a "?": the tile on the far side of the door. Its
        // other tiles and its connectors appear only when somebody enters (the recorded map never draws a grey connector).
        val grey = LinkedHashSet<com.engineerclient.p3sim.pack.Tile>()
        for (d in plan.doors) {
            if (!d.kind.connected || d.roomA == d.roomB || d.roomA !in shown.indices || d.roomB !in shown.indices) continue
            if (shown[d.roomA] && !shown[d.roomB]) grey += d.b
            else if (shown[d.roomB] && !shown[d.roomA]) grey += d.a
        }

        for (room in state.rooms) {
            if (!shown[room.index]) continue
            val colour = roomColour(room.plan.type)
            val tiles = room.plan.tiles
            for (t in tiles) fill(canvas, px(t.x), px(t.z), ROOM, ROOM, colour)
            // One piece: join neighbouring tiles of the same room.
            val set = tiles.toSet()
            for (t in tiles) {
                if (com.engineerclient.p3sim.pack.Tile(t.x + 1, t.z) in set) fill(canvas, px(t.x) + ROOM, px(t.z), PITCH - ROOM, ROOM, colour)
                if (com.engineerclient.p3sim.pack.Tile(t.x, t.z + 1) in set) fill(canvas, px(t.x), px(t.z) + ROOM, ROOM, PITCH - ROOM, colour)
                if (com.engineerclient.p3sim.pack.Tile(t.x + 1, t.z) in set && com.engineerclient.p3sim.pack.Tile(t.x, t.z + 1) in set &&
                    com.engineerclient.p3sim.pack.Tile(t.x + 1, t.z + 1) in set) fill(canvas, px(t.x) + ROOM, px(t.z) + ROOM, PITCH - ROOM, PITCH - ROOM, colour)
            }
        }
        for (t in grey) {
            fill(canvas, px(t.x), px(t.z), ROOM, ROOM, C_UNKNOWN)
            for ((col, row) in QUESTION) canvas.set(px(t.x) + col, px(t.z) + row, C_QUESTION)
        }

        for (d in plan.doors) {
            if (!d.kind.connected || d.roomA == d.roomB || d.roomA !in shown.indices || d.roomB !in shown.indices) continue
            if (!shown[d.roomA] && !shown[d.roomB]) continue
            val rec = state.doors[d.index]
            val open = rec.phase != DoorPhase.CLOSED
            val colour = doorColour(state, d.kind, open, d.roomA, d.roomB, shown)
            if (d.axis == 'E') fill(canvas, px(d.a.x) + ROOM, px(d.a.z) + 6, PITCH - ROOM, 5, colour)
            else fill(canvas, px(d.a.x) + 6, px(d.a.z) + ROOM, 5, PITCH - ROOM, colour)
        }

        for (room in state.rooms) {
            val i = room.index
            if (!shown[i]) continue
            val first = room.plan.tiles.minWithOrNull(compareBy({ it.z }, { it.x })) ?: continue
            val x0 = px(first.x)
            val y0 = px(first.z)
            val mark = when (room.check) {
                CheckMark.WHITE -> C_WHITE
                CheckMark.GREEN -> C_GREEN
                CheckMark.RED -> C_RED
                else -> null
            }
            if (mark != null) {
                val rows = CHECK.withIndex().associate { (CHECK_ROW0 + it.index) to it.value }
                glyph(canvas, x0, y0, rows, mark)
            }
        }
    }

    private fun doorColour(state: RunState, kind: DoorKind, open: Boolean, a: Int, b: Int, shown: BooleanArray): Int {
        if (!open) when (kind) {
            DoorKind.WITHER -> return C_WITHER_DOOR
            DoorKind.BLOOD -> return C_BLOOD
            else -> {}
        }
        // The free door to the Fairy room is drawn like a wither door until somebody is in the room, whether or not it has fallen.
        if (kind == DoorKind.FAIRY && (!shown[a] || !shown[b])) return C_WITHER_DOOR
        if (!open && kind == DoorKind.FAIRY) return C_FAIRY
        // An open plain door takes the colour of the room behind it (the one found later); a door to a room not yet opened is grey.
        if (!shown[a] || !shown[b]) return C_UNKNOWN
        val ra = state.rooms[a]
        val rb = state.rooms[b]
        val later = if ((ra.tDiscovered ?: Int.MIN_VALUE) > (rb.tDiscovered ?: Int.MIN_VALUE)) ra else rb
        val pick = if (later.plan.type == RoomType.ENTRANCE) (if (later === ra) rb else ra) else later
        return roomColour(pick.plan.type)
    }

    private fun px(tile: Int) = ORIGIN + PITCH * tile

    private fun fill(c: MapCanvas, x: Int, y: Int, w: Int, h: Int, colour: Int) {
        for (yy in y until y + h) for (xx in x until x + w) c.set(xx, yy, colour)
    }

    private fun glyph(c: MapCanvas, x0: Int, y0: Int, rows: Map<Int, String>, colour: Int) {
        for ((r, line) in rows) for ((col, ch) in line.withIndex()) if (ch == 'X') c.set(x0 + col, y0 + r, colour)
    }

    /**
     * Decorations for [viewer]'s map: every living teammate as a blue marker in tab order, then the viewer's own frame. A ghost teammate
     * has none (the count equals the number of living members, which Devonian checks; a teammate's death in the clear is not recorded).
     * The viewer's own frame stays after their death: the recorded count did not drop. [yaw] gives a member's facing in degrees (0 when unknown).
     */
    fun decorations(ctx: HudContext, viewer: UUID?, yaw: (UUID) -> Float = { 0f }): List<MapMark> {
        val out = ArrayList<MapMark>()
        val members = ctx.membersFor(viewer)
        for (p in members) {
            if (p.ghost || p.uuid == viewer) continue
            out += mark(false, p.x, p.z, yaw(p.uuid))
        }
        val me = members.firstOrNull { it.uuid == viewer }
        if (me != null) out += mark(true, me.x, me.z, yaw(me.uuid))
        return out
    }

    /**
     * The map pixel of a world coordinate: `floor((world + 200) * 20 / 32 + origin + c)`, with the recorded offsets [OFFSET_X] and
     * [OFFSET_Z] (a fit to 369 stationary samples, exact on about 85 percent). The packet byte is `2 * pixel - 128`, always even as on
     * Hypixel, clamped. The rotation is 0..15 sixteenths of a turn.
     */
    fun mark(frame: Boolean, x: Double, z: Double, yawDeg: Float): MapMark {
        fun b(v: Double, c: Double): Int = (2 * Math.floor((v - DungeonFrame.ORIGIN) * PITCH / DungeonFrame.PITCH + ORIGIN + c).toInt() - 128).coerceIn(-128, 126)
        val rot = Math.floorMod(Math.round(yawDeg / 22.5f), 16)
        return MapMark(frame, b(x, OFFSET_X), b(z, OFFSET_Z), rot)
    }

    const val OFFSET_X = 4.9
    const val OFFSET_Z = 5.2
}
