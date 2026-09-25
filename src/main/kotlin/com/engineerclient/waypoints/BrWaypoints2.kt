package com.engineerclient.waypoints

import com.engineerclient.splits.DoorBlocks
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.odtheking.odin.clickgui.settings.impl.ActionSetting
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.KeybindSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.clickgui.settings.impl.StringSetting
import com.odtheking.odin.events.BlockUpdateEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.RenderEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.dungeon.Highlight
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.features.impl.dungeon.map.tile.DungeonRoom
import com.odtheking.odin.features.impl.dungeon.map.tile.MapCheckmark
import com.odtheking.odin.features.impl.dungeon.map.tile.RoomType
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.itemUUID
import com.odtheking.odin.utils.modMessage
import com.odtheking.odin.utils.render.drawFilledBox
import com.odtheking.odin.utils.render.drawStyledBox
import com.odtheking.odin.utils.render.drawText
import com.odtheking.odin.utils.render.drawWireFrameBox
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.EnderMan
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.lwjgl.glfw.GLFW
import java.io.File

/**
 * Boxes that group a room's starred mobs, drawn and sized in game with a "wand" — any item you pick
 * with "Make Held Item Wand". With Edit Mode on and the wand in hand:
 *
 *  - Drop places a 1x1x1 box on the block at your feet, or deletes the box you are looking at.
 *  - Look through a box to select the side behind ([BoxFaces]); stand inside it and look up to
 *    select its top. Left click or scroll up pushes it out a block, right click or scroll down
 *    pulls it in. A box never
 *    gets thinner than a block, and its bottom never moves.
 *
 * A starred mob whose body overlaps a box where it was first seen is claimed by that box (the box
 * it overlaps most, if several); one in no box goes to the nearest box in its room within 5
 * blocks. Boxes are purple and show only in the room you are in; a box hides once all its mobs
 * are dead or the map shows the room cleared (never deleted), unless Keep All Boxes or Edit Mode
 * is on.
 *
 * Boxes are saved per room, relative to the room, so they come back in any run and any rotation.
 */
object BrWaypoints2 : Module(
    name = "BR Waypoints 2",
    category = Category.custom("Engineer Client"),
    description = "Boxes that group a room's starred mobs, shown while any of them is alive. Made in game with a wand.",
) {

    private var editMode by BooleanSetting("Edit Mode", false, desc = "Edits only happen while this is on. Off, the wand is just an item.")

    private val editKey by KeybindSetting("Edit Mode Keybind", GLFW.GLFW_KEY_UNKNOWN, "Toggles Edit Mode.").onPress {
        editMode = !editMode
        modMessage("§dBR Waypoints 2 §7edit mode " + if (editMode) "§aon" else "§coff")
    }

    private val makeWand by ActionSetting("Make Held Item Wand", desc = "Makes the item in your hand the wand, the tool the editor is used with.") {
        val held = mc.player?.mainHandItem
        if (held == null || held.isEmpty) return@ActionSetting modMessage("§cHold the item you want as the wand first.")
        wand = identity(held)
        modMessage("§aWand set: §f${held.hoverName.string}")
    }

    private val clearRoom by ActionSetting("Clear Room", desc = "Deletes every box in the room you are standing in, saved ones included.") {
        val room = DungeonUtils.currentRoom?.name ?: return@ActionSetting modMessage("§cYou are not in a dungeon room.")
        val gone = boxes.count { it.room == room }
        boxes.removeAll { it.room == room }
        saved.remove(room)
        write()
        modMessage("§aCleared §f$gone §abox${if (gone == 1) "" else "es"} from §f$room§a.")
    }

    private val keepAll by BooleanSetting("Keep All Boxes", false, desc = "Shows every box in your room. Off, a box hides once all its starred mobs are dead. Edit Mode always shows them all.")

    private val spawnMarkers by BooleanSetting("Starred Mobs Spawn", false, desc = "Marks where each starred mob was first seen, flat on the floor in Odin's Highlight colour.")

    /** The highest box number ever given, kept with the config so a deleted box's number stays retired. */
    private var lastId by StringSetting("Last Box Number", "0", 16, desc = "The highest box number given.").hide()

    /** Which item is the wand, saved with the config so it survives a restart. */
    private var wand by StringSetting("Wand", "", 256, desc = "The wand's identity.").hide()

    // --- boxes -----------------------------------------------------------------------------------

    /**
     * A box in the world: its corners ([BoxFaces.MIN_X]..), the room it belongs to, and its number —
     * given once, kept for good and never reused, so data can be tied to a box across runs.
     */
    class Box(val c: IntArray, val room: String?, val id: Int) {
        /** Written to the file. A box placed before Odin has worked out its room waits to be. */
        var saved = false

        fun aabb() = AABB(c[0].toDouble(), c[1].toDouble(), c[2].toDouble(), c[3].toDouble(), c[4].toDouble(), c[5].toDouble())
    }

    private val boxes = mutableListOf<Box>()

    /**
     * Every room's boxes, by room name, as their lowest and highest block in the room's own
     * coordinates (rotated to north, as Odin's waypoints are): x1 y1 z1 x2 y2 z2.
     */
    private val saved: MutableMap<String, MutableList<IntArray>> by lazy { read() }
    /** Each room whose boxes are in the world, and the rotation and clay block they were placed by. */
    private val loadedRooms = HashMap<String, String>()

    // --- starred mobs ----------------------------------------------------------------------------

    /**
     * A starred mob: where it was first seen, the mob and name tag entities while they are in view,
     * and whether it has died.
     */
    private class Mob(val spawn: AABB, val x: Double, val y: Double, val z: Double, val room: String?, var entity: Entity?, var tag: Entity?) {
        var dead = false
        /** The tick its name tag went, if it has — the same tick as the mob means it died. */
        var tagGoneAt: Int? = null
    }

    private val mobs = mutableListOf<Mob>()
    private val byId = HashMap<Int, Mob>()
    private var ticks = 0

    // Odin's Highlight: which name tags are starred mobs, and how a tag finds its mob.
    private val MOB_NAMES = listOf("Lurker", "Dreadlord", "Souleater", "Zombie", "Skeleton", "Skeletor", "Sniper", "Super Archer", "Spider", "Fels", "Withermancer", "Lost Adventurer", "Angry Archaeologist", "Frozen Adventurer")
    private val STARRED = Regex("^.*✯ .*\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?.?❤$")

    private var useHeld = false

    private val PURPLE = Color(170, 0, 170, 1f)

    private val CONTROL_CODES = Regex("\u00a7.")
    private const val MORT = "[NPC] Mort: Here, I found this map when I first entered the dungeon."
    private const val BLOOD_DOOR = "The BLOOD DOOR has been opened!"
    private val WITHER_DOOR = Regex("""^\w+ opened a WITHER door!$""")

    /** How far away a box can be selected from. */
    private const val REACH = 48.0

    /** A nearest box further than this from a mob does not claim it. */
    private const val CLAIM_REACH = 5.0

    // How far off a mob can vanish and still count as killed; see [watchDeaths].
    private const val DIES_WITH_TAG = 48.0
    private const val DIES_ALONE = 32.0

    init {
        on<LevelEvent.Load> {
            boxes.clear(); loadedRooms.clear(); mobs.clear(); byId.clear()
            rushing = false; rushRoom = null; rushed.clear(); watchDoorUntil = 0; barriers.clear()
        }

        // Chat straight off the network, before any mod can hide it: the rush starts with the
        // dungeon, each wither door starts falling on its line, and it ends at the blood door.
        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (overlay) return@onReceive
            val text = content.string.replace(CONTROL_CODES, "")
            mc.execute { onChat(text) }
        }

        // The door that just started falling: its blocks turn to barrier on the tick of its line.
        on<BlockUpdateEvent> {
            if (ticks <= watchDoorUntil && updated.block == Blocks.BARRIER && old.block != Blocks.BARRIER) barriers += pos.x to pos.z
        }

        on<TickEvent.End> {
            if (!DungeonUtils.inDungeons) return@on
            ticks++
            if (barriers.size >= DoorBlocks.DOOR_BLOCKS) for (d in DoorBlocks.doors(barriers)) doorFalling(tileRoom(d.a), tileRoom(d.b))
            barriers.clear()
            loadRooms()
            if (DungeonUtils.inClear) findStarred()
            watchDeaths()
            // Right click repeats every few ticks while held; a pull is one per press.
            if (!mc.options.keyUse.isDown) useHeld = false
        }

        on<RenderEvent.Extract> {
            if (!DungeonUtils.inDungeons) return@on

            for (box in shown()) {
                val bb = box.aabb()
                // Seen through walls; the faces faint enough to walk through without noticing.
                drawFilledBox(bb, PURPLE.withAlpha(0.08f), depth = false)
                drawWireFrameBox(bb, PURPLE, depth = false)
                drawText("§d" + box.id, Vec3((bb.minX + bb.maxX) / 2, bb.maxY + 0.6, (bb.minZ + bb.maxZ) / 2), 1.5f, false)
            }

            // The selected face, only with the wand in hand, when it can actually be edited.
            if (editing()) target(mc.deltaTracker.getGameTimeDeltaPartialTick(true))?.let { (box, face) ->
                drawFilledBox(faceSlab(box.aabb(), face), PURPLE.withAlpha(0.35f), depth = false)
            }

            if (spawnMarkers) {
                val colour = (Highlight.settings["Highlight color"] as? ColorSetting)?.value ?: Colors.WHITE
                val style = (Highlight.settings["Render Style"] as? SelectorSetting)?.value ?: 1
                for (mob in mobs) {
                    // Flat on the floor, lifted a hair so it does not flicker into the block.
                    val y = mob.y + 0.02
                    drawStyledBox(AABB(mob.x - 0.5, y, mob.z - 0.5, mob.x + 0.5, y, mob.z + 0.5), colour, style, true)
                }
            }
        }
    }

    private fun Color.withAlpha(a: Float) = Color(red, green, blue, a)

    // --- starred mobs ----------------------------------------------------------------------------

    private fun findStarred() {
        val level = mc.level ?: return
        for (tag in level.entitiesForRendering()) {
            if (tag !is ArmorStand || !tag.isAlive) continue
            val name = tag.name.string
            if (MOB_NAMES.none { it in name } || !STARRED.matches(name)) continue
            val mob = level.getEntities(tag, tag.boundingBox.move(0.0, -1.0, 0.0)) { isMob(it) }.firstOrNull() ?: continue
            val known = byId[mob.id]
            if (known != null) {
                // Back in view: Hypixel keeps a mob's id when it comes back into range.
                if (known.entity == null && !known.dead) known.entity = mob
                if (known.tag !== tag) { known.tag = tag; known.tagGoneAt = null }
                continue
            }
            val m = Mob(mob.boundingBox, mob.x, mob.y, mob.z, roomAt(mob.x, mob.z)?.name, mob, tag)
            mobs += m
            byId[mob.id] = m
        }
    }

    /**
     * Whether a mob died, from what the recorded runs show (1721 starred mobs across 32 runs).
     * Hypixel sends no death — no death animation, no health reaching zero, the tag never shows
     * 0❤ — the mob and its name tag are simply removed, on the same tick. Going out of view removes
     * them too, but further away, and a name tag also vanishes on its own from about 16 blocks
     * while the mob stays. So a mob is dead when it is removed within 48 blocks on the same tick as
     * its tag, or within 32 blocks at all. Removed further off, it is only out of view: it keeps
     * its state, and is picked up again when it comes back.
     */
    private fun watchDeaths() {
        val player = mc.player ?: return
        for (mob in mobs) {
            val tag = mob.tag
            if (tag != null && tag.isRemoved && mob.tagGoneAt == null) mob.tagGoneAt = ticks
        }
        for (mob in mobs) {
            val e = mob.entity ?: continue
            if (!e.isRemoved) continue
            val d = kotlin.math.hypot(player.x - e.x, player.z - e.z)
            val withTag = mob.tagGoneAt?.let { ticks - it <= 1 } == true
            if (d < DIES_WITH_TAG && (withTag || d < DIES_ALONE)) mob.dead = true
            mob.entity = null
            mob.tag = null
        }
    }

    /**
     * The boxes on screen, only ever the room you are in. With Keep All Boxes or Edit Mode on, all
     * of that room's boxes. Otherwise none once the map shows the room cleared, and a box hides
     * once every mob it claimed is known dead, showing until then — before any of its mobs are in
     * view, too. Hidden is only hidden: the box stays saved and comes back with its room next run.
     */
    private fun shown(): List<Box> {
        // The room you are in, and on blood rush the room whose door is coming down ahead of you.
        val rooms = listOfNotNull(DungeonUtils.currentRoom, rushRoom?.takeIf { rushing }?.let { n -> DungeonScan.rooms.firstOrNull { it.name == n } })
            .distinctBy { it.name }
        // Edit Mode shows them all too: a box being drawn has no mobs yet.
        if (keepAll || editMode) return boxes.filter { box -> rooms.any { it.name == box.room } }
        val claimed = HashSet<Box>(); val alive = HashSet<Box>()
        for (mob in mobs) {
            val box = claimOf(mob) ?: continue
            claimed += box
            if (!mob.dead) alive += box
        }
        // Cleared on the map (white check, or green once secrets are done too): every mob is dead,
        // including the ones killed out of your sight.
        val open = rooms.filter { it.checkmark != MapCheckmark.WHITE && it.checkmark != MapCheckmark.GREEN }.mapTo(HashSet()) { it.name }
        return boxes.filter { it.room in open && (it !in claimed || it in alive) }
    }

    // --- blood rush ------------------------------------------------------------------------------

    /** Between the dungeon starting and the blood door opening. */
    private var rushing = false
    /** The room the rush is heading into: the one behind the door that last started falling. */
    private var rushRoom: String? = null
    /** Rooms the rush has already been through, so a door's far side can be told from its near. */
    private val rushed = HashSet<String>()
    /** Watch for a door's blocks until this tick; set by the line that says one is falling. */
    private var watchDoorUntil = 0
    /** Waiting for the start door: the first rush door, the one out of Entrance. */
    private var startDoor = false
    private val barriers = mutableListOf<Pair<Int, Int>>()

    private fun onChat(msg: String) {
        if (!DungeonUtils.inDungeons) return
        when {
            // The dungeon starting is the start door coming down.
            msg == MORT -> { rushing = true; rushRoom = null; rushed.clear(); startDoor = true; watchDoorUntil = ticks + 40 }
            // A wither door means the start door has been and gone, whether its blocks were seen or not.
            rushing && WITHER_DOOR.matches(msg) -> { startDoor = false; watchDoorUntil = ticks + 10 }
            msg == BLOOD_DOOR -> { rushing = false; rushRoom = null; watchDoorUntil = 0 }
        }
    }

    /**
     * A door started falling between two rooms. The start door is the one out of Entrance; after
     * that it is whichever side the rush has not been through yet — past fairy too, which the rush
     * walks through by fairy's own open door and so never has a wither door into.
     */
    private fun doorFalling(a: DungeonRoom?, b: DungeonRoom?) {
        val sides = listOfNotNull(a, b)
        if (startDoor) {
            // At the start other doors come down too (fairy's); the rush's is the one out of Entrance.
            val entrance = sides.firstOrNull { it.type == RoomType.ENTRANCE } ?: return
            rushed += entrance.name ?: return
            startDoor = false
        }
        val next = sides.filter { it.name != null && it.name !in rushed }.minByOrNull { if (it.type == RoomType.FAIRY) 1 else 0 } ?: return
        rushRoom = next.name
        rushed += next.name!!
        watchDoorUntil = 0
    }

    private fun tileRoom(t: Pair<Int, Int>): DungeonRoom? =
        if (t.first !in 0..5 || t.second !in 0..5) null else DungeonScan.tiles[t.first + t.second * 6].room

    /**
     * The box a mob belongs to: the one its body overlapped most where it was first seen, or, if it
     * overlapped none, the nearest box in its room within [CLAIM_REACH] blocks. Otherwise, none.
     */
    private fun claimOf(mob: Mob): Box? {
        var best: Box? = null
        var most = 0.0
        for (box in boxes) {
            val o = overlap(mob.spawn, box.aabb())
            if (o > most) { most = o; best = box }
        }
        if (best != null || mob.room == null) return best
        return boxes.filter { it.room == mob.room && gap(mob.spawn, it.aabb()) <= CLAIM_REACH * CLAIM_REACH }
            .minByOrNull { gap(mob.spawn, it.aabb()) }
    }

    /** How far apart two boxes are at their closest; 0 if they touch. */
    private fun gap(a: AABB, b: AABB): Double {
        val x = maxOf(0.0, maxOf(a.minX, b.minX) - minOf(a.maxX, b.maxX))
        val y = maxOf(0.0, maxOf(a.minY, b.minY) - minOf(a.maxY, b.maxY))
        val z = maxOf(0.0, maxOf(a.minZ, b.minZ) - minOf(a.maxZ, b.maxZ))
        return x * x + y * y + z * z
    }

    private fun overlap(a: AABB, b: AABB): Double {
        val x = minOf(a.maxX, b.maxX) - maxOf(a.minX, b.minX)
        val y = minOf(a.maxY, b.maxY) - maxOf(a.minY, b.minY)
        val z = minOf(a.maxZ, b.maxZ) - maxOf(a.minZ, b.minZ)
        return if (x > 0 && y > 0 && z > 0) x * y * z else 0.0
    }

    /**
     * What can sit under a starred tag: Odin's Highlight rule, except for Fels. A Fel is an
     * enderman named "Dinnerbone" (drawn upside down) about 3 blocks under its tag, and until it
     * wakes it is invisible but for its head — Odin skips invisible mobs, which would drop every
     * dormant Fel, so an enderman counts whether it can be seen or not.
     */
    private fun isMob(e: Entity): Boolean = when (e) {
        is ArmorStand -> false
        is WitherBoss -> false
        is EnderMan -> true
        is Player -> e.uuid.version() == 2 && e != mc.player
        else -> !e.isInvisible
    }

    // --- input, called from the mixins -----------------------------------------------------------

    /** Drop: delete the box you are looking at, or place one. True means the drop must not happen. */
    @JvmStatic
    fun onDrop(): Boolean {
        if (!editing()) return false
        val player = mc.player ?: return false
        target(1f)?.let { (box, _) ->
            boxes -= box
            save(box.room)
            return true
        }
        val feet = BlockPos.containing(player.x, player.y, player.z)
        val c = intArrayOf(feet.x, feet.y, feet.z, feet.x + 1, feet.y + 1, feet.z + 1)
        if (boxes.any { it.c.contentEquals(c) }) return true
        val box = Box(c, roomAt(player.x, player.z)?.name, newId())
        boxes += box
        if (!save(box.room)) modMessage("§eOdin has not worked out this room yet; the box saves once it has.")
        return true
    }

    /** Left click: push the selected face out. True cancels the swing. */
    @JvmStatic
    fun onAttack(): Boolean = move(+1)

    /** Holding left click: swallowed while a face is selected, so the block behind is not mined. */
    @JvmStatic
    fun blocksContinueAttack(): Boolean = editing() && target(1f) != null

    /** Right click: pull the selected face in, once per press. True cancels using the wand. */
    @JvmStatic
    fun onUse(): Boolean {
        if (!editing() || target(1f) == null) return false
        if (!useHeld) { useHeld = true; move(-1) }
        return true
    }

    /** Scroll: up pushes out, down pulls in. True keeps the hotbar from switching off the wand. */
    @JvmStatic
    fun onScroll(y: Double): Boolean = if (y == 0.0) false else move(if (y > 0) +1 else -1)

    private fun move(by: Int): Boolean {
        if (!editing()) return false
        val (box, face) = target(1f) ?: return false
        if (BoxFaces.move(box.c, face, by)) save(box.room)
        return true
    }

    private fun editing(): Boolean {
        if (!enabled || !editMode || wand.isEmpty() || mc.screen != null) return false
        return identity(mc.player?.mainHandItem ?: return false) == wand
    }

    // --- selection -------------------------------------------------------------------------------

    /** The box under the crosshair, nearest first, and the face of it that is selected. */
    private fun target(partial: Float): Pair<Box, Face>? {
        val player = mc.player ?: return null
        val e = player.getEyePosition(partial)
        val v = player.getViewVector(partial)
        val eye = doubleArrayOf(e.x, e.y, e.z)
        val dir = doubleArrayOf(v.x, v.y, v.z)
        // Standing in a box and looking up: its top, whatever the view passes through.
        val feet = doubleArrayOf(player.x, player.y, player.z)
        val pitch = player.getViewXRot(partial)
        for (box in shown()) {
            val min = doubleArrayOf(box.c[0].toDouble(), box.c[1].toDouble(), box.c[2].toDouble())
            val max = doubleArrayOf(box.c[3].toDouble(), box.c[4].toDouble(), box.c[5].toDouble())
            if (BoxFaces.editsTop(feet, pitch, min, max)) return box to Face.UP
        }
        var best: Pair<Box, Face>? = null
        var bestT = REACH
        for (box in shown()) {
            val min = doubleArrayOf(box.c[0].toDouble(), box.c[1].toDouble(), box.c[2].toDouble())
            val max = doubleArrayOf(box.c[3].toDouble(), box.c[4].toDouble(), box.c[5].toDouble())
            val t = BoxFaces.distance(eye, dir, min, max) ?: continue
            if (t > bestT) continue
            val face = BoxFaces.select(eye, dir, min, max) ?: continue
            best = box to face
            bestT = t
        }
        return best
    }

    /** A thin slab lying on one face of a box, to show which face is selected. */
    private fun faceSlab(bb: AABB, face: Face): AABB {
        val e = 0.02
        return when (face) {
            Face.EAST -> AABB(bb.maxX - e, bb.minY, bb.minZ, bb.maxX + e, bb.maxY, bb.maxZ)
            Face.WEST -> AABB(bb.minX - e, bb.minY, bb.minZ, bb.minX + e, bb.maxY, bb.maxZ)
            Face.SOUTH -> AABB(bb.minX, bb.minY, bb.maxZ - e, bb.maxX, bb.maxY, bb.maxZ + e)
            Face.NORTH -> AABB(bb.minX, bb.minY, bb.minZ - e, bb.maxX, bb.maxY, bb.minZ + e)
            Face.UP -> AABB(bb.minX, bb.maxY - e, bb.minZ, bb.maxX, bb.maxY + e, bb.maxZ)
        }
    }

    // --- rooms and saving ------------------------------------------------------------------------

    /** The map room a world position is in: tiles are 32 blocks apart, the first centred at -185. */
    private fun roomAt(x: Double, z: Double): DungeonRoom? {
        val tx = Math.floorDiv(Math.floor(x).toInt() + 200, 32)
        val tz = Math.floorDiv(Math.floor(z).toInt() + 200, 32)
        return if (tx !in 0..5 || tz !in 0..5) null else DungeonScan.tiles[tx + tz * 6].room
    }

    /**
     * A room Odin has worked out well enough to turn room coordinates into world ones: rotation and
     * clay block known, and every one of its tiles found. That last part matters — until the whole
     * room is in, Odin reads a big room as a 1x1 and guesses its rotation from that. In the recorded
     * runs Pipes, Pit, Waterfall, Hallway and Quartz Knight all read NORTH first and only turned WEST
     * 10-180 ticks later, and boxes placed or saved by the first guess came out rotated.
     */
    private fun placed(name: String): DungeonRoom? = DungeonScan.rooms.firstOrNull { r ->
        r.name == name && r.rotation != null && r.clayPos != null &&
            r.tiles.size == (r.data?.shape?.tileAmount ?: r.tiles.size)
    }

    /** The rotation and clay block a room's boxes are placed by. */
    private fun transform(room: DungeonRoom) = "${room.rotation}|${room.clayPos}"

    /**
     * Puts each saved room's boxes into the world once Odin has worked the room out, and puts them
     * again if Odin later changes its mind about the room. Boxes placed before the room was worked
     * out are kept where they are and saved now.
     */
    private fun loadRooms() {
        val names = saved.keys + boxes.filter { !it.saved }.mapNotNull { it.room }
        for (name in names.toSet()) {
            val room = placed(name) ?: continue
            val t = transform(room)
            if (loadedRooms[name] == t) continue
            boxes.removeAll { it.room == name && it.saved }
            for (r in saved[name].orEmpty()) {
                val a = room.getRealCoords(BlockPos(r[0], r[1], r[2]))
                val b = room.getRealCoords(BlockPos(r[3], r[4], r[5]))
                boxes += Box(intArrayOf(
                    minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z),
                    maxOf(a.x, b.x) + 1, maxOf(a.y, b.y) + 1, maxOf(a.z, b.z) + 1,
                ), name, r[6]).also { it.saved = true }
            }
            loadedRooms[name] = t
            if (boxes.any { it.room == name && !it.saved }) save(name)
        }
    }

    /** Saves a room's boxes as they are now. False if Odin has not worked the room out yet. */
    private fun save(name: String?): Boolean {
        if (name == null) return false
        val room = placed(name) ?: return false
        // Not yet put back by this room's current placement: [loadRooms] saves it once it has.
        if (loadedRooms[name] != transform(room)) return false
        val mine = boxes.filter { it.room == name }
        val list = mine.map { box ->
            val a = room.getRelativeCoords(BlockPos(box.c[0], box.c[1], box.c[2]))
            val b = room.getRelativeCoords(BlockPos(box.c[3] - 1, box.c[4] - 1, box.c[5] - 1))
            intArrayOf(a.x, a.y, a.z, b.x, b.y, b.z, box.id)
        }
        mine.forEach { it.saved = true }
        if (list.isEmpty()) saved.remove(name) else saved[name] = list.toMutableList()
        write()
        return true
    }

    private val file get() = File(mc.gameDirectory, "config/engineerclient/brwaypoints2.json")
    private val gson = GsonBuilder().setPrettyPrinting().create()

    /** The next box number: one past the highest ever given, so a deleted box's number is not reused. */
    private var nextId = 1

    private fun newId(): Int {
        saved.size // numbers are only known once the file has been read
        val id = maxOf(nextId, (lastId.toIntOrNull() ?: 0) + 1)
        nextId = id + 1
        lastId = id.toString()
        return id
    }

    /**
     * The saved boxes: per room, each as x1 y1 z1 x2 y2 z2 and its number. Boxes saved before they
     * had numbers get one here, once, and the file is rewritten with them.
     */
    private fun read(): MutableMap<String, MutableList<IntArray>> {
        val map = runCatching {
            val type = object : TypeToken<MutableMap<String, MutableList<IntArray>>>() {}.type
            gson.fromJson<MutableMap<String, MutableList<IntArray>>>(file.readText(), type)
        }.getOrNull() ?: return mutableMapOf()
        var top = map.values.flatten().filter { it.size >= 7 }.maxOfOrNull { it[6] } ?: 0
        var numbered = false
        for (list in map.values) list.replaceAll { r -> if (r.size >= 7) r else { numbered = true; r.copyOf(7).also { it[6] = ++top } } }
        nextId = top + 1
        if (numbered) runCatching { file.writeText(gson.toJson(map)) }
        return map
    }

    private fun write() {
        runCatching {
            file.parentFile.mkdirs()
            file.writeText(gson.toJson(saved))
        }.onFailure { modMessage("§cCould not save BR Waypoints 2 boxes: ${it.message}") }
    }

    /**
     * What makes an item this item: a Skyblock item's own uuid when it has one (that exact item),
     * else its Skyblock id, else the vanilla item and its name.
     */
    private fun identity(stack: ItemStack): String = when {
        stack.isEmpty -> ""
        stack.itemUUID.isNotEmpty() -> "uuid:" + stack.itemUUID
        stack.itemId.isNotEmpty() -> "id:" + stack.itemId
        else -> "item:" + BuiltInRegistries.ITEM.getKey(stack.item) + "|" + stack.hoverName.string
    }
}
