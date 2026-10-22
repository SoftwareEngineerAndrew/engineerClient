package com.engineerclient.p3sim.clear.blood

import com.engineerclient.p3sim.pack.rooms.SkullTexture
import java.util.Base64
import java.util.UUID

/**
 * Turns the key a camp gives `BloodCampHost.setHead` into a skull texture (pure, tested). A key is a texture id (hex, the part of a
 * textures.minecraft.net URL after the last slash) or a `role` word the rooms pack's texture table uses. [NONE] takes the head away.
 */
object HeadTextures {
    /** The key that removes a head from its slot. */
    const val NONE = "none"

    /** The key for a plain head with no texture. */
    const val PLAIN = "plain"

    /** A skull profile as Minecraft wants it: the `textures` property value (base64 JSON) and a profile id. */
    data class Resolved(val value: String, val profileId: UUID)

    private val hex = Regex("^[0-9a-fA-F]{16,64}$")

    /** The base64 `textures` value for a texture id: `{"textures":{"SKIN":{"url":"http://textures.minecraft.net/texture/<id>"}}}`. */
    fun valueFor(textureId: String): String {
        val json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/$textureId\"}}}"
        return Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    /**
     * The texture for [key]: the rooms pack's own entry when it has one (by id, then by role), else one built from the id. Null for
     * [NONE], [PLAIN] or a key that is neither an id nor a known role.
     */
    fun resolve(key: String, table: List<SkullTexture>): Resolved? {
        if (key == NONE || key == PLAIN || key.isBlank()) return null
        val entry = table.firstOrNull { it.id.equals(key, ignoreCase = true) } ?: table.firstOrNull { it.role == key }
        val value = entry?.value ?: if (hex.matches(key)) valueFor(key.lowercase()) else return null
        val id = entry?.profileId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: UUID.nameUUIDFromBytes("ads-head:$key".toByteArray(Charsets.UTF_8))
        return Resolved(value, id)
    }
}
