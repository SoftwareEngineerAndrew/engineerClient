package com.engineerclient.recorder

import com.engineerclient.EngineerClient
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.RoomEnterEvent
import com.odtheking.odin.events.SecretsUpdateEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.boss.DragonCheck
import com.odtheking.odin.features.impl.boss.WitherDragons
import com.odtheking.odin.features.impl.boss.WitherDragonsEnum
import com.odtheking.odin.features.impl.dungeon.map.DungeonMap
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.features.impl.dungeon.map.SpecialColumn
import com.odtheking.odin.features.impl.dungeon.map.tile.DungeonRoom
import com.odtheking.odin.utils.network.WebUtils
import com.odtheking.odin.utils.skyblock.SplitsManager
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.Vec3i
import net.minecraft.world.entity.Entity
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Cached reflective reads of Odin's private state. Odin keeps most of what its solvers and boss
 * trackers know in private fields of Kotlin objects (static fields on the object's class), so a
 * recorder that wants it has to reach in. Every lookup is by name and cached; a field that is not
 * there (another Odin version) throws [Unavailable] so the caller can say so once and carry on.
 */
internal object Reflect {

    class Unavailable(val owner: String, val field: String) : RuntimeException("$owner.$field", null, false, false)

    private val NONE = Any()
    private val classes = ConcurrentHashMap<String, Any>()
    private val fields = ConcurrentHashMap<String, Any>()

    /** The class, or null when it does not exist (cached either way). */
    fun cls(name: String): Class<*>? = classes.getOrPut(name) {
        runCatching { Class.forName(name, false, Reflect::class.java.classLoader) }.getOrNull() ?: NONE
    } as? Class<*>

    /** The named field declared on [className] or a superclass, made accessible; throws [Unavailable]. */
    fun fieldOf(className: String, name: String): Field {
        val f = fields.getOrPut("$className#$name") {
            var k: Class<*>? = cls(className)
            var found: Field? = null
            while (k != null && found == null) {
                found = runCatching { k.getDeclaredField(name) }.getOrNull()
                k = k.superclass
            }
            found?.takeIf { runCatching { it.trySetAccessible() }.getOrDefault(false) } ?: NONE
        }
        return f as? Field ?: throw Unavailable(className, name)
    }

    /**
     * The field's current value: a static field (a Kotlin object's property) directly, an instance
     * field off the class's `INSTANCE`.
     */
    fun field(className: String, name: String): Any? {
        val f = fieldOf(className, name)
        return if (Modifier.isStatic(f.modifiers)) f.get(null) else f.get(instance(className) ?: throw Unavailable(className, "INSTANCE"))
    }

    /** An instance field of [obj] (for Odin's package-private data classes and enums). */
    fun field(obj: Any, name: String): Any? = fieldOf(obj.javaClass.name, name).get(obj)

    /** A Kotlin object's singleton. */
    fun instance(className: String): Any? = runCatching { fieldOf(className, "INSTANCE").get(null) }.getOrNull()

    /** Whether the Odin module [className] is on; null when it is not a module (or missing). */
    fun moduleEnabled(className: String): Boolean? = (instance(className) as? Module)?.let { runCatching { it.enabled }.getOrNull() }
}

/**
 * Odin's internal working state, for an LLM building dungeon mods: what each puzzle solver has
 * worked out, the boss trackers (dragons, relics, Livid, the tick timers and terminal times), the
 * blood camp predictor, the P3 device helpers, Odin's splits, its map-sync websocket, the room
 * library and waypoints, the special-column guess, the end-of-run stats and Odin's own clocks.
 *
 * All of it is read on the game thread at the end of each tick and frozen into strings there; each
 * group is written as a line only when it changes. The values that tick on their own (Odin's tick
 * counters) are written with the rest but do not count as a change, and `odin.clocks` anchors them
 * at each world load and every minute instead, so they cost one line a minute rather than one a
 * tick. Read-only: nothing here calls into Odin except public getters known to be pure.
 *
 * Version-fragile by nature, so every read is guarded: a field that is gone is reported once per
 * recording (`{"k":"odin.priv","mod","unavailable"}`) and the rest of the group still written.
 */
object OdinInternals {

    private const val BOSS = "com.odtheking.odin.features.impl.boss."
    private const val DUNGEON = "com.odtheking.odin.features.impl.dungeon."
    private const val PUZZLE = "com.odtheking.odin.features.impl.dungeon.puzzlesolvers."

    /** One member of a group: its JSON name, whether it ticks on its own (left out of the change check), how to read it. */
    internal class M(val name: String, val ticking: Boolean = false, val read: () -> Any?)

    /** A group written as one line: [kind], `"mod"` [mod], gated on the Odin module [module] being on (null: always). */
    internal class Probe(val kind: String, val mod: String, val module: String?, val members: List<M>)

    /** A member read off a private static field of [cls]. */
    private fun f(cls: String, name: String, ticking: Boolean = false) = M(name, ticking) { Reflect.field(cls, name) }

    private fun fields(cls: String, vararg names: String) = names.map { f(cls, it) }

    private val probes: List<Probe> = listOf(
        // ---- puzzle solvers (all run under the PuzzleSolvers module)
        Probe("odin.solver", "QuizSolver", PUZZLE + "PuzzleSolvers", fields(PUZZLE + "QuizSolver", "timer", "triviaAnswers", "triviaOptions")),
        Probe("odin.solver", "WaterSolver", PUZZLE + "PuzzleSolvers",
            fields(PUZZLE + "WaterSolver", "patternIdentifier", "openedWaterTicks", "solutions") + f(PUZZLE + "WaterSolver", "tickCounter", ticking = true)),
        Probe("odin.solver", "BlazeSolver", PUZZLE + "PuzzleSolvers", fields(PUZZLE + "BlazeSolver", "blazes", "roomType", "lastBlazeCount")),
        Probe("odin.solver", "BoulderSolver", PUZZLE + "PuzzleSolvers", fields(PUZZLE + "BoulderSolver", "currentPositions")),
        Probe("odin.solver", "IceFillSolver", PUZZLE + "PuzzleSolvers", fields(PUZZLE + "IceFillSolver", "currentPatterns")),
        Probe("odin.solver", "TPMazeSolver", PUZZLE + "PuzzleSolvers", fields(PUZZLE + "TPMazeSolver", "tpPads", "correctPortals", "visited", "best")),
        Probe("odin.solver", "WeirdosSolver", PUZZLE + "PuzzleSolvers", fields(PUZZLE + "WeirdosSolver", "correctPos", "wrongPositions")),
        Probe("odin.solver", "BeamsSolver", PUZZLE + "PuzzleSolvers", fields(PUZZLE + "BeamsSolver", "currentLanternPairs", "scanned")),
        Probe("odin.solver", "PuzzleSolvers", PUZZLE + "PuzzleSolvers", listOf(M("puzzleTimersMap") {
            (Reflect.field(PUZZLE + "PuzzleSolvers", "puzzleTimersMap") as? Map<*, *>)?.entries?.toList()?.associate { (k, t) ->
                k.toString() to t?.let { mapOf("timeEntered" to Reflect.field(it, "timeEntered"), "sentMessage" to Reflect.field(it, "sentMessage")) }
            }
        })),

        // ---- boss trackers
        Probe("odin.boss", "Boss", null, listOf(
            M("f7Phase") { DungeonUtils.getF7Phase() },
            M("dragons") { dragons() },
            M("priorityDragon") { WitherDragons.priorityDragon?.name },
            M("currentTick", ticking = true) { WitherDragons.currentTick },
            M("dragonHealth") { DragonCheck.dragonHealthMap.entries.toList().map { (u, p) -> listOf(u.toString(), (p.first as? Entity)?.id, p.second) } },
            M("lastDragonDeath") { DragonCheck.lastDragonDeath?.name },
            M("dragonsOn") { Reflect.moduleEnabled(BOSS + "WitherDragons") },
        )),
        Probe("odin.priv", "TickTimers", BOSS + "TickTimers", fields(BOSS + "TickTimers", "necronTime", "goldorTickTime", "goldorStartTime", "padTickTime",
            "lightningTickTime", "pyTriggered", "pyTickTime", "stormTick", "secretsCounter", "fireFreezeTime")),
        Probe("odin.priv", "TerminalTimes", BOSS + "TerminalTimes", fields(BOSS + "TerminalTimes", "completed", "times", "gateBlown", "sectionTimer", "phaseTimer") +
            f(BOSS + "TerminalTimes", "currentTick", ticking = true)),
        Probe("odin.priv", "KingRelics", BOSS + "KingRelics", fields(BOSS + "KingRelics", "currentRelic", "relicPlaceTick", "relicTicksToSpawn", "hasAnnouncedSpawn") +
            f(BOSS + "KingRelics", "serverTickCounter", ticking = true)),
        Probe("odin.priv", "LividSolver", BOSS + "LividSolver", listOf(
            M("currentLivid") { (Reflect.field(BOSS + "LividSolver", "currentLivid") as? Enum<*>)?.name },
            M("lividEntity") { Reflect.field(BOSS + "LividSolver", "currentLivid")?.let { Reflect.field(it, "entity") } },
            f(BOSS + "LividSolver", "invulnTime"),
        )),
        Probe("odin.priv", "SpiritBear", BOSS + "SpiritBear", fields(BOSS + "SpiritBear", "timer", "kills")),
        Probe("odin.priv", "TerracottaTimer", BOSS + "TerracottaTimer", fields(BOSS + "TerracottaTimer", "terracottaSpawning")),

        // ---- blood camp: Odin's watcher and per-mob prediction state (EntityData is package-private)
        Probe("odin.bloodcamp", "BloodCamp", DUNGEON + "BloodCamp", fields(DUNGEON + "BloodCamp", "currentWatcherEntity", "firstSpawns", "moveTimeSeconds", "startTime") +
            f(DUNGEON + "BloodCamp", "currentTickTime", ticking = true) + M("entityDataMap") {
                (Reflect.field(DUNGEON + "BloodCamp", "entityDataMap") as? Map<*, *>)?.entries?.toList()?.map { (e, d) ->
                    linkedMapOf("eid" to (e as? Entity)?.id, "start" to d?.let { Reflect.field(it, "startVector") }, "last" to d?.let { Reflect.field(it, "lastPosition") },
                        "started" to d?.let { Reflect.field(it, "started") }, "firstSpawns" to d?.let { Reflect.field(it, "firstSpawns") },
                        "deltaHistory" to d?.let { Reflect.field(it, "deltaHistory") })
                }
            }),

        // ---- P3 device helpers
        Probe("odin.p3", "ArrowAlign", BOSS + "ArrowAlign", fields(BOSS + "ArrowAlign", "recentClickTimestamps", "clicksRemaining", "currentFrameRotations", "targetSolution")),
        Probe("odin.p3", "ArrowsDevice", BOSS + "ArrowsDevice", fields(BOSS + "ArrowsDevice", "markedPositions", "targetPosition", "isDeviceComplete", "optimalAimPositions")),
        Probe("odin.p3", "InactiveWaypoints", BOSS + "InactiveWaypoints", fields(BOSS + "InactiveWaypoints", "inactiveList", "section", "terminals", "levers", "device",
            "gate", "isComplete", "firstInSection", "lastCompleted")),
        Probe("odin.p3", "SimonSays", BOSS + "SimonSays", fields(BOSS + "SimonSays", "clickInOrder", "clickNeeded", "firstPhase", "startClickCounter", "lastLanternTick")),
        Probe("odin.p3", "BreakerDisplay", DUNGEON + "BreakerDisplay", fields(DUNGEON + "BreakerDisplay", "charges", "maxCharges")),

        // ---- map: the special column guess's inputs (colorGuessForUnknown is not known to be pure, so it is never called)
        Probe("odin.special", "SpecialColumn", null, listOf(
            M("openedSpecialRooms") { SpecialColumn.openedSpecialRooms.toList().map { roomName(it) } },
            M("discoveredFullSpecialColumn") { SpecialColumn.discoveredFullSpecialColumn },
            M("columnRoomCount") { SpecialColumn.columnRoomCount },
            M("discovered1x1s") { SpecialColumn.discovered1x1s },
            M("column") { SpecialColumn.column },
        )),
        Probe("room.wp", "SecretClicked", DUNGEON + "SecretClicked", fields(DUNGEON + "SecretClicked", "clickedSecretsList")),

        // ---- end-of-run stats Odin parsed after "> EXTRA STATS <" (written when they change, i.e. as they are parsed)
        Probe("odin.endstats", "ExtraStats", BOSS + "ExtraStats", fields(BOSS + "ExtraStats", "extraStats")),
    )

    /** Each dragon's live state, from the public enum. */
    private fun dragons(): List<Map<String, Any?>> = WitherDragonsEnum.entries.map {
        linkedMapOf("name" to it.name, "state" to it.state, "timeToSpawn" to it.timeToSpawn, "timesSpawned" to it.timesSpawned,
            "uuid" to it.entityUUID, "sprayed" to it.isSprayed, "spawnedTime" to it.spawnedTime)
    }

    private fun roomName(r: DungeonRoom): String = runCatching { r.name }.getOrNull() ?: "?"

    // ------------------------------------------------------------------ install and tick

    @Volatile private var sessionSeen: RecorderSession? = null
    private val unavailable = HashSet<String>()
    private var clocksDue = true
    private var lastClocksMs = 0L
    private var lastConnected: Boolean? = null
    private var lastPaul: Boolean? = null
    private val labels = HashMap<String, String>()

    /** Whether to read at all: a session open and the module's Odin Internals setting on. */
    private val on: Boolean get() = Rec.active && runCatching { DungeonRecorder.odinInternals }.getOrDefault(false)

    fun install() {
        on<TickEvent.End>(priority = Int.MIN_VALUE) { if (on) EngineerClient.safely("recorder odin internals") { tick() } }
        on<LevelEvent.Load> { clocksDue = true }
        on<RoomEnterEvent>(priority = Int.MIN_VALUE) { if (on) EngineerClient.safely("recorder odin sync room") { syncOut("room_enter", room) } }
        on<SecretsUpdateEvent>(priority = Int.MIN_VALUE) { if (on) EngineerClient.safely("recorder odin sync secrets") { syncOut("secrets_update", room, foundSecrets) } }
        EventBus.subscribe(this)
    }

    private fun tick() {
        val s = Rec.session
        if (s !== sessionSeen) {
            // A new recording: everything is reported afresh in it (Rec.changed starts over too).
            sessionSeen = s
            unavailable.clear(); labels.clear()
            lastConnected = null; lastPaul = null
            clocksDue = true
            roomDb()
        }
        for (p in probes) runProbe(p)
        splits()
        roomWaypoints()
        labels()
        sync()
        val now = System.currentTimeMillis()
        if (clocksDue || now - lastClocksMs >= 60_000) { clocksDue = false; lastClocksMs = now; clocks() }
    }

    private fun runProbe(p: Probe) {
        if (p.module != null && Reflect.moduleEnabled(p.module) == false) return
        val parts = ArrayList<Part>(p.members.size)
        for (m in p.members) {
            val json = try {
                value(m.read())
            } catch (u: Reflect.Unavailable) {
                missing(p.mod, u.field); continue
            } catch (t: Throwable) {
                StringBuilder().also { PacketJson.error(it, t) }.toString()
            }
            parts += Part(m.name, json, m.ticking)
        }
        val (body, key) = compose(p.mod, parts)
        if (Rec.changed("u12:${p.kind}:${p.mod}", key)) Rec.emit(p.kind, body)
    }

    /** A field this Odin does not have: said once per recording, then left out. */
    private fun missing(mod: String, field: String) {
        if (unavailable.add("$mod.$field")) Rec.emit("odin.priv", "\"mod\":${RecorderFiles.q(mod)},\"unavailable\":${RecorderFiles.q(field)}")
    }

    /** Odin's split rows; the current row's live time is written but is not a change. */
    private fun splits() {
        val rows = try { SplitsManager.currentRows().toList() } catch (t: Throwable) { missing("SplitsManager", "currentRows"); return }
        val all = rows.joinToString(",", "[", "]") { "[${RecorderFiles.q(it.name)},${it.time},${it.tickTime},${it.isCurrent}]" }
        val key = rows.joinToString(",") { if (it.isCurrent) "${it.name}|cur" else "${it.name}|${it.time}|${it.tickTime}" }
        if (Rec.changed("u12:splits", key)) Rec.emit("odin.splits", "\"rows\":$all,\"cols\":[\"name\",\"time\",\"tickTime\",\"current\"]")
    }

    /** Each scanned room's waypoints with their clicked state, one line per room when it changes. */
    private fun roomWaypoints() {
        val rooms = try { DungeonScan.rooms.toList() } catch (t: Throwable) { return }
        for (r in rooms) {
            val wps = try { r.waypoints.toList() } catch (t: Throwable) { continue }
            if (wps.isEmpty()) continue
            val name = roomName(r)
            val sb = StringBuilder(64 + wps.size * 48)
            sb.append("\"room\":").append(RecorderFiles.q(name)).append(",\"core\":").append(RecorderFiles.q(r.topLeft.toString())).append(",\"wps\":[")
            wps.forEachIndexed { i, w ->
                if (i > 0) sb.append(',')
                val mark = sb.length
                try {
                    sb.append("{\"pos\":"); PacketJson.write(sb, w.blockPos, 0)
                    sb.append(",\"title\":").append(RecorderFiles.q(w.title))
                    sb.append(",\"type\":").append(RecorderFiles.q(w.type?.name))
                    sb.append(",\"clicked\":").append(w.isClicked)
                    sb.append(",\"secret\":").append(runCatching<Boolean> { w.isSecret }.getOrNull()).append('}')
                } catch (t: Throwable) { sb.setLength(mark); PacketJson.error(sb, t) }
            }
            sb.append(']')
            val body = sb.toString()
            if (Rec.changed("u12:wp:$name:${r.topLeft}", body)) Rec.emit("room.wp", body)
        }
    }

    /** Who Odin thinks the watcher, the real Livid and each dragon are (by entity id), when that changes. */
    private fun labels() {
        fun label(key: String, eid: Int?, extra: String = "") {
            val v = "$eid$extra"
            if (labels[key] == v) return
            labels[key] = v
            Rec.emit("label", "\"by\":\"odin\",\"label\":${RecorderFiles.q(key)},\"eid\":$eid$extra")
        }
        runCatching { label("watcher", (Reflect.field(DUNGEON + "BloodCamp", "currentWatcherEntity") as? Entity)?.id) }
        runCatching {
            val livid = Reflect.field(BOSS + "LividSolver", "currentLivid")
            label("livid", (livid?.let { Reflect.field(it, "entity") } as? Entity)?.id, ",\"livid\":${RecorderFiles.q((livid as? Enum<*>)?.name)}")
        }
        runCatching {
            val health = DragonCheck.dragonHealthMap
            for (d in WitherDragonsEnum.entries) {
                val uuid: UUID? = d.entityUUID
                val eid = uuid?.let { (health[it]?.first as? Entity)?.id }
                label("dragon:${d.name}", eid, ",\"uuid\":${RecorderFiles.q(uuid?.toString())}")
            }
        }
    }

    // ------------------------------------------------------------------ map sync websocket and Paul

    /** Wraps the sync socket's message handler (once; again if Odin replaces it), and writes connection and Paul flips. */
    private fun sync() {
        val socket = try { DungeonMap.syncSocket } catch (t: Throwable) { missing("DungeonMap", "syncSocket"); return }
        try {
            val cur = Reflect.field(socket, "onMessageFunc")
            if (cur !is SyncTap) {
                @Suppress("UNCHECKED_CAST")
                socket.onMessage(SyncTap(cur as? Function1<String, Unit>))
            }
        } catch (u: Reflect.Unavailable) { missing("WebSocketConnection", u.field) }
        val c = runCatching { socket.connected }.getOrNull()
        if (c != null && c != lastConnected) { lastConnected = c; Rec.emit("odin.sync.state", "\"connected\":$c") }
        val paul = runCatching { DungeonUtils.isPaul }.getOrNull()
        if (paul != null && paul != lastPaul) { lastPaul = paul; Rec.emit("odin.paul", "\"paul\":$paul") }
    }

    /**
     * Odin's handler, with every message recorded first. It runs on the websocket's thread, so it
     * only queues the (immutable) string; Odin's own handler is called outside the guard, exactly as
     * before.
     */
    private class SyncTap(val inner: Function1<String, Unit>?) : (String) -> Unit {
        override fun invoke(msg: String) {
            try {
                if (on) Rec.emit("odin.sync", "\"dir\":\"in\",\"msg\":${RecorderFiles.q(msg)}")
            } catch (t: Throwable) { EngineerClient.logger.error("[ec] recorder odin sync failed", t) }
            inner?.invoke(msg)
        }
    }

    /** What Odin sends to the sync socket on these events: the room as its Gson writes it. */
    private fun syncOut(ev: String, room: DungeonRoom?, found: Int? = null) {
        val json = room?.let { runCatching { WebUtils.gson.toJson(it) }.getOrElse { t -> "!" + t } }
        val connected = runCatching { DungeonMap.syncSocket.connected }.getOrNull()
        Rec.emit("odin.sync", "\"dir\":\"out\",\"ev\":${RecorderFiles.q(ev)},\"connected\":$connected" +
            (found?.let { ",\"found\":$it" } ?: "") + ",\"room\":${room?.let { RecorderFiles.q(roomName(it)) } ?: "null"},\"msg\":${RecorderFiles.q(json)}")
    }

    // ------------------------------------------------------------------ once per recording, and clocks

    /**
     * Which room library Odin is using: the SHA-1 and entry count of its bundled rooms.json, and how
     * many room cores it has learned. The file is hashed off the game thread.
     */
    private fun roomDb() {
        val cores = runCatching { DungeonScan.roomCores.size }.getOrNull()
        val session = Rec.session ?: return
        Thread({
            EngineerClient.safely("recorder odin roomdb") {
                val path = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("odin").flatMap { it.findPath("assets/odin/rooms.json") }.orElse(null)
                val bytes = path?.let { runCatching { java.nio.file.Files.readAllBytes(it) }.getOrNull() }
                val count = bytes?.let { runCatching { com.google.gson.JsonParser.parseString(String(it, Charsets.UTF_8)).let { j -> if (j.isJsonArray) j.asJsonArray.size() else if (j.isJsonObject) j.asJsonObject.size() else null } }.getOrNull() }
                if (Rec.session === session) Rec.emit("odin.roomdb", "\"sha1\":${RecorderFiles.q(bytes?.let { sha1(it) })},\"bytes\":${bytes?.size},\"count\":$count,\"cores\":$cores")
            }
        }, "ec-recorder-roomdb").apply { isDaemon = true }.start()
    }

    /** Odin's own tick counters side by side, so its tick-based numbers can be lined up with t and n. */
    private fun clocks() {
        fun r(read: () -> Any?): String = try { value(read()) } catch (t: Throwable) { "null" }
        Rec.emit("odin.clocks", "\"splits\":${r { Reflect.field("com.odtheking.odin.utils.skyblock.SplitsManager", "tickCounter") }}" +
            ",\"terminalTimes\":${r { Reflect.field(BOSS + "TerminalTimes", "currentTick") }}" +
            ",\"kingRelics\":${r { Reflect.field(BOSS + "KingRelics", "serverTickCounter") }}" +
            ",\"witherDragons\":${r { WitherDragons.currentTick }}" +
            ",\"waterSolver\":${r { Reflect.field(PUZZLE + "WaterSolver", "tickCounter") }}" +
            ",\"bloodCamp\":${r { Reflect.field(DUNGEON + "BloodCamp", "currentTickTime") }}")
    }

    // ------------------------------------------------------------------ pure helpers

    /** One member's frozen JSON; [ticking] members are written but not compared. */
    internal data class Part(val name: String, val json: String, val ticking: Boolean)

    /** The line body (`"mod":..,name:json,...`) and the change key (the same without ticking members). */
    internal fun compose(mod: String, parts: List<Part>): Pair<String, String> {
        val body = StringBuilder(64).append("\"mod\":").append(RecorderFiles.q(mod))
        val key = StringBuilder(64)
        for (p in parts) {
            body.append(',').append(RecorderFiles.q(p.name)).append(':').append(p.json)
            if (!p.ticking) key.append(p.name).append('=').append(p.json).append(';')
        }
        return body.toString() to key.toString()
    }

    /**
     * [v] as JSON, frozen now. Entities become `{"eid":id}` (their position is the entity rows'
     * business, and it would make every tick a change); collections are copied as they are walked;
     * Kotlin pairs and triples are arrays; everything else goes through [PacketJson.write].
     */
    internal fun value(v: Any?): String = StringBuilder().also { value(it, v, 0) }.toString()

    private fun value(sb: StringBuilder, v: Any?, depth: Int) {
        if (depth > 12) { PacketJson.write(sb, v, depth); return }
        when (v) {
            null -> sb.append("null")
            is Entity -> sb.append("{\"eid\":").append(v.id).append('}')
            is Map<*, *> -> {
                val entries = v.entries.toList()
                sb.append('{')
                entries.forEachIndexed { i, (k, x) -> if (i > 0) sb.append(','); PacketJson.str(sb, key(k)); sb.append(':'); element(sb, x, depth + 1) }
                sb.append('}')
            }
            is Iterable<*> -> list(sb, v.toList(), depth)
            is Array<*> -> list(sb, v.toList(), depth)
            is Pair<*, *> -> list(sb, listOf(v.first, v.second), depth)
            is Triple<*, *, *> -> list(sb, listOf(v.first, v.second, v.third), depth)
            else -> PacketJson.write(sb, v, depth)
        }
    }

    private fun list(sb: StringBuilder, items: List<*>, depth: Int) {
        sb.append('[')
        items.forEachIndexed { i, x -> if (i > 0) sb.append(','); element(sb, x, depth + 1) }
        sb.append(']')
    }

    private fun element(sb: StringBuilder, v: Any?, depth: Int) {
        val mark = sb.length
        try { value(sb, v, depth) } catch (t: Throwable) { sb.setLength(mark); PacketJson.error(sb, t) }
    }

    /** A map key as a string: entity ids, `x,y,z` for block positions, enum names. */
    internal fun key(k: Any?): String = when (k) {
        null -> "null"
        is Entity -> k.id.toString()
        is Vec3i -> "${k.x},${k.y},${k.z}"
        is Enum<*> -> k.name
        else -> k.toString()
    }

    internal fun sha1(b: ByteArray): String = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(b))
}
