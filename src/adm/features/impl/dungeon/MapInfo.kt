package com.adm.features.impl.dungeon

import com.engineerclient.dungeon.ClearInfo
import com.adm.events.LevelEvent
import com.adm.events.RenderExtractEvent
import com.adm.events.RoomEnterEvent
import com.adm.events.ScoreUpdateEvent
import com.adm.events.core.EventBus
import com.adm.events.core.on
import com.adm.features.impl.dungeon.map.tile.RoomType
import com.adm.utils.Color.Companion.withAlpha
import com.adm.utils.Colors
import com.adm.utils.alert
import com.adm.utils.modMessage
import com.adm.utils.render.drawFilledBox
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB

/** Clear Info's Map Info: the 300 score alert and the blood portal highlight; its HUD is [com.engineerclient.lcm.LcmMapInfo]. */
object MapInfo {
    private var portalAABB: AABB? = null
    private var shownTitle = false

    fun install() {
        on<ScoreUpdateEvent> {
            if (shownTitle || !DungeonUtils.inDungeons || !ClearInfo.mapInfoOn || !ClearInfo.scoreAlert || score < 300) return@on
            shownTitle = true
            alert("§c300 Score!")
            modMessage("§b${DungeonUtils.score} §ascore reached in §6${DungeonUtils.dungeonTime} §8|| §e${DungeonUtils.floor?.name}§8.")
        }

        on<RoomEnterEvent> {
            if (room?.type == RoomType.BLOOD)
                portalAABB = AABB.encapsulatingFullBlocks(room.getRealCoords(BlockPos(16, 69, 29)), room.getRealCoords(BlockPos(14, 69, 29))).inflate(0.0, 4.0, 0.0)
        }

        on<RenderExtractEvent> {
            if (!ClearInfo.mapInfoOn || !ClearInfo.highlightPortal || !DungeonUtils.inClear || DungeonUtils.score < 300) return@on
            portalAABB?.let { pos ->
                drawFilledBox(pos, Colors.MINECRAFT_GREEN.withAlpha(0.5f), depth = true)
            }
        }

        on<LevelEvent.Load> {
            shownTitle = false
            portalAABB = null
        }
        EventBus.subscribe(this)
    }
}
