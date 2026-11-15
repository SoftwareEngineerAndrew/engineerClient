package com.engineerclient.dungeon

import com.engineerclient.EngineerClient
import com.engineerclient.dungeonpack.pack.BundledPacks
import com.engineerclient.dungeonpack.pack.PackFiles
import com.engineerclient.dungeonpack.pack.PackReport
import com.engineerclient.dungeonpack.pack.PackSource
import com.engineerclient.dungeonpack.pack.Packs
import com.engineerclient.dungeonpack.pack.Pos
import com.engineerclient.dungeonpack.pack.Rot
import com.engineerclient.dungeonpack.pack.Tile
import com.engineerclient.dungeonpack.pack.rooms.Placement
import com.engineerclient.dungeonpack.pack.rooms.RoomPack
import com.engineerclient.dungeonpack.pack.secrets.SecretPack
import com.engineerclient.dungeonpack.pack.secrets.SecretType
import com.engineerclient.pov.KnownWorld
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.DropdownSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.SecretPickupEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.impl.dungeon.SecretClicked
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.features.impl.dungeon.map.WorldScan
import com.odtheking.odin.features.impl.dungeon.map.tile.DungeonRoom
import com.odtheking.odin.features.impl.dungeon.map.tile.RoomShape
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Color.Companion.multiplyAlpha
import com.odtheking.odin.utils.IVec2
import com.odtheking.odin.utils.render.drawFilledBox
import com.odtheking.odin.utils.render.drawText
import com.odtheking.odin.utils.render.drawWireFrameBox
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.BlockPos
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.concurrent.CompletableFuture
import kotlin.math.abs

object SecretWaypoints {
    private val dropdown = DropdownSetting("Waypoints", desc = "Boxes every secret in the clear.")
    private val show = BooleanSetting("Secret Waypoints", false, desc = "Boxes every secret in the clear.").withDependency { dropdown.value }
    private val chestColor = ColorSetting("Chest Color", Color(255, 170, 0), true, desc = "Chests.").withDependency { dropdown.value && show.value }
    private val itemColor = ColorSetting("Item Color", Color(85, 85, 255), true, desc = "Items.").withDependency { dropdown.value && show.value }
    private val essenceColor = ColorSetting("Essence Color", Color(170, 0, 170), true, desc = "Wither essence.").withDependency { dropdown.value && show.value }
    private val batColor = ColorSetting("Bat Color", Color(85, 255, 85), true, desc = "Bats.").withDependency { dropdown.value && show.value }
    private val keyColor = ColorSetting("Redstone Key Color", Color(255, 85, 85), true, desc = "Redstone Key spots.").withDependency { dropdown.value && show.value }
    private val leverColor = ColorSetting("Lever Color", Color(255, 255, 85), true, desc = "Levers.").withDependency { dropdown.value && show.value }
    private val text = BooleanSetting("Secret Text", false, desc = "Names each secret.").withDependency { dropdown.value && show.value }
    private val hideCollected = BooleanSetting("Hide Collected", true, desc = "Removes a secret once it is taken.").withDependency { dropdown.value && show.value }
    private val throughWalls = BooleanSetting("Secret Through Walls", true, desc = "Draws them through walls.").withDependency { dropdown.value && show.value }
    private val currentRoomOnly = BooleanSetting("Current Room Only", true, desc = "Only the secrets of the room you're in.").withDependency { dropdown.value && show.value }

    private enum class Kind(val label: String) { CHEST("Chest"), ITEM("Item"), ESSENCE("Essence"), BAT("Bat"), KEY("Redstone Key"), LEVER("Lever") }

    private class Spot(val kind: Kind, val pos: BlockPos, val box: AABB)

    private val secrets by lazy { SecretPack.bundled }
    @Volatile private var ownPack: RoomPack? = null
    private var loading = false
    private val roomsPack: RoomPack? get() = KnownWorld.roomPack ?: Packs.current?.rooms ?: ownPack

    private val placed = HashMap<String, List<Spot>>()
    private var spots: List<Spot> = emptyList()
    private val collected = HashSet<BlockPos>()
    private val cores = HashMap<Pair<Int, Int>, Int>()
    private var lastChest: BlockPos? = null
    private var lastChestAt = 0L
    private var ticks = 0
    private var lastRoom: DungeonRoom? = null

    fun install() {
        for (s in listOf(dropdown, show, chestColor, itemColor, essenceColor, batColor, keyColor, leverColor, text, hideCollected, throughWalls, currentRoomOnly))
            SecretClicked.registerSetting(s)

        on<LevelEvent.Load> { placed.clear(); spots = emptyList(); collected.clear(); cores.clear(); lastChest = null; lastRoom = null }

        on<TickEvent.End> {
            if (!active()) return@on
            // A new room is shown at once; otherwise every 10 ticks, as rooms are found and turned.
            val room = DungeonUtils.currentRoom
            if (++ticks % 10 != 0 && room === lastRoom) return@on
            lastRoom = room
            val pack = roomsPack ?: return@on load()
            val rooms = if (currentRoomOnly.value) listOfNotNull(room) else DungeonScan.rooms.distinct()
            EngineerClient.safely("secret waypoints") { spots = rooms.flatMap { spotsOf(it, pack) } }
        }

        on<SecretPickupEvent.Interact> {
            val spot = spots.firstOrNull { it.pos == blockPos } ?: return@on
            collected += spot.pos
            if (spot.kind == Kind.CHEST) { lastChest = spot.pos; lastChestAt = System.currentTimeMillis() }
        }

        on<SecretPickupEvent.Item> { take(Kind.ITEM, entity.x, entity.z, 8.0) }

        on<SecretPickupEvent.Bat> { take(Kind.BAT, packet.x, packet.z, 10.0) }

        on<MessageEvent.Chat> {
            // A locked chest still holds its secret.
            if (message == "That chest is locked!" && System.currentTimeMillis() - lastChestAt < 1000) lastChest?.let { collected -= it }
        }

        on<RenderExtractEvent> {
            if (!active()) return@on
            val depth = !throughWalls.value
            for (s in spots) {
                if (hideCollected.value && s.pos in collected) continue
                val c = colorOf(s.kind)
                drawFilledBox(s.box, c.multiplyAlpha(0.3f), depth)
                drawWireFrameBox(s.box, c, 2f, depth)
                if (text.value) drawText(s.kind.label, Vec3(s.box.center.x, s.box.maxY + 0.3, s.box.center.z), 1f, depth)
            }
        }
        EventBus.subscribe(this)
    }

    private fun active() = SecretClicked.enabled && show.value && DungeonUtils.inClear

    private fun load() {
        if (loading) return
        loading = true
        CompletableFuture.runAsync {
            val dir = PackFiles.defaultDir()
            ownPack = RoomPack.load(PackSource.resolve(dir, BundledPacks.JAR, PackFiles.cacheRootFor(dir), PackReport(), listOf(PackFiles.ROOMS_INDEX, PackFiles.ROOMS_BLOCKS)))
        }
    }

    // Item and bat spots are where they spawn, not where they are taken, so the nearest one in reach goes.
    private fun take(kind: Kind, x: Double, z: Double, reach: Double) {
        spots.filter { it.kind == kind && it.pos !in collected && abs(it.pos.x + 0.5 - x) + abs(it.pos.z + 0.5 - z) < reach }
            .minByOrNull { abs(it.pos.x + 0.5 - x) + abs(it.pos.z + 0.5 - z) }?.let { collected += it.pos }
    }

    private fun colorOf(kind: Kind): Color = when (kind) {
        Kind.CHEST -> chestColor.value
        Kind.ITEM -> itemColor.value
        Kind.ESSENCE -> essenceColor.value
        Kind.BAT -> batColor.value
        Kind.KEY -> keyColor.value
        Kind.LEVER -> leverColor.value
    }

    private fun spotsOf(room: DungeonRoom, pack: RoomPack): List<Spot> {
        val name = room.data?.name ?: return emptyList()
        val rot = Rot.fromWord(room.rotation?.name) ?: return emptyList()
        // A 1x1 room seen only on the map has a placeholder rotation until its clay is found.
        if (room.shape == RoomShape.OneByOne && room.clayPos == null && name != "Fairy") return emptyList()
        val tiles = room.tiles.map { Tile(it.x, it.z) }
        if (tiles.isEmpty() || tiles.any { it.x !in 0..5 || it.z !in 0..5 }) return emptyList()
        val core = core(room)
        val design = core?.let { pack.designByCore(it) }?.let { pack.design(it.design) } ?: pack.designsByName(name).firstOrNull() ?: return emptyList()
        val key = "${design.id}|$rot|$tiles"
        placed[key]?.let { return it }
        val p = try { design.place(rot, tiles) } catch (e: Exception) { return emptyList() }
        val d = secrets.design(design.id) ?: return emptyList<Spot>().also { placed[key] = it }
        val out = ArrayList<Spot>()
        for (s in d.sites) {
            val kind = when (s.type) {
                SecretType.CHEST, SecretType.LOCKED_CHEST -> Kind.CHEST
                SecretType.ITEM -> Kind.ITEM
                SecretType.ESSENCE -> Kind.ESSENCE
                SecretType.BAT -> Kind.BAT
                SecretType.KEY -> continue
            }
            out += spot(kind, s.pos, p)
        }
        for (l in d.levers) out += spot(Kind.LEVER, l.pos, p)
        d.key?.skulls?.forEach { out += spot(Kind.KEY, it.first, p) }
        placed[key] = out
        return out
    }

    private fun spot(kind: Kind, c: Pos, p: Placement): Spot {
        val w = p.toWorld(c.x, c.y, c.z)
        val pos = BlockPos(w.x, w.y, w.z)
        val x = w.x.toDouble(); val y = w.y.toDouble(); val z = w.z.toDouble()
        val box = when (kind) {
            Kind.CHEST -> AABB(x + 0.0625, y, z + 0.0625, x + 0.9375, y + 0.875, z + 0.9375)
            Kind.ESSENCE, Kind.KEY -> AABB(x + 0.25, y, z + 0.25, x + 0.75, y + 0.5, z + 0.75)
            Kind.LEVER -> AABB(x + 0.25, y, z + 0.25, x + 0.75, y + 0.6, z + 0.75)
            Kind.ITEM, Kind.BAT -> AABB(x, y, z, x + 1, y + 1, z + 1)
        }
        return Spot(kind, pos, box)
    }

    private fun core(room: DungeonRoom): Int? {
        val cs = room.data?.cores ?: return null
        val level = EngineerClient.mc.level ?: return null
        for (t in room.tiles) {
            cores[t.x to t.z]?.takeIf { it in cs }?.let { return it }
            val cx = (t.x - 6) * 2; val cz = (t.z - 6) * 2
            val chunk = level.chunkSource.getChunk(cx, cz, ChunkStatus.FULL, false) as? LevelChunk ?: continue
            val core = WorldScan.getRoomCore(chunk, IVec2(cx * 16 + 7, cz * 16 + 7)).first
            if (core in cs) { cores[t.x to t.z] = core; return core }
        }
        return null
    }
}
