package com.bloodrushwaypoints.waypoints

import com.odtheking.odin.events.SecretPickupEvent
import com.odtheking.odin.features.impl.dungeon.dungeonwaypoints.DungeonWaypoints.DungeonWaypoint
import com.odtheking.odin.features.impl.dungeon.dungeonwaypoints.DungeonWaypoints.WaypointType
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.world.phys.Vec3

/**
 * VENDORED from Odin `dungeonwaypoints/SecretWaypoints.kt` (upstream 0.3.1 — see
 * VENDORED.md), retargeted at [BrwWaypoints]. Marks BRW secret/etherwarp
 * waypoints clicked when the corresponding pickup/teleport is observed.
 */
object BrwSecretWaypoints {

    fun onSecret(event: SecretPickupEvent) {
        when (event) {
            is SecretPickupEvent.Interact -> clickSecret(event.blockPos, 0)
            is SecretPickupEvent.Bat -> clickSecret(BlockPos.containing(event.packet.x, event.packet.y, event.packet.z), 5)
            is SecretPickupEvent.Item -> clickSecret(event.entity.blockPosition(), 3)
        }
    }

    fun onEtherwarp(packet: ClientboundPlayerPositionPacket) {
        if (!DungeonUtils.inClear) return
        val room = DungeonUtils.currentRoom ?: return
        val etherPos = BrwWaypoints.lastEtherPos ?: return
        if (System.currentTimeMillis() - BrwWaypoints.lastEtherTime > 1000 || packet.change.position.distanceTo(Vec3(etherPos)) > 3) return
        val waypoints = BrwWaypoints.getWaypoints(room)
        waypoints.find { wp -> wp.blockPos == room.getRelativeCoords(etherPos) && wp.type == WaypointType.ETHERWARP }?.let {
            it.isClicked = true
            BrwWaypoints.lastEtherPos = null
            BrwWaypoints.applyRoom(room)
            BrwWaypoints.lastEtherTime = 0L
        }
    }

    private fun clickSecret(pos: BlockPos, distance: Int) {
        if (!DungeonUtils.inClear) return
        val room = DungeonUtils.currentRoom ?: return
        val blockPos = room.getRelativeCoords(pos)

        val waypoints = BrwWaypoints.getWaypoints(room)
        if (distance == 0) waypoints.find { wp -> wp.blockPos == blockPos && wp.isSecret && !wp.isClicked }
        else {
            waypoints.fold(null) { near: DungeonWaypoint?, wp ->
                val waypointDistance = wp.blockPos.distSqr(blockPos)
                if (waypointDistance <= distance && wp.isSecret && !wp.isClicked && (near == null || waypointDistance < near.blockPos.distSqr(blockPos))) wp
                else near
            }
        }?.let {
            it.isClicked = true
            BrwWaypoints.applyRoom(room)
        }
    }
}
