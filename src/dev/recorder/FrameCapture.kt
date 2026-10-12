package com.devgineerclient.recorder

import com.devgineerclient.DevgineerClient
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import java.lang.management.ManagementFactory

/**
 * Time itself: the camera as it was drawn in every frame (`frames`, flushed once per tick), and one
 * `tick` line per client tick with the game time, tick rate, how long the tick took, frames drawn,
 * FPS, focus, memory and garbage collection.
 *
 * Per-frame rows are what make rendered positions exact offline: an entity is drawn at
 * lerp(xo, x, partialTick), and the per-tick rows carry xo and x, so the partial tick of each frame
 * is all that is missing. Frames from other cameras are skipped.
 * Everything runs on the render/game thread, which in Minecraft are the same one.
 */
object FrameCapture {

    /** The module's "Per-Frame Camera" setting, pushed in by [DungeonRecorder]. */
    @Volatile var perFrame = true

    /** Rows of this tick's frames, `[...],[...]` without the outer brackets. */
    private val rows = StringBuilder(4096)
    private var frames = 0
    private var tickStartNs = 0L
    private var loggedFailure = false

    fun install() {
        LevelRenderEvents.END_EXTRACTION.register { ctx -> if (Rec.active) guard("frame") { frame(ctx.camera(), ctx.deltaTracker()) } }
        ClientTickEvents.START_CLIENT_TICK.register { _ -> if (Rec.active) tickStartNs = System.nanoTime() }
        ClientTickEvents.END_CLIENT_TICK.register { mc -> if (Rec.active) guard("tick") { endTick(mc) } else { rows.setLength(0); frames = 0 } }
    }

    private fun frame(cam: net.minecraft.client.Camera, dt: net.minecraft.client.DeltaTracker) {
        val mc = DevgineerClient.mc
        val e = cam.entity()
        if (e == null || e !== mc.player) return
        frames++
        if (!perFrame) return
        val pos = cam.position()
        frameRow(rows, Rec.nowNs(), dt.getGameTimeDeltaPartialTick(true), cam.yRot(), cam.xRot(), pos.x, pos.y, pos.z,
            cam.fov, cam.isDetached, cam.fluidInCamera.name, e.id, mc.frameTimeNs)
    }

    /**
     * One frame row: [ns since session start, partial tick, yaw, pitch, x, y, z, fov, detached 1/0,
     * fluid in camera, camera entity id, Minecraft's frame time ns]. Appended to [sb] after a comma
     * when it already holds rows.
     */
    fun frameRow(sb: StringBuilder, ns: Long, pt: Float, yRot: Float, xRot: Float, x: Double, y: Double, z: Double,
                 fov: Float, detached: Boolean, fluid: String, entityId: Int, frameTimeNs: Long) {
        if (sb.isNotEmpty()) sb.append(',')
        sb.append('['); PacketJson.num(sb, ns); sb.append(',')
        PacketJson.num(sb, pt); sb.append(','); PacketJson.num(sb, yRot); sb.append(','); PacketJson.num(sb, xRot); sb.append(',')
        PacketJson.num(sb, x); sb.append(','); PacketJson.num(sb, y); sb.append(','); PacketJson.num(sb, z); sb.append(',')
        PacketJson.num(sb, fov); sb.append(',').append(if (detached) 1 else 0).append(',').append(RecorderFiles.q(fluid))
            .append(',').append(entityId).append(','); PacketJson.num(sb, frameTimeNs); sb.append(']')
    }

    private fun endTick(mc: Minecraft) {
        val dur = if (tickStartNs != 0L) System.nanoTime() - tickStartNs else -1L
        if (rows.isNotEmpty()) {
            Rec.emit("frames", "\"d\":[$rows]")
            rows.setLength(0)
        }
        val sb = StringBuilder(320)
        val level = mc.level
        if (level != null) {
            sb.append("\"gt\":"); PacketJson.num(sb, level.gameTime)
            sb.append(",\"clock\":"); PacketJson.num(sb, level.defaultClockTime)
            val trm = level.tickRateManager()
            sb.append(",\"rate\":"); PacketJson.num(sb, trm.tickrate()); sb.append(",\"frozen\":").append(trm.isFrozen).append(',')
        }
        sb.append("\"dur\":"); PacketJson.num(sb, dur)
        sb.append(",\"frames\":").append(frames); frames = 0
        sb.append(",\"fps\":").append(mc.fps)
        sb.append(",\"rt\":"); PacketJson.num(sb, mc.deltaTracker.realtimeDeltaTicks)
        sb.append(",\"focus\":").append(mc.window.isFocused)
        sb.append(",\"active\":").append(mc.isWindowActive)
        sb.append(",\"gpu\":"); PacketJson.num(sb, mc.gpuUtilization)
        val rt = Runtime.getRuntime()
        val total = rt.totalMemory()
        sb.append(",\"mem\":["); PacketJson.num(sb, total - rt.freeMemory()); sb.append(','); PacketJson.num(sb, total); sb.append(','); PacketJson.num(sb, rt.maxMemory()); sb.append(']')
        var count = 0L; var ms = 0L
        for (gc in ManagementFactory.getGarbageCollectorMXBeans()) {
            count += gc.collectionCount.coerceAtLeast(0); ms += gc.collectionTime.coerceAtLeast(0)
        }
        sb.append(",\"gc\":[").append(count).append(',').append(ms).append(']')
        Rec.emit("tick", sb.toString())
    }

    private inline fun guard(what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) {
            if (!loggedFailure) { loggedFailure = true; DevgineerClient.logger.error("[dc] recorder $what failed", t) }
            Rec.emit("error", "\"p\":${RecorderFiles.q("frame:$what")},\"err\":${RecorderFiles.q(t.toString())}")
        }
    }
}
