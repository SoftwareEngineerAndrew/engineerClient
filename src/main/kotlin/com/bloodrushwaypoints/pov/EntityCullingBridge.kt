package com.bloodrushwaypoints.pov

import net.fabricmc.loader.api.FabricLoader

/**
 * Switches EntityCulling off for the duration of a preview's extract.
 *
 * EntityCulling decides what is visible by raytracing every entity from YOUR camera on its own
 * thread and stamping the verdict onto the entity; `LevelRenderer.extractEntity` then returns an
 * invisible stub for anything marked culled. Those verdicts are meaningless for a camera somewhere
 * else in the room, so a preview would be missing exactly the players it exists to show. The public
 * static `EntityCullingVersionlessBase.enabled` is the whole mod's master switch — one field,
 * flipped around the extract and put straight back.
 *
 * Reflection, not a compile dependency: EntityCulling is expected on every BRW client but nothing
 * here may require it.
 */
object EntityCullingBridge {

    private const val VERSIONLESS = "dev.tr7zw.entityculling.versionless.EntityCullingVersionlessBase"
    private const val MOD_BASE = "dev.tr7zw.entityculling.EntityCullingModBase"

    private val present: Boolean = FabricLoader.getInstance().isModLoaded("entityculling")

    private val enabledField by lazy {
        if (!present) null
        else runCatching { Class.forName(VERSIONLESS).getField("enabled") }.getOrNull()
    }

    private val modBaseClass by lazy {
        if (!present) null else runCatching { Class.forName(MOD_BASE) }.getOrNull()
    }

    /** Returns the previous value, or null when EntityCulling is absent (nothing to restore). */
    fun disable(): Boolean? {
        val field = enabledField ?: return null
        return runCatching {
            val was = field.getBoolean(null)
            field.setBoolean(null, false)
            was
        }.getOrNull()
    }

    fun restore(previous: Boolean?) {
        val field = enabledField ?: return
        if (previous == null) return
        runCatching { field.setBoolean(null, previous) }
    }

    /**
     * Asks for a fresh set of verdicts from the real camera. The cull thread only re-evaluates
     * when the camera moved or this is set, and a frame of previews does not move your camera.
     */
    fun requestRecull() {
        val modClass = modBaseClass ?: return
        // The instance is resolved per call, not cached: the mod is constructed during its own
        // init and a cached null would never be retried.
        runCatching {
            val instance = modClass.getField("instance").get(null) ?: return
            val task = modClass.getField("cullTask").get(instance) ?: return
            task.javaClass.getField("requestCull").setBoolean(task, true)
        }
    }
}
