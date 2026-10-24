package com.devgineerclient.posepov

import com.devgineerclient.DevgineerClient
import com.mojang.blaze3d.ProjectionType
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.commands.RenderPassDescriptor
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.CompareOp
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.client.renderer.texture.TextureAtlas
import com.mojang.renderpearl.api.pipeline.DepthStencilState
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.textures.FilterMode
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
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
    private const val FAR = 128f
    private const val DRAW_BUDGET_NS = 4_000_000L
    private const val PRERENDER_BUDGET_NS = 3_000_000L
    private const val RADIUS_XZ = 5
    private const val RADIUS_Y = 3
    private const val SKY = 0xFF78A7FF.toInt()

    private val PIPELINE: RenderPipeline = RenderPipelines.register(
        // The block atlas, textured as vanilla's position_tex_color draws it (transparent texels discarded: glass, leaves).
        RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("devgineerclient", "pipeline/pose_pov"))
            .withVertexShader("core/position_tex_color")
            .withFragmentShader("core/position_tex_color")
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withCull(false)
            // Reversed Z: depth is cleared to 0 and nearer is greater.
            .withDepthStencilState(DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
            .build()
    )

    private val prerender by BooleanSetting("Prerender", true, desc = "Builds the whole dungeon and the boss arena in the background while you're in a dungeon, so the leap menu only has to draw. Room geometry is kept across runs either way.")

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
            DevgineerClient.safely("pose pov prerender") { if (enabled && prerender && DungeonUtils.inDungeons && !wants()) build(PRERENDER_BUDGET_NS, emptyList()) }
        }
        // This run's block changes, on top of the known rooms and arena (see KnownWorld).
        onReceive<ClientboundBlockUpdatePacket> { KnownWorld.change(pos, blockState) }
        onReceive<ClientboundSectionBlocksUpdatePacket> { runUpdates { pos, state -> KnownWorld.change(pos, state) } }
        onReceive<ClientboundLevelChunkWithLightPacket> {
            val level = mc.level ?: return@onReceive
            BlockMesh.dirtyColumn(x, z, level.minSectionY, level.maxSectionY)
        }
        // An unloaded chunk falls back to what is known about it (or nothing).
        onReceive<ClientboundForgetLevelChunkPacket> {
            val level = mc.level ?: return@onReceive
            BlockMesh.dirtyColumn(pos.minBlockX shr 4, pos.minBlockZ shr 4, level.minSectionY, level.maxSectionY)
        }
        on<LevelEvent.Load> { KnownWorld.reset(); BlockMesh.newRun() }
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
        queued.clear()
        for (i in 0 until FEEDS) { feeds[i]?.destroyBuffers(); feeds[i] = null }
    }

    private fun draw(gfx: GuiGraphicsExtractor) {
        val level = mc.level ?: return
        queued.clear()
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
        build(DRAW_BUDGET_NS, wanted.toList())

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
            val eye = p.getEyePosition(pt); val yaw = p.getViewYRot(pt); val pitch = p.getViewXRot(pt)
            queued += Job(target, eye, yaw, pitch, w, h)
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

    /** Places what Odin has newly identified, then builds geometry for [budgetNs]: [near] first, then the whole dungeon if prerendering. */
    private fun build(budgetNs: Long, near: List<Long>) {
        val level = mc.level ?: return
        // On arrival the world sections are built again with the rooms and the arena in them.
        KnownWorld.load { mc.execute { BlockMesh.newRun(); KnownWorld.requeueChanges() } }
        KnownWorld.refresh(level) { r ->
            // The world sections under a room just placed lose those cells to the room's own pieces.
            for (cx in (r.x0 shr 4)..(r.x1 shr 4)) for (cz in (r.z0 shr 4)..(r.z1 shr 4)) BlockMesh.dirtyColumn(cx, cz, level.minSectionY, level.maxSectionY)
        }
        BlockMesh.pump(level, budgetNs, near, prerender && DungeonUtils.inDungeons)
    }

    private class Job(val target: TextureTarget, val eye: Vec3, val yaw: Float, val pitch: Float, val w: Int, val h: Int)

    /** The feeds the menu asked for this frame: poses read while the screen is extracted, drawn in [renderQueued]. */
    private val queued = ArrayList<Job>()

    /**
     * Draws this frame's feeds, from the end of the world render (PosePovRenderMixin): before the GUI that shows them, and in the
     * render phase where the game's own passes run, not while the screen is being extracted.
     */
    fun renderQueued() {
        if (queued.isEmpty()) return
        DevgineerClient.safely("pose pov render") {
            // One index buffer for every feed, asked for once at the largest size: growing it between feeds would replace it
            // under the passes already recorded.
            var most = 0
            BlockMesh.forEachDrawable { m, _, _, _, _, _ -> if (m.indexCount > most) most = m.indexCount }
            val quadIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS)
            val indices = if (most > 0) quadIndices.getBuffer(most) else null
            for (job in queued) renderFeed(job.target, job.eye, job.yaw, job.pitch, job.w, job.h, quadIndices, indices)
        }
        queued.clear()
    }

    private fun renderFeed(target: TextureTarget, eye: Vec3, yaw: Float, pitch: Float, w: Int, h: Int, quadIndices: RenderSystem.AutoStorageIndexBuffer, indices: com.mojang.renderpearl.api.buffers.GpuBuffer?) {
        val colorView = target.colorTextureView ?: return
        val depthView = target.depthTextureView ?: return
        val fov = mc.options.fov().get().toFloat()

        view.rotation(Quaternionf().rotationYXZ(Math.PI.toFloat() - Math.toRadians(yaw.toDouble()).toFloat(), -Math.toRadians(pitch.toDouble()).toFloat(), 0f).conjugate())
            .translate(-eye.x.toFloat(), -eye.y.toFloat(), -eye.z.toFloat())
        cull.setPerspective(Math.toRadians(fov.toDouble()).toFloat(), w.toFloat() / h, NEAR, FAR).mul(view)
        frustum.set(cull)

        // Every piece in range and in view, grouped by where it is drawn (a room's pieces are in its own box frame).
        val draws = HashMap<Long, MutableList<BlockMesh.Mesh>>()
        val far2 = (FAR + 28.0) * (FAR + 28.0)
        BlockMesh.forEachDrawable { m, x, y, z, ox, oz ->
            val dx = x + 8 - eye.x; val dy = y + 8 - eye.y; val dz = z + 8 - eye.z
            if (dx * dx + dy * dy + dz * dz > far2) return@forEachDrawable
            if (!frustum.testAab(x.toFloat(), y.toFloat(), z.toFloat(), x + 16f, y + 16f, z + 16f)) return@forEachDrawable
            draws.getOrPut((ox.toLong() shl 32) or (oz.toLong() and 0xFFFFFFFFL)) { ArrayList() } += m
        }

        projection.setupPerspective(NEAR, FAR, fov, w.toFloat(), h.toFloat())
        RenderSystem.backupProjectionMatrix()
        try {
            RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(projection), ProjectionType.PERSPECTIVE)
            // Fresh matrices: the uniform storage keeps the matrix by reference and skips a write equal to the last one, so
            // handing it the reused [view] made every feed match the previous write and draw with a stale slice.
            val transforms = draws.keys.associateWith { k ->
                RenderSystem.getDynamicUniforms().writeTransform(Matrix4f(view).translate((k shr 32).toInt().toFloat(), 0f, k.toInt().toFloat()))
            }
            RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                RenderPassDescriptor.builder { "devgineerclient pose pov" }
                    .withColorAttachment(colorView, Optional.of(skyColor))
                    .withDepthAttachment(depthView, OptionalDouble.of(0.0))
                    .build()
            ).use { pass ->
                if (indices == null) return@use
                RenderSystem.bindDefaultUniforms(pass)
                pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE))
                val atlas = mc.textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS)
                pass.setUniform("Sampler0", atlas.textureView, atlas.sampler)
                pass.setIndexBuffer(indices, quadIndices.type())
                for ((k, meshes) in draws) {
                    pass.setUniform("DynamicTransforms", transforms.getValue(k))
                    for (m in meshes) {
                        pass.setVertexBuffer(0, m.buffer.slice())
                        pass.drawIndexed(m.indexCount, 1, 0, 0, 0)
                    }
                }
            }
        } finally {
            RenderSystem.restoreProjectionMatrix()
        }
    }
}
