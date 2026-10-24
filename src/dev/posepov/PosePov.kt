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
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.model.player.PlayerModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.world.entity.player.PlayerSkin
import net.minecraft.world.entity.player.PlayerModelType
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
import net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
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
 * drawn from their eye, with the entities around them. No world render.
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
    private const val LOOK_EASE_S = 0.035
    private const val ENTITY_RANGE = 64.0
    private const val SWING_NS = 300_000_000L
    private const val FACE_HIDDEN = 0.3f
    private const val TRAIL_NS = 800_000_000L
    private const val BACK_OFF = 3.5
    private const val BACK_UP = 1.2
    private const val BACK_UP_HIGH = 2.4
    private const val MIN_CAM = 2.0
    private val CAM_OFFSETS = floatArrayOf(0f, 30f, -30f, 60f, -60f, 90f, -90f, 130f, -130f, 180f)
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
        // Only when the under-GUI hook didn't draw this frame (it can't have matched): on top of the HUD then, but still there.
        on<ScreenEvent.Render>(EventPriority.HIGHEST + 100) {
            DevgineerClient.safely("pose pov") { if (!drewUnderGui && wants()) draw(guiGraphics) }
            drewUnderGui = false
        }
        ClientTickEvents.END_CLIENT_TICK.register {
            DevgineerClient.safely("pose pov release") { if (!wants()) releaseFeeds() }
            DevgineerClient.safely("pose pov look") { if (enabled && DungeonUtils.inDungeons) sampleLooks() }
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
        // Arm swings, timed from the packet: the game's own swing state sticks for some remote players, as their head yaw does.
        onReceive<ClientboundSwingAnimationPacket> { if (hand() == InteractionHand.MAIN_HAND) swings[entityId()] = System.nanoTime() }
        on<LevelEvent.Load> { KnownWorld.reset(); BlockMesh.newRun() }
    }

    override fun onDisable() {
        super.onDisable()
        releaseFeeds()
        BlockMesh.clear()
    }

    /** The feeds, under all of the frame's GUI (PosePovUnderGuiMixin): the HUD, the leap menu and every mod draw over them. */
    fun drawUnderGui(gfx: GuiGraphicsExtractor) {
        DevgineerClient.safely("pose pov") { if (wants()) { draw(gfx); drewUnderGui = true } }
    }
    private var drewUnderGui = false

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
        val bodies = ArrayList<Body>()
        for (i in 0 until FEEDS) {
            val slot = DungeonUtils.leapTeammates.getOrNull(i)?.takeIf { it.name != "Empty" } ?: continue
            val p = slot.entity?.takeIf { !it.isRemoved } ?: level.players().firstOrNull { it.name.string == slot.name }
            val e = if (p != null) {
                players[i] = p
                val real = look(slot.name, p, pt, now)
                ghosts.getOrPut(slot.name) { Ghost() }.seen(real, now)
                real
            } else if (!slot.isDead && slot.mapPos.data != 0L) ghostEye(level, slot, now) else null
            if (e != null) eyes[i] = ghosts.getOrPut(slot.name) { Ghost() }.blended(e, now)
            // An out-of-range teammate's guessed body, in every feed (their own included, seen from behind).
            val g = ghosts[slot.name]
            val guess = e?.subject
            if (p == null && guess != null && g != null) slot.playerSkin?.let { bodies += Body(guess.subtract(0.0, 1.62, 0.0), g.yaw, g.walkPos, g.walkSpeed, it) }
        }

        // Every entity near any eye, extracted once for all the feeds: full bright like the blocks, without outlines.
        val entities = ArrayList<Pair<Entity, EntityRenderState>>()
        val near = eyes.filterNotNull()
        val range2 = ENTITY_RANGE * ENTITY_RANGE
        for (en in level.entitiesForRendering()) {
            if (near.none { it.pos.distanceToSqr(en.position()) < range2 }) continue
            val s = runCatching { mc.entityRenderDispatcher.extractEntity(en, pt) }.getOrNull() ?: continue
            s.lightCoords = LightCoordsUtil.FULL_BRIGHT; s.outlineColor = 0
            entities += en to s
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
            val subject = e.subject
            val ghost = slot?.let { ghosts[it.name] }
            // Everything around them but themselves (their view is from inside their head).
            val seen = entities.filter { (en, _) -> en !== p && en.position().distanceToSqr(e.pos) < range2 }.map { it.second }
            queued += Job(target, e.pos, e.yaw, e.pitch, w, h, item, if (right) 1f else -1f, p?.let { swingOf(it.id, now) } ?: 0f, seen, bodies)
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
            if (subject != null && slot != null && ghost != null) {
                // Their face over the guessed body's head, faded while blocks are in the way, then how far that is.
                val head = subject.add(0.0, 0.75, 0.0)
                val seen = clearance(level, e.pos, head.subtract(e.pos), 0.0) >= BACK_OFF
                val dt = ((now - ghost.faceAt) / 1e9).coerceIn(0.0, 0.25); ghost.faceAt = now
                ghost.faceAlpha += ((if (seen) 1f else FACE_HIDDEN) - ghost.faceAlpha) * (1 - Math.exp(-dt * 8)).toFloat()
                val a = (ghost.faceAlpha * 255).toInt().coerceIn(0, 255)
                project(e, head, w, h, x0, y0, x1, y1)?.let { (fx, fy) ->
                    slot.playerSkin?.let { PlayerFaceExtractor.extractRenderState(gfx, it, fx - 6, fy - 6, 12, (a shl 24) or 0xFFFFFF) }
                        ?: gfx.fill(fx - 3, fy - 3, fx + 3, fy + 3, (a shl 24) or 0xFFFFFF)
                }
                distanceLabel(gfx, subject, x0, y0, x1, y1)
            }
        }
    }

    /**
     * Each teammate's head yaw and pitch at the end of the last two ticks. The game's own "previous" head yaw isn't kept
     * up for every remote player (one stayed 85° stale for several ticks), and lerping from it swung the view across that
     * gap within each tick; these are sampled here, every tick, instead.
     */
    private class Look(var yawO: Float, var yaw: Float, var pitchO: Float, var pitch: Float, var tick: Long,
                       var outYaw: Float = yaw, var outPitch: Float = pitch, var outAt: Long = 0L)
    private val looks = HashMap<String, Look>()
    private var ticks = 0L

    private fun sampleLooks() {
        val level = mc.level ?: return
        ticks++
        val names = DungeonUtils.leapTeammates.mapTo(HashSet()) { it.name }
        looks.keys.retainAll(names)
        for (p in level.players()) {
            val name = p.name.string
            if (name !in names) continue
            val l = looks[name]
            // A gap (they were out of range) starts over rather than turning across it.
            if (l == null || l.tick != ticks - 1) looks[name] = Look(p.yHeadRot, p.yHeadRot, p.xRot, p.xRot, ticks)
            else { l.yawO = l.yaw; l.yaw = p.yHeadRot; l.pitchO = l.pitch; l.pitch = p.xRot; l.tick = ticks }
        }
    }

    /**
     * A loaded teammate's eye: their position, and their look between the last two samples (through the shortest turn),
     * lightly eased so a flick over the 20 updates a second reads as a quick turn rather than a jump.
     */
    private fun look(name: String, p: Player, pt: Float, now: Long): Eye {
        val l = looks[name] ?: return Eye(p.getEyePosition(pt), p.yHeadRot, p.xRot, null)
        val yaw = Mth.rotLerp(pt, l.yawO, l.yaw); val pitch = Mth.lerp(pt, l.pitchO, l.pitch)
        val dt = ((now - l.outAt) / 1e9); l.outAt = now
        if (dt > 0.25) { l.outYaw = yaw; l.outPitch = pitch }
        else {
            val k = (1 - Math.exp(-dt / LOOK_EASE_S)).toFloat()
            l.outYaw += Mth.wrapDegrees(yaw - l.outYaw) * k; l.outPitch += (pitch - l.outPitch) * k
        }
        return Eye(p.getEyePosition(pt), l.outYaw, l.outPitch, null)
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
        var camOff = 0f; var camAim = 0f; var camUp = BACK_UP; var camChecked = 0L
        val trail = ArrayDeque<DoubleArray>(); var walkPos = 0f; var walkSpeed = 0f; var faceAlpha = 1f; var faceAt = 0L
        var last: Eye? = null; var lastAt = 0L; var from: Eye? = null; var fromAt = 0L

        /** Their own view this frame: the estimate picks up from here when they go out of range. */
        fun seen(e: Eye, now: Long) {
            x = e.pos.x; z = e.pos.z; eyeY = e.pos.y; yaw = e.yaw; pitch = e.pitch; at = now
            trail.clear(); camDist = 0.0; camOff = 0f; camAim = 0f; camUp = BACK_UP
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
        if (g.x.isNaN() || Math.hypot(tx - g.x, tz - g.z) > 24.0) { g.x = tx; g.z = tz; g.yaw = slot.yaw; g.trail.clear(); g.at = now }
        val dt = ((now - g.at) / 1e9).coerceIn(0.0, 0.25); g.at = now

        // Which way the icon is going, over the last [TRAIL_NS]: a single step on the map's grid only says x or z, so a
        // diagonal walk would swing between the two.
        if (g.trail.isEmpty() || g.trail.last()[1] != tx || g.trail.last()[2] != tz) g.trail.addLast(doubleArrayOf(now.toDouble(), tx, tz))
        while (g.trail.size > 2 && now - g.trail.first()[0] > TRAIL_NS) g.trail.removeFirst()
        val first = g.trail.first(); val last = g.trail.last()
        val fresh = now - last[0] < 700_000_000L
        val span = Math.hypot(last[1] - first[1], last[2] - first[2])
        val moving = fresh && span > 1.5 && span / ((last[0] - first[0]) / 1e9).coerceAtLeast(0.1) > 1.5
        val heading = if (moving) Math.toDegrees(Math.atan2(-(last[1] - first[1]), last[2] - first[2])).toFloat() else slot.yaw

        // Where they'd stand: the icon's spot if there's room for a player there, else the nearest column with room.
        val spot = spotAt(level, tx, tz, g.eyeY)
        val k = 1 - Math.exp(-dt * 6)
        val px = g.x; val pz = g.z
        g.x += (spot.x - g.x) * k; g.z += (spot.z - g.z) * k
        g.yaw += Mth.wrapDegrees(heading - g.yaw) * (1 - Math.exp(-dt * 3)).toFloat()
        // The guessed body's walk: the game's limb swing advances about 4 per block walked.
        val moved = Math.hypot(g.x - px, g.z - pz)
        g.walkPos += (moved * 4).toFloat()
        val speed = if (dt > 0) moved / dt else 0.0
        g.walkSpeed += ((speed / 5).coerceAtMost(1.0).toFloat() - g.walkSpeed) * (1 - Math.exp(-dt * 6)).toFloat()
        g.eyeY = if (g.eyeY.isNaN() || Math.abs(spot.y + 1.62 - g.eyeY) > 6) spot.y + 1.62 else g.eyeY + (spot.y + 1.62 - g.eyeY) * (1 - Math.exp(-dt * 10))
        // A body is 0.6 wide: kept that far off the walls beside it, so it never sinks into one.
        val feetY = Mth.floor(g.eyeY - 1.62 + 0.01)
        val cx = Mth.floor(g.x); val cz = Mth.floor(g.z)
        fun wall(x: Int, z: Int) = KnownWorld.stateAt(level, scratch, x, feetY, z).isSolid || KnownWorld.stateAt(level, scratch, x, feetY + 1, z).isSolid
        if (!wall(cx, cz)) {
            if (wall(cx - 1, cz)) g.x = maxOf(g.x, cx + 0.3); if (wall(cx + 1, cz)) g.x = minOf(g.x, cx + 0.7)
            if (wall(cx, cz - 1)) g.z = maxOf(g.z, cz + 0.3); if (wall(cx, cz + 1)) g.z = minOf(g.z, cz + 0.7)
        }
        val subject = Vec3(g.x, g.eyeY, g.z)

        // The camera: behind them if there's room, otherwise round to the side with the most (never staying squeezed
        // under [MIN_CAM] when another angle is open), raised when every angle is tight. It keeps its angle until
        // another is clearly better, swings there smoothly, and comes in at once for a wall but backs out slowly.
        fun room(off: Float, up: Double) = clearance(level, subject, backOf(g.yaw + off, up))
        val held = room(g.camOff, g.camUp)
        if (held < MIN_CAM || now - g.camChecked > 250_000_000L) {
            g.camChecked = now
            var best = g.camOff; var bestUp = g.camUp; var bestScore = held - Math.abs(Mth.wrapDegrees(g.camOff)) / 120.0
            for (up in doubleArrayOf(BACK_UP, BACK_UP_HIGH)) for (off in CAM_OFFSETS) {
                val r = room(off, up)
                val score = r - Math.abs(off) / 120.0 - (if (up > BACK_UP) 0.4 else 0.0)
                if (score > bestScore + (if (held < MIN_CAM) 0.0 else 0.75)) { best = off; bestUp = up; bestScore = score }
            }
            g.camAim = best; g.camUp = bestUp
        }
        g.camOff += Mth.wrapDegrees(g.camAim - g.camOff) * (1 - Math.exp(-dt * 4)).toFloat()
        val back = backOf(g.yaw + g.camOff, g.camUp)
        val r = clearance(level, subject, back)
        g.camDist += (r - g.camDist) * (if (r < g.camDist) 1.0 else 1 - Math.exp(-dt * 3))
        val cam = subject.add(back.scale(g.camDist / BACK_OFF))
        // Looking at their chest from wherever the camera ended up.
        val look = subject.add(0.0, -0.4, 0.0).subtract(cam)
        val flat = Math.hypot(look.x, look.z)
        val yaw = if (flat < 0.2) g.yaw else Math.toDegrees(Math.atan2(-look.x, look.z)).toFloat()
        val pitch = Math.toDegrees(Math.atan2(-look.y, flat)).toFloat()
        return Eye(cam, yaw, pitch, subject)
    }

    private val scratch = net.minecraft.core.BlockPos.MutableBlockPos()

    /** From the subject back along [yaw] (the way they'd face) and [up], [BACK_OFF] long. */
    private fun backOf(yaw: Float, up: Double): Vec3 {
        val yr = Math.toRadians(yaw.toDouble())
        return Vec3(Math.sin(yr) * BACK_OFF, up, -Math.cos(yr) * BACK_OFF)
    }

    /** How far along [dir] (in units of [BACK_OFF] of it) one can go from [from] before a block, less [margin]; [BACK_OFF] when clear. */
    private fun clearance(level: net.minecraft.client.multiplayer.ClientLevel, from: Vec3, dir: Vec3, margin: Double = 0.3): Double {
        val pos = net.minecraft.core.BlockPos.MutableBlockPos()
        val len = dir.length(); val steps = (len / 0.2).toInt()
        for (s in 1..steps) {
            val p = from.add(dir.scale(s / steps.toDouble()))
            if (KnownWorld.stateAt(level, pos, Mth.floor(p.x), Mth.floor(p.y), Mth.floor(p.z)).isSolid)
                return (BACK_OFF * (s - 1) / steps - margin).coerceAtLeast(0.0)
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

    private class Spot(val x: Double, val z: Double, val y: Double)

    /**
     * Where to stand near (x, z): there when its column has room for a player under a ceiling (so never the top of a wall
     * or the roof), else the middle of the nearest column within three blocks that does; at the standing height closest to
     * [eyeY] (the lowest when unknown). Falls back to (x, z) at where they were last, or the usual dungeon floor.
     */
    private fun spotAt(level: net.minecraft.client.multiplayer.ClientLevel, x: Double, z: Double, eyeY: Double): Spot {
        val pos = net.minecraft.core.BlockPos.MutableBlockPos()
        val bx = Mth.floor(x); val bz = Mth.floor(z)
        val spots = ArrayList<Int>()
        for (r in 0..3) {
            var best = Double.NaN; var bestX = 0; var bestZ = 0
            for (dx in -r..r) for (dz in -r..r) {
                if (maxOf(Math.abs(dx), Math.abs(dz)) != r) continue
                spots.clear()
                var top = Int.MIN_VALUE
                var below = KnownWorld.stateAt(level, pos, bx + dx, FLOOR_LOW - 1, bz + dz).isSolid
                var at = KnownWorld.stateAt(level, pos, bx + dx, FLOOR_LOW, bz + dz).isSolid
                for (y in FLOOR_LOW..FLOOR_HIGH) {
                    val above = KnownWorld.stateAt(level, pos, bx + dx, y + 1, bz + dz).isSolid
                    if (at) top = y
                    if (below && !at && !above) spots += y
                    below = at; at = above
                }
                for (y in spots) {
                    if (y >= top) continue
                    val better = if (eyeY.isNaN()) best.isNaN() || y < best
                        else best.isNaN() || Math.abs(y + 1.62 - eyeY) < Math.abs(best + 1.62 - eyeY)
                    if (better) { best = y.toDouble(); bestX = dx; bestZ = dz }
                }
            }
            if (!best.isNaN()) return if (r == 0) Spot(x, z, best) else Spot(bx + bestX + 0.5, bz + bestZ + 0.5, best)
        }
        return Spot(x, z, if (eyeY.isNaN()) 69.0 else eyeY - 1.62)
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
        val entities: List<EntityRenderState>, val bodies: List<Body>,
    )

    /** The guessed body of an out-of-range teammate: where and which way it stands, its walk, and their skin. */
    private class Body(val feet: Vec3, val yaw: Float, val walkPos: Float, val walkSpeed: Float, val skin: PlayerSkin)

    private val swings = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /** How far through its swing the entity's arm is (0 when not swinging), from when the swing arrived. */
    private fun swingOf(id: Int, now: Long): Float {
        val at = swings[id] ?: return 0f
        val t = (now - at).toFloat() / SWING_NS
        if (t >= 1f) { swings.remove(id); return 0f }
        return t
    }

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
                renderFigures(job)
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

    private val figureProjection = Projection()
    private val figureProjectionBuffer by lazy { ProjectionMatrixBuffer("devgineerclient pose pov figures") }
    private val wideModel by lazy { PlayerModel(mc.entityModels.bakeLayer(ModelLayers.PLAYER), false) }
    private val slimModel by lazy { PlayerModel(mc.entityModels.bakeLayer(ModelLayers.PLAYER_SLIM), true) }
    private val bodyState = AvatarRenderState().apply {
        showHat = true; showJacket = true; showLeftPants = true; showRightPants = true; showLeftSleeve = true; showRightSleeve = true
    }

    /**
     * Over the feed's blocks, tested against their depth: every entity around them as the game draws it, and the guessed
     * bodies of teammates out of range (their skin, walking when the icon moves). Positions are relative to the eye, as the
     * game's own entity pass does it.
     */
    private fun renderFigures(job: Job) {
        if (job.entities.isEmpty() && job.bodies.isEmpty()) return
        val colorView = job.target.colorTextureView ?: return
        val depthView = job.target.depthTextureView ?: return
        val orientation = Quaternionf().rotationYXZ(Math.PI.toFloat() - Math.toRadians(job.yaw.toDouble()).toFloat(), -Math.toRadians(job.pitch.toDouble()).toFloat(), 0f)
        val poses = PoseStack()
        poses.mulPose(Matrix4f().rotation(Quaternionf(orientation).conjugate()))
        val camera = CameraRenderState().apply {
            pos = job.eye; blockPos = net.minecraft.core.BlockPos.containing(job.eye); xRot = job.pitch; yRot = job.yaw
            this.orientation = orientation; initialized = true; isFirstPerson = false
        }

        for (s in job.entities) runCatching {
            mc.entityRenderDispatcher.submit(s, camera, s.x - job.eye.x, s.y - job.eye.y, s.z - job.eye.z, poses, handSubmits)
        }
        for (b in job.bodies) {
            val model = if (b.skin.model() == PlayerModelType.SLIM) slimModel else wideModel
            bodyState.skin = b.skin; bodyState.bodyRot = b.yaw; bodyState.yRot = 0f; bodyState.xRot = 0f
            bodyState.walkAnimationPos = b.walkPos; bodyState.walkAnimationSpeed = b.walkSpeed
            poses.pushPose()
            poses.translate(b.feet.x - job.eye.x, b.feet.y - job.eye.y, b.feet.z - job.eye.z)
            poses.rotateDegrees(Axis.YP, 180f - b.yaw)
            // The living renderer's flip, and a player's 15/16 scale.
            poses.scale(-0.9375f, -0.9375f, 0.9375f)
            poses.translate(0f, -1.501f, 0f)
            handSubmits.submitModel(model, bodyState, poses, b.skin.body().texturePath(), LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0)
            poses.popPose()
        }

        figureProjection.setupPerspective(NEAR, FAR, mc.options.fov().get().toFloat(), job.w.toFloat(), job.h.toFloat())
        RenderSystem.backupProjectionMatrix()
        val modelView = RenderSystem.getModelViewStack()
        modelView.pushMatrix().identity()
        try {
            RenderSystem.setProjectionMatrix(figureProjectionBuffer.getBuffer(figureProjection), ProjectionType.PERSPECTIVE)
            mc.gameRenderer.featureRenderDispatcher().prepareFrame(handSubmits).use { frame ->
                RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    RenderPassDescriptor.builder { "devgineerclient pose pov figures" }
                        .withColorAttachment(colorView, Optional.empty())
                        .withDepthAttachment(depthView, OptionalDouble.empty())
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
