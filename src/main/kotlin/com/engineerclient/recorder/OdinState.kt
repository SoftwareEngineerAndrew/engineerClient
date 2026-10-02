package com.engineerclient.recorder

import com.engineerclient.ClassDetect
import com.engineerclient.EngineerClient
import com.engineerclient.betterpf.RoomKeys
import com.engineerclient.rotation.P3ChatParser
import com.odtheking.odin.clickgui.settings.Setting
import com.odtheking.odin.clickgui.settings.impl.StringSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.ModuleManager
import com.odtheking.odin.features.impl.dungeon.LeapMenu
import com.odtheking.odin.features.impl.dungeon.MapInfo
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.features.impl.dungeon.map.WorldScan
import com.odtheking.odin.features.impl.dungeon.map.tile.DungeonDoor
import com.odtheking.odin.features.impl.dungeon.map.tile.DungeonRoom
import com.odtheking.odin.utils.ServerUtils
import com.odtheking.odin.utils.skyblock.ActionBarListener
import com.odtheking.odin.utils.skyblock.LocationUtils
import com.odtheking.odin.utils.skyblock.PartyUtils
import com.odtheking.odin.utils.skyblock.dungeon.Blessing
import com.odtheking.odin.utils.skyblock.dungeon.DungeonListener
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.terminals.TerminalUtils
import com.odtheking.odin.utils.skyblock.dungeon.terminals.terminalhandler.TerminalHandler
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket

/**
 * Odin's view of the dungeon, as the recorder's state lines (recorder-2 U11). Everything Odin
 * knows and the mods built on it read - the score inputs, the teammates and where the map puts
 * them, the room grid and each room's identity, doors, puzzles, the open terminal, the action-bar
 * stats, location and party, and which modules (Odin's and ours) are on with what settings - so a
 * recording shows not just what the server sent but what the client made of it.
 *
 * Polled every tick at TickEvent.End and written only when it changed ([Rec.changed]), so a quiet
 * dungeon costs a handful of string compares a tick. The values are copied into finished strings
 * here, on the thread reading them; no Odin object ever reaches the writer thread or [PacketJson].
 *
 * Two parts of Odin's state are written by the network thread (the tab list parse in
 * DungeonListener, at priority 0): right after it, at priority -1000, the dungeon, team and
 * puzzle lines are snapshotted there too (`"thr":"net"`), so the view matching each tab update is
 * kept even when the next tick would already see a later one.
 *
 * Read-only by rule: none of Odin's mutating or scanning calls (updateScore, inferLayout*,
 * get1x1Rotation, updateViewableDoors, initClient, recordRoomCore, the SpecialColumn ones, the
 * getDungeonTeammates(ArrayList, List) overload) are ever called from here.
 */
object OdinState {

    /** `☠ Name ... and became a ghost.` - Odin 0.3.4 never counts deaths per player itself. */
    internal val GHOST = Regex("""☠ (\w{1,16}) .* and became a ghost\.""")
    private val COLOR = Regex("§.")
    /** StringSettings whose name says they may hold a secret: their value is never written. */
    internal val SECRET_NAME = Regex("key|token|webhook|url", RegexOption.IGNORE_CASE)

    /** Per-player deaths counted from chat, for this world. */
    private val deaths = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** isDead per player as last seen on the game thread, for the death/revive transitions. */
    private val lastDead = HashMap<String, Boolean>()
    /** Rooms written last tick (identity hash), so a room that disappears gets a "gone" line. */
    private val lastRooms = HashSet<Int>()
    private var lastRoomsSnapshotMs = 0L
    private var termOpen = false

    /** Set while a keyframe is being written: every line goes out whether it changed or not, tagged "kf". */
    @Volatile private var forcing = 0L

    fun install() {
        on<TickEvent.End> { if (Rec.active) EngineerClient.safely("recorder odin state") { tick() } }
        on<LevelEvent.Load> { deaths.clear(); lastDead.clear(); lastRooms.clear(); termOpen = false }

        // Right after Odin's own (priority 0) parse of the tab list, on the network thread.
        onReceive<ClientboundPlayerInfoUpdatePacket>(priority = -1000) { if (Rec.active) EngineerClient.safely("recorder odin net") { netSnapshot("player_info") } }
        onReceive<ClientboundSetPlayerTeamPacket>(priority = -1000) { if (Rec.active) EngineerClient.safely("recorder odin net") { netSnapshot("team") } }

        // Last of all, so a cancel by any listener before it is seen.
        on<MessageEvent.Chat>(priority = Int.MIN_VALUE) { if (Rec.active) EngineerClient.safely("recorder odin chat") { chat(message, component, isCancelled) } }
        on<MessageEvent.Overlay>(priority = Int.MIN_VALUE) { if (Rec.active) EngineerClient.safely("recorder odin overlay") { overlay(message, component, isCancelled) } }

        Rec.onKeyframe("odin") { keyframe() }
        EventBus.subscribe(this)
    }

    // ------------------------------------------------------------------ polling

    private fun tick() {
        val now = System.currentTimeMillis()
        put("odin.dungeon", dungeon(), "tick")
        put("team", team(), "tick")
        deathTransitions()
        roomLines()
        put("doors", doors(), null)
        put("where", where(), null)
        term()
        put("sb", sb(), null)
        put("loc", loc(), null)
        if (Rec.tick % 20 == 0) put("modules", modules(), null)
        if (now - lastRoomsSnapshotMs >= 5 * 60_000) { lastRoomsSnapshotMs = now; Rec.emit("rooms", rooms()) }
    }

    /** A full set of state lines (game thread), all written and tagged with the keyframe id. */
    private fun keyframe() {
        forcing = Rec.keyframeId
        try {
            put("odin.dungeon", dungeon(), "keyframe")
            put("team", team(), "keyframe")
            put("puzzles", puzzles(), "keyframe")
            roomLines()
            lastRoomsSnapshotMs = System.currentTimeMillis()
            Rec.emit("rooms", rooms() + kf())
            put("doors", doors(), null)
            put("where", where(), null)
            term()
            put("sb", sb(), null)
            put("loc", loc(), null)
            put("modules", modules(), null)
        } finally { forcing = 0L }
    }

    private fun netSnapshot(cause: String) {
        put("odin.dungeon", dungeon(), cause)
        put("team", team(), cause)
        put("puzzles", puzzles(), cause)
    }

    /**
     * Writes [state] as a [kind] line when it differs from the last one written under that kind
     * (or a keyframe is being written). The cause and thread are added after the compare, so the
     * same state seen from the tick and from the network thread is written once.
     */
    private fun put(kind: String, state: String, cause: String?) {
        val changed = Rec.changed("odin:$kind", state)
        if (!changed && kfNow() == 0L) return
        val sb = StringBuilder(state.length + 48).append(state)
        if (cause != null) { sb.append(",\"cause\":"); PacketJson.str(sb, cause) }
        sb.append(",\"thr\":\"").append(thr()).append('"')
        sb.append(kf())
        Rec.emit(kind, sb.toString())
    }

    /** The keyframe being written, seen only from the game thread (a network snapshot is never part of it). */
    private fun kfNow(): Long = if (forcing != 0L && Thread.currentThread() === Rec.gameThread) forcing else 0L

    private fun kf() = kfNow().let { if (it != 0L) ",\"kf\":$it" else "" }

    internal fun thr(): String = if (Thread.currentThread() === Rec.gameThread) "main" else "net"

    // ------------------------------------------------------------------ odin.dungeon

    private fun dungeon(): String {
        val j = OdinJs(640)
        j.safe("floor") { OdinJs.str(it, DungeonListener.floor?.name) }
        j.safe("inDungeons") { it.append(DungeonUtils.inDungeons) }
        j.safe("inBoss") { it.append(DungeonListener.inBoss) }
        j.safe("inClear") { it.append(DungeonUtils.inClear) }
        j.safe("paul") { it.append(DungeonListener.paul) }
        j.safe("togglePaul") { it.append(MapInfo.togglePaul) }
        j.safe("f7Phase") { OdinJs.str(it, DungeonUtils.getF7Phase().name) }
        // Replaced (not mutated) by Odin on every update: read it once, fresh.
        j.safe("stats") { out ->
            val st = DungeonListener.dungeonStats
            val o = OdinJs(400)
            o.n("secretsFound", st.secretsFound).n("secretsPercent", st.secretsPercent).n("knownSecrets", st.knownSecrets)
                .n("crypts", st.crypts).n("openedRooms", st.openedRooms).n("completedRooms", st.completedRooms)
                .n("deaths", st.deaths).n("percentCleared", st.percentCleared).s("elapsed", st.elapsedTime)
                .b("mimic", st.mimicKilled).b("prince", st.princeKilled).b("bat", st.batKilled)
                .s("doorOpener", st.doorOpener).b("bloodDone", st.bloodDone).n("puzzleCount", st.puzzleCount)
            out.append('{').append(o.sb).append('}')
        }
        j.safe("totalSecrets") { it.append(DungeonUtils.totalSecrets) }
        j.safe("totalRooms") { it.append(DungeonUtils.totalRooms) }
        j.safe("neededSecrets") { it.append(DungeonUtils.neededSecretsAmount) }
        j.safe("bonus") { it.append(DungeonUtils.getBonusScore) }
        j.safe("score") { it.append(DungeonUtils.score) }
        return j.toString()
    }

    // ------------------------------------------------------------------ team, deaths, leaps

    private fun teammates(): List<DungeonPlayer> = DungeonUtils.dungeonTeammates.toList()

    private fun team(): String {
        val j = OdinJs(1024)
        j.arr("players", teammates()) { out, p -> player(out, p) }
        j.arr("leap", DungeonUtils.leapTeammates.toList()) { out, p -> OdinJs.str(out, p.name) }
        j.safe("leapType") { it.append(LeapMenu.type) }
        j.arr("customOrder", DungeonUtils.customLeapOrder.toList()) { out, s -> OdinJs.str(out, s) }
        j.safe("ecClass") { OdinJs.str(it, ClassDetect.detected?.name) }
        return j.toString()
    }

    private fun player(out: StringBuilder, p: DungeonPlayer) {
        val o = OdinJs(256)
        o.s("name", p.name)
        o.safe("clazz") { OdinJs.str(it, p.clazz.name) }
        o.n("clazzLvl", p.clazzLvl)
        o.b("isDead", p.isDead)
        o.n("deathsOdin", p.deaths)
        o.n("deaths", deaths[p.name] ?: 0)
        val e = p.entity
        o.n("eid", e?.id)
        o.b("loaded", e != null && !e.isRemoved)
        o.safe("pos") { if (e == null) it.append("null") else OdinJs.nums(it, e.x, e.y, e.z) }
        o.safe("map") { val m = p.mapPos; OdinJs.nums(it, m.x, m.z) }
        o.safe("mapWorld") {
            val m = p.mapPos
            OdinJs.nums(it, mapToWorld(m.x, DungeonScan.startX, DungeonScan.roomGap), mapToWorld(m.z, DungeonScan.startY, DungeonScan.roomGap))
        }
        o.n("yaw", p.yaw)
        out.append('{').append(o.sb).append('}')
    }

    /**
     * A map pixel coordinate to the world block coordinate it stands for (RunRecorder's formula):
     * the map's 128 px span is centred on 0, two pixels per map unit, [start] the grid's corner in
     * map units and [roomGap] one room plus its connector, in map units, for 32 blocks.
     */
    internal fun mapToWorld(map: Int, start: Int, roomGap: Int): Double = ((map + 128) / 2.0 - start) * 32.0 / roomGap - 200

    /** isDead going true is a death, going false a revive (game thread, from Odin's tab list). */
    private fun deathTransitions() {
        val seen = HashSet<String>()
        for (p in teammates()) {
            seen += p.name
            val was = lastDead.put(p.name, p.isDead)
            if (was == null || was == p.isDead) continue
            val j = OdinJs().s("name", p.name).s("src", "tab").n("deaths", deaths[p.name] ?: 0)
            j.safe("clazz") { OdinJs.str(it, p.clazz.name) }
            Rec.emit(if (p.isDead) "death" else "revive", j.toString())
        }
        lastDead.keys.retainAll(seen)
    }

    // ------------------------------------------------------------------ rooms and doors

    private fun roomId(r: DungeonRoom?): Int? = r?.let { System.identityHashCode(it) }

    /** One room in full; [RoomKeys.key] may read a loaded chunk, so this runs on the game thread only. */
    private fun room(r: DungeonRoom): String {
        val j = OdinJs(512)
        j.n("id", roomId(r))
        j.safe("name") { OdinJs.str(it, r.name) }
        j.safe("type") { OdinJs.str(it, r.type.name) }
        j.safe("dtype") { OdinJs.str(it, r.data?.type?.name) }
        j.safe("shape") { OdinJs.str(it, r.shape.name) }
        j.safe("rot") { OdinJs.str(it, r.rotation?.name) }
        j.safe("rotKnown") { it.append(RoomKeys.rotationKnown(r)) }
        j.safe("clay") { val c = r.clayPos; if (c == null) it.append("null") else OdinJs.nums(it, c.x, c.y, c.z) }
        j.safe("highest") { OdinJs.num(it, r.highestBlock) }
        j.arr("tiles", r.tiles.toList()) { out, t -> OdinJs.nums(out, t.x, t.z) }
        j.safe("center") { val c = r.center; if (c == null) it.append("null") else OdinJs.nums(it, c.x, c.z) }
        j.safe("checkmark") { OdinJs.str(it, r.checkmark.name) }
        j.b("walkedInto", r.walkedInto)
        j.b("known1x1", r.isKnown1x1)
        j.safe("viewable") { it.append(r.isViewable) }
        j.safe("found") { OdinJs.num(it, r.foundSecrets) }
        j.safe("max") { OdinJs.num(it, r.data?.maxSecrets) }
        j.safe("crypts") { OdinJs.num(it, r.data?.crypts) }
        j.safe("trapped") { OdinJs.num(it, r.data?.trappedChests) }
        j.arr("cores", r.data?.cores?.toList()) { out, c -> OdinJs.num(out, c) }
        j.safe("key") { OdinJs.str(it, RoomKeys.key(r)) }
        return j.toString()
    }

    /** A `room` line for each room whose state changed, and `"gone":true` for one Odin dropped. */
    private fun roomLines() {
        val rooms = DungeonScan.rooms.toList()
        val now = HashSet<Int>()
        for (r in rooms) {
            val id = System.identityHashCode(r)
            now += id
            val s = try { room(r) } catch (t: Throwable) { "\"id\":$id,\"@error\":${RecorderFiles.q(t.toString())}" }
            putRoom(id, s)
        }
        for (id in lastRooms) if (id !in now) {
            Rec.changed("odin:room:$id", "gone")
            Rec.emit("room", "\"id\":$id,\"gone\":true")
        }
        lastRooms.clear(); lastRooms += now
    }

    private fun putRoom(id: Int, state: String) {
        if (!Rec.changed("odin:room:$id", state) && kfNow() == 0L) return
        Rec.emit("room", state + kf())
    }

    /** The whole grid: its geometry, every tile and path hint, and every room (keyframes, and every 5 minutes). */
    private fun rooms(): String {
        val j = OdinJs(8192)
        j.obj("grid") {
            safe("roomSize") { it.append(DungeonScan.roomSize) }
            safe("startX") { it.append(DungeonScan.startX) }
            safe("startY") { it.append(DungeonScan.startY) }
            safe("roomGap") { it.append(DungeonScan.roomGap) }
            safe("connectionGap") { it.append(DungeonScan.connectionGap) }
        }
        // Tiles in Odin's array order (index = position in the array): [x, z, room id or null].
        j.arr("tiles", DungeonScan.tiles.toList()) { out, t ->
            val p = t.position; out.append('['); out.append(p.x).append(',').append(p.z).append(','); OdinJs.num(out, roomId(t.room)); out.append(']')
        }
        j.arr("pathHints", DungeonScan.pathHints.toList()) { out, t ->
            val p = t.position; out.append('['); out.append(p.x).append(',').append(p.z).append(','); OdinJs.num(out, roomId(t.room)); out.append(']')
        }
        j.arr("rooms", DungeonScan.rooms.toList()) { out, r -> out.append('{').append(room(r)).append('}') }
        return j.toString()
    }

    private fun door(out: StringBuilder, d: DungeonDoor, rgba: Int?) {
        out.append('[').append(d.worldX).append(',').append(d.worldZ).append(',')
        OdinJs.str(out, d.type.name); out.append(','); OdinJs.str(out, d.rotation.name); out.append(',')
        OdinJs.num(out, rgba); out.append(',').append(d.originTileIndex).append(',').append(d.destinationTileIndex).append(']')
    }

    /** Doors as `[worldX,worldZ,type,rotation,rgba,from,to]`, sorted by position (the map is unordered). */
    private fun doors(): String {
        val j = OdinJs(1024)
        j.arr("doors", DungeonScan.doors.values.sortedWith(compareBy({ it.worldX }, { it.worldZ }))) { out, d -> door(out, d, runCatching { d.color.rgba }.getOrNull()) }
        j.arr("viewable", DungeonScan.viewableDoors.toList()) { out, (d, c) -> door(out, d, c.rgba) }
        return j.toString()
    }

    // ------------------------------------------------------------------ where you are

    /** The 6x6 room tile a block column is in (index x + 6z), or null outside the grid. */
    internal fun tileOf(bx: Int, bz: Int): Int? {
        val tx = (bx + 201) shr 5
        val tz = (bz + 201) shr 5
        return if (tx in 0..5 && tz in 0..5) tx + tz * 6 else null
    }

    private fun where(): String {
        val p = EngineerClient.mc.player ?: return "\"none\":true"
        val bp = p.blockPosition()
        val j = OdinJs(256)
        j.safe("bp") { OdinJs.nums(it, bp.x, bp.y, bp.z) }
        val tile = tileOf(bp.x, bp.z)
        j.n("tile", tile)
        j.safe("tileRoom") { out ->
            val tx = tile?.rem(6); val tz = tile?.div(6)
            val r = if (tile == null) null else DungeonScan.rooms.firstOrNull { room -> room.tiles.any { it.x == tx && it.z == tz } }
            if (r == null) out.append("null") else { out.append('['); OdinJs.num(out, roomId(r)); out.append(','); OdinJs.str(out, r.name); out.append(']') }
        }
        val cur = WorldScan.currentRoom
        j.safe("odinRoom") { out -> if (cur == null) out.append("null") else { out.append('['); OdinJs.num(out, roomId(cur)); out.append(','); OdinJs.str(out, cur.name); out.append(']') } }
        j.safe("rel") { out -> relative(out, cur, bp) }
        j.safe("rotKnown") { out -> if (cur == null) out.append("null") else out.append(RoomKeys.rotationKnown(cur)) }
        return j.toString()
    }

    /** [pos] in [room]'s own coordinates (rotated to north, from its clay corner); null until both are known. */
    internal fun relative(out: StringBuilder, room: DungeonRoom?, pos: BlockPos) {
        if (room == null || room.clayPos == null || room.rotation == null) { out.append("null"); return }
        val r = room.getRelativeCoords(pos)
        OdinJs.nums(out, r.x, r.y, r.z)
    }

    // ------------------------------------------------------------------ puzzles, terminals

    private fun puzzles(): String {
        val j = OdinJs(512)
        // Mutated by Odin on the network thread: the copy itself may throw, which `safe` turns into an @error.
        j.safe("puzzles") { out ->
            val list = DungeonListener.puzzles.toList()
            OdinJs.array(out, list) { o, p ->
                val x = OdinJs(128).s("name", p.name).s("display", p.displayName).s("status", p.status?.name).s("player", p.player).n("timeToBeat", p.timeToBeat)
                o.append('{').append(x.sb).append('}')
            }
        }
        return j.toString()
    }

    private fun term() {
        val t: TerminalHandler? = TerminalUtils.currentTerm
        if (t == null) {
            if (termOpen) { termOpen = false; Rec.changed("odin:term", "closed"); Rec.emit("term", "\"open\":false") }
            else if (forcing != 0L) Rec.emit("term", "\"open\":false" + kf())
            return
        }
        termOpen = true
        put("term", terminal(t), null)
    }

    internal fun terminal(t: TerminalHandler): String {
        val j = OdinJs(256)
        j.b("open", true)
        j.safe("type") { OdinJs.str(it, t.type.name) }
        j.safe("termName") { OdinJs.str(it, t.type.termName) }
        j.safe("windowSize") { it.append(t.type.windowSize) }
        j.n("timeOpened", t.timeOpened)
        j.n("ticksOpened", t.ticksOpened)
        j.arr("solution", t.solution.toList()) { out, s -> OdinJs.num(out, s) }
        j.arr("clickedSlots", t.clickedSlots.toList()) { out, (slot, button) -> OdinJs.nums(out, slot, button) }
        j.n("lastClickTime", t.lastClickTime)
        return j.toString()
    }

    // ------------------------------------------------------------------ stats, location, modules

    private fun sb(): String {
        val a = ActionBarListener
        val j = OdinJs(400)
        j.safe("bar") { out ->
            val o = OdinJs(256).n("hp", a.currentHealth).n("maxHp", a.maxHealth).n("mana", a.currentMana).n("maxMana", a.maxMana)
                .n("overflow", a.overflowMana).n("speed", a.currentSpeed).n("defense", a.currentDefense).n("ehp", a.effectiveHP)
                .n("vitality", a.currentVitality).n("maxVitality", a.maxVitality).b("vitalityShown", a.isVitalityShown)
            out.append('{').append(o.sb).append('}')
        }
        j.safe("blessings") { out ->
            out.append('{')
            Blessing.entries.forEachIndexed { i, b -> if (i > 0) out.append(','); PacketJson.str(out, b.name); out.append(':').append(b.current) }
            out.append('}')
        }
        j.safe("tps") { OdinJs.num(it, ServerUtils.averageTps) }
        j.safe("ping") { it.append(ServerUtils.currentPing) }
        j.safe("avgPing") { it.append(ServerUtils.averagePing) }
        return j.toString()
    }

    private fun loc(): String {
        val j = OdinJs(256)
        j.safe("area") { OdinJs.str(it, LocationUtils.currentArea.name) }
        j.safe("areaName") { OdinJs.str(it, LocationUtils.currentArea.displayName) }
        j.safe("lobby") { OdinJs.str(it, LocationUtils.lobbyId) }
        j.safe("skyblock") { it.append(LocationUtils.isInSkyblock) }
        j.arr("party", PartyUtils.members.toList()) { out, s -> OdinJs.str(out, s) }
        j.safe("leader") { OdinJs.str(it, PartyUtils.partyLeader) }
        j.safe("inParty") { it.append(PartyUtils.isInParty) }
        j.safe("isLeader") { it.append(PartyUtils.isLeader()) }
        return j.toString()
    }

    private val gson = com.google.gson.Gson()
    private val hiddenGetter by lazy { runCatching { Setting::class.java.getDeclaredMethod("getHidden").apply { isAccessible = true } }.getOrNull() }

    // Odin's Saving interface (a setting's saved form) is Kotlin-internal: public in bytecode, reached by reflection.
    private val saving: Class<*>? by lazy { runCatching { Class.forName("com.odtheking.odin.clickgui.settings.Saving") }.getOrNull() }
    private val savingWrite by lazy { runCatching { saving?.getMethod("write", com.google.gson.Gson::class.java) }.getOrNull() }

    private fun hidden(s: Setting<*>): Boolean = runCatching { hiddenGetter?.invoke(s) as? Boolean }.getOrNull() ?: false

    /**
     * Every module (Odin's and ours, which register through Odin), on or off, with every setting's
     * saved form; then every loaded mod and its version. Secrets stay out: string settings named
     * like a key, token, webhook or url, and settings Odin hides from its GUI (internal data).
     */
    private fun modules(): String {
        val j = OdinJs(32_768)
        j.arr("modules", ModuleManager.modules.values.sortedBy { it.name }) { out, m ->
            val o = OdinJs(512).s("name", m.name).b("enabled", m.enabled)
            o.safe("category") { OdinJs.str(it, m.category.name) }
            o.safe("settings") { so ->
                so.append('{')
                var first = true
                for ((name, s) in m.settings) {
                    if (!first) so.append(',')
                    first = false
                    PacketJson.str(so, name); so.append(':')
                    val mark = so.length
                    try { settingValue(so, s, name) } catch (t: Throwable) { so.setLength(mark); PacketJson.error(so, t) }
                }
                so.append('}')
            }
            out.append('{').append(o.sb).append('}')
        }
        j.arr("mods", FabricLoader.getInstance().allMods.map { it.metadata.id to it.metadata.version.friendlyString }.sortedBy { it.first }) { out, (id, v) ->
            out.append('['); OdinJs.str(out, id); out.append(','); OdinJs.str(out, v); out.append(']')
        }
        return j.toString()
    }

    private fun settingValue(out: StringBuilder, s: Setting<*>, name: String) {
        when {
            hidden(s) -> out.append("\"<hidden>\"")
            s is StringSetting && SECRET_NAME.containsMatchIn(name) -> out.append("\"<redacted>\"")
            saving?.isInstance(s) == true -> RichJson.json(out, savingWrite?.invoke(s, gson) as com.google.gson.JsonElement?)
            else -> OdinJs.str(out, runCatching { s.value?.toString() }.getOrNull())
        }
    }

    // ------------------------------------------------------------------ chat as Odin sees it

    /**
     * Chat after every Odin listener ran: the plain text, the component, and whether something
     * cancelled it (hid it from chat). Also counts ghost deaths per player, notes leaps, and takes
     * a puzzle snapshot, since Odin updates puzzles from chat and tab on the network thread.
     */
    private fun chat(text: String, c: net.minecraft.network.chat.Component, cancelled: Boolean) {
        val plain = COLOR.replace(text, "")
        if (Rec.privateText(plain)) {
            Rec.emit("odin.chat", "\"hidden\":\"private\",\"cancelled\":$cancelled,\"thr\":\"${thr()}\"")
            return
        }
        val sb = StringBuilder(128 + text.length * 2)
        sb.append("\"text\":"); PacketJson.str(sb, text)
        sb.append(",\"c\":"); val mark = sb.length
        try { RichJson.component(sb, c) } catch (t: Throwable) { sb.setLength(mark); PacketJson.error(sb, t) }
        sb.append(",\"cancelled\":").append(cancelled).append(",\"thr\":\"").append(thr()).append('"')
        Rec.emit("odin.chat", sb.toString())

        ghostDeath(plain)?.let { (name, asText) ->
            val n = deaths.merge(name, 1, Int::plus) ?: 1
            val j = OdinJs().s("name", name).s("src", "chat").n("deaths", n).s("text", plain)
            if (asText != name) j.s("as", asText)
            Rec.emit("death", j.toString())
        }
        leap(text)
        put("puzzles", puzzles(), "chat")
    }

    /** The player in a ghost line, and the word the line used ("You" for yourself). */
    internal fun ghostDeath(plain: String, self: () -> String? = { EngineerClient.mc.player?.name?.string }): Pair<String, String>? {
        val m = GHOST.find(plain) ?: return null
        val who = m.groupValues[1]
        val name = if (who == "You") self() ?: who else who
        return name to who
    }

    /** A leap: Hypixel's "You have teleported to X!" (yours) or Odin's party "Leaped to X!" (a teammate's). */
    private fun leap(text: String) {
        val tp = P3ChatParser.teleportedTo(text)
        val party = if (tp == null) P3ChatParser.partyLine(text) else null
        val to = tp ?: party?.let { P3ChatParser.leapedTo(it.message) } ?: return
        val mates = teammates()
        val j = OdinJs(256).s("via", if (tp != null) "teleported" else "party").s("from", party?.ign ?: EngineerClient.mc.player?.name?.string).s("to", to)
        j.arr("order", DungeonUtils.leapTeammates.toList()) { out, p -> OdinJs.str(out, p.name) }
        j.safe("self") { val p = EngineerClient.mc.player; if (p == null) it.append("null") else OdinJs.nums(it, p.x, p.y, p.z) }
        j.safe("target") { val e = mates.firstOrNull { m -> m.name == to }?.entity; if (e == null) it.append("null") else OdinJs.nums(it, e.x, e.y, e.z) }
        j.s("thr", thr())
        Rec.emit("leap", j.toString())
    }

    /** The action bar, written only when it differs from the last one (it is resent every second). */
    private fun overlay(text: String, c: net.minecraft.network.chat.Component, cancelled: Boolean) {
        if (!Rec.changed("odin:overlay", "$cancelled|$text")) return
        val sb = StringBuilder(128 + text.length * 2)
        sb.append("\"overlay\":true,\"text\":"); PacketJson.str(sb, text)
        sb.append(",\"c\":"); val mark = sb.length
        try { RichJson.component(sb, c) } catch (t: Throwable) { sb.setLength(mark); PacketJson.error(sb, t) }
        sb.append(",\"cancelled\":").append(cancelled).append(",\"thr\":\"").append(thr()).append('"')
        Rec.emit("odin.chat", sb.toString())
    }
}
