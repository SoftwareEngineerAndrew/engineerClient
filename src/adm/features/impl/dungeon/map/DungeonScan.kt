package com.adm.features.impl.dungeon.map

import com.adm.AdmMod
import com.adm.events.FloorEnterEvent
import com.adm.events.LevelEvent
import com.adm.events.MapUpdateEvent
import com.adm.events.RoomEnterEvent
import com.adm.events.core.EventBus
import com.adm.events.core.on
import com.adm.features.impl.dungeon.map.tile.DoorType
import com.adm.features.impl.dungeon.map.tile.DungeonDoor
import com.adm.features.impl.dungeon.map.tile.DungeonRoom
import com.adm.features.impl.dungeon.map.tile.DungeonTile
import com.adm.features.impl.dungeon.map.tile.RoomType
import com.adm.utils.Color
import com.adm.utils.Color.Companion.darker
import com.adm.utils.IVec2
import com.adm.utils.JsonResourceLoader.defaultGson
import com.adm.utils.skyblock.dungeon.Floor
import com.engineerclient.misc.DiskWrites
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object DungeonScan {

    const val ROOM_SPACING = 4

    var roomSize = 16
    val roomGap: Int get() = roomSize + ROOM_SPACING
    val connectionGap: Int get() = roomSize + ROOM_SPACING / 2
    var startX = 5
    var startY = 5

    val tiles: Array<DungeonTile> = Array(36) { freshTile(it) }
    val rooms: CopyOnWriteArrayList<DungeonRoom> = CopyOnWriteArrayList()
    val doors: ConcurrentHashMap<IVec2, DungeonDoor> = ConcurrentHashMap()

    val viewableDoors = CopyOnWriteArrayList<Pair<DungeonDoor, Color>>()
    val pathHints = CopyOnWriteArrayList<DungeonTile>()

    private val coresFile = File(AdmMod.configFile, "room-cores.json")
    val roomCores: MutableMap<String, MutableList<Map<Int, List<String>>>> by lazy {
        runCatching {
            defaultGson.fromJson<MutableMap<String, MutableList<Map<Int, List<String>>>>>(
                coresFile.readText(), object : TypeToken<MutableMap<String, MutableList<Map<Int, List<String>>>>>() {}.type
            )
        }.getOrNull() ?: mutableMapOf()
    }

    private var dimBloodSource = 0
    private var dimBlood: Color? = null

    init {
        on<LevelEvent.Load> { clear() }
        on<FloorEnterEvent> { initClient(floor) }
        on<RoomEnterEvent> { if (room != null) updateViewableDoors() }
        on<MapUpdateEvent> { updateViewableDoors() }

        EventBus.subscribe(WorldScan)
        EventBus.subscribe(MapScan)
        EventBus.subscribe(SpecialColumn)
        EventBus.subscribe(DoorScan)
    }

    private fun freshTile(index: Int) = DungeonTile(IVec2(index % 6, index / 6))

    fun recordRoomCore(name: String, core: Int, blocks: List<String>) {
        val known = roomCores.getOrPut(name) { mutableListOf() }
        for (entry in known) if (entry.containsKey(core)) return
        known.add(mapOf(core to blocks))
        val json = defaultGson.toJson(roomCores)
        DiskWrites.write {
            coresFile.parentFile.mkdirs()
            coresFile.writeText(json)
        }
    }

    private fun clear() {
        for (i in tiles.indices) tiles[i] = freshTile(i)
        rooms.clear()
        doors.clear()
        viewableDoors.clear()
        pathHints.clear()
        roomSize = 16
        startX = 5
        startY = 5
    }

    fun initClient(floor: Floor) {
        val n = floor.floorNumber
        roomSize = if (n <= 3) 18 else 16
        startX = if (n <= 1) 22 else if (n <= 3) 11 else 5
        startY = when (n) {
            0 -> 22
            1, 2, 3 -> 11
            4 -> 16
            else -> 5
        }
    }

    fun refresh() {
        updateViewableDoors()
        SpecialColumn.update()
    }

    fun updateViewableDoors() {
        val hints = ArrayList<DungeonTile>()
        for (door in doors.values) {
            val from = tiles[door.originTileIndex]
            val to = tiles[door.destinationTileIndex]
            val fromRoom = from.room
            val toRoom = to.room
            val fromSeen = fromRoom?.isViewable == true
            val toSeen = toRoom?.isViewable == true
            if (!fromSeen && !toSeen) continue

            if (!fromSeen) hints.add(from) else if (!toSeen) hints.add(to)
            val bothSeen = fromSeen && toSeen

            door.color = when (door.type) {
                DoorType.Wither -> DungeonMap.witherDoorColor
                DoorType.Blood -> if (bothSeen) DungeonMap.bloodDoorColor else dimmedBlood()
                DoorType.Fairy -> DungeonMap.fairyDoorColor
                DoorType.Normal -> when {
                    !bothSeen -> DungeonMap.unknownDoorColor
                    fromRoom.isSpecial() -> roomTypeColor(fromRoom!!.type)
                    toRoom.isSpecial() -> roomTypeColor(toRoom!!.type)
                    else -> DungeonMap.normalDoorColor
                }
            }
        }
        pathHints.clear()
        pathHints.addAll(hints)
    }

    private fun DungeonRoom?.isSpecial() = this != null && type != RoomType.NORMAL && type != RoomType.FAIRY

    private fun dimmedBlood(): Color {
        val source = DungeonMap.bloodDoorColor
        val cached = dimBlood
        if (cached != null && dimBloodSource == source.rgba) return cached
        return source.darker(0.5f).also {
            dimBlood = it
            dimBloodSource = source.rgba
        }
    }
}
