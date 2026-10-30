package com.engineerclient.lcm

import com.engineerclient.EngineerClient
import com.engineerclient.mappred.OdinMapPrediction
import com.odtheking.odin.OdinMod.mc
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.DropdownSetting
import com.odtheking.odin.features.impl.dungeon.map.DungeonMap
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.features.impl.dungeon.map.SpecialColumn
import com.odtheking.odin.features.impl.dungeon.map.buildExampleDoors
import com.odtheking.odin.features.impl.dungeon.map.buildExampleRooms
import com.odtheking.odin.features.impl.dungeon.map.fillRoom
import com.odtheking.odin.features.impl.dungeon.map.tile.DoorType
import com.odtheking.odin.features.impl.dungeon.map.tile.DungeonDoor
import com.odtheking.odin.features.impl.dungeon.map.tile.DungeonRoom
import com.odtheking.odin.features.impl.dungeon.map.tile.DungeonTile
import com.odtheking.odin.features.impl.dungeon.map.tile.MapCheckmark
import com.odtheking.odin.features.impl.dungeon.map.tile.RoomType
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.PlayerSkin

/**
 * LCM Style on Odin's Dungeon Map: the map drawn the way LCM's map, dtMap (github.com/ricedotwho/dtMap), draws
 * its own, in its Legit Mode with its default look, from what Odin's map already knows.
 *
 * - Background: dtMap's translucent black, no outline, sized to the floor's rooms plus 5 on each side.
 * - Rooms: dtMap's colours (fairy pink, rare gold) unless changed under LCM Colors, drawn tile by tile with
 *   the gaps inside a room filled.
 *   A room nobody has opened shows only where Odin shows it: dark grey, dark red for blood, or the special
 *   column's guess (one to three darkened colours in dtMap's split patterns), with no "?".
 * - Doors: 9 wide, coloured by the special room behind them; a door to a room nobody has opened is dark grey.
 * - Names: an opened room's name once someone has been in it, as Odin shows it (not entrance, blood or fairy),
 *   green when it's green-checked, white when cleared, grey before that, red when failed; dtMap's check marks
 *   before that; dtMap's crown on a Prince room (once its name shows) until a Prince is killed.
 * - Players: everyone else's head 10 wide on a square in their class colour, yours as dtMap's white marker
 *   (its MC Map Pointer); names while leaping.
 *
 * Only the look changes: every tile, guess and name is the one Odin's own map (or Room Prediction) would show.
 * Odin's Text Scaling, Player Name settings and Disable Prediction still apply. Room Prediction (ADM) still
 * draws in place of the unopened tiles, in this look: its guesses as [guessTile]s in these colours, its chances
 * where Odin would put a "?". dtMap's icons are under its BSD licence (assets/engineerclient/dtmap/LICENSE-dtMap.txt).
 * `LcmStyleMixin` hands Odin's map HUD to [render].
 */
object LcmStyle {

    private val style = BooleanSetting("LCM Style", false,
        desc = "Draws the map like LCM's map (dtMap) in its Legit Mode: its colours (changed under LCM Colors), background, doors, room names, check marks, Prince crowns and player heads (yours as its white marker). Room Prediction draws its guesses the same way, with its chances instead of \"?\". Added by engineerClient.")

    private val colors = DropdownSetting("LCM Colors",
        desc = "The colour LCM Style draws each type of room in (a door takes the colour of the room behind it). dtMap's colours to begin with.").withDependency { style.value }
    private val unopenedColor = colorSetting("Unopened", 0xFF1E1E1E.toInt(), "rooms nobody has opened yet")
    private val normalColor = colorSetting("Normal", 0xFF6B3A11.toInt(), "normal rooms")
    private val puzzleColor = colorSetting("Puzzle", 0xFF750085.toInt(), "puzzle rooms")
    private val trapColor = colorSetting("Trap", 0xFFD87F33.toInt(), "the trap room")
    private val yellowColor = colorSetting("Yellow", 0xFFFEDF00.toInt(), "the yellow (miniboss) room")
    private val bloodColor = colorSetting("Blood", 0xFFFF0000.toInt(), "the blood room")
    private val entranceColor = colorSetting("Entrance", 0xFF148500.toInt(), "the entrance")
    private val fairyColor = colorSetting("Fairy", 0xFFF4138B.toInt(), "the fairy room")
    private val rareColor = colorSetting("Rare", 0xFFFFCB59.toInt(), "rare rooms")

    private fun colorSetting(type: String, default: Int, what: String) =
        ColorSetting("LCM $type Room", Color(default), true, desc = "LCM Style's colour for $what.").withDependency { style.value && colors.value }

    private const val ROOM = 16
    private const val TILE = 20
    private const val PAD = 5
    private const val DOOR = 9
    private const val DARKEN = 0.4f

    // the room colours (LCM Colors, dtMap's by default) and dtMap's other default colours (C1Map)
    private const val BACKGROUND = 0x46000000
    val UNOPENED: Int get() = unopenedColor.value.rgba
    val BLOOD: Int get() = bloodColor.value.rgba
    val NORMAL: Int get() = normalColor.value.rgba
    val PUZZLE: Int get() = puzzleColor.value.rgba
    val CHAMPION: Int get() = yellowColor.value.rgba
    val TRAP: Int get() = trapColor.value.rgba
    private val ENTRANCE: Int get() = entranceColor.value.rgba
    val FAIRY: Int get() = fairyColor.value.rgba
    val RARE: Int get() = rareColor.value.rgba
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

    /** The rooms dtMap's room data marks as having a Prince. */
    internal val princeRooms = setOf("Big Red Flag", "Chambers", "Doors", "Flags", "Grass Ruin", "Leaves", "Market",
        "Pirate", "Quartz Knight", "Red Blue", "Skull", "Sloth", "Supertall", "Waterfall", "Withermancer")

    private val leapMenus = setOf("Spirit Leap", "Teleport to Player")

    private val exampleRooms by lazy { buildExampleRooms() }
    private val exampleDoors by lazy { buildExampleDoors() }

    private var failed = false

    fun install() {
        DungeonMap.registerSetting(style)
        // right below LCM Style
        for (s in listOf(colors, unopenedColor, normalColor, puzzleColor, trapColor, yellowColor, bloodColor, entranceColor, fairyColor, rareColor)) DungeonMap.registerSetting(s)
    }

    /** True while the map HUD is drawn here instead of by Odin (LcmStyleMixin). */
    @JvmStatic
    fun active(): Boolean = style.value

    /** Draws the whole map HUD; its size, or null to leave this frame to Odin (it couldn't be drawn). */
    @JvmStatic
    fun render(g: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int>? {
        if (!example && (!DungeonUtils.inDungeons || (disableInBoss() && DungeonUtils.inBoss))) return 0 to 0
        val (cols, rows) = mapSize(example)
        val width = cols * TILE - (TILE - ROOM) + PAD * 2
        val height = rows * TILE - (TILE - ROOM) + PAD * 2
        g.pose().pushMatrix()
        try {
            g.fill(0, 0, width, height, BACKGROUND)
            g.pose().translate(PAD.toFloat(), PAD.toFloat())
            if (example) drawMap(g, exampleRooms, exampleDoors, emptyList(), true)
            else {
                drawMap(g, DungeonScan.rooms, DungeonScan.doors.values, DungeonScan.pathHints, false)
                if (!DungeonUtils.inBoss) drawPlayers(g)
            }
        } catch (t: Throwable) {
            // Odin fills its rooms in from map packets as they come: a read mid-change just draws the next frame
            if (t !is ConcurrentModificationException && !failed) {
                failed = true
                EngineerClient.logger.error("[ec] dtMap style failed, Odin draws the map", t)
            }
            return null
        } finally {
            g.pose().popMatrix()
        }
        return width to height
    }

    private fun disableInBoss(): Boolean = (DungeonMap.settings["Disable in Boss"] as? BooleanSetting)?.value ?: true

    /** The floor's rooms across and down, as dtMap sizes its map. */
    internal fun mapSize(example: Boolean): Pair<Int, Int> {
        if (example) return 6 to 6
        return when (DungeonUtils.floor?.floorNumber) {
            0 -> 4 to 4
            1 -> 4 to 5
            2, 3 -> 5 to 5
            else -> (if (DungeonScan.startX == 5) 6 else 5) to (if (DungeonScan.startY == 5) 6 else 5)
        }
    }

    private fun drawMap(g: GuiGraphicsExtractor, rooms: Collection<DungeonRoom>, doors: Collection<DungeonDoor>, hints: Collection<DungeonTile>, example: Boolean) {
        for (room in rooms) {
            if (!room.isViewable) continue
            val color = if (room.checkmark == MapCheckmark.QUESTION_MARK) (if (room.type == RoomType.BLOOD) darker(BLOOD) else UNOPENED) else roomColor(room.type)
            if (room.tiles.isEmpty()) g.fillRoom(room, color) else fillTiles(g, room, color)
            if (opened(room) && room.shouldShowName && room.name in princeRooms && !DungeonUtils.princeKilled) drawCrown(g, room)
        }
        if (!example && !OdinMapPrediction.render(g, lcm = true)) drawUnopened(g, hints)
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

    /** The room type one of Odin's room or door colour settings stands for (the special column's guesses, Odin's example doors). */
    private fun typeOf(c: Color): RoomType = when (c.rgba) {
        DungeonMap.puzzleRoomColor.rgba -> RoomType.PUZZLE
        DungeonMap.trapRoomColor.rgba -> RoomType.TRAP
        DungeonMap.championRoomColor.rgba -> RoomType.CHAMPION
        DungeonMap.entranceRoomColor.rgba -> RoomType.ENTRANCE
        DungeonMap.bloodRoomColor.rgba, DungeonMap.bloodDoorColor.rgba -> RoomType.BLOOD
        DungeonMap.fairyRoomColor.rgba, DungeonMap.fairyDoorColor.rgba -> RoomType.FAIRY
        else -> RoomType.NORMAL
    }

    /** dtMap's darkening: each channel times the multiplier, alpha kept. */
    fun darker(c: Int, m: Float = DARKEN): Int =
        (c and 0xFF000000.toInt()) or (((c shr 16 and 255) * m).toInt() shl 16) or (((c shr 8 and 255) * m).toInt() shl 8) or ((c and 255) * m).toInt()

    /** Opened on the map (or walked into), not just seen behind a door. */
    internal fun opened(room: DungeonRoom?): Boolean = room != null && room.isViewable && room.checkmark != MapCheckmark.QUESTION_MARK

    /**
     * The room's name once it's open (as dtMap has it), not only once someone has walked in (Odin's [DungeonRoom.shouldShowName]):
     * Odin knows it from the room's core as soon as it has scanned it, and only holds it back. Null until then.
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

    /** Where the room's name goes: its middle, or the middle of an L's full row. Null before Odin has placed it. */
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

    /** dtMap's crown, small in the bottom right corner of the room's last tile. */
    private fun drawCrown(g: GuiGraphicsExtractor, room: DungeonRoom) {
        val last = room.tiles.maxByOrNull { it.x * 1000 + it.z } ?: return
        g.pose().pushMatrix()
        g.pose().translate(last.x * TILE + 9f, last.z * TILE + 10f)
        g.pose().scale(0.7f)
        g.blit(RenderPipelines.GUI_TEXTURED, crown, 0, 0, 0f, 0f, 10, 10, 10, 10)
        g.pose().popMatrix()
    }

    /**
     * Odin's unknown tiles (its renderPathHints), the same tiles and guesses in this look: the tile a door leads
     * to in a room nobody has opened is dark grey, blood dark red, a known 1x1 the special column's guess; and,
     * like Odin, the special column's guess on every tile of a known 1x1 nobody has opened that isn't behind a door yet.
     */
    private fun drawUnopened(g: GuiGraphicsExtractor, hints: Collection<DungeonTile>) {
        for ((position, room) in hints) {
            if (room?.walkedInto == true) continue
            val colors = when {
                room?.type == RoomType.BLOOD -> intArrayOf(darker(BLOOD))
                !DungeonMap.disablePred && room?.isKnown1x1 == true -> specialGuess(position.x)
                else -> intArrayOf(UNOPENED)
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

    /** Odin's special column guess for a tile in this column, in these colours and dtMap's order, darkened. */
    private fun specialGuess(column: Int): IntArray = SpecialColumn.colorGuessForUnknown(column).map { typeOf(it) }
        .sortedBy { guessOrder.indexOf(it).let { i -> if (i < 0) guessOrder.size else i } }.map { darker(roomColor(it)) }.toIntArray()

    /** The order guesses go in a [guessTile], as dtMap has them: puzzle outside (or on top), trap, yellow in the middle (or below). */
    private val guessOrder = listOf(RoomType.PUZZLE, RoomType.TRAP, RoomType.CHAMPION)

    /** dtMap's guess tile at (x, z): one colour, two as top and bottom halves, three as an L, its opposite corner and a middle square. */
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

    /** dtMap's door from tile (x, z) to the tile right of it (or below it): 9 wide across the gap, centred on the tile. */
    fun doorAt(g: GuiGraphicsExtractor, x: Int, z: Int, right: Boolean, color: Int) {
        g.pose().pushMatrix()
        if (right) g.pose().translate(x * TILE + ROOM.toFloat(), z * TILE + (ROOM - DOOR) / 2f)
        else g.pose().translate(x * TILE + (ROOM - DOOR) / 2f, z * TILE + ROOM.toFloat())
        g.fill(0, 0, if (right) TILE - ROOM else DOOR, if (right) DOOR else TILE - ROOM, color)
        g.pose().popMatrix()
    }

    private fun doorColor(door: DungeonDoor, a: DungeonRoom?, b: DungeonRoom?): Int {
        val noInfo = !opened(a) || !opened(b)
        val rooms = listOfNotNull(a, b)
        return when (door.type) {
            DoorType.Blood -> if (noInfo) darker(BLOOD) else BLOOD
            DoorType.Wither -> WITHER
            // an unlocked fairy door: black until the room behind it is open (as dtMap shows it)
            DoorType.Fairy -> if (noInfo) WITHER else if (rooms.firstOrNull { it.type != RoomType.NORMAL }?.type == RoomType.FAIRY) FAIRY else NORMAL
            else -> if (noInfo) UNOPENED else when (rooms.firstOrNull { it.type != RoomType.NORMAL && it.type != RoomType.FAIRY }?.type) {
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

    private fun drawName(g: GuiGraphicsExtractor, room: DungeonRoom) {
        if (!opened(room) || room.type == RoomType.ENTRANCE || room.type == RoomType.BLOOD || room.type == RoomType.FAIRY || room.type == RoomType.UNDISCOVERED) return
        val (cx, cz) = center(room) ?: return
        // as dtMap: the name once the room is open (and Odin has scanned it), its check mark before that
        val name = knownName(room)
        if (name == null) {
            val icon = when (room.checkmark) {
                MapCheckmark.GREEN -> greenCheck
                MapCheckmark.WHITE -> whiteCheck
                MapCheckmark.RED -> cross
                else -> return
            }
            g.blit(RenderPipelines.GUI_TEXTURED, icon, cx - ROOM / 2 + 2, cz - ROOM / 2 + 2, 0f, 0f, 12, 12, 12, 12, -1)
            return
        }

        val color = when (room.checkmark) {
            MapCheckmark.GREEN -> TEXT_GREEN
            MapCheckmark.NONE, MapCheckmark.UNDISCOVERED -> TEXT_GREY
            MapCheckmark.RED -> TEXT_RED
            else -> TEXT_WHITE
        }
        // dtMap's layout: one word a line, the block centred on the room
        val scale = DungeonMap.textScaling
        val lineH = mc.font.lineHeight * scale
        val words = name.split(" ")
        val top = ROOM / 2 - lineH / 2f - ((words.size - 1) / 2.0 * lineH).toInt()
        for ((i, word) in words.withIndex()) {
            g.pose().pushMatrix()
            g.pose().translate(cx.toFloat(), cz - ROOM / 2 + i * lineH + top)
            g.pose().scale(scale)
            g.centeredText(mc.font, word, 0, 0, color)
            g.pose().popMatrix()
        }
    }

    private fun drawPlayers(g: GuiGraphicsExtractor) {
        val self = mc.player ?: return
        val title = mc.gui.screen()?.title?.string
        val names = self.mainHandItem.itemId.let { it == "INFINITE_SPIRIT_LEAP" || it == "SPIRIT_LEAP" } || title in leapMenus
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
            val (x, z) = if (entity != null) worldX(entity.x) to worldX(entity.z) else decorationPos(player.mapPos.x, player.mapPos.z)
            drawHead(g, x, z, player.renderYaw, player.playerSkin, classColor(player.clazz), player.name, names)
        }
        if (!drewSelf) drawHead(g, worldX(self.x), worldX(self.z), self.yRot, self.skin, classColor(DungeonUtils.currentDungeonPlayer.clazz), selfName, names, own = true)
    }

    /** A world coordinate on the map, as dtMap places players. */
    private fun worldX(v: Double): Float = ((v + 201.0) / (32.0 / TILE)).toFloat()

    /** A map marker (from the map item, for players too far away to see) on the map. */
    private fun decorationPos(x: Int, z: Int): Pair<Float, Float> {
        val gap = DungeonScan.roomGap.toFloat()
        return ((x + 128) / 2f - DungeonScan.startX) * TILE / gap to ((z + 128) / 2f - DungeonScan.startY) * TILE / gap
    }

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
        // yours: dtMap's white marker (its MC Map Pointer), drawn as dtMap does, no class square
        if (own) g.blit(RenderPipelines.GUI_TEXTURED, selfMarker, 5, 5, 10f, 10f, -10, -10, 10, 10)
        else {
            g.fill(-6, -6, 6, 6, color)
            if (skin != null) PlayerFaceExtractor.extractRenderState(g, skin, -5, -5, 10)
        }
        g.pose().popMatrix()
    }
}
