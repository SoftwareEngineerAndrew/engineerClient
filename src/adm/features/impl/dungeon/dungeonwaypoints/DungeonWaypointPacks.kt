package com.adm.features.impl.dungeon.dungeonwaypoints

import com.adm.AdmMod
import com.adm.config.WaypointPackFileUtils
import com.adm.config.settlePackSelection
import com.adm.features.ModuleManager
import com.adm.features.impl.dungeon.dungeonwaypoints.DungeonWaypoints.DungeonWaypoint
import com.adm.features.impl.dungeon.map.tile.DungeonRoom
import com.adm.utils.modMessage
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

internal typealias PackRooms = MutableMap<String, MutableList<DungeonWaypoint>>

// Merged room lists hold the pack objects themselves; a click lives on them until the next load or clearClicks.
internal object WaypointStore {
    @Volatile private var packs: MutableMap<String, PackRooms> = LinkedHashMap()
    @Volatile private var merged: HashMap<String, ArrayList<DungeonWaypoint>> = HashMap()
    private val version = AtomicInteger()
    @Volatile private var loadedStamp: List<Long>? = null

    val revision: Int get() = version.get()
    val loaded: Boolean get() = loadedStamp != null

    fun changed() { version.incrementAndGet() }

    fun roomWaypoints(room: DungeonRoom): List<DungeonWaypoint>? = merged[room.data?.name ?: return null]

    fun editable(room: DungeonRoom): MutableList<DungeonWaypoint>? {
        val name = room.data?.name ?: return null
        return packs.getOrPut(DungeonWaypoints.editPackId) { LinkedHashMap() }.getOrPut(name) { ArrayList() }
    }

    fun waypointCount(packId: String): Int = packs[packId]?.values?.sumOf { it.size } ?: -1

    fun roomEdited(room: DungeonRoom) {
        val name = room.data?.name ?: return
        val list = ArrayList<DungeonWaypoint>()
        for (id in DungeonWaypoints.selectedPackIds) packs[id]?.get(name)?.let { list.addAll(it) }
        for (waypoint in list) waypoint.isClicked = false
        if (list.isEmpty()) merged.remove(name) else merged[name] = list
        changed()
        val packId = DungeonWaypoints.editPackId
        WaypointPackFileUtils.saveLater(packId, editPackCopy())
    }

    fun editPackCopy(): PackRooms {
        val copy: PackRooms = LinkedHashMap()
        packs[DungeonWaypoints.editPackId]?.forEach { (room, list) -> copy[room] = ArrayList(list) }
        return copy
    }

    fun clearClicks() {
        for (list in merged.values) for (waypoint in list) waypoint.isClicked = false
        changed()
    }

    fun reloadIfChanged() {
        AdmMod.scope.launch {
            val stamp = withContext(WaypointPackFileUtils.io) { WaypointPackFileUtils.stamp(DungeonWaypoints.selectedPackIds) }
            if (stamp != loadedStamp) load()
        }
    }

    suspend fun load() {
        val selected = settle()
        val (loaded, stamp) = withContext(WaypointPackFileUtils.io) {
            LinkedHashMap<String, PackRooms>().also { for (id in selected) it[id] = WaypointPackFileUtils.read(id) } to
                WaypointPackFileUtils.stamp(selected)
        }
        loadedStamp = stamp
        val rooms = HashMap<String, ArrayList<DungeonWaypoint>>()
        for (id in selected) loaded[id]?.forEach { (room, list) ->
            if (list.isNotEmpty()) rooms.getOrPut(room) { ArrayList() }.addAll(list)
        }
        packs = loaded
        merged = rooms
        changed()
    }

    suspend fun createPack(name: String): Boolean {
        if (!WaypointPackFileUtils.create(name)) return false
        settle(DungeonWaypoints.selectedPackIds + name, name)
        load()
        return true
    }

    suspend fun importPack(name: String, waypoints: PackRooms): Boolean {
        if (!WaypointPackFileUtils.create(name)) return false
        withContext(WaypointPackFileUtils.io) { WaypointPackFileUtils.write(name, waypoints) }
        settle(DungeonWaypoints.selectedPackIds + name, name)
        load()
        return true
    }

    suspend fun deletePack(name: String): Boolean {
        if (withContext(WaypointPackFileUtils.io) { WaypointPackFileUtils.packNames().size } <= 1) {
            modMessage("§cCannot delete the only pack!")
            return false
        }
        if (!WaypointPackFileUtils.delete(name)) return false
        settle(DungeonWaypoints.selectedPackIds.filter { it != name }, DungeonWaypoints.editPackId.takeIf { it != name } ?: "")
        load()
        return true
    }

    suspend fun renamePack(from: String, to: String): Boolean {
        if (!WaypointPackFileUtils.rename(from, to)) return false
        val edit = DungeonWaypoints.editPackId
        settle(DungeonWaypoints.selectedPackIds.map { if (it == from) to else it }, if (edit == from) to else edit)
        load()
        return true
    }

    suspend fun selectPacks(selected: List<String>, edit: String) {
        settle(selected, edit, persist = true)
        load()
    }

    private suspend fun settle(
        selected: List<String> = DungeonWaypoints.selectedPackIds,
        edit: String = DungeonWaypoints.editPackId,
        persist: Boolean = false,
    ): List<String> {
        val available = withContext(WaypointPackFileUtils.io) { WaypointPackFileUtils.packNames() }
        val result = settlePackSelection(
            selected.ifEmpty { DungeonWaypoints.selectedPackIds },
            edit.ifBlank { DungeonWaypoints.editPackId },
            available,
        )
        val changed = result.selected != DungeonWaypoints.selectedPackIds || result.edit != DungeonWaypoints.editPackId
        if (changed) {
            DungeonWaypoints.selectedPackIds = result.selected.toMutableList()
            DungeonWaypoints.editPackId = result.edit
        }
        if (changed || persist) ModuleManager.saveConfigurations()
        return result.selected
    }
}
