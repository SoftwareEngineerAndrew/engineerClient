package com.engineerclient.recorder

import com.engineerclient.EngineerClient
import com.google.gson.JsonElement
import com.mojang.serialization.DynamicOps
import com.mojang.serialization.JsonOps
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** What the recorder core needs from the module's settings, pushed in by [DungeonRecorder] when they change. */
data class RecConfig(
    val hidePrivate: Boolean = true,
    val typedChat: Boolean = false,
    val compactEntities: Boolean = false,
    val minFreeGb: Double = 10.0,
    val maxFolderGb: Double = 0.0,
    val deleteOldest: Boolean = false,
)

/**
 * The recorder's one entry point for every capture unit: the line contract and the current session.
 *
 * Every line is `{"k":kind,"seq":S,"t":T,"n":N,"ms":MS,"ns":NS,...}`. The sequence number is taken
 * on the producing thread, so seq is the true order things happened in even though lines from the
 * network, game and render threads reach the file in whatever order the writer gets them. t is the
 * client tick, n the number of server ticks seen (ping packets), ns the monotonic time since the
 * session started.
 *
 * Every call is thread-safe and does nothing (cheaply) when no session is open. Callers must freeze
 * anything mutable into a string on the thread that owns it before calling [emit]; only immutable
 * inputs may go through [emitLazy], whose builder runs later on the writer thread.
 */
object Rec {

    /** Client END_LEVEL_TICK count (absolute; moved here from DungeonRecorder). */
    @Volatile var tick = 0
    /** Server ticks seen on the game connection (ping packets; absolute). */
    @Volatile var serverTicks = 0

    @Volatile var config = RecConfig()
    /** Name -> value of the module's settings, for the `settings` line when they change. */
    @Volatile var settingsSource: (() -> Map<String, String>)? = null

    /** The game (client) thread, learned on its first tick; the queue lets only it wait a moment. */
    @Volatile var gameThread: Thread? = null
        private set

    private val current = AtomicReference<RecorderSession?>()
    private val seq = AtomicLong()

    /** The open session, pending or confirmed. */
    val session: RecorderSession? get() = current.get()

    /** A session exists and is still accepting lines. */
    val active: Boolean get() = current.get()?.running == true

    /** Id of the last keyframe started; contributors tag their keyframe lines with it as "kf". */
    @Volatile var keyframeId = 0L

    fun nextSeq(): Long = seq.incrementAndGet()
    internal fun nextSeqPeek(): Long = seq.get()

    /** Monotonic nanoseconds since the current session started (0 without one). */
    fun nowNs(): Long = current.get()?.let { System.nanoTime() - it.startNs } ?: 0L

    /** The start of a line, without the closing brace; t, n, ms and ns are taken now. */
    fun envelope(kind: String, seq: Long = nextSeq()): String =
        RecorderFiles.envelope(kind, seq, tick, serverTicks, System.currentTimeMillis(), nowNs())

    /** A finished line: [body] is JSON members without braces (may be ""). The envelope is taken now. */
    fun emit(kind: String, body: String) {
        val s = current.get() ?: return
        if (!s.running) return
        val sq = nextSeq()
        val ms = System.currentTimeMillis()
        s.line(sq, kind, ms, close(RecorderFiles.envelope(kind, sq, tick, serverTicks, ms, System.nanoTime() - s.startNs), body))
    }

    /**
     * A line whose body is built later on the writer thread (big, slow-to-format, immutable inputs
     * only: decoded packets without ItemStacks, copied byte arrays, copied sections). The envelope
     * is still taken now. [est] is the expected size in bytes, for the queue's memory cap.
     */
    fun emitLazy(kind: String, est: Int, typeTag: String, build: () -> String) {
        val s = current.get() ?: return
        if (!s.running) return
        val sq = nextSeq()
        val ms = System.currentTimeMillis()
        val env = RecorderFiles.envelope(kind, sq, tick, serverTicks, ms, System.nanoTime() - s.startNs)
        s.lazyLine(sq, est, typeTag, ms) { close(env, build()) }
    }

    /** A whole line built on the writer thread, envelope included (the packet path, which takes its seq itself). */
    fun emitLine(seq: Long, est: Int, typeTag: String, ms: Long, build: () -> String) {
        val s = current.get() ?: return
        if (!s.running) return
        s.lazyLine(seq, est, typeTag, ms, build)
    }

    /**
     * One raw packet frame for the sidecar: [dir] 0 in / 1 out, [phase] the protocol phase id. A
     * [withheld] reason (crypto, private, typed_chat) keeps only the length.
     */
    fun raw(seq: Long, dir: Int, phase: Int, bytes: ByteArray?, withheld: String?) {
        val s = current.get() ?: return
        if (!s.running) return
        s.raw(seq, dir, phase, bytes, withheld != null, bytes?.size ?: 0)
    }

    /** A line for a side index (`entities` -> entities.jsonl, `events` -> events.jsonl), with the envelope. */
    fun index(kind: String, json: String) {
        val s = current.get() ?: return
        if (!s.running) return
        val file = kind.lowercase().replace(Regex("[^a-z0-9_]"), "").ifEmpty { "events" }
        val sq = nextSeq()
        s.index(sq, file, close(envelope(kind, sq), RecorderFiles.members(json)))
    }

    // ------------------------------------------------------------------ keyframes

    private val contributors = CopyOnWriteArrayList<Pair<String, (String) -> Unit>>()
    private val pendingKeyframe = AtomicReference<String?>()
    private var lastKeyframeMs = 0L
    private val UNTHROTTLED = setOf("confirm", "part", "enable")

    /** A keyframe contributor: [fn] runs on the game thread with the reason, and tags its lines with [keyframeId]. */
    fun onKeyframe(name: String, fn: (reason: String) -> Unit) {
        contributors.removeIf { it.first == name }
        contributors += name to fn
    }

    /**
     * Asks for a full snapshot at the next tick end (any thread). At most one every 10 s, except
     * for a confirm, a new part or the module being turned on; a throttled request waits rather
     * than being lost.
     */
    fun requestKeyframe(reason: String) {
        if (current.get() == null) return
        pendingKeyframe.getAndUpdate { cur -> if (cur == null || (cur !in UNTHROTTLED && reason in UNTHROTTLED)) reason else cur }
    }

    // ------------------------------------------------------------------ shared helpers

    /** Registry-aware JSON ops when connected (so registry entries encode by name), plain JSON otherwise. */
    fun ops(): DynamicOps<JsonElement> =
        runCatching { EngineerClient.mc.connection?.registryAccess()?.createSerializationContext(JsonOps.INSTANCE) }.getOrNull() ?: JsonOps.INSTANCE

    private val PRIVATE_CHAT = Regex("""^(?:(?:From|To) (?:\[[^\]]+] )?\w{1,16}: |(?:Guild|Officer|Co-op|Friend) > )""")
    private val COLOR = Regex("§.")

    /** A private message, guild/officer/co-op line or friend notice, when Hide Private Chats is on. */
    fun privateText(plain: String): Boolean = config.hidePrivate && PRIVATE_CHAT.containsMatchIn(COLOR.replace(plain, ""))

    /** Whether what you type may be written out (Typed Chat). */
    val typedChat: Boolean get() = config.typedChat

    private val changedMap = ConcurrentHashMap<String, String>()

    /** True when [value] differs from the last one seen under [key] (game thread). Starts over with each part. */
    fun changed(key: String, value: String): Boolean = changedMap.put(key, value) != value

    internal fun clearChanged() = changedMap.clear()

    /** A bookmark: a `mark` line, a keyframe, and an entry in the manifest's marks. */
    fun mark(note: String?) {
        val s = current.get() ?: return
        if (!s.running) return
        val sq = nextSeq()
        val ms = System.currentTimeMillis()
        s.line(sq, "mark", ms, RecorderFiles.envelope("mark", sq, tick, serverTicks, ms, System.nanoTime() - s.startNs) + ",\"note\":${RecorderFiles.q(note)}}")
        s.addMark(sq, ms, note)
        requestKeyframe("mark")
    }

    // ------------------------------------------------------------------ lifecycle

    /** Makes [s] the current session; one still open is closed. */
    fun begin(s: RecorderSession) {
        current.getAndSet(s)?.takeIf { it !== s }?.close()
        pendingKeyframe.set(null)
        lastKeyframeMs = 0L
        lastSettings = null
        changedMap.clear()
    }

    /** Detaches the current session and returns it (the caller closes or abandons it). */
    fun end(): RecorderSession? = current.getAndSet(null)

    /** Detaches [expected] only if it is still the current one. */
    fun end(expected: RecorderSession): Boolean = current.compareAndSet(expected, null)

    private var lastSettings: Map<String, String>? = null

    /** Called by DungeonRecorder at TickEvent.End, after `tick++`: settings changes, then a pending keyframe. */
    fun onTickEnd() {
        gameThread = Thread.currentThread()
        val s = current.get() ?: return
        if (!s.running) return
        settingsSource?.let { src ->
            val now = runCatching { src() }.getOrNull()
            if (now != null) {
                val before = lastSettings
                lastSettings = now
                if (before != null && before != now) {
                    val changed = now.filter { (k, v) -> before[k] != v }.entries.joinToString(",") { "${RecorderFiles.q(it.key)}:${RecorderFiles.q(it.value)}" }
                    if (changed.isNotEmpty()) emit("settings", "\"changed\":{$changed}")
                }
            }
        }
        val reason = pendingKeyframe.get() ?: return
        val nowMs = System.currentTimeMillis()
        if (reason !in UNTHROTTLED && nowMs - lastKeyframeMs < 10_000) return
        if (!pendingKeyframe.compareAndSet(reason, null)) return
        lastKeyframeMs = nowMs
        val id = ++keyframeId
        val sq = nextSeq()
        s.line(sq, "keyframe", nowMs, RecorderFiles.envelope("keyframe", sq, tick, serverTicks, nowMs, System.nanoTime() - s.startNs) +
            ",\"kf\":$id,\"reason\":${RecorderFiles.q(reason)}}", kf = id)
        for ((name, fn) in contributors) {
            try { fn(reason) } catch (t: Throwable) {
                EngineerClient.logger.error("[ec] recorder keyframe '$name' failed", t)
                emit("error", "\"p\":${RecorderFiles.q("keyframe:$name")},\"err\":${RecorderFiles.q(t.toString())}")
            }
        }
    }

    private fun close(env: String, body: String): String = if (body.isBlank()) "$env}" else "$env,$body}"
}
