package com.adm.utils.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.feature.FeatureFrameContext
import net.minecraft.client.renderer.feature.FeatureRendererType
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer
import net.minecraft.client.renderer.feature.submit.TranslucentSubmit
import net.minecraft.client.renderer.rendertype.RenderType
import java.util.function.Function

fun interface MultiTypeRenderer {
    fun render(pose: PoseStack.Pose, buffers: Function<RenderType, VertexConsumer>)
}

class NoDepthSubmit private constructor(
    val pose: PoseStack.Pose,
    val renderType: RenderType?,
    val renderer: SubmitNodeCollector.CustomGeometryRenderer?,
    val multiRenderer: MultiTypeRenderer?
) : TranslucentSubmit {
    constructor(pose: PoseStack.Pose, renderType: RenderType, renderer: SubmitNodeCollector.CustomGeometryRenderer) : this(pose, renderType, renderer, null)
    constructor(pose: PoseStack.Pose, renderer: MultiTypeRenderer) : this(pose, null, null, renderer)

    override fun distanceToCameraSq(): Float = Float.MAX_VALUE
    override fun featureType(): FeatureRendererType<NoDepthSubmit> = NoDepthFeatureRenderer.TYPE
}

class NoDepthFeatureRenderer : RenderTypeFeatureRenderer<NoDepthSubmit>() {
    private val buffers = Function<RenderType, VertexConsumer> { getVertexBuilder(it) }

    override fun buildGroup(context: FeatureFrameContext, submits: List<NoDepthSubmit>) {
        for (submit in submits) {
            val multi = submit.multiRenderer
            if (multi != null) multi.render(submit.pose, buffers)
            else submit.renderer!!.render(submit.pose, getVertexBuilder(submit.renderType!!))
        }
    }

    companion object {
        val TYPE: FeatureRendererType<NoDepthSubmit> = FeatureRendererType.create("core_no_depth")
    }
}
