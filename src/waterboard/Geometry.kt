package com.engineerclient.waterboard

/**
 * One Water Board's plane in room coordinates (Odin's frame, `DungeonRoom.getRealCoords`): which cells are open, where the
 * lever-driven blocks ("slots") are and whose lever moves them, the outlets, the inlet; plus the order Minecraft 1.8 visits a
 * block's neighbours in, which depends on how the room is turned.
 *
 * Read from the world ([parse] takes the rows the room's blocks give, one char per cell), not from a list of known boards.
 */
class Geometry private constructor(
    val rotation: String,
    /** Cells water can be in when no block is: the board's air, the inlet column, the channel and the outlet pits. */
    val open: IntArray,
    val slotCell: IntArray,
    /** Per slot: the lever (index into [LEVERS]) that moves it. */
    val slotLever: IntArray,
) {
    val w = X1 - X0 + 1
    val h = Y1 - Y0 + 1
    val source = cell(15, 82)
    /** Outlet pits, in Odin's colour order: purple, orange, blue, green, red. */
    val outletCell = IntArray(OUTLET_X.size) { cell(OUTLET_X[it], 58) }

    /** 1.8's EnumFacing.Plane.HORIZONTAL order (N, E, S, W) on the board's axis, as cell offsets (+1 = room +x). */
    val horizontal: IntArray
    /** EnumSet order (N, S, W, E): the order water spreads to the directions it picked. */
    val setOrder: IntArray
    /** World.notifyNeighborsOfStateChange order (W, E, down, up, N, S), the board's four. */
    val notifyOrder: IntArray

    init {
        val plus = when (rotation) { "NORTH" -> 'W'; "WEST" -> 'S'; "SOUTH" -> 'E'; "EAST" -> 'N'; else -> error("rotation $rotation") }
        val minus = mapOf('N' to 'S', 'S' to 'N', 'E' to 'W', 'W' to 'E')[plus]!!
        fun rel(f: Char): Int? = if (f == plus) 1 else if (f == minus) -1 else null
        horizontal = "NESW".mapNotNull { rel(it) }.toIntArray()
        setOrder = "NSWE".mapNotNull { rel(it) }.toIntArray()
        notifyOrder = "WEDUNS".mapNotNull { f -> when (f) { 'D' -> -w; 'U' -> w; else -> rel(f) } }.toIntArray()
    }

    fun cell(x: Int, y: Int): Int = (y - Y0) * w + (x - X0)
    fun x(c: Int): Int = c % w + X0
    fun y(c: Int): Int = c / w + Y0
    fun slotAt(c: Int): Int = slotCell.indexOf(c)

    /** The same board turned another way (tests). */
    fun turned(rot: String) = Geometry(rot, open, slotCell, slotLever)

    companion object {
        /** The plane, padded by a solid ring so every neighbour exists. */
        const val X0 = 4; const val X1 = 26; const val Y0 = 56; const val Y1 = 83
        /** The board proper: x 5..25 (walls at the ends), y 57..80, one char per cell in [parse]'s rows. */
        const val BX0 = 5; const val BX1 = 25; const val BY0 = 57; const val BY1 = 80
        const val PLANE_Z = 26
        val OUTLET_X = intArrayOf(24, 20, 15, 10, 6)
        val COLORS = arrayOf("purple", "orange", "blue", "green", "red")
        /** Gate wool, by colour, at (15, 56, z). */
        val GATE_Z = intArrayOf(19, 18, 17, 16, 15)
        val LEVERS = arrayOf("quartz", "gold", "coal", "diamond", "emerald", "clay", "water")
        /** Lever blocks (room coordinates), [LEVERS] order. */
        val LEVER_POS = arrayOf(intArrayOf(20, 61, 20), intArrayOf(20, 61, 15), intArrayOf(20, 61, 10), intArrayOf(10, 61, 20),
            intArrayOf(10, 61, 15), intArrayOf(10, 61, 10), intArrayOf(15, 60, 5))
        /** Slot chars in [parse]'s rows: upper case in the board, lower case pulled back. */
        const val SLOT_CHARS = "QGCDET"

        /**
         * A board from its rows, top (y=80) first, x 5..25: '#' solid, '.' open, a [SLOT_CHARS] letter for a lever's block
         * (upper case in, lower case out). Returns the geometry and which slots are in.
         */
        fun parse(rows: List<String>, rotation: String): Pair<Geometry, BooleanArray> {
            require(rows.size == BY1 - BY0 + 1 && rows.all { it.length == BX1 - BX0 + 1 }) { "board rows" }
            val w = X1 - X0 + 1
            fun c(x: Int, y: Int) = (y - Y0) * w + (x - X0)
            val open = ArrayList<Int>(); val slots = ArrayList<Int>(); val levers = ArrayList<Int>(); val inn = ArrayList<Boolean>()
            for ((i, row) in rows.withIndex()) {
                val y = BY1 - i
                for ((j, ch) in row.withIndex()) {
                    val x = BX0 + j
                    when {
                        ch == '.' -> if (y <= 78 || x == 15) open += c(x, y)
                        ch.uppercaseChar() in SLOT_CHARS -> { slots += c(x, y); levers += SLOT_CHARS.indexOf(ch.uppercaseChar()); inn += ch.isUpperCase() }
                    }
                }
            }
            // The inlet above the board: only the column at x=15 is part of the plane's water.
            for (y in 79..81) if (c(15, y) !in open) open += c(15, y)
            return Geometry(rotation, open.toIntArray(), slots.toIntArray(), levers.toIntArray()) to inn.toBooleanArray()
        }
    }
}
