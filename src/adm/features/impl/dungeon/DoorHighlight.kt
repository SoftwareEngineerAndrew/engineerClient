package com.adm.features.impl.dungeon

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.events.EntityEvent
import com.adm.events.LevelEvent
import com.adm.events.RenderExtractEvent
import com.adm.events.MessageEvent
import com.adm.events.core.on
import com.adm.features.Module
import com.adm.features.impl.dungeon.map.DungeonScan
import com.adm.features.impl.dungeon.map.tile.DoorType
import com.adm.features.impl.dungeon.map.tile.DungeonDoor
import com.adm.features.impl.dungeon.map.tile.DungeonRoom
import com.adm.features.impl.dungeon.map.tile.MapCheckmark
import com.adm.features.impl.dungeon.map.tile.RoomType
import com.adm.utils.Color
import com.adm.utils.Color.Companion.withAlpha
import com.adm.utils.Colors
import com.adm.utils.NameMemo
import com.adm.utils.alert
import com.adm.utils.render.BoxStyle
import com.adm.utils.render.drawStyledBox
import com.adm.utils.render.drawText
import com.adm.utils.render.drawTracer
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.engineerclient.mappred.MapPrediction
import com.engineerclient.misc.DoorOutline
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
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
    private val doorLabels by BooleanSetting("Normal Door Labels", false, desc = "Names the room past each exit and its found/total secrets.").withDependency { normalDoors }
    private val doorTracer by BooleanSetting("Door Tracer", false, desc = "A line to the nearest door the party's key opens.")
    private val keyTracer by BooleanSetting("Key Tracer", false, desc = "A line to the key while you're in its room.")
    private val keyPickupTitle by BooleanSetting("Key Pickup Title", false, desc = "A title naming who picked up a key.")

    private const val WITHER_KEY = "Wither Key"
    private const val BLOOD_KEY = "Blood Key"

    private var witherKeys = 0
    private var bloodKey = false
    private var bloodOpened = false

    /** The key stand on the ground and which key it is ([WITHER_KEY] or [BLOOD_KEY]). */
    private var keyStand: ArmorStand? = null
    private var keyName: String? = null
    private val keyNames = NameMemo { name -> name.string.takeIf { it == WITHER_KEY || it == BLOOD_KEY } }

    private val witherKeyObtainRegex = Regex("^(\\[[^]]*?])? ?(\\w{1,16}) has obtained Wither Key!?$")
    private val witherDoorOpenRegex = Regex("^(\\[[^]]*?])? ?(\\w{1,16}) opened a WITHER door!$")
    private val bloodKeyObtainRegex = Regex("^(\\[[^]]*?])? ?(\\w{1,16}) has obtained Blood Key!$")

    private val doorBoxes = Long2ObjectOpenHashMap<AABB>()
    private val doorCenters = Long2ObjectOpenHashMap<Vec3>()
    private class Label(val found: Int?, val text: String)
    private val labels = IdentityHashMap<DungeonRoom, Label>()
    private val scannedDoors = LongOpenHashSet()

    private val links = HashMap<DungeonRoom, ArrayList<DungeonRoom>>()
    private val exits = ArrayList<DungeonDoor>()
    private val pastExits = ArrayList<DungeonRoom>()
    private val visited: MutableSet<DungeonRoom> = Collections.newSetFromMap(IdentityHashMap())

    init {
        on<MessageEvent.Chat> {
            if (DungeonUtils.inClear) onChat(message)
        }

        on<EntityEvent.SetData> {
            if (!DungeonUtils.inClear) return@on
            val stand = entity as? ArmorStand ?: return@on
            if (stand === keyStand) return@on
            val name = keyNames[stand, stand.name] ?: return@on
            keyStand = stand
            keyName = name
            if (announceKeySpawn) alert("§${if (name == WITHER_KEY) '8' else 'c'}$name§7 spawned!")
        }

        on<RenderExtractEvent> {
            if (!DungeonUtils.inClear) return@on
            val style = DoorOutline.style(BoxStyle.FILLED_OUTLINE)

            scannedDoors.clear()
            for (door in DungeonScan.doors.values) {
                scannedDoors.add(spot(door))
                if (locked(door)) drawStyledBox(boxOf(door), lockedColor(door), style, false)
            }
            if (predictedDoors) for (door in MapPrediction.certainDoors()) {
                if (!scannedDoors.contains(spot(door)) && locked(door)) drawStyledBox(boxOf(door), lockedColor(door), style, false)
            }

            if (normalDoors) drawNormalDoors()
            if (doorTracer && (witherKeys > 0 || bloodKey)) traceDoor()

            val stand = keyStand ?: return@on
            if (!stand.isAlive) {
                keyStand = null
                keyName = null
                return@on
            }
            val x = stand.x + -0.5
            val y = stand.y + 1.0
            val z = stand.z + -0.5
            val color = if (keyName == WITHER_KEY) witherColor else bloodColor
            drawStyledBox(AABB(x, y, z, x + 1.0, y + 1.0, z + 1.0), color, style, true)
            if (keyTracer && roomAt(stand.blockX, stand.blockZ).let { it != null && it === DungeonUtils.currentRoom })
                drawTracer(Vec3(stand.x, y + 0.5, stand.z), color, false)
        }

        on<LevelEvent.Load> {
            keyStand = null
            keyName = null
            witherKeys = 0
            bloodKey = false
            bloodOpened = false
            doorBoxes.clear()
            doorCenters.clear()
            labels.clear()
            links.clear()
        }
    }

    private fun onChat(message: String) {
        when {
            message == "A Wither Key was picked up!" -> witherKeys++
            (message.endsWith(" has obtained Wither Key!") || message.endsWith(" has obtained Wither Key")) && witherKeyObtainRegex.matches(message) -> {
                witherKeys++
                pickedUp(witherKeyObtainRegex, message, "§8$WITHER_KEY")
            }
            message.endsWith(" opened a WITHER door!") && witherDoorOpenRegex.matches(message) -> witherKeys = (witherKeys - 1).coerceAtLeast(0)
            message == "A Blood Key was picked up!" -> bloodKey = true
            message.endsWith(" has obtained Blood Key!") && bloodKeyObtainRegex.matches(message) -> {
                bloodKey = true
                pickedUp(bloodKeyObtainRegex, message, "§c$BLOOD_KEY")
            }
            message == "The BLOOD DOOR has been opened!" -> {
                bloodKey = false
                bloodOpened = true
            }
        }
    }

    private fun pickedUp(regex: Regex, message: String, key: String) {
        if (!keyPickupTitle) return
        val name = regex.find(message)?.groupValues?.get(2) ?: return
        alert("§a$name §7has the $key")
    }

    private fun roomAt(x: Int, z: Int): DungeonRoom? {
        val col = (x + 201) shr 5
        val row = (z + 201) shr 5
        if (col !in 0..5 || row !in 0..5) return null
        return roomAt(row * 6 + col)
    }

    private fun openable(door: DungeonDoor): Boolean =
        locked(door) && (if (door.type == DoorType.Wither) witherKeys > 0 else bloodKey)

    private fun RenderExtractEvent.traceDoor() {
        val player = mc.player ?: return
        var best: DungeonDoor? = null
        var bestDist = Double.MAX_VALUE
        for (door in DungeonScan.doors.values) {
            if (!openable(door)) continue
            val d = player.distanceToSqr(door.worldX + 0.5, 70.0, door.worldZ + 0.5)
            if (d < bestDist) { bestDist = d; best = door }
        }
        if (predictedDoors) for (door in MapPrediction.certainDoors()) {
            if (scannedDoors.contains(spot(door)) || !openable(door)) continue
            val d = player.distanceToSqr(door.worldX + 0.5, 70.0, door.worldZ + 0.5)
            if (d < bestDist) { bestDist = d; best = door }
        }
        val door = best ?: return
        drawTracer(centerOf(door), openableColor, false)
    }

    private fun centerOf(door: DungeonDoor): Vec3 {
        val key = spot(door)
        doorCenters.get(key)?.let { return it }
        val center = Vec3(door.worldX + 0.5, 71.0, door.worldZ + 0.5)
        doorCenters.put(key, center)
        return center
    }

    private fun labelOf(room: DungeonRoom): String? {
        val data = room.data ?: return null
        val found = room.foundSecrets
        labels[room]?.let { if (it.found == found) return it.text }
        val secrets = if (data.maxSecrets > 0) " §7(§b${found ?: "?"}§7/§b${data.maxSecrets}§7)" else ""
        val text = "§f${data.name}$secrets"
        labels[room] = Label(found, text)
        return text
    }

    private fun locked(door: DungeonDoor): Boolean =
        door.type == DoorType.Wither || door.type == DoorType.Blood && !bloodOpened

    private fun lockedColor(door: DungeonDoor): Color =
        if (if (door.type == DoorType.Wither) witherKeys > 0 else bloodKey) openableColor else doorHighlightColor

    private fun spot(door: DungeonDoor): Long = (door.worldX.toLong() shl 32) or (door.worldZ.toLong() and 0xFFFFFFFFL)

    private fun boxOf(door: DungeonDoor): AABB {
        val key = spot(door)
        doorBoxes.get(key)?.let { return it }
        val box = AABB(door.worldX - 1.0, 69.0, door.worldZ - 1.0, door.worldX + 2.0, 73.0, door.worldZ + 2.0)
        doorBoxes.put(key, box)
        return box
    }

    private fun roomAt(tile: Int): DungeonRoom? = DungeonScan.tiles.getOrNull(tile)?.room

    private fun link(from: DungeonRoom, to: DungeonRoom) {
        links.getOrPut(from) { ArrayList() }.add(to)
    }

    private fun RenderExtractEvent.drawNormalDoors() {
        val here = DungeonUtils.currentRoom ?: return
        for (next in links.values) next.clear()
        exits.clear()
        pastExits.clear()
        for (door in DungeonScan.doors.values) {
            val a = roomAt(door.originTileIndex) ?: continue
            val b = roomAt(door.destinationTileIndex) ?: continue
            link(a, b)
            link(b, a)
            if (door.type != DoorType.Normal && door.type != DoorType.Fairy) continue
            val past = if (a === here) b else if (b === here) a else continue
            exits.add(door)
            pastExits.add(past)
        }
        for (i in exits.indices) {
            if (hideDone) {
                visited.clear()
                visited.add(here)
                visited.add(pastExits[i])
                if (!leadsSomewhere(pastExits[i])) continue
            }
            drawStyledBox(boxOf(exits[i]), normalColor, BoxStyle.OUTLINE, false)
            if (doorLabels) labelOf(pastExits[i])?.let { drawText(it, centerOf(exits[i]).add(0.0, 2.5, 0.0), 1.5f, false) }
        }
    }

    /** A room is worth going through while it, or a room only reachable past it, isn't green. */
    private fun leadsSomewhere(room: DungeonRoom): Boolean {
        if (room.type == RoomType.ENTRANCE) return false
        if (room.checkmark != MapCheckmark.GREEN) return true
        val next = links[room] ?: return false
        for (i in next.indices) if (visited.add(next[i]) && leadsSomewhere(next[i])) return true
        return false
    }
}
