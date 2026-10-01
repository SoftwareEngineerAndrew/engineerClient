package com.engineerclient.betterpf

import com.engineerclient.EngineerClient
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.block.state.BlockState
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.LockSupport
import java.util.zip.GZIPOutputStream

/** One piece of a tick's work, run on the writer thread against [RunWriter]'s state. */
internal typealias WriterTask = (RunWriter) -> Unit

/**
 * The writer half of a [RunRecorder] session, on a thread of its own: everything that turns what the
 * game thread captured into lines - formatting, comparing with what was last written so only changes
 * go out, the JSON - plus the backlog kept until the world turns out to be a dungeon, compression
 * and the file itself. None of it runs on the game thread, so none of it can hold up a frame.
 *
 * The game thread hands over one tick's tasks at a time with [submit], a lock-free insert that never
 * waits; this thread polls. Tasks run in the order they were made, so lines come out in the same
 * order, with the same content, as when they were built on the game thread.
 */
internal class RunWriter(
    private val dir: Path,
    private val startedAt: LocalDateTime,
    private val captureGeometry: Boolean,
    private val onSaved: (Path) -> Unit,
) {

    // ------------------------------------------------------------------ thread

    private val queue = ConcurrentLinkedQueue<List<WriterTask>>()
    private var stopped = false
    private var failures = 0

    /** Hands over one tick's tasks. Never blocks: a lock-free insert, and the writer thread polls. */
    fun submit(tasks: List<WriterTask>) {
        queue.offer(tasks)
    }

    private fun loop() {
        while (true) {
            val tasks = queue.poll()
            if (tasks == null) {
                if (stopped) return
                LockSupport.parkNanos(IDLE_NS)
                continue
            }
            for (task in tasks) {
                if (stopped) return
                try {
                    task(this)
                } catch (t: Throwable) {
                    fail("line", t)
                }
            }
        }
    }

    /** Logs the first few failures only: a full disk would otherwise log once per line. */
    private fun fail(what: String, t: Throwable) {
        if (failures++ < MAX_LOGGED_FAILURES) EngineerClient.logger.error("[ec] betterpf $what failed", t)
    }

    // ------------------------------------------------------------------ file

    private var confirmed = false
    private val backlog = ArrayList<String>()
    private var writer: BufferedWriter? = null
    private var tempFile: Path? = null
    private var linesWritten = 0L
    private var floorName: String? = null

    private fun emit(line: String) {
        if (confirmed) writeNow(line) else backlog.add(line)
    }

    private fun writeNow(line: String) {
        val w = writer ?: return
        linesWritten++
        try {
            w.write(line)
            w.newLine()
        } catch (t: Throwable) {
            fail("write", t)
        }
    }

    /** The world is a dungeon: open the file, write the meta line and everything kept so far. */
    fun confirm(self: String, version: String, startMs: Long, t: Int) {
        confirmed = true
        try {
            Files.createDirectories(dir)
            val temp = dir.resolve("recording-${startedAt.format(STAMP)}.jsonl.gz.part")
            tempFile = temp
            writer = BufferedWriter(OutputStreamWriter(GZIPOutputStream(Files.newOutputStream(temp)), Charsets.UTF_8), 1 shl 16)
        } catch (e: Throwable) {
            fail("opening the run file", e)
        }
        writeNow("""{"k":"meta","format":2,"mod":${str(version)},"mc":"26.1.2","self":${str(self)},"startMs":$startMs,"confirmedAtTick":$t,"geometry":$captureGeometry}""")
        backlog.forEach(::writeNow)
        backlog.clear()
        if (writer != null) EngineerClient.chat("§8[§6EC§8]§7 Better PF: recording this run")
    }

    /** Not a dungeon: drop everything and stop. */
    fun abandon() {
        backlog.clear()
        tracked.clear()
        stopped = true
    }

    /** Ends the session: the end line, then the file is closed and given its final name. */
    fun finish(t: Int, ms: Long) {
        emit("""{"k":"end","t":$t,"ms":$ms}""")
        stopped = true
        val temp = tempFile ?: return
        val finalName = "${startedAt.format(STAMP)}_${(floorName ?: "unknown").replace(Regex("[^A-Za-z0-9]+"), "")}.jsonl.gz"
        val lines = linesWritten
        try {
            writer?.close()
            val target = temp.resolveSibling(finalName)
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            val mb = Files.size(target) / 1_000_000.0
            EngineerClient.chat("§8[§6EC§8]§7 Better PF: saved run §f$finalName §7(${String.format(Locale.ROOT, "%.1f", mb)} MB, $lines lines, ${t / 20}s)")
            onSaved(target)
        } catch (e: Throwable) {
            EngineerClient.logger.error("[ec] betterpf: failed to finish run file", e)
        }
    }

    // ------------------------------------------------------------------ simple lines

    fun time(t: Int, ms: Long) = emit("""{"k":"time","t":$t,"ms":$ms}""")

    fun serverTicks(t: Int, n: Int) = emit("""{"k":"st","t":$t,"n":$n}""")

    /** Camera frames: [partialTick, yaw, pitch] triples. */
    fun cam(t: Int, d: FloatArray) = emit("""{"k":"cam","t":$t,"d":[${triples(d)}]}""")

    /** Mouse frames over an open container: [partialTick, x, y] triples. */
    fun mouse(t: Int, d: FloatArray) = emit("""{"k":"mouse","t":$t,"d":[${triples(d)}]}""")

    private fun triples(d: FloatArray): String {
        val sb = StringBuilder(d.size * 8)
        var i = 0
        while (i < d.size) {
            if (i > 0) sb.append(',')
            sb.append('[').append(f2(d[i])).append(',').append(f2(d[i + 1])).append(',').append(f2(d[i + 2])).append(']')
            i += 3
        }
        return sb.toString()
    }

    fun block(t: Int, x: Int, y: Int, z: Int, state: BlockState) {
        val s = paletteIndex(state) // may write its "pal" line first
        emit("""{"k":"block","t":$t,"x":$x,"y":$y,"z":$z,"s":$s}""")
    }

    fun slotClick(t: Int, slot: Int, button: Int, type: String) =
        emit("""{"k":"slotclick","t":$t,"slot":$slot,"button":$button,"type":${str(type)}}""")

    fun blockUse(t: Int, x: Int, y: Int, z: Int) = emit("""{"k":"use","t":$t,"x":$x,"y":$y,"z":$z}""")

    fun teleport(t: Int, x: Double, y: Double, z: Double, yaw: Float, pitch: Float, rel: List<String>) {
        val r = if (rel.isEmpty()) "" else rel.joinToString(",", ",\"rel\":[", "]") { str(it) }
        emit("""{"k":"tp","t":$t,"x":${n(x)},"y":${n(y)},"z":${n(z)},"yaw":${a(yaw)},"pitch":${a(pitch)}$r}""")
    }

    fun batSound(t: Int, x: Double, y: Double, z: Double, volume: Float) =
        emit("""{"k":"batsound","t":$t,"x":${n(x)},"y":${n(y)},"z":${n(z)},"v":${f2(volume)}}""")

    /** [playerName] is the collector's name when it's a player, else the line gets its entity id. */
    fun pickup(t: Int, itemId: Int, collectorId: Int, playerName: String?) {
        val by = playerName?.let { str(it) } ?: collectorId.toString()
        emit("""{"k":"pickup","t":$t,"id":$itemId,"by":$by}""")
    }

    private var lastEther = ""

    fun ether(t: Int, merge: Int, tuners: Int) {
        val entry = """"merge":$merge,"tuners":$tuners"""
        if (entry == lastEther) return
        lastEther = entry
        emit("""{"k":"ether","t":$t,$entry}""")
    }

    fun chat(t: Int, message: String, colored: String?) {
        val c = if (colored != null && colored != message) ",\"c\":${str(colored)}" else ""
        emit("""{"k":"chat","t":$t,"m":${str(message)}$c}""")
    }

    fun chestEvent(t: Int, x: Int, y: Int, z: Int, openCount: Int) =
        emit("""{"k":"bev","t":$t,"x":$x,"y":$y,"z":$z,"b":$openCount}""")

    fun roomEnter(t: Int, name: String?) = emit("""{"k":"room","t":$t,"name":${str(name ?: "Unknown")}}""")

    fun click(t: Int, x: Float, y: Float, button: Int) =
        emit("""{"k":"click","t":$t,"x":${f2(x)},"y":${f2(y)},"button":$button}""")

    // ------------------------------------------------------------------ containers

    private val lastSlots = HashMap<Int, String>()
    private var lastCarried: String? = null

    fun guiOpen(t: Int, title: String, menu: String, w: Int, h: Int, slots: List<IntArray>) {
        lastSlots.clear(); lastCarried = null
        val sb = StringBuilder()
        slots.forEachIndexed { i, p -> if (i > 0) sb.append(','); sb.append('[').append(p[0]).append(',').append(p[1]).append(']') }
        emit("""{"k":"gui","t":$t,"title":${str(title)},"menu":${str(menu)},"w":$w,"h":$h,"slots":[$sb]}""")
    }

    fun guiClose(t: Int) {
        lastSlots.clear(); lastCarried = null
        emit("""{"k":"guiclose","t":$t}""")
    }

    /** Slots that changed since the last line, and the cursor's item. */
    fun containerTick(t: Int, slots: Array<SlotSnap>, carried: ItemSnap, carriedCount: Int) {
        val sb = StringBuilder()
        slots.forEachIndexed { i, s ->
            // [index, id, count, head skin or "", plain name, glint 0/1]
            val entry = "[$i,${str(vanillaId(s.item))},${s.count},${str(s.tex ?: "")},${str(s.name)},${s.glint}]"
            if (lastSlots.put(i, entry) == entry) return@forEachIndexed
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(entry)
        }
        if (sb.isNotEmpty()) emit("""{"k":"slots","t":$t,"s":[$sb]}""")
        val c = """"id":${str(vanillaId(carried))},"count":$carriedCount"""
        if (c != lastCarried) { lastCarried = c; emit("""{"k":"carried","t":$t,$c}""") }
    }

    // ------------------------------------------------------------------ per-tick snapshots

    private var partyKey = ""

    fun floorAndParty(t: Int, floor: String?, names: Array<String>, classes: Array<String>) {
        if (floor != null && floor != floorName) {
            floorName = floor
            emit("""{"k":"floor","t":$t,"floor":${str(floor)}}""")
        }
        val key = names.indices.joinToString("|") { "${names[it]}:${classes[it]}" }
        if (key != partyKey && names.isNotEmpty()) {
            partyKey = key
            emit("""{"k":"party","t":$t,"m":[${names.indices.joinToString(",") { "[${str(names[it])},${str(classes[it])}]" }}]}""")
        }
    }

    private var roomsKey = ""

    fun rooms(t: Int, rooms: List<RoomSnap>) {
        val sb = StringBuilder()
        rooms.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            sb.append('[').append(str(r.name)).append(',').append(str(r.type)).append(',')
                .append(str(r.shape)).append(',').append(str(r.rotation)).append(',')
                .append(str(r.checkmark)).append(",[")
            var j = 0
            while (j < r.tiles.size) {
                if (j > 0) sb.append(',')
                sb.append('[').append(r.tiles[j]).append(',').append(r.tiles[j + 1]).append(']')
                j += 2
            }
            sb.append("],").append(r.found).append(',').append(r.max)
            r.key?.let { sb.append(',').append(str(it)) }
            sb.append(']')
        }
        val body = sb.toString()
        if (body == roomsKey) return
        roomsKey = body
        emit("""{"k":"rooms","t":$t,"r":[$body]}""")
    }

    private val lastPlayer = HashMap<String, String>()
    private val lastHeldHead = HashMap<String, String>()

    fun players(t: Int, players: List<PlayerSnap>) {
        val sb = StringBuilder()
        var changed = 0
        val seen = HashSet<String>()
        for (p in players) {
            val name = p.name
            seen += name
            val main = p.equipment.items[0]
            val held = if (main === ItemSnap.EMPTY) "" else p.skyblockId.ifEmpty { vanillaId(main) }
            p.skin?.let { emit("""{"k":"skin","t":$t,"name":${str(name)},"tex":${str(it)}}""") }
            equipment(t, p.equipment, "\"name\":${str(name)}", name)
            // [8] is the vanilla item (for drawing it); [6] the Skyblock id when there is one; [9] 1 while crouching.
            val entry = "[${str(name)},${n(p.x)},${n(p.y)},${n(p.z)},${a(p.yaw)},${a(p.pitch)},${str(held)},${p.uuidVersion},${str(vanillaId(main))},${if (p.crouching) 1 else 0}]"
            heldHead(t, name, p.heldHeadTex)
            if (lastPlayer.put(name, entry) == entry) continue
            if (changed++ > 0) sb.append(',')
            sb.append(entry)
        }
        if (changed > 0) emit("""{"k":"p","t":$t,"d":[$sb]}""")
        for (name in lastPlayer.keys.filter { it !in seen }) {
            lastPlayer.remove(name)
            emit("""{"k":"pgone","t":$t,"name":${str(name)}}""")
        }
    }

    private fun heldHead(t: Int, name: String, tex: String) {
        val prev = lastHeldHead.put(name, tex)
        if (prev != tex && !(prev == null && tex.isEmpty())) emit("""{"k":"held","t":$t,"name":${str(name)},"tex":${str(tex)}}""")
    }

    private var lastHotbar = ""

    fun hotbar(t: Int, items: Array<ItemSnap>, tex: Array<String?>, selected: Int) {
        val itemsJson = items.joinToString(",", "[", "]") { str(vanillaId(it)) }
        val texJson = tex.indices.mapNotNull { i -> tex[i]?.let { "\"$i\":${str(it)}" } }.joinToString(",", "{", "}")
        val body = """"items":$itemsJson,"sel":$selected,"tex":$texJson"""
        if (body == lastHotbar) return
        lastHotbar = body
        emit("""{"k":"hotbar","t":$t,$body}""")
    }

    private val lastSwingTime = HashMap<String, Int>()

    fun swings(t: Int, names: Array<String>, swinging: BooleanArray, swingTime: IntArray) {
        val sb = StringBuilder()
        var count = 0
        for (i in names.indices) {
            val prev = lastSwingTime.put(names[i], if (swinging[i]) swingTime[i] else 99) ?: 99
            if (!swinging[i] || swingTime[i] > 0 || prev == -1) continue
            if (count++ > 0) sb.append(',')
            sb.append(str(names[i]))
        }
        if (count > 0) emit("""{"k":"sw","t":$t,"d":[$sb]}""")
    }

    private val lastMapPos = HashMap<String, String>()

    fun mapPlayers(t: Int, players: List<MapSnap>) {
        val sb = StringBuilder()
        var count = 0
        for (p in players) {
            if (!p.shown) { lastMapPos.remove(p.name); continue }
            val entry = "[${str(p.name)},${n(p.x)},${n(p.z)},${a(p.yaw)}]"
            if (lastMapPos.put(p.name, entry) == entry) continue
            if (count++ > 0) sb.append(',')
            sb.append(entry)
        }
        if (count > 0) emit("""{"k":"mp","t":$t,"d":[$sb]}""")
    }

    // Entity tracking: last written position/name per entity id, to only write changes.
    private class Tracked(var x: Double, var y: Double, var z: Double, var yaw: Float, var name: String, var headYaw: Float)
    private val tracked = HashMap<Int, Tracked>()
    private val lastEquipment = HashMap<String, String>()
    private val lastStand = HashMap<Int, String>()
    private val lastFrame = HashMap<Int, String>()

    fun entities(t: Int, entities: List<EntitySnap>) {
        val seen = HashSet<Int>(tracked.size + 16)
        val moved = StringBuilder()
        var movedCount = 0
        for (e in entities) {
            val id = e.id
            seen += id
            val c = if (e.colored.isNotEmpty() && e.colored != e.name) ",\"c\":${str(e.colored)}" else ""
            val tr = tracked[id]
            if (tr == null) {
                tracked[id] = Tracked(e.x, e.y, e.z, e.yaw, e.colored, e.headYaw)
                val block = e.block?.let { ",\"block\":" + str(BlockStateParser.serialize(it)) } ?: ""
                val item = if (e.isItem) ",\"item\":" + str(e.itemName) + ",\"itemId\":" + str(vanillaId(e.item)) else ""
                emit("""{"k":"spawn","t":$t,"id":$id,"type":${str(typeOf(e.type))},"name":${str(e.name)}$c,"x":${n(e.x)},"y":${n(e.y)},"z":${n(e.z)},"yaw":${a(e.yaw)}${if (e.living) ",\"headYaw\":" + a(e.headYaw) else ""}${if (e.baby) ",\"baby\":1" else ""}$block$item}""")
                e.stand?.let { stand(t, id, it) }
                e.frame?.let { frame(t, id, it, e.frameRot) }
                continue
            }
            e.equipment?.let { equipment(t, it, "\"id\":$id", "#$id") }
            e.stand?.let { stand(t, id, it) }
            e.frame?.let { frame(t, id, it, e.frameRot) }
            if (e.colored != tr.name) {
                tr.name = e.colored
                emit("""{"k":"name","t":$t,"id":$id,"name":${str(e.name)}$c}""")
            }
            if (e.x != tr.x || e.y != tr.y || e.z != tr.z || e.yaw != tr.yaw || e.headYaw != tr.headYaw) {
                tr.x = e.x; tr.y = e.y; tr.z = e.z; tr.yaw = e.yaw; tr.headYaw = e.headYaw
                if (movedCount++ > 0) moved.append(',')
                moved.append('[').append(id).append(',').append(n(e.x)).append(',').append(n(e.y)).append(',')
                    .append(n(e.z)).append(',').append(a(e.yaw))
                if (e.living) moved.append(',').append(a(e.headYaw))
                moved.append(']')
            }
        }
        if (movedCount > 0) emit("""{"k":"e","t":$t,"d":[$moved]}""")
        val gone = tracked.keys.filter { it !in seen }
        for (id in gone) {
            tracked.remove(id)
            lastEquipment.remove("#$id")
            lastStand.remove(id)
            lastFrame.remove(id)
            emit("""{"k":"gone","t":$t,"id":$id}""")
        }
    }

    private fun stand(t: Int, id: Int, s: StandSnap) {
        val pose = s.poses.joinToString(",") { "${a(it.x())},${a(it.y())},${a(it.z())}" }
        val body = """"f":${s.flags},"pose":[$pose]"""
        if (lastStand.put(id, body) == body) return
        emit("""{"k":"stand","t":$t,"id":$id,$body}""")
    }

    private fun frame(t: Int, id: Int, item: ItemSnap, rotation: Int) {
        val entry = """"item":${str(vanillaId(item))},"rot":$rotation"""
        if (lastFrame.put(id, entry) == entry) return
        emit("""{"k":"frame","t":$t,"id":$id,$entry}""")
    }

    private fun equipment(t: Int, eq: EquipSnap, who: String, key: String) {
        val body = eq.items.joinToString(",", "[", "]") { str(vanillaId(it)) } + (eq.headTex?.let { ",\"headTex\":${str(it)}" } ?: "")
        val previous = lastEquipment.put(key, body)
        if (previous == body || (previous == null && body == NO_EQUIPMENT)) return
        emit("""{"k":"eq","t":$t,$who,"eq":$body}""")
    }

    private val lastSkull = HashMap<BlockPos, String>()

    fun skulls(t: Int, skulls: List<SkullSnap>) {
        for (s in skulls) {
            if (lastSkull.put(BlockPos(s.x, s.y, s.z), s.tex) == s.tex) continue
            emit("""{"k":"skull","t":$t,"x":${s.x},"y":${s.y},"z":${s.z},"tex":${str(s.tex)}}""")
        }
    }

    // ------------------------------------------------------------------ geometry volumes

    private val volumes = HashMap<Int, VolumeEncoder>()

    fun volumeBlocks(id: Int, states: Array<BlockState?>) = volumes.getOrPut(id, ::VolumeEncoder).add(states)

    fun volumeDone(id: Int, t: Int, kind: String, key: String?, x0: Int, y0: Int, z0: Int, w: Int, h: Int, d: Int) =
        emit((volumes.remove(id) ?: VolumeEncoder()).line(t, kind, key, x0, y0, z0, w, h, d))

    // ------------------------------------------------------------------ block palette (for "block" change lines)

    private val palette = HashMap<BlockState, Int>()

    private fun paletteIndex(state: BlockState): Int = palette.getOrPut(state) {
        val i = palette.size
        emit("""{"k":"pal","i":$i,"s":${str(BlockStateParser.serialize(state))}}""")
        i
    }

    // ------------------------------------------------------------------ formatting

    /** The game item id, plus "#rrggbb" for dyed items (leather armour) so the viewer can colour it. */
    fun vanillaId(item: ItemSnap): String {
        if (item === ItemSnap.EMPTY) return ""
        // The item it looks like: Hypixel builds many items on a base item with another item's model
        // (paper that is drawn as TNT), so a vanilla item_model wins over the base item.
        val modelId = item.model
        val model = modelId?.takeIf { it.namespace == "minecraft" }?.toString()
        var id = model ?: BuiltInRegistries.ITEM.getKey(item.item!!).toString()
        // A model from another namespace (Hypixel's own) is kept after "@", for the viewer to map.
        if (modelId != null && model == null) id += "@" + modelId
        if (!item.dyed) return id
        return id + "#" + String.format(Locale.ROOT, "%06x", item.dye and 0xFFFFFF)
    }

    private fun typeOf(type: EntityType<*>): String = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString()

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
        const val NO_EQUIPMENT = """["","","","",""]"""
        /** How long the writer sleeps when there's nothing to do; lines are never time-critical. */
        const val IDLE_NS = 5_000_000L
        const val MAX_LOGGED_FAILURES = 5
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    }

    // Last, so every field above is set before the thread can touch it.
    init {
        Thread(::loop, "ec-betterpf-writer").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }
}
