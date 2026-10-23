// Adapted from Odin (https://github.com/odtheking/OdinFabric), Copyright (c) 2025, odtheking. BSD 3-Clause License: see licenses/Odin-LICENSE.txt.
package com.engineerclient.ads.map

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.ads.dungeon.AdsClass
import com.engineerclient.ads.dungeon.AdsDungeon
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.PlayerSkin

/**
 * Draws the minimap from [DungeonScan], in map units: 20 a tile, a room 16 of them. Two looks:
 *
 * - [Odin]: Odin's Dungeon Map with its default colours: rooms, doors coloured by the room behind them, names or
 *   check marks, the special column's guesses with a "?", heads in class colours, yours as the map's arrow.
 * - [Lcm]: LCM's map (dtMap) as LCM Style draws Odin's map: its colours (LCM Colors with Odin installed), tiles
 *   with the gaps inside a room filled, 9 wide doors, one word a line, dtMap's check marks, Prince crowns and markers.
 */
object MinimapRenderer {
    const val ROOM = 16
    const val TILE = 20

    private fun icon(name: String) = Identifier.fromNamespaceAndPath("engineerclient", name)

    /** a colour with each channel times [m], alpha kept (Odin's and dtMap's darker) */
    fun darker(c: Int, m: Float): Int =
        (c and 0xFF000000.toInt()) or (((c shr 16 and 255) * m).toInt() shl 16) or (((c shr 8 and 255) * m).toInt() shl 8) or ((c and 255) * m).toInt()

    /** Opened on the map (or walked into), not just seen behind a door. */
    fun opened(room: DungeonRoom?): Boolean = room != null && room.isViewable && room.checkmark != MapCheckmark.QUESTION_MARK

    private fun tileRoom(index: Int): DungeonRoom? = if (index in 0 until 36) DungeonScan.tiles[index].room else null

    /** A world position on the map. */
    private fun worldToMap(v: Double): Float = ((v + 201.0) * TILE / 32.0).toFloat()

    /** A marker from the map item (for someone too far away to see) on the map. */
    private fun markerToMap(x: Int, z: Int): Pair<Float, Float> {
        val gap = DungeonScan.roomGap.toFloat()
        return ((x + 128) / 2f - DungeonScan.startX) * TILE / gap to ((z + 128) / 2f - DungeonScan.startY) * TILE / gap
    }

    /** Where each living teammate is on the map, from their entity when it's loaded. */
    private fun teammatePositions() = AdsDungeon.teammatesNoSelf.filter { !it.isDead }.map { t ->
        val e = t.entity
        Triple(t, if (e != null) worldToMap(e.x) else markerToMap(t.mapX, t.mapZ).first, if (e != null) worldToMap(e.z) else markerToMap(t.mapX, t.mapZ).second)
    }

    private val leapItems = setOf("INFINITE_SPIRIT_LEAP", "SPIRIT_LEAP")
    private val leapMenus = setOf("Spirit Leap", "Teleport to Player")

    /** Names over the heads while holding a leap or in its menu (as Odin and dtMap do). */
    private fun showNames(): Boolean {
        val held = mc.player?.mainHandItem ?: return false
        val id = held.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA)?.copyTag()?.getStringOr("id", "")
        return id in leapItems || mc.gui.screen()?.title?.string in leapMenus
    }

    // ------------------------------------------------------------------------------------- Odin's look

    object Odin {
        const val NORMAL = 0xFF6B3A11.toInt()
        const val PUZZLE = 0xFF750085.toInt()
        const val TRAP = 0xFFD87F33.toInt()
        const val BLOOD = 0xFFFF0000.toInt()
        const val ENTRANCE = 0xFF148500.toInt()
        const val FAIRY = 0xFFE000FF.toInt()
        const val CHAMPION = 0xFFFEDF00.toInt()
        const val PENDING = 0xFF37415A.toInt()
        const val UNKNOWN = 0xFF282828.toInt()
        const val WITHER_DOOR = 0xFF000000.toInt()
        private val NORMAL_DOOR = darker(NORMAL, 0.7f)
        private val FAIRY_DOOR = darker(FAIRY, 0.7f)
        private val UNKNOWN_DOOR = darker(UNKNOWN, 0.7f)
        private const val BACKGROUND = 0x1A000000
        private const val OUTLINE = 0xFF000000.toInt()
        private const val NAME_COLOR = 0xFF464646.toInt()

        private val cross = icon("ads/map/cross.png")
        private val green = icon("ads/map/green_check.png")
        private val white = icon("ads/map/white_check.png")
        private val question = icon("ads/map/question.png")
        private val marker = Identifier.withDefaultNamespace("textures/map/decorations/frame.png")

        const val SIZE = 128

        fun roomColor(type: RoomType): Int = when (type) {
            RoomType.NORMAL, RoomType.RARE -> NORMAL
            RoomType.PUZZLE -> PUZZLE
            RoomType.TRAP -> TRAP
            RoomType.BLOOD -> BLOOD
            RoomType.ENTRANCE -> ENTRANCE
            RoomType.FAIRY -> FAIRY
            RoomType.CHAMPION -> CHAMPION
            else -> 0xFF3C3C3C.toInt()
        }

        /** The whole HUD, 128 square like the map item. */
        fun draw(g: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int> {
            g.fill(0, 0, SIZE, SIZE, BACKGROUND)
            g.fill(0, 0, SIZE, 1, OUTLINE); g.fill(0, SIZE - 1, SIZE, SIZE, OUTLINE)
            g.fill(0, 0, 1, SIZE, OUTLINE); g.fill(SIZE - 1, 0, SIZE, SIZE, OUTLINE)
            g.pose().pushMatrix()
            if (example) {
                g.pose().translate(5f, 5f)
                drawMap(g, MinimapExample.rooms, MinimapExample.doors, emptyList(), example = true)
            } else {
                g.pose().translate(DungeonScan.startX.toFloat(), DungeonScan.startY.toFloat())
                g.pose().scale(DungeonScan.roomSize / 16f)
                drawMap(g, DungeonScan.rooms, DungeonScan.doors.values, DungeonScan.pathHints, example = false)
                if (!AdsDungeon.inBoss) drawPlayers(g)
            }
            g.pose().popMatrix()
            return SIZE to SIZE
        }

        private fun drawMap(g: GuiGraphicsExtractor, rooms: Collection<DungeonRoom>, doors: Collection<DungeonDoor>, hints: Collection<DungeonTile>, example: Boolean) {
            for (door in doors) drawDoor(g, door, example)
            for (room in rooms) if (room.isViewable) fillRoom(g, room, if (room.mapSeen || room.type == RoomType.ENTRANCE) roomColor(room.type) else PENDING)
            for (room in rooms) drawRoomText(g, room)
            drawGuesses(g, hints, rooms)
        }

        private fun doorColor(door: DungeonDoor, example: Boolean): Int? {
            val a = tileRoom(door.originTileIndex); val b = tileRoom(door.destinationTileIndex)
            val av = example || a?.isViewable == true; val bv = example || b?.isViewable == true
            if (!av && !bv) return null
            return when (door.type) {
                DoorType.Wither -> WITHER_DOOR
                DoorType.Blood -> if (!av || !bv) darker(BLOOD, 0.5f) else BLOOD
                DoorType.Fairy -> FAIRY_DOOR
                else -> if (!av || !bv) UNKNOWN_DOOR
                    else listOfNotNull(a, b).firstOrNull { it.type != RoomType.NORMAL && it.type != RoomType.FAIRY }?.let { roomColor(it.type) } ?: NORMAL_DOOR
            }
        }

        private fun drawDoor(g: GuiGraphicsExtractor, door: DungeonDoor, example: Boolean) {
            val color = doorColor(door, example) ?: return
            val o = door.rotation.offset
            val half = (ROOM - 8) / 2f
            g.pose().pushMatrix()
            g.pose().translate(door.position.x * TILE + o.x * ROOM + o.z * half, door.position.z * TILE + o.z * ROOM + o.x * half)
            g.fill(0, 0, 4 + 4 * o.z, 4 + 4 * o.x, color)
            g.pose().popMatrix()
        }

        private fun fillRoom(g: GuiGraphicsExtractor, room: DungeonRoom, color: Int) {
            val ox = room.topLeft.x * TILE
            val oy = room.topLeft.z * TILE
            val span = TILE + ROOM
            when (val shape = room.shape) {
                RoomShape.OneByOne -> g.fill(ox, oy, ox + ROOM, oy + ROOM, color)
                RoomShape.TwoByTwo -> g.fill(ox, oy, ox + span, oy + span, color)
                RoomShape.L -> when (room.rotation) {
                    RoomRotation.WEST -> {
                        g.fill(ox, oy, ox + span, oy + ROOM, color)
                        g.fill(ox, oy + ROOM, ox + ROOM, oy + span, color)
                    }
                    RoomRotation.NORTH -> {
                        g.fill(ox, oy, ox + span, oy + ROOM, color)
                        g.fill(ox + TILE, oy + ROOM, ox + span, oy + span, color)
                    }
                    else -> {
                        g.fill(ox, oy, ox + ROOM, oy + span, color)
                        g.fill(ox + ROOM, oy + TILE, ox + span, oy + span, color)
                    }
                }
                else -> {
                    val long = (shape.tileAmount - 1) * TILE + ROOM
                    if (room.rotation == RoomRotation.SOUTH) g.fill(ox, oy, ox + long, oy + ROOM, color)
                    else g.fill(ox, oy, ox + ROOM, oy + long, color)
                }
            }
        }

        private fun drawIcon(g: GuiGraphicsExtractor, x: Int, z: Int, id: Identifier) {
            val size = ROOM - DungeonScan.ROOM_SPACING
            g.blit(RenderPipelines.GUI_TEXTURED, id, x + 2, z + 2, 0f, 0f, size, size, size, size)
        }

        private fun drawRoomText(g: GuiGraphicsExtractor, room: DungeonRoom) {
            if (room.type == RoomType.UNDISCOVERED || room.type == RoomType.FAIRY || room.type == RoomType.ENTRANCE || room.type == RoomType.BLOOD) return
            val text = AdsMinimap.roomText
            val maxSecrets = room.data?.maxSecrets ?: 0
            if (!room.walkedInto || (text == AdsMinimap.RoomText.SECRETS && maxSecrets == 0)) {
                val id = when (room.checkmark) {
                    MapCheckmark.GREEN -> green
                    MapCheckmark.WHITE -> white
                    MapCheckmark.RED -> cross
                    else -> return
                }
                drawIcon(g, room.topLeft.x * TILE, room.topLeft.z * TILE, id)
                return
            }
            val center = room.center ?: return
            val color = when (room.checkmark) {
                MapCheckmark.GREEN -> 0xFF55FF55.toInt()
                MapCheckmark.WHITE -> 0xFFFFFFFF.toInt()
                MapCheckmark.RED -> 0xFFFF5555.toInt()
                else -> 0xFF646464.toInt()
            }
            val secrets = if (maxSecrets > 0) " ${room.foundSecrets ?: "?"}/$maxSecrets" else ""
            val words = when (text) {
                AdsMinimap.RoomText.NAME_AND_SECRETS -> "${room.name ?: return}$secrets"
                AdsMinimap.RoomText.NAME -> room.name ?: return
                AdsMinimap.RoomText.SECRETS -> secrets
            }.trim().split(" ")
            val scale = AdsMinimap.textScale
            val lineH = mc.font.lineHeight
            val total = (words.size - 1) * lineH * scale
            for ((i, word) in words.withIndex()) {
                g.pose().pushMatrix()
                g.pose().translate(center.x.toFloat(), center.z - total / 2f + i * lineH * scale)
                g.pose().scale(scale)
                g.centeredText(mc.font, word, 0, -lineH / 2, color)
                g.pose().popMatrix()
            }
        }

        private fun drawGuesses(g: GuiGraphicsExtractor, hints: Collection<DungeonTile>, rooms: Collection<DungeonRoom>) {
            for ((position, room) in hints) {
                if (room?.walkedInto == true) continue
                val colors = when {
                    room?.type == RoomType.BLOOD -> listOf(darker(BLOOD, 0.5f))
                    AdsMinimap.guesses && room?.isKnown1x1 == true -> SpecialColumn.guessFor(position.x).map { darker(roomColor(it), 0.5f) }
                    else -> listOf(UNKNOWN)
                }
                guess(g, position, colors)
            }
            if (!AdsMinimap.guesses) return
            for (room in rooms) {
                if (!room.isKnown1x1 || room.isViewable) continue
                for (position in room.tiles) {
                    if (hints.any { it.position == position }) continue
                    guess(g, position, SpecialColumn.guessFor(position.x).map { darker(roomColor(it), 0.5f) })
                }
            }
        }

        private fun guess(g: GuiGraphicsExtractor, position: GridPos, colors: List<Int>) {
            val x = position.x * TILE; val y = position.z * TILE
            val part = ROOM / colors.size.coerceAtLeast(1)
            for ((i, c) in colors.withIndex()) g.fill(x + i * part, y, if (i == colors.lastIndex) x + ROOM else x + (i + 1) * part, y + ROOM, c)
            drawIcon(g, x, y, question)
        }

        private fun drawPlayers(g: GuiGraphicsExtractor) {
            val names = showNames()
            for ((t, x, z) in teammatePositions()) {
                g.pose().pushMatrix()
                g.pose().translate(x, z)
                if (names) {
                    g.pose().pushMatrix()
                    g.pose().scale(0.75f)
                    g.centeredText(mc.font, t.name, 0, 8, NAME_COLOR)
                    g.pose().popMatrix()
                }
                g.pose().rotate(Math.toRadians(180.0 + t.renderYaw).toFloat())
                g.fill(-5, -5, 5, 5, t.clazz.color)
                t.skin?.let { PlayerFaceExtractor.extractRenderState(g, it, -4, -4, 8) }
                g.pose().popMatrix()
            }
            val self = mc.player ?: return
            g.pose().pushMatrix()
            g.pose().translate(worldToMap(self.x), worldToMap(self.z))
            g.pose().rotate(Math.toRadians(180.0 + self.yRot).toFloat())
            g.pose().translate(-0.5f, -0.5f)
            g.blit(RenderPipelines.GUI_TEXTURED, marker, -2, -4, 2f, 0f, 5, 7, 8, 8)
            g.pose().popMatrix()
        }
    }

    // ------------------------------------------------------------------------------------- LCM's look

    object Lcm {
        private const val PAD = 5
        private const val DOOR = 9
        private const val DARKEN = 0.4f
        private const val BACKGROUND = 0x46000000
        private const val WITHER = 0xFF000000.toInt()

        private val greenCheck = icon("dtmap/green_check.png")
        private val whiteCheck = icon("dtmap/white_check.png")
        private val cross = icon("dtmap/cross.png")
        private val crown = icon("dtmap/prince_crown_ziyno.png")
        private val selfMarker = icon("dtmap/self_marker.png")

        /** dtMap's room colours, or LCM Colors (on Odin's Dungeon Map) with Odin installed. */
        private fun color(type: RoomType): Int {
            if (EngineerClient.odin) OdinMinimap.lcmColor(type)?.let { return it }
            return when (type) {
                RoomType.BLOOD -> 0xFFFF0000.toInt()
                RoomType.NORMAL -> 0xFF6B3A11.toInt()
                RoomType.PUZZLE -> 0xFF750085.toInt()
                RoomType.CHAMPION -> 0xFFFEDF00.toInt()
                RoomType.TRAP -> 0xFFD87F33.toInt()
                RoomType.ENTRANCE -> 0xFF148500.toInt()
                RoomType.FAIRY -> 0xFFF4138B.toInt()
                RoomType.RARE -> 0xFFFFCB59.toInt()
                else -> 0xFF1E1E1E.toInt()
            }
        }

        private val unopened: Int get() = color(RoomType.UNDISCOVERED)

        /** The floor's rooms across and down, as dtMap sizes its map. */
        fun gridSize(example: Boolean): Pair<Int, Int> {
            if (example) return 6 to 6
            return when (AdsDungeon.floorNumber) {
                0 -> 4 to 4
                1 -> 4 to 5
                2, 3 -> 5 to 5
                else -> (if (DungeonScan.startX == 5) 6 else 5) to (if (DungeonScan.startY == 5) 6 else 5)
            }
        }

        fun draw(g: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int> {
            val (cols, rows) = gridSize(example)
            val width = cols * TILE - (TILE - ROOM) + PAD * 2
            val height = rows * TILE - (TILE - ROOM) + PAD * 2
            g.fill(0, 0, width, height, BACKGROUND)
            g.pose().pushMatrix()
            g.pose().translate(PAD.toFloat(), PAD.toFloat())
            if (example) drawMap(g, MinimapExample.rooms, MinimapExample.doors, emptyList(), true)
            else {
                drawMap(g, DungeonScan.rooms, DungeonScan.doors.values, DungeonScan.pathHints, false)
                if (!AdsDungeon.inBoss) drawPlayers(g)
            }
            g.pose().popMatrix()
            return width to height
        }

        private fun drawMap(g: GuiGraphicsExtractor, rooms: Collection<DungeonRoom>, doors: Collection<DungeonDoor>, hints: Collection<DungeonTile>, example: Boolean) {
            val princeKilled = EngineerClient.odin && OdinMinimap.princeKilled()
            for (room in rooms) {
                if (!room.isViewable) continue
                val c = if (room.checkmark == MapCheckmark.QUESTION_MARK) (if (room.type == RoomType.BLOOD) darker(color(RoomType.BLOOD), DARKEN) else unopened) else color(room.type)
                fillTiles(g, room, c)
                if (opened(room) && room.walkedInto && room.name in princeRooms && !princeKilled) drawCrown(g, room)
            }
            if (!example) drawUnopened(g, hints)
            for (door in doors) drawDoor(g, door, example)
            for (room in rooms) drawName(g, room)
        }

        private fun fillTiles(g: GuiGraphicsExtractor, room: DungeonRoom, color: Int) {
            val tiles = room.tiles.ifEmpty { listOf(room.topLeft) }
            for (t in tiles) {
                val x = t.x * TILE; val z = t.z * TILE
                g.fill(x, z, x + ROOM, z + ROOM, color)
                val right = tiles.any { it.x == t.x + 1 && it.z == t.z }
                val down = tiles.any { it.x == t.x && it.z == t.z + 1 }
                if (right) g.fill(x + ROOM, z, x + TILE, z + ROOM, color)
                if (down) g.fill(x, z + ROOM, x + ROOM, z + TILE, color)
                if (right && down && tiles.any { it.x == t.x + 1 && it.z == t.z + 1 }) g.fill(x + ROOM, z + ROOM, x + TILE, z + TILE, color)
            }
        }

        /** Where the room's name goes: its middle, or the middle of an L's full row. */
        private fun center(room: DungeonRoom): Pair<Int, Int>? {
            val tiles = room.tiles
            if (tiles.isEmpty()) return room.center?.let { it.x to it.z }
            val minX = tiles.minOf { it.x }; val maxX = tiles.maxOf { it.x }
            val minZ = tiles.minOf { it.z }; val maxZ = tiles.maxOf { it.z }
            if (tiles.size == 3 && maxX - minX == 1 && maxZ - minZ == 1) {
                val row = if (tiles.count { it.z == minZ } == 2) minZ else maxZ
                return minX * TILE + TILE / 2 + ROOM / 2 to row * TILE + ROOM / 2
            }
            return (minX + maxX) * TILE / 2 + ROOM / 2 to (minZ + maxZ) * TILE / 2 + ROOM / 2
        }

        private fun drawCrown(g: GuiGraphicsExtractor, room: DungeonRoom) {
            val last = room.tiles.maxByOrNull { it.x * 1000 + it.z } ?: return
            g.pose().pushMatrix()
            g.pose().translate(last.x * TILE + 9f, last.z * TILE + 10f)
            g.pose().scale(0.7f)
            g.blit(RenderPipelines.GUI_TEXTURED, crown, 0, 0, 0f, 0f, 10, 10, 10, 10)
            g.pose().popMatrix()
        }

        private fun drawUnopened(g: GuiGraphicsExtractor, hints: Collection<DungeonTile>) {
            for ((position, room) in hints) {
                if (room?.walkedInto == true) continue
                val colors = when {
                    room?.type == RoomType.BLOOD -> listOf(darker(color(RoomType.BLOOD), DARKEN))
                    AdsMinimap.guesses && room?.isKnown1x1 == true -> specialGuess(position.x)
                    else -> listOf(unopened)
                }
                guessTile(g, position.x * TILE, position.z * TILE, colors)
            }
            if (!AdsMinimap.guesses) return
            for (room in DungeonScan.rooms) {
                if (!room.isKnown1x1 || room.isViewable) continue
                for (position in room.tiles) {
                    if (hints.any { it.position == position }) continue
                    guessTile(g, position.x * TILE, position.z * TILE, specialGuess(position.x))
                }
            }
        }

        private val guessOrder = listOf(RoomType.PUZZLE, RoomType.TRAP, RoomType.CHAMPION)

        private fun specialGuess(column: Int): List<Int> =
            SpecialColumn.guessFor(column).sortedBy { guessOrder.indexOf(it) }.map { darker(color(it), DARKEN) }

        /** dtMap's guess tile: one colour, two as top and bottom halves, three as an L, its opposite corner and a middle square. */
        private fun guessTile(g: GuiGraphicsExtractor, x: Int, z: Int, colors: List<Int>) {
            when (colors.size) {
                0 -> {}
                1 -> g.fill(x, z, x + ROOM, z + ROOM, colors[0])
                2 -> {
                    g.fill(x, z, x + ROOM, z + 8, colors[0])
                    g.fill(x, z + 8, x + ROOM, z + ROOM, colors[1])
                }
                else -> {
                    g.fill(x, z, x + ROOM, z + 5, colors[0])
                    g.fill(x, z, x + 5, z + 10, colors[0])
                    g.fill(x + 10, z + 5, x + ROOM, z + ROOM, colors[1])
                    g.fill(x, z + 10, x + ROOM, z + ROOM, colors[1])
                    g.fill(x + 5, z + 5, x + 11, z + 11, colors[2])
                }
            }
        }

        private fun drawDoor(g: GuiGraphicsExtractor, door: DungeonDoor, example: Boolean) {
            val a = if (example) null else tileRoom(door.originTileIndex)
            val b = if (example) null else tileRoom(door.destinationTileIndex)
            if (!example && !opened(a) && !opened(b)) return
            val noInfo = !example && (!opened(a) || !opened(b))
            val behind = listOfNotNull(a, b)
            val c = when (door.type) {
                DoorType.Blood -> if (noInfo) darker(color(RoomType.BLOOD), DARKEN) else color(RoomType.BLOOD)
                DoorType.Wither -> WITHER
                DoorType.Fairy -> if (noInfo) WITHER else if (example || behind.firstOrNull { it.type != RoomType.NORMAL }?.type == RoomType.FAIRY) color(RoomType.FAIRY) else color(RoomType.NORMAL)
                else -> if (noInfo) unopened else color(behind.firstOrNull { it.type != RoomType.NORMAL && it.type != RoomType.FAIRY }?.type ?: RoomType.NORMAL)
            }
            val right = door.rotation == DoorRotation.Horizontal
            val x = door.position.x; val z = door.position.z
            g.pose().pushMatrix()
            if (right) g.pose().translate(x * TILE + ROOM.toFloat(), z * TILE + (ROOM - DOOR) / 2f)
            else g.pose().translate(x * TILE + (ROOM - DOOR) / 2f, z * TILE + ROOM.toFloat())
            g.fill(0, 0, if (right) TILE - ROOM else DOOR, if (right) DOOR else TILE - ROOM, c)
            g.pose().popMatrix()
        }

        private fun drawName(g: GuiGraphicsExtractor, room: DungeonRoom) {
            if (!opened(room) || room.type == RoomType.ENTRANCE || room.type == RoomType.BLOOD || room.type == RoomType.FAIRY || room.type == RoomType.UNDISCOVERED) return
            val (cx, cz) = center(room) ?: return
            val name = room.name?.takeIf { room.walkedInto }
            if (name == null) {
                val id = when (room.checkmark) {
                    MapCheckmark.GREEN -> greenCheck
                    MapCheckmark.WHITE -> whiteCheck
                    MapCheckmark.RED -> cross
                    else -> return
                }
                g.blit(RenderPipelines.GUI_TEXTURED, id, cx - ROOM / 2 + 2, cz - ROOM / 2 + 2, 0f, 0f, 12, 12, 12, 12, -1)
                return
            }
            val c = when (room.checkmark) {
                MapCheckmark.GREEN -> 0xFF00FF00.toInt()
                MapCheckmark.NONE, MapCheckmark.UNDISCOVERED -> 0xFF646464.toInt()
                MapCheckmark.RED -> 0xFFFF0000.toInt()
                else -> 0xFFFFFFFF.toInt()
            }
            val scale = AdsMinimap.textScale
            val lineH = mc.font.lineHeight * scale
            val words = name.split(" ")
            val top = ROOM / 2 - lineH / 2f - ((words.size - 1) / 2.0 * lineH).toInt()
            for ((i, word) in words.withIndex()) {
                g.pose().pushMatrix()
                g.pose().translate(cx.toFloat(), cz - ROOM / 2 + i * lineH + top)
                g.pose().scale(scale)
                g.centeredText(mc.font, word, 0, 0, c)
                g.pose().popMatrix()
            }
        }

        private fun drawPlayers(g: GuiGraphicsExtractor) {
            val names = showNames()
            for ((t, x, z) in teammatePositions()) drawHead(g, x, z, t.renderYaw, t.skin, t.clazz, t.name, names)
            val self = mc.player ?: return
            g.pose().pushMatrix()
            g.pose().translate(worldToMap(self.x) - 2f, worldToMap(self.z) - 2f)
            g.pose().rotate(Math.toRadians(180.0 + self.yRot).toFloat())
            g.blit(RenderPipelines.GUI_TEXTURED, selfMarker, 5, 5, 10f, 10f, -10, -10, 10, 10)
            g.pose().popMatrix()
        }

        private fun drawHead(g: GuiGraphicsExtractor, x: Float, z: Float, yaw: Float, skin: PlayerSkin?, clazz: AdsClass, name: String, showName: Boolean) {
            g.pose().pushMatrix()
            g.pose().translate(x - 2f, z - 2f)
            if (showName) {
                g.pose().pushMatrix()
                g.pose().scale(0.75f)
                g.centeredText(mc.font, name, 0, 8, 0xFF464646.toInt())
                g.pose().popMatrix()
            }
            g.pose().rotate(Math.toRadians(180.0 + yaw).toFloat())
            g.fill(-6, -6, 6, 6, if (clazz == AdsClass.EMPTY) 0xB2000000.toInt() else clazz.color)
            if (skin != null) PlayerFaceExtractor.extractRenderState(g, skin, -5, -5, 10)
            g.pose().popMatrix()
        }

        /** The rooms dtMap's room data marks as having a Prince. */
        private val princeRooms = setOf("Big Red Flag", "Chambers", "Doors", "Flags", "Grass Ruin", "Leaves", "Market",
            "Pirate", "Quartz Knight", "Red Blue", "Skull", "Sloth", "Supertall", "Waterfall", "Withermancer")
    }
}
