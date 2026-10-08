package com.devgineerclient.recorder

import com.devgineerclient.DevgineerClient
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import net.minecraft.network.protocol.Packet
import java.util.IdentityHashMap

/**
 * What became of every recorded server packet, and when it took effect.
 *
 * An `in` line is stamped when the packet is decoded on the network thread, but most packets only
 * change the game later, when the game thread drains its packet queue - and some never do: a mod
 * cancels them at channelRead0 (Odin's bus, chat filters), the listener rejects them (disconnecting),
 * or their handler throws. This keeps the packet -> seq map that lets those moments refer back to
 * the `in` line and writes:
 *
 *  - `applied` `{"on":"game","seqs":[..],"dur"}`: the packets one queue drain applied (seq ranges as
 *    [first,last]), with the envelope's t/ns taken right after the drain; `dur` is the nanoseconds
 *    spent in their handlers. `{"on":"netty","seqs":[S]}`: a packet handled on the network thread.
 *  - `fate` `{"seqs","p","fate"}` with fate `cancelled_odin` (cancelled on Odin's bus),
 *    `cancelled_read0` (another mod cancelled channelRead0), `rejected` (the listener refused it,
 *    `at` read0 or game), `error` (its handler threw, `err` the stack trace) or `expired` (nothing
 *    was seen of it for 30 s - never silently dropped).
 *
 * A bundle is one task on the game thread: its sub-packets are applied together, under one entry.
 * Threads: the read state is per network thread (a ThreadLocal), the applied batch per draining
 * thread; the map itself is an IdentityHashMap under a lock, touched once or twice per packet.
 */
object PacketFate {

    /** One remembered packet: its seqs (several for a bundle), when it was remembered, a bundle's sub-packets. */
    private class Entry(val seqs: LongArray, val bornNs: Long, val subs: List<Packet<*>>? = null)

    private val lock = Any()
    private val map = IdentityHashMap<Packet<*>, Entry>()
    /** The session the map belongs to: a new one starts it over (the old seqs mean nothing in it). */
    private var mapSession: RecorderSession? = null

    private const val MAX_AGE_NS = 30_000_000_000L
    /** Past this many outstanding packets the age limit drops to 5 s, so the map stays bounded. */
    private const val SOFT_CAP = 200_000

    // ------------------------------------------------------------------ remembering

    /** A packet line was just written with [seq] (DungeonRecorder.packetLine, network thread or bundle recursion). */
    fun remember(p: Packet<*>, seq: Long) {
        if (!Rec.active) return
        try {
            val e = Entry(longArrayOf(seq), System.nanoTime())
            synchronized(lock) { syncSession(); map[p] = e }
        } catch (t: Throwable) { fail("remember", t) }
    }

    /** After a bundle's sub-packets were remembered: the bundle carries all their seqs, as it is applied as one. */
    fun rememberBundle(bundle: Packet<*>, subs: Iterable<Packet<*>>) {
        if (!Rec.active) return
        try {
            synchronized(lock) {
                syncSession()
                val list = ArrayList<Packet<*>>()
                val seqs = ArrayList<Long>()
                for (s in subs) { val e = map[s] ?: continue; list += s; seqs += e.seqs.asList() }
                if (seqs.isNotEmpty()) map[bundle] = Entry(seqs.toLongArray(), System.nanoTime(), list)
            }
        } catch (t: Throwable) { fail("bundle", t) }
    }

    private fun syncSession() {
        val s = Rec.session
        if (s !== mapSession) { map.clear(); mapSession = s }
    }

    private fun lookup(p: Packet<*>): Entry? = synchronized(lock) { map[p] }

    /** Forgets [p] (and a bundle's sub-packets) once its fate is written. */
    private fun forget(p: Packet<*>) = synchronized(lock) { map.remove(p)?.subs?.forEach { map.remove(it) } }

    // ------------------------------------------------------------------ channelRead0 (network thread)

    /** How far one channelRead0 got (ConnectionTapMixin's tap, then ConnectionReadEndMixin's points). */
    private class Read {
        var packet: Packet<*>? = null
        var stage = STAGE_TAPPED
        var queued = false
        var odin = false
    }

    const val STAGE_TAPPED = 0
    /** Reached the method's own first call (Channel.isOpen): no HEAD callback cancelled it. */
    const val STAGE_BODY = 1
    /**
     * Passed the vanilla checks (open channel, shouldHandleMessage) and reached the genericsFtw call,
     * marked by the first callback there (ConnectionTapMixin, priority 1), ahead of any mod that
     * cancels at that point (Odin 0.3.4 does).
     */
    const val STAGE_PASSED = 2
    /** Reached genericsFtw past every other callback there: handed to the listener (run here or queued for the game thread). */
    const val STAGE_DISPATCHED = 3

    private val read = ThreadLocal.withInitial { Read() }
    /** Whether the in-method points ever fired; until then a missing one must not read as a cancel. */
    @Volatile private var dispatchSeen = false
    @Volatile private var returnSeen = false

    /** channelRead0 HEAD, after DungeonRecorder.tap remembered the packet: settles the previous read if its end was never seen. */
    fun readBegin(p: Packet<*>) {
        try {
            val r = read.get()
            r.packet?.let { prev -> settle(r, prev, atReturn = false) }
            if (!Rec.active || lookup(p) == null) return
            r.packet = p; r.stage = STAGE_TAPPED; r.queued = false; r.odin = false
        } catch (t: Throwable) { fail("read", t) }
    }

    /** channelRead0 reached [stage] for [p] (ConnectionReadEndMixin). */
    fun readStage(p: Packet<*>, stage: Int) {
        if (stage == STAGE_DISPATCHED) dispatchSeen = true
        if (!Rec.active) return
        val r = read.get()
        if (r.packet === p && stage > r.stage) r.stage = stage
    }

    /** A ListenerAndPacket was made for [p]: it waits in the game thread's queue (PacketApplyTapMixin). */
    fun queued(p: Packet<*>) {
        if (!Rec.active) return
        val r = read.get()
        if (r.packet === p) r.queued = true
    }

    /** channelRead0 returned normally (ConnectionReadEndMixin, RETURN). */
    fun readEnd(p: Packet<*>) {
        returnSeen = true
        if (!Rec.active) return
        val r = read.get()
        if (r.packet === p) settle(r, p, atReturn = true)
    }

    private fun settle(r: Read, p: Packet<*>, atReturn: Boolean) {
        r.packet = null
        try {
            if (!Rec.active) return
            when (val out = readOutcome(r.stage, r.queued, r.odin, atReturn, dispatchSeen, returnSeen)) {
                null -> {}
                "wait" -> {}
                "netty" -> { val e = lookup(p) ?: return; forget(p); Rec.emit("applied", "\"on\":\"netty\",\"seqs\":${seqRanges(e.seqs)}") }
                else -> {
                    val e = lookup(p) ?: return
                    forget(p)
                    val extra = when (out) {
                        "rejected" -> ",\"at\":\"read0\""
                        "error" -> ",\"at\":\"read0\",\"err\":\"threw out of channelRead0\""
                        else -> ""
                    }
                    fateLine(p, e, out, extra)
                }
            }
        } catch (t: Throwable) { fail("settle", t) }
    }

    /**
     * What one channelRead0 means for its packet: null (nothing to write: already written as an
     * Odin cancel, or the hooks are not known to work), "wait" (queued for the game thread),
     * "netty" (applied on the network thread), or a fate. [atReturn]: settled at the method's
     * RETURN rather than by the next packet's tap.
     */
    fun readOutcome(stage: Int, queued: Boolean, odin: Boolean, atReturn: Boolean, dispatchSeen: Boolean, returnSeen: Boolean): String? = when {
        queued -> "wait"
        stage >= STAGE_DISPATCHED -> if (atReturn || !returnSeen) "netty" else "error"
        odin -> null
        // Passed the checks, then a mod's callback at genericsFtw cancelled it.
        stage == STAGE_PASSED -> if (dispatchSeen) "cancelled_read0" else null
        stage == STAGE_BODY -> "rejected"
        // Never got into the method body: a HEAD callback cancelled it. Only trusted once the
        // in-method points have been seen to work, so a target that failed to apply is not read as a cancel.
        else -> if (dispatchSeen) "cancelled_read0" else null
    }

    // ------------------------------------------------------------------ Odin's bus

    /** Registers the Odin listener and the tick flush; once, from DungeonRecorder's init. */
    fun install() {
        // Last of all listeners, cancelled or not: sees whether anyone on Odin's bus cancelled it.
        // On the network thread for top-level packets, on the game thread for a bundle's sub-packets.
        onReceive<Packet<*>>(priority = Int.MIN_VALUE) { ev ->
            if (!Rec.active || !ev.isCancelled) return@onReceive
            DevgineerClient.safely("recorder fate odin") { odinCancelled(this) }
        }
        on<TickEvent.End> {
            if (!Rec.active) return@on
            DevgineerClient.safely("recorder fate tick") { flushApplied(); prune() }
        }
        EventBus.subscribe(this)
    }

    private fun odinCancelled(p: Packet<*>) {
        val e = lookup(p) ?: return
        val r = read.get()
        if (r.packet === p) r.odin = true
        forget(p)
        fateLine(p, e, "cancelled_odin", "")
    }

    // ------------------------------------------------------------------ the game thread's queue

    /** The packet being handled on this thread, and the batch since the last drain ended. */
    private class Apply {
        var packet: Packet<*>? = null
        var entry: Entry? = null
        var startNs = 0L
        var failed = false
        val seqs = ArrayList<Long>()
        var durNs = 0L
    }

    private val apply = ThreadLocal.withInitial { Apply() }

    /** ListenerAndPacket.handle HEAD. */
    fun handleStart(p: Packet<*>) {
        if (!Rec.active) return
        try {
            val a = apply.get()
            val e = lookup(p)
            a.packet = if (e != null) p else null
            a.entry = e
            a.failed = false
            a.startNs = System.nanoTime()
        } catch (t: Throwable) { fail("handle", t) }
    }

    /** The listener refused it (shouldHandleMessage false: disconnecting). */
    fun handleRejected(p: Packet<*>) = handleFailed(p, "rejected", ",\"at\":\"game\"")

    /** Its handler threw [ex] (PacketListener.onPacketError is about to report it). */
    fun handleError(p: Packet<*>, ex: Throwable) =
        handleFailed(p, "error", ",\"at\":\"game\",\"err\":${RecorderFiles.q(runCatching { ex.stackTraceToString() }.getOrElse { ex.toString() })}")

    private fun handleFailed(p: Packet<*>, fate: String, extra: String) {
        if (!Rec.active) return
        try {
            val a = apply.get()
            val e = (if (a.packet === p) a.entry else null) ?: lookup(p) ?: return
            if (a.packet === p) a.failed = true
            forget(p)
            fateLine(p, e, fate, extra)
        } catch (t: Throwable) { fail("handle fate", t) }
    }

    /** ListenerAndPacket.handle RETURN: applied, unless it was rejected or threw. */
    fun handleEnd(p: Packet<*>) {
        if (!Rec.active) return
        try {
            val a = apply.get()
            if (a.packet !== p) return
            val e = a.entry
            a.packet = null; a.entry = null
            if (e == null || a.failed) return
            a.durNs += System.nanoTime() - a.startNs
            for (s in e.seqs) a.seqs += s
            forget(p)
        } catch (t: Throwable) { fail("handle end", t) }
    }

    /** One `applied` line for what this thread's drain applied (PacketProcessor.processQueuedPackets RETURN, and each tick end). */
    fun flushApplied() {
        if (!Rec.active) return
        try {
            val a = apply.get()
            if (a.seqs.isEmpty()) return
            val body = "\"on\":\"game\",\"seqs\":${seqRanges(a.seqs.toLongArray())},\"dur\":${a.durNs}"
            a.seqs.clear(); a.durNs = 0L
            Rec.emit("applied", body)
        } catch (t: Throwable) { fail("flush", t) }
    }

    // ------------------------------------------------------------------ housekeeping

    private var lastPruneNs = 0L

    /** Once a second (game thread): packets nothing was seen of for 30 s get an `expired` fate, so none vanish unrecorded. */
    private fun prune() {
        val now = System.nanoTime()
        if (now - lastPruneNs < 1_000_000_000L) return
        lastPruneNs = now
        val gone = ArrayList<Pair<Packet<*>, Entry>>()
        synchronized(lock) {
            syncSession()
            if (map.isEmpty()) return
            val maxAge = if (map.size > SOFT_CAP) 5_000_000_000L else MAX_AGE_NS
            val it = map.entries.iterator()
            while (it.hasNext()) {
                val (p, e) = it.next()
                if (now - e.bornNs > maxAge) { gone += p to e; it.remove() }
            }
            // A bundle that expired takes its sub-packets with it (they are written under the bundle).
            for ((_, e) in gone.toList()) e.subs?.forEach { s -> map.remove(s)?.let { gone += s to it } }
        }
        if (gone.isEmpty()) return
        // Sub-packets also listed under an expired bundle are written once, under the bundle.
        val underBundle = gone.flatMap { it.second.subs.orEmpty() }.toHashSet()
        val seqs = gone.filter { it.first !in underBundle }.flatMap { it.second.seqs.asList() }.sorted()
        Rec.emit("fate", "\"seqs\":${seqRanges(seqs.toLongArray())},\"fate\":\"expired\"")
    }

    private fun fateLine(p: Packet<*>, e: Entry, fate: String, extra: String) =
        Rec.emit("fate", "\"seqs\":${seqRanges(e.seqs)},\"p\":${RecorderFiles.q(PacketJson.type(p))},\"fate\":\"$fate\"$extra")

    private fun fail(what: String, t: Throwable) = DevgineerClient.logger.error("[dc] recorder fate $what failed", t)

    /** Seqs as a JSON array, runs of three or more consecutive ones as [first,last]: `[4,[7,12],15]`. */
    fun seqRanges(seqs: LongArray): String {
        val sb = StringBuilder("[")
        var i = 0
        while (i < seqs.size) {
            var j = i
            while (j + 1 < seqs.size && seqs[j + 1] == seqs[j] + 1) j++
            if (sb.length > 1) sb.append(',')
            when {
                j - i >= 2 -> sb.append('[').append(seqs[i]).append(',').append(seqs[j]).append(']')
                j > i -> sb.append(seqs[i]).append(',').append(seqs[j])
                else -> sb.append(seqs[i])
            }
            i = j + 1
        }
        return sb.append(']').toString()
    }
}
