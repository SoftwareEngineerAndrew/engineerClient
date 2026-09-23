package com.engineerclient.pov

import com.engineerclient.EngineerClient
import com.engineerclient.mixin.CameraAccessor
import com.engineerclient.mixin.GameRendererInvoker
import com.engineerclient.mixin.MinecraftAccessor
import com.engineerclient.rotation.EcLog
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.odtheking.odin.features.ModuleManager
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.CameraType
import net.minecraft.client.DeltaTracker
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.state.gui.BlitRenderState
import net.minecraft.world.entity.player.Player
import org.joml.Matrix3x2f

/**
 * Renders the four teammate POV feeds and puts them on screen.
 *
 * ## Why a second `renderLevel` is cheap here
 * 26.1.2 splits the frame into `update()` -> `extract()` -> `render()`, and ALL the expensive
 * terrain work — `LevelRenderer.update`, `cullTerrain`, the occlusion graph, section compilation —
 * lives in `update()`, which we never call. A preview pass is therefore extract + render only:
 * sky, entities, block entities, particles and the already-built terrain, drawn from another pair
 * of eyes into an offscreen target.
 *
 * ## Where the two halves of the feature sit in a frame
 * ```
 * GameRenderer.extract
 *   -> extractGui -> HUD elements                (vanilla HUD, every Odin HUD)
 *   -> screen extract -> ScreenEvent.Render
 *        PovPreviews' handler, priority above Odin's CustomGUIImpl:
 *          [onScreenExtract] allocate feeds, submit the four quadrant blits,
 *                            re-submit the "keep on top" HUDs above them
 *        CustomGUIImpl -> Odin's leap boxes, EC's ring   (submitted after us -> on top)
 * GameRenderer.render
 *   -> renderLevel  HEAD   [beforeLevelRender]   Skip Own View: run the passes, cancel the main one
 *                   RETURN [afterLevelRender]    otherwise: run the passes after the main one
 *   -> guiRenderer.render                        the blits above are drawn HERE, in submit order
 * ```
 * The blit is *submitted* before the feeds are rendered and *drawn* after, so it always shows the
 * current frame — but it also means the feed targets must not be recreated between the two. They
 * are only ever allocated or freed in [onScreenExtract].
 *
 * ## Layering, and why it is submit order rather than z
 * `GuiRenderState.findAppropriateNode` pushes any element that overlaps an earlier one into a
 * higher layer, so "drawn on top" is exactly "submitted later". Submitting in the screen phase
 * puts the previews above the whole HUD; the kept HUDs and Odin's leap boxes are submitted after
 * the previews, so they survive on top of them.
 *
 * ## The pass itself
 * ```
 * save   cameraEntity, window w/h, cameraType, hideGui, camera eye height, mainRenderTarget
 * pose   PovPose.begin(target, mode, ticks, partialTick)   // rewrites the entity's lerp fields
 * set    cameraEntity = teammate, cameraType = FIRST_PERSON, hideGui = true (no hand),
 *        window = feed size (Camera.update reads it for the aspect ratio),
 *        camera eye height = the teammate's, mainRenderTarget = feed
 * camera.update(delta)                     // aligns the shared camera with the teammate
 * SodiumBridge.recull(camera)              // else Sodium shows only YOUR frustum's sections
 * EntityCulling off                        // its verdicts were raytraced from your camera
 * extractWindow + extractOptions + extractCamera + levelRenderer.extractLevel(camera)
 * gameRenderer.renderLevel(delta)          // whole pass lands in the feed
 * restore everything, in reverse
 * ```
 * then, once all the passes are done, `camera.update` + the three extract halves again with the
 * real state, so anything drawing later in the frame sees the player's camera and the real window.
 *
 * ## One deliberate deviation from `docs/pov-preview-plan.md`
 * **No `gameRenderer.extract(delta, true)`.** That is the SecurityCraft recipe, but it also runs
 * `extractGui`, which resets the frame's `GuiRenderState` — including the blits we just submitted —
 * and replays every screen and HUD handler a second time with the POV window size. Calling the
 * three private extract halves instead leaves the GUI state completely untouched.
 */
object PovCapture {

    private const val FEEDS = 4

    private val feeds = arrayOfNulls<RenderTarget>(FEEDS)

    /** Quadrant has no teammate at all (fewer than four in the party) — nothing is blitted there. */
    private val empty = BooleanArray(FEEDS) { true }

    /** Teammate is in the leap menu but their entity is not loaded on this client. */
    private val outOfRange = BooleanArray(FEEDS)

    private var feedWidth = 0
    private var feedHeight = 0

    /** The nested `renderLevel` fires our own injectors again. */
    private var capturing = false

    private var nextFeed = 0
    private var freeRequested = false

    /** Quadrants blitted this frame; the Skip Own View path only fires when all four are. */
    private var blitsSubmitted = 0
    private var skippedMainPass = false

    /** One throwable anywhere in a pass takes the feature out for the session, not the game. */
    @Volatile
    var disabledForSession = false
        private set

    private var lastCostMs = 0.0

    /** What the state was before this quadrant's pass, so a `finally` can put it all back. */
    private class Saved(
        val cameraEntity: net.minecraft.world.entity.Entity?,
        val windowWidth: Int,
        val windowHeight: Int,
        val cameraType: CameraType,
        val hideGui: Boolean,
        val eyeHeight: Float,
        val eyeHeightOld: Float,
        val target: RenderTarget,
    )

    /** Deferred: the actual destroy needs the render thread and a frame that has no blit pending. */
    fun requestFree() {
        freeRequested = true
    }

    fun onWorldChange() {
        freeRequested = true
    }

    /** Called from the client tick, outside any frame, when the previews are not wanted. */
    fun releaseNow() {
        freeFeedsNow()
    }

    /** The debug HUD line, or null when there is nothing to say. */
    fun costLine(): String? {
        if (!PovPreviews.showCost || !PovPreviews.wants()) return null
        val live = (0 until FEEDS).count { !empty[it] && !outOfRange[it] }
        return "§7POV §f${"%.1f".format(lastCostMs)}§7ms  §f$live§7/4" +
            (if (SodiumBridge.available && PovPreviews.recullTerrain) " §8recull" else "")
    }

    // --------------------------------------------------------------- GUI (extract phase)

    /**
     * Called from `PovPreviews`' `ScreenEvent.Render` handler, which is registered above Odin's
     * `CustomGUIImpl` priority so everything Odin draws in the leap menu lands on top of us.
     *
     * This is the only place feeds are created, resized or destroyed: a blit submitted here holds
     * the feed's `GpuTextureView` until the GUI pass at the end of the frame draws it.
     */
    fun onScreenExtract(gfx: GuiGraphicsExtractor) {
        blitsSubmitted = 0
        if (disabledForSession) return
        val mc = EngineerClient.mc
        if (!PovPreviews.wants() || mc.level == null || mc.player == null) {
            freeFeedsNow()
            return
        }
        val main = mc.mainRenderTarget
        val scale = PovPreviews.resolution
        val width = ((main.width / 2) * scale).toInt()
        val height = ((main.height / 2) * scale).toInt()
        // Below this the frame graph's own targets start rounding to nothing; not worth guarding
        // further, the leap menu is never open on a window that small.
        if (width < 16 || height < 16) return

        ensureFeeds(width, height)
        for (index in 0 until FEEDS) {
            val slot = DungeonUtils.leapTeammates.getOrNull(index)
            empty[index] = slot == null || slot.name == "Empty"
        }
        submitBlits(gfx)
        redrawKeptHuds(gfx)
    }

    private fun submitBlits(gfx: GuiGraphicsExtractor) {
        val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
        val guiWidth = gfx.guiWidth()
        val guiHeight = gfx.guiHeight()
        val pose = Matrix3x2f(gfx.pose())
        val opacity = PovPreviews.opacity
        val opaque = opacity >= 1f
        // Opaque stays on the no-blend pipeline (see below). Translucent has to blend, so it uses
        // GUI_TEXTURED with the opacity as the vertex tint's alpha - the catch being that fragments
        // the level pass left at alpha zero (mostly open sky) come out fully see-through rather than
        // at [opacity]. Indoors, which is nearly every leap, that's no difference at all.
        val pipeline = if (opaque) RenderPipelines.GUI_OPAQUE_TEXTURED_BACKGROUND else RenderPipelines.GUI_TEXTURED
        val tint = if (opaque) -1 else ((opacity * 255f).toInt().coerceIn(0, 255) shl 24) or 0xFFFFFF
        for (index in 0 until FEEDS) {
            if (empty[index]) continue
            val view = feeds[index]?.colorTextureView ?: continue
            val column = index % 2
            val row = index / 2
            val x0 = if (column == 0) 0 else guiWidth / 2
            val x1 = if (column == 0) guiWidth / 2 else guiWidth
            val y0 = if (row == 0) 0 else guiHeight / 2
            val y1 = if (row == 0) guiHeight / 2 else guiHeight
            gfx.guiRenderState.addGuiElement(
                BlitRenderState(
                    // Opaque, not GUI_TEXTURED: the level pass clears its target to alpha ZERO
                    // (`LevelRenderer` clear pass), so anything that blends on source alpha would
                    // drop the sky and every other fragment that did not write alpha.
                    pipeline,
                    TextureSetup.singleTexture(view, sampler),
                    pose,
                    x0, y0, x1, y1,
                    // v is flipped: texture row 0 is the BOTTOM of a render target's image.
                    0f, 1f, 1f, 0f,
                    tint,
                    null,
                )
            )
            blitsSubmitted++
        }
    }

    /**
     * Puts the HUDs that must stay readable over a preview back on top of it.
     *
     * Odin draws its HUDs from a `HudElementRegistry` element in the HUD phase, which is *before*
     * the screen phase we are in, so a preview covers them. Rather than move the previews down
     * (they have to be above the vanilla hotbar and scoreboard), the few that matter mid-fight are
     * simply submitted a second time here, under the same 1/guiScale pose `ModuleManager.render`
     * uses. Drawing a HUD element twice in one frame is safe: `HudElement.draw` only renders and
     * records its own width/height.
     */
    private fun redrawKeptHuds(gfx: GuiGraphicsExtractor) {
        val keep = PovPreviews.keptHudNames()
        if (keep.isEmpty()) return
        val scale = EngineerClient.mc.window.guiScale.toFloat()
        if (scale <= 0f) return
        gfx.pose().pushMatrix()
        gfx.pose().scale(1f / scale, 1f / scale)
        try {
            for (hud in ModuleManager.hudSettingsCache) {
                if (!hud.isEnabled || hud.name !in keep) continue
                EngineerClient.safely("pov keep hud ${hud.name}") { hud.value.draw(gfx, false) }
            }
        } finally {
            gfx.pose().popMatrix()
        }
    }

    // --------------------------------------------------------------- render phase

    /**
     * HEAD of `GameRenderer.renderLevel`. Returns true when the caller should cancel the main
     * world render: with "Skip Own View" on the four previews tile the screen, so drawing your own
     * view underneath them is work nobody sees. Resuming costs nothing — there is no state to
     * rebuild, the next frame simply does not take this path.
     */
    fun beforeLevelRender(deltaTracker: DeltaTracker): Boolean {
        // The nested preview renders come back through here; let them straight through.
        if (capturing || disabledForSession) return false
        // This is the one outer `renderLevel` of the frame, so it is also where the flag that
        // tells the RETURN injector "the passes already ran" is cleared. Doing it here rather
        // than in `afterLevelRender` matters because a cancelled call may never reach RETURN.
        skippedMainPass = false
        if (!PovPreviews.skipOwnView || PovPreviews.opacity < 1f || !canCapture()) return false
        // Only when every quadrant has an image to show: otherwise cancelling would leave bare
        // black where a quadrant has no teammate.
        if (blitsSubmitted < FEEDS) return false
        if (runPasses(deltaTracker) == 0) return false

        // Nothing drew into the real target this frame. `GameRenderer.render` cleared it on the
        // way in, but clear it again here so a rounding gap between the gui-scaled quadrants and
        // the framebuffer cannot show a stale frame.
        val main = EngineerClient.mc.mainRenderTarget
        val color = main.colorTexture
        val depth = main.depthTexture
        if (color != null && depth != null) {
            RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(color, 0xFF000000.toInt(), depth, 1.0)
        }
        blitsSubmitted = 0
        skippedMainPass = true
        return true
    }

    /**
     * RETURN of `GameRenderer.renderLevel`: the main image exists and the GUI has not been drawn.
     * This is the path when "Skip Own View" is off, or when it declined the frame.
     */
    fun afterLevelRender(deltaTracker: DeltaTracker) {
        if (capturing || disabledForSession) return
        if (skippedMainPass) {
            // The HEAD injector cancelled this call; the passes already ran.
            skippedMainPass = false
            return
        }
        if (blitsSubmitted == 0 || !canCapture()) return
        blitsSubmitted = 0
        runPasses(deltaTracker)
    }

    private fun canCapture(): Boolean {
        val mc = EngineerClient.mc
        return PovPreviews.wants() && mc.level != null && mc.player != null && feeds[0] != null
    }

    /** Renders this frame's share of the feeds. Returns how many passes ran. */
    private fun runPasses(deltaTracker: DeltaTracker): Int {
        val started = System.nanoTime()
        var ran = 0
        capturing = true
        try {
            repeat(PovPreviews.previewsPerFrame.coerceIn(1, FEEDS)) {
                renderFeed(nextFeed, deltaTracker)
                nextFeed = (nextFeed + 1) % FEEDS
                ran++
            }
            restoreMainCamera(deltaTracker)
            EntityCullingBridge.requestRecull()
        } catch (t: Throwable) {
            // The per-pass `finally` has already put the client's state back; all that is left is
            // to stop doing this for the session and say so once.
            fail(t)
            EngineerClient.safely("pov camera restore") { restoreMainCamera(deltaTracker) }
        } finally {
            capturing = false
        }
        lastCostMs = (System.nanoTime() - started) / 1_000_000.0
        return ran
    }

    private const val OUT_OF_RANGE_ARGB = 0xFF141414.toInt()

    private fun renderFeed(index: Int, deltaTracker: DeltaTracker) {
        val mc = EngineerClient.mc
        if (empty[index]) return
        val feed = feeds[index] ?: return
        val slot = DungeonUtils.leapTeammates.getOrNull(index) ?: return

        val target: Player? = slot.entity ?: findByName(slot.name)
        if (target == null || target === mc.player || !target.isAlive) {
            // Dead, out of entity range, or not on this client at all: a flat dark quadrant, which
            // reads as "no feed" where a stale image — or a view of your own surroundings — lies.
            if (!outOfRange[index]) {
                outOfRange[index] = true
                feed.colorTexture?.let { RenderSystem.getDevice().createCommandEncoder().clearColorTexture(it, OUT_OF_RANGE_ARGB) }
            }
            return
        }
        outOfRange[index] = false

        val gameRenderer = mc.gameRenderer
        val invoker = gameRenderer as GameRendererInvoker
        val camera = gameRenderer.mainCamera
        val cameraAccess = camera as CameraAccessor
        val window = mc.window
        val options = mc.options

        val saved = Saved(
            cameraEntity = mc.cameraEntity,
            windowWidth = window.width,
            windowHeight = window.height,
            cameraType = options.cameraType,
            hideGui = options.hideGui,
            eyeHeight = cameraAccess.`ec$getEyeHeight`(),
            eyeHeightOld = cameraAccess.`ec$getEyeHeightOld`(),
            target = mc.mainRenderTarget,
        )
        var pose: PovPose.Restore? = null
        var cullingWas: Boolean? = null

        try {
            val worldPartialTicks = deltaTracker.getGameTimeDeltaPartialTick(false)
            pose = PovPose.begin(target, PovPreviews.mode, PovPreviews.smoothingTicks, worldPartialTicks)

            mc.setCameraEntity(target)
            options.cameraType = CameraType.FIRST_PERSON
            options.hideGui = true
            window.setWidth(feed.width)
            window.setHeight(feed.height)
            // Camera.tick() is the only thing that maintains the eye height, and ticking the
            // shared camera from another player's position would also rewrite its environment
            // probe and so the real view's fog and cloud colour.
            cameraAccess.`ec$setEyeHeight`(target.eyeHeight)
            cameraAccess.`ec$setEyeHeightOld`(target.eyeHeight)
            (mc as MinecraftAccessor).`ec$setMainRenderTarget`(feed)

            camera.update(deltaTracker)
            if (PovPreviews.recullTerrain) SodiumBridge.recull(camera)
            // EntityCulling's verdicts were raytraced from YOUR camera and are consumed inside
            // extractLevel, so the flip has to bracket the extract, not the draw.
            cullingWas = EntityCullingBridge.disable()

            invoker.`ec$extractWindow`()
            invoker.`ec$extractOptions`()
            invoker.`ec$extractCamera`(deltaTracker, worldPartialTicks, camera.getCameraEntityPartialTicks(deltaTracker))
            // extractLevel appends to the render-state lists; only renderLevel resets them. On the
            // Skip Own View path the main pass never ran, so the main extract's entities would
            // otherwise be drawn a second time in the first preview.
            gameRenderer.gameRenderState.levelRenderState.reset()
            mc.levelRenderer.extractLevel(deltaTracker, camera, worldPartialTicks)

            gameRenderer.renderLevel(deltaTracker)
        } finally {
            (mc as MinecraftAccessor).`ec$setMainRenderTarget`(saved.target)
            window.setWidth(saved.windowWidth)
            window.setHeight(saved.windowHeight)
            options.cameraType = saved.cameraType
            options.hideGui = saved.hideGui
            cameraAccess.`ec$setEyeHeight`(saved.eyeHeight)
            cameraAccess.`ec$setEyeHeightOld`(saved.eyeHeightOld)
            mc.setCameraEntity(saved.cameraEntity)
            pose?.restore()
            EntityCullingBridge.restore(cullingWas)
        }
    }

    /**
     * Leaves the shared camera and the window/option/camera render state on the real player again.
     * The GUI pass has not run yet and plenty of mods read `gameRenderer.mainCamera` from it.
     */
    private fun restoreMainCamera(deltaTracker: DeltaTracker) {
        val mc = EngineerClient.mc
        if (mc.level == null || mc.player == null) return
        val gameRenderer = mc.gameRenderer
        val camera = gameRenderer.mainCamera
        val invoker = gameRenderer as GameRendererInvoker
        camera.update(deltaTracker)
        val worldPartialTicks = deltaTracker.getGameTimeDeltaPartialTick(false)
        invoker.`ec$extractWindow`()
        invoker.`ec$extractOptions`()
        invoker.`ec$extractCamera`(deltaTracker, worldPartialTicks, camera.getCameraEntityPartialTicks(deltaTracker))
    }

    // --------------------------------------------------------------- feeds

    private fun ensureFeeds(width: Int, height: Int) {
        if (!freeRequested && feedWidth == width && feedHeight == height && feeds.all { it != null }) return
        freeFeedsNow()
        feedWidth = width
        feedHeight = height
        for (index in 0 until FEEDS) {
            val feed = TextureTarget("ec:pov$index", width, height, true)
            // A fresh target's contents are undefined; make the first frame a dark quadrant rather
            // than whatever was last in that piece of VRAM.
            feed.colorTexture?.let { RenderSystem.getDevice().createCommandEncoder().clearColorTexture(it, OUT_OF_RANGE_ARGB) }
            feeds[index] = feed
        }
        outOfRange.fill(false)
        nextFeed = 0
    }

    private fun freeFeedsNow() {
        freeRequested = false
        if (feeds.all { it == null }) return
        for (index in 0 until FEEDS) {
            feeds[index]?.destroyBuffers()
            feeds[index] = null
        }
        feedWidth = 0
        feedHeight = 0
        nextFeed = 0
    }

    // --------------------------------------------------------------- misc

    private fun findByName(name: String): Player? =
        EngineerClient.mc.level?.players()?.firstOrNull { it.name.string.equals(name, ignoreCase = true) }

    private fun fail(t: Throwable) {
        disabledForSession = true
        freeRequested = true
        EngineerClient.logger.error("[ec] pov preview failed — disabled for this session", t)
        EcLog.log("WARN", "pov preview failed: ${t.javaClass.simpleName}: ${t.message}")
        EngineerClient.chat("§8[§6EC§8]§c POV previews hit an error and are off for this session §7(see the log).")
    }
}
