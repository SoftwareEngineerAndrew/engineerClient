package com.adm.features.impl.dungeon.puzzlesolvers

import com.adm.AdmMod.mc
import com.adm.events.BlockUpdateEvent
import com.adm.events.RenderExtractEvent
import com.adm.events.RoomEnterEvent
import com.adm.features.impl.dungeon.map.tile.DungeonRoom
import com.adm.features.impl.dungeon.puzzlesolvers.PuzzleSolvers.onPuzzleComplete
import com.adm.utils.*
import com.adm.utils.Color.Companion.withAlpha
import com.adm.utils.Color.Companion.multiplyAlphaRgba
import com.adm.utils.render.BoxBatch
import com.adm.utils.render.BoxStyle
import com.adm.utils.render.drawBoxes
import com.adm.utils.render.drawLine
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentHashMap

object BeamsSolver {
    private var scanned = false
    private var lanternPairs: List<List<Int>> = JsonResourceLoader.loadJson(Identifier.fromNamespaceAndPath("engineerclient", "puzzles/creeper-beams-solutions.json")) ?: emptyList()

    private var currentLanternPairs = ConcurrentHashMap<BlockPos, Pair<BlockPos, Color>>()

    // Render-side copy of currentLanternPairs, in its order: two boxes and a tracer per pair.
    private var boxes = emptyArray<AABB>()
    private var fills = emptyArray<AABB>()
    private var tracers = emptyArray<List<Vec3>>()
    private var baseColors = emptyArray<Color>()
    private var drawColors = emptyArray<Color>()
    private var drawAlpha = Float.NaN
    private val batch = BoxBatch()

    fun onRoomEnter(event: RoomEnterEvent) = with(event.room) {
        if (this?.data?.name != "Creeper Beams") return@with reset()
        recalculateLanternPairs(this)
    }

    private fun recalculateLanternPairs(room: DungeonRoom) {
        val level = mc.level
        currentLanternPairs.clear()
        partners.clear()
        lanternPairs.forEachIndexed { index, list ->
            val a = room.getRealCoords(BlockPos(list[0], list[1], list[2]))
            val b = room.getRealCoords(BlockPos(list[3], list[4], list[5]))
            val blockA = level?.getBlockState(a)?.block
            val blockB = level?.getBlockState(b)?.block
            if (blockA == Blocks.SEA_LANTERN && blockB == Blocks.SEA_LANTERN) currentLanternPairs[a] = b to colors[index % colors.size]
            if (blockA.isBeamEnd() && blockB.isBeamEnd()) { partners[a] = b; partners[b] = a }
        }
        val pairs = currentLanternPairs.entries.toList()
        boxes = Array(pairs.size * 2) { AABB(if (it % 2 == 0) pairs[it / 2].key else pairs[it / 2].value.first) }
        fills = Array(boxes.size) { boxes[it].inflate(0.00005) }
        tracers = Array(pairs.size) { listOf(pairs[it].key.center, pairs[it].value.first.center) }
        baseColors = Array(pairs.size) { pairs[it].value.second }
        drawAlpha = Float.NaN
    }

    private fun Block?.isBeamEnd() = this == Blocks.SEA_LANTERN || this == Blocks.PRISMARINE

    fun onRenderWorld(event: RenderExtractEvent, beamStyle: BoxStyle, beamsTracer: Boolean, beamsAlpha: Float) {
        val boxes = boxes
        if (boxes.isEmpty() || DungeonUtils.currentRoomName != "Creeper Beams") return
        if (drawAlpha != beamsAlpha || drawColors.size != baseColors.size) {
            drawColors = Array(baseColors.size) { baseColors[it].withAlpha(beamsAlpha) }
            drawAlpha = beamsAlpha
        }
        val colors = drawColors
        batch.clear()
        for (i in boxes.indices) {
            val color = colors[i / 2]
            if (beamStyle != BoxStyle.OUTLINE) {
                if (beamStyle == BoxStyle.FILLED) batch.filled(boxes[i], color, true) else batch.filled(fills[i], color.multiplyAlphaRgba(0.5f), true)
            }
            if (beamStyle != BoxStyle.FILLED) batch.outline(boxes[i], color, depth = true)
        }
        event.drawBoxes(batch)
        if (beamsTracer) for (i in tracers.indices) event.drawLine(tracers[i], color = colors[i], depth = false)
    }

    fun onBlockChange(event: BlockUpdateEvent) {
        if (DungeonUtils.currentRoomName != "Creeper Beams") return
        if (event.old.isAir && event.updated.block == Blocks.CHEST && event.pos == DungeonUtils.currentRoom?.getRealCoords(BlockPos(15, 69, 15))) onPuzzleComplete("Creeper Beams")
        if (event.pos == selected && event.updated.block == Blocks.PRISMARINE) selected = null
        if (
            event.old.block.equalsOneOf(Blocks.PRISMARINE, Blocks.SEA_LANTERN) &&
            event.updated.block.equalsOneOf(Blocks.PRISMARINE, Blocks.SEA_LANTERN)
        ) {
            mc.execute {
                recalculateLanternPairs(DungeonUtils.currentRoom ?: return@execute)
            }
            return
        }
    }

    // Selection is per player: only a select sound at a lantern you just hit is yours.
    fun onSound(packet: ClientboundSoundPacket) {
        if (packet.sound.value() != SoundEvents.ELDER_GUARDIAN_HURT || DungeonUtils.currentRoomName != "Creeper Beams") return
        val pos = BlockPos.containing(packet.x, packet.y, packet.z)
        mc.execute {
            if ((mine.remove(pos) ?: 0L) < System.currentTimeMillis()) return@execute
            selected = if (packet.pitch > 1.9f) null else pos
        }
    }

    fun blocksPunch(): Boolean {
        if (!active()) return false
        val pos = (mc.hitResult as? BlockHitResult)?.takeIf { it.type == HitResult.Type.BLOCK }?.blockPos ?: return false
        if (!isLantern(pos)) return false
        if (wrong(ownArrows() + Hit(pos, 0, arrow = false, new = true))) { PuzzleSolvers.blocked(); return true }
        mine[pos] = System.currentTimeMillis() + MINE_MS
        return false
    }

    fun wantsShots() = active()

    fun blocksShot(shot: List<PuzzleArrows.Flight>): Boolean {
        if (!active()) return false
        return wrong(ownArrows() + landings(shot, new = true))
    }

    fun fired(shot: List<PuzzleArrows.Flight>) {
        if (!active()) return
        for (hit in landings(shot, new = true)) mine[hit.pos] = System.currentTimeMillis() + MINE_MS
    }

    private class Hit(val pos: BlockPos, val tick: Int, val arrow: Boolean, val new: Boolean)

    private fun active() = PuzzleSolvers.enabled && PuzzleSolvers.beamsBlockWrong && DungeonUtils.inClear &&
        DungeonUtils.currentRoomName == "Creeper Beams" && partners.isNotEmpty()

    private fun ownArrows() = landings(PuzzleArrows.flying(own = true), new = false)

    private fun landings(flights: List<PuzzleArrows.Flight>, new: Boolean): List<Hit> {
        val player = mc.player ?: return emptyList()
        val stops = mc.level?.getEntitiesOfClass(Mob::class.java, player.boundingBox.inflate(32.0))?.map { it.boundingBox.inflate(0.3) } ?: emptyList()
        return flights.mapNotNull { f ->
            val land = PuzzleArrows.land(f, emptyList(), stops) ?: return@mapNotNull null
            land.block?.takeIf(::isLantern)?.let { Hit(it, land.tick, arrow = true, new = new) }
        }
    }

    private fun isLantern(pos: BlockPos): Boolean {
        val block = mc.level?.getBlockState(pos)?.block ?: return false
        val y = DungeonUtils.currentRoom?.getRelativeCoords(pos)?.y ?: return false
        return (block == Blocks.SEA_LANTERN && y >= 68) || (block == Blocks.PRISMARINE && partners.containsKey(pos))
    }

    // Hits play out in order (one counted arrow a player per 10 ticks): with nothing selected only a solution lantern,
    // then only its partner; a solution beam already up is never taken down.
    private fun wrong(hits: List<Hit>): Boolean {
        val level = mc.level ?: return false
        var sel = selected
        var lastArrow = Int.MIN_VALUE / 2
        val linked = HashSet<BlockPos>()
        fun lit(pos: BlockPos) = pos !in linked && level.getBlockState(pos).block == Blocks.SEA_LANTERN
        for (hit in hits.sortedBy { it.tick }) {
            if (hit.arrow) { if (hit.tick - lastArrow < ARROW_GATE) continue; lastArrow = hit.tick }
            val ok = if (lit(hit.pos)) {
                val s = sel
                val good = if (s == null) partners.containsKey(hit.pos) else partners[s] == hit.pos
                if (s == null) sel = hit.pos else { if (good) { linked += s; linked += hit.pos }; sel = null }
                good
            } else partners[hit.pos]?.let { lit(it) } ?: true
            if (!ok && hit.new) return true
        }
        return false
    }

    fun reset() {
        scanned = false
        currentLanternPairs.clear()
        boxes = emptyArray()
        fills = emptyArray()
        tracers = emptyArray()
        baseColors = emptyArray()
        partners.clear()
        mine.clear()
        selected = null
    }

    private const val ARROW_GATE = 10
    private const val MINE_MS = 3000L
    private val partners = ConcurrentHashMap<BlockPos, BlockPos>()
    private val mine = ConcurrentHashMap<BlockPos, Long>()
    private var selected: BlockPos? = null

    private val colors = listOf(Colors.MINECRAFT_GOLD, Colors.MINECRAFT_GREEN, Colors.MINECRAFT_LIGHT_PURPLE, Colors.MINECRAFT_DARK_AQUA, Colors.MINECRAFT_YELLOW, Colors.MINECRAFT_DARK_RED, Colors.WHITE, Colors.MINECRAFT_DARK_PURPLE)
}

