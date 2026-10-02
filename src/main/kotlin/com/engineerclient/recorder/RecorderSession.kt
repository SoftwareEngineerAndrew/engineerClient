package com.engineerclient.recorder

import com.engineerclient.EngineerClient
import java.io.BufferedWriter
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream

/**
 * One recording's files. Lines are queued from any thread and built and written on one background
 * thread (packets are turned into JSON there, not on the network or game thread). Files are gzipped
 * JSON Lines, a new part every hour of wall clock or 256 MB, so no single file gets unwieldy.
 *
 * The size budget is compressed bytes per clock hour. Once an hour's writing passes 90% of it, the
 * bulky low-value lines (other entities' movement, particles, sounds) are left out until the hour
 * turns; everything else is still written. A queue that backs up (a slow disk) drops lines rather
 * than memory, and says how many.
 */
class RecorderSession(private val dir: Path, private val label: () -> String, private val budgetBytesPerHour: () -> Long, private val header: () -> String) {

    private class Job(val lowPriority: Boolean, val build: () -> String?)

    private val queue = LinkedBlockingQueue<Job>(QUEUE_LIMIT)
    private val dropped = AtomicLong()
    @Volatile private var running = true
    /**
     * Lines queue up in memory until the session is [confirm]ed (the world turned out to be one to
     * record), so its first moments are kept; [abandon] drops them without writing a file.
     */
    @Volatile private var confirmed = false
    @Volatile private var discard = false
    @Volatile var lowPriorityAllowed = true
        private set

    private val started = LocalDateTime.now()
    private var part = 0
    private var writer: BufferedWriter? = null
    private var counter: Counting? = null
    private var temp: Path? = null
    private var partOpenedMs = 0L
    private var hourStartMs = 0L
    private var hourBytes = 0L
    private var lines = 0L

    private val thread = Thread(::loop, "ec-recorder-writer").apply { isDaemon = true; start() }

    /** Queues a line; [build] runs on the writer thread. Low-priority lines are skipped over budget. */
    fun add(lowPriority: Boolean = false, build: () -> String?) {
        if (!running || (lowPriority && !lowPriorityAllowed)) return
        if (!queue.offer(Job(lowPriority, build))) dropped.incrementAndGet()
    }

    fun line(text: String) = add { text }

    fun confirm() { confirmed = true }

    /** Stops without writing anything still unconfirmed. */
    fun abandon() { discard = true; running = false; queue.clear() }

    /** Stops after writing what is queued, and gives the last part its final name. */
    fun close() {
        running = false
        thread.join(5000)
    }

    private fun loop() {
        try {
            while (!confirmed && !discard) Thread.sleep(50)
            while (!discard && (running || queue.isNotEmpty())) {
                val job = queue.poll(250, TimeUnit.MILLISECONDS)
                if (job == null) { flushQuietly(); continue }
                if (job.lowPriority && !lowPriorityAllowed) continue
                val text = runCatching { job.build() }.getOrElse { e -> "{\"k\":\"error\",\"what\":${q(e.toString())}}" } ?: continue
                write(text)
                val d = dropped.getAndSet(0)
                if (d > 0) write("{\"k\":\"dropped\",\"lines\":$d,\"ms\":${System.currentTimeMillis()}}")
            }
        } catch (t: Throwable) {
            EngineerClient.logger.error("[ec] recorder writer stopped", t)
        } finally {
            finishPart()
        }
    }

    private fun write(text: String) {
        val now = System.currentTimeMillis()
        if (writer == null || now - partOpenedMs > PART_MS || (counter?.count ?: 0) > PART_BYTES) { finishPart(); openPart(now) }
        if (now - hourStartMs > HOUR_MS) { hourStartMs = now; hourBytes = 0; lowPriorityAllowed = true }
        val before = counter!!.count
        writer!!.write(text); writer!!.newLine()
        lines++
        hourBytes += counter!!.count - before
        if (hourBytes > budgetBytesPerHour() * 9 / 10 && lowPriorityAllowed) {
            lowPriorityAllowed = false
            writer!!.write("{\"k\":\"budget\",\"ms\":$now,\"note\":\"over 90% of the hour's size budget: entity movement, particles and sounds left out until the hour turns\"}")
            writer!!.newLine()
        }
    }

    private fun openPart(now: Long) {
        Files.createDirectories(dir)
        part++
        partOpenedMs = now
        if (hourStartMs == 0L) hourStartMs = now
        val t = dir.resolve("${started.format(STAMP)}_${label()}_part$part.jsonl.gz.part")
        temp = t
        val c = Counting(Files.newOutputStream(t))
        counter = c
        writer = BufferedWriter(OutputStreamWriter(GZIPOutputStream(c, 1 shl 16), Charsets.UTF_8), 1 shl 16)
        writer!!.write(header().replace("\"part\":0", "\"part\":$part")); writer!!.newLine()
    }

    private fun finishPart() {
        val w = writer ?: return
        runCatching { w.close() }
        writer = null
        val t = temp ?: return
        runCatching { Files.move(t, t.resolveSibling(t.fileName.toString().removeSuffix(".part")), StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun flushQuietly() { runCatching { writer?.flush() } }

    private class Counting(out: OutputStream) : FilterOutputStream(out) {
        @Volatile var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }

    private fun q(s: String) = com.google.gson.JsonPrimitive(s).toString()

    private companion object {
        const val QUEUE_LIMIT = 400_000
        const val PART_MS = 60 * 60 * 1000L
        const val HOUR_MS = 60 * 60 * 1000L
        const val PART_BYTES = 256L * 1024 * 1024
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    }
}
