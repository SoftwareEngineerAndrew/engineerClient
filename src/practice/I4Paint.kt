package com.engineerclient.practice

import com.engineerclient.EngineerClient.mc
import com.engineerclient.practice.I4Geometry.Aim
import com.engineerclient.practice.I4Geometry.Lattice
import com.mojang.blaze3d.pipeline.RenderPipeline
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.resources.Identifier
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.Optional
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * i4 Aim's paint: NOW (#00FF00) over NEXT (cyan), opaque, drawn in the world's own pass with the frame's camera, so
 * it stays on the world every frame (the HUD can be frozen by Exordium), over every block (no depth test) and under
 * every screen (the GUI comes after the world; the pause blur blurs it with the world).
 *
 * Each aim is its spots' count cells: spot (i, j) is every look whose nearest mouse count is (i, j), the directions
 * from yaw0 + (i - 1/2) step to yaw0 + (i + 1/2) step and pitch0 + (j - 1/2) step to pitch0 + (j + 1/2) step. They
 * are drawn as quads of those directions, [D] blocks from the camera, which the GPU projects with the real matrix
 * (zoom, bobbing and all). A pixel is lit when its look rounds to a spot. A run of counts along a lattice row is one
 * strip, cut every [CHORD] counts so its top and bottom (lines of equal pitch, which a straight edge only follows
 * between its corners) stay within 0.007 counts; rows overlap by [EPS] so they never leave a hairline between them.
 *
 * The mesh is built once per pick ([build]); a frame only writes its vertices.
 */
object I4Paint {
    private val PIPELINE: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("engineerclient", "pipeline/i4_paint"))
            .withCull(false)
            .withDepthStencilState(Optional.empty())
            .build()
    )
    private val TYPE: RenderType = RenderType.create("engineerclient_i4_paint", RenderSetup.builder(PIPELINE).bufferSize(RenderType.SMALL_BUFFER_SIZE).createRenderSetup())

    const val GREEN = 0xFF00FF00.toInt()
    const val CYAN = 0xFF00FFFF.toInt()
    /** Blocks from the camera the quads are drawn at (any distance past the near plane draws the same). */
    private const val D = 2.0
    private const val CHORD = 16
    private const val EPS = 0.01

    /** Loads this object (and registers its pipeline). */
    fun init() {}

    /** Vertices (x, y, z each, camera-relative) of quads, and each vertex's colour. */
    class Mesh(val xyz: FloatArray, val argb: IntArray, val vertices: Int)

    /** The quads of NEXT (cyan) then NOW (green, so it is drawn over NEXT where they meet), on lattice [l]. */
    fun build(l: Lattice, now: Aim?, next: Aim?): Mesh? {
        if (now == null && next == null) return null
        val xyz = FloatList(); val argb = IntList()
        next?.let { emit(l, it, CYAN, xyz, argb) }
        now?.let { emit(l, it, GREEN, xyz, argb) }
        return Mesh(xyz.toArray(), argb.toArray(), argb.size)
    }

    private fun emit(l: Lattice, aim: Aim, color: Int, xyz: FloatList, argb: IntList) {
        for (k in 0 until aim.rowStart.size - 1) {
            val j = aim.jMin + k
            val top = Math.toRadians(l.pitch0 + (j - 0.5 - EPS) * l.step); val bottom = Math.toRadians(l.pitch0 + (j + 0.5 + EPS) * l.step)
            val cT = cos(top); val sT = sin(top); val cB = cos(bottom); val sB = sin(bottom)
            for (r in aim.rowStart[k] until aim.rowStart[k + 1]) {
                val a = aim.runs[2 * r]; val b = aim.runs[2 * r + 1]
                var lo = a
                while (lo <= b) {
                    val hi = minOf((Math.floorDiv(lo, CHORD) + 1) * CHORD, b + 1)
                    val y0 = Math.toRadians(l.yaw0 + (lo - 0.5) * l.step); val y1 = Math.toRadians(l.yaw0 + (hi - 0.5) * l.step)
                    val s0 = sin(y0); val c0 = cos(y0); val s1 = sin(y1); val c1 = cos(y1)
                    vertex(xyz, -s0 * cT, -sT, c0 * cT); vertex(xyz, -s1 * cT, -sT, c1 * cT)
                    vertex(xyz, -s1 * cB, -sB, c1 * cB); vertex(xyz, -s0 * cB, -sB, c0 * cB)
                    repeat(4) { argb.add(color) }
                    lo = hi
                }
            }
        }
    }

    private fun vertex(xyz: FloatList, x: Double, y: Double, z: Double) { xyz.add((x * D).toFloat()); xyz.add((y * D).toFloat()); xyz.add((z * D).toFloat()) }

    private val pose = Matrix4f()
    private val turn = Quaternionf()
    private val to = Vector3f()

    /**
     * Draws [m] this frame. The crosshair sprite is not centred on the screen's centre (at 3840x2160, GUI scale 4,
     * its middle is 2 pixels up and left: ((guiWidth - 15) / 2 + 7.5) x scale), so the paint is turned by that much:
     * putting the crosshair's middle on green puts your look on a spot.
     */
    fun draw(ctx: LevelRenderContext, m: Mesh) {
        val cam = mc.gameRenderer.mainCamera
        val window = mc.window
        val gs = window.guiScale
        val dx = ((window.guiScaledWidth - 15) / 2 + 7.5) * gs - window.width / 2.0
        val dy = ((window.guiScaledHeight - 15) / 2 + 7.5) * gs - window.height / 2.0
        pose.identity()
        if (dx != 0.0 || dy != 0.0) {
            val focal = window.height / 2.0 / tan(Math.toRadians(cam.fov / 2.0))
            val f = cam.forwardVector(); val left = cam.leftVector(); val up = cam.upVector()
            to.set(f).sub(left.x() * (dx / focal).toFloat(), left.y() * (dx / focal).toFloat(), left.z() * (dx / focal).toFloat())
                .sub(up.x() * (dy / focal).toFloat(), up.y() * (dy / focal).toFloat(), up.z() * (dy / focal).toFloat()).normalize()
            pose.rotation(turn.rotationTo(f, to))
        }
        val buffers = ctx.bufferSource()
        val vc = buffers.getBuffer(TYPE)
        val xyz = m.xyz; val argb = m.argb
        for (k in 0 until m.vertices) vc.addVertex(pose, xyz[3 * k], xyz[3 * k + 1], xyz[3 * k + 2]).setColor(argb[k])
        buffers.endBatch(TYPE)
    }

    private class FloatList {
        private var a = FloatArray(1024); private var n = 0
        fun add(x: Float) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = x }
        fun toArray(): FloatArray = a.copyOf(n)
    }

    private class IntList {
        private var a = IntArray(256); var size = 0; private set
        fun add(x: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = x }
        fun toArray(): IntArray = a.copyOf(size)
    }
}
