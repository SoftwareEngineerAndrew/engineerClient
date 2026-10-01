package com.engineerclient.betterpf

import com.engineerclient.EngineerClient
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.skyblock.Island
import com.odtheking.odin.utils.skyblock.LocationUtils
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ResolvableProfile
import net.minecraft.world.level.block.entity.SkullBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.status.ChunkStatus
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.IdentityHashMap

/**
 * One recording session: everything that happens in one world, from the moment it loads.
 *
 * Format: gzipped JSON Lines, one event per line, every line has "k" (kind) and, for anything that
 * happens during the run, "t" (ticks since the world loaded). See tools/betterpf-viewer/FORMAT.md.
 *
 * Nothing is kept unless the world turns out to be a dungeon: until [DungeonUtils.inDungeons]
 * reports true, lines pile up in memory; the moment it does, the file is opened and the backlog
 * flushed, so the recording still starts at instance load. If Odin works out the area is something else, or a minute passes without it, the
 * session is dropped - a hub or island visit costs nothing on disk.
 *
 * Threads: this class runs on the game thread and only captures. It copies what each line needs out
 * of the game - numbers, strings, and references to objects the game never changes once made (items,
 * block states, armour stand poses) - and hands each tick's worth to [RunWriter] in one lock-free
 * insert. Formatting, working out what changed, JSON, compression and the file all happen on the
 * writer's thread, so recording can't hold up a frame, and lines come out exactly as before. Names
 * and skins can only be read here, but only change when the game swaps in a new name/profile object,
 * so each is read once per change rather than every tick.
 */
class RunRecorder(
    dir: Path,
    private val captureGeometry: Boolean,
    libraryKeys: () -> Set<String>? = { null },
    onSaved: (Path) -> Unit = {},
) : RecorderApi {

    private var tick = 0
    private var confirmed = false
    override var abandoned = false
        private set
    private var closed = false

    private val out = RunWriter(dir, LocalDateTime.now(), captureGeometry, onSaved)
    private var pending = ArrayList<WriterTask>(64)

    // Geometry: rooms go to the server's room library once, gaps between rooms per run (GeometryCapture).
    private val geometry = GeometryCapture(libraryKeys, ::post)

    /** Queues a task for the writer. Tasks capture copies only - never this recorder's fields. */
    private fun post(task: WriterTask) {
        if (!abandoned && !closed) pending.add(task)
    }

    /** Hands everything queued since the last tick to the writer. */
    private fun publish() {
        if (pending.isEmpty()) return
        out.submit(pending)
        pending = ArrayList(64)
    }

    // ------------------------------------------------------------------ inputs

    override fun onTick(level: ClientLevel) {
        if (closed) return
        flushFrames()
        flushMouse()
        tick++
        if (!confirmed) {
            if (DungeonUtils.inDungeons) confirm()
            // Area is known a second or two after load; anything that is known and not a dungeon is dropped
            // right away rather than buffered for the full timeout.
            else if (tick > ABANDON_AFTER_TICKS || !LocationUtils.isCurrentArea(Island.Unknown)) { abandon(); return }
        }
        val t = tick
        if (t % 20 == 0) { val ms = System.currentTimeMillis(); post { it.time(t, ms) } }
        if (serverTicks != lastServerTicks) { lastServerTicks = serverTicks; val n = serverTicks; post { it.serverTicks(t, n) } }
        captureEther(t)
        captureFloorAndParty(t)
        if (t % 10 == 0) captureRooms(t)
        capturePlayers(level, t)
        captureHotbar(t)
        captureSwings(level, t)
        if (t % 5 == 0) captureMapPlayers(t)
        captureEntities(level, t)
        if (confirmed && t % 20 == 0) captureSkulls(level, t)
        if (confirmed && captureGeometry) geometry.tick(level, t)
        publish()
    }

    // Your own look direction every rendered frame, not just every tick: the mouse turns the camera
    // between ticks, so this is what you actually saw. Written once per tick as a "cam" line. Frames
    // where the view didn't move are left out, except the last one before it moves again, so a
    // replay holds still through the gap instead of drifting across it.
    private val frames = FrameBuffer()
    private var lastFrameYaw = Float.NaN
    private var lastFramePitch = Float.NaN
    private var lastFrameNs = 0L

    override fun onFrame(partialTick: Float, yaw: Float, pitch: Float) {
        // At most 60 a second, whatever the game's frame rate.
        val now = System.nanoTime()
        if (now - lastFrameNs < FRAME_NS) return
        lastFrameNs = now
        if (yaw == lastFrameYaw && pitch == lastFramePitch) { frames.hold(partialTick, yaw, pitch); return }
        lastFrameYaw = yaw; lastFramePitch = pitch
        frames.add(partialTick, yaw, pitch)
    }

    private fun flushFrames() {
        val d = frames.take() ?: return
        val t = tick
        post { it.cam(t, d) }
    }

    override fun onBlockUpdate(pos: BlockPos, state: BlockState) {
        val t = tick; val x = pos.x; val y = pos.y; val z = pos.z
        post { it.block(t, x, y, z, state) }
    }

    // ------------------------------------------------------------------ server ticks
    // The server's own tick count (Odin's per-tick ping), written when it moved: split and tick
    // timers count these, and they fall behind the client's ticks when the server lags.
    private var serverTicks = 0
    private var lastServerTicks = 0
    override fun onServerTick() { serverTicks++ }

    // ------------------------------------------------------------------ what you do
    /** A container slot click you sent (any window, any way: mouse, Odin's terminal GUI, keys). */
    override fun onSlotClick(slot: Int, button: Int, type: String) {
        val t = tick
        post { it.slotClick(t, slot, button, type) }
    }

    /** A block you right-clicked (chests, levers, secrets...). */
    override fun onBlockUse(pos: BlockPos) {
        val t = tick; val x = pos.x; val y = pos.y; val z = pos.z
        post { it.blockUse(t, x, y, z) }
    }

    /**
     * A teleport the server put you through, where it put you (absolute); rel lists any parts the
     * packet gave relative to where you were, when that couldn't be worked out.
     */
    override fun onTeleport(x: Double, y: Double, z: Double, yaw: Float, pitch: Float, rel: List<String>) {
        val t = tick; val r = rel.toList()
        post { it.teleport(t, x, y, z, yaw, pitch, r) }
    }

    /** A bat hurt or killed (the sound's position and volume: secret bats squeak at 0.1). */
    override fun onBatSound(x: Double, y: Double, z: Double, volume: Float) {
        val t = tick
        post { it.batSound(t, x, y, z, volume) }
    }

    /** An item entity picked up, and by whom (a player's name, else the collector's entity id). */
    override fun onPickup(itemId: Int, collectorId: Int) {
        val t = tick
        val name = (EngineerClient.mc.level?.getEntity(collectorId) as? Player)?.name?.string
        post { it.pickup(t, itemId, collectorId, name) }
    }

    /** A chat line: plain [message], plus [colored] (with § formatting codes) when it has any formatting. */
    override fun onChat(message: String, colored: String?) {
        val t = tick
        post { it.chat(t, message, colored) }
    }

    /** A chest (or ender chest) lid event: [openCount] players now have it open (0 = it closes). */
    override fun onChestEvent(pos: BlockPos, openCount: Int) {
        val t = tick; val x = pos.x; val y = pos.y; val z = pos.z
        post { it.chestEvent(t, x, y, z, openCount) }
    }

    override fun onRoomEnter(name: String?) {
        val t = tick
        post { it.roomEnter(t, name) }
    }

    // Your held item's etherwarp: whether it has Etherwarp merged and how many Transmission Tuners
    // (each +1 block of range), written when it changes. CustomData never changes once made, so the
    // same object means the same values: only a different one is read.
    private var lastEtherData: Any? = UNSET

    private fun captureEther(t: Int) {
        val data = EngineerClient.mc.player?.mainHandItem?.get(DataComponents.CUSTOM_DATA)
        if (data === lastEtherData) return
        lastEtherData = data
        val tag = data?.copyTag()
        val merge = tag?.getIntOr("ethermerge", 0) ?: 0
        val tuners = tag?.getIntOr("tuned_transmission", 0) ?: 0
        post { it.ether(t, merge, tuners) }
    }

    /** Your own container screens (terminals are GUIs with fixed titles): exact open/close times. */
    override fun onGuiClose() {
        flushMouse()
        lastMouseX = Float.NaN
        val t = tick
        post { it.guiClose(t) }
    }

    // ------------------------------------------------------------------ what's in your open container
    // So the viewer can redraw the window you had open: its layout (on the gui line), every slot's
    // item (changes only), the item on your cursor, the mouse at every frame (up to 60 a second,
    // like the camera) and your clicks. Positions are GUI pixels from the window's top-left.

    /** A container screen opened: its menu type ("inventory" for your own), size and slot positions. */
    override fun onContainerOpen(title: String, menu: String, w: Int, h: Int, slots: List<IntArray>) {
        lastMouseX = Float.NaN
        val t = tick; val s = slots.toList()
        post { it.guiOpen(t, title, menu, w, h, s) }
    }

    // Per slot: the last name object seen and its plain text, so a name is only read when it changes.
    private var slotNameObj = arrayOfNulls<Component>(0)
    private var slotName = arrayOfNulls<String>(0)

    /** Each tick while a container is open: every slot's item and the cursor's (the writer keeps only changes). */
    override fun onContainerTick(items: List<ItemStack>, carried: ItemStack) {
        if (slotNameObj.size < items.size) {
            slotNameObj = slotNameObj.copyOf(items.size)
            slotName = slotName.copyOf(items.size)
        }
        val slots = Array(items.size) { i -> slotSnap(i, items[i]) }
        val c = ItemSnap.of(carried)
        val count = if (carried.isEmpty) 0 else carried.count
        val t = tick
        post { it.containerTick(t, slots, c, count) }
    }

    private fun slotSnap(i: Int, stack: ItemStack): SlotSnap {
        // [index, id, count, head skin or "", plain name, glint 0/1] - terminals are solved by names
        // and Hypixel marks clicked items with the enchantment glint.
        val tex = profileTex(stack.get(DataComponents.PROFILE))
        if (stack.isEmpty) return if (tex == null) SlotSnap.EMPTY else SlotSnap(ItemSnap.EMPTY, 0, tex, "", 0)
        val nameObj = stack.hoverName
        val name = if (slotNameObj[i] === nameObj) slotName[i]!!
        else nameObj.string.replace(FORMAT_CODES, "").also { slotNameObj[i] = nameObj; slotName[i] = it }
        val glint = if (stack.has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE) || stack.hasFoil()) 1 else 0
        return SlotSnap(ItemSnap.of(stack), stack.count, tex, name, glint)
    }

    private val mouse = FrameBuffer()
    private var lastMouseNs = 0L
    private var lastMouseX = Float.NaN
    private var lastMouseY = Float.NaN

    /** The mouse over the open container, per rendered frame (60 a second at most); still frames skipped. */
    override fun onContainerMouse(partialTick: Float, x: Float, y: Float) {
        val now = System.nanoTime()
        if (now - lastMouseNs < FRAME_NS) return
        lastMouseNs = now
        if (x == lastMouseX && y == lastMouseY) { mouse.hold(partialTick, x, y); return }
        lastMouseX = x; lastMouseY = y
        mouse.add(partialTick, x, y)
    }

    private fun flushMouse() {
        val d = mouse.take() ?: return
        val t = tick
        post { it.mouse(t, d) }
    }

    override fun onContainerClick(x: Float, y: Float, button: Int) {
        val t = tick
        post { it.click(t, x, y, button) }
    }

    /** Ends the session: the writer closes the file and gives it its final name, off this thread. */
    override fun finish() {
        if (closed || abandoned) return
        if (!confirmed) { abandon(); return }
        val t = tick; val ms = System.currentTimeMillis()
        post { it.finish(t, ms) }
        publish()
        closed = true
    }

    // ------------------------------------------------------------------ lifecycle

    private fun confirm() {
        confirmed = true
        val self = EngineerClient.mc.player?.name?.string ?: "?"
        val version = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("engineerclient")
            .map { it.metadata.version.friendlyString }.orElse("?")
        val startMs = System.currentTimeMillis() - tick * 50L
        val t = tick
        post { it.confirm(self, version, startMs, t) }
    }

    private fun abandon() {
        pending.clear() // the writer would drop it anyway
        post { it.abandon() }
        publish()
        abandoned = true
        names.clear()
    }

    // ------------------------------------------------------------------ per-tick snapshots

    private fun captureFloorAndParty(t: Int) {
        val floor = DungeonUtils.floor?.name
        val party = DungeonUtils.dungeonTeammates.toList()
        val names = Array(party.size) { party[it].name }
        val classes = Array(party.size) { party[it].clazz.name }
        post { it.floorAndParty(t, floor, names, classes) }
    }

    /**
     * Odin's own room classification: every room it knows (from the dungeon map, and named once its
     * core has been seen), with type, shape, rotation and checkmark. Rewritten whenever any of that
     * changes, which is also how cleared/failed state shows up over time.
     */
    private fun captureRooms(t: Int) {
        val rooms = DungeonScan.rooms
        if (rooms.isEmpty()) return
        val snaps = rooms.map { r ->
            val tiles = r.tiles.toList()
            RoomSnap(
                r.name ?: "", r.type.name, r.shape.name, r.rotation?.name ?: "", r.checkmark.name,
                IntArray(tiles.size * 2) { j -> if (j % 2 == 0) tiles[j / 2].x else tiles[j / 2].z },
                // Secrets found / total. "Found" comes from the action bar (the room you're in) and other
                // Odin users, so it's a lower bound for rooms nobody running Odin is in.
                r.foundSecrets ?: -1, r.data?.maxSecrets ?: -1,
                // [8]: the room's library key when it's known (rotation found, variant told apart).
                RoomKeys.key(r),
            )
        }
        post { it.rooms(t, snaps) }
    }

    // Players' skins (their profile's "textures" property, base64) once per name.
    private val skinsWritten = HashSet<String>()

    private fun capturePlayers(level: ClientLevel, t: Int) {
        val players = level.players()
        val snaps = ArrayList<PlayerSnap>(players.size)
        for (p in players) {
            val name = p.name.string
            val skin = if (skinsWritten.add(name)) texturesOf(p.gameProfile.properties()) else null
            val main = p.mainHandItem
            snaps += PlayerSnap(
                name, skin, equipment(p),
                p.x, p.y, p.z, p.yRot, p.xRot,
                if (main.isEmpty) "" else main.itemId,
                p.uuid.version(),
                p.isCrouching,
                // The skin of a player head someone holds (the leap item, for one).
                profileTex(main.get(DataComponents.PROFILE)) ?: "",
            )
        }
        post { it.players(t, snaps) }
    }

    // Your own hotbar: the nine items, which slot is selected, and the skins of any player heads in it.
    private fun captureHotbar(t: Int) {
        val inv = EngineerClient.mc.player?.inventory ?: return
        val items = Array(9) { ItemSnap.of(inv.getItem(it)) }
        val tex = Array(9) { profileTex(inv.getItem(it).get(DataComponents.PROFILE)) }
        val selected = inv.selectedSlot
        post { it.hotbar(t, items, tex, selected) }
    }

    // Arm swings: a left click, or a right click that hit something (opening a terminal swings too).
    // Other players' swings arrive as animation packets; a new one shows as swinging with swingTime
    // at -1 or 0 (depending on whether the entity has ticked since), so -1 then 0 is one swing.
    private fun captureSwings(level: ClientLevel, t: Int) {
        val players = level.players()
        val names = Array(players.size) { players[it].name.string }
        val swinging = BooleanArray(players.size) { players[it].swinging }
        val swingTime = IntArray(players.size) { players[it].swingTime }
        post { it.swings(t, names, swinging, swingTime) }
    }

    // Teammates the game isn't rendering: where the dungeon map puts them (Odin decodes the map's
    // player markers), turned into world coordinates the way Odin's map draws them. Clear only -
    // the map shows the room grid, not the boss.
    private fun captureMapPlayers(t: Int) {
        if (!DungeonUtils.inDungeons || DungeonUtils.inBoss) return
        val snaps = ArrayList<MapSnap>()
        for (p in DungeonUtils.dungeonTeammatesNoSelf) {
            if (p.isDead || p.entity != null) { snaps += MapSnap(p.name, false, 0.0, 0.0, 0f); continue }
            val x = ((p.mapPos.x + 128) / 2.0 - DungeonScan.startX) * 32.0 / DungeonScan.roomGap - 200
            val z = ((p.mapPos.z + 128) / 2.0 - DungeonScan.startY) * 32.0 / DungeonScan.roomGap - 200
            snaps += MapSnap(p.name, true, x, z, p.yaw)
        }
        post { it.mapPlayers(t, snaps) }
    }

    // Per entity id: its name object last tick and the strings read from it (plain and with § colour
    // codes), and the same for a dropped item's name. Entries not seen in a tick are pruned each second.
    private class Names(var nameObj: Component?, var name: String, var colored: String, var seen: Int) {
        var itemNameObj: Component? = null
        var itemName = ""
    }
    private val names = HashMap<Int, Names>()

    private fun captureEntities(level: ClientLevel, t: Int) {
        val snaps = ArrayList<EntitySnap>(names.size + 16)
        for (e in level.entitiesForRendering()) {
            if (e is Player) continue
            // An entity the game hasn't placed yet (NaN position) would make an unreadable line.
            if (!e.x.isFinite() || !e.y.isFinite() || !e.z.isFinite()) continue
            val id = e.id
            val nameObj = e.customName
            val n = names[id]?.takeIf { it.nameObj === nameObj }
                // The name with its colours (§ codes), when it has any: "c" next to the plain "name".
                ?: Names(nameObj, nameObj?.string ?: "", nameObj?.let { BetterPF.legacyText(it) } ?: "", t).also { names[id] = it }
            n.seen = t
            val living = e as? LivingEntity
            // Mobs turn their heads apart from their bodies (the way they look at you).
            val headYaw = living?.yHeadRot ?: e.yRot
            var itemSnap = ItemSnap.EMPTY
            if (e is ItemEntity) {
                // Dropped items say what they are (secret items: Decoys, Spirit Leaps...).
                val stack = e.item
                val itemNameObj = stack.hoverName
                if (n.itemNameObj !== itemNameObj) { n.itemNameObj = itemNameObj; n.itemName = itemNameObj.string.replace(FORMAT_CODES, "") }
                itemSnap = ItemSnap.of(stack)
            }
            val frame = e as? ItemFrame
            snaps += EntitySnap(
                id, e.type, e.x, e.y, e.z, e.yRot, headYaw,
                living != null, living?.isBaby == true,
                n.name, n.colored,
                // Falling blocks carry which block they are, so the viewer can draw it.
                (e as? FallingBlockEntity)?.blockState,
                e is ItemEntity, n.itemName, itemSnap,
                (e as? ArmorStand)?.let(::standSnap),
                frame?.let { ItemSnap.of(it.item) }, frame?.rotation ?: 0,
                living?.let(::equipment),
            )
        }
        if (t % 20 == 0) names.values.removeIf { it.seen != t }
        post { it.entities(t, snaps) }
    }

    // Armor stands: size, visibility, arms/base plate and their pose (Hypixel poses them for heads,
    // held items and nametags). The poses are immutable, so they go to the writer as they are.
    private fun standSnap(e: ArmorStand): StandSnap {
        val flags = (if (e.isSmall) 1 else 0) or (if (e.isInvisible) 2 else 0) or (if (e.showArms()) 4 else 0) or
            (if (!e.showBasePlate()) 8 else 0) or (if (e.isMarker) 16 else 0)
        return StandSnap(flags, arrayOf(e.headPose, e.bodyPose, e.leftArmPose, e.rightArmPose, e.leftLegPose, e.rightLegPose))
    }

    // Held item and armour: vanilla ids, plus the head item's skin texture when it's a player head
    // (dungeon mobs wear those).
    private fun equipment(e: LivingEntity): EquipSnap {
        val head = e.getItemBySlot(EquipmentSlot.HEAD)
        return EquipSnap(
            arrayOf(
                ItemSnap.of(e.mainHandItem), ItemSnap.of(head), ItemSnap.of(e.getItemBySlot(EquipmentSlot.CHEST)),
                ItemSnap.of(e.getItemBySlot(EquipmentSlot.LEGS)), ItemSnap.of(e.getItemBySlot(EquipmentSlot.FEET)),
            ),
            profileTex(head.get(DataComponents.PROFILE)),
        )
    }

    // Player heads placed as blocks (skulls on walls, floors, the boss arena): their skin, once per
    // position and again if it changes. The viewer would otherwise draw a default head.
    private fun captureSkulls(level: ClientLevel, t: Int) {
        val player = EngineerClient.mc.player ?: return
        val cx = player.blockPosition().x shr 4
        val cz = player.blockPosition().z shr 4
        val found = ArrayList<SkullSnap>()
        for (x in cx - SKULL_CHUNKS..cx + SKULL_CHUNKS) for (z in cz - SKULL_CHUNKS..cz + SKULL_CHUNKS) {
            val chunk = level.chunkSource.getChunk(x, z, ChunkStatus.FULL, false) ?: continue
            for (be in chunk.blockEntities.values) {
                if (be !is SkullBlockEntity) continue
                val tex = profileTex(be.ownerProfile) ?: continue
                val pos = be.blockPos
                found += SkullSnap(pos.x, pos.y, pos.z, tex)
            }
        }
        post { it.skulls(t, found) }
    }

    // A profile's "textures" property, per profile object: profiles never change once made and are
    // shared by every copy of a stack, so each is read once. Bounded by clearing when it gets big.
    private val profileTexCache = IdentityHashMap<ResolvableProfile, Any>()

    private fun profileTex(profile: ResolvableProfile?): String? {
        if (profile == null) return null
        profileTexCache[profile]?.let { return if (it === NO_TEX) null else it as String }
        if (profileTexCache.size >= PROFILE_CACHE_MAX) profileTexCache.clear()
        val tex = texturesOf(profile.partialProfile().properties())
        profileTexCache[profile] = tex ?: NO_TEX
        return tex
    }

    private fun texturesOf(props: com.mojang.authlib.properties.PropertyMap): String? = props.get("textures").firstOrNull()?.value()

    /**
     * Frames ([partialTick, a, b] triples) since the last tick, with the "held" still frame: the last
     * frame before the view moves again, written ahead of the frame that moves.
     */
    private class FrameBuffer {
        private var data = FloatArray(96)
        private var size = 0
        private var held = false
        private var h0 = 0f
        private var h1 = 0f
        private var h2 = 0f

        fun hold(a: Float, b: Float, c: Float) { held = true; h0 = a; h1 = b; h2 = c }

        fun add(a: Float, b: Float, c: Float) {
            if (held) { put(h0, h1, h2); held = false }
            put(a, b, c)
        }

        private fun put(a: Float, b: Float, c: Float) {
            if (size + 3 > data.size) data = data.copyOf(data.size * 2)
            data[size++] = a; data[size++] = b; data[size++] = c
        }

        /** The frames so far (null if none), leaving the buffer empty; a held frame stays held. */
        fun take(): FloatArray? {
            if (size == 0) return null
            val d = data.copyOf(size)
            size = 0
            return d
        }
    }

    private companion object {
        const val ABANDON_AFTER_TICKS = 20 * 60
        private val FORMAT_CODES = Regex("§.")
        // A little under 1/60 s, so a game running at 60 fps with uneven frame times keeps every frame.
        const val FRAME_NS = 16_000_000L
        // How far around you (in chunks) placed player heads are looked for, every second.
        const val SKULL_CHUNKS = 12
        const val PROFILE_CACHE_MAX = 4096
        val UNSET = Any()
        val NO_TEX = Any()
    }
}
