package com.devgineerclient.posepov

import com.devgineerclient.DevgineerClient
import com.mojang.blaze3d.ProjectionType
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
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
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.equalsOneOf
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.renderer.Projection
import net.minecraft.client.renderer.ProjectionMatrixBuffer
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.SubmitNodeStorage
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.item.ItemDisplayContext
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
    private const val FLOOR_LOW = 55
    private const val BACK_OFF = 3.5
    private const val BACK_UP = 1.2
    private const val BLEND_NS = 350_000_000L
    private const val FLOOR_HIGH = 140
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

        // Each slot's eye: the teammate's own when they're loaded, otherwise a camera behind where their map icon puts them.
        val now = System.nanoTime()
        val eyes = arrayOfNulls<Eye>(FEEDS)
        val players = arrayOfNulls<Player>(FEEDS)
        for (i in 0 until FEEDS) {
            val slot = DungeonUtils.leapTeammates.getOrNull(i)?.takeIf { it.name != "Empty" } ?: continue
            val p = slot.entity?.takeIf { !it.isRemoved } ?: level.players().firstOrNull { it.name.string == slot.name }
            val e = if (p != null) {
                players[i] = p
                // Head yaw through the shortest turn: a plain lerp across the ±180 wrap swings the view through every angle
                // between the two, a different one each frame.
                val real = Eye(p.getEyePosition(pt), Mth.rotLerp(pt, p.yHeadRotO, p.yHeadRot), p.getViewXRot(pt), null)
                ghosts.getOrPut(slot.name) { Ghost() }.seen(real, now)
                real
            } else if (!slot.isDead && slot.mapPos.data != 0L) ghostEye(level, slot, now) else null
            if (e != null) eyes[i] = ghosts.getOrPut(slot.name) { Ghost() }.blended(e, now)
        }

        // The sections around every eye, nearest first, built (within budget) before anything is drawn.
        val wanted = LinkedHashSet<Long>()
        val me = mc.player?.position()
        eyes.filterNotNull().sortedBy { if (me == null) 0.0 else it.pos.distanceToSqr(me) }.forEach { e ->
            val cx = SectionPos.blockToSectionCoord(Mth.floor(e.pos.x)); val cy = SectionPos.blockToSectionCoord(Mth.floor(e.pos.y)); val cz = SectionPos.blockToSectionCoord(Mth.floor(e.pos.z))
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
            val slot = DungeonUtils.leapTeammates.getOrNull(i)?.takeIf { it.name != "Empty" }
            val e = eyes[i]
            if (e == null) {
                gfx.fill(x0, y0, x1, y1, 0xFF101010.toInt())
                if (slot != null) {
                    val cx = (x0 + x1) / 2; val cy = (y0 + y1) / 2
                    gfx.text(slot.name, cx - mc.font.width(slot.name) / 2, cy - 10, Color(170, 170, 170), shadow = true)
                    val label = if (slot.isDead) "dead" else "not on the map yet"
                    gfx.text(label, cx - mc.font.width(label) / 2, cy + 2, Color(120, 120, 120), shadow = true)
                }
                continue
            }
            val target = feeds[i]?.takeIf { it.width == w && it.height == h } ?: run {
                feeds[i]?.destroyBuffers()
                TextureTarget("dc:pose_pov$i", w, h, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT).also { feeds[i] = it }
            }
            val p = players[i]
            val item = handItems[i].also { it.clear() }
            val right = p?.mainArm != HumanoidArm.LEFT
            if (p != null && !p.mainHandItem.isEmpty) mc.itemModelResolver.updateForLiving(item, p.mainHandItem, if (right) ItemDisplayContext.FIRST_PERSON_RIGHT_HAND else ItemDisplayContext.FIRST_PERSON_LEFT_HAND, p)
            queued += Job(target, e.pos, e.yaw, e.pitch, w, h, item, if (right) 1f else -1f, p?.getSwingAnimation(pt) ?: 0f)
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
            val subject = e.subject
            if (subject != null && slot != null) {
                // Their face where the estimate puts them, then how far that is.
                project(e, subject, w, h, x0, y0, x1, y1)?.let { (fx, fy) ->
                    slot.playerSkin?.let { PlayerFaceExtractor.extractRenderState(gfx, it, fx - 6, fy - 6, 12) } ?: gfx.fill(fx - 3, fy - 3, fx + 3, fy + 3, -1)
                }
                distanceLabel(gfx, subject, x0, y0, x1, y1)
            }
        }
    }

    /** A camera: where it is and looks, and for an estimate, the spot the teammate is thought to stand (eye height). */
    private class Eye(val pos: Vec3, val yaw: Float, val pitch: Float, val subject: Vec3?)

    /**
     * What's known of one teammate across frames: the estimate eased towards their map icon (position, heading, floor and
     * camera distance), how the icon has been moving, and the last camera drawn, so switching between their own view and
     * the estimate blends instead of cutting.
     */
    private class Ghost {
        var x = Double.NaN; var z = Double.NaN; var eyeY = Double.NaN; var yaw = 0f; var pitch = 0f
        var camDist = BACK_OFF; var at = 0L
        var iconX = Double.NaN; var iconZ = Double.NaN; var iconAt = 0L; var vx = 0.0; var vz = 0.0
        var last: Eye? = null; var lastAt = 0L; var from: Eye? = null; var fromAt = 0L

        /** Their own view this frame: the estimate picks up from here when they go out of range. */
        fun seen(e: Eye, now: Long) {
            x = e.pos.x; z = e.pos.z; eyeY = e.pos.y; yaw = e.yaw; pitch = e.pitch; at = now
            iconX = Double.NaN; vx = 0.0; vz = 0.0; camDist = 0.0
        }

        /** [e], or on its way to [e] from the other kind of camera for [BLEND_NS] after a switch. */
        fun blended(e: Eye, now: Long): Eye {
            // Only from a camera drawn just now: reopening the menu starts clean.
            val prev = last?.takeIf { now - lastAt < 200_000_000L }
            if (prev != null && (prev.subject == null) != (e.subject == null)) { from = prev; fromAt = now }
            val f = from
            val out = if (f == null || now - fromAt >= BLEND_NS) e.also { from = null } else {
                val t = (now - fromAt).toDouble() / BLEND_NS; val s = t * t * (3 - 2 * t)
                Eye(f.pos.lerp(e.pos, s), Mth.rotLerp(s.toFloat(), f.yaw, e.yaw), Mth.lerp(s.toFloat(), f.pitch, e.pitch), e.subject)
            }
            last = out; lastAt = now
            return out
        }
    }
    private val ghosts = HashMap<String, Ghost>()

    /**
     * An out-of-range teammate from their map icon, seen from behind and above. The icon moves in steps of about a block and
     * turns in 22.5° steps, so the spot eases towards it, and while it's moving the heading follows the way it goes rather
     * than the arrow. They stand on the floor under that spot in the known blocks (the level nearest where they were last
     * seen); the camera backs off along their heading only as far as the blocks allow, and looks down at them.
     */
    private fun ghostEye(level: net.minecraft.client.multiplayer.ClientLevel, slot: DungeonPlayer, now: Long): Eye {
        val tx = ((slot.mapPos.x + 128) / 2.0 - DungeonScan.startX) * 32.0 / DungeonScan.roomGap - 200
        val tz = ((slot.mapPos.z + 128) / 2.0 - DungeonScan.startY) * 32.0 / DungeonScan.roomGap - 200
        val g = ghosts.getOrPut(slot.name) { Ghost() }
        // First sight, or a leap / teleport: no gliding across the map.
        if (g.x.isNaN() || Math.hypot(tx - g.x, tz - g.z) > 24.0) { g.x = tx; g.z = tz; g.yaw = slot.yaw; g.iconX = Double.NaN; g.at = now }
        val dt = ((now - g.at) / 1e9).coerceIn(0.0, 0.25); g.at = now

        // The icon's velocity, from its steps; still after a while without one.
        if (g.iconX.isNaN()) { g.iconX = tx; g.iconZ = tz; g.iconAt = now; g.vx = 0.0; g.vz = 0.0 }
        else if (tx != g.iconX || tz != g.iconZ) {
            val sec = ((now - g.iconAt) / 1e9).coerceAtLeast(0.05)
            g.vx = (tx - g.iconX) / sec; g.vz = (tz - g.iconZ) / sec
            g.iconX = tx; g.iconZ = tz; g.iconAt = now
        } else if (now - g.iconAt > 700_000_000L) { g.vx = 0.0; g.vz = 0.0 }
        val moving = Math.hypot(g.vx, g.vz) > 1.5
        val heading = if (moving) Math.toDegrees(Math.atan2(-g.vx, g.vz)).toFloat() else slot.yaw

        val k = 1 - Math.exp(-dt * 6)
        g.x += (tx - g.x) * k; g.z += (tz - g.z) * k
        g.yaw += Mth.wrapDegrees(heading - g.yaw) * (1 - Math.exp(-dt * 4)).toFloat()
        val feet = floorAt(level, g.x, g.z, g.eyeY)
        g.eyeY = if (g.eyeY.isNaN() || Math.abs(feet + 1.62 - g.eyeY) > 6) feet + 1.62 else g.eyeY + (feet + 1.62 - g.eyeY) * (1 - Math.exp(-dt * 10))
        val subject = Vec3(g.x, g.eyeY, g.z)

        // Back along the heading and up, stopped short of the first block in the way; eased so walls don't pop it in and out.
        val yr = Math.toRadians(g.yaw.toDouble())
        val back = Vec3(Math.sin(yr) * BACK_OFF, BACK_UP, -Math.cos(yr) * BACK_OFF)
        val room = clearance(level, subject, back)
        g.camDist += (room - g.camDist) * (if (room < g.camDist) 1.0 else 1 - Math.exp(-dt * 3))
        val cam = subject.add(back.scale(g.camDist / BACK_OFF))
        val look = subject.add(0.0, -0.4, 0.0).subtract(cam)
        val pitch = Math.toDegrees(Math.atan2(-look.y, Math.hypot(look.x, look.z))).toFloat()
        return Eye(cam, g.yaw, pitch, subject)
    }

    /** How far (in units of [BACK_OFF]) the camera can go from [from] along [dir] before a block, less a margin. */
    private fun clearance(level: net.minecraft.client.multiplayer.ClientLevel, from: Vec3, dir: Vec3): Double {
        val pos = net.minecraft.core.BlockPos.MutableBlockPos()
        val len = dir.length(); val steps = (len / 0.2).toInt()
        for (s in 1..steps) {
            val p = from.add(dir.scale(s / steps.toDouble()))
            if (KnownWorld.stateAt(level, pos, Mth.floor(p.x), Mth.floor(p.y), Mth.floor(p.z)).isSolid)
                return (BACK_OFF * (s - 1) / steps - 0.3).coerceAtLeast(0.0)
        }
        return BACK_OFF
    }

    /** Where a world point lands in a feed's panel, or null when it's behind the camera or off the panel. */
    private fun project(e: Eye, at: Vec3, w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int): Pair<Int, Int>? {
        val m = Matrix4f().setPerspective(Math.toRadians(mc.options.fov().get().toDouble()).toFloat(), w.toFloat() / h, NEAR, FAR)
            .rotate(Quaternionf().rotationYXZ(Math.PI.toFloat() - Math.toRadians(e.yaw.toDouble()).toFloat(), -Math.toRadians(e.pitch.toDouble()).toFloat(), 0f).conjugate())
            .translate(-e.pos.x.toFloat(), -e.pos.y.toFloat(), -e.pos.z.toFloat())
        val v = Vector4f(at.x.toFloat(), at.y.toFloat(), at.z.toFloat(), 1f).mul(m)
        if (v.w <= 0.01f) return null
        val nx = v.x / v.w; val ny = v.y / v.w
        if (nx !in -1f..1f || ny !in -1f..1f) return null
        return (x0 + (nx * 0.5f + 0.5f) * (x1 - x0)).toInt() to (y0 + (0.5f - ny * 0.5f) * (y1 - y0)).toInt()
    }

    /**
     * The floor to stand on near (x, z): the nearest column within two blocks with room for a player, at the standing
     * height closest to [eyeY] (the lowest when unknown). Falls back to the usual dungeon floor.
     */
    private fun floorAt(level: net.minecraft.client.multiplayer.ClientLevel, x: Double, z: Double, eyeY: Double): Double {
        val pos = net.minecraft.core.BlockPos.MutableBlockPos()
        val bx = Mth.floor(x); val bz = Mth.floor(z)
        for (r in 0..2) {
            var best = Double.NaN
            for (dx in -r..r) for (dz in -r..r) {
                if (maxOf(Math.abs(dx), Math.abs(dz)) != r) continue
                var below = KnownWorld.stateAt(level, pos, bx + dx, FLOOR_LOW - 1, bz + dz).isSolid
                var at = KnownWorld.stateAt(level, pos, bx + dx, FLOOR_LOW, bz + dz).isSolid
                for (y in FLOOR_LOW..FLOOR_HIGH) {
                    val above = KnownWorld.stateAt(level, pos, bx + dx, y + 1, bz + dz).isSolid
                    if (below && !at && !above) {
                        if (eyeY.isNaN()) { if (best.isNaN()) best = y.toDouble() }
                        else if (best.isNaN() || Math.abs(y + 1.62 - eyeY) < Math.abs(best + 1.62 - eyeY)) best = y.toDouble()
                    }
                    below = at; at = above
                }
            }
            if (!best.isNaN()) return best
        }
        return if (eyeY.isNaN()) 69.0 else eyeY - 1.62
    }

    /** The estimate's distance from you, coloured by how far, under their name. */
    private fun distanceLabel(gfx: GuiGraphicsExtractor, at: Vec3, x0: Int, y0: Int, x1: Int, y1: Int) {
        val me = mc.player ?: return
        val d = Math.hypot(at.x - me.x, at.z - me.z).toInt()
        val color = when {
            d < 48 -> Color(90, 235, 120)
            d < 80 -> Color(240, 220, 70)
            d < 112 -> Color(255, 150, 50)
            else -> Color(255, 70, 70)
        }
        val label = "~$d blocks · from map"
        val cx = (x0 + x1) / 2
        gfx.fill(cx - mc.font.width(label) / 2 - 3, y1 - 14, cx + mc.font.width(label) / 2 + 3, y1 - 2, 0x90000000.toInt())
        gfx.text(label, cx - mc.font.width(label) / 2, y1 - 12, color, shadow = true)
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

    private class Job(
        val target: TextureTarget, val eye: Vec3, val yaw: Float, val pitch: Float, val w: Int, val h: Int,
        val item: ItemStackRenderState, val side: Float, val swing: Float,
    )

    private val handItems = Array(FEEDS) { ItemStackRenderState() }
    private val handProjection = Projection()
    private val handProjectionBuffer by lazy { ProjectionMatrixBuffer("devgineerclient pose pov hand") }
    private val handSubmits = SubmitNodeStorage()

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
            for (job in queued) {
                renderFeed(job.target, job.eye, job.yaw, job.pitch, job.w, job.h, quadIndices, indices)
                renderHand(job)
            }
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

    /**
     * Their held item over the feed, where the game holds yours: the first-person arm pose (with the swing) at the hand's
     * own field of view, on a cleared depth so the world never cuts into it.
     */
    private fun sinf(x: Float) = kotlin.math.sin(x)
    private fun sqrtf(x: Float) = kotlin.math.sqrt(x)

    private fun renderHand(job: Job) {
        if (job.item.isEmpty) return
        val colorView = job.target.colorTextureView ?: return
        val depthView = job.target.depthTextureView ?: return
        val s = job.side; val a = job.swing
        val poses = PoseStack()
        poses.translate(s * -0.4f * sinf(sqrtf(a) * Mth.PI), 0.2f * sinf(sqrtf(a) * Mth.TWO_PI), -0.2f * sinf(a * Mth.PI))
        poses.translate(s * 0.56f, -0.52f, -0.72f)
        val sa = sinf(a * a * Mth.PI); val sb = sinf(sqrtf(a) * Mth.PI)
        poses.rotateDegrees(Axis.YP, s * (45f + sa * -20f))
        poses.rotateDegrees(Axis.ZP, s * sb * -20f)
        poses.rotateDegrees(Axis.XP, sb * -80f)
        poses.rotateDegrees(Axis.YP, s * -45f)
        job.item.submit(poses, handSubmits, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0)

        handProjection.setupPerspective(NEAR, FAR, 70f, job.w.toFloat(), job.h.toFloat())
        RenderSystem.backupProjectionMatrix()
        val modelView = RenderSystem.getModelViewStack()
        modelView.pushMatrix().identity()
        try {
            RenderSystem.setProjectionMatrix(handProjectionBuffer.getBuffer(handProjection), ProjectionType.PERSPECTIVE)
            mc.gameRenderer.featureRenderDispatcher().prepareFrame(handSubmits).use { frame ->
                RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    RenderPassDescriptor.builder { "devgineerclient pose pov hand" }
                        .withColorAttachment(colorView, Optional.empty())
                        .withDepthAttachment(depthView, OptionalDouble.of(0.0))
                        .build()
                ).use { pass ->
                    RenderSystem.bindDefaultUniforms(pass)
                    net.minecraft.client.renderer.feature.FeatureRenderDispatcher.renderAllFeatures(pass, frame)
                }
            }
        } finally {
            modelView.popMatrix()
            RenderSystem.restoreProjectionMatrix()
        }
    }
}
