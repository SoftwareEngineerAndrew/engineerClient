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
import com.engineerclient.dungeonpack.pack.secrets.SecretSite
import com.engineerclient.dungeonpack.pack.secrets.SecretType
import com.engineerclient.pov.KnownWorld
import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.clickgui.settings.impl.DropdownSetting
import com.adm.clickgui.settings.impl.NumberSetting
import com.adm.clickgui.settings.impl.SelectorSetting
import com.adm.events.LevelEvent
import com.adm.events.MessageEvent
import com.adm.events.RenderExtractEvent
import com.adm.events.SecretPickupEvent
import com.adm.events.TickEvent
import com.adm.events.core.SubscribeWhile
import com.adm.events.core.on
import com.adm.features.impl.dungeon.SecretClicked
import com.adm.features.impl.dungeon.map.DungeonScan
import com.adm.features.impl.dungeon.map.WorldScan
import com.adm.features.impl.dungeon.map.tile.DungeonRoom
import com.adm.features.impl.dungeon.map.tile.MapCheckmark
import com.adm.features.impl.dungeon.map.tile.RoomShape
import com.adm.features.impl.dungeon.map.tile.RoomType
import com.adm.utils.Color
import com.adm.utils.Color.Companion.multiplyAlpha
import com.adm.utils.Color.Companion.multiplyAlphaRgba
import com.adm.utils.IVec2
import com.adm.utils.render.drawFilledBox
import com.adm.utils.render.drawText
import com.adm.utils.render.drawWireFrameBox
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.BlockPos
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import kotlin.math.abs

object SecretWaypoints {
    private val dropdown = DropdownSetting("Waypoints", desc = "Boxes every secret in the clear.")
    private val show = BooleanSetting("Secret Waypoints", false, desc = "Boxes every secret in the clear.").withDependency { dropdown.value }
    private val style = SelectorSetting("Style", Style.CELL, desc = "How each secret is drawn.").withDependency { dropdown.value && show.value }
    private val lineWidth = NumberSetting("Line Width", 2.0, 0.5..10.0, 0.5, desc = "Outline thickness.").withDependency { dropdown.value && show.value && style.value != Style.FULL_BLOCK }
    private val fillOpacity = NumberSetting("Fill Opacity", 80, 0..255, 1, desc = "How see-through the fill is.").withDependency { dropdown.value && show.value && style.value == Style.FILLED_OUTLINE }
    private val smallBoxes = BooleanSetting("Small Boxes", true, desc = "Boxes fit the secret instead of the whole block, like Devonian.").withDependency { dropdown.value && show.value }
    private val chestColor = ColorSetting("Chest Color", Color(0, 255, 0), true, desc = "Chests.").withDependency { dropdown.value && show.value }
    private val itemColor = ColorSetting("Item Color", Color(0, 0, 255), true, desc = "Items.").withDependency { dropdown.value && show.value }
    private val essenceColor = ColorSetting("Essence Color", Color(255, 0, 255), true, desc = "Wither essence.").withDependency { dropdown.value && show.value }
    private val batColor = ColorSetting("Bat Color", Color(0, 255, 150), true, desc = "Bats.").withDependency { dropdown.value && show.value }
    private val keyColor = ColorSetting("Redstone Key Color", Color(255, 0, 0), true, desc = "Redstone Key spots.").withDependency { dropdown.value && show.value }
    private val leverColor = ColorSetting("Lever Color", Color(0, 150, 255), true, desc = "Levers.").withDependency { dropdown.value && show.value }
    private val text = BooleanSetting("Secret Text", false, desc = "Names each secret.").withDependency { dropdown.value && show.value }
    private val hideCollected = BooleanSetting("Hide Collected", true, desc = "Removes a secret once it is taken.").withDependency { dropdown.value && show.value }
    private val throughWalls = BooleanSetting("Secret Through Walls", true, desc = "Draws them through walls.").withDependency { dropdown.value && show.value }
    private val currentRoomOnly = BooleanSetting("Current Room Only", true, desc = "Only the secrets of the room you're in.").withDependency { dropdown.value && show.value }
    private val hideGreen = BooleanSetting("Hide Green Rooms", true, desc = "No waypoints in a room whose checkmark is green.").withDependency { dropdown.value && show.value }

    enum class Style(private val label: String) { CELL("Devonian"), OUTLINE("Outline"), FILLED_OUTLINE("Filled Outline"), FULL_BLOCK("Full Block"); override fun toString() = label }

    private enum class Kind(val label: String, val tag: String = label) { CHEST("Chest"), ITEM("Item"), ESSENCE("Essence"), BAT("Bat"), KEY("Redstone Key", "Redstone"), LEVER("Lever") }

    private class Spot(val kind: Kind, val pos: BlockPos, val box: AABB, val locked: Boolean = false)

    private val secrets get() = SecretPack.bundled
    @Volatile private var ownPack: RoomPack? = null
    @Volatile private var loading = false
    @Volatile private var retryAt = 0L
    private val roomsPack: RoomPack? get() = KnownWorld.roomPack ?: Packs.current?.rooms ?: ownPack

    // A room's spots stay until its rotation, clay, tiles, core or the pack change.
    private class RoomSpots(val room: DungeonRoom, val rotation: Any?, val clay: Boolean, val tiles: Int, val core: Int?, val pack: RoomPack, val spots: List<Spot>)

    private const val CULL = 64.0
    private val placed = HashMap<String, List<Spot>>()
    private val byRoom = IdentityHashMap<DungeonRoom, RoomSpots>()
    private val shown = ArrayList<RoomSpots>()
    private var spots: List<Spot> = emptyList()
    private val collected = HashSet<BlockPos>()
    private val cores = HashMap<Pair<Int, Int>, Int>()
    private var lastChest: BlockPos? = null
    private var lastChestAt = 0L
    private var ticks = 0
    private var lastRoom: DungeonRoom? = null

    fun install() {
        for (s in listOf(dropdown, show, style, lineWidth, fillOpacity, smallBoxes, chestColor, itemColor, essenceColor, batColor, keyColor, leverColor, text, hideCollected, throughWalls, currentRoomOnly, hideGreen))
            SecretClicked.registerSetting(s)

        on<LevelEvent.Load> { reset() }

        on<TickEvent.End> {
            if (!active()) return@on
            // A new room is shown at once; otherwise every 10 ticks, as rooms are found and turned.
            val room = DungeonUtils.currentRoom
            if (++ticks % 10 != 0 && room === lastRoom) return@on
            lastRoom = room
            val pack = roomsPack ?: return@on load()
            EngineerClient.safely("secret waypoints") { refresh(room, pack) }
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
            val st = style.value
            val width = lineWidth.value.toFloat()
            val fill = fillOpacity.value / 255f
            val cam = EngineerClient.mc.gameRenderer.mainCamera().position()
            val cull = !currentRoomOnly.value
            for (i in shown.indices) {
                val r = shown[i]
                if (hideGreen.value && r.room.checkmark == MapCheckmark.GREEN) continue
                for (s in r.spots) {
                    if (hideCollected.value && s.pos in collected) continue
                    if (cull) {
                        val dx = s.pos.x + 0.5 - cam.x; val dz = s.pos.z + 0.5 - cam.z
                        if (dx * dx + dz * dz > CULL * CULL) continue
                    }
                    val c = colorOf(s.kind)
                    val box = if (smallBoxes.value) s.box else AABB(s.pos)
                    when (st) {
                        Style.CELL, Style.OUTLINE -> drawWireFrameBox(box, c, width, depth)
                        Style.FILLED_OUTLINE -> {
                            fillBox(box, c, fill, depth)
                            drawWireFrameBox(box, c, width, depth)
                        }
                        Style.FULL_BLOCK -> {
                            fillBox(box, c, 0.3f, depth)
                            drawWireFrameBox(box, c, 2f, depth)
                        }
                    }
                    if (!text.value) continue
                    if (st == Style.CELL) {
                        // Past 20 blocks the label grows with distance so it keeps its size on screen.
                        val at = Vec3(s.pos.x + 0.5, s.pos.y + 1.0, s.pos.z + 0.5)
                        drawText(s.kind.tag, at, 2f * maxOf(1.0, at.distanceTo(cam) / 20.0).toFloat(), depth)
                    } else drawText(s.kind.label, Vec3(box.center.x, box.maxY + 0.3, box.center.z), 1f, depth)
                }
            }
        }
        SubscribeWhile.add(this, ::reset) { SecretClicked.enabled && show.value }
    }

    private fun reset() { placed.clear(); byRoom.clear(); shown.clear(); spots = emptyList(); collected.clear(); cores.clear(); lastChest = null; lastRoom = null }

    private fun active() = DungeonUtils.inClear

    // The Color overload counts 0.999 alpha as opaque and the rgba one doesn't, so only those keep making a Color.
    private fun RenderExtractEvent.fillBox(box: AABB, c: Color, factor: Float, depth: Boolean) {
        if (c.alphaFloat * factor >= 0.999f) drawFilledBox(box, c.multiplyAlpha(factor), depth)
        else drawFilledBox(box, c.multiplyAlphaRgba(factor), depth)
    }

    /** Something reads the secret pack: these waypoints or Mimic's chest highlight. */
    val packWanted get() = SecretClicked.enabled && show.value || com.adm.features.impl.dungeon.Mimic.chestHighlightOn

    private fun refresh(here: DungeonRoom?, pack: RoomPack) {
        var changed = false
        var n = 0
        fun add(room: DungeonRoom) {
            for (i in 0 until n) if (shown[i].room === room) return
            var e = byRoom[room]
            val clay = room.clayPos != null
            if (e == null || e.rotation !== room.rotation || e.clay != clay || e.tiles != room.tiles.size || e.pack !== pack || (e.core == null && core(room) != null)) {
                e = RoomSpots(room, room.rotation, clay, room.tiles.size, core(room), pack, spotsOf(room, pack))
                byRoom[room] = e
                changed = true
            }
            if (n < shown.size) { if (shown[n] !== e) { shown[n] = e; changed = true } } else { shown.add(e); changed = true }
            n++
        }
        if (currentRoomOnly.value) here?.let(::add) else for (room in DungeonScan.rooms) add(room)
        while (shown.size > n) { shown.removeAt(shown.size - 1); changed = true }
        if (changed) spots = shown.flatMap { it.spots }
    }

    /** Where [room]'s secret chests are (empty until the rooms pack is in). */
    fun chestSpots(room: DungeonRoom): List<BlockPos> {
        val pack = roomsPack ?: return emptyList<BlockPos>().also { load() }
        return spotsOf(room, pack).filter { it.kind == Kind.CHEST }.map { it.pos }
    }

    class ChestSite(val pos: BlockPos, val locked: Boolean)

    /** [room]'s secret chest sites; null while its design or rotation isn't known yet. */
    fun chestSites(room: DungeonRoom): List<ChestSite>? {
        val pack = roomsPack ?: return null.also { load() }
        if (room.data?.name == null || Rot.fromWord(room.rotation?.name) == null) return null
        if (room.shape == RoomShape.OneByOne && room.clayPos == null && room.data?.name != "Fairy") return null
        return spotsOf(room, pack).filter { it.kind == Kind.CHEST }.map { ChestSite(it.pos, it.locked) }
    }

    val pack: RoomPack? get() = roomsPack ?: null.also { load() }

    private fun load() {
        if (loading || System.currentTimeMillis() < retryAt) return
        loading = true
        CompletableFuture.runAsync {
            val dir = PackFiles.defaultDir()
            val report = PackReport()
            val pack = runCatching { RoomPack.load(PackSource.resolve(dir, BundledPacks.JAR, PackFiles.cacheRootFor(dir), report, listOf(PackFiles.ROOMS_INDEX, PackFiles.ROOMS_BLOCKS)), report) }
                .onFailure { report.error(it.toString()) }.getOrNull()
            ownPack = pack
            if (pack == null) {
                EngineerClient.logger.warn("[ec] Secret Waypoints couldn't load the rooms pack: ${report.errors().joinToString("; ")}")
                retryAt = System.currentTimeMillis() + 60_000L
                loading = false
            }
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
        val puzzle = room.data?.type == RoomType.PUZZLE
        for (s in d.sites) {
            // x sites are extras: in a puzzle they are the reward chest, and an x item on a chest is not a secret
            if (s.id.startsWith('x') && (puzzle || (s.type == SecretType.ITEM && onChest(s.pos, d.sites)))) continue
            val kind = when (s.type) {
                SecretType.CHEST, SecretType.LOCKED_CHEST -> Kind.CHEST
                SecretType.ITEM -> Kind.ITEM
                SecretType.ESSENCE -> Kind.ESSENCE
                SecretType.BAT -> Kind.BAT
                SecretType.KEY -> continue
            }
            out += spot(kind, s.pos, p, s.type == SecretType.LOCKED_CHEST)
        }
        for (l in d.levers) out += spot(Kind.LEVER, l.pos, p)
        d.key?.skulls?.forEach { out += spot(Kind.KEY, it.first, p) }
        placed[key] = out
        return out
    }

    private fun onChest(at: Pos, sites: List<SecretSite>): Boolean = sites.any {
        (it.type == SecretType.CHEST || it.type == SecretType.LOCKED_CHEST) && it.pos.x == at.x && it.pos.z == at.z && at.y - it.pos.y in 1..2
    }

    private fun spot(kind: Kind, c: Pos, p: Placement, locked: Boolean = false): Spot {
        val w = p.toWorld(c.x, c.y, c.z)
        val pos = BlockPos(w.x, w.y, w.z)
        val x = w.x.toDouble(); val y = w.y.toDouble(); val z = w.z.toDouble()
        val box = when (kind) {
            Kind.CHEST -> AABB(x + 0.0625, y, z + 0.0625, x + 0.9375, y + 0.875, z + 0.9375)
            Kind.ESSENCE, Kind.KEY -> AABB(x + 0.25, y, z + 0.25, x + 0.75, y + 0.5, z + 0.75)
            Kind.LEVER -> AABB(x + 0.25, y, z + 0.25, x + 0.75, y + 0.6, z + 0.75)
            Kind.ITEM -> AABB(x + 0.25, y, z + 0.25, x + 0.75, y + 0.5, z + 0.75)
            Kind.BAT -> AABB(x + 0.25, y + 0.25, z + 0.25, x + 0.75, y + 0.75, z + 0.75)
        }
        return Spot(kind, pos, box, locked)
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
