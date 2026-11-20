package com.adm.features.impl.dungeon.puzzlesolvers

import com.adm.AdmMod.mc
import com.adm.events.RenderExtractEvent
import com.adm.features.impl.dungeon.map.tile.DungeonRoom
import com.adm.utils.Color
import com.adm.utils.render.drawSegments
import com.adm.utils.modMessage
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.entity.monster.Silverfish
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.EntityHitResult

object IcePathSolver {
    private const val Y = 67
    private val DIRS = listOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)

    private var pending: BlockPos? = null
    private var pendingUntil = 0L
    private var lastAlert = 0L
    private var cached: Map<BlockPos, Int>? = null
    private var cachedAt = 0L

    private var routeFish: Silverfish? = null
    private var routeFrom: BlockPos? = null
    private var routeKey = -1L
    private var routeMoving = false
    private var route = DoubleArray(0)
    private var routeLen = 0
    private val nextStep = DoubleArray(6)
    private var hasNext = false

    private fun room(): DungeonRoom? = DungeonUtils.currentRoom?.takeIf { it.data?.name == "Ice Path" }

    private fun fish(room: DungeonRoom): Silverfish? {
        val level = mc.level ?: return null
        val center = room.getRealCoords(BlockPos(15, Y, 16))
        return level.getEntitiesOfClass(Silverfish::class.java, AABB(center).inflate(12.0, 4.0, 12.0)).firstOrNull { it.isAlive }
    }

    private fun inArena(c: BlockPos) = (c.x in 7..23 && c.z in 8..24) || (c.x in 14..16 && c.z in 25..28)

    private fun free(room: DungeonRoom, c: BlockPos): Boolean =
        inArena(c) && mc.level?.getBlockState(room.getRealCoords(BlockPos(c.x, Y, c.z)))?.isAir == true

    private fun slide(room: DungeonRoom, c: BlockPos, d: Direction): Pair<BlockPos, Boolean> {
        var at = c
        while (free(room, at.relative(d))) at = at.relative(d)
        return at to (d == Direction.SOUTH && at.x in 14..16 && at.z >= 24)
    }

    private fun distances(room: DungeonRoom): Map<BlockPos, Int> {
        cached?.takeIf { System.currentTimeMillis() - cachedAt < 1000 }?.let { return it }
        val cells = buildList { for (x in 7..23) for (z in 8..28) BlockPos(x, Y, z).takeIf { free(room, it) }?.let(::add) }
        val into = HashMap<BlockPos, MutableList<BlockPos>>()
        val dist = HashMap<BlockPos, Int>()
        val queue = ArrayDeque<BlockPos>()
        for (c in cells) for (d in DIRS) {
            val (to, out) = slide(room, c, d)
            if (out) { if (dist.put(c, 1) == null) queue.add(c) }
            else if (to != c) into.getOrPut(to) { ArrayList() }.add(c)
        }
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            for (from in into[c].orEmpty()) if (from !in dist) { dist[from] = dist.getValue(c) + 1; queue.add(from) }
        }
        cached = dist; cachedAt = System.currentTimeMillis()
        return dist
    }

    private fun cellOf(room: DungeonRoom, x: Double, z: Double): BlockPos =
        room.getRelativeCoords(BlockPos.containing(x, Y.toDouble(), z)).atY(Y)

    private fun moving(fish: Silverfish): Boolean {
        val dx = fish.x - fish.xo; val dz = fish.z - fish.zo
        return dx * dx + dz * dz >= 1e-4
    }

    // A sliding fish is judged from where it stops; a push already sent from where that one leaves it.
    private fun restCell(room: DungeonRoom, fish: Silverfish): BlockPos {
        pending?.takeIf { System.currentTimeMillis() < pendingUntil }?.let { return it }
        val now = cellOf(room, fish.x, fish.z)
        if (!moving(fish)) return now
        val dx = fish.x - fish.xo; val dz = fish.z - fish.zo
        val real = room.getRealCoords(now)
        val step = if (kotlin.math.abs(dx) > kotlin.math.abs(dz)) real.offset(if (dx > 0) 1 else -1, 0, 0) else real.offset(0, 0, if (dz > 0) 1 else -1)
        val rel = room.getRelativeCoords(step).atY(Y)
        val d = DIRS.firstOrNull { now.relative(it) == rel } ?: return now
        return slide(room, now, d).first
    }

    // The push goes along the hitter's look snapped to an axis, in the room's frame.
    private fun lookDir(room: DungeonRoom): Direction? {
        val player = mc.player ?: return null
        val real = player.direction
        val a = room.getRelativeCoords(BlockPos.ZERO.atY(Y))
        val b = room.getRelativeCoords(BlockPos.ZERO.relative(real).atY(Y))
        return DIRS.firstOrNull { a.relative(it) == b }
    }

    private fun badPush(room: DungeonRoom, from: BlockPos): Pair<Boolean, BlockPos?> {
        val d = lookDir(room) ?: return false to null
        val dist = distances(room)
        val here = dist[from] ?: return false to null
        val (to, out) = slide(room, from, d)
        if (out) return false to to
        val next = dist[to]
        if (to == from) return false to null
        return (next == null || next != here - 1) to to
    }

    private fun active(): Boolean = PuzzleSolvers.enabled && PuzzleSolvers.icePathBlockWrong && DungeonUtils.inClear

    private fun judge(room: DungeonRoom, from: BlockPos, landMs: Long): Boolean {
        val (bad, to) = badPush(room, from)
        if (bad) {
            if (System.currentTimeMillis() - lastAlert > 1000) {
                lastAlert = System.currentTimeMillis()
                modMessage("§cBlocked: that push isn't on the shortest way out")
            }
            return true
        }
        if (to != null && to != from) { pending = to; pendingUntil = System.currentTimeMillis() + landMs + 400 }
        return false
    }

    fun blocksPunch(): Boolean {
        if (!active()) return false
        val room = room() ?: return false
        val fish = (mc.hitResult as? EntityHitResult)?.entity as? Silverfish ?: return false
        if (moving(fish)) return false
        if (!judge(room, restCell(room, fish), 0)) return false
        PuzzleSolvers.blocked()
        return true
    }

    fun blocksShot(shot: List<PuzzleArrows.Flight>): Boolean {
        if (!active()) return false
        val room = room() ?: return false
        val fish = fish(room) ?: return false
        val from = restCell(room, fish)
        val rest = room.getRealCoords(from)
        val boxes = listOf(fish.boundingBox.inflate(0.3), AABB(rest).setMaxY(rest.y + 1.0).deflate(0.3, 0.0, 0.3).inflate(0.3))
        val tick = shot.mapNotNull { PuzzleArrows.land(it, boxes)?.takeIf { l -> l.box >= 0 }?.tick }.minOrNull() ?: return false
        return judge(room, from, tick * 50L)
    }

    // The route only changes when the fish (or a sent push) moves it, so it is rebuilt then and only drawn per frame.
    fun onTick() {
        val room = room() ?: return clearRoute()
        val fish = routeFish?.takeIf { it.isAlive && !it.isRemoved } ?: fish(room)?.also { routeFish = it } ?: return clearRoute()
        val key = fish.blockPosition().asLong()
        val moving = moving(fish)
        val pend = pending?.takeIf { System.currentTimeMillis() < pendingUntil }
        if (key == routeKey && moving == routeMoving && routeFrom == pend) return
        routeKey = key
        routeMoving = moving
        val from = restCell(room, fish)
        routeFrom = pend
        buildRoute(room, from)
    }

    private fun buildRoute(room: DungeonRoom, start: BlockPos) {
        val dist = distances(room)
        routeLen = 0
        hasNext = false
        var at = start
        var left = dist[at] ?: return
        while (left > 0) {
            var next: BlockPos? = null
            for (d in DIRS) {
                val (to, out) = slide(room, at, d)
                if (to == at) continue
                if (if (left == 1) out else dist[to] == left - 1) { next = to; break }
            }
            next ?: return
            addSegment(room, at, next)
            at = next
            left--
        }
        if (routeLen < 6) return
        System.arraycopy(route, 0, nextStep, 0, 6)
        System.arraycopy(route, 6, route, 0, routeLen - 6)
        routeLen -= 6
        hasNext = true
    }

    private fun addSegment(room: DungeonRoom, a: BlockPos, b: BlockPos) {
        if (routeLen + 6 > route.size) route = route.copyOf(maxOf(24, route.size * 2))
        val ra = room.getRealCoords(a)
        val rb = room.getRealCoords(b)
        route[routeLen++] = ra.x + 0.5; route[routeLen++] = Y + 0.05; route[routeLen++] = ra.z + 0.5
        route[routeLen++] = rb.x + 0.5; route[routeLen++] = Y + 0.05; route[routeLen++] = rb.z + 0.5
    }

    private fun clearRoute() {
        routeFish = null
        routeFrom = null
        routeKey = -1L
        routeLen = 0
        hasNext = false
    }

    fun onRenderWorld(event: RenderExtractEvent, color: Color, nextColor: Color) {
        if (!hasNext) return
        event.drawSegments(nextStep, 6, nextColor.rgba, depth = true)
        event.drawSegments(route, routeLen, color.rgba, depth = true)
    }

    fun reset() {
        pending = null
        cached = null
        clearRoute()
    }
}
