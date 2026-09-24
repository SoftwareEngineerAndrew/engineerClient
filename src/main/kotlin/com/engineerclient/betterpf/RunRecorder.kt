package com.engineerclient.betterpf

import com.engineerclient.EngineerClient
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.skyblock.Island
import com.odtheking.odin.utils.skyblock.LocationUtils
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.entity.SkullBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.status.ChunkStatus
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream

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
 * Disk writes happen on a single background thread; the client thread only builds strings.
 */
class RunRecorder(
    private val dir: Path,
    private val captureGeometry: Boolean,
    libraryKeys: () -> Set<String>? = { null },
    private val onSaved: (Path) -> Unit = {},
) {

    private var tick = 0
    private var confirmed = false
    var abandoned = false
        private set
    private val backlog = ArrayList<String>()

    private val io = Executors.newSingleThreadExecutor { Thread(it, "ec-betterpf-writer").apply { isDaemon = true } }
    private var writer: BufferedWriter? = null
    private var tempFile: Path? = null
    private var linesWritten = 0L

    private val startedAt = LocalDateTime.now()
    private var floorName: String? = null
    private var partyKey = ""

    // Entity tracking: last written position/name per entity id, to only write changes.
    private class Tracked(var x: Double, var y: Double, var z: Double, var yaw: Float, var name: String)
    private val tracked = HashMap<Int, Tracked>()

    // Geometry: rooms go to the server's room library once, gaps between rooms per run (GeometryCapture).
    private val geometry = GeometryCapture(::emit, libraryKeys)
    private val palette = HashMap<BlockState, Int>()

    // ------------------------------------------------------------------ inputs

    fun onTick(level: ClientLevel) {
        flushFrames()
        tick++
        if (!confirmed) {
            if (DungeonUtils.inDungeons) confirm()
            // Area is known a second or two after load; anything that is known and not a dungeon is dropped
            // right away rather than buffered for the full timeout.
            else if (tick > ABANDON_AFTER_TICKS || !LocationUtils.isCurrentArea(Island.Unknown)) { abandon(); return }
        }
        if (tick % 20 == 0) emit("""{"k":"time","t":$tick,"ms":${System.currentTimeMillis()}}""")
        recordFloorAndParty()
        if (tick % 10 == 0) recordRooms()
        recordPlayers(level)
        recordSwings(level)
        if (tick % 5 == 0) recordMapPlayers()
        recordEntities(level)
        if (confirmed && tick % 20 == 0) recordSkulls(level)
        if (confirmed && captureGeometry) geometry.tick(level, tick)
    }

    // Your own look direction every rendered frame, not just every tick: the mouse turns the camera
    // between ticks, so this is what you actually saw. Written once per tick as a "cam" line. Frames
    // where the view didn't move are left out, except the last one before it moves again, so a
    // replay holds still through the gap instead of drifting across it.
    private val frames = StringBuilder()
    private var lastFrameYaw = Float.NaN
    private var lastFramePitch = Float.NaN
    private var heldFrame: String? = null
    private var lastFrameNs = 0L

    fun onFrame(partialTick: Float, yaw: Float, pitch: Float) {
        // At most 60 a second, whatever the game's frame rate.
        val now = System.nanoTime()
        if (now - lastFrameNs < FRAME_NS) return
        lastFrameNs = now
        val entry = "[${f2(partialTick)},${f2(yaw)},${f2(pitch)}]"
        if (yaw == lastFrameYaw && pitch == lastFramePitch) { heldFrame = entry; return }
        heldFrame?.let { if (frames.isNotEmpty()) frames.append(','); frames.append(it) }
        heldFrame = null
        lastFrameYaw = yaw; lastFramePitch = pitch
        if (frames.isNotEmpty()) frames.append(',')
        frames.append(entry)
    }

    private fun flushFrames() {
        if (frames.isEmpty()) return
        emit("""{"k":"cam","t":$tick,"d":[$frames]}""")
        frames.setLength(0)
    }

    fun onBlockUpdate(pos: BlockPos, state: BlockState) {
        emit("""{"k":"block","t":$tick,"x":${pos.x},"y":${pos.y},"z":${pos.z},"s":${paletteIndex(state)}}""")
    }

    /** A chat line: plain [message], plus [colored] (with § formatting codes) when it has any formatting. */
    fun onChat(message: String, colored: String? = null) {
        val c = if (colored != null && colored != message) ",\"c\":${str(colored)}" else ""
        emit("""{"k":"chat","t":$tick,"m":${str(message)}$c}""")
    }

    /** A chest (or ender chest) lid event: [openCount] players now have it open (0 = it closes). */
    fun onChestEvent(pos: BlockPos, openCount: Int) =
        emit("""{"k":"bev","t":$tick,"x":${pos.x},"y":${pos.y},"z":${pos.z},"b":$openCount}""")

    fun onRoomEnter(name: String?) = emit("""{"k":"room","t":$tick,"name":${str(name ?: "Unknown")}}""")

    /** Your own container screens (terminals are GUIs with fixed titles): exact open/close times. */
    fun onGuiOpen(title: String) = emit("""{"k":"gui","t":$tick,"title":${str(title)}}""")
    fun onGuiClose() = emit("""{"k":"guiclose","t":$tick}""")

    /** Ends the session: closes the file (on the writer thread) and gives it its final name. */
    fun finish() {
        if (!confirmed) { abandon(); return }
        emit("""{"k":"end","t":$tick,"ms":${System.currentTimeMillis()}}""")
        val temp = tempFile ?: return
        val finalName = "${startedAt.format(STAMP)}_${(floorName ?: "unknown").replace(Regex("[^A-Za-z0-9]+"), "")}.jsonl.gz"
        val lines = linesWritten
        io.execute {
            try {
                writer?.close()
                val target = temp.resolveSibling(finalName)
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
                val mb = Files.size(target) / 1_000_000.0
                EngineerClient.chat("§8[§6EC§8]§7 Better PF: saved run §f$finalName §7(${String.format(Locale.ROOT, "%.1f", mb)} MB, $lines lines, ${tick / 20}s)")
                onSaved(target)
            } catch (t: Throwable) {
                EngineerClient.logger.error("[ec] betterpf: failed to finish run file", t)
            }
        }
        io.shutdown()
    }

    // ------------------------------------------------------------------ lifecycle

    private fun confirm() {
        confirmed = true
        Files.createDirectories(dir)
        val temp = dir.resolve("recording-${startedAt.format(STAMP)}.jsonl.gz.part")
        tempFile = temp
        writer = BufferedWriter(OutputStreamWriter(GZIPOutputStream(Files.newOutputStream(temp)), Charsets.UTF_8), 1 shl 16)
        val self = EngineerClient.mc.player?.name?.string ?: "?"
        val version = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("engineerclient")
            .map { it.metadata.version.friendlyString }.orElse("?")
        writeNow("""{"k":"meta","format":2,"mod":${str(version)},"mc":"26.1.2","self":${str(self)},"startMs":${System.currentTimeMillis() - tick * 50L},"confirmedAtTick":$tick,"geometry":$captureGeometry}""")
        backlog.forEach(::writeNow)
        backlog.clear()
        EngineerClient.chat("§8[§6EC§8]§7 Better PF: recording this run")
    }

    private fun abandon() {
        abandoned = true
        backlog.clear()
        tracked.clear()
        io.shutdown()
    }

    private fun emit(line: String) {
        if (abandoned) return
        if (confirmed) writeNow(line) else backlog.add(line)
    }

    private fun writeNow(line: String) {
        val w = writer ?: return
        linesWritten++
        io.execute {
            try { w.write(line); w.newLine() } catch (t: Throwable) { EngineerClient.logger.error("[ec] betterpf write failed", t) }
        }
    }

    // ------------------------------------------------------------------ per-tick snapshots

    private fun recordFloorAndParty() {
        val floor = DungeonUtils.floor?.name
        if (floor != null && floor != floorName) {
            floorName = floor
            emit("""{"k":"floor","t":$tick,"floor":${str(floor)}}""")
        }
        val party = DungeonUtils.dungeonTeammates
        val key = party.joinToString("|") { "${it.name}:${it.clazz.name}" }
        if (key != partyKey && party.isNotEmpty()) {
            partyKey = key
            emit("""{"k":"party","t":$tick,"m":[${party.joinToString(",") { "[${str(it.name)},${str(it.clazz.name)}]" }}]}""")
        }
    }

    private var roomsKey = ""

    /**
     * Odin's own room classification: every room it knows (from the dungeon map, and named once its
     * core has been seen), with type, shape, rotation and checkmark. Rewritten whenever any of that
     * changes, which is also how cleared/failed state shows up over time.
     */
    private fun recordRooms() {
        val rooms = DungeonScan.rooms
        if (rooms.isEmpty()) return
        val sb = StringBuilder()
        rooms.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            sb.append('[').append(str(r.name ?: "")).append(',').append(str(r.type.name)).append(',')
                .append(str(r.shape.name)).append(',').append(str(r.rotation?.name ?: "")).append(',')
                .append(str(r.checkmark.name)).append(",[")
            r.tiles.forEachIndexed { j, t -> if (j > 0) sb.append(','); sb.append('[').append(t.x).append(',').append(t.z).append(']') }
            // Secrets found / total. "Found" comes from the action bar (the room you're in) and other
            // Odin users, so it's a lower bound for rooms nobody running Odin is in.
            sb.append("],").append(r.foundSecrets ?: -1).append(',').append(r.data?.maxSecrets ?: -1).append(']')
        }
        val body = sb.toString()
        if (body == roomsKey) return
        roomsKey = body
        emit("""{"k":"rooms","t":$tick,"r":[$body]}""")
    }

    // Last written entry per player name; only players whose entry changed go in a "p" line.
    private val lastPlayer = HashMap<String, String>()

    // The skin of a player head someone holds (the leap item, for one), written when it changes.
    private val lastHeldHead = HashMap<String, String>()
    private fun recordHeldHead(p: Player, name: String) {
        val tex = p.mainHandItem.get(DataComponents.PROFILE)?.let { texturesOf(it.partialProfile().properties()) } ?: ""
        val prev = lastHeldHead.put(name, tex)
        if (prev != tex && !(prev == null && tex.isEmpty())) emit("""{"k":"held","t":$tick,"name":${str(name)},"tex":${str(tex)}}""")
    }

    private fun recordPlayers(level: ClientLevel) {
        val sb = StringBuilder()
        var changed = 0
        val seen = HashSet<String>()
        for (p in level.players()) {
            val name = p.name.string
            seen += name
            val held = p.mainHandItem.let { if (it.isEmpty) "" else it.itemId.ifEmpty { vanillaId(it) } }
            if (skinsWritten.add(name)) texturesOf(p.gameProfile.properties())?.let { emit("""{"k":"skin","t":$tick,"name":${str(name)},"tex":${str(it)}}""") }
            recordEquipment(p, "\"name\":${str(name)}", name)
            // [8] is the vanilla item (for drawing it); [6] the Skyblock id when there is one.
            val entry = "[${str(name)},${n(p.x)},${n(p.y)},${n(p.z)},${a(p.yRot)},${a(p.xRot)},${str(held)},${p.uuid.version()},${str(vanillaId(p.mainHandItem))}]"
            recordHeldHead(p, name)
            if (lastPlayer.put(name, entry) == entry) continue
            if (changed++ > 0) sb.append(',')
            sb.append(entry)
        }
        if (changed > 0) emit("""{"k":"p","t":$tick,"d":[$sb]}""")
        for (name in lastPlayer.keys.filter { it !in seen }) {
            lastPlayer.remove(name)
            emit("""{"k":"pgone","t":$tick,"name":${str(name)}}""")
        }
    }

    // Arm swings: a left click, or a right click that hit something (opening a terminal swings too).
    // Other players' swings arrive as animation packets; a new one shows as swinging with swingTime
    // at -1 or 0 (depending on whether the entity has ticked since), so -1 then 0 is one swing.
    private val lastSwingTime = HashMap<String, Int>()

    private fun recordSwings(level: ClientLevel) {
        val sb = StringBuilder()
        var count = 0
        for (p in level.players()) {
            val name = p.name.string
            val prev = lastSwingTime.put(name, if (p.swinging) p.swingTime else 99) ?: 99
            if (!p.swinging || p.swingTime > 0 || prev == -1) continue
            if (count++ > 0) sb.append(',')
            sb.append(str(name))
        }
        if (count > 0) emit("""{"k":"sw","t":$tick,"d":[$sb]}""")
    }

    // Teammates the game isn't rendering: where the dungeon map puts them (Odin decodes the map's
    // player markers), turned into world coordinates the way Odin's map draws them. Clear only -
    // the map shows the room grid, not the boss.
    private val lastMapPos = HashMap<String, String>()

    private fun recordMapPlayers() {
        if (!DungeonUtils.inDungeons || DungeonUtils.inBoss) return
        val sb = StringBuilder()
        var count = 0
        for (p in DungeonUtils.dungeonTeammatesNoSelf) {
            if (p.isDead || p.entity != null) { lastMapPos.remove(p.name); continue }
            val x = ((p.mapPos.x + 128) / 2.0 - DungeonScan.startX) * 32.0 / DungeonScan.roomGap - 200
            val z = ((p.mapPos.z + 128) / 2.0 - DungeonScan.startY) * 32.0 / DungeonScan.roomGap - 200
            val entry = "[${str(p.name)},${n(x)},${n(z)},${a(p.yaw)}]"
            if (lastMapPos.put(p.name, entry) == entry) continue
            if (count++ > 0) sb.append(',')
            sb.append(entry)
        }
        if (count > 0) emit("""{"k":"mp","t":$tick,"d":[$sb]}""")
    }

    private fun recordEntities(level: ClientLevel) {
        val seen = HashSet<Int>(tracked.size + 16)
        val moved = StringBuilder()
        var movedCount = 0
        for (e in level.entitiesForRendering()) {
            if (e is Player) continue
            val id = e.id
            seen += id
            val name = e.customName?.string ?: ""
            // The name with its colours (§ codes), when it has any: "c" next to the plain "name".
            val colored = e.customName?.let { BetterPF.legacyText(it) } ?: ""
            val c = if (colored.isNotEmpty() && colored != name) ",\"c\":${str(colored)}" else ""
            val t = tracked[id]
            if (t == null) {
                tracked[id] = Tracked(e.x, e.y, e.z, e.yRot, colored)
                // Falling blocks carry which block they are, so the viewer can draw it.
                val block = (e as? FallingBlockEntity)?.let { ",\"block\":" + str(BlockStateParser.serialize(it.blockState)) } ?: ""
                emit("""{"k":"spawn","t":$tick,"id":$id,"type":${str(typeOf(e))},"name":${str(name)}$c,"x":${n(e.x)},"y":${n(e.y)},"z":${n(e.z)},"yaw":${a(e.yRot)}$block}""")
                if (e is ArmorStand) recordStand(e)
                continue
            }
            if (e is LivingEntity) recordEquipment(e, "\"id\":$id", "#$id")
            if (e is ArmorStand) recordStand(e)
            if (colored != t.name) {
                t.name = colored
                emit("""{"k":"name","t":$tick,"id":$id,"name":${str(name)}$c}""")
            }
            if (e.x != t.x || e.y != t.y || e.z != t.z || e.yRot != t.yaw) {
                t.x = e.x; t.y = e.y; t.z = e.z; t.yaw = e.yRot
                if (movedCount++ > 0) moved.append(',')
                moved.append('[').append(id).append(',').append(n(e.x)).append(',').append(n(e.y)).append(',')
                    .append(n(e.z)).append(',').append(a(e.yRot)).append(']')
            }
        }
        if (movedCount > 0) emit("""{"k":"e","t":$tick,"d":[$moved]}""")
        val gone = tracked.keys.filter { it !in seen }
        for (id in gone) {
            tracked.remove(id)
            lastEquipment.remove("#$id")
            lastStand.remove(id)
            emit("""{"k":"gone","t":$tick,"id":$id}""")
        }
    }

    // Players' skins (their profile's "textures" property, base64) once per name.
    private val skinsWritten = HashSet<String>()

    // Held item and armour per player name / "#entityId", written when it changes: vanilla ids, plus
    // the head item's skin texture when it's a player head (dungeon mobs wear those).
    private val lastEquipment = HashMap<String, String>()

    // Armor stands: size, visibility, arms/base plate and their pose (Hypixel poses them for heads,
    // held items and nametags), written when any of it changes.
    private val lastStand = HashMap<Int, String>()

    private fun recordStand(e: ArmorStand) {
        val f = (if (e.isSmall) 1 else 0) or (if (e.isInvisible) 2 else 0) or (if (e.showArms()) 4 else 0) or
            (if (!e.showBasePlate()) 8 else 0) or (if (e.isMarker) 16 else 0)
        val pose = listOf(e.headPose, e.bodyPose, e.leftArmPose, e.rightArmPose, e.leftLegPose, e.rightLegPose)
            .joinToString(",") { "${a(it.x())},${a(it.y())},${a(it.z())}" }
        val body = """"f":$f,"pose":[$pose]"""
        if (lastStand.put(e.id, body) == body) return
        emit("""{"k":"stand","t":$tick,"id":${e.id},$body}""")
    }

    // Player heads placed as blocks (skulls on walls, floors, the boss arena): their skin, once per
    // position and again if it changes. The viewer would otherwise draw a default head.
    private val lastSkull = HashMap<BlockPos, String>()

    private fun recordSkulls(level: ClientLevel) {
        val player = EngineerClient.mc.player ?: return
        val cx = player.blockPosition().x shr 4
        val cz = player.blockPosition().z shr 4
        for (x in cx - SKULL_CHUNKS..cx + SKULL_CHUNKS) for (z in cz - SKULL_CHUNKS..cz + SKULL_CHUNKS) {
            val chunk = level.chunkSource.getChunk(x, z, ChunkStatus.FULL, false) ?: continue
            for (be in chunk.blockEntities.values) {
                if (be !is SkullBlockEntity) continue
                val tex = be.ownerProfile?.let { texturesOf(it.partialProfile().properties()) } ?: continue
                val pos = be.blockPos
                if (lastSkull.put(pos.immutable(), tex) == tex) continue
                emit("""{"k":"skull","t":$tick,"x":${pos.x},"y":${pos.y},"z":${pos.z},"tex":${str(tex)}}""")
            }
        }
    }

    private fun recordEquipment(e: LivingEntity, who: String, key: String) {
        val head = e.getItemBySlot(EquipmentSlot.HEAD)
        val items = listOf(e.mainHandItem, head, e.getItemBySlot(EquipmentSlot.CHEST), e.getItemBySlot(EquipmentSlot.LEGS), e.getItemBySlot(EquipmentSlot.FEET))
        val headTex = head.get(DataComponents.PROFILE)?.let { texturesOf(it.partialProfile().properties()) }
        val body = items.joinToString(",", "[", "]") { str(vanillaId(it)) } + (headTex?.let { ",\"headTex\":${str(it)}" } ?: "")
        val previous = lastEquipment.put(key, body)
        if (previous == body || (previous == null && body == NO_EQUIPMENT)) return
        emit("""{"k":"eq","t":$tick,$who,"eq":$body}""")
    }

    /** The game item id, plus "#rrggbb" for dyed items (leather armour) so the viewer can colour it. */
    private fun vanillaId(stack: ItemStack): String {
        if (stack.isEmpty) return ""
        // The item it looks like: Hypixel builds many items on a base item with another item's model
        // (paper that is drawn as TNT), so a vanilla item_model wins over the base item.
        val model = stack.get(DataComponents.ITEM_MODEL)?.takeIf { it.namespace == "minecraft" }?.toString()
        val id = model ?: BuiltInRegistries.ITEM.getKey(stack.item).toString()
        val dye = stack.get(DataComponents.DYED_COLOR) ?: return id
        return id + "#" + String.format(Locale.ROOT, "%06x", dye.rgb() and 0xFFFFFF)
    }

    private fun texturesOf(props: com.mojang.authlib.properties.PropertyMap): String? = props.get("textures").firstOrNull()?.value()

    // ------------------------------------------------------------------ block palette (for "block" change lines)

    private fun paletteIndex(state: BlockState): Int = palette.getOrPut(state) {
        val i = palette.size
        emit("""{"k":"pal","i":$i,"s":${str(BlockStateParser.serialize(state))}}""")
        i
    }

    // ------------------------------------------------------------------ formatting

    private fun typeOf(e: Entity): String = BuiltInRegistries.ENTITY_TYPE.getKey(e.type).toString()

    private fun n(v: Double) = String.format(Locale.ROOT, "%.3f", v)
    private fun a(v: Float) = String.format(Locale.ROOT, "%.1f", v)
    private fun f2(v: Float) = String.format(Locale.ROOT, "%.2f", v)

    private fun str(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (ch in s) when {
            ch == '"' -> sb.append("\\\"")
            ch == '\\' -> sb.append("\\\\")
            ch == '\n' -> sb.append("\\n")
            ch < ' ' -> sb.append(String.format(Locale.ROOT, "\\u%04x", ch.code))
            else -> sb.append(ch)
        }
        return sb.append('"').toString()
    }

    private companion object {
        const val ABANDON_AFTER_TICKS = 20 * 60
        // A little under 1/60 s, so a game running at 60 fps with uneven frame times keeps every frame.
        const val FRAME_NS = 16_000_000L
        const val NO_EQUIPMENT = """["","","","",""]"""
        // How far around you (in chunks) placed player heads are looked for, every second.
        const val SKULL_CHUNKS = 12
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    }
}
