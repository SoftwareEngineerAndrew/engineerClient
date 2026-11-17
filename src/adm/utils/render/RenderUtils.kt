package com.adm.utils.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.adm.AdmMod.mc
import com.adm.events.RenderExtractEvent
import com.adm.utils.Color
import com.adm.utils.Color.Companion.multiplyAlphaRgba
import com.adm.utils.addVec
import com.adm.utils.center
import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhases
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BeaconRenderer
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.locale.Language
import net.minecraft.network.chat.FormattedText
import net.minecraft.resources.Identifier
import net.minecraft.util.FormattedCharSequence
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private fun Int.isFullyOpaque(): Boolean = ((this ushr 24) and 0xFF) == 0xFF

private fun resolveLineRenderType(depth: Boolean, fullyOpaque: Boolean) = when {
    depth && fullyOpaque -> RenderTypes.LINES
    depth -> CustomRenderType.LINES_TRANSLUCENT_DEPTH
    fullyOpaque -> CustomRenderType.LINES_ESP
    else -> CustomRenderType.LINES_TRANSLUCENT_ESP
}

private fun resolveQuadRenderType(depth: Boolean, fullyOpaque: Boolean) = when {
    depth && fullyOpaque -> CustomRenderType.QUADS_OPAQUE
    depth -> CustomRenderType.QUADS_TRANSLUCENT
    fullyOpaque -> CustomRenderType.QUADS_ESP
    else -> CustomRenderType.QUADS_TRANSLUCENT_ESP
}

private fun RenderExtractEvent.submitGeometry(poseStack: PoseStack, renderType: RenderType, depth: Boolean, renderer: SubmitNodeCollector.CustomGeometryRenderer) {
    val collector = context.submitNodeCollector()
    if (depth) return collector.submitCustomGeometry(poseStack, renderType, renderer)
    collector.submitCustom(SubmitRenderPhases.SEE_THROUGH_NAME_TAGS, NoDepthSubmit(poseStack.last().copy(), renderType, renderer))
}

private fun RenderExtractEvent.cameraRelativePose(offset: Vec3 = Vec3.ZERO): PoseStack {
    val camera = mc.gameRenderer.mainCamera().position()
    val poseStack = context.poseStack()
    poseStack.pushPose()
    poseStack.translate(offset.x - camera.x, offset.y - camera.y, offset.z - camera.z)
    return poseStack
}

fun RenderExtractEvent.drawTexturedQuad(
    texture: Identifier,
    pos: Vec3,
    width: Float,
    height: Float,
    yaw: Float = 0f,
    color: Color = Color(255, 255, 255)
) {
    val yawRad = Math.toRadians(yaw.toDouble())
    val rx = cos(yawRad).toFloat()
    val rz = sin(yawRad).toFloat()
    val hw = width * 0.5
    val hh = height * 0.5
    val rgba = color.rgba

    val poseStack = cameraRelativePose()
    context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.entityCutoutZOffset(texture)) { pose, buffer ->
        fun vertex(x: Double, y: Double, z: Double, u: Float, v: Float) {
            buffer.addVertex(pose, x.toFloat(), y.toFloat(), z.toFloat())
                .setColor(rgba)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(LightCoordsUtil.FULL_BRIGHT, LightCoordsUtil.FULL_BRIGHT)
                .setNormal(pose, -rz, 0f, rx)
        }

        vertex(pos.x - rx * hw, pos.y - hh, pos.z - rz * hw, 0f, 1f)
        vertex(pos.x - rx * hw, pos.y + hh, pos.z - rz * hw, 0f, 0f)
        vertex(pos.x + rx * hw, pos.y + hh, pos.z + rz * hw, 1f, 0f)
        vertex(pos.x + rx * hw, pos.y - hh, pos.z + rz * hw, 1f, 1f)
    }
    poseStack.popPose()
}

fun RenderExtractEvent.drawTracer(to: Vec3, color: Color, depth: Boolean, thickness: Float = 3f) {
    val cam = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState
    drawLine(listOf(cam.pos.add(Vec3.directionFromRotation(cam.xRot, cam.yRot)), to), color, depth, thickness)
}

fun RenderExtractEvent.drawLine(points: Collection<Vec3>, color: Color, depth: Boolean, thickness: Float = 3f) {
    drawLine(points, color.rgba, color.rgba, depth, thickness)
}

fun RenderExtractEvent.drawLine(points: Collection<Vec3>, color1: Color, color2: Color, depth: Boolean, thickness: Float = 3f) {
    drawLine(points, color1.rgba, color2.rgba, depth, thickness)
}

fun RenderExtractEvent.drawLine(points: Collection<Vec3>, rgba1: Int, rgba2: Int, depth: Boolean, thickness: Float = 3f) {
    if (points.size < 2) return

    val renderType = resolveLineRenderType(depth, rgba1.isFullyOpaque() && rgba2.isFullyOpaque())
    val coords = DoubleArray(points.size * 3)
    var n = 0
    for (p in points) { coords[n++] = p.x; coords[n++] = p.y; coords[n++] = p.z }

    val poseStack = cameraRelativePose()
    submitGeometry(poseStack, renderType, depth) { pose, buffer ->
        var i = 0
        while (i + 3 < n) {
            PrimitiveRenderer.renderSegment(
                pose, buffer,
                coords[i], coords[i + 1], coords[i + 2], coords[i + 3], coords[i + 4], coords[i + 5],
                rgba1, rgba2, thickness
            )
            i += 3
        }
    }
    poseStack.popPose()
}

fun RenderExtractEvent.drawWireFrameBox(aabb: AABB, color: Color, thickness: Float = 3f, depth: Boolean = false) {
    wireFrameBox(aabb, color.rgba, color.alphaFloat >= 0.999f, thickness, depth)
}

fun RenderExtractEvent.drawWireFrameBox(aabb: AABB, rgba: Int, thickness: Float = 3f, depth: Boolean = false) {
    wireFrameBox(aabb, rgba, rgba.isFullyOpaque(), thickness, depth)
}

private fun RenderExtractEvent.wireFrameBox(aabb: AABB, rgba: Int, opaque: Boolean, thickness: Float, depth: Boolean) {
    val poseStack = cameraRelativePose()
    submitGeometry(poseStack, resolveLineRenderType(depth, opaque), depth) { pose, buffer ->
        PrimitiveRenderer.renderLineBox(
            pose, buffer,
            aabb.minX.toFloat(), aabb.minY.toFloat(), aabb.minZ.toFloat(),
            aabb.maxX.toFloat(), aabb.maxY.toFloat(), aabb.maxZ.toFloat(),
            rgba, thickness
        )
    }
    poseStack.popPose()
}

fun RenderExtractEvent.drawFilledBox(aabb: AABB, color: Color, depth: Boolean = false) {
    filledBox(aabb, color.rgba, color.alphaFloat >= 0.999f, depth)
}

fun RenderExtractEvent.drawFilledBox(aabb: AABB, rgba: Int, depth: Boolean = false) {
    filledBox(aabb, rgba, rgba.isFullyOpaque(), depth)
}

private fun RenderExtractEvent.filledBox(aabb: AABB, rgba: Int, opaque: Boolean, depth: Boolean) {
    val poseStack = cameraRelativePose()
    submitGeometry(poseStack, resolveQuadRenderType(depth, opaque), depth) { pose, buffer ->
        PrimitiveRenderer.addChainedFilledBoxVertices(
            pose, buffer,
            aabb.minX.toFloat(), aabb.minY.toFloat(), aabb.minZ.toFloat(),
            aabb.maxX.toFloat(), aabb.maxY.toFloat(), aabb.maxZ.toFloat(),
            rgba
        )
    }
    poseStack.popPose()
}

enum class BoxStyle { FILLED, OUTLINE, FILLED_OUTLINE }

fun RenderExtractEvent.drawStyledBox(
    aabb: AABB,
    color: Color,
    style: BoxStyle = BoxStyle.FILLED,
    depth: Boolean = true
) {
    when (style) {
        BoxStyle.FILLED -> drawFilledBox(aabb, color, depth = depth)
        BoxStyle.OUTLINE -> drawWireFrameBox(aabb, color, depth = depth)
        BoxStyle.FILLED_OUTLINE -> {
            drawFilledBox(aabb.inflate(0.00005), color.multiplyAlphaRgba(0.5f), depth = depth)
            drawWireFrameBox(aabb, color, depth = depth)
        }
    }
}

/** Boxes gathered over a frame and drawn with [drawBoxes]: one submit per render type instead of one per box. */
class BoxBatch {
    internal var size = 0
        private set
    internal var bounds = FloatArray(16 * 6)
        private set
    internal var colors = IntArray(16)
        private set
    internal var widths = FloatArray(16)
        private set
    internal var kinds = ByteArray(16)
        private set

    fun clear() { size = 0 }

    fun filled(aabb: AABB, color: Color, depth: Boolean = false) = add(aabb, color.rgba, color.alphaFloat >= 0.999f, depth, 0f, false)
    fun filled(aabb: AABB, rgba: Int, depth: Boolean = false) = add(aabb, rgba, rgba.isFullyOpaque(), depth, 0f, false)
    fun outline(aabb: AABB, color: Color, thickness: Float = 3f, depth: Boolean = false) = add(aabb, color.rgba, color.alphaFloat >= 0.999f, depth, thickness, true)
    fun outline(aabb: AABB, rgba: Int, thickness: Float = 3f, depth: Boolean = false) = add(aabb, rgba, rgba.isFullyOpaque(), depth, thickness, true)

    private fun add(aabb: AABB, rgba: Int, opaque: Boolean, depth: Boolean, thickness: Float, outline: Boolean) {
        if (size == colors.size) {
            val cap = size * 2
            bounds = bounds.copyOf(cap * 6); colors = colors.copyOf(cap); widths = widths.copyOf(cap); kinds = kinds.copyOf(cap)
        }
        val o = size * 6
        bounds[o] = aabb.minX.toFloat(); bounds[o + 1] = aabb.minY.toFloat(); bounds[o + 2] = aabb.minZ.toFloat()
        bounds[o + 3] = aabb.maxX.toFloat(); bounds[o + 4] = aabb.maxY.toFloat(); bounds[o + 5] = aabb.maxZ.toFloat()
        colors[size] = rgba
        widths[size] = thickness
        kinds[size] = ((if (outline) KIND_OUTLINE else 0) or (if (depth) KIND_DEPTH else 0) or (if (opaque) KIND_OPAQUE else 0)).toByte()
        size++
    }

    internal companion object {
        const val KIND_OUTLINE = 1
        const val KIND_DEPTH = 2
        const val KIND_OPAQUE = 4

        fun renderType(kind: Int): RenderType {
            val depth = kind and KIND_DEPTH != 0
            val opaque = kind and KIND_OPAQUE != 0
            return if (kind and KIND_OUTLINE != 0) resolveLineRenderType(depth, opaque) else resolveQuadRenderType(depth, opaque)
        }

        fun render(pose: PoseStack.Pose, buffer: VertexConsumer, kind: Int, bounds: FloatArray, rgba: Int, width: Float, i: Int) {
            val o = i * 6
            if (kind and KIND_OUTLINE != 0) PrimitiveRenderer.renderLineBox(
                pose, buffer, bounds[o], bounds[o + 1], bounds[o + 2], bounds[o + 3], bounds[o + 4], bounds[o + 5], rgba, width
            )
            else PrimitiveRenderer.addChainedFilledBoxVertices(
                pose, buffer, bounds[o], bounds[o + 1], bounds[o + 2], bounds[o + 3], bounds[o + 4], bounds[o + 5], rgba
            )
        }
    }
}

/** Same vertices in the same order as drawing each box on its own; the batch can be cleared and refilled right after. */
fun RenderExtractEvent.drawBoxes(batch: BoxBatch) {
    val n = batch.size
    if (n == 0) return
    val bounds = batch.bounds.copyOf(n * 6)
    val colors = batch.colors.copyOf(n)
    val widths = batch.widths.copyOf(n)
    val kinds = batch.kinds.copyOf(n)

    var depthKinds = 0
    var anyNoDepth = false
    for (i in 0 until n) {
        val k = kinds[i].toInt()
        if (k and BoxBatch.KIND_DEPTH != 0) depthKinds = depthKinds or (1 shl k) else anyNoDepth = true
    }

    val poseStack = cameraRelativePose()
    val collector = context.submitNodeCollector()
    for (kind in 0 until 8) {
        if (depthKinds and (1 shl kind) == 0) continue
        collector.submitCustomGeometry(poseStack, BoxBatch.renderType(kind)) { pose, buffer ->
            for (i in 0 until n) if (kinds[i].toInt() == kind) BoxBatch.render(pose, buffer, kind, bounds, colors[i], widths[i], i)
        }
    }
    if (anyNoDepth) collector.submitCustom(SubmitRenderPhases.SEE_THROUGH_NAME_TAGS, NoDepthSubmit(poseStack.last().copy()) { pose, buffers ->
        for (i in 0 until n) {
            val k = kinds[i].toInt()
            if (k and BoxBatch.KIND_DEPTH == 0) BoxBatch.render(pose, buffers.apply(BoxBatch.renderType(k)), k, bounds, colors[i], widths[i], i)
        }
    })
    poseStack.popPose()
}

fun RenderExtractEvent.drawBeaconBeam(position: BlockPos, color: Color) {
    val camera = mc.gameRenderer.mainCamera().position()
    val animationTime = (mc.level?.gameTime ?: 0L).mod(40L) +  mc.deltaTracker.getGameTimeDeltaPartialTick(false)
    val scale = if (mc.player?.isScoping == true) 1f else max(1f, camera.subtract(Vec3.atCenterOf(position)).horizontalDistance().toFloat() / 96f)

    val poseStack = cameraRelativePose(Vec3.atLowerCornerOf(position))
    BeaconRenderer.submitBeaconBeam(
        poseStack, context.submitNodeCollector(), BeaconRenderer.BEAM_LOCATION, 1f,
        animationTime, 0, BeaconRenderer.MAX_RENDER_Y, color.rgba,
        BeaconRenderer.SOLID_BEAM_RADIUS * scale, BeaconRenderer.BEAM_GLOW_RADIUS * scale
    )
    poseStack.popPose()
}

private val visualOrders = HashMap<String, FormattedCharSequence>()
private var visualOrderLanguage: Language? = null

private fun visualOrder(text: String): FormattedCharSequence {
    val language = Language.getInstance()
    // Changing texts (timers, distances) would otherwise grow it without bound.
    if (language !== visualOrderLanguage || visualOrders.size >= 512) {
        visualOrders.clear()
        visualOrderLanguage = language
    }
    return visualOrders.getOrPut(text) { language.getVisualOrder(FormattedText.of(text)) }
}

fun RenderExtractEvent.drawText(text: String, pos: Vec3, scale: Float, depth: Boolean) {
    val scaleFactor = scale * 0.025f
    val displayMode = if (depth) Font.DisplayMode.POLYGON_OFFSET else Font.DisplayMode.SEE_THROUGH
    val string = visualOrder(text)
    val x = -mc.font.width(text).toFloat() / 2f
    val camera = mc.gameRenderer.mainCamera()
    val cameraPos = camera.position()

    val poseStack = context.poseStack()
    poseStack.pushPose()
    poseStack.last().pose()
        .translate(pos.x.toFloat(), pos.y.toFloat(), pos.z.toFloat())
        .translate(-cameraPos.x.toFloat(), -cameraPos.y.toFloat(), -cameraPos.z.toFloat())
        .rotate(camera.rotation())
        .scale(scaleFactor, -scaleFactor, scaleFactor)

    context.submitNodeCollector().submitText(poseStack, x, 0f, string, true, displayMode, LightCoordsUtil.FULL_BRIGHT, -1, 0, 0)
    poseStack.popPose()
}

fun RenderExtractEvent.drawCustomBeacon(title: String, position: BlockPos, color: Color, increase: Boolean = true, distance: Boolean = true) {
    val dist = mc.player?.blockPosition()?.distManhattan(position) ?: return

    drawWireFrameBox(AABB(position), color, depth = false)
    drawBeaconBeam(position, color)
    drawText(
        (if (distance) ("$title §f(§3${dist}m§f)") else title),
        position.center.addVec(y = 1.7),
        if (increase) max(1f, dist * 0.05f) else 2f,
        false
    )
}

fun RenderExtractEvent.drawCylinder(
    center: Vec3,
    radius: Float,
    height: Float,
    color: Color,
    segments: Int = 32,
    thickness: Float = 5f,
    depth: Boolean = false
) {
    val rgba = color.rgba
    val renderType = resolveLineRenderType(depth, rgba.isFullyOpaque())
    val angleStep = 2.0 * Math.PI / segments
    val cx = center.x
    val cy = center.y
    val cz = center.z
    val top = cy + height.toDouble()
    val bottom = cy + 0.0

    val poseStack = cameraRelativePose()
    submitGeometry(poseStack, renderType, depth) { pose, buffer ->
        var x1 = cx + (radius * cos(0 * angleStep)).toFloat().toDouble()
        var z1 = cz + (radius * sin(0 * angleStep)).toFloat().toDouble()
        for (i in 0 until segments) {
            val angle2 = (i + 1) * angleStep
            val x2 = cx + (radius * cos(angle2)).toFloat().toDouble()
            val z2 = cz + (radius * sin(angle2)).toFloat().toDouble()

            PrimitiveRenderer.renderSegment(pose, buffer, x1, top, z1, x2, top, z2, rgba, rgba, thickness)
            PrimitiveRenderer.renderSegment(pose, buffer, x1, bottom, z1, x2, bottom, z2, rgba, rgba, thickness)
            PrimitiveRenderer.renderSegment(pose, buffer, x1, bottom, z1, x1, top, z1, rgba, rgba, thickness)
            x1 = x2
            z1 = z2
        }
    }
    poseStack.popPose()
}

object PrimitiveRenderer {

    fun renderLineBox(
        pose: PoseStack.Pose,
        buffer: VertexConsumer,
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        rgba: Int,
        thickness: Float
    ) {
        val r = (rgba shr 16) and 0xFF
        val g = (rgba shr 8) and 0xFF
        val b = rgba and 0xFF
        val a = (rgba ushr 24) and 0xFF

        fun edge(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float) {
            val dx = bx - ax
            val dy = by - ay
            val dz = bz - az
            buffer.addVertex(pose, ax, ay, az).setColor(r, g, b, a).setNormal(pose, dx, dy, dz).setLineWidth(thickness)
            buffer.addVertex(pose, bx, by, bz).setColor(r, g, b, a).setNormal(pose, dx, dy, dz).setLineWidth(thickness)
        }

        edge(x0, y0, z0, x1, y0, z0)
        edge(x1, y0, z0, x1, y0, z1)
        edge(x1, y0, z1, x0, y0, z1)
        edge(x0, y0, z1, x0, y0, z0)
        edge(x0, y1, z0, x1, y1, z0)
        edge(x1, y1, z0, x1, y1, z1)
        edge(x1, y1, z1, x0, y1, z1)
        edge(x0, y1, z1, x0, y1, z0)
        edge(x0, y0, z0, x0, y1, z0)
        edge(x1, y0, z0, x1, y1, z0)
        edge(x1, y0, z1, x1, y1, z1)
        edge(x0, y0, z1, x0, y1, z1)
    }

    fun addChainedFilledBoxVertices(
        pose: PoseStack.Pose,
        buffer: VertexConsumer,
        minX: Float, minY: Float, minZ: Float,
        maxX: Float, maxY: Float, maxZ: Float,
        rgba: Int
    ) {
        val matrix = pose.pose()
        val r = (rgba shr 16) and 0xFF
        val g = (rgba shr 8) and 0xFF
        val b = rgba and 0xFF
        val a = (rgba ushr 24) and 0xFF

        fun vertex(x: Float, y: Float, z: Float) {
            buffer.addVertex(matrix, x, y, z).setColor(r, g, b, a)
        }

        vertex(minX, minY, minZ)
        vertex(minX, minY, maxZ)
        vertex(minX, maxY, maxZ)
        vertex(minX, maxY, minZ)

        vertex(maxX, minY, maxZ)
        vertex(maxX, minY, minZ)
        vertex(maxX, maxY, minZ)
        vertex(maxX, maxY, maxZ)

        vertex(minX, minY, minZ)
        vertex(minX, maxY, minZ)
        vertex(maxX, maxY, minZ)
        vertex(maxX, minY, minZ)

        vertex(maxX, minY, maxZ)
        vertex(maxX, maxY, maxZ)
        vertex(minX, maxY, maxZ)
        vertex(minX, minY, maxZ)

        vertex(minX, minY, minZ)
        vertex(maxX, minY, minZ)
        vertex(maxX, minY, maxZ)
        vertex(minX, minY, maxZ)

        vertex(minX, maxY, maxZ)
        vertex(maxX, maxY, maxZ)
        vertex(maxX, maxY, minZ)
        vertex(minX, maxY, minZ)
    }

    fun renderVector(
        pose: PoseStack.Pose,
        buffer: VertexConsumer,
        start: Vector3f,
        direction: Vec3,
        startColor: Int,
        endColor: Int,
        thickness: Float
    ) = segment(pose, buffer, start.x(), start.y(), start.z(), direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat(), startColor, endColor, thickness)

    /** From (ax, ay, az) to (bx, by, bz), rounded to floats the way [renderVector] with `b - a` would be. */
    fun renderSegment(
        pose: PoseStack.Pose,
        buffer: VertexConsumer,
        ax: Double, ay: Double, az: Double,
        bx: Double, by: Double, bz: Double,
        startColor: Int,
        endColor: Int,
        thickness: Float
    ) = segment(pose, buffer, ax.toFloat(), ay.toFloat(), az.toFloat(), (bx - ax).toFloat(), (by - ay).toFloat(), (bz - az).toFloat(), startColor, endColor, thickness)

    private fun segment(
        pose: PoseStack.Pose,
        buffer: VertexConsumer,
        sx: Float, sy: Float, sz: Float,
        nx: Float, ny: Float, nz: Float,
        startColor: Int,
        endColor: Int,
        thickness: Float
    ) {
        buffer.addVertex(pose, sx, sy, sz)
            .setColor(startColor)
            .setNormal(pose, nx, ny, nz)
            .setLineWidth(thickness)

        buffer.addVertex(pose, sx + nx, sy + ny, sz + nz)
            .setColor(endColor)
            .setNormal(pose, nx, ny, nz)
            .setLineWidth(thickness)
    }
}
