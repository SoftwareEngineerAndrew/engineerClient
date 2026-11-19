package com.adm.features.impl.dungeon.puzzlesolvers

import com.adm.AdmMod.mc
import com.adm.events.RenderExtractEvent
import com.adm.events.RoomEnterEvent
import com.adm.features.impl.dungeon.map.tile.DungeonRoom
import com.adm.features.impl.dungeon.map.tile.RoomRotation
import com.adm.utils.Color
import com.adm.utils.modMessage
import com.adm.utils.render.BoxStyle
import com.adm.utils.render.drawStyledBox
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import com.adm.features.impl.dungeon.map.DungeonScan
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

// Cells are the 7x6 boulder grid (x 6..24, z 9..24, step 3), bit i = column i % 7, row i / 7.
// Players walk on the ground between boulders: a strip north of row 0 they drop into, a strip south of row 5 that leads to the chest.
// A click on a face centre (the button or sign 2 blocks out from the boulder's centre) pushes that boulder 3 away from the face,
// into an empty cell of the grid, or toward -x into another boulder, which is deleted. Every other push does nothing.
object BoulderSolver {
    enum class Mode(private val label: String) { CLICKS("Least Clicks"), MOVEMENT("Least Blocks Traveled"), BALANCED("Balanced"); override fun toString() = label }

    enum class Target(private val label: String) { BOULDER("Boulder"), BUTTON("Button"); override fun toString() = label }

    data class Step(val cell: Int, val dc: Int, val dr: Int, val after: Long)

    private const val NORTH = 42
    private const val SOUTH = 49
    private const val DONE = 63
    private const val CHEST = SOUTH + 3
    private const val MAX_STATES = 200_000
    private val PUSHES = arrayOf(intArrayOf(0, -1), intArrayOf(0, 1), intArrayOf(-1, 0), intArrayOf(1, 0))
    private val LAYOUTS = longArrayOf(
        0x105E94E0B87L, 0x20F7BB8EE80L, 0x31DDA33F8C1L, 0x24A228A8A22L,
        0x7E94E0B86L, 0x3E448BF80L, 0x5754D0500L, 0x25202A7D555L
    )

    private var room: DungeonRoom? = null
    private var board = -1L
    private var seen = -1L
    private var stableTicks = 0
    private var planMode: Mode? = null
    private var plan = listOf<Step>()

    /** Plans by board, start and mode, worked out off the game thread once the board is read (the map finds the room before anyone walks in). */
    private val plans = ConcurrentHashMap<Triple<Long, Int, Mode>, List<Step>>()
    private val solving = ConcurrentHashMap.newKeySet<Triple<Long, Int, Mode>>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "engineerclient-boulder").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private var asked: Triple<Long, Int, Mode>? = null

    private fun solveLater(b: Long, from: Int, mode: Mode) {
        val key = Triple(b, from, mode)
        if (plans.containsKey(key) || !solving.add(key)) return
        worker.execute {
            try { plans[key] = solve(b, from, mode) ?: emptyList() } catch (_: Throwable) {} finally { solving.remove(key) }
        }
    }

    fun onRoomEnter(event: RoomEnterEvent) {
        if (event.room?.data?.name == "Boulder") room = event.room
    }

    /** The Boulder room as soon as the map knows it and has its turn, before anyone walks in. */
    private fun findRoom(): DungeonRoom? =
        room ?: DungeonScan.rooms.firstOrNull { it.data?.name == "Boulder" && it.clayPos != null }?.also { room = it }

    fun onTick(mode: Mode) = onTick(mode, true)

    fun onTick(mode: Mode, planning: Boolean) {
        val room = findRoom() ?: return
        val read = readBoard(room) ?: return
        if (read != seen) { seen = read; stableTicks = 0; return }
        // a push reaches the client as one packet per chunk section, so wait for the board to settle
        if (++stableTicks < 2) return
        if (stableTicks == 2) checkLater(read)
        if (!planning || read == board && mode == planMode) return

        if (mode == planMode && board != -1L) {
            val done = plan.indexOfFirst { it.after == read }
            if (done != -1) { board = read; plan = plan.drop(done + 1); return }
        }
        // Worked out off the game thread: until the plan is in, this tick comes back.
        // Where you stood when this board was first read: walking on doesn't start the solve over.
        if (asked?.first != read || asked?.third != mode) asked = Triple(read, playerNode(room, read), mode)
        val key = asked!!
        val ready = plans[key]
        if (ready == null) { solveLater(read, key.second, mode); return }
        val first = board == -1L
        board = read
        if (first && read !in LAYOUTS && !solved(read)) modMessage("§eBoulder has already been moved, solving from where it is.")
        planMode = mode
        plan = ready
        if (ready.isEmpty() && !solved(read)) modMessage("§cBoulder: no solve found from here.")
    }

    fun onRenderWorld(event: RenderExtractEvent, showAllBoulderClicks: Boolean, target: Target, boulderStyle: BoxStyle, boulderColor: Color) {
        val room = room ?: return
        if (plan.isEmpty() || DungeonUtils.currentRoomName != "Boulder") return
        if (facesFor !== plan || facesTarget != target || !sameFrame(facesFrame, room)) {
            faces = Array(plan.size) { faceBox(room.getRealCoords(facePos(plan[it], if (target == Target.BUTTON) 2 else 1))) }
            facesFor = plan
            facesTarget = target
            facesFrame = Frame(room, room.clayPos, room.rotation)
        }
        val count = if (showAllBoulderClicks) faces.size else 1
        for (i in 0 until count) event.drawStyledBox(faces[i], boulderColor, boulderStyle, false)
    }

    fun reset() {
        room = null
        board = -1L
        seen = -1L
        stableTicks = 0
        planMode = null
        plan = emptyList()
        asked = null
        faces = emptyArray()
        facesFor = null
        facesFrame = null
        cellsFrame = null
    }

    // [out] 1: the boulder block the button sits on; 2: the button or sign itself.
    private fun facePos(step: Step, out: Int): BlockPos {
        val x = 6 + 3 * (step.cell % 7)
        val z = 9 + 3 * (step.cell / 7)
        return BlockPos(x - out * step.dc, 65, z - out * step.dr)
    }

    private fun faceBox(pos: BlockPos): AABB {
        val shape = mc.level?.getBlockState(pos)?.getShape(mc.level!!, pos)
        return if (shape == null || shape.isEmpty) AABB(pos) else shape.bounds().move(pos)
    }

    /** The board after pushing [cell] by ([dc], [dr]), or null when that click doesn't push. */
    private fun push(b: Long, cell: Int, dc: Int, dr: Int): Long? {
        val tc = cell % 7 + dc
        val tr = cell / 7 + dr
        if (tc !in 0..6 || tr !in 0..5) return null
        val target = tc + 7 * tr
        if (b shr target and 1L == 1L && dc != -1) return null
        return b and (1L shl cell).inv() or (1L shl target)
    }

    private val solvable = ConcurrentHashMap<Long, Boolean>()
    private val checking = ConcurrentHashMap.newKeySet<Long>()

    // The board and every board one push away, so a click only reads answers already worked out.
    private fun checkLater(b: Long) {
        if (solvable.containsKey(b) || !checking.add(b)) return
        worker.execute {
            try {
                if (canSolve(b)) for (cell in 0 until 42) {
                    if (b shr cell and 1L == 0L) continue
                    for ((dc, dr) in PUSHES) canSolve(push(b, cell, dc, dr) ?: continue)
                }
            } catch (_: Throwable) {} finally { checking.remove(b) }
        }
    }

    /** Whether anyone dropping in from the north can still open the way to the chest from [start]. Unknown counts as yes. */
    private fun canSolve(start: Long): Boolean = solvable.getOrPut(start) {
        val seen = HashSet<Long>()
        val queue = ArrayDeque<Long>()
        seen.add(start); queue.add(start)
        while (queue.isNotEmpty()) {
            val b = queue.removeFirst()
            val dist = distances(NORTH, b, allNorth = true)
            if (dist[CHEST] != -1) return@getOrPut true
            if (seen.size > MAX_STATES) return@getOrPut true
            for (cell in 0 until 42) {
                if (b shr cell and 1L == 0L) continue
                for ((dc, dr) in PUSHES) {
                    if (dist[frontOf(cell, dc, dr) ?: continue] == -1) continue
                    val after = push(b, cell, dc, dr) ?: continue
                    if (seen.add(after)) queue.add(after)
                }
            }
        }
        false
    }

    /** The cell (or north strip spot) a player stands in to push [cell] by ([dc], [dr]). */
    private fun frontOf(cell: Int, dc: Int, dr: Int): Int? {
        val fc = cell % 7 - dc
        val fr = cell / 7 - dr
        return when {
            fc in 0..6 && fr in 0..5 -> fc + 7 * fr
            fr == -1 && fc in 0..6 -> NORTH + fc
            else -> null
        }
    }

    /** A right click on [pos]: true when it would leave Boulder unsolvable. */
    fun blocksClick(pos: BlockPos): Boolean {
        val room = findRoom() ?: return false
        if (DungeonUtils.currentRoomName != "Boulder") return false
        val b = readBoard(room) ?: return false
        if (solvable[b] != true) return false
        val rel = room.getRelativeCoords(pos)
        if (rel.y != 65) return false
        for ((dc, dr) in PUSHES) {
            val x = rel.x + 2 * dc
            val z = rel.z + 2 * dr
            if ((x - 6) % 3 != 0 || (z - 9) % 3 != 0) continue
            val c = (x - 6) / 3
            val r = (z - 9) / 3
            if (c !in 0..6 || r !in 0..5 || b shr (c + 7 * r) and 1L == 0L) continue
            val after = push(b, c + 7 * r, dc, dr) ?: return false
            return solvable[after] == false
        }
        return false
    }

    private fun readBoard(room: DungeonRoom): Long? {
        val level = mc.level ?: return null
        if (!sameFrame(cellsFrame, room)) {
            for (i in 0 until 42) cells[i] = room.getRealCoords(BlockPos(6 + 3 * (i % 7), 65, 9 + 3 * (i / 7))).asLong()
            cellsFrame = Frame(room, room.clayPos, room.rotation)
        }
        var b = 0L
        for (i in 0 until 42) {
            probe.set(cells[i])
            if (!level.isLoaded(probe)) return null
            if (!level.getBlockState(probe).isAir) b = b or (1L shl i)
        }
        return b
    }

    // Where the room sits: its cell and face positions are worked out again only when this changes.
    private class Frame(val room: DungeonRoom, val clay: BlockPos?, val rotation: RoomRotation?)

    private fun sameFrame(f: Frame?, room: DungeonRoom) = f != null && f.room === room && f.clay == room.clayPos && f.rotation == room.rotation

    private val cells = LongArray(42)
    private var cellsFrame: Frame? = null
    private val probe = BlockPos.MutableBlockPos()
    private var faces = emptyArray<AABB>()
    private var facesFor: List<Step>? = null
    private var facesTarget: Target? = null
    private var facesFrame: Frame? = null

    private fun playerNode(room: DungeonRoom, b: Long): Int {
        val pos = room.getRelativeCoords(mc.player?.blockPosition() ?: return NORTH + 3)
        val col = ((pos.x - 5) / 3).coerceIn(0, 6)
        if (pos.y >= 67 || pos.z < 8) return NORTH + col
        if (pos.z > 25) return SOUTH + col
        val cell = col + 7 * ((pos.z - 8) / 3).coerceIn(0, 5)
        return if (b shr cell and 1L == 0L) cell else NORTH + col
    }

    private fun neighbours(n: Int, b: Long, out: IntArray): Int {
        var k = 0
        fun add(m: Int) { out[k++] = m }
        fun open(cell: Int) = b shr cell and 1L == 0L
        when {
            n < NORTH -> {
                val c = n % 7
                val r = n / 7
                if (c > 0 && open(n - 1)) add(n - 1)
                if (c < 6 && open(n + 1)) add(n + 1)
                if (r > 0 && open(n - 7)) add(n - 7)
                if (r < 5 && open(n + 7)) add(n + 7)
                if (r == 0) add(NORTH + c)
                if (r == 5) add(SOUTH + c)
            }
            n < SOUTH -> {
                val c = n - NORTH
                if (c > 0) add(n - 1)
                if (c < 6) add(n + 1)
                if (open(c)) add(c)
            }
            else -> {
                val c = n - SOUTH
                if (c > 0) add(n - 1)
                if (c < 6) add(n + 1)
                if (open(35 + c)) add(35 + c)
            }
        }
        return k
    }

    private fun distances(from: Int, b: Long, allNorth: Boolean = false): IntArray {
        val dist = IntArray(SOUTH + 7) { -1 }
        val queue = IntArray(SOUTH + 7)
        val nb = IntArray(4)
        var head = 0
        var tail = 0
        if (allNorth) for (c in 0 until 7) { dist[NORTH + c] = 0; queue[tail++] = NORTH + c }
        else { dist[from] = 0; queue[tail++] = from }
        while (head < tail) {
            val n = queue[head++]
            for (j in 0 until neighbours(n, b, nb)) {
                val m = nb[j]
                if (dist[m] != -1) continue
                dist[m] = dist[n] + 1
                queue[tail++] = m
            }
        }
        return dist
    }

    private fun solved(b: Long) = distances(NORTH, b)[CHEST] != -1

    /** [lean]: how far right its pushes are, so of two equal plans the one on your left (east, as you drop in facing south) wins. */
    private class Node(val cost: Long, val board: Long, val pos: Int, val parent: Node?, val step: Step?, val lean: Int = 0)

    // Dijkstra over (board, where the player stands). Each push costs a click plus the walk to its face; finishing costs the walk to the chest.
    private fun solve(start: Long, from: Int, mode: Mode): List<Step>? {
        // Balanced: a click takes about as long as walking three cells.
        fun cost(clicks: Int, walk: Int): Long = when (mode) {
            Mode.CLICKS -> clicks * 1000L + walk
            Mode.MOVEMENT -> walk * 1000L + clicks
            Mode.BALANCED -> (3L * clicks + walk) * 1000L + clicks
        }
        val queue = PriorityQueue<Node>(compareBy<Node> { it.cost }.thenBy { it.lean })
        val closed = HashSet<Long>()
        queue.add(Node(0, start, from, null, null))
        while (queue.isNotEmpty()) {
            val node = queue.poll()
            if (node.pos == DONE) return generateSequence(node) { it.parent }.mapNotNull { it.step }.toList().reversed()
            if (!closed.add(node.board shl 6 or node.pos.toLong())) continue
            if (closed.size > MAX_STATES) return null
            val dist = distances(node.pos, node.board)
            if (dist[CHEST] != -1) queue.add(Node(node.cost + cost(0, dist[CHEST]), node.board, DONE, node, null, node.lean))
            for (cell in 0 until 42) {
                if (node.board shr cell and 1L == 0L) continue
                val c = cell % 7
                val r = cell / 7
                for ((dc, dr) in PUSHES) {
                    val front = frontOf(cell, dc, dr) ?: continue
                    if (dist[front] == -1) continue
                    val after = push(node.board, cell, dc, dr) ?: continue
                    queue.add(Node(node.cost + cost(1, dist[front]), after, front, node, Step(cell, dc, dr, after), node.lean + 6 - c))
                }
            }
        }
        return null
    }
}
