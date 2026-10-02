package com.engineerclient.recorder

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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import java.util.zip.GZIPOutputStream

/**
 * One recording on disk. Lines are queued from any thread; one writer thread (`ec-recorder-writer`)
 * builds them (packets turn into JSON there, not on the network or game thread) and gzips them,
 * and one IO thread (`ec-recorder-io`) puts the bytes on disk, so a slow disk never stalls the
 * serializer and a heavy packet never stalls the disk.
 *
 * Nothing is thrown away to keep the files small. The queue is bounded only by an estimate of the
 * bytes it holds (512 MB); anything that does not fit is counted per type and written as a `gap`
 * line, so a reader always knows what is missing. Recording stops whole, with a `stopped` line,
 * only when the disk is (nearly) full or failing.
 *
 * Layout (see [RecorderFiles]): a directory per recording, `.pending-<id>` until [confirm] gives it
 * its final name. Each part is a run of independent gzip members of about a second or a MiB each,
 * with an index line per member (offsets, sequence/tick/time ranges, line types), so a reader can
 * seek straight to any moment and a crash loses at most the member being built. Raw packet bytes go
 * to a sidecar whose members line up with the JSON members.
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

    /** Accepting lines: false once closing, abandoned or stopped. */
    @Volatile var running = true
        private set
    @Volatile var stoppedReason: String? = null
        private set
    @Volatile private var abandoned = false

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

    /** Lines that could not be queued, per reason, until the writer writes their gap line. */
    private class Gap(val why: String) {
        var lines = 0L; var seqA = Long.MAX_VALUE; var seqB = Long.MIN_VALUE; var msA = Long.MAX_VALUE; var msB = Long.MIN_VALUE
        val types = HashMap<String, Long>()
    }
    private val gapLock = Any()
    private val gaps = HashMap<String, Gap>()
    private val gapTotal = AtomicLong()
    private val gapHistory = ConcurrentLinkedQueue<String>()
    @Volatile private var gapWarned = false

    private fun recordGap(job: Job, why: String) {
        synchronized(gapLock) {
            val g = gaps.getOrPut(why) { Gap(why) }
            g.lines++; g.seqA = minOf(g.seqA, job.seq); g.seqB = maxOf(g.seqB, job.seq); g.msA = minOf(g.msA, job.ms); g.msB = maxOf(g.msB, job.ms)
            g.types.merge(job.typeTag, 1L, Long::plus)
        }
        gapTotal.incrementAndGet()
    }

    private fun offer(job: Job): Boolean {
        if (!running) { recordGap(job, "after_close"); return false }
        val cost = 64L + job.est
        if (pendingBytes.get() + cost > QUEUE_BYTES) {
            // The game thread may wait a moment for the writer; network threads never block.
            if (Thread.currentThread() === Rec.gameThread) LockSupport.parkNanos(2_000_000)
            if (pendingBytes.get() + cost > QUEUE_BYTES) { recordGap(job, "queue_full"); return false }
        }
        pendingBytes.addAndGet(cost)
        queue.offer(job)
        return true
    }

    fun line(seq: Long, typeTag: String, ms: Long, text: String, kf: Long = -1) =
        offer(LineJob(seq, text.length, typeTag, ms, Rec.tick, Rec.serverTicks, kf, text, null))

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
        this.label = RecorderFiles.sanitizeLabel(label)
        io(IoOp.Confirm(RecorderFiles.finalName(stamp, label, hex)))
    }

    /** Stops and deletes everything written so far (the world was not one to record). */
    fun abandon() {
        abandoned = true
        running = false
        OPEN.remove(this)
        queue.offer(Wake)
    }

    /** Stops after writing everything queued. Returns at once; a non-daemon closer waits for the threads. */
    fun close() {
        if (!running && closing.get()) return
        running = false
        queue.offer(Wake)
        if (closing.compareAndSet(false, true)) Thread({
            try { writer.join(); ioThread.join() } catch (_: InterruptedException) {}
        }, "ec-recorder-close").apply { isDaemon = false; start() }
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
    private var partLines = 0L; private var partGz = 0L; private var partRawGz = 0L

    private val writer = Thread(::writerLoop, "ec-recorder-writer").apply { priority = Thread.NORM_PRIORITY - 1; isDaemon = false }

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

    private val ioThread = Thread(::ioLoop, "ec-recorder-io").apply { priority = Thread.NORM_PRIORITY - 1; isDaemon = false }

    init {
        installHooks()
        OPEN += this
        writer.start()
        ioThread.start()
    }

    private fun io(op: IoOp) {
        ioBytes.addAndGet(op.bytes)
        ioQueue.offer(op)
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
                writeGaps(now)
                guardStop?.let { stopFromGuard(it) }
                if (ioFailed != null) { failDrain(); break }
                if (mLines + mEntLines > 0 && (memberBuf.size() >= MEMBER_RAW || now - mStartMs >= MEMBER_MS)) closeMember()
                else if (mLines == 0 && (rawBuf.size() > 0 || indexBufs.isNotEmpty()) && now - mStartMs >= MEMBER_MS) closeMember()
                if (part > 0 && now - lastTelemetryMs >= TELEMETRY_MS) telemetry(now)
                if (part > 0 && now - lastManifestMs >= MANIFEST_MS) { lastManifestMs = now; io(IoOp.Manifest(manifest(false), false)) }
                if (!running && queue.isEmpty()) break
            }
            if (!abandoned) {
                if (ioFailed == null) {
                    writeGaps(System.currentTimeMillis())
                    if (part > 0) { closeMember(); closePart() }
                }
                io(IoOp.Manifest(manifest(true), true))
            }
        } catch (t: Throwable) {
            log.error("[ec] recorder writer stopped", t)
            ioFailed = ioFailed ?: "writer: $t"
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
        val text = if (job.text != null) job.text else try {
            job.build!!()
        } catch (t: Throwable) {
            errors.merge(job.typeTag, 1L, Long::plus)
            RecorderFiles.envelope("error", job.seq, job.t, job.n, job.ms, System.nanoTime() - startNs) +
                ",\"p\":${RecorderFiles.q(job.typeTag)},\"err\":${RecorderFiles.q(t.toString())}}"
        }
        serNs += System.nanoTime() - t0
        if (job.kf >= 0) mKf = job.kf
        val toEnt = job.typeTag == "ent" && config().compactEntities
        append(text, job.typeTag, job.seq, job.t, job.n, job.ms, toEnt)
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
        while (ioBytes.get() > IO_QUEUE_BYTES && ioFailed == null && ioThread.isAlive) Thread.sleep(5)
        io(IoOp.Member(part, json, raw, ent, idx.toString(), index, mSeqB, jsonOff, rawOff, entOff))
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
        partsDone += "{\"name\":${RecorderFiles.q(RecorderFiles.partName(part, "jsonl.gz"))},\"seq\":[$partSeqA,${Rec.nextSeqPeek()}]," +
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

    private fun writeGaps(now: Long) {
        val pending: List<Gap> = synchronized(gapLock) { if (gaps.isEmpty()) return; gaps.values.toList().also { gaps.clear() } }
        if (part == 0 && running) openPart(Rec.nextSeqPeek())
        for (g in pending) {
            val types = g.types.entries.joinToString(",") { "${RecorderFiles.q(it.key)}:${it.value}" }
            val body = "\"range\":[${g.seqA},${g.seqB}],\"lines\":${g.lines},\"msRange\":[${g.msA},${g.msB}],\"why\":${RecorderFiles.q(g.why)},\"types\":{$types}"
            gapHistory += "{$body}"
            if (part > 0) selfLine("gap", body)
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

    /** The disk failed for good: nothing more can be written, so the rest is only counted. */
    private fun failDrain() {
        running = false
        while (true) {
            val j = queue.poll() ?: break
            if (j !== Wake) recordGap(j, "io_error")
        }
        synchronized(gapLock) { gaps.values.forEach { g -> gapHistory += "{\"range\":[${g.seqA},${g.seqB}],\"lines\":${g.lines},\"why\":\"${g.why}\"}" }; gaps.clear() }
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
        m.add("gaps", JsonArray().also { a -> gapHistory.forEach { a.add(JsonParser.parseString(it)) } })
        m.add("marks", JsonArray().also { a -> marks.forEach { a.add(JsonParser.parseString(it)) } })
        m.addProperty("lines", lines)
        m.addProperty("gzBytes", gzTotal)
        m.addProperty("rawBytes", rawTotal)
        return GSON.toJson(m)
    }

    // ------------------------------------------------------------------ IO thread

    private fun ioLoop() {
        try {
            Files.createDirectories(dir)
            while (true) {
                val op = ioQueue.poll(1, TimeUnit.SECONDS)
                val now = System.currentTimeMillis()
                if (op != null) {
                    ioBytes.addAndGet(-op.bytes)
                    if (op is IoOp.Finish) break
                    if (abandoned) continue
                    if (ioFailed == null || op is IoOp.Manifest) {
                        val t0 = System.nanoTime()
                        try { perform(op) } catch (e: Throwable) { if (op is IoOp.Manifest) log.warn("[ec] recorder manifest", e) else fail(e) }
                        ioNs += System.nanoTime() - t0
                    }
                }
                if (abandoned) continue
                if (now - lastForceMs >= FORCE_MS) { lastForceMs = now; channels.values.forEach { runCatching { it.force(false) } } }
                if (now - lastGuardMs >= GUARD_MS && running) { lastGuardMs = now; runCatching { diskGuard() } }
            }
        } catch (t: Throwable) {
            log.error("[ec] recorder io stopped", t)
        } finally {
            closeChannels()
            if (abandoned) runCatching { RecorderFiles.deleteRecursively(dir) }
            OPEN.remove(this)
            if (!abandoned) summary()
        }
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
                ioJsonEnd = op.jsonOff + op.json.size
                if (op.lastSeq > lastGoodSeq) lastGoodSeq = op.lastSeq
            }
            is IoOp.EntTail -> writeAt("${RecorderFiles.partName(op.part, "")}ent.xz.part", op.bytes2, op.off)
            is IoOp.ClosePart -> {
                val p = RecorderFiles.partName(op.part, "")
                idxOff = 0; ioJsonEnd = 0
                for (ext in listOf("jsonl.gz", "raw.gz", "ent.xz", "idx.jsonl")) {
                    val name = if (ext == "idx.jsonl") "$p$ext" else "$p$ext.part"
                    val ch = channels.remove(name) ?: continue
                    try { ch.force(true); ch.close() } catch (e: IOException) { anyCloseFailed = true; runCatching { ch.close() }; throw e }
                    // Never rename a part whose close failed: recovery will check it against the index.
                    if (name.endsWith(".part")) Files.move(dir.resolve(name), dir.resolve(name.removeSuffix(".part")))
                }
            }
            is IoOp.Confirm -> {
                closeChannels()
                val target = dir.resolveSibling(op.finalName)
                // Written first, so a crash mid-rename (or a rename that keeps failing) still keeps it.
                runCatching { RecorderFiles.writeAtomically(dir.resolve("manifest.json"), "{\"format\":\"${RecorderFiles.FORMAT}\",\"id\":${RecorderFiles.q(id)},\"confirmed\":true,\"finalName\":${RecorderFiles.q(op.finalName)},\"complete\":false}") }
                var moved = false
                for (attempt in 0 until 20) {
                    try {
                        try { Files.move(dir, target, StandardCopyOption.ATOMIC_MOVE) } catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(dir, target) }
                        moved = true; break
                    } catch (e: IOException) {
                        Thread.sleep(minOf(5000L, 100L shl minOf(attempt, 6)))
                    }
                }
                if (moved) dir = target else log.warn("[ec] recorder could not rename $dir to ${op.finalName}; recovery will on next start")
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
        label?.let { m.addProperty("confirmed", true); m.addProperty("finalName", dir.fileName.toString()) }
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

    /** A persistent IO error: try to leave a `stopped` line, keep the .part names, warn, stop. */
    private fun fail(e: Throwable) {
        if (ioFailed != null) return
        log.error("[ec] recorder io error in $dir", e)
        ioFailed = e.toString()
        stoppedReason = "io_error"
        running = false
        runCatching {
            val seq = Rec.nextSeq()
            val line = envelope("stopped", seq) + ",\"why\":\"io_error\",\"error\":${RecorderFiles.q(e.toString())}}\n"
            val out = ByteArrayOutputStream()
            Gz6(out).use { it.write(line.toByteArray(Charsets.UTF_8)) }
            // Its own member, indexed like any other, so recovery keeps it.
            val gz = out.toByteArray()
            val p = RecorderFiles.partName(ioPart, "")
            channels["${p}jsonl.gz.part"]?.let { ch ->
                var pos = ioJsonEnd
                val b = ByteBuffer.wrap(gz)
                while (b.hasRemaining()) pos += ch.write(b, pos)
                val idx = "{\"off\":$ioJsonEnd,\"len\":${gz.size},\"raw\":${line.length},\"lines\":1,\"seq\":[$seq,$seq],\"kf\":null,\"types\":{\"stopped\":1}}\n"
                channels["${p}idx.jsonl"]?.let { ic -> ic.write(ByteBuffer.wrap(idx.toByteArray(Charsets.UTF_8)), idxOff) }
            }
        }
        notify("§cDungeon Recorder stopped: could not write to disk (${e.javaClass.simpleName}). What was written so far is kept.")
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
            if (size > cap && c.deleteOldest) { size -= RecorderFiles.deleteOldest(root, dir, size - cap) }
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
        private val log = LoggerFactory.getLogger("engineerclient")
        private val GSON = GsonBuilder().setPrettyPrinting().serializeNulls().create()

        const val QUEUE_BYTES = 512L * 1024 * 1024
        const val IO_QUEUE_BYTES = 256L * 1024 * 1024
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

        private fun installHooks() {
            if (!hooked.compareAndSet(false, true)) return
            // Minecraft.destroy() ends in System.exit, which runs this: the last member and the
            // part renames still happen when the game is closed with a recording open.
            runCatching { Runtime.getRuntime().addShutdownHook(Thread({ shutdownAll(2500) }, "ec-recorder-shutdown")) }
        }

        /** Finishes every open or closing session within [ms] in total. Bounded and idempotent. */
        fun shutdownAll(ms: Long) {
            val deadline = System.currentTimeMillis() + ms
            val sessions = OPEN.toList()
            sessions.forEach { runCatching { it.close() } }
            for (s in sessions) runCatching { s.closeAndWait(maxOf(1, deadline - System.currentTimeMillis())) }
        }

        private fun chat(text: String) {
            runCatching { com.engineerclient.EngineerClient.msg(text) }
        }
    }
}
