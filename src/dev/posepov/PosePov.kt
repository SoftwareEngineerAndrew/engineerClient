package com.devgineerclient.posepov

import com.devgineerclient.DevgineerClient
import com.mojang.blaze3d.ProjectionType
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.commands.RenderPassDescriptor
import com.mojang.renderpearl.api.pipeline.CompareOp
import com.mojang.renderpearl.api.pipeline.DepthStencilState
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.textures.FilterMode
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.ScreenEvent
import com.odtheking.odin.events.core.EventPriority
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.dungeon.LeapMenu
import com.odtheking.odin.utils.equalsOneOf
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.renderer.Projection
import net.minecraft.client.renderer.ProjectionMatrixBuffer
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.state.gui.BlitRenderState
import net.minecraft.core.SectionPos
import net.minecraft.util.Mth
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.joml.FrustumIntersection
import org.joml.Matrix3x2f
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector4f
import java.util.Optional
import java.util.OptionalDouble

/**
 * Each teammate's view in their quarter of Odin's leap menu, from nothing but where they stand,
 * where they look and what they hold: the blocks are our own flat-coloured meshes ([BlockMesh]),
 * drawn from their eye. No entities, no world render.
 */
object PosePov : Module(
    name = "Pose POV",
    category = Category.custom("Devgineer Client", 860, 10),
    description = "Minimal teammate views in the leap menu: their position, look and held item over flat-coloured blocks.",
    key = null,
) {
    private const val FEEDS = 4
    private const val NEAR = 0.05f
    private const val FAR = 96f
    private const val RADIUS_XZ = 5
    private const val RADIUS_Y = 3
    private const val SKY = 0xFF78A7FF.toInt()

    private val PIPELINE: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("devgineerclient", "pipeline/pose_pov"))
            .withCull(false)
            // Reversed Z: depth is cleared to 0 and nearer is greater.
            .withDepthStencilState(DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
            .build()
    )

    private val feeds = arrayOfNulls<TextureTarget>(FEEDS)
    private val projection = Projection()
    private val projectionBuffer by lazy { ProjectionMatrixBuffer("devgineerclient pose pov") }
    private val view = Matrix4f()
    private val cull = Matrix4f()
    private val frustum = FrustumIntersection()
    private val skyColor = Vector4f(((SKY shr 16) and 255) / 255f, ((SKY shr 8) and 255) / 255f, (SKY and 255) / 255f, 1f)

    init {
        on<ScreenEvent.Render>(EventPriority.HIGHEST + 100) {
            DevgineerClient.safely("pose pov") { if (wants()) draw(guiGraphics) }
        }
        ClientTickEvents.END_CLIENT_TICK.register {
            DevgineerClient.safely("pose pov release") { if (!wants()) releaseFeeds() }
        }
        onReceive<ClientboundBlockUpdatePacket> { BlockMesh.dirty(pos) }
        onReceive<ClientboundSectionBlocksUpdatePacket> { runUpdates { pos, _ -> BlockMesh.dirty(pos) } }
        onReceive<ClientboundLevelChunkWithLightPacket> {
            val level = mc.level ?: return@onReceive
            BlockMesh.dirtyColumn(x, z, level.minSectionY, level.maxSectionY)
        }
        onReceive<ClientboundForgetLevelChunkPacket> { BlockMesh.forget(pos.minBlockX shr 4, pos.minBlockZ shr 4) }
        on<LevelEvent.Load> { BlockMesh.clear() }
    }

    override fun onDisable() {
        super.onDisable()
        releaseFeeds()
        BlockMesh.clear()
    }

    private fun wants(): Boolean {
        if (!enabled || !LeapMenu.enabled) return false
        val screen = mc.gui.screen() as? AbstractContainerScreen<*> ?: return false
        return screen.title.string.equalsOneOf("Spirit Leap", "Teleport to Player")
    }

    private fun releaseFeeds() {
        for (i in 0 until FEEDS) { feeds[i]?.destroyBuffers(); feeds[i] = null }
    }

    private fun draw(gfx: GuiGraphicsExtractor) {
        val level = mc.level ?: return
        val pt = mc.deltaTracker.getGameTimeDeltaPartialTick(false)
        val w = (mc.window.width / 2).coerceAtLeast(1)
        val h = (mc.window.height / 2).coerceAtLeast(1)
        val gw = gfx.guiWidth(); val gh = gfx.guiHeight()
        val pose = Matrix3x2f(gfx.pose())
        val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)

        val players = ArrayList<Player?>(FEEDS)
        for (i in 0 until FEEDS) {
            val slot = DungeonUtils.leapTeammates.getOrNull(i)?.takeIf { it.name != "Empty" }
            players += slot?.let { s -> (s.entity?.takeIf { !it.isRemoved } ?: level.players().firstOrNull { it.name.string == s.name }) }
        }

        // The sections around every eye, nearest first, built (within budget) before anything is drawn.
        val wanted = LinkedHashSet<Long>()
        players.filterNotNull().sortedBy { it.distanceToSqr(mc.player ?: it) }.forEach { p ->
            val e = p.getEyePosition(pt)
            val cx = SectionPos.blockToSectionCoord(Mth.floor(e.x)); val cy = SectionPos.blockToSectionCoord(Mth.floor(e.y)); val cz = SectionPos.blockToSectionCoord(Mth.floor(e.z))
            val around = ArrayList<Long>()
            for (dx in -RADIUS_XZ..RADIUS_XZ) for (dy in -RADIUS_Y..RADIUS_Y) for (dz in -RADIUS_XZ..RADIUS_XZ) around += SectionPos.asLong(cx + dx, cy + dy, cz + dz)
            around.sortBy { k -> val a = SectionPos.x(k) - cx; val b = SectionPos.y(k) - cy; val c = SectionPos.z(k) - cz; a * a + b * b + c * c }
            wanted += around
        }
        BlockMesh.update(level, wanted.toList())

        for (i in 0 until FEEDS) {
            val col = i % 2; val row = i / 2
            val x0 = if (col == 0) 0 else gw / 2; val x1 = if (col == 0) gw / 2 else gw
            val y0 = if (row == 0) 0 else gh / 2; val y1 = if (row == 0) gh / 2 else gh
            val p = players[i]
            if (p == null) { gfx.fill(x0, y0, x1, y1, 0xFF101010.toInt()); continue }
            val target = feeds[i]?.takeIf { it.width == w && it.height == h } ?: run {
                feeds[i]?.destroyBuffers()
                TextureTarget("dc:pose_pov$i", w, h, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT).also { feeds[i] = it }
            }
            renderFeed(target, p.getEyePosition(pt), p.getViewYRot(pt), p.getViewXRot(pt), w, h)
            val colorView = target.colorTextureView ?: continue
            gfx.guiRenderState.addGuiElement(
                BlitRenderState(
                    RenderPipelines.GUI_OPAQUE_TEXTURED_BACKGROUND,
                    TextureSetup.singleTexture(colorView, sampler),
                    pose,
                    x0, y0, x1, y1,
                    // A render target's row 0 is the bottom of its image.
                    0f, 1f, 1f, 0f,
                    -1,
                    null,
                )
            )
            val held = p.mainHandItem
            if (!held.isEmpty) gfx.item(held, x0 + 4, y1 - 20)
        }
    }

    private fun renderFeed(target: TextureTarget, eye: Vec3, yaw: Float, pitch: Float, w: Int, h: Int) {
        val colorView = target.colorTextureView ?: return
        val depthView = target.depthTextureView ?: return
        val fov = mc.options.fov().get().toFloat()

        view.rotation(Quaternionf().rotationYXZ(Math.PI.toFloat() - Math.toRadians(yaw.toDouble()).toFloat(), -Math.toRadians(pitch.toDouble()).toFloat(), 0f).conjugate())
            .translate(-eye.x.toFloat(), -eye.y.toFloat(), -eye.z.toFloat())
        cull.setPerspective(Math.toRadians(fov.toDouble()).toFloat(), w.toFloat() / h, NEAR, FAR).mul(view)
        frustum.set(cull)

        val draws = ArrayList<BlockMesh.Section>()
        var maxIndices = 0
        val cx = SectionPos.blockToSectionCoord(Mth.floor(eye.x)); val cy = SectionPos.blockToSectionCoord(Mth.floor(eye.y)); val cz = SectionPos.blockToSectionCoord(Mth.floor(eye.z))
        for (dx in -RADIUS_XZ..RADIUS_XZ) for (dy in -RADIUS_Y..RADIUS_Y) for (dz in -RADIUS_XZ..RADIUS_XZ) {
            val sx = cx + dx; val sy = cy + dy; val sz = cz + dz
            val s = BlockMesh.get(SectionPos.asLong(sx, sy, sz)) ?: continue
            if (s.buffer == null || s.indexCount == 0) continue
            if (!frustum.testAab(sx * 16f, sy * 16f, sz * 16f, sx * 16f + 16f, sy * 16f + 16f, sz * 16f + 16f)) continue
            draws += s
            if (s.indexCount > maxIndices) maxIndices = s.indexCount
        }

        projection.setupPerspective(NEAR, FAR, fov, w.toFloat(), h.toFloat())
        RenderSystem.backupProjectionMatrix()
        try {
            RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(projection), ProjectionType.PERSPECTIVE)
            // A copy: the uniform storage keeps the matrix by reference and skips a write equal to the last one, so
            // handing it the reused [view] made every feed match the previous write and draw with a stale slice.
            val transforms = RenderSystem.getDynamicUniforms().writeTransform(Matrix4f(view))
            val quadIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS)
            val indices = if (maxIndices > 0) quadIndices.getBuffer(maxIndices) else null
            RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                RenderPassDescriptor.builder { "devgineerclient pose pov" }
                    .withColorAttachment(colorView, Optional.of(skyColor))
                    .withDepthAttachment(depthView, OptionalDouble.of(0.0))
                    .build()
            ).use { pass ->
                if (indices == null) return@use
                RenderSystem.bindDefaultUniforms(pass)
                pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE))
                pass.setUniform("DynamicTransforms", transforms)
                pass.setIndexBuffer(indices, quadIndices.type())
                for (s in draws) {
                    pass.setVertexBuffer(0, s.buffer!!.slice())
                    pass.drawIndexed(s.indexCount, 1, 0, 0, 0)
                }
            }
        } finally {
            RenderSystem.restoreProjectionMatrix()
        }
    }
}
