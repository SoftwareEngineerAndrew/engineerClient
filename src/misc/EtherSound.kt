package com.engineerclient.misc

import com.odtheking.odin.features.impl.render.Etherwarp
import com.odtheking.odin.utils.playSoundAtPlayer
import com.odtheking.odin.utils.playSoundSettings
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents

/**
 * 0 Ping Etherwarp Sound: the etherwarp sound plays when the camera warps, and the server's own one (ender dragon hurt at
 * 34/63, the sound Odin's Etherwarp module swaps for its own) is swallowed when it arrives for a warp already heard.
 * Each warp the camera played is owed one server sound; one never coming (a refusal) is forgotten after [KEEP_MS].
 */
object EtherSound {
    private const val PITCH = 34f / 63f
    private const val KEEP_MS = 3000L
    private val owed = ArrayDeque<Long>()

    /** The camera etherwarped: play the sound now, as Odin's Etherwarp module would for the server's. */
    fun played() {
        try {
            if (odinSounds()) playSoundSettings(odinSettings() ?: return vanilla()) else vanilla()
        } catch (_: Throwable) {
            vanilla()
        }
        owed.addLast(System.currentTimeMillis())
    }

    private fun vanilla() = playSoundAtPlayer(SoundEvents.ENDER_DRAGON_HURT, 1f, PITCH)

    fun clear() = owed.clear()

    /** The server's etherwarp sound arrived: true when a camera warp already played it, so it's not played again. */
    @JvmStatic fun swallow(packet: ClientboundSoundPacket): Boolean {
        if (packet.sound.value() != SoundEvents.ENDER_DRAGON_HURT || Math.abs(packet.pitch - PITCH) > 0.01f) return false
        val now = System.currentTimeMillis()
        while (owed.isNotEmpty() && now - owed.first() > KEEP_MS) owed.removeFirst()
        return owed.removeFirstOrNull() != null
    }

    // Odin's Etherwarp settings, read through its accessors (no public getters).
    private fun odinSounds(): Boolean =
        Etherwarp.enabled && Etherwarp::class.java.getMethod("access\$getSounds", Etherwarp::class.java).invoke(null, Etherwarp) as Boolean

    @Suppress("UNCHECKED_CAST")
    private fun odinSettings(): Triple<String, Float, Float>? =
        (Etherwarp::class.java.getMethod("access\$getSoundSettings\$p").invoke(null) as? () -> Any?)?.invoke() as? Triple<String, Float, Float>
}
