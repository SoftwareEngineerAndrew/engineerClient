// Adapted from Odin (https://github.com/odtheking/OdinFabric), Copyright (c) 2025, odtheking. BSD 3-Clause License: see licenses/Odin-LICENSE.txt.
package com.engineerclient.devices

import com.engineerclient.EngineerClient.mc
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.locale.Language
import net.minecraft.network.chat.FormattedText
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes

/**
 * The device solvers' world drawing, on vanilla's render types (Odin's render utils, without its own pipelines):
 * boxes filled, outlined or both, and text that faces you. Everything is depth tested.
 */
object DeviceRender {

    enum class BoxStyle { FILLED, OUTLINE, FILLED_OUTLINE }

    /** [aabb] in [argb], as [style] draws it (filled and outlined: the fill at half the colour's alpha). */
    fun box(ctx: LevelRenderContext, aabb: AABB, argb: Int, style: BoxStyle) {
        when (style) {
            BoxStyle.FILLED -> filled(ctx, aabb, argb)
            BoxStyle.OUTLINE -> outline(ctx, aabb, argb)
            BoxStyle.FILLED_OUTLINE -> {
                filled(ctx, aabb.inflate(0.00005), halfAlpha(argb))
                outline(ctx, aabb, argb)
            }
        }
    }

    fun filled(ctx: LevelRenderContext, aabb: AABB, argb: Int) {
        val cam = mc.gameRenderer.mainCamera().position()
        val pose = ctx.poseStack()
        pose.pushPose()
        pose.translate(aabb.minX - cam.x, aabb.minY - cam.y, aabb.minZ - cam.z)
        val x = aabb.xsize.toFloat(); val y = aabb.ysize.toFloat(); val z = aabb.zsize.toFloat()
        ctx.submitNodeCollector().submitCustomGeometry(pose, RenderTypes.debugQuads()) { last, buffer ->
            fun quad(vararg v: Float) { for (i in v.indices step 3) buffer.addVertex(last, v[i], v[i + 1], v[i + 2]).setColor(argb) }
            quad(0f, 0f, 0f, x, 0f, 0f, x, 0f, z, 0f, 0f, z) // bottom
            quad(0f, y, 0f, 0f, y, z, x, y, z, x, y, 0f) // top
            quad(0f, 0f, 0f, 0f, y, 0f, x, y, 0f, x, 0f, 0f) // north
            quad(0f, 0f, z, x, 0f, z, x, y, z, 0f, y, z) // south
            quad(0f, 0f, 0f, 0f, 0f, z, 0f, y, z, 0f, y, 0f) // west
            quad(x, 0f, 0f, x, y, 0f, x, y, z, x, 0f, z) // east
        }
        pose.popPose()
    }

    fun outline(ctx: LevelRenderContext, aabb: AABB, argb: Int) {
        val cam = mc.gameRenderer.mainCamera().position()
        val pose = ctx.poseStack()
        pose.pushPose()
        pose.translate(aabb.minX - cam.x, aabb.minY - cam.y, aabb.minZ - cam.z)
        val shape = Shapes.box(0.0, 0.0, 0.0, aabb.xsize, aabb.ysize, aabb.zsize)
        val width = mc.gameRenderer.gameRenderState().windowRenderState.appropriateLineWidth
        ctx.submitNodeCollector().submitShapeOutline(pose, shape, RenderTypes.linesTranslucent(), argb, width, false)
        pose.popPose()
    }

    /** [text] (with § colours) centred on [pos], turned to the camera; [scale] 1 is Odin's size. */
    fun text(ctx: LevelRenderContext, text: String, pos: Vec3, scale: Float = 1f) {
        val f = scale * 0.025f
        val line = Language.getInstance().getVisualOrder(FormattedText.of(text))
        val camera = mc.gameRenderer.mainCamera()
        val pose = ctx.poseStack()
        pose.pushPose()
        pose.last().pose()
            .translate(pos.toVector3f())
            .translate(camera.position().toVector3f().negate())
            .rotate(camera.rotation())
            .scale(f, -f, f)
        ctx.submitNodeCollector().submitText(pose, -mc.font.width(text) / 2f, 0f, line, true, Font.DisplayMode.POLYGON_OFFSET, LightCoordsUtil.FULL_BRIGHT, -1, 0, 0)
        pose.popPose()
    }

    private fun halfAlpha(argb: Int): Int = (((argb ushr 24) / 2) shl 24) or (argb and 0xFFFFFF)
}
