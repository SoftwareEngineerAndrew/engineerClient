// Adapted from Odin (https://github.com/odtheking/OdinFabric), Copyright (c) 2025, odtheking. BSD 3-Clause License: see licenses/Odin-LICENSE.txt.
package com.engineerclient.ads.map

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.ads.dungeon.AdsDungeon
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk

/**
 * Room names from the world (Odin's WorldScan): each loaded room's core column, hashed, looked up in the room
 * data, gives its name, secrets and shape; walking into a room marks it entered, which shows its name on the map.
 * Chunks that load before the run's floor is known wait until it is.
 */
object WorldScan {
    var currentRoom: DungeonRoom? = null
        private set

    private val dataToRoom = HashMap<RoomData, DungeonRoom>()
    private val waiting = HashSet<GridPos>()
    private var wasInDungeon = false
    private var initialized = false

    fun init() {
        if (initialized) return
        initialized = true
        ClientTickEvents.END_CLIENT_TICK.register { EngineerClient.safely("ads minimap world scan") { tick() } }
        ClientChunkEvents.CHUNK_LOAD.register { _, chunk ->
            EngineerClient.safely("ads minimap chunk") {
                if (!AdsDungeon.inDungeon) waiting.add(GridPos(chunk.pos.x, chunk.pos.z)) else scanChunk(chunk)
            }
        }
        ClientChunkEvents.CHUNK_UNLOAD.register { _, chunk ->
            if (!AdsDungeon.inDungeon) waiting.remove(GridPos(chunk.pos.x, chunk.pos.z))
        }
    }

    fun reset() {
        dataToRoom.clear()
        waiting.clear()
        currentRoom = null
        wasInDungeon = false
    }

    private fun tick() {
        val player = mc.player ?: return
        if (AdsDungeon.inDungeon && !wasInDungeon) {
            wasInDungeon = true
            mc.level?.let { level -> for (p in waiting) level.chunkSource.getChunk(p.x, p.z, false)?.let { scanChunk(it) } }
            waiting.clear()
        }
        if (!AdsDungeon.inClear) {
            currentRoom = null
            return
        }

        for (room in DungeonScan.rooms) {
            if (room.shape != RoomShape.OneByOne || (room.rotation != null && room.clayPos != null)) continue
            if (room.highestBlock != null) room.get1x1Rotation()
        }

        val tileX = (player.blockX + 201) shr 5
        val tileZ = (player.blockZ + 201) shr 5
        if (tileX !in 0..5 || tileZ !in 0..5) return
        val room = DungeonScan.tiles[tileX + tileZ * 6].room ?: return
        val top = room.highestBlock ?: return
        if (room == currentRoom || room.rotation == null || room.clayPos == null) return
        // under the room's roof (Odin: not checked in single player, where ADS's worlds are)
        if (player.blockY > top - 10 && !mc.hasSingleplayerServer()) return

        currentRoom = room
        room.walkedInto = true
        DungeonScan.refresh()
    }

    private fun scanChunk(chunk: LevelChunk) {
        val cx = chunk.pos.x; val cz = chunk.pos.z
        if (cx !in -12..-2 || cz !in -12..-2 || cx % 2 != 0 || cz % 2 != 0) return
        val tilePos = GridPos(cx, cz) / 2 + GridPos(6, 6)
        val tile = DungeonScan.tiles.getOrNull(tilePos.x + tilePos.z * 6) ?: return
        if (tile.room?.data != null) return

        val (core, highestBlock) = roomCore(chunk, cx * 16 + 7, cz * 16 + 7)
        val data = RoomData.of(core) ?: return

        val room = dataToRoom.getOrPut(data) {
            val mapRoom = tile.room
            if (mapRoom != null) {
                mapRoom.data = data
                mapRoom.type = data.type
                mapRoom
            } else DungeonRoom(data.type, tile.position, data).also { DungeonScan.rooms.add(it) }
        }

        if (tile.room !== room) {
            room.highestBlock = highestBlock
            tile.room = room
            room.addSegment(tile)
        } else if (room.highestBlock == null) {
            room.highestBlock = highestBlock
            if (room.tiles.size == data.shape.tileAmount) room.inferLayout(highestBlock)
        }
        if (room.data == null) {
            room.data = data
            room.type = data.type
        }
        DungeonScan.refresh()
    }

    private val core = StringBuilder(1024)

    /** Odin's room core: the blocks of one column from the top down, hashed, and the room's highest block. */
    private fun roomCore(chunk: LevelChunk, x: Int, z: Int): Pair<Int, Int> {
        core.setLength(0)
        var foundHighest = false
        var highestBlock = 0
        var bedrock = 0

        for (y in 140 downTo 12) {
            val state = chunk.stateAt(x, y, z)
            if (!foundHighest) {
                if (!state.isAir && state.block !== Blocks.GOLD_BLOCK) {
                    foundHighest = true
                    highestBlock = y
                } else core.append('0')
            }
            if (foundHighest) {
                if (state.isAir && bedrock >= 2 && y < 69) {
                    repeat(y - 11) { core.append('0') }
                    break
                }
                if (state.block === Blocks.BEDROCK) bedrock++
                else {
                    bedrock = 0
                    if (state.block === Blocks.OAK_PLANKS || state.block === Blocks.TRAPPED_CHEST || state.block === Blocks.CHEST) continue
                }
                core.append(state.block)
            }
        }
        return core.toString().hashCode() to highestBlock
    }

    private fun LevelChunk.stateAt(x: Int, y: Int, z: Int): BlockState {
        val index = getSectionIndex(y)
        if (index >= 0 && index < sections.size) {
            val section = sections[index]
            if (!section.hasOnlyAir()) return section.getBlockState(x and 15, y and 15, z and 15)
        }
        return Blocks.AIR.defaultBlockState()
    }
}
