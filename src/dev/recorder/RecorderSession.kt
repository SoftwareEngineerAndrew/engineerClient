package com.devgineerclient.recorder

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.slf4j.LoggerFactory
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import java.util.zip.GZIPOutputStream

/**
 * One recording on disk. Lines are queued from any thread; one writer thread (`dc-recorder-writer`)
 * builds them (packets turn into JSON there, not on the network or game thread) and gzips them,
 * and one IO thread (`dc-recorder-io`) puts the bytes on disk, so a slow disk never stalls the
 * serializer and a heavy packet never stalls the disk.
 *
 * Nothing is thrown away to keep the files small. The queue is bounded only by an estimate of the
 * bytes it holds (512 MB, or an eighth of the game's heap if that is less); anything that does not
 * fit is counted per type and written as a `gap` line, so a reader always knows what is missing. No
 * producer ever waits for the writer: a full queue is a gap, never a stall. Recording stops whole, with a `stopped` line,
 * only when the disk is (nearly) full or failing.
 *
 * Layout (see [RecorderFiles]): a directory per recording, `.pending-<id>` until [confirm] gives it
 * its final name. Each part is a run of independent gzip members of about a second or a MiB each,
 * with an index line per member (offsets, sequence/tick/time ranges, line types), so a reader can
 * seek straight to any moment. A crash loses whatever had not reached disk: normally the member being
 * built (about a second), more if the writer or disk had fallen behind (the `rec` lines' qBytes and
 * ioQueueBytes show the backlog). Raw packet bytes go to a sidecar whose members line up with the
 * JSON members.
 *
 * The IO thread holds a lock on `<dir>/.lock` while it owns the directory, so another game instance's
 * startup recovery and folder cap leave a live recording alone.
 *
 * Part files carry a `.part` suffix until they are closed and forced to disk; startup recovery
 * ([RecorderFiles.recover]) cuts any left behind back to their last indexed member.
 */
class RecorderSession(
    val root: Path,
    /** The session's meta members (no braces), built on the game thread at start; may be filled in later. */
    @Volatile var metaBody: String = "",
    private val config: () -> RecConfig = { Rec.config },
    private val notify: (String) -> Unit = ::chat,
) {
    // ------------------------------------------------------------------ identity

    val hex = RecorderFiles.newHexId()
    val stamp: String = LocalDateTime.now().format(RecorderFiles.STAMP)
    val id = "${stamp}_$hex"
    val startNs = System.nanoTime()
    val startMs = System.currentTimeMillis()
    val startT = Rec.tick
    val startN = Rec.serverTicks

    /** Where the files are right now: the pending directory until the IO thread has renamed it. */
    @Volatile var dir: Path = root.resolve(RecorderFiles.pendingName(id))
        private set
    @Volatile var label: String? = null
        private set
    /** The name [confirm] asked for (kept in the manifest even when the rename failed, for recovery). */
    @Volatile private var finalName: String? = null

    /** Accepting lines: false once closing, abandoned or stopped. */
    @Volatile var running = true
        private set
    @Volatile var stoppedReason: String? = null
        private set
    @Volatile private var abandoned = false

    /** Abandoned: everything it wrote is being deleted (side writers such as thumbnails stop). */
    val isAbandoned: Boolean get() = abandoned

    // ------------------------------------------------------------------ queue

    internal abstract class Job(val seq: Long, val est: Int, val typeTag: String, val ms: Long, val t: Int, val n: Int)
    private class LineJob(seq: Long, est: Int, typeTag: String, ms: Long, t: Int, n: Int, val kf: Long, val text: String?, val build: (() -> String)?) :
        Job(seq, est, typeTag, ms, t, n)
    private class RawJob(seq: Long, ms: Long, t: Int, n: Int, val dirb: Int, val phase: Int, val bytes: ByteArray?, val withheld: Boolean, val len: Int) :
        Job(seq, (bytes?.size ?: 0), "raw", ms, t, n)
    private class IndexJob(seq: Long, ms: Long, t: Int, n: Int, val file: String, val text: String) : Job(seq, text.length, "index", ms, t, n)
    private object Wake : Job(-1, 0, "", 0, 0, 0)

    private val queue = LinkedBlockingQueue<Job>()
    private val pendingBytes = AtomicLong()
    /** Producers inside [offer] right now: the writer waits for them before its last pass. */
    private val inFlight = AtomicInteger()
    /** The final manifest is on its way: a line offered now belongs to no recording and is not counted. */
    @Volatile private var finalized = false
    private val queueCap = queueCapFor(Runtime.getRuntime().maxMemory())
    private val ioCap = ioCapFor(Runtime.getRuntime().maxMemory())

    /** Lines that could not be queued, per reason, until the writer writes their gap line. */
    private class Gap(val why: String) {
        var lines = 0L; var seqA = Long.MAX_VALUE; var seqB = Long.MIN_VALUE; var msA = Long.MAX_VALUE; var msB = Long.MIN_VALUE
        val types = HashMap<String, Long>()
    }
    private val gapLock = Any()
    private val gaps = HashMap<String, Gap>()
    private val gapTotal = AtomicLong()
    /** Every gap so far (writer thread), merged per overload episode and capped: see [addHistory]. */
    private val gapHistory = GapHistory()
    private var lastGapFlushMs = 0L
    @Volatile private var gapWarned = false

    private fun recordGap(job: Job, why: String) = recordGap(why, job.seq, job.seq, job.ms, job.ms, 1, mapOf(job.typeTag to 1L))

    private fun recordGap(why: String, seqA: Long, seqB: Long, msA: Long, msB: Long, lines: Long, types: Map<String, Long>) {
        if (finalized) return
        synchronized(gapLock) {
            val g = gaps.getOrPut(why) { Gap(why) }
            g.lines += lines; g.seqA = minOf(g.seqA, seqA); g.seqB = maxOf(g.seqB, seqB); g.msA = minOf(g.msA, msA); g.msB = maxOf(g.msB, msB)
            types.forEach { (k, v) -> g.types.merge(k, v, Long::plus) }
        }
        gapTotal.addAndGet(lines)
    }

    /**
     * Queues [job], or counts it as a gap. Never blocks: not the game thread (a stall per line adds
     * up to whole frames) and not a network thread.
     */
    private fun offer(job: Job): Boolean {
        inFlight.incrementAndGet()
        try {
            if (!running) { recordGap(job, "after_close"); return false }
            val cost = 64L + job.est
            if (pendingBytes.get() + cost > queueCap) { recordGap(job, "queue_full"); return false }
            pendingBytes.addAndGet(cost)
            queue.offer(job)
            return true
        } finally {
            inFlight.decrementAndGet()
        }
    }

    /** [t] and [n] are the line's own (a replayed line keeps those it was taken with). */
    fun line(seq: Long, typeTag: String, ms: Long, text: String, kf: Long = -1, t: Int = Rec.tick, n: Int = Rec.serverTicks) =
        offer(LineJob(seq, text.length, typeTag, ms, t, n, kf, text, null))

    fun lazyLine(seq: Long, est: Int, typeTag: String, ms: Long, build: () -> String) =
        offer(LineJob(seq, est, typeTag, ms, Rec.tick, Rec.serverTicks, -1, null, build))

    fun raw(seq: Long, dir: Int, phase: Int, bytes: ByteArray?, withheld: Boolean, originalLen: Int = bytes?.size ?: 0) =
        offer(RawJob(seq, System.currentTimeMillis(), Rec.tick, Rec.serverTicks, dir, phase, if (withheld) null else bytes, withheld, originalLen))

    fun index(seq: Long, file: String, text: String) =
        offer(IndexJob(seq, System.currentTimeMillis(), Rec.tick, Rec.serverTicks, file, text))

    private val marks = ConcurrentLinkedQueue<String>()
    fun addMark(seq: Long, ms: Long, note: String?) { marks += "{\"seq\":$seq,\"ms\":$ms,\"t\":${Rec.tick},\"note\":${RecorderFiles.q(note)}}" }

    /** The JSON envelope of a line this session writes itself (its own start for ns). */
    fun envelope(kind: String, seq: Long, ms: Long = System.currentTimeMillis()) =
        RecorderFiles.envelope(kind, seq, Rec.tick, Rec.serverTicks, ms, System.nanoTime() - startNs)

    // ------------------------------------------------------------------ lifecycle

    /** The world turned out to be one to keep: the directory gets its final name (label fixed here). */
    fun confirm(label: String) {
        if (this.label != null || abandoned) return
        val name = RecorderFiles.finalName(stamp, label, hex)
        finalName = name
        this.label = RecorderFiles.sanitizeLabel(label)
        io(IoOp.Confirm(name))
    }

    /**
     * Stops and deletes everything written so far (the world was not one to record). It stays in
     * [OPEN] until its IO thread has deleted the directory, so [shutdownAll] waits for that too.
     */
    fun abandon() {
        abandoned = true
        running = false
        queue.offer(Wake)
    }

    /** Stops after writing everything queued. Returns at once; a non-daemon closer waits for the threads. */
    fun close() {
        if (!running && closing.get()) return
        running = false
        queue.offer(Wake)
        if (closing.compareAndSet(false, true)) Thread({
            try { writer.join(); ioThread.join() } catch (_: InterruptedException) {}
        }, "dc-recorder-close").apply { isDaemon = false; start() }
    }
    private val closing = AtomicBoolean()

    /** Closes and waits up to [ms] for the files to be finished. */
    fun closeAndWait(ms: Long): Boolean {
        close()
        val deadline = System.currentTimeMillis() + ms
        runCatching { writer.join(maxOf(1, deadline - System.currentTimeMillis())) }
        runCatching { ioThread.join(maxOf(1, deadline - System.currentTimeMillis())) }
        return !writer.isAlive && !ioThread.isAlive
    }

    // ------------------------------------------------------------------ writer thread state

    private var part = 0

    /** The part being written (the writer's view; may lag a line), for side files such as thumbnails. 0 before the first. */
    val currentPart: Int get() = part
    private var partOpenMs = 0L
    private var partRaw = 0L
    private var prevPart: String? = null
    private var jsonOff = 0L; private var rawOff = 0L; private var entOff = 0L

    private val memberBuf = ByteArrayOutputStream(1 shl 21)
    private val rawBuf = ByteArrayOutputStream(1 shl 16)
    private val entBuf = ByteArrayOutputStream(1 shl 16)
    private val indexBufs = LinkedHashMap<String, StringBuilder>()
    private var mLines = 0; private var mEntLines = 0; private var mStartMs = 0L
    private var mSeqA = Long.MAX_VALUE; private var mSeqB = Long.MIN_VALUE
    private var mTA = Int.MAX_VALUE; private var mTB = Int.MIN_VALUE
    private var mNA = Int.MAX_VALUE; private var mNB = Int.MIN_VALUE
    private var mMsA = Long.MAX_VALUE; private var mMsB = Long.MIN_VALUE
    private var mKf = -1L
    private val mTypes = HashMap<String, Int>()

    private var xzSink: ByteArrayOutputStream? = null
    private var xz: XZOutputStream? = null

    private val counts = HashMap<String, Long>()
    private val errors = HashMap<String, Long>()
    private val partsDone = ArrayList<String>()
    private var lines = 0L
    private var rawTotal = 0L
    private var gzTotal = 0L
    private var serNs = 0L
    private var gzNs = 0L
    private var lastLagMs = 0L
    private var lastTelemetryMs = 0L
    private var lastTelemetryLines = 0L
    private var lastManifestMs = 0L
    private var partSeqA = -1L; private var partTA = 0; private var partNA = 0; private var partMsA = 0L
    /** The seq range of the lines actually written to the part (lines may arrive slightly out of order). */
    private var partSeqMin = Long.MAX_VALUE; private var partSeqMax = Long.MIN_VALUE
    private var partLines = 0L; private var partGz = 0L; private var partRawGz = 0L

    private val writer = Thread(::writerLoop, "dc-recorder-writer").apply { priority = Thread.NORM_PRIORITY - 1; isDaemon = false }

    // ------------------------------------------------------------------ IO thread state

    private sealed class IoOp(val bytes: Long) {
        class Member(val part: Int, val json: ByteArray, val raw: ByteArray?, val ent: ByteArray?, val idx: String,
                     val index: Map<String, String>, val lastSeq: Long, val jsonOff: Long, val rawOff: Long, val entOff: Long) :
            IoOp(json.size.toLong() + (raw?.size ?: 0) + (ent?.size ?: 0))
        class EntTail(val part: Int, val bytes2: ByteArray, val off: Long) : IoOp(bytes2.size.toLong())
        class ClosePart(val part: Int) : IoOp(0)
        class Confirm(val finalName: String) : IoOp(0)
        class Manifest(val text: String, val final: Boolean) : IoOp(text.length.toLong())
        object Finish : IoOp(0)
    }

    private val ioQueue = LinkedBlockingQueue<IoOp>()
    private val ioBytes = AtomicLong()
    private var lock: RecorderFiles.DirLock? = null
    /** The IO thread's current part was closed (renamed): a late `stopped` line must not reopen it. */
    private var ioPartClosed = false
    @Volatile private var ioFailed: String? = null
    @Volatile private var guardStop: String? = null
    @Volatile private var freeDisk = -1L
    @Volatile private var ioNs = 0L
    @Volatile private var lastGoodSeq = -1L
    @Volatile private var anyCloseFailed = false
    private val channels = HashMap<String, FileChannel>()
    private val indexOffsets = HashMap<String, Long>()
    private var idxOff = 0L
    private var ioPart = 1
    private var ioJsonEnd = 0L
    private var lastForceMs = 0L
    private var lastGuardMs = 0L
    private var lastSchema = -1

    private val ioThread = Thread(::ioLoop, "dc-recorder-io").apply { priority = Thread.NORM_PRIORITY - 1; isDaemon = false }

    init {
        installHooks()
        OPEN += this
        // The IO thread first: the writer treats a dead IO thread as a failed disk.
        ioThread.start()
        writer.start()
    }

    /** False once nothing will consume [ioQueue] any more (the IO thread died) or the disk failed for good. */
    private fun ioAccepting() = ioFailed == null && ioThread.isAlive

    /** Hands [op] to the IO thread. Data ops are refused once [ioAccepting] is false (the caller counts them). */
    private fun io(op: IoOp): Boolean {
        if (!ioThread.isAlive) return false
        if (ioFailed != null && (op is IoOp.Member || op is IoOp.EntTail)) return false
        ioBytes.addAndGet(op.bytes)
        ioQueue.offer(op)
        return true
    }

    // ------------------------------------------------------------------ writer

    private fun writerLoop() {
        try {
            while (true) {
                val job = queue.poll(200, TimeUnit.MILLISECONDS)
                if (abandoned) break
                if (job != null && job !== Wake) {
                    pendingBytes.addAndGet(-(64L + job.est))
                    handle(job)
                }
                val now = System.currentTimeMillis()
                writeGaps(now, !running)
                guardStop?.let { stopFromGuard(it) }
                if (!ioAccepting()) {
                    // A disk error, or an IO thread that died: nothing more reaches the disk.
                    if (ioFailed == null) ioFailed = "io thread stopped"
                    if (stoppedReason == null) stoppedReason = "io_error"
                    failDrain("io_error"); break
                }
                if (mLines + mEntLines > 0 && (memberBuf.size() >= MEMBER_RAW || now - mStartMs >= MEMBER_MS)) closeMember()
                else if (mLines == 0 && (rawBuf.size() > 0 || indexBufs.isNotEmpty()) && now - mStartMs >= MEMBER_MS) closeMember()
                if (part > 0 && now - lastTelemetryMs >= TELEMETRY_MS) telemetry(now)
                if (part > 0 && now - lastManifestMs >= MANIFEST_MS) { lastManifestMs = now; io(IoOp.Manifest(manifest(false), false)) }
                if (!running && queue.isEmpty()) {
                    // A producer that saw running=true just before close() may still be queueing: wait
                    // for it (briefly), then go round again if it did.
                    val deadline = System.nanoTime() + 50_000_000L
                    while (inFlight.get() > 0 && System.nanoTime() < deadline) LockSupport.parkNanos(100_000)
                    if (queue.isEmpty()) break
                }
            }
            if (!abandoned) {
                // Anything that still slipped in is counted, then the last gaps go in the file.
                drainToGap("after_close")
                if (ioAccepting()) {
                    if (part > 0) { closeMember(); closePart() }
                }
                finalized = true
                io(IoOp.Manifest(manifest(true), true))
            }
        } catch (t: Throwable) {
            // Out of memory, or a bug outside one line's build: stop the session properly (a dead
            // writer must never leave it "running", queueing into a queue nothing drains).
            runCatching { memberBuf.reset(); rawBuf.reset(); entBuf.reset(); indexBufs.clear() }
            runCatching { log.error("[dc] recorder writer stopped", t) }
            ioFailed = ioFailed ?: "writer: $t"
            if (stoppedReason == null) stoppedReason = "writer_error"
            running = false
            runCatching { failDrain("writer_error") }
            finalized = true
            runCatching { io(IoOp.Manifest(manifest(true), true)) }
            runCatching { notify("§cDungeon Recorder stopped: internal error (${t.javaClass.simpleName}). What was written so far is kept.") }
        } finally {
            io(IoOp.Finish)
            runCatching { xz?.close() }
        }
    }

    private fun handle(job: Job) {
        lastLagMs = System.currentTimeMillis() - job.ms
        if (job is RawJob) {
            if (part == 0) openPart(job.seq)
            if (mLines + mEntLines == 0 && rawBuf.size() == 0 && indexBufs.isEmpty()) mStartMs = System.currentTimeMillis()
            RecorderFiles.rawRecord(rawBuf, job.seq, job.dirb, job.phase, job.bytes, job.withheld, job.len)
            val size = 13L + (job.bytes?.size ?: 0)
            rawTotal += size; partRaw += size
            return
        }
        if (job is IndexJob) {
            if (part == 0) openPart(job.seq)
            if (mLines + mEntLines == 0 && rawBuf.size() == 0 && indexBufs.isEmpty()) mStartMs = System.currentTimeMillis()
            indexBufs.getOrPut(job.file) { StringBuilder() }.append(job.text).append('\n')
            return
        }
        job as LineJob
        val now = System.currentTimeMillis()
        if (part == 0 || now - partOpenMs >= PART_MS || partRaw >= PART_RAW) {
            if (part > 0) { closeMember(); closePart() }
            openPart(job.seq)
        }
        val t0 = System.nanoTime()
        var failed = false
        val text = if (job.text != null) job.text else try {
            job.build!!()
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) throw t
            failed = true
            errors.merge(job.typeTag, 1L, Long::plus)
            RecorderFiles.envelope("error", job.seq, job.t, job.n, job.ms, System.nanoTime() - startNs) +
                ",\"p\":${RecorderFiles.q(job.typeTag)},\"err\":${RecorderFiles.q(t.toString())}}"
        }
        serNs += System.nanoTime() - t0
        if (job.kf >= 0) mKf = job.kf
        // A failed build is an `error` line, and is counted as one (idx types, manifest counts).
        val type = if (failed) "error" else job.typeTag
        val toEnt = !failed && job.typeTag == "ent" && config().compactEntities
        append(text, type, job.seq, job.t, job.n, job.ms, toEnt)
    }

    /** Puts one finished line into the current member (or the entity xz stream). */
    private fun append(text: String, type: String, seq: Long, t: Int, n: Int, ms: Long, toEnt: Boolean = false) {
        if (mLines + mEntLines == 0 && rawBuf.size() == 0 && indexBufs.isEmpty()) mStartMs = System.currentTimeMillis()
        val bytes = text.toByteArray(Charsets.UTF_8)
        val buf = if (toEnt) entBuf else memberBuf
        buf.write(bytes); buf.write('\n'.code)
        if (toEnt) mEntLines++ else mLines++
        lines++; partLines++
        partRaw += bytes.size + 1
        rawTotal += bytes.size + 1
        counts.merge(type, 1L, Long::plus)
        mTypes.merge(type, 1, Int::plus)
        if (seq < mSeqA) mSeqA = seq; if (seq > mSeqB) mSeqB = seq
        if (seq < partSeqMin) partSeqMin = seq; if (seq > partSeqMax) partSeqMax = seq
        if (t < mTA) mTA = t; if (t > mTB) mTB = t
        if (n < mNA) mNA = n; if (n > mNB) mNB = n
        if (ms < mMsA) mMsA = ms; if (ms > mMsB) mMsB = ms
    }

    /** A line the writer writes itself (meta, gap, rec, stopped): a fresh sequence number, taken now. */
    private fun selfLine(kind: String, body: String) {
        val seq = Rec.nextSeq(); val ms = System.currentTimeMillis()
        append(envelope(kind, seq, ms) + (if (body.isEmpty()) "}" else ",$body}"), kind, seq, Rec.tick, Rec.serverTicks, ms)
    }

    private fun openPart(firstSeq: Long) {
        part++
        partOpenMs = System.currentTimeMillis()
        partRaw = 0; jsonOff = 0; rawOff = 0; entOff = 0
        partSeqA = firstSeq; partTA = Rec.tick; partNA = Rec.serverTicks; partMsA = partOpenMs
        partSeqMin = Long.MAX_VALUE; partSeqMax = Long.MIN_VALUE
        partLines = 0; partGz = 0; partRawGz = 0
        val name = RecorderFiles.partName(part, "jsonl.gz")
        val body = StringBuilder()
        body.append("\"format\":\"").append(RecorderFiles.FORMAT).append("\",\"rec\":").append(RecorderFiles.q(id))
            .append(",\"part\":").append(part).append(",\"firstSeq\":").append(firstSeq)
            .append(",\"prevPart\":").append(RecorderFiles.q(prevPart))
            .append(",\"tz\":").append(RecorderFiles.q(ZoneId.systemDefault().id))
            .append(",\"startMs\":").append(startMs).append(",\"startNs\":").append(startNs)
            .append(",\"startT\":").append(startT).append(",\"startN\":").append(startN)
        val meta = metaBody.trim()
        if (meta.isNotEmpty()) body.append(',').append(RecorderFiles.members(meta))
        selfLine("meta", body.toString())
        prevPart = name
        // Each part stands on its own: a keyframe right after its header, and every "only when it
        // changed" line starts over.
        if (Rec.session === this) { Rec.requestKeyframe("part"); Rec.clearChanged() }
    }

    private fun closeMember() {
        if (mLines + mEntLines == 0 && rawBuf.size() == 0 && indexBufs.isEmpty()) return
        val g0 = System.nanoTime()
        val json = if (mLines > 0) gzip(memberBuf) else ByteArray(0)
        val raw = if (rawBuf.size() > 0) gzip(rawBuf) else null
        var ent: ByteArray? = null
        if (mEntLines > 0) {
            if (xz == null) { xzSink = ByteArrayOutputStream(1 shl 16); xz = XZOutputStream(xzSink, LZMA2Options(1)) }
            entBuf.writeTo(xz!!)
            xz!!.endBlock()
            ent = xzSink!!.toByteArray(); xzSink!!.reset()
        }
        gzNs += System.nanoTime() - g0
        val idx = StringBuilder(256)
        idx.append("{\"off\":").append(jsonOff).append(",\"len\":").append(json.size).append(",\"raw\":").append(memberBuf.size())
            .append(",\"lines\":").append(mLines)
        if (mSeqA != Long.MAX_VALUE) {
            idx.append(",\"seq\":[").append(mSeqA).append(',').append(mSeqB).append("],\"t\":[").append(mTA).append(',').append(mTB)
                .append("],\"n\":[").append(mNA).append(',').append(mNB).append("],\"ms\":[").append(mMsA).append(',').append(mMsB).append(']')
        }
        idx.append(",\"kf\":").append(if (mKf >= 0) mKf.toString() else "null")
        if (raw != null) idx.append(",\"rawOff\":").append(rawOff).append(",\"rawLen\":").append(raw.size).append(",\"rawRaw\":").append(rawBuf.size())
        if (ent != null) idx.append(",\"ent\":[").append(entOff).append(',').append(ent.size).append(',').append(mEntLines).append(']')
        idx.append(",\"types\":{")
        mTypes.entries.forEachIndexed { i, (k, v) -> if (i > 0) idx.append(','); idx.append(RecorderFiles.q(k)).append(':').append(v) }
        idx.append("}}")
        val index = indexBufs.mapValues { it.value.toString() }
        // Back-pressure: the writer (never a game or network thread) waits while the disk catches up.
        while (ioBytes.get() > ioCap && ioAccepting()) Thread.sleep(5)
        if (!io(IoOp.Member(part, json, raw, ent, idx.toString(), index, mSeqB, jsonOff, rawOff, entOff)) && mSeqA != Long.MAX_VALUE) {
            // Nothing will write it: its lines are a gap, not a silent loss.
            recordGap("io_error", mSeqA, mSeqB, mMsA, mMsB, (mLines + mEntLines).toLong(), mTypes.mapValues { it.value.toLong() })
        }
        jsonOff += json.size; rawOff += raw?.size ?: 0; entOff += ent?.size ?: 0
        gzTotal += json.size + (raw?.size ?: 0) + (ent?.size ?: 0)
        partGz += json.size; partRawGz += raw?.size ?: 0
        memberBuf.reset(); rawBuf.reset(); entBuf.reset(); indexBufs.clear()
        mLines = 0; mEntLines = 0; mKf = -1; mTypes.clear()
        mSeqA = Long.MAX_VALUE; mSeqB = Long.MIN_VALUE; mTA = Int.MAX_VALUE; mTB = Int.MIN_VALUE
        mNA = Int.MAX_VALUE; mNB = Int.MIN_VALUE; mMsA = Long.MAX_VALUE; mMsB = Long.MIN_VALUE
    }

    private fun closePart() {
        xz?.let { x ->
            runCatching { x.finish() }
            val tail = xzSink!!.toByteArray()
            if (tail.isNotEmpty()) { io(IoOp.EntTail(part, tail, entOff)); entOff += tail.size }
        }
        xz = null; xzSink = null
        val seqA = if (partSeqMin != Long.MAX_VALUE) partSeqMin else partSeqA
        val seqB = if (partSeqMax != Long.MIN_VALUE) partSeqMax else partSeqA
        partsDone += "{\"name\":${RecorderFiles.q(RecorderFiles.partName(part, "jsonl.gz"))},\"seq\":[$seqA,$seqB]," +
            "\"t\":[$partTA,${Rec.tick}],\"n\":[$partNA,${Rec.serverTicks}],\"ms\":[$partMsA,${System.currentTimeMillis()}]," +
            "\"bytes\":$partGz,\"rawBytes\":$partRawGz,\"lines\":$partLines}"
        io(IoOp.ClosePart(part))
    }

    private fun gzip(b: ByteArrayOutputStream): ByteArray {
        val out = ByteArrayOutputStream(b.size() / 4 + 64)
        Gz6(out).use { b.writeTo(it) }
        return out.toByteArray()
    }

    /** gzip at level 6 (the JDK's constructor has no level; `def` is DeflaterOutputStream's protected deflater). */
    private class Gz6(out: OutputStream) : GZIPOutputStream(out, 1 shl 16, false) { init { def.setLevel(6) } }

    /**
     * Writes the gaps gathered since the last call: at most once a second unless [force]d, so one
     * gap line per reason covers a whole second of an overload rather than one line per job.
     */
    private fun writeGaps(now: Long, force: Boolean = true) {
        if (!force && now - lastGapFlushMs < GAP_FLUSH_MS) return
        lastGapFlushMs = now
        val pending: List<Gap> = synchronized(gapLock) { if (gaps.isEmpty()) return; gaps.values.toList().also { gaps.clear() } }
        if (part == 0 && running) openPart(Rec.nextSeqPeek())
        for (g in pending) {
            gapHistory.add(g.why, g.seqA, g.seqB, g.msA, g.msB, g.lines, g.types)
            if (part > 0) selfLine("gap", gapBody(g))
        }
        if (!gapWarned && pending.any { it.why == "queue_full" }) {
            gapWarned = true
            notify("§cDungeon Recorder: the recorder fell behind and skipped some lines (written as a gap in the file).")
        }
    }

    private fun telemetry(now: Long) {
        val dt = (now - lastTelemetryMs).coerceAtLeast(1)
        val perS = if (lastTelemetryMs == 0L) 0 else (lines - lastTelemetryLines) * 1000 / dt
        lastTelemetryMs = now; lastTelemetryLines = lines
        selfLine("rec", "\"q\":${queue.size},\"qBytes\":${pendingBytes.get()},\"lagMs\":$lastLagMs,\"lines\":$lines,\"rawBytes\":$rawTotal," +
            "\"gzBytes\":$gzTotal,\"serNs\":$serNs,\"gzNs\":$gzNs,\"ioNs\":$ioNs,\"ioQueueBytes\":${ioBytes.get()},\"freeDisk\":$freeDisk,\"linesPerS\":$perS")
    }

    /** The disk guard asked to stop: say why, count whatever is still queued as a gap, and close. */
    private fun stopFromGuard(why: String) {
        guardStop = null
        if (stoppedReason != null) return
        stoppedReason = why.substringBefore(':')
        running = false
        selfLine("stopped", why.substringAfter(':'))
        drainToGap("stopped")
        notify("§cDungeon Recorder stopped: ${if (stoppedReason == "folder_cap") "the recordings folder reached its size limit" else "the disk is almost full"}.")
    }

    private fun drainToGap(why: String) {
        while (true) {
            val j = queue.poll() ?: break
            if (j === Wake) continue
            pendingBytes.addAndGet(-(64L + j.est))
            recordGap(j, why)
        }
        writeGaps(System.currentTimeMillis())
    }

    /**
     * Nothing more can be written (the disk failed for good, or the writer broke): the rest is only
     * counted, into the manifest's gaps. Producers still holding the session see running=false.
     */
    private fun failDrain(why: String) {
        running = false
        // A producer mid-offer may still queue one more: wait for them briefly.
        val deadline = System.nanoTime() + 50_000_000L
        while (inFlight.get() > 0 && System.nanoTime() < deadline) LockSupport.parkNanos(100_000)
        while (true) {
            val j = queue.poll() ?: break
            if (j === Wake) continue
            pendingBytes.addAndGet(-(64L + j.est))
            recordGap(j, why)
        }
        val pending = synchronized(gapLock) { gaps.values.toList().also { gaps.clear() } }
        pending.forEach { g -> gapHistory.add(g.why, g.seqA, g.seqB, g.msA, g.msB, g.lines, g.types) }
    }

    private fun gapBody(g: Gap): String {
        val types = g.types.entries.joinToString(",") { "${RecorderFiles.q(it.key)}:${it.value}" }
        return "\"range\":[${g.seqA},${g.seqB}],\"lines\":${g.lines},\"msRange\":[${g.msA},${g.msB}],\"why\":${RecorderFiles.q(g.why)},\"types\":{$types}"
    }

    private fun manifest(final: Boolean): String {
        val m = JsonObject()
        m.addProperty("format", RecorderFiles.FORMAT)
        m.addProperty("id", id)
        // Session meta (versions, self, server, settings, filters...) at the top level.
        runCatching {
            val meta = metaBody.trim()
            if (meta.isNotEmpty()) JsonParser.parseString("{" + RecorderFiles.members(meta) + "}").asJsonObject.entrySet().forEach { (k, v) -> m.add(k, v) }
        }
        m.addProperty("label", label)
        m.addProperty("start", startMs)
        m.addProperty("startNs", startNs)
        m.addProperty("startT", startT)
        m.addProperty("startN", startN)
        if (final) m.addProperty("end", System.currentTimeMillis())
        m.add("parts", JsonArray().also { a -> partsDone.forEach { a.add(JsonParser.parseString(it)) } })
        if (!final && part > 0) m.add("openPart", JsonParser.parseString("{\"name\":${RecorderFiles.q(RecorderFiles.partName(part, "jsonl.gz"))},\"lines\":$partLines,\"bytes\":$jsonOff}"))
        m.add("counts", JsonObject().also { o -> counts.toSortedMap().forEach { (k, v) -> o.addProperty(k, v) } })
        m.add("errors", JsonObject().also { o -> errors.toSortedMap().forEach { (k, v) -> o.addProperty(k, v) } })
        gapHistory.toJson(m)
        m.add("marks", JsonArray().also { a -> marks.forEach { a.add(JsonParser.parseString(it)) } })
        m.addProperty("lines", lines)
        m.addProperty("gzBytes", gzTotal)
        m.addProperty("rawBytes", rawTotal)
        return GSON.toJson(m)
    }

    // ------------------------------------------------------------------ IO thread

    private fun ioLoop() {
        var finished = false
        try {
            // A folder that cannot be made (read-only, Controlled Folder Access, a file in the way)
            // is a disk error like any other: the session stops and says so.
            try {
                Files.createDirectories(dir)
                lock = RecorderFiles.lockDir(dir, create = true)
            } catch (e: Throwable) { fail(e, writeLine = false) }
            while (true) {
                val op = ioQueue.poll(1, TimeUnit.SECONDS)
                val now = System.currentTimeMillis()
                if (op != null) {
                    ioBytes.addAndGet(-op.bytes)
                    if (op is IoOp.Finish) { finished = true; break }
                    if (abandoned) continue
                    if (ioFailed == null || op is IoOp.Manifest) {
                        val t0 = System.nanoTime()
                        try { perform(op) } catch (e: Throwable) {
                            if (op is IoOp.Manifest) log.warn("[dc] recorder manifest", e)
                            // A part's close renames its files: no stopped line can be added to it afterwards.
                            else fail(e, writeLine = op !is IoOp.ClosePart)
                        }
                        ioNs += System.nanoTime() - t0
                    }
                }
                if (abandoned) continue
                if (now - lastForceMs >= FORCE_MS) { lastForceMs = now; channels.values.forEach { runCatching { it.force(false) } } }
                if (now - lastGuardMs >= GUARD_MS && running) { lastGuardMs = now; runCatching { diskGuard() } }
            }
        } catch (t: Throwable) {
            log.error("[dc] recorder io stopped", t)
            // Never leave the session running with nobody to write it.
            if (ioFailed == null) runCatching { fail(t, writeLine = false) }
        } finally {
            closeChannels()
            if (abandoned) {
                runCatching { lock?.release() }; lock = null
                runCatching { RecorderFiles.deleteRecursively(dir) }
            } else {
                // A confirmed recording whose rename kept failing: one more try now that nothing is open.
                if (finished && finalName != null && dir.fileName.toString().startsWith(".")) runCatching { renameTo(finalName!!, 1) }
                runCatching { lock?.release(delete = true) }; lock = null
            }
            OPEN.remove(this)
            if (!abandoned) runCatching { summary() }
        }
    }

    /** Moves the directory to [name] (the lock is let go for the move and taken again after). */
    private fun renameTo(name: String, attempts: Int): Boolean {
        val target = dir.resolveSibling(name)
        runCatching { lock?.release() }; lock = null
        var moved = false
        for (attempt in 0 until attempts) {
            try {
                try { Files.move(dir, target, StandardCopyOption.ATOMIC_MOVE) } catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(dir, target) }
                moved = true; break
            } catch (e: IOException) {
                if (attempt + 1 < attempts) Thread.sleep(minOf(5000L, 100L shl minOf(attempt, 6)))
            }
        }
        if (moved) dir = target
        lock = runCatching { RecorderFiles.lockDir(dir, create = true) }.getOrNull()
        return moved
    }

    private fun perform(op: IoOp) {
        when (op) {
            is IoOp.Member -> {
                val p = RecorderFiles.partName(op.part, "")
                if (op.json.isNotEmpty()) writeAt("${p}jsonl.gz.part", op.json, op.jsonOff)
                op.raw?.let { writeAt("${p}raw.gz.part", it, op.rawOff) }
                op.ent?.let { writeAt("${p}ent.xz.part", it, op.entOff) }
                for ((file, text) in op.index) {
                    val name = "$file.jsonl"
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    val off = indexOffsets.getOrPut(name) { runCatching { Files.size(dir.resolve(name)) }.getOrDefault(0L) }
                    writeAt(name, bytes, off)
                    indexOffsets[name] = off + bytes.size
                }
                // The index line goes last: a member it names is always whole on disk.
                val idx = (op.idx + "\n").toByteArray(Charsets.UTF_8)
                writeAt("${p}idx.jsonl", idx, idxOff)
                idxOff += idx.size
                ioPart = op.part
                ioPartClosed = false
                ioJsonEnd = op.jsonOff + op.json.size
                if (op.lastSeq > lastGoodSeq) lastGoodSeq = op.lastSeq
            }
            is IoOp.EntTail -> writeAt("${RecorderFiles.partName(op.part, "")}ent.xz.part", op.bytes2, op.off)
            is IoOp.ClosePart -> {
                val p = RecorderFiles.partName(op.part, "")
                idxOff = 0; ioJsonEnd = 0
                ioPartClosed = true
                for (ext in listOf("jsonl.gz", "raw.gz", "ent.xz", "idx.jsonl")) {
                    val name = if (ext == "idx.jsonl") "$p$ext" else "$p$ext.part"
                    // From what is on disk, not from the open channels: a confirm closed them all, and
                    // a stream not written since (often the raw sidecar) must still lose its .part.
                    channels.remove(name)?.let { ch ->
                        try { ch.force(true); ch.close() } catch (e: IOException) { anyCloseFailed = true; runCatching { ch.close() }; throw e }
                    }
                    // Never rename a part whose close failed: recovery will check it against the index.
                    val path = dir.resolve(name)
                    if (name.endsWith(".part") && Files.exists(path)) Files.move(path, dir.resolve(name.removeSuffix(".part")))
                }
            }
            is IoOp.Confirm -> {
                closeChannels()
                // Written first, so a crash mid-rename (or a rename that keeps failing) still keeps it.
                runCatching { RecorderFiles.writeAtomically(dir.resolve("manifest.json"), "{\"format\":\"${RecorderFiles.FORMAT}\",\"id\":${RecorderFiles.q(id)},\"label\":${RecorderFiles.q(label)},\"confirmed\":true,\"finalName\":${RecorderFiles.q(op.finalName)},\"complete\":false}") }
                if (!renameTo(op.finalName, 20)) log.warn("[dc] recorder could not rename $dir to ${op.finalName}; recovery will on next start")
                lastSchema = -1
            }
            is IoOp.Manifest -> {
                if (ioFailed != null || stoppedReason != null || anyCloseFailed) {
                    val m = JsonParser.parseString(op.text).asJsonObject
                    m.addProperty("complete", op.final && ioFailed == null && !anyCloseFailed)
                    m.addProperty("stoppedReason", stoppedReason ?: if (ioFailed != null) "io_error" else null)
                    ioFailed?.let { m.addProperty("ioError", it) }
                    finishManifest(m, op.final)
                } else {
                    val m = JsonParser.parseString(op.text).asJsonObject
                    m.addProperty("complete", op.final)
                    finishManifest(m, op.final)
                }
                if (lastSchema != RecorderFiles.schemaVersion()) {
                    lastSchema = RecorderFiles.schemaVersion()
                    RecorderFiles.writeAtomically(dir.resolve("schema.json"), RecorderFiles.schemaJson())
                }
            }
            IoOp.Finish -> {}
        }
    }

    private fun finishManifest(m: JsonObject, final: Boolean) {
        m.addProperty("crashed", false)
        m.addProperty("closed", final)
        m.addProperty("lastGoodSeq", lastGoodSeq)
        // The name asked for, not the directory's: a rename that failed is finished by recovery.
        finalName?.let { m.addProperty("confirmed", true); m.addProperty("finalName", it) }
        RecorderFiles.writeAtomically(dir.resolve("manifest.json"), GSON.toJson(m))
    }

    /**
     * Writes [bytes] at [pos] (positional, so a retry after a torn write lands in the same place).
     * Files are created with CREATE_NEW and never truncated or replaced. Access-denied and
     * sharing-violation errors (antivirus, a backup tool) are retried for a while; a full disk or
     * an error that persists stops the recording.
     */
    private fun writeAt(name: String, bytes: ByteArray, pos: Long) {
        var delay = 100L
        var waited = 0L
        while (true) {
            try {
                val ch = channels.getOrPut(name) {
                    val path = dir.resolve(name)
                    if (Files.exists(path)) FileChannel.open(path, StandardOpenOption.WRITE)
                    else FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                }
                val buf = ByteBuffer.wrap(bytes)
                var p = pos
                while (buf.hasRemaining()) p += ch.write(buf, p)
                return
            } catch (e: IOException) {
                channels.remove(name)?.let { runCatching { it.close() } }
                val usable = runCatching { Files.getFileStore(dir).usableSpace }.getOrDefault(Long.MAX_VALUE)
                val transient = (e is AccessDeniedException || (e is FileSystemException && (e.reason ?: "").contains("used by another process", true)))
                if (usable < bytes.size + 1024 * 1024 || !transient || waited > 60_000) throw e
                Thread.sleep(delay); waited += delay
                delay = minOf(5000L, delay * 2)
            }
        }
    }

    /**
     * A persistent IO error: try to leave a `stopped` line (when [writeLine] and the disk still takes
     * one), keep the .part names, warn, stop. The manifest always says io_error.
     */
    private fun fail(e: Throwable, writeLine: Boolean = true) {
        if (ioFailed != null) return
        log.error("[dc] recorder io error in $dir", e)
        ioFailed = e.toString()
        stoppedReason = "io_error"
        running = false
        if (writeLine && !ioPartClosed) runCatching { stoppedLine(e) }
        notify("§cDungeon Recorder stopped: could not write to disk (${e.javaClass.simpleName}). What was written so far is kept.")
    }

    /**
     * The `stopped` line as its own member, indexed like any other so recovery keeps it. The failed
     * write already closed its channel, so the part's files are opened again by name; the member goes
     * at the end of the last whole one, over anything torn.
     */
    private fun stoppedLine(e: Throwable) {
        val p = RecorderFiles.partName(ioPart, "")
        val jPath = dir.resolve("${p}jsonl.gz.part")
        if (!Files.isDirectory(dir)) return
        val seq = Rec.nextSeq()
        val ms = System.currentTimeMillis()
        val t = Rec.tick; val n = Rec.serverTicks
        val lb = (RecorderFiles.envelope("stopped", seq, t, n, ms, System.nanoTime() - startNs) +
            ",\"why\":\"io_error\",\"error\":${RecorderFiles.q(e.toString())}}\n").toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        Gz6(out).use { it.write(lb) }
        val gz = out.toByteArray()
        val idx = "{\"off\":$ioJsonEnd,\"len\":${gz.size},\"raw\":${lb.size},\"lines\":1,\"seq\":[$seq,$seq],\"t\":[$t,$t],\"n\":[$n,$n],\"ms\":[$ms,$ms],\"kf\":null,\"types\":{\"stopped\":1}}\n"
        fun put(name: String, path: Path, bytes: ByteArray, at: Long) {
            val held = channels[name]
            val ch = held ?: FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            try {
                val b = ByteBuffer.wrap(bytes)
                var pos = at
                while (b.hasRemaining()) pos += ch.write(b, pos)
                ch.force(true)
            } finally { if (held == null) runCatching { ch.close() } }
        }
        put("${p}jsonl.gz.part", jPath, gz, ioJsonEnd)
        put("${p}idx.jsonl", dir.resolve("${p}idx.jsonl"), idx.toByteArray(Charsets.UTF_8), idxOff)
    }

    /** Free space and folder size, every 10 s. */
    private fun diskGuard() {
        val c = config()
        val free = Files.getFileStore(dir).usableSpace
        freeDisk = free
        val minFree = (c.minFreeGb * RecorderFiles.GIB).toLong()
        if (free < minFree) { guardStop = "disk_free_below:\"why\":\"disk_free_below\",\"freeBytes\":$free,\"limitBytes\":$minFree"; queue.offer(Wake); return }
        if (c.maxFolderGb > 0) {
            val cap = (c.maxFolderGb * RecorderFiles.GIB).toLong()
            var size = RecorderFiles.folderSize(root)
            if (size > cap && c.deleteOldest) {
                RecorderFiles.deleteOldest(root, liveDirs() + dir, size - cap)
                size = RecorderFiles.folderSize(root)
            }
            if (size > cap) { guardStop = "folder_cap:\"why\":\"folder_cap\",\"freeBytes\":$free,\"folderBytes\":$size,\"limitBytes\":$cap"; queue.offer(Wake) }
        }
    }

    private fun closeChannels() {
        channels.values.forEach { runCatching { it.force(true) }; runCatching { it.close() } }
        channels.clear()
        indexOffsets.clear()
    }

    private fun summary() {
        if (label == null) return
        val mb = gzTotal / 1048576.0
        val g = gapTotal.get()
        notify("§7Dungeon Recorder saved §f${dir.fileName}§7: ${"%.1f".format(java.util.Locale.ROOT, mb)} MB, $lines lines" +
            (if (g > 0) ", §c$g lines skipped§7" else "") + (stoppedReason?.let { " §c(stopped: $it)" } ?: "") + ".")
    }

    companion object {
        private val log = LoggerFactory.getLogger("devgineerclient")
        private val GSON = GsonBuilder().setPrettyPrinting().serializeNulls().create()

        const val QUEUE_BYTES = 512L * 1024 * 1024
        const val IO_QUEUE_BYTES = 256L * 1024 * 1024
        /** The writer queue's byte cap for a heap of [maxMemory]: never more than an eighth of it. */
        fun queueCapFor(maxMemory: Long) = minOf(QUEUE_BYTES, maxMemory / 8)
        /** The IO queue's (finished members waiting for the disk): never more than a sixteenth of the heap. */
        fun ioCapFor(maxMemory: Long) = minOf(IO_QUEUE_BYTES, maxMemory / 16)
        const val GAP_FLUSH_MS = 1000L
        const val MEMBER_RAW = 1 shl 20
        const val MEMBER_MS = 1000L
        const val PART_MS = 60 * 60 * 1000L
        const val PART_RAW = 2L * 1024 * 1024 * 1024
        const val TELEMETRY_MS = 10_000L
        const val MANIFEST_MS = 10_000L
        const val GUARD_MS = 10_000L
        const val FORCE_MS = 30_000L

        /** Every session not yet finished (closing ones included), for [shutdownAll]. */
        private val OPEN: MutableSet<RecorderSession> = ConcurrentHashMap.newKeySet()
        private val hooked = AtomicBoolean()

        /** The directories of every session still writing (closing ones included): never deleted by the folder cap. */
        fun liveDirs(): Set<Path> = OPEN.mapTo(HashSet()) { it.dir }

        private fun installHooks() {
            if (!hooked.compareAndSet(false, true)) return
            // Minecraft.destroy() ends in System.exit, which runs this: the last member and the
            // part renames still happen when the game is closed with a recording open.
            runCatching { Runtime.getRuntime().addShutdownHook(Thread({ shutdownAll(2500) }, "dc-recorder-shutdown")) }
        }

        /** Finishes every open or closing session within [ms] in total. Bounded and idempotent. */
        fun shutdownAll(ms: Long) {
            val deadline = System.currentTimeMillis() + ms
            val sessions = OPEN.toList()
            sessions.forEach { runCatching { it.close() } }
            for (s in sessions) runCatching { s.closeAndWait(maxOf(1, deadline - System.currentTimeMillis())) }
        }

        private fun chat(text: String) {
            runCatching { com.devgineerclient.DevgineerClient.msg(text) }
        }
    }
}

/**
 * A session's gaps for the manifest, merged per overload episode: a gap with the same reason that
 * starts within [MERGE_MS] of the last one ends widens it. Past [CAP] entries the oldest are folded
 * into per-reason totals (`gapsOverflow`), so the list stays small and nothing is lost from the count.
 * Writer thread only.
 */
internal class GapHistory {
    class Entry(val why: String, var seqA: Long, var seqB: Long, var msA: Long, var msB: Long, var lines: Long, val types: HashMap<String, Long>)
    class Overflow(var entries: Long = 0, var lines: Long = 0, var seqA: Long = Long.MAX_VALUE, var seqB: Long = Long.MIN_VALUE)

    val entries = ArrayDeque<Entry>()
    val overflow = LinkedHashMap<String, Overflow>()

    fun add(why: String, seqA: Long, seqB: Long, msA: Long, msB: Long, lines: Long, types: Map<String, Long>) {
        val last = entries.lastOrNull()
        if (last != null && last.why == why && msA - last.msB <= MERGE_MS) {
            last.seqA = minOf(last.seqA, seqA); last.seqB = maxOf(last.seqB, seqB)
            last.msA = minOf(last.msA, msA); last.msB = maxOf(last.msB, msB)
            last.lines += lines
            types.forEach { (k, v) -> last.types.merge(k, v, Long::plus) }
            return
        }
        entries.addLast(Entry(why, seqA, seqB, msA, msB, lines, HashMap(types)))
        while (entries.size > CAP) {
            val o = entries.removeFirst()
            val f = overflow.getOrPut(o.why) { Overflow() }
            f.entries++; f.lines += o.lines; f.seqA = minOf(f.seqA, o.seqA); f.seqB = maxOf(f.seqB, o.seqB)
        }
    }

    /** Adds `gaps` (and `gapsOverflow` when anything was folded) to the manifest [m]. */
    fun toJson(m: JsonObject) {
        m.add("gaps", JsonArray().also { a ->
            for (e in entries) a.add(JsonObject().also { o ->
                o.add("range", JsonArray().also { it.add(e.seqA); it.add(e.seqB) })
                o.addProperty("lines", e.lines)
                o.add("msRange", JsonArray().also { it.add(e.msA); it.add(e.msB) })
                o.addProperty("why", e.why)
                o.add("types", JsonObject().also { t -> e.types.toSortedMap().forEach { (k, v) -> t.addProperty(k, v) } })
            })
        })
        if (overflow.isNotEmpty()) m.add("gapsOverflow", JsonObject().also { o ->
            overflow.forEach { (why, f) -> o.add(why, JsonObject().also { x ->
                x.addProperty("entries", f.entries); x.addProperty("lines", f.lines)
                x.add("range", JsonArray().also { it.add(f.seqA); it.add(f.seqB) })
            }) }
        })
    }

    companion object {
        const val MERGE_MS = 2000L
        const val CAP = 1000
    }
}
