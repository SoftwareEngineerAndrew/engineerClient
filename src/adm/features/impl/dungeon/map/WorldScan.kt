package com.adm.features.impl.dungeon.map

import com.adm.AdmMod.mc
import com.adm.events.LevelEvent
import com.adm.events.LocationChangeEvent
import com.adm.events.RoomEnterEvent
import com.adm.events.TickEvent
import com.adm.events.core.on
import com.adm.features.impl.dungeon.map.tile.DungeonRoom
import com.adm.features.impl.dungeon.map.tile.RoomData
import com.adm.features.impl.dungeon.map.tile.RoomShape
import com.adm.features.impl.render.ClickGUIModule
import com.adm.utils.IVec2
import com.adm.utils.devMessage
import com.adm.utils.skyblock.Island
import com.adm.utils.skyblock.LocationUtils
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import java.util.concurrent.ConcurrentHashMap

object WorldScan {

    var currentRoom: DungeonRoom? = null
        private set

    private const val UNMAPPED_CORE = -318865360

    private val roomsByData = HashMap<RoomData, DungeonRoom>()
    private val pending = HashSet<IVec2>()
    private val blockNames = ConcurrentHashMap<Block, String>()

    init {
        on<LevelEvent.Load> {
            roomsByData.clear()
            pending.clear()
            currentRoom = null
        }

        on<TickEvent.End> { tick() }

        ClientChunkEvents.CHUNK_LOAD.register { _, chunk ->
            if (DungeonUtils.inDungeons) scan(chunk) else pending.add(IVec2(chunk.pos.x, chunk.pos.z))
        }

        ClientChunkEvents.CHUNK_UNLOAD.register { _, chunk ->
            if (!DungeonUtils.inDungeons) pending.remove(IVec2(chunk.pos.x, chunk.pos.z))
        }

        on<LocationChangeEvent> {
            if (DungeonUtils.inDungeons) {
                val level = mc.level ?: return@on
                for (pos in pending) scan(level.getChunk(pos.x, pos.z))
            }
            pending.clear()
        }
    }

    private fun tick() {
        val player = mc.player ?: return
        if (!DungeonUtils.inDungeons || DungeonUtils.inBoss) {
            if (currentRoom != null) {
                currentRoom = null
                RoomEnterEvent(null).postAndCatch()
            }
            return
        }

        val rooms = DungeonScan.rooms
        for (i in 0 until rooms.size) {
            val room = rooms[i]
            if (room.shape == RoomShape.OneByOne && (room.rotation == null || room.clayPos == null) && room.highestBlock != null) room.get1x1Rotation()
        }

        val col = (player.blockX + 201) shr 5
        val row = (player.blockZ + 201) shr 5
        if (col !in 0..5 || row !in 0..5) return
        val room = DungeonScan.tiles[row * 6 + col].room ?: return
        if (room === currentRoom || room.rotation == null || room.clayPos == null) return
        val top = room.highestBlock ?: return
        if (player.blockY > top - 10 && !LocationUtils.isCurrentArea(Island.SinglePlayer)) return

        currentRoom = room
        room.walkedInto = true
        RoomEnterEvent(room).postAndCatch()
        devMessage("${room.data?.name ?: room.type} - ${room.rotation} || clay: ${room.clayPos}")
    }

    private fun scan(chunk: LevelChunk) {
        val cx = chunk.pos.x
        val cz = chunk.pos.z
        if (cx !in -12..-2 || cz !in -12..-2 || cx and 1 != 0 || cz and 1 != 0) return
        val tile = DungeonScan.tiles[(cz / 2 + 6) * 6 + cx / 2 + 6]
        if (tile.room?.data == null) identify(chunk, tile.position, IVec2(cx * 16 + 7, cz * 16 + 7))
    }

    private fun identify(chunk: LevelChunk, tilePos: IVec2, column: IVec2) {
        val (core, top) = getRoomCore(chunk, column)
        val data = RoomData.getRoomData(core)
        if (data == null) {
            if (core != UNMAPPED_CORE) devMessage("Unknown room data for core: $core ${IVec2(chunk.pos.x, chunk.pos.z)}")
            return
        }

        if (ClickGUIModule.dungeonCoresLogging) mc.execute { DungeonScan.recordRoomCore(data.name, core, getRoomCoreBlocks(chunk, column)) }

        val tile = DungeonScan.tiles[tilePos.z * 6 + tilePos.x]
        val room = roomsByData.getOrPut(data) {
            tile.room?.also {
                it.data = data
                it.type = data.type
            } ?: DungeonRoom(data.type, tilePos, data).also { DungeonScan.rooms.add(it) }
        }

        if (tile.room !== room) {
            room.highestBlock = top
            tile.room = room
            room.addSegment(tile)
        } else if (room.highestBlock == null) {
            room.highestBlock = top
            if (room.tiles.size == data.shape.tileAmount) room.inferLayout(top)
        }

        if (room.data == null) {
            room.data = data
            room.type = data.type
        }

        mc.execute { DungeonScan.refresh() }
    }

    fun getRoomCore(chunk: LevelChunk, position: IVec2): Pair<Int, Int> {
        // the String.hashCode of the column written out (block names, '0' for skipped air), without building the string
        var hash = 0
        var top = 0
        var started = false
        var bedrockRun = 0
        val x = position.x
        val z = position.z

        for (y in 140 downTo 12) {
            val state = chunk.stateAt(x, y, z)
            val block = state.block
            if (!started) {
                if (state.isAir || block === Blocks.GOLD_BLOCK) {
                    hash = 31 * hash + '0'.code
                    continue
                }
                started = true
                top = y
            }
            if (state.isAir && bedrockRun >= 2 && y < 69) {
                repeat(y - 11) { hash = 31 * hash + '0'.code }
                break
            }
            if (block === Blocks.BEDROCK) bedrockRun++
            else {
                bedrockRun = 0
                if (block === Blocks.OAK_PLANKS || block === Blocks.TRAPPED_CHEST || block === Blocks.CHEST) continue
            }
            val name = blockNames.getOrPut(block) { block.toString() }
            for (c in name) hash = 31 * hash + c.code
        }
        return hash to top
    }

    fun getRoomCoreBlocks(chunk: LevelChunk, position: IVec2): List<String> =
        (160 downTo 12).map { y -> chunk.stateAt(position.x, y, position.z).block.descriptionId }

    private fun LevelChunk.stateAt(x: Int, y: Int, z: Int): BlockState {
        val index = getSectionIndex(y)
        if (index < 0 || index >= sections.size) return Blocks.AIR.defaultBlockState()
        val section = sections[index]
        return if (section.hasOnlyAir()) Blocks.AIR.defaultBlockState() else section.getBlockState(x and 15, y and 15, z and 15)
    }
}
