package com.odtheking.odin.features.impl.dungeon.puzzlesolvers

import com.odtheking.odin.OdinMod.mc
import com.odtheking.odin.events.BlockUpdateEvent
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.RoomEnterEvent
import com.odtheking.odin.features.impl.dungeon.map.tile.DungeonRoom
import com.odtheking.odin.features.impl.dungeon.puzzlesolvers.PuzzleSolvers.onPuzzleComplete
import com.odtheking.odin.utils.*
import com.odtheking.odin.utils.Color.Companion.withAlpha
import com.odtheking.odin.utils.render.BoxStyle
import com.odtheking.odin.utils.render.drawLine
import com.odtheking.odin.utils.render.drawStyledBox
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import java.util.concurrent.ConcurrentHashMap

object BeamsSolver {
    private var scanned = false
    private var lanternPairs: List<List<Int>> = JsonResourceLoader.loadJson(Identifier.fromNamespaceAndPath("odin", "puzzles/creeper-beams-solutions.json")) ?: emptyList()

    private var currentLanternPairs = ConcurrentHashMap<BlockPos, Pair<BlockPos, Color>>()

    fun onRoomEnter(event: RoomEnterEvent) = with(event.room) {
        if (this?.data?.name != "Creeper Beams") return@with reset()
        recalculateLanternPairs(this)
    }

    private fun recalculateLanternPairs(room: DungeonRoom) {
        currentLanternPairs.clear()
        lanternPairs.forEachIndexed { index, list ->
            val pos = room.getRealCoords(BlockPos(list[0], list[1], list[2])).takeIf { mc.level?.getBlockState(it)?.block == Blocks.SEA_LANTERN } ?: return@forEachIndexed
            val pos2 = room.getRealCoords(BlockPos(list[3], list[4], list[5])).takeIf { mc.level?.getBlockState(it)?.block == Blocks.SEA_LANTERN } ?: return@forEachIndexed

            currentLanternPairs[pos] = pos2 to colors[index % colors.size]
        }
        partners.clear()
        val ends = setOf(Blocks.SEA_LANTERN, Blocks.PRISMARINE)
        for (list in lanternPairs) {
            val a = room.getRealCoords(BlockPos(list[0], list[1], list[2]))
            val b = room.getRealCoords(BlockPos(list[3], list[4], list[5]))
            if (mc.level?.getBlockState(a)?.block !in ends || mc.level?.getBlockState(b)?.block !in ends) continue
            partners[a] = b; partners[b] = a
        }
    }

    fun onRenderWorld(event: RenderExtractEvent, beamStyle: BoxStyle, beamsTracer: Boolean, beamsAlpha: Float) {
        if (DungeonUtils.currentRoomName != "Creeper Beams" || currentLanternPairs.isEmpty()) return

        currentLanternPairs.entries.forEach { positions ->
            val color = positions.value.second.withAlpha(beamsAlpha)

            event.drawStyledBox(AABB(positions.key), color, depth = true, style = beamStyle)
            event.drawStyledBox(AABB(positions.value.first), color, depth = true, style = beamStyle)

            if (beamsTracer)
                event.drawLine(listOf(positions.key.center, positions.value.first.center), color = color, depth = false)
        }
    }

    fun onBlockChange(event: BlockUpdateEvent) {
        if (DungeonUtils.currentRoomName != "Creeper Beams") return
        if (event.pos == DungeonUtils.currentRoom?.getRealCoords(BlockPos(15, 69, 15)) && event.old.isAir && event.updated.block == Blocks.CHEST) onPuzzleComplete("Creeper Beams")
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

