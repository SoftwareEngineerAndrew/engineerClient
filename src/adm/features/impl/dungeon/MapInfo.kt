package com.adm.features.impl.dungeon

import com.engineerclient.dungeon.ClearInfo
import com.adm.AdmMod.mc
import com.adm.events.LevelEvent
import com.adm.events.RenderExtractEvent
import com.adm.events.RoomEnterEvent
import com.adm.events.ScoreUpdateEvent
import com.adm.events.TickEvent
import com.adm.events.core.EventBus
import com.adm.events.core.on
import com.adm.features.impl.dungeon.map.DungeonScan
import com.adm.features.impl.dungeon.map.tile.DungeonRoom
import com.adm.features.impl.dungeon.map.tile.RoomType
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
    private var ticks = 0

    fun install() {
        on<ScoreUpdateEvent> {
            if (shownTitle || !DungeonUtils.inDungeons || !ClearInfo.mapInfoOn || !ClearInfo.scoreAlert || score < 300) return@on
            shownTitle = true
            mc.execute { alert(ClearInfo.scoreAlertText.replace('&', '§')) }
            modMessage("§b${DungeonUtils.score} §ascore reached in §6${DungeonUtils.dungeonTime} §8|| §e${DungeonUtils.floor?.name}§8.")
        }

        on<RoomEnterEvent> {
            if (portalAABB == null && room?.type == RoomType.BLOOD) portalAABB = portalOf(room)
        }

        // The scanned map knows blood's place and rotation long before anyone walks in.
        on<TickEvent.End> {
            if (portalAABB != null || ++ticks % 10 != 0 || !ClearInfo.mapInfoOn || !ClearInfo.highlightPortal || !DungeonUtils.inClear) return@on
            for (room in DungeonScan.rooms) if (room.type == RoomType.BLOOD) {
                portalAABB = portalOf(room)
                break
            }
        }

        on<RenderExtractEvent> {
            val box = portalAABB ?: return@on
            if (!ClearInfo.mapInfoOn || !ClearInfo.highlightPortal || !DungeonUtils.inClear) return@on
            val score = DungeonUtils.score
            val color = if (score >= 300) ClearInfo.portalSPlus else if (score >= 270) ClearInfo.portalS else ClearInfo.portalUnderS
            if (!color.isTransparent) drawFilledBox(box, color, depth = true)
        }

        on<LevelEvent.Load> {
            shownTitle = false
            portalAABB = null
        }
        EventBus.subscribe(this)
    }

    private fun portalOf(room: DungeonRoom): AABB? {
        if (room.rotation == null || room.clayPos == null) return null
        return AABB.encapsulatingFullBlocks(room.getRealCoords(BlockPos(16, 69, 29)), room.getRealCoords(BlockPos(14, 69, 29))).inflate(0.0, 4.0, 0.0)
    }
}
