package com.devgineerclient.betterrecorder

import com.devgineerclient.DevgineerClient
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.utils.skyblock.dungeon.DungeonListener
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundBossEventPacket
import net.minecraft.network.chat.Component
import net.minecraft.world.BossEvent
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * One Better Recorder recording: a dungeon from the world it starts in to the world change that ends it. Lines are
 * `{"k":kind,"t":client tick,"n":server tick, ...}` with both clocks counted from the start; see docs/better-recorder.md for every kind.
 */
class BrSession(val path: Path, val where: String) {
    val file = BrFile(path)
    private val startTick = BetterRecorder.clientTick
    val t get() = BetterRecorder.clientTick - startTick
    private val serverTicks = AtomicInteger()
    val n get() = serverTicks.get()

    fun serverTick() { serverTicks.incrementAndGet() }

    // ------------------------------------------------------------------ lines

    private class Ev(val kind: String, val n: Int, val build: (BrJson) -> Unit)
    private val events = ConcurrentLinkedQueue<Ev>()

    /** An event from any thread, written at the end of this client tick (with its own server tick). */
    fun queue(kind: String, build: (BrJson) -> Unit) { events.add(Ev(kind, n, build)) }

    /** A line now (game thread). */
    fun line(kind: String, build: (BrJson) -> Unit = {}) = write(kind, n, build)

    private fun write(kind: String, sn: Int, build: (BrJson) -> Unit) {
        val j = BrJson(160).s("k", kind).i("t", t).i("n", sn)
        try { build(j) } catch (e: Throwable) { j.s("err", e.toString()) }
        file.write("{$j}")
        BetterRecorder.lineWritten(kind)
    }

    // ------------------------------------------------------------------ start and end

    fun start() {
        val mc = DevgineerClient.mc
        line("meta") { j ->
            j.i("format", FORMAT).s("where", where).s("date", java.time.OffsetDateTime.now().toString())
            j.s("me", mc.player?.gameProfile?.name).s("uuid", mc.player?.uuid?.toString())
            j.s("floor", runCatching { DungeonUtils.floor?.name }.getOrNull())
            j.s("server", runCatching { mc.connection?.serverBrand() }.getOrNull())
            j.s("mod", runCatching { net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("engineerclient").get().metadata.version.friendlyString }.getOrNull())
            j.raw("probes", BrProbes.active().joinToString(",", "[", "]") { BrJson.q(it) })
        }
    }

    fun end(why: String) {
        flush()
        line("end") { j -> j.s("why", why).l("lines", file.lines.get()) }
        file.close()
    }

    // ------------------------------------------------------------------ per tick (game thread)

    fun tick() {
        flush()
        val mc = DevgineerClient.mc
        val level = mc.level ?: return
        val me = mc.player ?: return
        if (t % 20 == 0) line("clk") { j -> j.l("ms", System.currentTimeMillis()).l("game", level.gameTime) }
        self(me)
        inventory(me)
        entities(level, me)
        if (t % 5 == 0) { sidebar(); tab() }
        if (t % 10 == 0) { odin(); snapshots(level) }
        BrProbes.tick(this)
    }

    /** Every queued packet event, in order. */
    private fun flush() {
        while (true) {
            val e = events.poll() ?: break
            write(e.kind, e.n, e.build)
        }
    }

    // ------------------------------------------------------------------ you

    private var lastMe = ""
    private var lastRoom: String? = null

    private fun self(p: net.minecraft.client.player.LocalPlayer) {
        var flags = 0
        if (p.onGround()) flags = flags or 1
        if (p.isShiftKeyDown) flags = flags or 2
        if (p.isSprinting) flags = flags or 4
        if (p.isInWater) flags = flags or 8
        if (p.isInLava) flags = flags or 16
        if (p.abilities.flying) flags = flags or 32
        val sb = StringBuilder(64).append('[')
        BrJson.num(sb, p.x, 1000.0); sb.append(','); BrJson.num(sb, p.y, 1000.0); sb.append(','); BrJson.num(sb, p.z, 1000.0); sb.append(',')
        BrJson.num(sb, net.minecraft.util.Mth.wrapDegrees(p.yRot).toDouble(), 10.0); sb.append(','); BrJson.num(sb, p.xRot.toDouble(), 10.0)
        sb.append(',').append(flags).append(',').append(p.inventory.selectedSlot).append(',')
        BrJson.num(sb, p.health.toDouble(), 10.0); sb.append(','); BrJson.num(sb, p.absorptionAmount.toDouble(), 10.0); sb.append(']')
        val s = sb.toString()
        if (s != lastMe) { lastMe = s; line("me") { it.raw("s", s) } }
        val room = BrRooms.current()
        val name = room?.data?.name
        if (name != lastRoom) {
            val from = lastRoom
            lastRoom = name
            line("enter") { j -> j.s("from", from).s("to", name); if (room != null) j.raw("room", BrRooms.describe(room)); BrRooms.tag(j, p.x, p.y, p.z) }
            if (room != null) snapshotWanted(room, "enter")
        }
    }

    private val inv = arrayOfNulls<String>(41)

    private fun inventory(p: net.minecraft.client.player.LocalPlayer) {
        val inventory = p.inventory
        var sb: StringBuilder? = null
        for (i in 0 until minOf(41, inventory.containerSize)) {
            val st = inventory.getItem(i)
            val key = if (st.isEmpty) "" else BrJson.item(st)
            if (key == inv[i]) continue
            inv[i] = key
            if (sb == null) sb = StringBuilder(128).append('[') else sb.append(',')
            sb.append('[').append(i).append(',').append(key.ifEmpty { "null" }).append(']')
        }
        if (sb != null) { val s = sb.append(']').toString(); line("inv") { it.raw("s", s) } }
    }

    /** The held item, short (for use and attack lines). Game thread. */
    fun heldItem(): String = DevgineerClient.mc.player?.mainHandItem?.let { BrJson.item(it) } ?: "null"

    fun blockAt(pos: BlockPos): String? = DevgineerClient.mc.level?.getBlockState(pos)?.let { BrJson.state(it) }

    // ------------------------------------------------------------------ entities

    private class Track(val e: Entity) {
        var x = Double.NaN; var y = 0.0; var z = 0.0; var yaw = 0f; var head = 0f; var pitch = 0f
        var state = ""; var eq = ""
    }
    private val tracks = HashMap<Int, Track>()
    private val removedAt = ConcurrentHashMap<Int, Int>()

    fun removed(ids: IntArray) { val sn = n; for (id in ids) removedAt[id] = sn }

    private fun entities(level: net.minecraft.client.multiplayer.ClientLevel, me: Player) {
        val seen = HashSet<Int>(tracks.size + 16)
        val moves = StringBuilder(256)
        for (e in level.entitiesForRendering()) {
            val id = e.id
            seen += id
            var tr = tracks[id]
            if (tr == null) { tr = Track(e); tracks[id] = tr; spawn(e, me) }
            // The last position the server sent (not the drawn one), and the angles.
            val b = runCatching { e.positionCodec.base }.getOrNull() ?: e.position()
            val yaw = net.minecraft.util.Mth.wrapDegrees(e.yRot); val head = net.minecraft.util.Mth.wrapDegrees(e.yHeadRot); val pitch = e.xRot
            if (e !== me && (tr.x.isNaN() || Math.abs(b.x - tr.x) > 0.0005 || Math.abs(b.y - tr.y) > 0.0005 || Math.abs(b.z - tr.z) > 0.0005 ||
                    Math.abs(yaw - tr.yaw) > 0.5f || Math.abs(head - tr.head) > 0.5f || Math.abs(pitch - tr.pitch) > 0.5f)) {
                tr.x = b.x; tr.y = b.y; tr.z = b.z; tr.yaw = yaw; tr.head = head; tr.pitch = pitch
                if (moves.length > 1) moves.append(',') else moves.append('[')
                moves.append('[').append(id).append(','); BrJson.num(moves, b.x, 1000.0); moves.append(','); BrJson.num(moves, b.y, 1000.0); moves.append(',')
                BrJson.num(moves, b.z, 1000.0); moves.append(','); BrJson.num(moves, yaw.toDouble(), 10.0); moves.append(','); BrJson.num(moves, head.toDouble(), 10.0)
                moves.append(','); BrJson.num(moves, pitch.toDouble(), 10.0); moves.append(']')
            }
            val st = stateOf(e)
            if (st != tr.state) { val was = tr.state; tr.state = st; if (was.isNotEmpty()) line("ed") { j -> j.i("e", id).raw("s", st) } }
            if (e is LivingEntity) {
                val eq = equipment(e)
                if (eq != tr.eq) { val was = tr.eq; tr.eq = eq; if (was.isNotEmpty()) line("eq") { j -> j.i("e", id).raw("eq", eq) } }
            }
        }
        if (moves.length > 1) { val s = moves.append(']').toString(); line("mv") { it.raw("m", s) } }
        val gone = tracks.keys.filter { it !in seen }
        for (id in gone) {
            val tr = tracks.remove(id) ?: continue
            val e = tr.e
            val rn = removedAt.remove(id)
            line("gone") { j ->
                j.i("e", id).s("why", runCatching { e.removalReason?.name }.getOrNull()).i("rmN", rn)
                if (e is LivingEntity) j.f("hp", e.health).b("dead", e.isDeadOrDying)
                j.s("name", e.customName?.let { BrJson.legacy(it) })
            }
            BrProbes.gone(this, e)
        }
    }

    /** `[name, nameVisible, invisible, hp, maxHp, flags, pose, vehicle]`: what changes on an entity besides where it is. */
    private fun stateOf(e: Entity): String {
        val sb = StringBuilder(64).append('[')
        BrJson.str(sb, e.customName?.let { BrJson.legacy(it) })
        sb.append(',').append(if (e.isCustomNameVisible) 1 else 0).append(',').append(if (e.isInvisible) 1 else 0).append(',')
        if (e is LivingEntity) { BrJson.num(sb, e.health.toDouble(), 10.0); sb.append(','); BrJson.num(sb, e.maxHealth.toDouble(), 10.0) } else sb.append("null,null")
        var f = 0
        if (e.isCurrentlyGlowing) f = f or 1
        if (e.isOnFire) f = f or 2
        if (e.isShiftKeyDown) f = f or 4
        if (e.isSprinting) f = f or 8
        if (e is LivingEntity && e.isBaby) f = f or 16
        if (e.isNoGravity) f = f or 32
        if (e is ArmorStand) { if (e.isMarker) f = f or 64; if (e.isSmall) f = f or 128 }
        if (e is LivingEntity && e.isDeadOrDying) f = f or 256
        sb.append(',').append(f).append(',')
        BrJson.str(sb, e.pose.name.lowercase())
        sb.append(',').append(e.vehicle?.id ?: -1)
        return sb.append(']').toString()
    }

    private val SLOTS = arrayOf(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)

    private fun equipment(e: LivingEntity): String {
        val sb = StringBuilder(64).append('[')
        SLOTS.forEachIndexed { i, s -> if (i > 0) sb.append(','); sb.append(BrJson.item(e.getItemBySlot(s))) }
        return sb.append(']').toString()
    }

    private fun spawn(e: Entity, me: Player) {
        val type = BuiltInRegistries.ENTITY_TYPE.getKey(e.type).path
        val b = runCatching { e.positionCodec.base }.getOrNull() ?: e.position()
        line("spawn") { j ->
            j.i("e", e.id).s("type", type).s("uuid", e.uuid.toString()).i("uv", e.uuid.version())
            j.xyz("p", b.x, b.y, b.z).a("yaw", net.minecraft.util.Mth.wrapDegrees(e.yRot)).a("pitch", e.xRot)
            j.raw("s", stateOf(e))
            if (e is LivingEntity) j.raw("eq", equipment(e))
            if (e is ItemEntity) j.raw("item", BrJson.item(e.item))
            if (e is Player) {
                j.s("pname", e.gameProfile.name)
                val tex = runCatching { DevgineerClient.mc.connection?.getPlayerInfo(e.uuid)?.profile?.properties()?.get("textures")?.firstOrNull() }.getOrNull()
                if (tex != null) { j.s("tex", tex.value()); j.s("sig", tex.signature()) }
            }
            j.f("dist", e.distanceTo(me))
            BrRooms.tag(j, b.x, b.y, b.z)
        }
        BrProbes.spawned(this, e)
    }

    /** `[id, type, name, distance]` of an entity, for attack and interact lines. */
    fun entityBrief(id: Int): String {
        val mc = DevgineerClient.mc
        val e = mc.level?.getEntity(id) ?: return "null"
        val sb = StringBuilder(64).append('[').append(id).append(',')
        BrJson.str(sb, BuiltInRegistries.ENTITY_TYPE.getKey(e.type).path); sb.append(',')
        BrJson.str(sb, e.customName?.let { BrJson.legacy(it) } ?: (e as? Player)?.gameProfile?.name); sb.append(',')
        BrJson.num(sb, mc.player?.let { e.distanceTo(it).toDouble() } ?: -1.0, 1000.0)
        return sb.append(']').toString()
    }

    // ------------------------------------------------------------------ action bar, tab, sidebar, boss bars, map

    @Volatile private var lastBar = ""
    /** The action bar, only when it changes (it is resent every few ticks). */
    fun bar(text: String) { if (text == lastBar) return; lastBar = text; queue("bar") { it.s("m", text) } }

    @Volatile private var lastHeader = ""; @Volatile private var lastFooter = ""
    fun tabHeader(h: String, f: String) {
        if (h == lastHeader && f == lastFooter) return
        val hh = if (h != lastHeader) h else null; val ff = if (f != lastFooter) f else null
        lastHeader = h; lastFooter = f
        queue("tabHF") { j -> if (hh != null) j.s("h", hh); if (ff != null) j.s("f", ff) }
    }

    private val tabRows = HashMap<String, String>()

    private fun tab() {
        val mc = DevgineerClient.mc
        val infos = mc.connection?.listedOnlinePlayers ?: return
        val now = HashMap<String, String>(infos.size * 2)
        for (info in infos) {
            val name = info.profile.name()
            now[name] = BrJson.legacy(mc.gui.hud.tabList.getNameForDisplay(info))
        }
        val changed = now.filter { (k, v) -> tabRows[k] != v }
        val gone = tabRows.keys.filter { it !in now }
        if (changed.isEmpty() && gone.isEmpty()) return
        tabRows.clear(); tabRows.putAll(now)
        line("tab") { j ->
            j.raw("rows", changed.entries.sortedBy { it.key }.joinToString(",", "[", "]") { "[${BrJson.q(it.key)},${BrJson.q(it.value)}]" })
            if (gone.isNotEmpty()) j.raw("gone", gone.joinToString(",", "[", "]") { BrJson.q(it) })
        }
    }

    private var lastSide = ""

    private fun sidebar() {
        val board = DevgineerClient.mc.level?.scoreboard ?: return
        val obj = board.getDisplayObjective(DisplaySlot.SIDEBAR)
        val lines = if (obj == null) emptyList() else board.listPlayerScores(obj).filter { !it.isHidden }
            .sortedWith(compareByDescending<net.minecraft.world.scores.PlayerScoreEntry> { it.value() }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.owner() })
            .take(15).map { BrJson.legacy(PlayerTeam.formatNameForTeam(board.getPlayersTeam(it.owner()), it.ownerName())) }
        val title = obj?.displayName?.let { BrJson.legacy(it) }
        val s = BrJson.q(title) + lines.joinToString(",", ",[", "]") { BrJson.q(it) }
        if (s == lastSide) return
        lastSide = s
        line("side") { j -> j.s("title", title).raw("lines", lines.joinToString(",", "[", "]") { BrJson.q(it) }) }
    }

    fun bossPacket(p: ClientboundBossEventPacket) {
        p.dispatch(object : ClientboundBossEventPacket.Handler {
            override fun add(id: UUID, name: Component, progress: Float, color: BossEvent.BossBarColor, overlay: BossEvent.BossBarOverlay, darkenScreen: Boolean, playMusic: Boolean, createWorldFog: Boolean) {
                val nm = BrJson.legacy(name); queue("boss") { j -> j.s("op", "add").s("id", id.toString().take(8)).s("name", nm).f("v", progress).s("color", color.name) }
            }
            override fun remove(id: UUID) { queue("boss") { j -> j.s("op", "remove").s("id", id.toString().take(8)) } }
            override fun updateProgress(id: UUID, progress: Float) { queue("boss") { j -> j.s("op", "v").s("id", id.toString().take(8)).f("v", progress) } }
            override fun updateName(id: UUID, name: Component) { val nm = BrJson.legacy(name); queue("boss") { j -> j.s("op", "name").s("id", id.toString().take(8)).s("name", nm) } }
        })
    }

    private val mapHash = ConcurrentHashMap<Int, Int>()
    private val iconHash = ConcurrentHashMap<Int, Int>()

    fun mapPatch(id: Int, x: Int, y: Int, w: Int, h: Int, colors: ByteArray) {
        val hash = colors.contentHashCode() * 31 + x * 7 + y * 13 + w
        if (mapHash.put(id, hash) == hash) return
        queue("map") { j -> j.i("id", id).ints("box", intArrayOf(x, y, w, h)).s("px", BrNet.b64(colors)) }
    }

    fun mapIcons(id: Int, icons: String) {
        val hash = icons.hashCode()
        if (iconHash.put(id, hash) == hash) return
        queue("mapIcons") { j -> j.i("id", id).raw("icons", icons) }
    }

    // ------------------------------------------------------------------ skins, particles, time

    private val skins = ConcurrentHashMap.newKeySet<String>()

    /** A player profile's skin, signed, once per texture (fake players included: Hypixel's mobs, NPCs, the blood camp). */
    fun skin(name: String, uuid: String, value: String, sig: String?) {
        if (!skins.add(value)) return
        queue("skin") { j -> j.s("name", name).s("uuid", uuid).s("tex", value).s("sig", sig) }
    }

    private val particleSeen = ConcurrentHashMap<String, Int>()

    /** Null when the same particle at the same spot was already written this server tick (Hypixel repeats them). */
    fun particle(type: String?, x: Double, y: Double, z: Double): Unit? {
        val key = "$type ${Math.round(x * 10)} ${Math.round(y * 10)} ${Math.round(z * 10)}"
        val now = n
        if (particleSeen.put(key, now) == now) return null
        if (particleSeen.size > 4096) particleSeen.clear()
        return Unit
    }

    @Volatile private var lastTimeLine = -100
    fun time(game: Long) {
        val now = n
        if (now - lastTimeLine < 20) return
        lastTimeLine = now
        queue("stime") { j -> j.l("game", game).l("ms", System.currentTimeMillis()) }
    }

    // ------------------------------------------------------------------ Odin: rooms, doors, puzzles, the run's numbers

    private val roomState = HashMap<Int, String>()
    private var lastDoors = ""
    private var lastPuzzles = ""
    private var lastDungeon = ""

    private fun odin() {
        val rooms = runCatching { DungeonScan.rooms.toList() }.getOrNull() ?: return
        for (r in rooms) {
            val id = System.identityHashCode(r)
            val d = runCatching { BrRooms.describe(r) }.getOrNull() ?: continue
            if (roomState[id] == d) continue
            roomState[id] = d
            line("room") { j -> j.i("id", id).raw("room", d) }
            snapshotWanted(r, "seen")
        }
        val doors = runCatching {
            DungeonScan.doors.values.sortedWith(compareBy({ it.worldX }, { it.worldZ })).joinToString(",", "[", "]") { "[${it.worldX},${it.worldZ},${BrJson.q(it.type.name)},${BrJson.q(it.rotation.name)},${it.originTileIndex},${it.destinationTileIndex}]" }
        }.getOrNull()
        if (doors != null && doors != lastDoors) { lastDoors = doors; line("doors") { it.raw("doors", doors) } }
        val puzzles = runCatching { DungeonListener.puzzles.toList().joinToString(",", "[", "]") { "[${BrJson.q(it.name)},${BrJson.q(it.status?.name)}]" } }.getOrNull()
        if (puzzles != null && puzzles != lastPuzzles) { lastPuzzles = puzzles; line("puzzles") { it.raw("p", puzzles) } }
        val dung = runCatching {
            BrJson().b("inBoss", DungeonUtils.inBoss).i("secretsFound", DungeonUtils.secretCount).i("crypts", DungeonUtils.cryptCount)
                .i("deaths", DungeonUtils.deathCount).i("score", DungeonUtils.score).b("mimic", DungeonUtils.mimicKilled).b("prince", DungeonUtils.princeKilled)
                .i("totalSecrets", DungeonUtils.totalSecrets).i("totalRooms", DungeonUtils.totalRooms).toString()
        }.getOrNull()
        if (dung != null && dung != lastDungeon) { lastDungeon = dung; line("dungeon") { it.raw("s", "{$dung}") } }
    }

    // ------------------------------------------------------------------ room snapshots

    private val snapped = HashSet<String>()
    private val snapQueue = ArrayDeque<Pair<com.odtheking.odin.features.impl.dungeon.map.tile.DungeonRoom, String>>()

    /**
     * Queues a block snapshot of [r] the first time it is seen with its rotation known (its untouched state: a puzzle's layout, the
     * chests and levers as built), and again for puzzle and trap rooms when they are done, if the setting asks for it.
     */
    fun snapshotWanted(r: com.odtheking.odin.features.impl.dungeon.map.tile.DungeonRoom, why: String) {
        val name = r.data?.name ?: return
        if (!BrRooms.rotationKnown(r)) return
        val type = runCatching { r.data?.type?.name }.getOrNull() ?: ""
        val mode = BetterRecorder.snapshotMode
        if (mode == 0) return
        val special = type == "PUZZLE" || type == "TRAP" || name == "Blood" || name == "Fairy"
        if (mode == 1 && !special) return
        val done = runCatching { r.checkmark.name }.getOrNull()
        val key = when {
            special && (done == "WHITE" || done == "GREEN") -> "$name|done"
            else -> "$name|first"
        }
        if (!snapped.add(key + "@" + System.identityHashCode(r))) return
        snapQueue.addLast(r to key.substringAfter('|'))
    }

    /** At most one room a call: all of its blocks from y [Y0] to [Y1], run-length coded in world order, with Odin's frame to turn them. */
    private fun snapshots(level: net.minecraft.client.multiplayer.ClientLevel) {
        val (r, why) = snapQueue.removeFirstOrNull() ?: return
        val box = BrRooms.box(r) ?: return
        for (cx in (box[0] shr 4)..(box[2] shr 4)) for (cz in (box[1] shr 4)..(box[3] shr 4))
            if (level.chunkSource.getChunk(cx, cz, ChunkStatus.FULL, false) == null) { snapQueue.addLast(r to why); return }
        val palette = LinkedHashMap<net.minecraft.world.level.block.state.BlockState, Int>()
        val runs = StringBuilder(32768)
        val pos = BlockPos.MutableBlockPos()
        var last = -1; var count = 0; var nRuns = 0
        fun flushRun() { if (count == 0) return; if (nRuns++ > 0) runs.append(','); if (count == 1) runs.append(last) else runs.append(last).append('x').append(count) }
        var top = 0
        for (y in Y0..Y1) for (z in box[1]..box[3]) for (x in box[0]..box[2]) {
            val st = level.getBlockState(pos.set(x, y, z))
            if (!st.isAir) top = y
            val idx = palette.getOrPut(st) { palette.size }
            if (idx == last) count++ else { flushRun(); last = idx; count = 1 }
        }
        flushRun()
        line("snap") { j ->
            j.s("why", why).raw("room", BrRooms.describe(r)).ints("box", intArrayOf(box[0], Y0, box[1], box[2], Y1, box[3])).i("top", top)
            j.raw("pal", palette.keys.joinToString(",", "[", "]") { BrJson.q(BrJson.state(it)) }).s("order", "y,z,x").s("rle", runs.toString())
        }
    }

    companion object {
        const val FORMAT = 1
        /** The heights a room snapshot covers: the Water Board reaches down to 56, no room's roof is above 140. */
        const val Y0 = 40
        const val Y1 = 160
        @Suppress("unused") private val AIR = Blocks.AIR
    }
}
