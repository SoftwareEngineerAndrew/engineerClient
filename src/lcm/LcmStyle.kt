package com.engineerclient.lcm

import com.engineerclient.EngineerClient
import com.engineerclient.mappred.MapPrediction
import com.adm.AdmMod.mc
import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.clickgui.settings.impl.DropdownSetting
import com.adm.clickgui.settings.impl.NumberSetting
import com.adm.features.impl.dungeon.map.DungeonMap
import com.adm.features.impl.dungeon.map.DungeonScan
import com.adm.features.impl.dungeon.map.SpecialColumn
import com.adm.features.impl.dungeon.map.buildExampleDoors
import com.adm.features.impl.dungeon.map.buildExampleRooms
import com.adm.features.impl.dungeon.map.fillRoom
import com.adm.features.impl.dungeon.map.tile.DoorType
import com.adm.features.impl.dungeon.map.tile.DungeonDoor
import com.adm.features.impl.dungeon.map.tile.DungeonRoom
import com.adm.features.impl.dungeon.map.tile.DungeonTile
import com.adm.features.impl.dungeon.map.tile.MapCheckmark
import com.adm.features.impl.dungeon.map.tile.RoomType
import com.adm.utils.Color
import com.adm.utils.itemId
import com.adm.utils.skyblock.dungeon.DungeonClass
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.PlayerSkin
import java.util.IdentityHashMap

/**
 * LCM Style on the Dungeon Map: LCM's look (its colours, doors, room text, Prince crowns and player heads) over
 * the same rooms, guesses and names the map already shows. The icons are under their own licence
 * (assets/engineerclient/dtmap/LICENSE-dtMap.txt).
 */
object LcmStyle {

    private val style = BooleanSetting("LCM Style", false,
        desc = "Draws the map like LCM's map: its doors, room text, Prince crowns and player heads, in the colors below.")

    internal val borderWidth = NumberSetting("Outline Width", 1, 1..5, 1, desc = "How thick the map's outline is in LCM Style.").withDependency { style.value }
    private val princeCrowns = BooleanSetting("Prince Crowns", true, desc = "Crowns on opened rooms that can still have a Prince.").withDependency { style.value }
    internal val ownMarkerColor = ColorSetting("Own Marker Color", Color(0xFFFFFFFF.toInt()), true, desc = "Your own marker on the map, when it isn't your head.")


    private const val ROOM = 16
    private const val TILE = 20
    /** The background around the rooms: Map Padding when Custom Map Padding is on (MapSizes), else 5. */
    private val PAD: Int get() = com.engineerclient.mapicons.MapSizes.padding() ?: 5
    private const val DOOR = 9
    private const val DARKEN = 0.4f

    // the Dungeon Map's colours
    private val BACKGROUND: Int get() = DungeonMap.backgroundColor.rgba
    val UNOPENED: Int get() = DungeonMap.unknownRoomColor.rgba
    val BLOOD: Int get() = DungeonMap.bloodRoomColor.rgba
    val NORMAL: Int get() = DungeonMap.normalRoomColor.rgba
    val PUZZLE: Int get() = DungeonMap.puzzleRoomColor.rgba
    val CHAMPION: Int get() = DungeonMap.championRoomColor.rgba
    val TRAP: Int get() = DungeonMap.trapRoomColor.rgba
    private val ENTRANCE: Int get() = DungeonMap.entranceRoomColor.rgba
    val FAIRY: Int get() = DungeonMap.fairyRoomColor.rgba
    val RARE: Int get() = DungeonMap.rareRoomColor.rgba
    const val WITHER = 0xFF000000.toInt()
    private const val NO_CLASS = 0xB2000000.toInt()

    private const val TEXT_GREEN = 0xFF00FF00.toInt()
    private const val TEXT_WHITE = 0xFFFFFFFF.toInt()
    private const val TEXT_GREY = 0xFF646464.toInt()
    private const val TEXT_RED = 0xFFFF0000.toInt()

    private val greenCheck = Identifier.fromNamespaceAndPath("engineerclient", "dtmap/green_check.png")
    private val whiteCheck = Identifier.fromNamespaceAndPath("engineerclient", "dtmap/white_check.png")
    private val cross = Identifier.fromNamespaceAndPath("engineerclient", "dtmap/cross.png")
    private val crown = Identifier.fromNamespaceAndPath("engineerclient", "dtmap/prince_crown_ziyno.png")
    private val selfMarker = Identifier.fromNamespaceAndPath("engineerclient", "dtmap/self_marker.png")

    /** The rooms that can have a Prince. */
    internal val princeRooms = setOf("Big Red Flag", "Chambers", "Doors", "Flags", "Grass Ruin", "Leaves", "Market",
        "Pirate", "Quartz Knight", "Red Blue", "Skull", "Sloth", "Supertall", "Waterfall", "Withermancer")

    private val leapMenus = setOf("Spirit Leap", "Teleport to Player")

    private val exampleRooms by lazy { buildExampleRooms() }
    private val exampleDoors by lazy { buildExampleDoors() }

    private var failed = false

    fun install() {
        DungeonMap.registerSetting(style)
        // right below LCM Style
        for (s in listOf(borderWidth, princeCrowns, ownMarkerColor)) DungeonMap.registerSetting(s)
    }

    /** True while the map HUD is drawn here instead of by ADM (LcmStyleMixin). */
    @JvmStatic
    fun active(): Boolean = style.value

    /** Draws the whole map HUD; its size, or null to leave this frame to ADM (it couldn't be drawn). */
    @JvmStatic
    fun render(g: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int>? {
        if (!example && (!DungeonUtils.inDungeons || (disableInBoss() && DungeonUtils.inBoss))) return NONE
        val width = mapWidth(example)
        val height = rows(example) * TILE - (TILE - ROOM) + PAD * 2
        g.pose().pushMatrix()
        try {
            g.fill(0, 0, width, height, BACKGROUND)
            border(g, width, height)
            g.pose().translate(PAD.toFloat(), PAD.toFloat())
            if (example) drawMap(g, exampleRooms, exampleDoors, emptyList(), true)
            else {
                drawMap(g, DungeonScan.rooms, DungeonScan.doors.values, DungeonScan.pathHints, false)
                if (!DungeonUtils.inBoss) drawPlayers(g)
            }
        } catch (t: Throwable) {
            // ADM fills its rooms in from map packets as they come: a read mid-change just draws the next frame
            if (t !is ConcurrentModificationException && !failed) {
                failed = true
                EngineerClient.logger.error("[ec] LCM Style failed, ADM draws the map", t)
            }
            return null
        } finally {
            g.pose().popMatrix()
        }
        if (size.first != width || size.second != height) size = width to height
        return size
    }

    private val NONE = 0 to 0
    private var size = NONE

    private val disableBoss by lazy { DungeonMap.settings["Disable in Boss"] as? BooleanSetting }

    internal fun disableInBoss(): Boolean = disableBoss?.value ?: true

    /** How wide the map HUD is drawn, background included. */
    internal fun mapWidth(example: Boolean): Int = cols(example) * TILE - (TILE - ROOM) + PAD * 2

    /** The floor's rooms across and down. */
    internal fun mapSize(example: Boolean): Pair<Int, Int> = cols(example) to rows(example)

    internal fun cols(example: Boolean): Int = if (example) 6 else when (DungeonUtils.floor?.floorNumber) {
        0, 1 -> 4
        2, 3 -> 5
        else -> if (DungeonScan.startX == 5) 6 else 5
    }

    internal fun rows(example: Boolean): Int = if (example) 6 else when (DungeonUtils.floor?.floorNumber) {
        0 -> 4
        1, 2, 3 -> 5
        else -> if (DungeonScan.startY == 5) 6 else 5
    }

    private fun drawMap(g: GuiGraphicsExtractor, rooms: Collection<DungeonRoom>, doors: Collection<DungeonDoor>, hints: Collection<DungeonTile>, example: Boolean) {
        for (room in rooms) {
            if (!room.isViewable) continue
            val color = if (room.checkmark == MapCheckmark.QUESTION_MARK) (if (room.type == RoomType.BLOOD) darker(BLOOD) else UNOPENED) else roomColor(room.type)
            if (room.tiles.isEmpty()) g.fillRoom(room, color) else fillTiles(g, room, color)
            if (princeCrowns.value && opened(room) && room.shouldShowName && room.name in princeRooms && !DungeonUtils.princeKilled) drawCrown(g, room)
        }
        if (!example && !MapPrediction.render(g, lcm = true)) drawUnopened(g, hints)
        for (door in doors) drawDoor(g, door, example)
        for (room in rooms) drawName(g, room)
    }

    private fun roomColor(type: RoomType): Int = when (type) {
        RoomType.BLOOD -> BLOOD
        RoomType.NORMAL -> NORMAL
        RoomType.PUZZLE -> PUZZLE
        RoomType.CHAMPION -> CHAMPION
        RoomType.TRAP -> TRAP
        RoomType.ENTRANCE -> ENTRANCE
        RoomType.FAIRY -> FAIRY
        RoomType.RARE -> RARE
        else -> UNOPENED
    }

    /** The room type one of ADM's room or door colour settings stands for (the special column's guesses, ADM's example doors). */
    private fun typeOf(c: Color): RoomType = when (c.rgba) {
        DungeonMap.puzzleRoomColor.rgba -> RoomType.PUZZLE
        DungeonMap.trapRoomColor.rgba -> RoomType.TRAP
        DungeonMap.championRoomColor.rgba -> RoomType.CHAMPION
        DungeonMap.entranceRoomColor.rgba -> RoomType.ENTRANCE
        DungeonMap.bloodRoomColor.rgba, DungeonMap.bloodDoorColor.rgba -> RoomType.BLOOD
        DungeonMap.fairyRoomColor.rgba, DungeonMap.fairyDoorColor.rgba -> RoomType.FAIRY
        else -> RoomType.NORMAL
    }

    /** The LCM Border outline, inside the map's [width] x [height]; nothing while it's fully see-through. */
    private fun border(g: GuiGraphicsExtractor, width: Int, height: Int) {
        val c = DungeonMap.backgroundOutline.rgba
        if (c ushr 24 == 0) return
        val w = borderWidth.value.coerceIn(1, minOf(width, height) / 2)
        g.fill(0, 0, width, w, c)
        g.fill(0, height - w, width, height, c)
        g.fill(0, w, w, height - w, c)
        g.fill(width - w, w, width, height - w, c)
    }

    /** Each channel times the multiplier, alpha kept. */
    fun darker(c: Int, m: Float = DARKEN): Int =
        (c and 0xFF000000.toInt()) or (((c shr 16 and 255) * m).toInt() shl 16) or (((c shr 8 and 255) * m).toInt() shl 8) or ((c and 255) * m).toInt()

    /** Opened on the map (or walked into), not just seen behind a door. */
    internal fun opened(room: DungeonRoom?): Boolean = room != null && room.isViewable && room.checkmark != MapCheckmark.QUESTION_MARK

    /**
     * The room's name once it's open, not only once someone has walked in (ADM's [DungeonRoom.shouldShowName]):
     * ADM knows it from the room's core as soon as it has scanned it, and only holds it back. Null until then.
     */
    internal fun knownName(room: DungeonRoom): String? = room.name?.takeIf { room.shouldShowName || opened(room) }

    /** Each tile, and the gaps between neighbouring tiles of the room (the middle of a 2x2 too, never an L's missing corner). */
    private fun fillTiles(g: GuiGraphicsExtractor, room: DungeonRoom, color: Int) {
        val tiles = room.tiles
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

    /** A room's text, rebuilt only when what it shows changes. */
    private class Label {
        var option: DungeonMap.RoomTextOption? = null
        var known: String? = null
        var found = Int.MIN_VALUE
        var max = -1
        var words: Array<String>? = null

        var tileCount = -1
        var placed = false
        var cx = 0
        var cz = 0
    }

    private val labels = IdentityHashMap<DungeonRoom, Label>()

    private fun labelOf(room: DungeonRoom): Label {
        labels[room]?.let { return it }
        // rooms of past runs
        if (labels.size >= 64) labels.clear()
        return Label().also { labels[room] = it }
    }

    /** Where the room's name goes: its middle, or the middle of an L's full row. False before ADM has placed it. */
    private fun place(label: Label, room: DungeonRoom): Boolean {
        val tiles = room.tiles
        if (tiles.isEmpty()) {
            val c = room.center
            label.tileCount = 0
            label.placed = c != null
            if (c != null) {
                label.cx = c.x
                label.cz = c.z
            }
            return label.placed
        }
        if (label.tileCount == tiles.size) return label.placed
        label.tileCount = tiles.size
        var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE
        var minZ = Int.MAX_VALUE; var maxZ = Int.MIN_VALUE
        for (t in tiles) {
            if (t.x < minX) minX = t.x
            if (t.x > maxX) maxX = t.x
            if (t.z < minZ) minZ = t.z
            if (t.z > maxZ) maxZ = t.z
        }
        label.placed = true
        if (tiles.size == 3 && maxX - minX == 1 && maxZ - minZ == 1) {
            val row = if (tiles.count { it.z == minZ } == 2) minZ else maxZ
            label.cx = minX * TILE + TILE / 2 + ROOM / 2
            label.cz = row * TILE + ROOM / 2
        } else {
            label.cx = (minX + maxX) * TILE / 2 + ROOM / 2
            label.cz = (minZ + maxZ) * TILE / 2 + ROOM / 2
        }
        return true
    }

    /** The room's text as words, one a line; null for its check mark. */
    private fun words(label: Label, room: DungeonRoom): Array<String>? {
        val option = DungeonMap.roomText
        val known = knownName(room)
        val found = room.foundSecrets ?: Int.MIN_VALUE + 1
        val max = room.data?.maxSecrets ?: 0
        if (option === label.option && known == label.known && found == label.found && max == label.max) return label.words
        label.option = option; label.known = known; label.found = found; label.max = max
        val count = if (found == Int.MIN_VALUE + 1) "?" else found.toString()
        val text = when (option) {
            DungeonMap.RoomTextOption.CHECKMARKS -> null
            DungeonMap.RoomTextOption.ROOM_NAME -> known
            DungeonMap.RoomTextOption.ROOM_SECRETS -> if (known != null && max > 0) "$count/$max" else null
            DungeonMap.RoomTextOption.BOTH -> known?.let { if (max > 0) "$it $count/$max" else it }
        }
        label.words = text?.split(" ")?.toTypedArray()
        return label.words
    }

    /** The crown, small in the bottom right corner of the room's last tile. */
    private fun drawCrown(g: GuiGraphicsExtractor, room: DungeonRoom) {
        val last = room.tiles.maxByOrNull { it.x * 1000 + it.z } ?: return
        g.pose().pushMatrix()
        g.pose().translate(last.x * TILE + 9f, last.z * TILE + 10f)
        g.pose().scale(0.7f)
        g.blit(RenderPipelines.GUI_TEXTURED, crown, 0, 0, 0f, 0f, 10, 10, 10, 10)
        g.pose().popMatrix()
    }

    /**
     * ADM's unknown tiles (its renderPathHints), the same tiles and guesses in this look: the tile a door leads
     * to in a room nobody has opened is dark grey, blood dark red, a known 1x1 the special column's guess; and,
     * like ADM, the special column's guess on every tile of a known 1x1 nobody has opened that isn't behind a door yet.
     */
    private fun drawUnopened(g: GuiGraphicsExtractor, hints: Collection<DungeonTile>) {
        codeInColumn = -1
        codeElsewhere = -1
        for ((position, room) in hints) {
            if (room?.walkedInto == true) continue
            val colors = when {
                room?.type == RoomType.BLOOD -> single(darker(BLOOD))
                !DungeonMap.disablePred && room?.isKnown1x1 == true -> specialGuess(position.x)
                else -> single(UNOPENED)
            }
            guessTile(g, position.x * TILE, position.z * TILE, colors)
        }
        if (DungeonMap.disablePred) return
        for (room in DungeonScan.rooms) {
            if (!room.isKnown1x1 || room.isViewable) continue
            for (position in room.tiles) {
                if (hints.any { it.position == position }) continue
                guessTile(g, position.x * TILE, position.z * TILE, specialGuess(position.x))
            }
        }
    }

    // Scratch arrays for guessTile, which only reads them during the call.
    private val singleColor = IntArray(1)
    private val guessColors = arrayOf(IntArray(0), IntArray(1), IntArray(2), IntArray(3))
    private val guessTypes = arrayOfNulls<RoomType>(3)
    private var codeInColumn = -1
    private var codeElsewhere = -1

    private fun single(color: Int): IntArray {
        singleColor[0] = color
        return singleColor
    }

    /** The special column's guess for a tile in [column], darkened: puzzle outside (or on top), trap, yellow in the middle (or below). */
    private fun specialGuess(column: Int): IntArray {
        val code = if (column == SpecialColumn.column) {
            if (codeInColumn < 0) codeInColumn = SpecialColumn.guessCode(true)
            codeInColumn
        } else {
            if (codeElsewhere < 0) codeElsewhere = SpecialColumn.guessCode(false)
            codeElsewhere
        }
        val n = SpecialColumn.guessCount(code)
        for (i in 0 until n) {
            val type = typeOf(SpecialColumn.kindColor(SpecialColumn.guessKind(code, i)))
            var j = i
            while (j > 0 && guessRank(guessTypes[j - 1]!!) > guessRank(type)) {
                guessTypes[j] = guessTypes[j - 1]
                j--
            }
            guessTypes[j] = type
        }
        val out = guessColors[n]
        for (i in 0 until n) out[i] = darker(roomColor(guessTypes[i]!!))
        return out
    }

    private fun guessRank(type: RoomType): Int = when (type) {
        RoomType.PUZZLE -> 0
        RoomType.TRAP -> 1
        RoomType.CHAMPION -> 2
        else -> 3
    }

    /** A guess tile at (x, z): one colour, two as top and bottom halves, three as an L, its opposite corner and a middle square. */
    fun guessTile(g: GuiGraphicsExtractor, x: Int, z: Int, colors: IntArray) {
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

    private fun tileRoom(index: Int): DungeonRoom? = if (index in 0 until 36) DungeonScan.tiles[index].room else null

    private fun drawDoor(g: GuiGraphicsExtractor, door: DungeonDoor, example: Boolean) {
        val a = if (example) null else tileRoom(door.originTileIndex)
        val b = if (example) null else tileRoom(door.destinationTileIndex)
        if (!example && !opened(a) && !opened(b)) return

        val color = if (example) when (door.type) {
            DoorType.Wither -> WITHER
            DoorType.Blood -> BLOOD
            DoorType.Fairy -> FAIRY
            else -> roomColor(typeOf(door.color))
        } else doorColor(door, a, b)

        doorAt(g, door.position.x, door.position.z, door.rotation.offset.x == 1, color)
    }

    /** A door from tile (x, z) to the tile right of it (or below it): 9 wide across the gap, centred on the tile. */
    fun doorAt(g: GuiGraphicsExtractor, x: Int, z: Int, right: Boolean, color: Int) {
        g.pose().pushMatrix()
        if (right) g.pose().translate(x * TILE + ROOM.toFloat(), z * TILE + (ROOM - DOOR) / 2f)
        else g.pose().translate(x * TILE + (ROOM - DOOR) / 2f, z * TILE + ROOM.toFloat())
        g.fill(0, 0, if (right) TILE - ROOM else DOOR, if (right) DOOR else TILE - ROOM, color)
        g.pose().popMatrix()
    }

    private fun doorColor(door: DungeonDoor, a: DungeonRoom?, b: DungeonRoom?): Int {
        val noInfo = !opened(a) || !opened(b)
        return when (door.type) {
            DoorType.Blood -> if (noInfo) darker(BLOOD) else BLOOD
            DoorType.Wither -> WITHER
            // an unlocked fairy door: black until the room behind it is open
            DoorType.Fairy -> if (noInfo) WITHER else if (special(a, b, fairy = true) == RoomType.FAIRY) FAIRY else NORMAL
            else -> if (noInfo) UNOPENED else when (special(a, b, fairy = false)) {
                RoomType.ENTRANCE -> ENTRANCE
                RoomType.BLOOD -> BLOOD
                RoomType.CHAMPION -> CHAMPION
                RoomType.PUZZLE -> PUZZLE
                RoomType.RARE -> RARE
                RoomType.TRAP -> TRAP
                else -> NORMAL
            }
        }
    }

    /** The type of the first of [a] and [b] that isn't a normal room (nor a fairy room, unless [fairy]). */
    private fun special(a: DungeonRoom?, b: DungeonRoom?, fairy: Boolean): RoomType? {
        if (a != null && a.type != RoomType.NORMAL && (fairy || a.type != RoomType.FAIRY)) return a.type
        if (b != null && b.type != RoomType.NORMAL && (fairy || b.type != RoomType.FAIRY)) return b.type
        return null
    }

    private fun drawName(g: GuiGraphicsExtractor, room: DungeonRoom) {
        if (!opened(room) || room.type == RoomType.ENTRANCE || room.type == RoomType.BLOOD || room.type == RoomType.FAIRY || room.type == RoomType.UNDISCOVERED) return
        val label = labelOf(room)
        if (!place(label, room)) return
        val cx = label.cx
        val cz = label.cz
        // Room Text, as on ADM's map: the check mark until the name is known, or when the room has nothing else to show
        val words = words(label, room)
        if (words == null) {
            if (!com.engineerclient.mapicons.MapIcons.showCheckmarks) return
            val icon = when (room.checkmark) {
                MapCheckmark.GREEN -> greenCheck
                MapCheckmark.WHITE -> whiteCheck
                MapCheckmark.RED -> cross
                else -> return
            }
            if (!com.adm.features.impl.dungeon.map.MapChecks.draw(g, room, TILE, ROOM)) g.blit(RenderPipelines.GUI_TEXTURED, icon, cx - ROOM / 2 + 2, cz - ROOM / 2 + 2, 0f, 0f, 12, 12, 12, 12, -1)
            return
        }

        val color = when (room.checkmark) {
            MapCheckmark.GREEN -> TEXT_GREEN
            MapCheckmark.NONE, MapCheckmark.UNDISCOVERED -> TEXT_GREY
            MapCheckmark.RED -> TEXT_RED
            else -> TEXT_WHITE
        }
        // one word a line, the block centred on the room
        val scale = DungeonMap.textScaling
        val lineH = mc.font.lineHeight * scale
        val top = ROOM / 2 - lineH / 2f - ((words.size - 1) / 2.0 * lineH).toInt()
        for (i in words.indices) {
            g.pose().pushMatrix()
            g.pose().translate(cx.toFloat(), cz - ROOM / 2 + i * lineH + top)
            g.pose().scale(scale)
            g.centeredText(mc.font, words[i], 0, 0, color)
            g.pose().popMatrix()
        }
    }

    private fun drawPlayers(g: GuiGraphicsExtractor) {
        val self = mc.player ?: return
        val screen = mc.gui.screen()
        if (screen !== lastScreen) {
            lastScreen = screen
            leapScreen = screen?.title?.string in leapMenus
        }
        val names = self.mainHandItem.itemId.let { it == "INFINITE_SPIRIT_LEAP" || it == "SPIRIT_LEAP" } || leapScreen
        val selfName = self.name.string
        var drewSelf = false

        for (player in DungeonUtils.dungeonTeammates) {
            if (player.isDead) continue
            if (player.name == selfName) {
                drewSelf = true
                drawHead(g, worldX(self.x), worldX(self.z), self.yRot, self.skin, classColor(player.clazz), player.name, names, own = true)
                continue
            }
            val entity = player.entity
            val x = if (entity != null) worldX(entity.x) else decorationX(player.mapPos.x)
            val z = if (entity != null) worldX(entity.z) else decorationZ(player.mapPos.z)
            drawHead(g, x, z, player.renderYaw, player.playerSkin, classColor(player.clazz), player.name, names)
        }
        if (!drewSelf) drawHead(g, worldX(self.x), worldX(self.z), self.yRot, self.skin, classColor(DungeonUtils.currentDungeonPlayer.clazz), selfName, names, own = true)
    }

    private var lastScreen: Screen? = null
    private var leapScreen = false

    /** A world coordinate on the map. */
    private fun worldX(v: Double): Float = ((v + 201.0) / (32.0 / TILE)).toFloat()

    // A map marker (from the map item, for players too far away to see) on the map.
    private fun decorationX(x: Int): Float = ((x + 128) / 2f - DungeonScan.startX) * TILE / DungeonScan.roomGap.toFloat()
    private fun decorationZ(z: Int): Float = ((z + 128) / 2f - DungeonScan.startY) * TILE / DungeonScan.roomGap.toFloat()

    private fun classColor(clazz: DungeonClass): Int = when (clazz) {
        DungeonClass.ARCHER -> 0xFFFFAA00.toInt()
        DungeonClass.BERSERK -> 0xFFFF5555.toInt()
        DungeonClass.HEALER -> 0xFFFF55FF.toInt()
        DungeonClass.MAGE -> 0xFF55FFFF.toInt()
        DungeonClass.TANK -> 0xFF00AA00.toInt()
        else -> NO_CLASS
    }

    private fun drawHead(g: GuiGraphicsExtractor, x: Float, z: Float, yaw: Float, skin: PlayerSkin?, color: Int, name: String, showName: Boolean, own: Boolean = false) {
        g.pose().pushMatrix()
        g.pose().translate(x - 2f, z - 2f)
        if (showName) {
            g.pose().pushMatrix()
            g.pose().scale(DungeonMap.playerNamesScaling)
            g.centeredText(mc.font, name, 0, 8, DungeonMap.playerNameColor.rgba)
            g.pose().popMatrix()
        }
        g.pose().rotate(Math.toRadians(180.0 + yaw).toFloat())
        // yours: the white marker, no class square
        if (own) g.blit(RenderPipelines.GUI_TEXTURED, selfMarker, 5, 5, 10f, 10f, -10, -10, 10, 10, ownMarkerColor.value.rgba)
        else {
            g.fill(-6, -6, 6, 6, color)
            if (skin != null) PlayerFaceExtractor.extractRenderState(g, skin, -5, -5, 10)
        }
        g.pose().popMatrix()
    }
}
