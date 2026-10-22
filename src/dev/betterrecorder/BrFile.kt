package com.devgineerclient.betterrecorder

import com.devgineerclient.DevgineerClient
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream

/**
 * One Better Recorder file: gzipped JSON Lines, written by its own thread. The gzip stream is flushed (sync flush) every second, so
 * a crash loses at most that second and the file still reads up to there. Lines that do not fit the queue (about 400k lines, far
 * more than a run makes) are counted and written as a `gap` line, never waited for.
 */
class BrFile(val path: Path) {
    private val queue = LinkedBlockingQueue<String>(4_000_000)
    private val dropped = AtomicLong()
    @Volatile private var open = true
    val lines = AtomicLong()
    val bytes = AtomicLong()

    private val thread = Thread({ run() }, "better-recorder-writer").also { it.isDaemon = false; it.priority = Thread.NORM_PRIORITY - 1 }

    init {
        Files.createDirectories(path.parent)
        thread.start()
    }

    fun write(line: String) {
        if (!open) return
        if (!queue.offer(line)) dropped.incrementAndGet()
    }

    /** No more lines; the thread writes what is queued, closes the file and ends. */
    fun close() {
        open = false
        queue.offer(STOP)
    }

    fun join(ms: Long) = thread.join(ms)

    private fun run() {
        try {
            val tmp = path.resolveSibling(path.fileName.toString() + ".part")
            GZIPOutputStream(BufferedOutputStream(Files.newOutputStream(tmp), 1 shl 16), 1 shl 16, true).use { out ->
                var lastFlush = System.currentTimeMillis()
                while (true) {
                    val l = queue.poll(250, TimeUnit.MILLISECONDS)
                    if (l === STOP) break
                    if (l != null) {
                        val b = l.toByteArray(Charsets.UTF_8)
                        out.write(b); out.write('\n'.code)
                        lines.incrementAndGet(); bytes.addAndGet(b.size + 1L)
                    }
                    val d = dropped.getAndSet(0)
                    if (d > 0) out.write("{\"k\":\"gap\",\"lines\":$d}\n".toByteArray())
                    val now = System.currentTimeMillis()
                    if (now - lastFlush >= 1000) { out.flush(); lastFlush = now }
                }
                out.flush()
            }
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (t: Throwable) {
            DevgineerClient.logger.error("[dc] better recorder: writing $path failed", t)
        }
    }

    companion object {
        private const val STOP = "\u0000stop"
    }
}
