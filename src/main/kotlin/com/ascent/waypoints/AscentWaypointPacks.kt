package com.ascent.waypoints

import com.odtheking.odin.config.WaypointPackState
import com.odtheking.odin.config.normalized
import com.odtheking.odin.features.ModuleManager
import com.odtheking.odin.features.impl.dungeon.dungeonwaypoints.DungeonWaypoints.DungeonWaypoint
import com.odtheking.odin.features.impl.dungeon.map.tile.DungeonRoom
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.world.phys.AABB

/**
 * VENDORED from Odin `dungeonwaypoints/DungeonWaypointPacks.kt` (upstream 0.3.1 —
 * see VENDORED.md), retargeted at [AscentWaypoints] + [AscentPackFiles]. The room
 * application writes [AscentWaypoints.roomWaypoints] instead of Odin's
 * `room.waypoints` field (that field belongs to Odin's own renderer).
 */

suspend fun AscentWaypoints.loadWaypoints() {
    val packState = ensurePackState()
    loadedPacks = packState.selectedPackIds.associateWithTo(mutableMapOf()) { packId -> copyWaypointMap(AscentPackFiles.loadPack(packId)) }
    allActiveWaypoints = rebuildVisibleWaypoints()
    applyCurrentRoom()
}

suspend fun AscentWaypoints.saveWaypoints() {
    ensurePackState()
    AscentPackFiles.savePack(editPackId, copyWaypointMap(loadedPacks[editPackId] ?: mutableMapOf()))
}

fun AscentWaypoints.resetClickedWaypoints() {
    loadedPacks = loadedPacks.mapValuesTo(mutableMapOf()) { (_, packWaypoints) -> copyWaypointMap(packWaypoints) }
    allActiveWaypoints = rebuildVisibleWaypoints()
    applyCurrentRoom()
}

/** World-space waypoints for [room] from the merged active set — Ascent's analog of upstream's `DungeonRoom.setWaypoints()`. */
fun AscentWaypoints.applyRoom(room: DungeonRoom) {
    val name = room.data?.name ?: run {
        roomWaypoints = mutableSetOf()
        return
    }
    roomWaypoints = allActiveWaypoints[name]
        ?.mapTo(mutableSetOf()) { waypoint -> waypoint.copy(blockPos = room.getRealCoords(waypoint.blockPos)) }
        ?: mutableSetOf()
}

fun AscentWaypoints.applyCurrentRoom() {
    DungeonUtils.currentRoom?.let { applyRoom(it) } ?: run { roomWaypoints = mutableSetOf() }
}

fun AscentWaypoints.getWaypoints(room: DungeonRoom): MutableList<DungeonWaypoint> =
    allActiveWaypoints.getOrPut(room.data?.name ?: return mutableListOf()) { mutableListOf() }

fun AscentWaypoints.getEditableWaypoints(room: DungeonRoom): MutableList<DungeonWaypoint> =
    loadedPacks.getOrPut(editPackId) { mutableMapOf() }.getOrPut(room.data?.name ?: return mutableListOf()) { mutableListOf() }

fun AscentWaypoints.syncRoomToActive(room: DungeonRoom) {
    val name = room.data?.name ?: return
    val mergedRoom = mergeRoomWaypoints(name)
    if (mergedRoom.isEmpty()) allActiveWaypoints.remove(name)
    else allActiveWaypoints[name] = mergedRoom
    applyRoom(room)
}

private suspend fun AscentWaypoints.ensurePackState(
    requestedSelection: List<String> = selectedPackIds,
    requestedEditPackId: String = editPackId,
): WaypointPackState {
    var availablePacks = AscentPackFiles.listPackNames()
    if (availablePacks.isEmpty()) {
        AscentPackFiles.createPack("default")
        availablePacks = AscentPackFiles.listPackNames()
    }

    val normalizedState = WaypointPackState(
        selectedPackIds = requestedSelection.ifEmpty { selectedPackIds }.toMutableList(),
        editPackId = requestedEditPackId.ifBlank { editPackId },
    ).normalized(availablePacks)

    selectedPackIds = normalizedState.selectedPackIds.toMutableList()
    editPackId = normalizedState.editPackId
    ModuleManager.saveConfigurations()
    return normalizedState
}

private fun AscentWaypoints.rebuildVisibleWaypoints(): MutableMap<String, MutableList<DungeonWaypoint>> {
    val roomNames = loadedPacks.values.flatMap { it.keys }.distinct()
    return roomNames.associateWithTo(mutableMapOf()) { roomName -> mergeRoomWaypoints(roomName) }.also { merged ->
        merged.entries.removeIf { it.value.isEmpty() }
    }
}

private fun AscentWaypoints.mergeRoomWaypoints(roomName: String): MutableList<DungeonWaypoint> =
    selectedPackIds.fold(mutableListOf()) { merged, packId ->
        loadedPacks[packId]?.get(roomName)?.forEach { merged.add(it.resetRuntimeState()) }
        merged
    }

private fun copyWaypointMap(source: Map<String, List<DungeonWaypoint>>): MutableMap<String, MutableList<DungeonWaypoint>> =
    source.entries.associateTo(mutableMapOf()) { (room, waypoints) ->
        room to waypoints.mapTo(mutableListOf()) { it.resetRuntimeState() }
    }

private fun DungeonWaypoint.resetRuntimeState() = copy(
    color = color.copy(),
    aabb = AABB(aabb.minX, aabb.minY, aabb.minZ, aabb.maxX, aabb.maxY, aabb.maxZ),
    isClicked = false,
)
