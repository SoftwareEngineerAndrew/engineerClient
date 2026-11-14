package com.odtheking.odin.features.impl.dungeon

import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.events.EntityEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.features.impl.dungeon.map.tile.DoorType
import com.odtheking.odin.features.impl.dungeon.map.tile.DungeonDoor
import com.odtheking.odin.features.impl.dungeon.map.tile.DungeonRoom
import com.odtheking.odin.features.impl.dungeon.map.tile.MapCheckmark
import com.odtheking.odin.features.impl.dungeon.map.tile.RoomType
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Color.Companion.withAlpha
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.alert
import com.odtheking.odin.utils.equalsOneOf
import com.odtheking.odin.utils.render.BoxStyle
import com.odtheking.odin.utils.render.drawStyledBox
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.AABB
import java.util.Collections
import java.util.IdentityHashMap

object DoorHighlight : Module(
    name = "Door Highlight",
    description = "Highlights wither, blood and normal doors and keys in dungeons."
) {
    private val announceKeySpawn by BooleanSetting("Announce Key Spawn", true, desc = "Announces when a key is spawned.")
    private val doorHighlightColor by ColorSetting("Door Highlight Color", Colors.MINECRAFT_RED.withAlpha(0.8f), true, desc = "Color for locked doors.")
    private val openableColor by ColorSetting("Openable Door Color", Colors.MINECRAFT_GREEN.withAlpha(0.8f), true, desc = "Color for doors that can be opened with a held key.")
    private val witherColor by ColorSetting("Wither Color", Colors.BLACK.withAlpha(0.8f), true, desc = "The color of the box.")
    private val bloodColor by ColorSetting("Blood Color", Colors.MINECRAFT_RED.withAlpha(0.8f), true, desc = "The color of the box.")
    private val predictedDoors by BooleanSetting("Predicted Doors", true, desc = "Also boxes wither and blood doors Map Prediction is sure of.")
    private val normalDoors by BooleanSetting("Highlight Normal Doors", false, desc = "Also boxes the normal doors of the room you're in.")
    private val hideDone by BooleanSetting("Hide Done Doors", true, desc = "No box on a door when every room past it is green.").withDependency { normalDoors }
    private val normalColor by ColorSetting("Normal Door Color", Color(0, 128, 128, 0.8f), true, desc = "Color for normal doors.").withDependency { normalDoors }

    private var currentKey: KeyType? = null
    private var witherKeys = 0
    private var bloodKey = false
    private var bloodOpened = false

    private val witherKeyObtainRegex = Regex("^(\\[[^]]*?])? ?(\\w{1,16}) has obtained Wither Key!?$")
    private val witherKeyPickedUpRegex = Regex("^A Wither Key was picked up!$")
    private val witherDoorOpenRegex = Regex("^(\\[[^]]*?])? ?(\\w{1,16}) opened a WITHER door!$")
    private val bloodKeyObtainRegex = Regex("^(\\[[^]]*?])? ?(\\w{1,16}) has obtained Blood Key!$")
    private val bloodKeyPickedUpRegex = Regex("^A Blood Key was picked up!$")
    private val bloodDoorOpenRegex = Regex("^The BLOOD DOOR has been opened!$")

    init {
        on<MessageEvent.Chat> {
            if (!DungeonUtils.inClear) return@on
            when {
                witherKeyObtainRegex.matches(message) || witherKeyPickedUpRegex.matches(message) -> witherKeys++
                witherDoorOpenRegex.matches(message) -> witherKeys = (witherKeys - 1).coerceAtLeast(0)
                bloodKeyObtainRegex.matches(message) || bloodKeyPickedUpRegex.matches(message) -> bloodKey = true
                bloodDoorOpenRegex.matches(message) -> { bloodKey = false; bloodOpened = true }
            }
        }

        on<EntityEvent.SetData> {
            if (!DungeonUtils.inClear) return@on
            val entity = entity as? ArmorStand ?: return@on
            if (currentKey?.entity == entity) return@on
            currentKey = KeyType.entries.find { it.displayName == entity.name.string } ?: return@on
            currentKey?.entity = entity

            if (announceKeySpawn) alert("§${currentKey?.colorCode}${entity.name.string}§7 spawned!")
        }

        on<RenderExtractEvent> {
            if (!DungeonUtils.inClear) return@on

            val known = DungeonScan.doors.values.map { it.worldX to it.worldZ }.toSet()
            val predicted = if (predictedDoors) com.engineerclient.mappred.OdinMapPrediction.certainDoors().filter { (it.worldX to it.worldZ) !in known } else emptyList()
            (DungeonScan.doors.values + predicted).forEach { door ->
                if (!door.type.equalsOneOf(DoorType.Wither, DoorType.Blood)) return@forEach
                if (door.type == DoorType.Blood && bloodOpened) return@forEach

                val box = AABB(door.worldX - 1.0, 69.0, door.worldZ - 1.0, door.worldX + 2.0, 73.0, door.worldZ + 2.0)

                val isOpenable = when (door.type) {
                    DoorType.Wither -> witherKeys > 0
                    DoorType.Blood -> bloodKey
                    else -> false
                }
                drawStyledBox(box, if (isOpenable) openableColor else doorHighlightColor, BoxStyle.FILLED_OUTLINE, false)
            }

            if (normalDoors) drawNormalDoors()

            if (currentKey == null || currentKey?.entity == null) return@on
            currentKey?.let { keyType ->
                if (keyType.entity?.isAlive == false) {
                    currentKey = null
                    return@on
                }
                val position = keyType.entity?.position() ?: return@on
                drawStyledBox(AABB.unitCubeFromLowerCorner(position.add(-0.5, 1.0, -0.5)), keyType.color(), BoxStyle.FILLED_OUTLINE, true)
            }
        }

        on<LevelEvent.Load> {
            currentKey = null
            witherKeys = 0
            bloodKey = false
            bloodOpened = false
        }
    }

    private fun RenderExtractEvent.drawNormalDoors() {
        val here = DungeonUtils.currentRoom ?: return
        val links = HashMap<DungeonRoom, MutableList<DungeonRoom>>()
        val mine = ArrayList<Pair<DungeonDoor, DungeonRoom>>()
        for ((_, door) in DungeonScan.doors) {
            val a = DungeonScan.tiles.getOrNull(door.originTileIndex)?.room ?: continue
            val b = DungeonScan.tiles.getOrNull(door.destinationTileIndex)?.room ?: continue
            links.getOrPut(a) { ArrayList() }.add(b)
            links.getOrPut(b) { ArrayList() }.add(a)
            if (!door.type.equalsOneOf(DoorType.Normal, DoorType.Fairy)) continue
            if (a === here) mine += door to b else if (b === here) mine += door to a
        }
        for ((door, past) in mine) {
            if (hideDone && !leadsSomewhere(past, links, Collections.newSetFromMap(IdentityHashMap<DungeonRoom, Boolean>()).apply { add(here); add(past) })) continue
            val box = AABB(door.worldX - 1.0, 69.0, door.worldZ - 1.0, door.worldX + 2.0, 73.0, door.worldZ + 2.0)
            drawStyledBox(box, normalColor, BoxStyle.OUTLINE, true)
        }
    }

    /** A room is worth going through while it, or a room only reachable past it, isn't green. */
    private fun leadsSomewhere(room: DungeonRoom, links: Map<DungeonRoom, List<DungeonRoom>>, seen: MutableSet<DungeonRoom>): Boolean {
        if (room.type == RoomType.ENTRANCE) return false
        if (room.checkmark != MapCheckmark.GREEN) return true
        return links[room].orEmpty().any { seen.add(it) && leadsSomewhere(it, links, seen) }
    }

    private enum class KeyType(val displayName: String, val color: () -> Color, val colorCode: Char) {
        Wither("Wither Key", { witherColor }, '8'),
        Blood("Blood Key", { bloodColor }, 'c');

        var entity: Entity? = null
    }
}