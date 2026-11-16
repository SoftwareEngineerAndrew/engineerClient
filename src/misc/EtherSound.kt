package com.engineerclient.misc

import com.adm.features.impl.render.Etherwarp
import com.adm.utils.playSoundAtPlayer
import com.adm.utils.playSoundSettings
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents

/**
 * 0 ping teleport sounds: a teleport's sound plays when the camera teleports, and the server's own one is swallowed when it
 * arrives for a teleport already heard. Etherwarp: ender dragon hurt at 34/63 (the sound Odin's Etherwarp module swaps for its
 * own). AOTE / AOTV: enderman teleport 1/1. Hyperion: that and the Implosion (explode 1/1). Each teleport the camera played is
 * owed its server sounds; one never coming (a refusal) is forgotten after [KEEP_MS].
 */
object EtherSound {
    private const val PITCH = 34f / 63f
    private const val KEEP_MS = 3000L

    enum class Kind { ETHER, AOTV, HYPE }

    private enum class Server(val matches: (ClientboundSoundPacket) -> Boolean) {
        DRAGON({ it.sound.value() == SoundEvents.ENDER_DRAGON_HURT && Math.abs(it.pitch - PITCH) <= 0.01f }),
        TELEPORT({ it.sound.value() == SoundEvents.ENDERMAN_TELEPORT && it.volume == 1f && it.pitch == 1f }),
        EXPLODE({ it.sound.value() == SoundEvents.GENERIC_EXPLODE.value() && it.volume == 1f && it.pitch == 1f }),
    }

    private val owed = Server.entries.associateWith { ArrayDeque<Long>() }

    /** The camera teleported: play the sounds now. */
    fun played(kind: Kind = Kind.ETHER) {
        val now = System.currentTimeMillis()
        when (kind) {
            Kind.ETHER -> {
                try {
                    val odin = if (odinSounds()) odinSettings() else null
                    if (odin != null) playSoundSettings(odin) else vanilla()
                } catch (_: Throwable) {
                    vanilla()
                }
                owed.getValue(Server.DRAGON).addLast(now)
            }
            Kind.AOTV -> {
                if (!com.engineerclient.visual.Sounds.mutesAotv()) playSoundAtPlayer(SoundEvents.ENDERMAN_TELEPORT, 1f, 1f)
                owed.getValue(Server.TELEPORT).addLast(now)
            }
            Kind.HYPE -> {
                playSoundAtPlayer(SoundEvents.ENDERMAN_TELEPORT, 1f, 1f)
                playSoundAtPlayer(SoundEvents.GENERIC_EXPLODE.value(), 1f, 1f)
                owed.getValue(Server.TELEPORT).addLast(now)
                owed.getValue(Server.EXPLODE).addLast(now)
            }
        }
    }

    private fun vanilla() = playSoundAtPlayer(SoundEvents.ENDER_DRAGON_HURT, 1f, PITCH)

    fun clear() = owed.values.forEach { it.clear() }

    /** A server sound arrived: true when a camera teleport already played it, so it's not played again. */
    @JvmStatic fun swallow(packet: ClientboundSoundPacket): Boolean {
        val server = Server.entries.firstOrNull { it.matches(packet) } ?: return false
        val queue = owed.getValue(server)
        val now = System.currentTimeMillis()
        while (queue.isNotEmpty() && now - queue.first() > KEEP_MS) queue.removeFirst()
        return queue.removeFirstOrNull() != null
    }

    // Odin's Etherwarp settings, read through its accessors (no public getters).
    private fun odinSounds(): Boolean =
        Etherwarp.enabled && Etherwarp::class.java.getMethod("access\$getSounds", Etherwarp::class.java).invoke(null, Etherwarp) as Boolean

    @Suppress("UNCHECKED_CAST")
    private fun odinSettings(): Triple<String, Float, Float>? =
        (Etherwarp::class.java.getMethod("access\$getSoundSettings\$p").invoke(null) as? () -> Any?)?.invoke() as? Triple<String, Float, Float>
}
