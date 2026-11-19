package com.adm.features.impl.render

import com.google.gson.annotations.SerializedName
import com.mojang.authlib.GameProfile
import com.mojang.blaze3d.vertex.PoseStack
import com.adm.AdmMod
import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.*
import com.adm.features.Module
import com.adm.utils.network.WebUtils.fetchJson
import kotlinx.coroutines.launch
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.world.phys.Vec3
import java.util.*
import kotlin.math.abs

object PlayerSize : Module(
    name = "Player Size",
    description = "Changes the size of the player."
) {
    private val sizeX by NumberSetting("Size X", 1f, -1.0..3.0, 0.1, desc = "X scale of your client side size.")
    private val sizeY by NumberSetting("Size Y", 1f, -1.0..3.0, 0.1, desc = "Y scale of your client side size.")
    private val sizeZ by NumberSetting("Size Z", 1f, -1.0..3.0, 0.1, desc = "Z scale of your client side size.")
    private val customSizes by BooleanSetting("Show Custom Sizes", true, desc = "Other players' custom sizes.")

    private const val COSMETICS_URL = "https://api.noamm.org/cosmeticPeople.json"

    private data class CustomSize(val sizeX: Float, val sizeY: Float, val sizeZ: Float)
    @Volatile private var customSizeById: Map<UUID, CustomSize> = emptyMap()

    @JvmStatic
    fun preRenderCallbackScaleHook(state: AvatarRenderState, matrix: PoseStack) {
        if (!enabled) return
        val id = state.getData(GAME_PROFILE_KEY)?.id ?: return
        val x: Float
        val y: Float
        val z: Float
        if (id == mc.player?.gameProfile?.id) {
            x = sizeX; y = sizeY; z = sizeZ
        } else {
            if (!customSizes) return
            refreshCustomSizes()
            val custom = customSizeById[id] ?: return
            x = custom.sizeX; y = custom.sizeY; z = custom.sizeZ
        }
        if (x == 1f && y == 1f && z == 1f) return
        if (y < 0) matrix.translate(0f, y * 2, 0f)
        matrix.scale(x, y, z)
        // The nametag follows the head.
        state.nameTagAttachment?.let { state.nameTagAttachment = Vec3(it.x, (it.y + 0.15) * abs(y), it.z) }
    }

    private suspend fun loadCustomSizes() {
        val data = fetchJson<com.google.gson.JsonObject>(COSMETICS_URL).getOrNull() ?: return
        fun com.google.gson.JsonObject.size(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asFloat ?: 1f
        customSizeById = data.entrySet().mapNotNull { (k, v) ->
            val o = v as? com.google.gson.JsonObject ?: return@mapNotNull null
            val size = CustomSize(o.size("sizeX"), o.size("sizeY"), o.size("sizeZ"))
            if (size.sizeX == 1f && size.sizeY == 1f && size.sizeZ == 1f) return@mapNotNull null
            runCatching { UUID.fromString(k) }.getOrNull()?.let { it to size }
        }.toMap()
    }

    private const val REFRESH_MS = 30 * 60_000L
    private var fetchedAt = 0L

    // Fetched only once another player is drawn with Show Custom Sizes on, then every half hour.
    private fun refreshCustomSizes() {
        val now = System.currentTimeMillis()
        if (fetchedAt != 0L && now - fetchedAt < REFRESH_MS) return
        fetchedAt = now
        AdmMod.scope.launch { loadCustomSizes() }
    }

    @JvmStatic
    val GAME_PROFILE_KEY: RenderStateDataKey<GameProfile> = RenderStateDataKey.create { "engineerclient:game_profile" }
}