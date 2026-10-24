package com.devgineerclient.posepov

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.Locale

/**
 * TEMPORARY: what each feed was given, frame by frame, for the first [FRAMES] frames the leap menu is open each game session,
 * written to config/devgineerclient/posepov-frames.log. For finding the views' shaking; to be removed.
 */
object FrameLog {
    private const val FRAMES = 300
    private val out = StringBuilder()
    private var frames = 0
    private var lastFrameNs = 0L
    private var written = false

    fun feed(i: Int, p: Player, pt: Float, eye: Vec3, yaw: Float, pitch: Float) {
        if (written) return
        val now = System.nanoTime()
        if (i == 0 || now - lastFrameNs > 1_000_000) {
            if (lastFrameNs != 0L) frames++
            out.append(String.format(Locale.ROOT, "F %d t=%.2fms\n", frames, (now - lastFrameNs) / 1e6))
            lastFrameNs = now
        }
        out.append(String.format(Locale.ROOT, "  %d %s id=%x pt=%.3f eye=%.4f,%.4f,%.4f yaw=%.3f pitch=%.3f pos=%.4f,%.4f,%.4f old=%.4f,%.4f,%.4f head=%.3f/%.3f\n",
            i, p.name.string, System.identityHashCode(p), pt, eye.x, eye.y, eye.z, yaw, pitch, p.x, p.y, p.z, p.xo, p.yo, p.zo, p.yHeadRot, p.yHeadRotO))
        if (frames >= FRAMES) flush()
    }

    fun slices(offsets: List<Long>) { if (!written) out.append("    slices ").append(offsets.joinToString(",")).append('\n') }

    private fun flush() {
        written = true
        try {
            val f = FabricLoader.getInstance().configDir.resolve("devgineerclient").resolve("posepov-frames.log")
            Files.createDirectories(f.parent)
            Files.writeString(f, out, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        } catch (_: Exception) {}
        out.setLength(0)
    }
}
