package com.adm.features.impl.dungeon.dungeonwaypoints

import com.adm.AdmMod.mc
import com.adm.features.impl.dungeon.dungeonwaypoints.DungeonWaypoints.DungeonWaypoint
import com.adm.features.impl.dungeon.dungeonwaypoints.DungeonWaypoints.WaypointType
import com.adm.features.impl.render.Etherwarp
import com.adm.utils.devMessage
import com.adm.utils.isEtherwarpItem
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket

object SecretWaypoints {
    @Volatile private var etherTarget: BlockPos? = null
    @Volatile private var etherTime = 0L

    internal fun aimEtherwarp() {
        if (!DungeonUtils.inClear) return
        val player = mc.player ?: return
        val data = player.mainHandItem.isEtherwarpItem() ?: return
        val result = Etherwarp.getEtherPos(player.position(), Etherwarp.getEtherwarpDistance(data))
        val pos = result.pos ?: return
        if (!result.succeeded) return
        etherTime = System.currentTimeMillis()
        etherTarget = pos
    }

    internal fun forgetEtherwarp() {
        etherTarget = null
        etherTime = 0L
    }

    internal fun onTeleport(packet: ClientboundPlayerPositionPacket) {
        val target = etherTarget ?: return
        if (System.currentTimeMillis() - etherTime > 1000) return
        val to = packet.change.position
        val dx = to.x - target.x
        val dy = to.y - target.y
        val dz = to.z - target.z
        if (dx * dx + dy * dy + dz * dz > 9.0) return
        mc.execute { landEtherwarp(target) }
    }

    private fun landEtherwarp(target: BlockPos) {
        if (!DungeonUtils.inClear) return
        val room = DungeonUtils.currentRoom ?: return
        val list = WaypointStore.roomWaypoints(room) ?: return
        val rel = room.getRelativeCoords(target)
        val hit = list.firstOrNull { it.type == WaypointType.ETHERWARP && it.blockPos == rel } ?: return
        hit.isClicked = true
        if (etherTarget == target) forgetEtherwarp()
        WaypointStore.changed()
    }

    /** [reach] is a squared block distance; 0 means the exact block. */
    internal fun onPickup(pos: BlockPos, reach: Int) {
        mc.execute { pickup(pos, reach) }
    }

    private fun pickup(pos: BlockPos, reach: Int) {
        if (!DungeonUtils.inClear) return
        val room = DungeonUtils.currentRoom ?: return
        val list = WaypointStore.roomWaypoints(room) ?: return
        val rel = room.getRelativeCoords(pos)
        var hit: DungeonWaypoint? = null
        if (reach == 0) {
            hit = list.firstOrNull { it.isSecret && !it.isClicked && it.blockPos == rel }
        } else {
            var best = Double.MAX_VALUE
            for (waypoint in list) {
                if (!waypoint.isSecret || waypoint.isClicked) continue
                val d = waypoint.blockPos.distSqr(rel)
                if (d <= reach && d < best) {
                    best = d
                    hit = waypoint
                }
            }
        }
        hit ?: return
        hit.isClicked = true
        WaypointStore.changed()
        devMessage("clicked ${hit.blockPos}")
    }

    fun resetSecrets() {
        WaypointStore.clearClicks()
    }
}
