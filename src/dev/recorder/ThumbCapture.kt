package com.devgineerclient.recorder

import com.devgineerclient.DevgineerClient
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.blaze3d.systems.RenderSystem
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.onReceive
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Frame thumbnails (opt-in, off by default): a small JPEG of the whole screen a few times a second,
 * plus one right after each screen opens and each title appears.
 *
 * Everything else the recorder keeps is what the game was told or did; this is the only record of
 * what other mods drew (HUDs, waypoints, ESP boxes, custom terminal GUIs). The frame is what you
 * saw, private chat included, and a picture cannot be redacted - the setting says so. While you
 * type (chat, a sign, a book, a focused text field) and Typed Chat is off, no frame is taken: the
 * picture would show the text the rest of the recorder leaves out.
 *
 * Threads: [onFrameEnd] runs on the render thread at the end of every frame (RecThumbFrameMixin)
 * and only asks the GPU for an asynchronous copy of the finished main render target into a buffer;
 * the copy's callback (render thread, a frame or two later) only bulk-copies the pixels out (one
 * memcpy) and writes the `thumb` line. Flipping, channel order, scaling, JPEG encoding and the file
 * write happen on one daemon thread, into `<recording>/thumbs/partNNNN/<seq>.jpg`. It still costs
 * a little frame time (the copy, and the bulk read). Frames that cannot be taken (the GPU still busy
 * with earlier copies, the encoder behind, you typing) are counted and the count rides on the next
 * `thumb` line as "skipped", so nothing is left out without a trace.
 */
object ThumbCapture {

    /** Pushed by DungeonRecorder from its settings each tick. */
    @Volatile var on = false
    @Volatile var fps = 1.0

    /** JPEG quality; at a quarter of the screen this keeps a frame around 15-40 KB. */
    const val QUALITY = 0.75f

    /** GPU copies that may be outstanding at once; more means the GPU is not keeping up. */
    private const val MAX_IN_FLIGHT = 3
    /** A copy whose callback never came (lost device, resize mid-copy) stops blocking after this. */
    private const val IN_FLIGHT_TIMEOUT_MS = 5_000L
    /** Frames waiting for the encoder; full size (8 MB each at 1080p), so only a few. */
    private const val QUEUE_FRAMES = 4

    /** Why the next capture is wanted, beyond the timer (screen, title); any thread. */
    private val requests: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private var lastNs = 0L
    private val inFlight = AtomicInteger()
    @Volatile private var inFlightSinceMs = 0L

    /** Frames not taken since the last `thumb` line, by reason. */
    private val skippedGpu = AtomicInteger()
    private val skippedQueue = AtomicInteger()
    private val skippedTyping = AtomicInteger()

    private class Job(
        val session: RecorderSession, val seq: Long, val rel: String,
        val abgr: IntArray, val w: Int, val h: Int, val ow: Int, val oh: Int,
    )

    private val queue = LinkedBlockingQueue<Job>(QUEUE_FRAMES)
    @Volatile private var encoder: Thread? = null

    /** Title and subtitle packets ask for a frame once they are on screen. */
    fun install() {
        onReceive<ClientboundSetTitleTextPacket>(priority = Int.MIN_VALUE) { request("title") }
        onReceive<ClientboundSetSubtitleTextPacket>(priority = Int.MIN_VALUE) { request("subtitle") }
        EventBus.subscribe(this)
    }

    /**
     * Asks for a thumbnail of the next finished frame (any thread; ScreenCapture calls it with "screen" when a screen opens).
     * The frame being drawn when this is called may predate the change, so it is served at the end
     * of the next whole frame, which always shows it.
     */
    fun request(why: String) {
        if (!on || !Rec.active) return
        requests += why
    }

    /** End of a whole frame, render thread (RecThumbFrameMixin). */
    fun onFrameEnd() {
        if (!on) { if (requests.isNotEmpty()) requests.clear(); return }
        if (!Rec.active) return
        DevgineerClient.safely("recorder thumb") { capture() }
    }

    private fun capture() {
        val now = System.nanoTime()
        val due = now - lastNs >= (1e9 / fps.coerceIn(0.1, 60.0)).toLong()
        if (!due && requests.isEmpty()) return
        if (inFlight.get() >= MAX_IN_FLIGHT) {
            if (System.currentTimeMillis() - inFlightSinceMs < IN_FLIGHT_TIMEOUT_MS) {
                // Requests stay pending; the timer frame is the one skipped.
                if (due) { lastNs = now; skippedGpu.incrementAndGet() }
                return
            }
            inFlight.set(0)
        }
        val whys = ArrayList<String>(2)
        requests.toList().forEach { if (requests.remove(it)) whys += it }
        if (due) whys += "fps"
        lastNs = now
        // Typed Chat off: a picture of a text field being typed into would show what is redacted everywhere else.
        if (InputCapture.redactKeys(DevgineerClient.mc.gui.screen())) { skippedTyping.incrementAndGet(); return }

        val session = Rec.session ?: return
        val target = DevgineerClient.mc.gameRenderer.mainRenderTarget()
        val fw = target.width
        val fh = target.height
        val tex = target.colorTexture ?: return
        if (fw <= 0 || fh <= 0 || tex.format.blockSize() != 4) return
        val (ow, oh) = ThumbMath.outSize(fw, fh)
        val seq = Rec.nextSeq()
        val env = Rec.envelope("thumb", seq)
        val ms = System.currentTimeMillis()
        val rel = ThumbMath.relPath(maxOf(1, session.currentPart), seq)
        val why = whys.sorted().joinToString("+")

        if (inFlight.getAndIncrement() == 0) inFlightSinceMs = System.currentTimeMillis()
        try {
            // What Screenshot.takeScreenshot does, without its per-pixel loop on the render thread.
            val device = RenderSystem.getDevice()
            val buf = device.createBuffer({ "Recorder thumbnail" }, GpuBuffer.USAGE_MAP_READ or GpuBuffer.USAGE_COPY_DST, fw.toLong() * fh * 4)
            try {
                device.createCommandEncoder().copyTextureToBuffer(tex, buf, 0L, Runnable { delivered(buf, session, seq, env, ms, rel, why, fw, fh, ow, oh) }, 0)
            } catch (t: Throwable) {
                runCatching { buf.close() }
                throw t
            }
        } catch (t: Throwable) {
            inFlight.decrementAndGet()
            throw t
        }
    }

    /**
     * The GPU copy is back (render thread): one bulk copy of the pixels (ABGR, bottom row first) out
     * of the buffer, the buffer closed, the line written; everything per-pixel is the encoder's.
     */
    private fun delivered(buf: GpuBuffer, session: RecorderSession, seq: Long, env: String, ms: Long,
                          rel: String, why: String, fw: Int, fh: Int, ow: Int, oh: Int) {
        inFlight.updateAndGet { if (it > 0) it - 1 else 0 }
        var abgr: IntArray? = null
        try {
            if (Rec.session === session && session.running) {
                buf.map(true, false).use { view -> abgr = IntArray(fw * fh).also { view.data().asIntBuffer().get(it) } }
            }
        } catch (t: Throwable) {
            DevgineerClient.logger.error("[dc] recorder thumb read failed", t)
        } finally {
            runCatching { buf.close() }
        }
        val px = abgr ?: return
        if (Rec.session !== session || !session.running) return
        val job = Job(session, seq, rel, px, fw, fh, ow, oh)
        val queued = queue.offer(job)
        if (queued) ensureEncoder() else skippedQueue.incrementAndGet()
        if (!queued) return
        val sg = skippedGpu.getAndSet(0)
        val sq = skippedQueue.getAndSet(0)
        val st = skippedTyping.getAndSet(0)
        val skipped = if (sg + sq + st > 0) ",\"skipped\":{\"gpu\":$sg,\"queue\":$sq,\"typing\":$st}" else ""
        val body = ",\"file\":${RecorderFiles.q(rel)},\"w\":$ow,\"h\":$oh,\"why\":${RecorderFiles.q(why)},\"fw\":$fw,\"fh\":$fh,\"q\":$QUALITY$skipped}"
        Rec.emitLine(seq, env.length + body.length, "thumb", ms) { env + body }
    }

    private fun ensureEncoder() {
        if (encoder?.isAlive == true) return
        synchronized(this) {
            if (encoder?.isAlive == true) return
            encoder = Thread(::encodeLoop, "dc-recorder-thumbs").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
        }
    }

    private fun encodeLoop() {
        while (true) {
            val job = try { queue.take() } catch (_: InterruptedException) { return }
            if (job.session.isAbandoned) continue
            try {
                val px = ThumbMath.downscale(ThumbMath.fromGl(job.abgr, job.w, job.h), job.w, job.h, job.ow, job.oh)
                write(job, ThumbMath.jpeg(px, job.ow, job.oh, QUALITY))
            } catch (t: Throwable) {
                DevgineerClient.logger.warn("[dc] recorder thumb ${job.rel} failed", t)
                if (Rec.session === job.session) Rec.emit("error", "\"p\":\"thumb\",\"of\":${job.seq},\"file\":${RecorderFiles.q(job.rel)},\"err\":${RecorderFiles.q(t.toString())}")
            }
        }
    }

    /**
     * Writes into the session's directory as it is now: it is renamed once when the recording is
     * confirmed, so a write that lands mid-rename is retried at the new path. The recording's own
     * directory is never created here; when it is gone (an abandoned recording) the frame goes too,
     * and a frame that lands while an abandoned recording is being deleted is deleted with it.
     */
    private fun write(job: Job, bytes: ByteArray) {
        for (attempt in 0 until 20) {
            if (job.session.isAbandoned) return
            val dir = job.session.dir
            if (Files.isDirectory(dir)) {
                try {
                    writeInto(dir, job.rel, bytes)
                    // abandon() is set before its directory is deleted: a write that raced the delete is undone here.
                    if (job.session.isAbandoned) runCatching { RecorderFiles.deleteRecursively(dir) }
                    return
                } catch (_: NoSuchFileException) {
                    // renamed under us (or deleted); try the new name
                }
            } else if (!job.session.running && attempt > 0) return
            Thread.sleep(100)
        }
        throw java.io.IOException("recording directory unavailable: ${job.session.dir}")
    }

    /** Writes [rel] under [dir], creating only the folders below [dir]: a missing [dir] fails with NoSuchFileException. */
    internal fun writeInto(dir: Path, rel: String, bytes: ByteArray) {
        val f = dir.resolve(rel)
        var p = dir
        for (part in dir.relativize(f.parent)) {
            p = p.resolve(part.toString())
            try { Files.createDirectory(p) } catch (_: java.nio.file.FileAlreadyExistsException) {}
        }
        Files.write(f, bytes)
    }
}

/** The pure parts of [ThumbCapture]: sizes, scaling and encoding. */
object ThumbMath {

    /** Thumbnails are a quarter of the screen each way. */
    const val SCALE = 4

    /**
     * A GPU readback ([w] x [h], ABGR as ints, bottom row first) as ARGB with the top row first,
     * opaque (what [downscale] and [jpeg] take).
     */
    fun fromGl(abgr: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val src = (h - 1 - y) * w
            val dst = y * w
            for (x in 0 until w) {
                val p = abgr[src + x]
                // ABGR -> ARGB: swap the red and blue bytes.
                out[dst + x] = 0xFF000000.toInt() or ((p and 0xFF) shl 16) or (p and 0xFF00) or ((p ushr 16) and 0xFF)
            }
        }
        return out
    }

    /** The thumbnail's size for a [w] x [h] frame. */
    fun outSize(w: Int, h: Int): Pair<Int, Int> = maxOf(1, w / SCALE) to maxOf(1, h / SCALE)

    /** `thumbs/part0001/123.jpg`, relative to the recording directory. */
    fun relPath(part: Int, seq: Long): String = "thumbs/part%04d/%d.jpg".format(part, seq)

    /** Box-averages ARGB [src] ([w] x [h]) to [ow] x [oh]; returns [src] itself when the sizes match. */
    fun downscale(src: IntArray, w: Int, h: Int, ow: Int, oh: Int): IntArray {
        if (w == ow && h == oh) return src
        val out = IntArray(ow * oh)
        for (y in 0 until oh) {
            val y0 = (y.toLong() * h / oh).toInt()
            val y1 = maxOf(y0 + 1, ((y + 1).toLong() * h / oh).toInt())
            for (x in 0 until ow) {
                val x0 = (x.toLong() * w / ow).toInt()
                val x1 = maxOf(x0 + 1, ((x + 1).toLong() * w / ow).toInt())
                var r = 0L; var g = 0L; var b = 0L; var n = 0
                for (yy in y0 until minOf(y1, h)) {
                    val row = yy * w
                    for (xx in x0 until minOf(x1, w)) {
                        val p = src[row + xx]
                        r += (p ushr 16) and 0xFF; g += (p ushr 8) and 0xFF; b += p and 0xFF; n++
                    }
                }
                if (n == 0) n = 1
                out[y * ow + x] = (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
            }
        }
        return out
    }

    /** A baseline JPEG of ARGB [px] (alpha ignored: the frame is opaque on screen). */
    fun jpeg(px: IntArray, w: Int, h: Int, quality: Float): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, w, h, px, 0, w)
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream(w * h / 4)
        try {
            ImageIO.createImageOutputStream(out).use { ios ->
                writer.output = ios
                val param = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = quality }
                writer.write(null, IIOImage(img, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        return out.toByteArray()
    }
}
