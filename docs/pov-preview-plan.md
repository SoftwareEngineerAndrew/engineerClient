# POV previews in the leap menu — design

Goal: while the Spirit Leap menu is open, each quarter of the screen shows the world rendered
from the eyes of the teammate Odin puts in that quadrant (top-left, top-right, bottom-left,
bottom-right = `DungeonUtils.leapTeammates[0..3]`). Odin's leap boxes, BRW's ring/dim and the
role HUD draw on top as today. A teammate whose entity is not loaded on this client (out of
entity range, dead, not in the party) gets an "out of range" quadrant.

Research briefs (facts with file:line, read them before touching code):
`scratchpad/POV_VANILLA_BRIEF.md` and `scratchpad/POV_SODIUM_BRIEF.md` under
`/tmp/claude-1000/-home-andrew-Cluade/92e72a56-e4cc-4181-89d1-d2ba61869e86/scratchpad/`
(also there: `mc-src/` decompiled 26.1.2 client, `sodium-src/` tag mc26.1.2-0.9.2-alpha.4,
`entityculling-src/` 1.10.5, `securitycraft-src/` precedent, `odin-src/` 0.3.2).

## Facts that fix the design (26.1.2)

- The frame is `GameRenderer.update()` → `extract()` → `render()`. All terrain culling
  (`LevelRenderer.update(Camera)`, ViewArea repositioning, occlusion graph) runs only in
  `update()`. A second `gameRenderer.extract(delta, true)` + `gameRenderer.renderLevel(delta)`
  is therefore a full second world pass (sky, terrain, entities, particles, block entities,
  waypoints from other mods) that never touches the chunk ring. This is the SecurityCraft
  camera-feed recipe (`securitycraft-src/.../entity/camera/FrameFeedHandler.java:62-208`).
- `LevelRenderer.renderLevel` re-reads `Minecraft.getMainRenderTarget()` each call and its
  first pass clears colour+depth of that target: swapping `Minecraft.mainRenderTarget`
  (private final → `@Mutable @Accessor`) to a quadrant-sized `TextureTarget(label, w, h, true)`
  redirects the whole pass. Restore afterwards.
- The camera is `gameRenderer.getMainCamera()`; there is no `Camera.setup`. Use
  `mc.setCameraEntity(target)`, set `options.cameraType = FIRST_PERSON`, resize the window
  (`window.setWidth/Height` to the target size, for aspect), then `camera.update(delta)`;
  `extract()` feeds that camera into every consumer. `LivingEntity.getViewYRot` is head yaw.
  Never call `camera.tick()`.
- Sodium replaces the terrain path. Its render lists are whatever the last
  `SodiumWorldRenderer.setupTerrain(camera, viewport, fog, spectator, immediate, cullMatrix)`
  produced, so without a re-cull the preview shows only sections visible from *your* frustum.
  A re-cull from the preview camera is public API: build a vanilla `Frustum(view, proj)`,
  `prepare(x,y,z)`, `((ViewportProvider) frustum).sodium$createViewport()`,
  `((FrustumAccessor) frustum).sodium$getMatrix()`. Re-culling also schedules builds for
  sections only the preview sees (they pop in over a few frames). Hazards (brief §2): no
  cached cull tree is reusable across cameras 4+ sections apart, and
  `AsyncCameraTimingControl` latches "sync" (distance+frustum only, no occlusion) while the
  camera keeps jumping ≥32 blocks between calls — i.e. while the menu is open. Accept it; it
  unlatches once the menu closes. Next frame's main `update()` restores the main lists.
- EntityCulling hides entities at `LevelRenderer.extractEntity` using verdicts raytraced from
  the player camera. Around the preview `extract()` set the public static
  `dev.tr7zw.entityculling.versionless.EntityCullingVersionlessBase.enabled = false`
  (reflection, soft dependency), restore after, then `cullTask.requestCull = true`.
- Blit: preview target is exactly the quadrant in framebuffer pixels
  (`main.width/2 × main.height/2`), so at RETURN of `GameRenderer.renderLevel`, with the real
  target restored, `RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(src,
  main.colorTexture, 0, dstX, dstY, 0, 0, w, h)`. No pipeline, no projection state.
- After the last pass: restore camera entity, window size, camera type, `mainRenderTarget`,
  then `mc.gui.extractRenderState(...)` and `gameRenderer.extractWindow()` (`@Invoker`) so the
  HUD/GUI pass sees the real frame. Recursion guard: the injector fires again inside the
  nested `renderLevel`.
- Remote-player interpolation: `entity.getInterpolation()` (`InterpolationHandler`) exposes
  the packet targets `position()/yRot()/xRot()`; head yaw target is
  `LivingEntity.lerpYHeadRot` (protected, accessor). Vanilla lerps over 3 ticks (~150 ms).

## Per-frame sequence (mixin at RETURN of `GameRenderer.renderLevel`, guarded)

```
if capturing or !PovPreviews.wants(): return
for each quadrant due this frame (round-robin, N per frame):
    target = leapTeammates[i].entity ?: mark quadrant "out of range"; continue
    save   = mc.cameraEntity, window w/h, options.cameraType, hideGui, mainRenderTarget
    pose   = PovPose.begin(target, mode, ticks)     // may rewrite the entity's lerp fields
    mc.setCameraEntity(target); cameraType = FIRST_PERSON; hideGui = true (no hand)
    window.setWidth/Height(feed.w, feed.h); mainRenderTarget = feed.target
    camera.update(delta)
    if sodium && recull: SodiumBridge.recull(camera, feed.w, feed.h, fov)
    entityCulling(false)
    gameRenderer.extract(delta, true); gameRenderer.renderLevel(delta)
    entityCulling(restore); pose.restore(); restore saved state
after loop: mc.gui.extractRenderState(...); gameRenderer.extractWindow()
copy every feed that has a valid image into its quadrant of the real main target
```

## Layering (user requirement, 2026-09-20)

The previews cover the vanilla HUD (hotbar, scoreboard, boss bar) and Odin's ordinary HUDs, and
sit UNDER: Odin's leap boxes (player names), Odin's Tick Timers HUDs, the Invincibility Timer
HUD, the Melody display and BRW's role HUD — each "keep on top" group a toggle. Because the HUD
phase is extracted before the screen, the blit is submitted from a handler that runs before
Odin's CustomGUIImpl `ScreenEvent.Render` handler (a BRW `Screen` mixin at HEAD, priority 1, or
an Odin subscription that beats HIGHEST), and the kept Odin HUDs are re-drawn on top right
after (`ModuleManager.hudSettingsCache` by name → `value.draw(gfx, false)` under 1/guiScale).
So the GUI-pass blit is the only route; the texture copy is out.

`Skip Own View` (default on): the main world render is cancelled at HEAD of
`GameRenderer.renderLevel` while previews are active — the quadrants cover the screen — and the
preview passes run from there instead; the real target is cleared first. Resuming costs
nothing (there is no render thread to re-create).

Round-robin: `Previews Per Frame` (1–4, default 1) quadrants render per frame; the other feeds
keep their last image. `Resolution` scales the feed (0.25–1.0 of the quadrant, default 0.5) —
when below 1.0 the copy must scale, so use the GUI-pass blit instead
(`GuiGraphicsExtractor.blit(GpuTextureView, GpuSampler, x0,y0,x1,y1, u0,u1, v0=1,v1=0)` with
`RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA`) drawn from BRW's `CustomGUIImpl` handler
*before* the ring/dim. Pick ONE route and keep it: the GUI blit handles both scaling and
alpha, so prefer it unless it proves to draw above Odin's boxes (then fall back to the copy at
resolution 1.0).

## Files (all new code under `src/main/kotlin/com/bloodrushwaypoints/pov/` and
`src/main/java/com/bloodrushwaypoints/mixin/`)

- `mixin/MinecraftAccessor.java` — `@Mutable @Accessor("mainRenderTarget")` setter.
- `mixin/GameRendererMixin.java` — `@Inject(method="renderLevel", at=@At("RETURN"))` →
  `PovCapture.INSTANCE.afterLevelRender(deltaTracker)`; `@Invoker("extractWindow")`.
- `mixin/LivingEntityAccessor.java` — `lerpYHeadRot`, `lerpHeadSteps`.
- `pov/PovPreviews.kt` — Odin `Module("POV Previews", Category "Blood Rush")` with settings:
  `Enabled` (module toggle), `Previews Per Frame` (1–4), `Resolution` (0.25–1.0),
  `Re-cull Terrain` (bool, default on; off = cheaper, shows only what you can see),
  `Head Smoothing` dropdown `Vanilla | Raw | Custom`, `Smoothing Ticks` (0–6, Custom only),
  `Show FPS Cost` debug line. `wants()` = enabled && leap screen open (same title check as
  `LeapHighlight.leapScreen()`) && `LeapMenu.enabled`.
- `pov/PovCapture.kt` — feeds (4 × `TextureTarget`, resized on window change, destroyed on
  disable), the sequence above, quadrant geometry, the copy/blit, the recursion guard, an
  "out of range" placeholder, and timing (ms per pass) for the debug line.
- `pov/SodiumBridge.kt` — only file that imports `net.caffeinemc.*`; `available` via
  `FabricLoader.getInstance().isModLoaded("sodium")`; `recull(camera, w, h, fovDeg)`.
- `pov/EntityCullingBridge.kt` — reflection on the static flag; no-op when absent.
- `pov/PovPose.kt` — see below.
- `SetupCheck` additions: graphics mode must not be Fabulous (transparency post-chain owns
  extra targets); note Sodium/EntityCulling presence.

## PovPose (interpolation option)

`PovPose.begin(entity: LivingEntity, mode, ticks, partialTick): Restore`
- `VANILLA`: no-op — `Camera.update(delta)` lerps `yHeadRotO→yHeadRot` etc. (smooth, ~3 ticks
  behind).
- `RAW`: write the packet targets into the entity for the pass: `xo=x=interp.position().x`
  (same y, z), `yHeadRotO=yHeadRot=lerpYHeadRot`, `xRotO=xRot=interp.xRot()`; `Restore` puts
  every field back. Lowest latency, 20 Hz steps.
- `CUSTOM(ticks)`: BRW keeps a per-player ring of packet targets stamped with
  `System.nanoTime()` (sampled every client tick from `getInterpolation()`/`lerpYHeadRot`,
  which is what the server last sent), and renders the pose `ticks × 50 ms` in the past by
  lerping between the two samples bracketing that time (rotations via shortest-arc). Writes
  the same fields as RAW. ticks=0 ≡ RAW; ticks=3 ≈ VANILLA but time-based rather than
  tick-stepped. The lerp maths is plain Kotlin with a headless unit test.

## Constraints

- Only ever runs on the render thread inside `GameRenderer.renderLevel`; never from a packet
  or tick handler.
- Nothing here changes what the rotation engine decides; it is display only.
- Team-mod scope: Sodium + EntityCulling are assumed present but every bridge no-ops without
  them (the BRW instance is the reference config).
- Every exception inside a pass is caught, the feature disables itself for the session with
  one chat line, and all saved state is restored in a `finally`.
- Version bump to 0.5.0; `./deploy.sh` after the build.
