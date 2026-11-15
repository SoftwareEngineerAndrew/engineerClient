package com.odtheking.odin.features.impl.dungeon.puzzlesolvers

import com.odtheking.odin.OdinMod.mc
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.RoomEnterEvent
import com.odtheking.odin.features.impl.dungeon.map.tile.DungeonRoom
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.modMessage
import com.odtheking.odin.utils.render.BoxStyle
import com.odtheking.odin.utils.render.drawStyledBox
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

// Cells are the 7x6 boulder grid (x 6..24, z 9..24, step 3), bit i = column i % 7, row i / 7.
// Players walk on the ground between boulders: a strip north of row 0 they drop into, a strip south of row 5 that leads to the chest.
// A click on a face centre (the button) pushes that boulder 3 away from the face, deleting any boulder it lands on.
object BoulderSolver {
    enum class Mode(private val label: String) { CLICKS("Least Clicks"), MOVEMENT("Least Movement"); override fun toString() = label }

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

    /** Plans by board and mode, worked out off the game thread: the known layouts from the entrance as soon as one is needed. */
    private val plans = ConcurrentHashMap<Triple<Long, Int, Mode>, List<Step>>()
    private val solving = ConcurrentHashMap.newKeySet<Triple<Long, Int, Mode>>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "engineerclient-boulder").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private var precomputed = false
    private var asked: Triple<Long, Int, Mode>? = null

    private fun precompute() {
        if (precomputed) return
        precomputed = true
        for (layout in LAYOUTS) for (mode in Mode.entries) for (c in 0 until 7) solveLater(layout, NORTH + c, mode)
    }

    private fun solveLater(b: Long, from: Int, mode: Mode) {
        val key = Triple(b, from, mode)
        if (plans.containsKey(key) || !solving.add(key)) return
        worker.execute {
            try { plans[key] = solve(b, from, mode) ?: emptyList() } catch (_: Throwable) {} finally { solving.remove(key) }
        }
    }

    fun onRoomEnter(event: RoomEnterEvent) {
        precompute()
        if (event.room?.data?.name == "Boulder") room = event.room
    }

    /** The Boulder room as soon as the map knows it and has its turn, before anyone walks in. */
    private fun findRoom(): DungeonRoom? =
        room ?: DungeonScan.rooms.firstOrNull { it.data?.name == "Boulder" && it.clayPos != null }?.also { room = it }

    fun onTick(mode: Mode) {
        val room = findRoom() ?: return
        val read = readBoard(room) ?: return
        if (read != seen) { seen = read; stableTicks = 0; return }
        // a push reaches the client as one packet per chunk section, so wait for the board to settle
        if (++stableTicks < 2 || (read == board && mode == planMode)) return

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

    fun onRenderWorld(event: RenderExtractEvent, showAllBoulderClicks: Boolean, boulderStyle: BoxStyle, boulderColor: Color) {
        val room = room ?: return
        if (DungeonUtils.currentRoomName != "Boulder" || plan.isEmpty()) return
        val steps = if (showAllBoulderClicks) plan else plan.take(1)
        for (step in steps) event.drawStyledBox(AABB(room.getRealCoords(facePos(step))), boulderColor, boulderStyle, false)
    }

    fun reset() {
        room = null
        board = -1L
        seen = -1L
        stableTicks = 0
        planMode = null
        plan = emptyList()
        asked = null
    }

    private fun facePos(step: Step): BlockPos {
        val x = 6 + 3 * (step.cell % 7)
        val z = 9 + 3 * (step.cell / 7)
        return BlockPos(x - step.dc, 65, z - step.dr)
    }

    private fun readBoard(room: DungeonRoom): Long? {
        val level = mc.level ?: return null
        var b = 0L
        for (i in 0 until 42) {
            val pos = room.getRealCoords(BlockPos(6 + 3 * (i % 7), 65, 9 + 3 * (i / 7)))
            if (!level.isLoaded(pos)) return null
            if (!level.getBlockState(pos).isAir) b = b or (1L shl i)
        }
        return b
    }

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

    private fun distances(from: Int, b: Long): IntArray {
        val dist = IntArray(SOUTH + 7) { -1 }
        val queue = IntArray(SOUTH + 7)
        val nb = IntArray(4)
        var head = 0
        var tail = 0
        dist[from] = 0
        queue[tail++] = from
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

    private class Node(val cost: Long, val board: Long, val pos: Int, val parent: Node?, val step: Step?)

    // Dijkstra over (board, where the player stands). Each push costs a click plus the walk to its face; finishing costs the walk to the chest.
    private fun solve(start: Long, from: Int, mode: Mode): List<Step>? {
        fun cost(clicks: Int, walk: Int): Long = if (mode == Mode.CLICKS) clicks * 1000L + walk else walk * 1000L + clicks
        val queue = PriorityQueue<Node>(compareBy { it.cost })
        val closed = HashSet<Long>()
        queue.add(Node(0, start, from, null, null))
        while (queue.isNotEmpty()) {
            val node = queue.poll()
            if (node.pos == DONE) return generateSequence(node) { it.parent }.mapNotNull { it.step }.toList().reversed()
            if (!closed.add(node.board shl 6 or node.pos.toLong())) continue
            if (closed.size > MAX_STATES) return null
            val dist = distances(node.pos, node.board)
            if (dist[CHEST] != -1) queue.add(Node(node.cost + cost(0, dist[CHEST]), node.board, DONE, node, null))
            for (cell in 0 until 42) {
                if (node.board shr cell and 1L == 0L) continue
                val c = cell % 7
                val r = cell / 7
                for ((dc, dr) in PUSHES) {
                    val tc = c + dc
                    val tr = r + dr
                    if (tc !in 0..6 || tr !in 0..5) continue
                    val fc = c - dc
                    val fr = r - dr
                    val front = when {
                        fc in 0..6 && fr in 0..5 -> fc + 7 * fr
                        fr == -1 -> NORTH + fc
                        else -> continue
                    }
                    if (dist[front] == -1) continue
                    val after = node.board and (1L shl cell).inv() or (1L shl (tc + 7 * tr))
                    queue.add(Node(node.cost + cost(1, dist[front]), after, front, node, Step(cell, dc, dr, after)))
                }
            }
        }
        return null
    }
}
