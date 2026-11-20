package com.adm.features.impl.dungeon.map

import com.adm.AdmMod.mc
import com.adm.features.impl.dungeon.map.tile.DoorRotation
import com.adm.features.impl.dungeon.map.tile.DoorType
import com.adm.features.impl.dungeon.map.tile.DungeonDoor
import com.adm.features.impl.dungeon.map.tile.DungeonRoom
import com.adm.features.impl.dungeon.map.tile.DungeonTile
import com.adm.features.impl.dungeon.map.tile.MapCheckmark
import com.adm.features.impl.dungeon.map.tile.RoomData
import com.adm.features.impl.dungeon.map.tile.RoomRotation
import com.adm.features.impl.dungeon.map.tile.RoomShape
import com.adm.features.impl.dungeon.map.tile.RoomType
import com.adm.utils.Color
import com.adm.utils.Colors
import com.adm.utils.IVec2
import com.adm.utils.itemId
import com.adm.utils.renderX
import com.adm.utils.renderZ
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.engineerclient.lcm.LcmStyle
import com.engineerclient.mapicons.MapIcons
import com.engineerclient.mapicons.MapSizes
import com.engineerclient.mappred.MapPrediction
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.world.entity.player.Player
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import java.awt.Color.HSBtoRGB

private const val GRID = 20
private const val ICON = 12

private val crossIcon = Identifier.fromNamespaceAndPath("engineerclient", "map/cross.png")
private val greenIcon = Identifier.fromNamespaceAndPath("engineerclient", "map/green_check.png")
private val whiteIcon = Identifier.fromNamespaceAndPath("engineerclient", "map/white_check.png")
private val questionIcon = Identifier.fromNamespaceAndPath("engineerclient", "map/question.png")
private val greyText = Color(100, 100, 100).rgba

internal class RoomLabel(val mode: DungeonMap.RoomTextOption, val data: RoomData?, val found: Int, val lines: List<String>)

fun GuiGraphicsExtractor.fillRoom(room: DungeonRoom, color: Int) {
    val s = MapSizes.room()
    val x = room.topLeft.x * GRID
    val y = room.topLeft.z * GRID
    val two = GRID + s
    when (room.shape) {
        RoomShape.OneByOne -> fill(x, y, x + s, y + s, color)
        RoomShape.TwoByTwo -> fill(x, y, x + two, y + two, color)
        RoomShape.L -> when (room.rotation) {
            RoomRotation.WEST -> {
                fill(x, y, x + two, y + s, color)
                fill(x, y + s, x + s, y + two, color)
            }
            RoomRotation.NORTH -> {
                fill(x, y, x + two, y + s, color)
                fill(x + GRID, y + s, x + two, y + two, color)
            }
            else -> {
                fill(x, y, x + s, y + two, color)
                fill(x + s, y + GRID, x + two, y + two, color)
            }
        }
        else -> {
            val long = (room.shape.tileAmount - 1) * GRID + s
            if (room.rotation == RoomRotation.SOUTH) fill(x, y, x + long, y + s, color) else fill(x, y, x + s, y + long, color)
        }
    }
}

fun GuiGraphicsExtractor.renderMap(rooms: Collection<DungeonRoom>, doors: Collection<DungeonDoor>, pathHints: Collection<DungeonTile>) {
    drawDoors(doors)
    for (room in rooms) {
        if (!room.isViewable) continue
        val color = if (room.mapSeen || room.type == RoomType.ENTRANCE) roomTypeColor(room.type) else DungeonMap.pendingRoomColor
        fillRoom(room, color.rgba)
    }
    val shift = MapSizes.shift()
    for (room in rooms) drawLabel(room, shift)
    if (!MapPrediction.render(this)) drawUnknowns(pathHints, rooms, shift)
}

private fun GuiGraphicsExtractor.drawDoors(doors: Collection<DungeonDoor>) {
    val s = MapSizes.room()
    val gap = GRID - s
    if (gap <= 0) return
    val width = MapSizes.door()
    val inset = (s - width) / 2f
    val whole = (s - width) % 2 == 0
    for (door in doors) {
        val x = door.position.x * GRID
        val y = door.position.z * GRID
        val across = door.rotation == DoorRotation.Horizontal
        val w = if (across) gap else width
        val h = if (across) width else gap
        if (whole) {
            val left = x + if (across) s else inset.toInt()
            val top = y + if (across) inset.toInt() else s
            fill(left, top, left + w, top + h, door.color.rgba)
        } else {
            pose().pushMatrix()
            pose().translate(x + if (across) s.toFloat() else inset, y + if (across) inset else s.toFloat())
            fill(0, 0, w, h, door.color.rgba)
            pose().popMatrix()
        }
    }
}

private fun GuiGraphicsExtractor.drawUnknowns(hints: Collection<DungeonTile>, rooms: Collection<DungeonRoom>, shift: Float) {
    val guessing = !DungeonMap.disablePred
    var inColumn = -1
    var outColumn = -1
    for (hint in hints) {
        val room = hint.room
        if (room != null && room.walkedInto) continue
        val pos = hint.position
        when {
            room != null && room.type == RoomType.BLOOD -> guessTile(pos, 1, dim(DungeonMap.bloodRoomColor), 0, 0, shift)
            guessing && room != null && room.isKnown1x1 -> {
                if (inColumn < 0) { inColumn = SpecialColumn.guessCode(true); outColumn = SpecialColumn.guessCode(false) }
                guessTile(pos, if (pos.x == SpecialColumn.column) inColumn else outColumn, shift)
            }
            else -> guessTile(pos, 1, DungeonMap.unknownRoomColor.rgba, 0, 0, shift)
        }
    }

    if (!guessing) return
    for (room in rooms) {
        if (!room.isKnown1x1 || room.isViewable) continue
        val tiles = room.tiles
        for (i in tiles.indices) {
            val pos = tiles[i]
            if (hints.any { it.position == pos }) continue
            if (inColumn < 0) { inColumn = SpecialColumn.guessCode(true); outColumn = SpecialColumn.guessCode(false) }
            guessTile(pos, if (pos.x == SpecialColumn.column) inColumn else outColumn, shift)
        }
    }
}

private fun GuiGraphicsExtractor.guessTile(pos: IVec2, code: Int, shift: Float) {
    val n = SpecialColumn.guessCount(code)
    fun shade(i: Int): Int {
        val c = SpecialColumn.kindColor(SpecialColumn.guessKind(code, i))
        return if (n == 1) c.rgba else dim(c)
    }
    guessTile(pos, n, shade(0), if (n > 1) shade(1) else 0, if (n > 2) shade(2) else 0, shift)
}

private fun GuiGraphicsExtractor.guessTile(pos: IVec2, parts: Int, a: Int, b: Int, c: Int, shift: Float) {
    val s = MapSizes.room()
    val x = pos.x * GRID
    val y = pos.z * GRID
    when (parts) {
        1 -> fill(x, y, x + s, y + s, a)
        2 -> {
            val mid = x + s / 2
            fill(x, y, mid, y + s, a)
            fill(mid, y, x + s, y + s, b)
        }
        3 -> {
            val third = s / 3
            fill(x, y, x + third, y + s, a)
            fill(x + third, y, x + third * 2, y + s, b)
            fill(x + third * 2, y, x + s, y + s, c)
        }
    }
    if (MapIcons.showQuestionMarks) icon(x + Math.round(shift), y + Math.round(shift), questionIcon)
}

private fun GuiGraphicsExtractor.icon(x: Int, y: Int, id: Identifier) {
    blit(RenderPipelines.GUI_TEXTURED, id, x + 2, y + 2, ICON.toFloat(), ICON.toFloat(), ICON, ICON, ICON, ICON)
}

private fun GuiGraphicsExtractor.drawLabel(room: DungeonRoom, shift: Float) {
    when (room.type) {
        RoomType.UNDISCOVERED, RoomType.FAIRY, RoomType.ENTRANCE, RoomType.BLOOD -> return
        else -> {}
    }
    val mode = DungeonMap.roomText
    val maxSecrets = room.data?.maxSecrets ?: 0
    if (!room.shouldShowName || mode == DungeonMap.RoomTextOption.CHECKMARKS || (mode == DungeonMap.RoomTextOption.ROOM_SECRETS && maxSecrets == 0)) {
        val id = when (room.checkmark) {
            MapCheckmark.GREEN -> greenIcon
            MapCheckmark.WHITE -> whiteIcon
            MapCheckmark.RED -> crossIcon
            else -> return
        }
        if (!MapIcons.showCheckmarks) return
        pose().pushMatrix()
        pose().translate(shift, shift)
        if (!MapChecks.draw(this, room, GRID, GRID - 4)) icon(room.topLeft.x * GRID, room.topLeft.z * GRID, id)
        pose().popMatrix()
        return
    }

    val center = room.center ?: return
    val lines = labelLines(room, mode, maxSecrets) ?: return
    val color = when (room.checkmark) {
        MapCheckmark.GREEN -> Colors.MINECRAFT_GREEN.rgba
        MapCheckmark.WHITE -> Colors.WHITE.rgba
        MapCheckmark.RED -> Colors.MINECRAFT_RED.rgba
        else -> greyText
    }
    val font = mc.font
    val lineHeight = font.lineHeight
    val scale = DungeonMap.textScaling
    val top = center.z.toFloat() - (lines.size - 1) * lineHeight * scale / 2f
    pose().pushMatrix()
    pose().translate(shift, shift)
    for (i in lines.indices) {
        pose().pushMatrix()
        pose().translate(center.x.toFloat(), top + i * lineHeight * scale)
        pose().scale(scale)
        centeredText(font, lines[i], 0, -lineHeight / 2, color)
        pose().popMatrix()
    }
    pose().popMatrix()
}

private fun labelLines(room: DungeonRoom, mode: DungeonMap.RoomTextOption, maxSecrets: Int): List<String>? {
    val found = room.foundSecrets ?: Int.MIN_VALUE
    val cached = room.label
    if (cached != null && cached.mode == mode && cached.data === room.data && cached.found == found) return cached.lines.ifEmpty { null }

    val secrets = if (maxSecrets > 0) " ${room.foundSecrets ?: "?"}/${room.data?.maxSecrets}" else ""
    val text = when (mode) {
        DungeonMap.RoomTextOption.BOTH -> "${room.name}$secrets"
        DungeonMap.RoomTextOption.ROOM_NAME -> room.name
        DungeonMap.RoomTextOption.ROOM_SECRETS -> secrets
        DungeonMap.RoomTextOption.CHECKMARKS -> null
    }
    val lines = text?.trim()?.split(" ") ?: emptyList()
    room.label = RoomLabel(mode, room.data, found, lines)
    return lines.ifEmpty { null }
}

private fun dim(c: Color): Int =
    (HSBtoRGB(c.hue, c.saturation, c.brightness * 0.5f) and 0xFFFFFF) or ((c.alphaFloat * 255).toInt() shl 24)

fun GuiGraphicsExtractor.renderPlayers() {
    val self = mc.player
    val held = self?.mainHandItem?.itemId
    val showNames = held == "INFINITE_SPIRIT_LEAP" || held == "SPIRIT_LEAP"
    val back = 4 + DungeonMap.playerHeadBackgroundScale

    for (mate in DungeonUtils.dungeonTeammatesNoSelf) {
        if (mate.isDead) continue
        val entity = mate.entity
        pose().pushMatrix()
        if (entity != null) pose().translate(mapX(entity.renderX.toFloat()), mapX(entity.renderZ.toFloat()))
        else pose().translate((mate.mapPos.x + 128) / 2f - DungeonScan.startX, (mate.mapPos.z + 128) / 2f - DungeonScan.startY)

        if (showNames) {
            pose().pushMatrix()
            pose().scale(DungeonMap.playerNamesScaling)
            centeredText(mc.font, mate.name, 0, 8, DungeonMap.playerNameColor.rgba)
            pose().popMatrix()
        }

        pose().rotate(Math.toRadians(180.0 + mate.renderYaw).toFloat())
        val skin = mate.playerSkin
        if (skin != null) {
            pose().scale(DungeonMap.playerHeadScale)
            fill(-back, -back, back, back, mate.clazz.color.rgba)
            PlayerFaceExtractor.extractRenderState(this, skin, -4, -4, 8)
        }
        pose().popMatrix()
    }

    pose().pushMatrix()
    val (selfX, selfZ) = playerRenderPosition(self, IVec2(0, 0))
    pose().translate(selfX, selfZ)
    pose().rotate(Math.toRadians(180.0 + (self?.yRot ?: 0f)).toFloat())

    if (DungeonMap.playerHead) {
        val skin = self?.skin
        if (skin != null) {
            pose().scale(DungeonMap.playerHeadScale)
            fill(-back, -back, back, back, DungeonUtils.currentDungeonPlayer.clazz.color.rgba)
            PlayerFaceExtractor.extractRenderState(this, skin, -4, -4, 8)
        }
    } else {
        pose().scale(DungeonMap.playerPointerScale)
        selfArrow(LcmStyle.ownMarkerColor.value.rgba)
    }
    pose().popMatrix()
}

private const val ARROW_RES = 8

// An arrowhead pointing up (-y), centred on the origin, as rows of 1/ARROW_RES-unit spans: drawn under the pose's rotation it
// stays sharp at any angle and map scale, where a pixel texture turns ragged.
private class ArrowSpans(val y0: IntArray, val y1: IntArray, val x0: IntArray, val x1: IntArray)

private fun arrowSpans(grow: Float): ArrowSpans {
    val poly = arrayOf(floatArrayOf(0f, -4.25f), floatArrayOf(3.25f, 3.75f), floatArrayOf(0f, 1.75f), floatArrayOf(-3.25f, 3.75f))
    fun spans(yc: Float): List<FloatArray> {
        val xs = ArrayList<Float>()
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[(i + 1) % poly.size]
            if ((a[1] <= yc) != (b[1] <= yc)) xs += a[0] + (yc - a[1]) / (b[1] - a[1]) * (b[0] - a[0])
        }
        xs.sort()
        return (0 until xs.size / 2).map { floatArrayOf(xs[2 * it], xs[2 * it + 1]) }
    }
    val ys = ArrayList<Int>(); val x0 = ArrayList<Int>(); val x1 = ArrayList<Int>()
    val steps = (grow * ARROW_RES).toInt()
    for (row in (-5 * ARROW_RES)..(5 * ARROW_RES)) {
        val merged = ArrayList<FloatArray>()
        for (d in -steps..steps) {
            val dy = d.toFloat() / ARROW_RES
            val dx = kotlin.math.sqrt(maxOf(0f, grow * grow - dy * dy))
            for (sp in spans((row + 0.5f) / ARROW_RES + dy)) merged += floatArrayOf(sp[0] - dx, sp[1] + dx)
        }
        merged.sortBy { it[0] }
        var cur: FloatArray? = null
        fun emit(c: FloatArray) {
            val a = Math.round(c[0] * ARROW_RES); val b = Math.round(c[1] * ARROW_RES)
            if (b > a) { ys += row; x0 += a; x1 += b }
        }
        for (sp in merged) {
            val c = cur
            if (c != null && sp[0] <= c[1]) c[1] = maxOf(c[1], sp[1]) else { if (c != null) emit(c); cur = sp.copyOf() }
        }
        cur?.let { emit(it) }
    }
    // Rows with the same span one under another become one rectangle: fewer fills, the same cells.
    val ry0 = ArrayList<Int>(); val ry1 = ArrayList<Int>(); val rx0 = ArrayList<Int>(); val rx1 = ArrayList<Int>()
    for (i in ys.indices) {
        var r = rx0.indices.firstOrNull { ry1[it] == ys[i] && rx0[it] == x0[i] && rx1[it] == x1[i] }
        if (r == null) { r = rx0.size; ry0 += ys[i]; ry1 += ys[i]; rx0 += x0[i]; rx1 += x1[i] }
        ry1[r] = ys[i] + 1
    }
    return ArrowSpans(ry0.toIntArray(), ry1.toIntArray(), rx0.toIntArray(), rx1.toIntArray())
}

private val arrowOutline by lazy { arrowSpans(0.75f) }
private val arrowFill by lazy { arrowSpans(0f) }

// Vanilla's player marker sprites, as shades of grey (0 = none, 1 = the black edge) tinted by the marker colour.
private val vanillaMarker = arrayOf(
    "....1...", "...1b1..", "...1e1..", "..1efe1.", "..1fff1.", "..1efe1.", "..1beb1.", "...111..",
)
private val legacyMarker = arrayOf(
    "....1...", "...1b1..", "..1beb1.", "..1efe1.", "..1efe1.", "..1beb1.", "...111..", "........",
)
private val lcmMarker = arrayOf(
    "....1...", "...1f1..", "...1f1..", "..1fff1.", "..1fff1.", "..1fff1.", "..11111.", "........",
)

private fun GuiGraphicsExtractor.pixelMarker(rows: Array<String>, color: Int) {
    for (y in rows.indices) for (x in 0 until 8) {
        val shade = when (rows[y][x]) { 'b' -> 0xBC; 'e' -> 0xE0; 'f' -> 0xFF; '1' -> 0; else -> continue }
        val c = if (shade == 0) 0xFF000000.toInt() else {
            fun ch(sh: Int) = ((color shr sh and 0xFF) * shade / 255) shl sh
            (color and 0xFF000000.toInt()) or ch(16) or ch(8) or ch(0)
        }
        fill(x - 4, y - 4, x - 3, y - 3, c)
    }
}

private fun GuiGraphicsExtractor.arrowRects(s: ArrowSpans, c: Int) {
    for (i in s.y0.indices) fill(s.x0[i], s.y0[i], s.x1[i], s.y1[i], c)
}

/** Your own map marker in the chosen style, centred on the pose's origin. */
fun GuiGraphicsExtractor.selfArrow(color: Int) {
    when (DungeonMap.pointerStyle) {
        DungeonMap.PointerStyle.VANILLA -> return pixelMarker(vanillaMarker, color)
        DungeonMap.PointerStyle.LEGACY -> return pixelMarker(legacyMarker, color)
        DungeonMap.PointerStyle.LCM -> {
            pose().pushMatrix()
            pose().scale(1.25f)
            pixelMarker(lcmMarker, DungeonMap.lcmPointerColor.rgba)
            pose().popMatrix()
            return
        }
        DungeonMap.PointerStyle.ADM -> {}
    }
    pose().pushMatrix()
    pose().scale(1f / ARROW_RES)
    arrowRects(arrowOutline, DungeonMap.pointerOutline.rgba)
    arrowRects(arrowFill, color)
    pose().popMatrix()
}

// Ascent's camera projection hooks this by name
private fun playerRenderPosition(entity: Player?, mapPos: IVec2): Pair<Float, Float> =
    if (entity != null) mapX(entity.renderX.toFloat()) to mapX(entity.renderZ.toFloat())
    else (mapPos.x + 128) / 2f - DungeonScan.startX to (mapPos.z + 128) / 2f - DungeonScan.startY

private fun mapX(world: Float): Float = (world + 200f) * GRID / 32f

fun buildExampleRooms(): List<DungeonRoom> {
    val n = MapCheckmark.NONE
    val w = MapCheckmark.WHITE
    val g = MapCheckmark.GREEN
    fun room(type: RoomType, x: Int, z: Int, rot: RoomRotation, mark: MapCheckmark, entered: Boolean, core: Int? = null): DungeonRoom {
        val data = core?.let { RoomData.getRoomData(it) }
        return DungeonRoom(type, IVec2(x, z), data).apply {
            if (data != null) shape = data.shape
            rotation = rot
            checkmark = mark
            walkedInto = entered
            mapSeen = true
        }
    }
    return listOf(
        room(RoomType.ENTRANCE, 2, 0, RoomRotation.NORTH, g, true),
        room(RoomType.NORMAL, 0, 0, RoomRotation.WEST, n, true, 1051405699),
        room(RoomType.PUZZLE, 1, 0, RoomRotation.NORTH, n, true, 379499781),
        room(RoomType.NORMAL, 3, 0, RoomRotation.WEST, w, true, 90256084),
        room(RoomType.NORMAL, 4, 0, RoomRotation.SOUTH, n, true, 1024359556),
        room(RoomType.NORMAL, 1, 1, RoomRotation.SOUTH, w, true, 1250712883),
        room(RoomType.NORMAL, 4, 1, RoomRotation.SOUTH, n, true, 950592972),
        room(RoomType.NORMAL, 1, 3, RoomRotation.NORTH, w, true, -333637832),
        room(RoomType.FAIRY, 2, 3, RoomRotation.SOUTH, g, true),
        room(RoomType.CHAMPION, 4, 3, RoomRotation.SOUTH, g, false, -1334473473),
        room(RoomType.TRAP, 5, 3, RoomRotation.NORTH, g, false, 1590699551),
        room(RoomType.PUZZLE, 0, 4, RoomRotation.WEST, n, true, 799715466),
        room(RoomType.NORMAL, 1, 4, RoomRotation.EAST, w, true, 1484939648),
        room(RoomType.NORMAL, 2, 4, RoomRotation.WEST, w, true, -1764045332),
        room(RoomType.NORMAL, 3, 4, RoomRotation.NORTH, n, false, 76347246),
        room(RoomType.NORMAL, 4, 4, RoomRotation.WEST, n, false, -1899702429),
        room(RoomType.NORMAL, 5, 4, RoomRotation.WEST, w, false, -151940807),
        room(RoomType.NORMAL, 0, 5, RoomRotation.SOUTH, w, true, 259768244),
        room(RoomType.BLOOD, 2, 5, RoomRotation.EAST, n, true),
        room(RoomType.NORMAL, 3, 5, RoomRotation.SOUTH, n, false, 222124420),
    )
}

fun buildExampleDoors(): List<DungeonDoor> {
    val h = DoorRotation.Horizontal
    val v = DoorRotation.Vertical
    fun door(x: Int, z: Int, rot: DoorRotation, color: Color, type: DoorType = DoorType.Normal) = DungeonDoor(IVec2(x, z), rot, type, color)
    val plain = DungeonMap.normalDoorColor
    return listOf(
        door(2, 0, v, DungeonMap.entranceRoomColor),
        door(1, 0, v, DungeonMap.puzzleRoomColor),
        door(0, 1, h, plain),
        door(2, 2, h, plain),
        door(1, 2, v, plain),
        door(4, 0, v, plain),
        door(3, 1, h, plain),
        door(3, 3, v, plain),
        door(1, 3, h, DungeonMap.fairyDoorColor, DoorType.Fairy),
        door(2, 3, v, plain),
        door(4, 3, v, DungeonMap.championRoomColor),
        door(5, 3, v, DungeonMap.trapRoomColor),
        door(0, 4, h, DungeonMap.puzzleRoomColor),
        door(1, 4, h, plain),
        door(1, 4, v, plain),
        door(3, 4, h, plain),
        door(3, 4, v, plain),
        door(5, 4, v, plain),
        door(1, 5, h, DungeonMap.bloodDoorColor, DoorType.Blood),
    )
}

internal fun roomTypeColor(type: RoomType): Color = when (type) {
    RoomType.NORMAL -> DungeonMap.normalRoomColor
    RoomType.PUZZLE -> DungeonMap.puzzleRoomColor
    RoomType.TRAP -> DungeonMap.trapRoomColor
    RoomType.BLOOD -> DungeonMap.bloodRoomColor
    RoomType.ENTRANCE -> DungeonMap.entranceRoomColor
    RoomType.FAIRY -> DungeonMap.fairyRoomColor
    RoomType.CHAMPION -> DungeonMap.championRoomColor
    RoomType.RARE -> DungeonMap.rareRoomColor
    else -> fallbackRoomColor
}

private val fallbackRoomColor = Color(60, 60, 60)
