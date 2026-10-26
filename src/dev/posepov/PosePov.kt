package com.devgineerclient.posepov

import com.devgineerclient.DevgineerClient
import com.mojang.blaze3d.ProjectionType
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.commands.RenderPassDescriptor
import net.minecraft.client.renderer.texture.TextureAtlas
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.textures.FilterMode
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
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
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.Floor
import com.odtheking.odin.utils.skyblock.dungeon.terminals.TerminalTypes
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
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
import net.minecraft.network.protocol.game.ClientboundSetTimePacket
import net.minecraft.resources.Identifier
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import com.devgineerclient.perf.GameMonitor
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ExperienceOrb
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.joml.FrustumIntersection
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
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

    private val PIPELINE = PoseShader.PIPELINE
    private val CULLED = PoseShader.CULLED

    /** Entities drawn in full per view at most (the nearest), and how far one is drawn in full rather than as a box. */
    private const val ENTITY_CAP = 32
    private const val ENTITY_FULL = 40.0
    /** Name tags, and armour stands that are only a name, past this are left out. */
    private const val NAME_RANGE = 16.0

    private val prerender by BooleanSetting("Prerender", true, desc = "Builds the whole dungeon and the boss arena in the background while you're in a dungeon, so the leap menu only has to draw. Room geometry is kept across runs either way.")
    private val optimizeLocation by BooleanSetting("Optimize server location", true, desc = "Times each teammate's two ways separately (Cloudflare's nearest location to each of you, or undonecoffee.com's relay) and sends your view to them by whichever is quicker right now. Off: Cloudflare's whenever it's up. Nobody ever sees your IP either way.")
    private val shareFps by NumberSetting("Sharing FPS", 60, 20..240, 1, desc = "How many times a second your look goes to teammates. Higher is smoother for them.")
    private val skipTiny by BooleanSetting("Skip tiny look changes", true, desc = "Doesn't send a look change under 0.05° (too small to see in a view; mostly hand jitter at high FPS). The exact look still goes with every tick. Cuts what you send when your mouse barely moves.")
    private val debugInfo by BooleanSetting("Debug info", false, desc = "Over the views: the relay and TURN connections, each teammate's route and one-way delays both ways, frames a second, losses and buffering. Also written to the log every 10 seconds.")
    private val perfMonitor by BooleanSetting("Performance monitor", false, desc = "What every part of the views costs: each step of working out and drawing them (ms a frame and the worst), what runs every tick (sharing, prerender, prefetch), the background threads' CPU, GPU time, uploads, culling and memory. Over the views, top left on the HUD otherwise; also written to the log every 10 seconds.")
    private val gameMonitor by BooleanSetting("Game performance monitor", false, desc = "Temporary: the whole game's performance, top right over everything. Frame times and lows, where each frame goes (packets, ticks, world, GUI, waiting on the GPU), GPU time, which mods the game thread is in, heap, GC, threads' CPU, the world and the connection. Also written to the log every 10 seconds.")
    private val overHuds by BooleanSetting("Show over HUDs", false, desc = "Draws the views on top of every HUD (yours and every mod's) instead of under them. The leap menu's own boxes and names stay on top either way.")
    private val resolution by NumberSetting("POV resolution", 100, 25..100, 5, unit = "%", desc = "How sharp the views are drawn. Lower is faster on weaker graphics cards; they're scaled up to fill their quarter of the screen.")
    private val viewDistance by NumberSetting("View distance", 96, 32..160, 8, unit = " blocks", desc = "How far the views draw, fading into the sky over the last stretch. Lower is faster; past half of it only the bigger faces are drawn.")

    /** Whether the device draws many ranges in one call (glMultiDrawElementsBaseVertex and the like). */
    private var multiFailed = false
    private val multiDraw by lazy { runCatching { RenderSystem.getDevice().deviceInfo.features().multiDrawDirectSeparate() }.getOrDefault(false) }
    /** Whether the device takes its draws from a buffer (glMultiDrawElementsIndirect): a view's blocks are then one call per buffer. */
    private var indirectFailed = false
    private val indirect by lazy { runCatching { RenderSystem.getDevice().deviceInfo.features().multiDrawIndirect() }.getOrDefault(false) }
    /**
     * Every view in one target, a quarter each (feed i: column i % 2, row i / 2, row 0 on top): their blocks in one pass, one
     * pipeline and texture bind for all four. A view drawn again clears only its quarter, so a still one's picture stays.
     */
    private var allViews: TextureTarget? = null
    /** What each feed last drew, when that was only blocks and its own hand (see [draw]). */
    private data class FeedSig(val target: TextureTarget, val eye: Vec3, val yaw: Float, val pitch: Float, val swing: Float, val held: Int, val world: Long)
    private val lastSig = arrayOfNulls<FeedSig>(FEEDS)
    /** Each feed's camera and world when it was last drawn with entities in it, and when (ns): see [STILL_NS]. */
    private val lastCam = arrayOfNulls<FeedSig>(FEEDS)
    private val lastDrawnAt = LongArray(FEEDS)
    /** A view whose camera hasn't moved is drawn again at most this often for its entities alone (20 a second). */
    private const val STILL_NS = 50_000_000L
    /** Each view's projection, written once per frame (one buffer each: the blocks pass binds all four). */
    private val viewProjections by lazy { Array(FEEDS) { ProjectionMatrixBuffer("devgineerclient pose pov $it") } }
    private val projection = Projection()
    private val projectionBuffer by lazy { ProjectionMatrixBuffer("devgineerclient pose pov") }
    private val view = Matrix4f()
    private val skyColor = Vector4f(((SKY shr 16) and 255) / 255f, ((SKY shr 8) and 255) / 255f, (SKY and 255) / 255f, 1f)

    init {
        // Only when the under-GUI hook didn't draw this frame (it can't have matched): on top of the HUD then, but still there.
        on<ScreenEvent.Render>(EventPriority.HIGHEST + 100) {
            DevgineerClient.safely("pose pov") { if (!drewUnderGui && wants()) timed(true) { draw(guiGraphics) } }
            drewUnderGui = false
        }
        ClientTickEvents.END_CLIENT_TICK.register {
            DevgineerClient.safely("pose pov release") { if (!wants()) releaseFeeds() }
            // Swings are kept only as long as one lasts (the packet comes for every entity in sight).
            DevgineerClient.safely("pose pov swings") { val now = System.nanoTime(); swings.values.removeIf { now - it > SWING_NS } }
            PovPerf.on = enabled && perfMonitor
            GameMonitor.on = gameMonitor
            DevgineerClient.safely("pose pov look") { if (enabled && DungeonUtils.inDungeons) PovPerf.time(PovPerf.LOOKS) { sampleLooks() } }
            // Sharing runs for everyone in a dungeon, this on or not; it only sends while a linked teammate is watching.
            DevgineerClient.safely("pose share") {
                PoseShare.watching = enabled && DungeonUtils.inDungeons && (wants() || mc.player?.mainHandItem?.itemId.equalsOneOf("SPIRIT_LEAP", "INFINITE_SPIRIT_LEAP"))
                PoseShare.optimize = optimizeLocation
                PoseShare.skipTiny = skipTiny
                PoseShare.debug = enabled && debugInfo
                PoseShare.frameNs = 1_000_000_000L / shareFps.toLong().coerceIn(20L, 240L)
                PovPerf.time(PovPerf.SHARE) { PoseShare.tick() }
            }
            DevgineerClient.safely("pose pov warm up") { if (enabled && DungeonUtils.inDungeons && PoseShare.watching && !wants()) PovPerf.time(PovPerf.WARM) { warmUp() } }
            DevgineerClient.safely("pose pov prerender") { if (enabled && prerender && DungeonUtils.inDungeons && !wants()) PovPerf.time(PovPerf.PRERENDER) { build(PRERENDER_BUDGET_NS, emptyList()) } }
            DevgineerClient.safely("pose pov export") { exportTick() }
            // From the first world joined (not the first dungeon), every room's geometry onto disk in the background (once;
            // paced), so none is ever built or waited for. Looked at every 5 s until it has started.
            DevgineerClient.safely("pose pov prefetch") {
                if (enabled && mc.level != null && ++prefetchTicks % 100 == 1 && !BlockMesh.prefetchStarted)
                    PovPerf.time(PovPerf.PREFETCH) { KnownWorld.load {}; BlockMesh.prefetchAll(!DungeonUtils.inDungeons || DungeonUtils.floor.let { it == Floor.F7 || it == Floor.M7 }) }
            }
        }
        // `/posepov export`: every room, variant and the arena built here and written out, for undonecoffee.com to hand out.
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            // `/posepov trace`: 6 seconds of every view's camera and its nearest entities, frame by frame, to a file (for finding shaking).
            dispatcher.register(literal("posepov").then(literal("trace").executes {
                mc.execute { traceStart() }
                1
            }))
            dispatcher.register(literal("posepov").then(literal("export").executes {
                mc.execute {
                    KnownWorld.load {}
                    val n = BlockMesh.startExport()
                    DevgineerClient.chat(if (n < 0) "§cPose POV: the room pack is still loading, try again in a moment." else "§aPose POV: building $n bundles...")
                    exportShown = 0
                }
                1
            }))
        }
        // This run's block changes, on top of the known rooms and arena (see KnownWorld).
        onReceive<ClientboundBlockUpdatePacket> { PovPerf.time(PovPerf.PACKETS) { KnownWorld.change(pos, blockState) } }
        onReceive<ClientboundSectionBlocksUpdatePacket> { PovPerf.time(PovPerf.PACKETS) { runUpdates { pos, state -> KnownWorld.change(pos, state) } } }
        // Game performance monitor: the server's ticks a second, and how long since anything arrived.
        onReceive<ClientboundSetTimePacket> { if (GameMonitor.on) GameMonitor.timePacket(gameTime()) }
        onReceive<net.minecraft.network.protocol.Packet<*>>(priority = Int.MIN_VALUE) { if (GameMonitor.on) GameMonitor.packet() }
        // The monitors: on the HUD without a screen, over everything with one (the menu draws the views' own, see draw).
        HudElementRegistry.attachElementBefore(VanillaHudElements.SLEEP, Identifier.fromNamespaceAndPath("devgineerclient", "perf_monitors")) { gfx, _ ->
            DevgineerClient.safely("perf monitors") { if (mc.gui.screen() == null) monitors(gfx) }
        }
        on<ScreenEvent.Render>(EventPriority.LOWEST) { DevgineerClient.safely("perf monitors") { monitors(guiGraphics) } }
        onReceive<ClientboundLevelChunkWithLightPacket> {
            val level = mc.level ?: return@onReceive
            PovPerf.time(PovPerf.PACKETS) {
                BlockMesh.dirtyColumn(x, z, level.minSectionY, level.maxSectionY, check = true)
                RoomEdges.chunk(x, z)
            }
        }
        // (An unloaded chunk keeps what was built from it: the best there is until it comes back, checked then.)
        // Arm swings, timed from the packet: the game's own swing state sticks for some remote players, as their head yaw does.
        onReceive<ClientboundSwingAnimationPacket> { if (hand() == InteractionHand.MAIN_HAND) swings[entityId()] = System.nanoTime() }
        on<LevelEvent.Load> { KnownWorld.reset(); BlockMesh.newRun(); portals = null; for (c in culls) c.reset() }
    }

    override fun onDisable() {
        super.onDisable()
        releaseFeeds()
        BlockMesh.clear()
    }

    /** The feeds, under all of the frame's GUI (PosePovUnderGuiMixin): the HUD, the leap menu and every mod draw over them. */
    // The log's line every 10 s while the feeds show: how long the frame's preparing and drawing took on the game's thread, and how
    // much was drawn (for testers' logs: what it costs on their machine).
    private var perfFrames = 0
    private var perfPrep = 0L
    private var perfRender = 0L
    private var perfWorst = 0L
    private var perfDraws = 0L
    private var perfEntities = 0L
    private var perfReused = 0L
    private var perfAt = 0L
    private var perfFrameNs = 0L
    /** Smoothed prepare and draw times (ns), and the last frame's draws and entities, for the debug info. */
    private var dbgPrep = 0.0
    private var dbgDraw = 0.0
    private var dbgDraws = 0
    private var dbgEntities = 0
    private var dbgDrawsMark = 0L
    private var dbgEntitiesMark = 0L
    private var perfAlloc = 0L
    private inline fun timed(prep: Boolean, f: () -> Unit) {
        val a0 = PovPerf.allocated()
        val t0 = System.nanoTime()
        f()
        val dt = System.nanoTime() - t0
        GameMonitor.pov(if (prep) GameMonitor.POV_PREP else GameMonitor.POV, dt)
        if (prep) { perfPrep += dt; perfFrameNs = dt; perfAlloc = PovPerf.allocated() - a0; return }
        PovPerf.frame(perfFrameNs + dt, perfAlloc + PovPerf.allocated() - a0)
        perfRender += dt; perfFrames++; perfWorst = maxOf(perfWorst, perfFrameNs + dt)
        dbgPrep = dbgPrep * 0.95 + perfFrameNs * 0.05; dbgDraw = dbgDraw * 0.95 + dt * 0.05
        dbgDraws = (perfDraws - dbgDrawsMark).toInt(); dbgEntities = (perfEntities - dbgEntitiesMark).toInt(); dbgDrawsMark = perfDraws; dbgEntitiesMark = perfEntities
        val now = System.currentTimeMillis()
        if (perfAt == 0L) perfAt = now
        if (now - perfAt < 10_000) return
        val n = perfFrames.coerceAtLeast(1)
        DevgineerClient.logger.info("[pose pov] {} frames: prepare {} ms, draw {} ms a frame (worst {} ms), {} draws and {} entities a frame, {} views reused, {} piece buffers",
            n, "%.2f".format(perfPrep / 1e6 / n), "%.2f".format(perfRender / 1e6 / n), "%.1f".format(perfWorst / 1e6), perfDraws / n, perfEntities / n, perfReused, PiecePool.poolCount)
        perfFrames = 0; perfPrep = 0; perfRender = 0; perfWorst = 0; perfDraws = 0; perfEntities = 0; perfReused = 0; perfAt = now
        dbgDrawsMark = 0; dbgEntitiesMark = 0
    }

    fun drawUnderGui(gfx: GuiGraphicsExtractor) {
        // "Show over HUDs": left to the screen's render instead (ScreenEvent.Render above), after the HUD.
        if (overHuds) return
        DevgineerClient.safely("pose pov") { if (wants()) { timed(true) { draw(gfx) }; drewUnderGui = true } }
    }
    private var drewUnderGui = false

    private fun wants(): Boolean {
        if (!enabled || !LeapMenu.enabled) return false
        val screen = mc.gui.screen() as? AbstractContainerScreen<*> ?: return false
        return screen.title.string.equalsOneOf("Spirit Leap", "Teleport to Player")
    }

    private fun releaseFeeds() {
        queued.clear()
        allViews?.destroyBuffers(); allViews = null
        for (i in 0 until FEEDS) { lastSig[i] = null; lastCam[i] = null }
    }

    private fun draw(gfx: GuiGraphicsExtractor) {
        val level = mc.level ?: return
        queued.clear()
        val pt = mc.deltaTracker.getGameTimeDeltaPartialTick(false)
        val w = (mc.window.width / 2 * resolution.toInt() / 100).coerceAtLeast(1)
        val h = (mc.window.height / 2 * resolution.toInt() / 100).coerceAtLeast(1)
        val gw = gfx.guiWidth(); val gh = gfx.guiHeight()
        val pose = Matrix3x2f(gfx.pose())
        val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)

        // Each slot's eye: the teammate's own when they're loaded, otherwise a camera behind where their map icon puts them.
        val now = System.nanoTime()
        var lap = now
        val eyes = arrayOfNulls<Eye>(FEEDS)
        val players = arrayOfNulls<Player>(FEEDS)
        val bodies = ArrayList<Body>()
        val shared = arrayOfNulls<PoseShare.Pose>(FEEDS)
        for (i in 0 until FEEDS) {
            val slot = DungeonUtils.leapTeammates.getOrNull(i)?.takeIf { it.name != "Empty" } ?: continue
            val p = slot.entity?.takeIf { !it.isRemoved } ?: level.players().firstOrNull { it.name.string == slot.name }
            // Out of your range: where they (or a teammate who can see them) say they are, else the map's guess.
            // In clear the map says roughly where they are: a shared pose far from their icon is someone lying, and isn't used.
            // Peer-to-peer, they're drawn from their own frames even in range (smoother than the game's 20 a second), while
            // those put them where the server does (a few blocks' lead for the delay).
            val d = if (p != null && !slot.isDead) PoseShare.pose(slot.name, now)?.takeIf { it.direct && it.pos.distanceToSqr(p.position()) < 16.0 } else null
            val s = d ?: if (p == null && !slot.isDead) PoseShare.pose(slot.name, now)?.takeIf { DungeonUtils.inBoss || slot.mapPos.data == 0L || near(slot, it.pos) } else null
            val e = if (p != null && d == null) {
                players[i] = p
                val real = look(slot.name, p, pt, now)
                ghosts.getOrPut(slot.name) { Ghost() }.seen(real, now)
                real
            } else if (s != null) {
                shared[i] = s
                if (p != null) players[i] = p
                val real = Eye(s.pos.add(0.0, if (s.sneaking) 1.27 else 1.62, 0.0), s.yaw, s.pitch, null)
                ghosts.getOrPut(slot.name) { Ghost() }.seen(real, now)
                real
            // (In boss the map stops: its icon would put them back where they were in clear.)
            } else if (!slot.isDead && slot.mapPos.data != 0L && !DungeonUtils.inBoss) ghostEye(level, slot, now) else null
            if (e != null) eyes[i] = ghosts.getOrPut(slot.name) { Ghost() }.blended(e, now)
            // An out-of-range teammate's body, in every feed: where they're shared at (but their own), or the guess (their
            // own included, seen from behind).
            val g = ghosts[slot.name]
            val guess = e?.subject
            if (s != null && p == null && g != null) slot.playerSkin?.let { bodies += Body(s.pos, s.yaw, g.walkPos, g.walkSpeed, it, i) }
            else if (p == null && guess != null && g != null) slot.playerSkin?.let { bodies += Body(guess.subtract(0.0, 1.62, 0.0), g.yaw, g.walkPos, g.walkSpeed, it, -1) }
        }

        lap = PovPerf.lap(PovPerf.EYES, lap)

        // Every entity near any eye, extracted once for all the feeds (only those near enough to one to be drawn in full): full
        // bright like the blocks, without outlines, and no name tag past NAME_RANGE of every eye. (Not an invisible armour stand
        // with nothing on it and no name showing: Hypixel's markers, which draw nothing.)
        // What every view asks of an entity is worked out here once: its room, its arena cluster, its box where it's drawn.
        val entities = frameEntities.also { it.clear() }
        val near = eyes.filterNotNull()
        val range = minOf(ENTITY_RANGE, viewDistance.toDouble())
        val range2 = range * range
        for (en in level.entitiesForRendering()) {
            val closest = near.minOfOrNull { it.pos.distanceToSqr(en.position()) } ?: break
            if (closest >= range2) continue
            // Nothing to draw: an invisible mob or armour stand with nothing on (Hypixel's markers) and no name showing near
            // enough; a dropped item or orb past TINY_RANGE (a few pixels at most).
            val bare = en.isInvisible && en is LivingEntity && !en.isCurrentlyGlowing && EquipmentSlot.entries.all { en.getItemBySlot(it).isEmpty }
            if (bare && (!en.isCustomNameVisible || closest > NAME_RANGE * NAME_RANGE)) continue
            if ((en is ItemEntity || en is ExperienceOrb) && closest > TINY_RANGE * TINY_RANGE) continue
            val at = smoothed(en, pt, now)
            val s = if (closest > ENTITY_FULL * ENTITY_FULL) null else runCatching { mc.entityRenderDispatcher.extractEntity(en, pt) }.getOrNull()?.also { s ->
                s.x = at.x; s.y = at.y; s.z = at.z
                s.lightCoords = LightCoordsUtil.FULL_BRIGHT; s.outlineColor = 0
                if (closest > NAME_RANGE * NAME_RANGE) s.nameTag = null
            }
            val bx = Mth.floor(en.x); val by = Mth.floor(en.y); val bz = Mth.floor(en.z)
            val b = en.boundingBox.move(at.x - en.x, at.y - en.y, at.z - en.z)
            entities += Seen(en, s, at, b, KnownWorld.roomAt(bx, bz), PovVisibility.index(BlockMesh.cluster(SectionPos.asLong(bx shr 4, by shr 4, bz shr 4))), bare, kindColor(en))
        }
        if (now - smoothPruned > 2_000_000_000L) { smoothPruned = now; smooth.values.removeIf { now - it.at > 2_000_000_000L } }

        lap = PovPerf.lap(PovPerf.ENTITIES, lap)

        // The sections around every eye, nearest first, built (within budget) before anything is drawn: made again only when an
        // eye moves into another section (the same list again tells the builder nothing new is wanted).
        val me = mc.player?.position()
        val order = eyes.filterNotNull().sortedBy { if (me == null) 0.0 else it.pos.distanceToSqr(me) }
        val sections = LongArray(order.size) { SectionPos.asLong(SectionPos.blockToSectionCoord(Mth.floor(order[it].pos.x)), SectionPos.blockToSectionCoord(Mth.floor(order[it].pos.y)), SectionPos.blockToSectionCoord(Mth.floor(order[it].pos.z))) }
        if (!sections.contentEquals(nearFor)) {
            nearFor = sections
            val wanted = LinkedHashSet<Long>()
            for (s in sections) {
                val cx = SectionPos.x(s); val cy = SectionPos.y(s); val cz = SectionPos.z(s)
                val around = ArrayList<Long>()
                for (dx in -RADIUS_XZ..RADIUS_XZ) for (dy in -RADIUS_Y..RADIUS_Y) for (dz in -RADIUS_XZ..RADIUS_XZ) around += SectionPos.asLong(cx + dx, cy + dy, cz + dz)
                around.sortBy { k -> val a = SectionPos.x(k) - cx; val b = SectionPos.y(k) - cy; val c = SectionPos.z(k) - cz; a * a + b * b + c * c }
                wanted += around
            }
            nearList = wanted.toList()
        }
        build(DRAW_BUDGET_NS, nearList)
        lap = PovPerf.lap(PovPerf.BUILD, lap)

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
                    val label = if (slot.isDead) "dead" else if (DungeonUtils.inBoss) "out of range" else "not on the map yet"
                    gfx.text(label, cx - mc.font.width(label) / 2, cy + 2, Color(120, 120, 120), shadow = true)
                }
                continue
            }
            val target = allViews?.takeIf { it.width == w * 2 && it.height == h * 2 } ?: run {
                allViews?.destroyBuffers()
                TextureTarget("dc:pose_pov", w * 2, h * 2, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT).also { allViews = it }
            }
            val p = players[i]
            val item = handItems[i].also { it.clear() }
            val right = p?.mainArm != HumanoidArm.LEFT
            if (p != null && !p.mainHandItem.isEmpty) mc.itemModelResolver.updateForLiving(item, p.mainHandItem, if (right) ItemDisplayContext.FIRST_PERSON_RIGHT_HAND else ItemDisplayContext.FIRST_PERSON_LEFT_HAND, p)
            val sh = shared[i]
            val me = mc.player
            if (sh != null && me != null && !sh.item.isEmpty) mc.itemModelResolver.updateForLiving(item, sh.item, ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, me)
            val subject = e.subject
            val ghost = slot?.let { ghosts[it.name] }
            // Everything around them but themselves (their view is from inside their head), and every body but a shared one's own.
            val ef = entityFrustum(e, w, h)
            // Not in a room or an arena cluster that can't be seen from here (the feed's last cull, PovVisibility), as the blocks.
            // The nearest ENTITY_CAP within ENTITY_FULL drawn in full, the rest as boxes coloured by their kind.
            val roomsSeen = culls[i].result?.rooms
            val clustersSeen = PovVisibility.clusters(e.pos)
            val inSight = sightLists[i].also { it.clear() }
            for (x in entities) {
                val b = x.box
                val d2 = x.at.distanceToSqr(e.pos)
                if (x.en === p || d2 >= range2) continue
                if (x.bare && d2 > NAME_RANGE * NAME_RANGE) continue
                if (roomsSeen != null && x.room != null && x.room !in roomsSeen) continue
                if (clustersSeen != null && x.cluster >= 0 && !clustersSeen[x.cluster]) continue
                if (!ef.testAab(b.minX.toFloat() - 1, b.minY.toFloat() - 1, b.minZ.toFloat() - 1, b.maxX.toFloat() + 1, b.maxY.toFloat() + 1, b.maxZ.toFloat() + 1)) continue
                x.d2 = d2
                inSight += x
            }
            inSight.sortBy { it.d2 }
            if (trace != null) traceFeed(i, e, slot?.name, inSight.map { Triple(it.en, it.state, it.d2) }, pt, now, w, h)
            val seen = ArrayList<EntityRenderState>()
            val boxes = ArrayList<Box>()
            for (x in inSight) {
                val s = x.state
                if (s != null && x.d2 <= ENTITY_FULL * ENTITY_FULL && seen.size < ENTITY_CAP) { seen += s; continue }
                if (x.bare || x.en is ArmorStand && x.en.isInvisible) continue
                // Where the entity is drawn this frame (smoothed), not where its last tick left it: boxes otherwise step 20 times a second.
                val b = x.box
                boxes += Box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, x.rgb)
            }
            val swing = p?.let { swingOf(it.id, now) } ?: sh?.let { swingFrom(it.swingAt + it.bufferNs, now) } ?: 0f
            val others = bodies.filter { it.slot != i }
            // Nothing in it moved and nothing new to draw (a teammate standing still): last frame's picture stays.
            val held = (sh?.item ?: p?.mainHandItem)?.let { System.identityHashCode(it) * 31 + it.count } ?: 0
            val cam = FeedSig(target, e.pos, e.yaw, e.pitch, swing, held, BlockMesh.changes + PiecePool.version + MeshBundles.count * 1_000_003L + System.identityHashCode(KnownWorld.rooms) * 7L + PovVisibility.knownCount * 131L + culls[i].generation * 1_000_033L + viewDistance.toLong() * 7919L)
            val sig = if (seen.isEmpty() && others.isEmpty() && boxes.isEmpty()) cam else null
            // With entities in it but its camera and world as they were: drawn again only every STILL_NS (they still move,
            // 20 times a second as the server sends them).
            val still = sig == null && cam == lastCam[i] && now - lastDrawnAt[i] < STILL_NS
            if ((sig == null || sig != lastSig[i]) && !still) {
                queued += Job(i, target, e.pos, e.yaw, e.pitch, w, h, item, if (right) 1f else -1f, swing, seen, others, boxes)
                lastCam[i] = cam; lastDrawnAt[i] = now
            } else perfReused++
            lastSig[i] = sig
            perfEntities += seen.size
            val colorView = target.colorTextureView ?: continue
            gfx.guiRenderState.addGuiElement(
                BlitRenderState(
                    RenderPipelines.GUI_OPAQUE_TEXTURED_BACKGROUND,
                    TextureSetup.singleTexture(colorView, sampler),
                    pose,
                    x0, y0, x1, y1,
                    // Its quarter of the shared target; a render target's row 0 is the bottom of its image.
                    col * 0.5f, 1f - row * 0.5f, col * 0.5f + 0.5f, 0.5f - row * 0.5f,
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
            if (sh != null) footLabel(gfx, delayLabel(i, sh.via, sh.direct, sh.delayMs), Color(200, 200, 210), x0, x1, y1)
            slot?.let { PoseShare.gui(it.name, now) }?.let { terminal(gfx, it, x0, y0, x1, y1) }
        }
        lap = PovPerf.lap(PovPerf.FEEDS, lap)
        // Whether sharing is up, and how many teammates are sharing with you.
        val n = PoseShare.sharingCount
        val (dot, status) = when {
            !PoseShare.connected -> 0xFF808080.toInt() to "sharing: offline"
            n == 0 -> 0xFFE0C040.toInt() to "sharing: no one else"
            else -> 0xFF50E070.toInt() to "sharing: $n"
        }
        gfx.fill(4, 5, 9, 10, dot)
        gfx.text(status, 12, 4, Color(200, 200, 200), shadow = true)
        val lines = ArrayList<Pair<String, Int>>()
        if (debugInfo) lines += PoseShare.debugLines()
        if (perfMonitor) lines += perfLines()
        if (lines.isNotEmpty()) panel(gfx, lines)
        PovPerf.lap(PovPerf.OVERLAY, lap)
    }

    /** What each view knows of an entity this frame (see [draw]); [d2] is set per view. */
    private class Seen(val en: Entity, val state: EntityRenderState?, val at: Vec3, val box: net.minecraft.world.phys.AABB, val room: KnownWorld.Room?, val cluster: Int, val bare: Boolean, val rgb: Int) { var d2 = 0.0 }
    private val frameEntities = ArrayList<Seen>()
    private val sightLists = Array(FEEDS) { ArrayList<Seen>() }
    /** Dropped items and orbs past this from every eye are left out (a few pixels at most). */
    private const val TINY_RANGE = 24.0

    /** A view's delay label, made again only when what it says changes. */
    private val delayLabels = arrayOfNulls<String>(FEEDS)
    private val delayKeys = LongArray(FEEDS) { -1 }
    private fun delayLabel(i: Int, via: String?, direct: Boolean, ms: Int): String {
        val key = (via?.hashCode()?.toLong() ?: 0L) * 31 + (if (direct) 1 else 0) * 1_000_003L + ms
        if (key != delayKeys[i] || delayLabels[i] == null) { delayKeys[i] = key; delayLabels[i] = (via?.let { "via $it · " } ?: if (direct) "fast · " else "relay · ") + "~${ms}ms" }
        return delayLabels[i]!!
    }

    /** The monitors outside the menu: the views' own (what runs every tick still costs), and the game's. */
    private fun monitors(gfx: GuiGraphicsExtractor) {
        if (perfMonitor && enabled && !wants()) panel(gfx, perfLines())
        if (gameMonitor) GameMonitor.draw(gfx)
    }

    /** "Performance monitor": PovPerf's lines, then the render's counts. */
    private fun perfLines(): List<Pair<String, Int>> {
        val lines = PovPerf.lines().toMutableList()
        lines += "render: prepare %.2f ms · draw %.2f ms · %d draws · %d entities · %d bundles loaded%s".format(
            dbgPrep / 1e6, dbgDraw / 1e6, dbgDraws, dbgEntities, MeshBundles.count, if (multiDraw && !multiFailed) " · multidraw" else "") to 0xFFB0B0B0.toInt()
        lines += "cull: %d kept · %d ahead · %d waited for a second · %d pieces · %d far · %d quads · %d rooms seen".format(
            cullRate[0], cullRate[1], cullRate[2], dbgRefs, dbgFar, dbgQuads, dbgRooms) to 0xFFB0B0B0.toInt()
        lines += "gpu: %d pieces · %.0f of %.0f MB (cap %d) · %d evicted · %d decoding".format(
            PiecePool.residentCount, PiecePool.usedBytes / 1048576.0, PiecePool.poolBytes / 1048576.0, PiecePool.CAP_BYTES shr 20, PiecePool.evictions, PiecePool.queued) to 0xFFB0B0B0.toInt()
        PovPerf.log(lines)
        return lines
    }

    /** Lines in a dark box, top left under the sharing status; scaled down to fit the screen's width. */
    private fun panel(gfx: GuiGraphicsExtractor, lines: List<Pair<String, Int>>) {
        var y = 16
        val w = lines.maxOf { mc.font.width(it.first) }
        val scale = minOf(1f, (gfx.guiWidth() - 12f) / (w + 8))
        val pose = gfx.pose()
        pose.pushMatrix()
        pose.scale(scale, scale)
        gfx.fill(2, y - 2, 8 + w, y + lines.size * 10, 0xA0000000.toInt())
        for ((text, color) in lines) { gfx.text(text, 5, y, Color((color shr 16) and 255, (color shr 8) and 255, color and 255), shadow = true); y += 10 }
        pose.popMatrix()
    }

    private fun footLabel(gfx: GuiGraphicsExtractor, label: String, color: Color, x0: Int, x1: Int, y1: Int) {
        val cx = (x0 + x1) / 2; val half = mc.font.width(label) / 2
        gfx.fill(cx - half - 3, y1 - 14, cx + half + 3, y1 - 2, 0x90000000.toInt())
        gfx.text(label, cx - half, y1 - 12, color, shadow = true)
    }

    /**
     * A teammate's open terminal over their feed: its grid, as Odin lays it out, with Odin's solution lit (Numbers in order,
     * Rubix with its click counts, Melody's pointer and its lane). Nothing of theirs is drawn but which cells: no items, no
     * text (the title is the terminal's name here, with the letter or colour looked up here).
     */
    private fun terminal(gfx: GuiGraphicsExtractor, t: PoseShare.Gui, x0: Int, y0: Int, x1: Int, y1: Int) {
        val grid = PoseShare.GRIDS[t.type] ?: return
        val cell = 14
        val gridW = grid.cols * cell; val gridH = grid.rows * cell + 12
        val boxW = maxOf(gridW, 120)
        val scale = minOf(1.5f, (x1 - x0 - 8f) / boxW, (y1 - y0 - 8f) / gridH)
        if (scale <= 0.3f) return
        gfx.fill(x0, y0, x1, y1, 0x80000000.toInt())
        val pose = gfx.pose()
        pose.pushMatrix()
        pose.translate((x0 + x1) / 2f - boxW * scale / 2, (y0 + y1) / 2f - gridH * scale / 2)
        pose.scale(scale, scale)
        val left = t.solution.distinct().size
        val title = when (t.type) {
            TerminalTypes.PANES -> "Panes"
            TerminalTypes.RUBIX -> "Rubix"
            TerminalTypes.NUMBERS -> "Numbers"
            TerminalTypes.STARTS_WITH -> "Starts With '${'A' + t.extra}'"
            TerminalTypes.SELECT -> "Select " + net.minecraft.world.item.DyeColor.entries[t.extra].name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
            TerminalTypes.MELODY -> "Melody"
        } + if (t.type == TerminalTypes.MELODY) "" else " · $left left"
        gfx.text(title, (boxW - mc.font.width(title)) / 2, 0, Color(255, 255, 255), shadow = true)
        val clicks = HashMap<Int, Int>(); val order = HashMap<Int, Int>()
        t.solution.forEachIndexed { n, s -> clicks.merge(s, 1, Int::plus); order.putIfAbsent(s, n) }
        val gx = (boxW - gridW) / 2
        for (r in 0 until grid.rows) for (c in 0 until grid.cols) {
            val slot = (grid.startRow + r) * 9 + grid.startCol + c
            val x = gx + c * cell; val y = 12 + r * cell
            val n = order[slot]
            val color = when (t.type) {
                // Red: still to click; green: done.
                TerminalTypes.PANES -> if (n != null) 0xE0D04040.toInt() else 0xE040B040.toInt()
                TerminalTypes.NUMBERS -> when (n) { null -> 0x60303030; 0 -> 0xF040D040.toInt(); 1 -> 0xE0D0C030.toInt(); 2 -> 0xD0D07020.toInt(); else -> 0xC0A04040.toInt() }
                // Melody's solution: the lane's green pane, the pointer, and the button when they line up.
                TerminalTypes.MELODY -> when (n) { 0 -> 0xF040D040.toInt(); 1 -> 0xF0D040D0.toInt(); 2 -> 0xF080FF80.toInt(); else -> 0x40303030 }
                else -> if (n != null) 0xE040D040.toInt() else 0x60303030
            }
            gfx.fill(x, y, x + cell - 1, y + cell - 1, color)
            if (t.type == TerminalTypes.RUBIX) clicks[slot]?.let { gfx.text("$it", x + (cell - mc.font.width("$it")) / 2, y + 3, Color(255, 255, 255), shadow = true) }
            if (t.type == TerminalTypes.NUMBERS && n != null && n < 3) gfx.text("${n + 1}", x + (cell - mc.font.width("${n + 1}")) / 2, y + 3, Color(255, 255, 255), shadow = true)
        }
        pose.popMatrix()
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
        val eye = smoothed(p, pt, now).add(0.0, p.eyeHeight.toDouble(), 0.0)
        val l = looks[name] ?: return Eye(eye, p.yHeadRot, p.xRot, null)
        val yaw = Mth.rotLerp(pt, l.yawO, l.yaw); val pitch = Mth.lerp(pt, l.pitchO, l.pitch)
        val dt = ((now - l.outAt) / 1e9); l.outAt = now
        if (dt > 0.25) { l.outYaw = yaw; l.outPitch = pitch }
        else {
            val k = (1 - Math.exp(-dt / LOOK_EASE_S)).toFloat()
            l.outYaw += Mth.wrapDegrees(yaw - l.outYaw) * k; l.outPitch += (pitch - l.outPitch) * k
        }
        return Eye(eye, l.outYaw, l.outPitch, null)
    }

    /**
     * Where an entity is drawn in the views: its interpolated position eased over [SMOOTH_S]. Hypixel's position updates come
     * unevenly (none one tick, two the next), which the game's own view hides because you never ride another entity's position;
     * a view from a teammate's eye does, and every entity near it shook against it. The eye and the entities go through the same
     * easing, so things moving with the teammate stay put relative to them. Jumps over [SMOOTH_SNAP] blocks aren't eased.
     */
    private class Smooth(var x: Double, var y: Double, var z: Double, var at: Long)
    private val smooth = HashMap<Int, Smooth>()
    private var smoothPruned = 0L
    private const val SMOOTH_S = 0.05
    private const val SMOOTH_SNAP = 4.0

    private fun smoothed(en: Entity, pt: Float, now: Long): Vec3 {
        val tx = Mth.lerp(pt.toDouble(), en.xOld, en.x); val ty = Mth.lerp(pt.toDouble(), en.yOld, en.y); val tz = Mth.lerp(pt.toDouble(), en.zOld, en.z)
        val m = smooth[en.id]
        if (m == null) { smooth[en.id] = Smooth(tx, ty, tz, now); return Vec3(tx, ty, tz) }
        val dt = (now - m.at) / 1e9
        if (dt <= 0.0) return Vec3(m.x, m.y, m.z)
        m.at = now
        val dx = tx - m.x; val dy = ty - m.y; val dz = tz - m.z
        if (dt > 0.25 || dx * dx + dy * dy + dz * dz > SMOOTH_SNAP * SMOOTH_SNAP) { m.x = tx; m.y = ty; m.z = tz }
        else { val k = 1 - Math.exp(-dt / SMOOTH_S); m.x += dx * k; m.y += dy * k; m.z += dz * k }
        return Vec3(m.x, m.y, m.z)
    }

    // --- /posepov trace -----------------------------------------------------------------------------------------------------

    private var trace: StringBuilder? = null
    private var traceUntil = 0L
    private var traceFrame = 0L
    private var traceT0 = 0L

    private fun traceStart() {
        trace = StringBuilder("ms,frame,pt,gametime,feed,name,camera,eyeX,eyeY,eyeZ,yaw,pitch,entity,type,drawnX,drawnY,drawnZ,tickX,tickY,tickZ,oldX,oldY,oldZ,screenX,screenY,full,bodyYaw,headYaw,stateBody,stateHead\n")
        traceT0 = System.nanoTime(); traceUntil = traceT0 + 6_000_000_000L; traceFrame = 0
        DevgineerClient.chat("§aPose POV: tracing for 6 seconds, keep the leap menu open on the shaking.")
    }

    private var traceFrameAt = 0L
    private fun traceFeed(i: Int, e: Eye, name: String?, inSight: List<Triple<Entity, EntityRenderState?, Double>>, pt: Float, now: Long, w: Int, h: Int) {
        val sb = trace ?: return
        if (now > traceUntil) {
            trace = null
            val file = net.fabricmc.loader.api.FabricLoader.getInstance().gameDir.resolve("devgineerclient").resolve("posepov-trace.csv")
            runCatching { java.nio.file.Files.createDirectories(file.parent); java.nio.file.Files.writeString(file, sb) }
            DevgineerClient.chat("§aPose POV: trace written to $file")
            return
        }
        if (now != traceFrameAt) { traceFrameAt = now; traceFrame++ }
        val gt = mc.level?.gameTime ?: 0L
        for ((en, st, _) in inSight.take(4)) {
            val m = smooth[en.id]
            val dx = st?.x ?: m?.x ?: en.x; val dy = st?.y ?: m?.y ?: en.y; val dz = st?.z ?: m?.z ?: en.z
            val p = project(e, Vec3(dx, dy + en.bbHeight / 2, dz), w, h, 0, 0, w, h)
            sb.append("%.3f,%d,%.4f,%d,%d,%s,%s,%.4f,%.4f,%.4f,%.3f,%.3f,%d,%s,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%s,%s,%b,%.3f,%.3f,%s,%s\n".format(
                (now - traceT0) / 1e6, traceFrame, pt, gt, i, name ?: "", if (e.subject == null) "eye" else "guess",
                e.pos.x, e.pos.y, e.pos.z, e.yaw, e.pitch, en.id, en.type.toString().substringAfterLast('.'),
                dx, dy, dz, en.x, en.y, en.z, en.xOld, en.yOld, en.zOld, p?.first ?: "", p?.second ?: "", st != null,
                (en as? net.minecraft.world.entity.LivingEntity)?.yBodyRot ?: en.yRot, en.yHeadRot,
                (st as? net.minecraft.client.renderer.entity.state.LivingEntityRenderState)?.bodyRot?.toString() ?: "", (st as? net.minecraft.client.renderer.entity.state.LivingEntityRenderState)?.yRot?.toString() ?: ""))
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
        var camOff = 0f; var camAim = 0f; var camUp = BACK_UP; var camChecked = 0L
        val trail = ArrayDeque<DoubleArray>(); var walkPos = 0f; var walkSpeed = 0f; var faceAlpha = 1f; var faceAt = 0L
        var last: Eye? = null; var lastAt = 0L; var from: Eye? = null; var fromAt = 0L

        /** Their own view this frame: the estimate picks up from here when they go out of range. */
        fun seen(e: Eye, now: Long) {
            // The walk, for their body when it is drawn from shared poses (the game's limb swing advances about 4 per block).
            if (!x.isNaN() && at > 0L) {
                val moved = Math.hypot(e.pos.x - x, e.pos.z - z); val dt = ((now - at) / 1e9).coerceIn(0.0, 0.25)
                if (moved < 2.0) { walkPos += (moved * 4).toFloat(); if (dt > 0) walkSpeed += (((moved / dt) / 5).coerceAtMost(1.0).toFloat() - walkSpeed) * (1 - Math.exp(-dt * 6)).toFloat() }
            }
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

    /** Whether [pos] is within a room's width of where the map puts [slot] (the icon is only ever a block or two out). */
    private fun near(slot: DungeonPlayer, pos: Vec3): Boolean {
        val tx = ((slot.mapPos.x + 128) / 2.0 - DungeonScan.startX) * 32.0 / DungeonScan.roomGap - 200
        val tz = ((slot.mapPos.z + 128) / 2.0 - DungeonScan.startY) * 32.0 / DungeonScan.roomGap - 200
        return Math.hypot(pos.x - tx, pos.z - tz) < 24.0
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
        KnownWorld.refresh(level, DungeonUtils.inDungeons && !DungeonUtils.inBoss) { r ->
            // The world sections under a room just placed lose those cells to the room's own pieces.
            for (cx in (r.x0 shr 4)..(r.x1 shr 4)) for (cz in (r.z0 shr 4)..(r.z1 shr 4)) BlockMesh.dirtyColumn(cx, cz, level.minSectionY, level.maxSectionY)
        }
        BlockMesh.pump(level, budgetNs, near, prerender && DungeonUtils.inDungeons)
    }

    private const val EXPORT_BUDGET_NS = 40_000_000L
    private var exportShown = 0

    private fun exportTick() {
        val level = mc.level ?: return
        val (done, of) = BlockMesh.exportStep(level, EXPORT_BUDGET_NS) ?: return
        if (done == of) { DevgineerClient.chat("§aPose POV: all $of bundles written to ${BlockMesh.exportFolder}"); return }
        val tenth = done * 10 / of
        if (tenth > exportShown) { exportShown = tenth; DevgineerClient.chat("§7Pose POV export: $done / $of") }
    }

    /** One draw: [count] indices of [buffer] from vertex [first]. */
    private class Cmd(val buffer: com.mojang.renderpearl.api.buffers.GpuBuffer, val first: Int, var count: Int)

    private class Job(
        val feed: Int, val target: TextureTarget, val eye: Vec3, val yaw: Float, val pitch: Float, val w: Int, val h: Int,
        val item: ItemStackRenderState, val side: Float, val swing: Float,
        val entities: List<EntityRenderState>, val bodies: List<Body>, val boxes: List<Box>,
    )

    /** An entity too far (or past the cap) to draw in full: its box, in its kind's colour (RGB). */
    private class Box(val x0: Double, val y0: Double, val z0: Double, val x1: Double, val y1: Double, val z1: Double, val rgb: Int)

    /** A colour per kind of entity, the same every time: bright enough to read against the blocks. */
    private fun kindColor(en: Entity): Int {
        val h = (en.type.toString().hashCode() and 0xFFFF) / 65536f
        return Mth.hsvToRgb(h, 0.65f, 0.95f) and 0xFFFFFF
    }

    /** The guessed body of an out-of-range teammate: where and which way it stands, its walk, and their skin. */
    private class Body(val feet: Vec3, val yaw: Float, val walkPos: Float, val walkSpeed: Float, val skin: PlayerSkin, val slot: Int)

    private val swings = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /** How far through its swing the entity's arm is (0 when not swinging), from when the swing arrived. */
    private fun swingOf(id: Int, now: Long): Float {
        val at = swings[id] ?: return 0f
        val t = (now - at).toFloat() / SWING_NS
        if (t >= 1f) { swings.remove(id); return 0f }
        return t
    }

    /** How far through a swing that started at [at] the arm is (0 before it or once done). */
    private fun swingFrom(at: Long, now: Long): Float {
        val t = (now - at).toFloat() / SWING_NS
        return if (t < 0f || t >= 1f) 0f else t
    }

    /** Whether the entity swung since [since] (for passing on a teammate's swings). */
    fun swungSince(id: Int, since: Long) = (swings[id] ?: 0L) > since

    private val handItems = Array(FEEDS) { ItemStackRenderState() }
    private val handProjection = Projection()
    private val handProjectionBuffer by lazy { ProjectionMatrixBuffer("devgineerclient pose pov hand") }
    private val handSubmits = SubmitNodeStorage()

    /** The feeds the menu asked for this frame: poses read while the screen is extracted, drawn in [renderQueued]. */
    private val queued = ArrayList<Job>()
    /** The feed's view as a frustum (world coordinates, out to [ENTITY_RANGE]), for leaving out the entities it can't show. */
    private val entityCull = FrustumIntersection()
    private fun entityFrustum(e: Eye, w: Int, h: Int): FrustumIntersection {
        val v = Matrix4f().rotation(Quaternionf().rotationYXZ(Math.PI.toFloat() - Math.toRadians(e.yaw.toDouble()).toFloat(), -Math.toRadians(e.pitch.toDouble()).toFloat(), 0f).conjugate())
            .translate(-e.pos.x.toFloat(), -e.pos.y.toFloat(), -e.pos.z.toFloat())
        return entityCull.set(Matrix4f().setPerspective(Math.toRadians(mc.options.fov().get().toDouble()).toFloat(), w.toFloat() / h, NEAR, ENTITY_RANGE.toFloat()).mul(v))
    }

    // The sections the eyes were in when [nearList] (the sections around them, nearest first) was made.
    private var nearFor = LongArray(0)
    private var nearList: List<Long> = emptyList()

    // --- what each feed draws: worked out ahead and kept (PovCull) ----------------------------------------------------------

    /** What a feed's cull was worked out for: the view, and what it went by. */
    private class At(
        val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float, val w: Int, val h: Int, val fov: Float, val dist: Int,
        val rooms: List<KnownWorld.Room>, val eyePiece: Long, val inputs: Long,
    )

    /**
     * A feed's last cull and what it resolved to: kept while the eye stays within [PovCull.MARGIN] and the look within
     * [PovCull.ANGLE_MARGIN]° of where it was worked out for (the same room piece, rooms, size and view distance); worked out
     * again on [cullWorker] once the eye has moved half that, so the game thread only waits for one when it jumps.
     */
    private class Cull {
        var result: PovCull.Result? = null
        var at: At? = null
        var pending: java.util.concurrent.Future<Pair<At, PovCull.Result>>? = null
        /** Bumped whenever what the feed draws changes (for the still-picture check in [draw]). */
        var generation = 0L
        var draws: Map<Long, Map<com.mojang.renderpearl.api.buffers.GpuBuffer, List<Cmd>>> = emptyMap()
        var drawsFor: PovCull.Result? = null
        var drawsKey = 0L
        var drawsAt = 0L
        var drawsComplete = false
        /** [draws] as [Batch]es, and their indirect commands on the GPU (see [batches]). */
        var batches: List<Batch> = emptyList()
        var batchesFor: Any? = null
        var batchesIndirect = false
        var indirect: com.mojang.renderpearl.api.buffers.GpuBuffer? = null

        fun reset() { result = null; at = null; pending?.cancel(false); pending = null; drawsFor = null; draws = emptyMap(); generation++ }
    }
    private val culls = Array(FEEDS) { Cull() }
    private val cullWorker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "pose pov cull").apply { isDaemon = true } }
    /** Culls a second: kept, worked out ahead, waited for. */
    private val cullCount = IntArray(3)
    private val cullRate = IntArray(3)
    private var cullRateAt = 0L
    private var dbgRefs = 0
    private var dbgFar = 0
    private var dbgQuads = 0
    private var dbgRooms = 0

    @Volatile private var portals: PovCull.Portals? = null
    private var portalsRooms: List<KnownWorld.Room>? = null
    private var portalsChanged = -1
    private var portalsAt = 0L

    /** The openings between rooms as they stand (made again when a room is placed, and at most 4 times a second for block changes). */
    private fun portalsNow(): PovCull.Portals? {
        if (!DungeonUtils.inDungeons || DungeonUtils.inBoss) return null
        val rooms = KnownWorld.rooms
        val changed = KnownWorld.changedCells
        val now = System.currentTimeMillis()
        if (portals == null || rooms !== portalsRooms || (changed.size != portalsChanged && now - portalsAt > 250)) {
            portals = PovCull.Portals.build(portals, changed.toList())
            portalsRooms = rooms; portalsChanged = changed.size; portalsAt = now
        }
        return portals
    }

    /** The view's projection times view for culling: out to [dist], [PovCull.ANGLE_MARGIN]° wider every way. */
    private fun cullMatrix(eye: Vec3, yaw: Float, pitch: Float, w: Int, h: Int, fov: Float, dist: Int): Matrix4f {
        val halfV = Math.toRadians(fov / 2.0)
        val halfH = Math.atan(Math.tan(halfV) * w / h)
        val m = Math.toRadians(PovCull.ANGLE_MARGIN.toDouble())
        val wideV = minOf(halfV + m, Math.toRadians(85.0)); val wideH = minOf(halfH + m, Math.toRadians(85.0))
        val v = Matrix4f().rotation(Quaternionf().rotationYXZ(Math.PI.toFloat() - Math.toRadians(yaw.toDouble()).toFloat(), -Math.toRadians(pitch.toDouble()).toFloat(), 0f).conjugate())
            .translate(-eye.x.toFloat(), -eye.y.toFloat(), -eye.z.toFloat())
        return Matrix4f().setPerspective((wideV * 2).toFloat(), (Math.tan(wideH) / Math.tan(wideV)).toFloat(), NEAR, dist + PovCull.MARGIN.toFloat()).mul(v)
    }

    private fun cullInput(eye: Vec3, vp: Matrix4f, dist: Int, wide: Boolean = false) = PovCull.Input(
        eye.x, eye.y, eye.z, vp, dist.toDouble(), maxOf(32.0, dist * 0.5), KnownWorld.rooms, portalsNow(),
        PovVisibility.clusters(eye), if (KnownWorld.bossFloor()) MeshBundles.get(BlockMesh.ARENA) else null, BlockMesh.worldSections(), wide,
    )

    /** What feed [i] draws from this view: its kept cull when it still holds, a finished one from the worker, or one worked out now. */
    private fun cullFor(i: Int, eye: Vec3, yaw: Float, pitch: Float, w: Int, h: Int, fov: Float, dist: Int): PovCull.Result {
        val c = culls[i]
        val rooms = KnownWorld.rooms
        // An eye in a room's sealed pocket (a teleport, a stonk): that room's pockets are drawn from now on.
        KnownWorld.roomAt(Mth.floor(eye.x), Mth.floor(eye.z))?.let { r ->
            if (r.cutPockets && r.inPocket(Mth.floor(eye.x), Mth.floor(eye.y), Mth.floor(eye.z))) { r.cutPockets = false; BlockMesh.roomChanged() }
        }
        val eyePiece = PovCull.eyePiece(eye.x, eye.y, eye.z)
        val sections = BlockMesh.worldSections()
        val inputs = MeshBundles.count * 1_000_003L + PovVisibility.knownCount * 131L + System.identityHashCode(sections) * 31L + System.identityHashCode(portals) + (if (KnownWorld.bossFloor()) 7L else 0L)
        fun turned(a: At) = Math.abs(Mth.wrapDegrees(yaw - a.yaw)) + Math.abs(pitch - a.pitch)
        fun moved(a: At) = Math.sqrt((eye.x - a.x) * (eye.x - a.x) + (eye.y - a.y) * (eye.y - a.y) + (eye.z - a.z) * (eye.z - a.z))
        fun holds(a: At) = a.rooms === rooms && a.w == w && a.h == h && a.fov == fov && a.dist == dist && a.eyePiece == eyePiece &&
            moved(a) <= PovCull.MARGIN && turned(a) <= PovCull.ANGLE_MARGIN
        fun at() = At(eye.x, eye.y, eye.z, yaw, pitch, w, h, fov, dist, rooms, eyePiece, inputs)

        c.pending?.takeIf { it.isDone }?.let { f ->
            c.pending = null
            val (a, r) = runCatching { f.get() }.getOrNull() ?: return@let
            if (holds(a)) { c.at = a; c.result = r; c.generation++; cullCount[1]++ }
        }
        val a = c.at
        if (a == null || c.result == null || !holds(a)) {
            val now = at()
            c.result = PovPerf.time(PovPerf.CULL_WAIT) { PovCull.compute(cullInput(eye, cullMatrix(eye, yaw, pitch, w, h, fov, dist), dist)) }
            c.at = now; c.generation++; cullCount[2]++
            c.pending?.cancel(false); c.pending = null
        } else {
            cullCount[0]++
            if (c.pending == null && (moved(a) > PovCull.MARGIN / 2 || turned(a) > PovCull.ANGLE_MARGIN / 2 || a.inputs != inputs)) {
                val next = at()
                val input = cullInput(eye, cullMatrix(eye, yaw, pitch, w, h, fov, dist), dist)
                c.pending = cullWorker.submit<Pair<At, PovCull.Result>> { next to PovPerf.time(PovPerf.CULL_AHEAD) { PovCull.compute(input) } }
            }
        }
        return c.result!!
    }

    /**
     * The cull as draws, grouped by the frame they're drawn in (a room's pieces are in its own box frame; the nearest frame
     * first), then by buffer, each piece's facing groups (and near ones' small faces) as runs. Pieces not on the GPU yet are
     * asked for, those just out of view after. Kept until a piece comes or goes, the run's own geometry changes, or a second
     * passes (pieces in use are marked used then).
     */
    private fun resolve(c: Cull, res: PovCull.Result): Map<Long, Map<com.mojang.renderpearl.api.buffers.GpuBuffer, List<Cmd>>> {
        val key = PiecePool.version * 31 + BlockMesh.changes
        val now = System.currentTimeMillis()
        if (c.drawsFor === res && c.drawsKey == key && c.drawsComplete && now - c.drawsAt < 1000) return c.draws
        val out = LinkedHashMap<Long, LinkedHashMap<com.mojang.renderpearl.api.buffers.GpuBuffer, ArrayList<Cmd>>>()
        val asked = PiecePool.asked
        fun emit(buffer: com.mojang.renderpearl.api.buffers.GpuBuffer, first: Int, counts: IntArray, ox: Int, oz: Int, faces: Int, far: Boolean) {
            val byBuffer = out.getOrPut((ox.toLong() shl 32) or (oz.toLong() and 0xFFFFFFFFL)) { LinkedHashMap() }
            val list = byBuffer.getOrPut(buffer) { ArrayList() }
            var v = first; var runFirst = -1; var runQuads = 0
            fun flush() {
                if (runFirst < 0) return
                // Runs that sit one after another in the same buffer are one draw.
                val last = list.lastOrNull()
                if (last != null && last.first + last.count / 6 * 4 == runFirst) last.count += runQuads * 6 else list += Cmd(buffer, runFirst, runQuads * 6)
                dbgQuads += runQuads
                runFirst = -1; runQuads = 0
            }
            for (g in 0 until CompactMesh.GROUPS) {
                val n = counts[g]
                if (n == 0) continue
                if (PovCull.drawn(g, faces, far)) { if (runFirst < 0) runFirst = v; runQuads += n } else flush()
                v += n * 4
            }
            flush()
        }
        for (ref in res.refs) {
            if (ref.far) dbgFar++
            when (ref.kind) {
                PovCull.ROOM -> {
                    val r = ref.room!!
                    val own = BlockMesh.own(r, ref.lx, ref.ly, ref.lz)
                    if (own != null) emit(own.buffer, own.first, own.counts, own.ox, own.oz, ref.faces, ref.far)
                    else if (ref.piece >= 0) PiecePool.get(ref.bundle!!, ref.piece, ref.rot, ref.w, ref.d, r, ref.dist2)?.let { emit(it.buffer, it.first, it.counts, 0, 0, ref.faces, ref.far) }
                }
                PovCull.ARENA -> if (!BlockMesh.arenaTouched(ref.section)) PiecePool.get(ref.bundle!!, ref.piece, ref.rot, 0, 0, null, ref.dist2)?.let { emit(it.buffer, it.first, it.counts, 0, 0, ref.faces, ref.far) }
                else -> BlockMesh.worldMesh(ref.section)?.let { emit(it.buffer, it.first, it.counts, it.ox, it.oz, ref.faces, ref.far) }
            }
        }
        val complete = PiecePool.asked == asked
        for (ref in res.around) if (ref.piece >= 0 && ref.bundle != null) PiecePool.get(ref.bundle, ref.piece, ref.rot, ref.w, ref.d, ref.room, 1e9 + ref.dist2)
        c.draws = out; c.drawsFor = res; c.drawsKey = key; c.drawsAt = now; c.drawsComplete = complete
        c.generation++
        return out
    }

    /**
     * While a leap is in hand (the menu not open yet), every half second: the pieces within 64 blocks of each teammate,
     * through the openings around them but whichever way they look, asked for (after anything a view wants), so opening the
     * menu finds them on the GPU.
     */
    private var warmTicks = 0
    private var prefetchTicks = 0
    private fun warmUp() {
        if (++warmTicks % 10 != 0) return
        val level = mc.level ?: return
        val now = System.nanoTime()
        for (slot in DungeonUtils.leapTeammates) {
            if (slot.name == "Empty" || slot.isDead) continue
            val pos = slot.entity?.takeIf { !it.isRemoved }?.position() ?: level.players().firstOrNull { it.name.string == slot.name }?.position()
                ?: PoseShare.pose(slot.name, now)?.pos ?: continue
            val eye = pos.add(0.0, 1.62, 0.0)
            val res = PovCull.compute(cullInput(eye, Matrix4f(), 64, wide = true))
            for (ref in res.refs) if (ref.piece >= 0 && ref.bundle != null) PiecePool.get(ref.bundle, ref.piece, ref.rot, ref.w, ref.d, ref.room, 1e8 + ref.dist2)
        }
        if (!prerender) PiecePool.load(1_000_000L)
    }

    /**
     * Draws this frame's feeds, from the end of the world render (PosePovRenderMixin): before the GUI that shows them, and in the
     * render phase where the game's own passes run, not while the screen is being extracted.
     */
    fun renderQueued() {
        if (queued.isEmpty()) return
        DevgineerClient.safely("pose pov render") { timed(false) {
            val nowMs = System.currentTimeMillis()
            if (nowMs - cullRateAt >= 1000) { cullCount.copyInto(cullRate); cullCount.fill(0); cullRateAt = nowMs }
            dbgRefs = 0; dbgFar = 0; dbgQuads = 0; dbgRooms = 0
            // One index buffer for every feed, asked for once at the largest size: growing it between feeds would replace it
            // under the passes already recorded.
            val most = maxOf(BlockMesh.mostIndices, BOX_INDICES)
            val quadIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS)
            val indices = quadIndices.getBuffer(most)
            PovPerf.gpuBegin()
            renderBlocks(queued, quadIndices, indices)
            for (job in queued) {
                PovPerf.time(PovPerf.FIGURES) { renderFigures(job) }
                PovPerf.time(PovPerf.HAND) { renderHand(job) }
            }
            PovPerf.gpuEnd()
        } }
        queued.clear()
    }

    /** Entity boxes drawn per view at most. */
    private const val MAX_BOXES = 256
    private const val BOX_INDICES = MAX_BOXES * 6 * 6
    private val boxBuffers = arrayOfNulls<com.mojang.renderpearl.api.buffers.GpuBuffer>(FEEDS)
    private val SIDE_SHADE = floatArrayOf(0.5f, 1f, 0.8f, 0.8f, 0.6f, 0.6f)
    private val mipSampler by lazy { RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST, true) }

    /** The job's entity boxes as [CompactMesh] quads in feed [i]'s box buffer, around (ox, oz): how many quads. */
    private fun boxQuads(i: Int, boxes: List<Box>, ox: Int, oz: Int): Int {
        val n = minOf(boxes.size, MAX_BOXES)
        if (n == 0) return 0
        val white = MeshCodec.sprites()?.let { CompactMesh.SpriteTable.of(it.named("minecraft:block/white_concrete")) } ?: return 0
        val bytes = java.nio.ByteBuffer.allocateDirect(n * 6 * 4 * CompactMesh.VERTEX).order(java.nio.ByteOrder.nativeOrder())
        fun p(v: Double) = ((v + 256) * 64 + 0.5).toInt().coerceIn(0, 65535).toShort()
        for (k in 0 until n) {
            val b = boxes[k]
            val x0 = b.x0 - ox; val x1 = b.x1 - ox; val z0 = b.z0 - oz; val z1 = b.z1 - oz
            for (f in 0 until 6) {
                val s = SIDE_SHADE[f]
                val r = (((b.rgb shr 16) and 255) * s).toInt().toByte(); val g = (((b.rgb shr 8) and 255) * s).toInt().toByte(); val bl = ((b.rgb and 255) * s).toInt().toByte()
                for (c in 0 until 4) {
                    // Corner c of side f: two of its coordinates step around the face, the third is the side's.
                    val a = c == 1 || c == 2; val bb = c >= 2
                    val (x, y, z) = when (f) {
                        0 -> Triple(if (a) x1 else x0, b.y0, if (bb) z1 else z0)
                        1 -> Triple(if (a) x1 else x0, b.y1, if (bb) z1 else z0)
                        2 -> Triple(if (a) x1 else x0, if (bb) b.y1 else b.y0, z0)
                        3 -> Triple(if (a) x1 else x0, if (bb) b.y1 else b.y0, z1)
                        4 -> Triple(x0, if (bb) b.y1 else b.y0, if (a) z1 else z0)
                        else -> Triple(x1, if (bb) b.y1 else b.y0, if (a) z1 else z0)
                    }
                    bytes.putShort(p(x)).putShort(p(y)).putShort(p(z)).putShort(white.toShort())
                    bytes.putShort(if (a) 2048 else 0).putShort(if (bb) 2048 else 0)
                    bytes.put(r).put(g).put(bl).put((-1).toByte())
                }
            }
        }
        bytes.flip()
        val size = bytes.remaining().toLong()
        val buf = boxBuffers[i]?.takeIf { it.size() >= size } ?: run {
            boxBuffers[i]?.close()
            RenderSystem.getDevice().createBuffer({ "devgineerclient pose pov boxes" }, com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_VERTEX or com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_COPY_DST, maxOf(size, 64L * 6 * 4 * CompactMesh.VERTEX)).also { boxBuffers[i] = it }
        }
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buf.slice(0, size), bytes)
        return n * 6
    }

    /** Feed [i]'s quarter of the shared target, in the target's pixels (GL's: from the bottom left). */
    private fun quarter(i: Int, w: Int, h: Int) = com.mojang.renderpearl.api.commands.RenderPass.RenderArea((i % 2) * w, (1 - i / 2) * h, w, h)

    /** [p] squeezed into feed [i]'s quarter of the shared target (the viewport is always the whole target). */
    private fun intoQuarter(i: Int, p: Matrix4f): Matrix4f = Matrix4f().translate((i % 2) - 0.5f, 0.5f - i / 2, 0f).scale(0.5f, 0.5f, 1f).mul(p)

    /** What a view's blocks are drawn with: one frame and buffer's draws as a range of [Cull.indirect], or as commands. */
    private class Batch(val frame: Long, val buffer: com.mojang.renderpearl.api.buffers.GpuBuffer, val cmds: List<Cmd>, val offset: Long)

    /**
     * Feed [c]'s draws as indirect commands (count, 1, first index 0, base vertex, 0), uploaded once each time they change
     * ([resolve] keeps them otherwise): its blocks are then one call per frame and buffer.
     */
    private fun batches(c: Cull, draws: Map<Long, Map<com.mojang.renderpearl.api.buffers.GpuBuffer, List<Cmd>>>, useIndirect: Boolean): List<Batch> {
        if (c.batchesFor === draws && c.batchesIndirect == useIndirect) return c.batches
        val out = ArrayList<Batch>()
        var n = 0
        for ((k, byBuffer) in draws) for ((buffer, cmds) in byBuffer) { out += Batch(k, buffer, cmds, n * 20L); n += cmds.size }
        if (useIndirect && n > 0) {
            val bytes = MemoryUtil.memAlloc(n * 20)
            try {
                for (bt in out) for (m in bt.cmds) bytes.putInt(m.count).putInt(1).putInt(0).putInt(m.first).putInt(0)
                bytes.flip()
                val size = n * 20L
                val buf = c.indirect?.takeIf { it.size() >= size } ?: run {
                    c.indirect?.close()
                    RenderSystem.getDevice().createBuffer({ "devgineerclient pose pov draws" }, com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_INDIRECT_PARAMETERS or com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_COPY_DST, maxOf(size * 2, 4096L)).also { c.indirect = it }
                }
                RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buf.slice(0, size), bytes)
            } finally { MemoryUtil.memFree(bytes) }
        }
        c.batches = out; c.batchesFor = draws; c.batchesIndirect = useIndirect
        return out
    }

    /**
     * Every queued view's blocks and entity boxes, in one pass over the shared target: each view's quarter cleared to the sky,
     * then per view its quarter as the scissor, its projection (squeezed into the quarter), and its draws (back faces culled).
     */
    private fun renderBlocks(jobs: List<Job>, quadIndices: RenderSystem.AutoStorageIndexBuffer, indices: com.mojang.renderpearl.api.buffers.GpuBuffer) {
        val target = jobs.firstOrNull()?.target ?: return
        val colorView = target.colorTextureView ?: return
        val depthView = target.depthTextureView ?: return
        val colorTex = target.colorTexture ?: return
        val depthTex = target.depthTexture ?: return
        val fov = mc.options.fov().get().toFloat()
        val dist = viewDistance.toInt()
        val blocksAt = System.nanoTime()
        val encoder = RenderSystem.getDevice().createCommandEncoder()

        class Prepared(val job: Job, val area: com.mojang.renderpearl.api.commands.RenderPass.RenderArea, val proj: com.mojang.renderpearl.api.buffers.GpuBufferSlice,
            val batches: List<Batch>, val c: Cull, val transforms: Map<Long, com.mojang.renderpearl.api.buffers.GpuBufferSlice>, val boxQuads: Int, val boxTransform: com.mojang.renderpearl.api.buffers.GpuBufferSlice?)
        val useIndirect = indirect && !indirectFailed
        val prepared = ArrayList<Prepared>(jobs.size)
        for (job in jobs) {
            val eye = job.eye; val w = job.w; val h = job.h
            view.rotation(Quaternionf().rotationYXZ(Math.PI.toFloat() - Math.toRadians(job.yaw.toDouble()).toFloat(), -Math.toRadians(job.pitch.toDouble()).toFloat(), 0f).conjugate())
                .translate(-eye.x.toFloat(), -eye.y.toFloat(), -eye.z.toFloat())
            val c = culls[job.feed]
            val res = cullFor(job.feed, eye, job.yaw, job.pitch, w, h, fov, dist)
            val draws = PovPerf.time(PovPerf.RESOLVE) { resolve(c, res) }
            dbgRefs += res.refs.size; dbgRooms += res.rooms?.size ?: 0
            val ox = Mth.floor(eye.x); val oz = Mth.floor(eye.z)
            val boxQuads = PovPerf.time(PovPerf.BOXES) { boxQuads(job.feed, job.boxes, ox, oz) }
            projection.setupPerspective(NEAR, dist.toFloat(), fov, w.toFloat(), h.toFloat())
            val proj = viewProjections[job.feed].getBuffer(intoQuarter(job.feed, projection.getMatrix(Matrix4f())))
            // Fresh matrices: the uniform storage keeps the matrix by reference and skips a write equal to the last one, so
            // handing it the reused [view] made every feed match the previous write and draw with a stale slice. The fog's
            // start and end ride in the model offset (the shader has no other use for it).
            fun transform(x: Int, z: Int) = RenderSystem.getDynamicUniforms().writeTransform(
                Matrix4f(view).translate(x.toFloat(), 0f, z.toFloat()), Vector4f(1f, 1f, 1f, 1f), org.joml.Vector3f(dist * 0.7f, dist.toFloat(), 0f), Matrix4f())
            val transforms = draws.keys.associateWith { k -> transform((k shr 32).toInt(), k.toInt()) }
            val area = quarter(job.feed, w, h)
            prepared += Prepared(job, area, proj, batches(c, draws, useIndirect), c, transforms, boxQuads, if (boxQuads > 0) transform(ox, oz) else null)
            encoder.clearColorAndDepthTextures(colorTex, skyColor, depthTex, 0.0, area.x(), area.y(), area.width(), area.height(), 0)
        }

        try {
            encoder.createRenderPass(
                RenderPassDescriptor.builder { "devgineerclient pose pov" }
                    .withColorAttachment(colorView, Optional.empty())
                    .withDepthAttachment(depthView, OptionalDouble.empty())
                    .build()
            ).use { pass ->
                val sprites = CompactMesh.SpriteTable.buffer ?: return@use
                RenderSystem.bindDefaultUniforms(pass)
                val atlas = mc.textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS)
                pass.setPipeline(RenderSystem.getCompiledPipeline(CULLED))
                pass.setUniform("Sampler0", atlas.textureView, mipSampler)
                pass.setUniform("PoseSprites", sprites)
                pass.setIndexBuffer(indices, quadIndices.type())
                val multi = multiDraw && !multiFailed
                for (p in prepared) {
                    val area = p.area
                    pass.enableScissor(area.x(), area.y(), area.width(), area.height())
                    pass.setUniform("Projection", p.proj)
                    // Nearest frame first; per frame and buffer one call where the device can (most do).
                    var frame = Long.MIN_VALUE
                    for (bt in p.batches) {
                        if (bt.frame != frame) { frame = bt.frame; pass.setUniform("DynamicTransforms", p.transforms.getValue(bt.frame)) }
                        pass.setVertexBuffer(0, bt.buffer.slice())
                        val cmds = bt.cmds
                        if (p.c.batchesIndirect && !indirectFailed && cmds.size > 1) {
                            perfDraws += 1
                            try { pass.drawIndexedIndirect(p.c.indirect!!.slice(bt.offset, cmds.size * 20L), cmds.size); continue }
                            catch (e: Exception) {
                                indirectFailed = true
                                DevgineerClient.logger.warn("[pose pov] indirect drawing failed, drawing ranges from the CPU from now on", e)
                            }
                        }
                        perfDraws += if (multi) 1 else cmds.size
                        if (!multi || cmds.size == 1) { for (m in cmds) pass.drawIndexed(m.count, 1, 0, m.first, 0); continue }
                        MemoryStack.stackPush().use { stack ->
                            val offsets = stack.mallocPointer(cmds.size); val counts = stack.mallocInt(cmds.size); val bases = stack.mallocInt(cmds.size)
                            for (m in cmds) { offsets.put(0L); counts.put(m.count); bases.put(m.first) }
                            offsets.flip(); counts.flip(); bases.flip()
                            try { pass.multiDrawIndexed(offsets, counts, bases, cmds.size) }
                            catch (e: Exception) {
                                multiFailed = true
                                DevgineerClient.logger.warn("[pose pov] multi-draw failed, drawing ranges one by one from now on", e)
                                for (m in cmds) pass.drawIndexed(m.count, 1, 0, m.first, 0)
                            }
                        }
                    }
                }
                // Entity boxes are seen from inside too (a box around the camera): not culled.
                var boxes = false
                for (p in prepared) {
                    val bt = p.boxTransform ?: continue
                    if (!boxes) { pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE)); boxes = true }
                    val area = p.area
                    pass.enableScissor(area.x(), area.y(), area.width(), area.height())
                    pass.setUniform("Projection", p.proj)
                    pass.setUniform("DynamicTransforms", bt)
                    pass.setVertexBuffer(0, boxBuffers[p.job.feed]!!.slice())
                    pass.drawIndexed(p.boxQuads * 6, 1, 0, 0, 0)
                }
            }
        } finally {
            PovPerf.lap(PovPerf.BLOCKS, blocksAt)
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

        // The blocks' own far plane, so the depth they left means the same here.
        figureProjection.setupPerspective(NEAR, viewDistance.toFloat(), mc.options.fov().get().toFloat(), job.w.toFloat(), job.h.toFloat())
        RenderSystem.backupProjectionMatrix()
        val modelView = RenderSystem.getModelViewStack()
        modelView.pushMatrix().identity()
        try {
            RenderSystem.setProjectionMatrix(figureProjectionBuffer.getBuffer(intoQuarter(job.feed, figureProjection.getMatrix(Matrix4f()))), ProjectionType.PERSPECTIVE)
            mc.gameRenderer.featureRenderDispatcher().prepareFrame(handSubmits).use { frame ->
                RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    RenderPassDescriptor.builder { "devgineerclient pose pov figures" }
                        .withColorAttachment(colorView, Optional.empty())
                        .withDepthAttachment(depthView, OptionalDouble.empty())
                        .withRenderArea(quarter(job.feed, job.w, job.h))
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
            RenderSystem.setProjectionMatrix(handProjectionBuffer.getBuffer(intoQuarter(job.feed, handProjection.getMatrix(Matrix4f()))), ProjectionType.PERSPECTIVE)
            mc.gameRenderer.featureRenderDispatcher().prepareFrame(handSubmits).use { frame ->
                RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    RenderPassDescriptor.builder { "devgineerclient pose pov hand" }
                        .withColorAttachment(colorView, Optional.empty())
                        // Cleared within its quarter only (the render area is the scissor the clear keeps to).
                        .withDepthAttachment(depthView, OptionalDouble.of(0.0))
                        .withRenderArea(quarter(job.feed, job.w, job.h))
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
