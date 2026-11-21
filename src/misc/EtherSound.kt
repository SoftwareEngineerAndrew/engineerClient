package com.engineerclient.misc

import com.adm.features.impl.render.Etherwarp
import com.adm.utils.playSoundAtPlayer
import com.adm.utils.playSoundSettings
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents

/**
 * 0 ping teleport sounds: a teleport's sound plays when the camera teleports, and the server's own one is swallowed when it
 * arrives for a teleport already heard. Etherwarp: ender dragon hurt at 34/63 (the sound ADM's Etherwarp module swaps for its
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

    // Filled on the game thread, swallowed on the network thread: every use holds this lock.
    private val owed = Server.entries.associateWith { ArrayDeque<Long>() }

    private fun owe(server: Server, at: Long) = synchronized(owed) { owed.getValue(server).addLast(at) }

    /** The camera teleported: play the sounds now. */
    fun played(kind: Kind = Kind.ETHER) {
        val now = System.currentTimeMillis()
        when (kind) {
            Kind.ETHER -> {
                try {
                    val core = if (coreSounds()) coreSettings() else null
                    if (core != null) playSoundSettings(core) else vanilla()
                } catch (_: Throwable) {
                    vanilla()
                }
                owe(Server.DRAGON, now)
            }
            Kind.AOTV -> {
                if (!com.engineerclient.visual.Sounds.mutesAotv()) playSoundAtPlayer(SoundEvents.ENDERMAN_TELEPORT, 1f, 1f)
                owe(Server.TELEPORT, now)
            }
            Kind.HYPE -> {
                playSoundAtPlayer(SoundEvents.ENDERMAN_TELEPORT, 1f, 1f)
                playSoundAtPlayer(SoundEvents.GENERIC_EXPLODE.value(), 1f, 1f)
                owe(Server.TELEPORT, now)
                owe(Server.EXPLODE, now)
            }
        }
    }

    private fun vanilla() = playSoundAtPlayer(SoundEvents.ENDER_DRAGON_HURT, 1f, PITCH)

    fun clear() = synchronized(owed) { owed.values.forEach { it.clear() } }

    /** A server sound arrived: true when a camera teleport already played it, so it's not played again. */
    @JvmStatic fun swallow(packet: ClientboundSoundPacket): Boolean {
        val server = Server.entries.firstOrNull { it.matches(packet) } ?: return false
        val now = System.currentTimeMillis()
        synchronized(owed) {
            val queue = owed.getValue(server)
            while (queue.isNotEmpty() && now - queue.first() > KEEP_MS) queue.removeFirst()
            return queue.removeFirstOrNull() != null
        }
    }

    // ADM's Etherwarp settings, read through its accessors (no public getters).
    private fun coreSounds(): Boolean =
        Etherwarp.enabled && Etherwarp::class.java.getMethod("access\$getSounds", Etherwarp::class.java).invoke(null, Etherwarp) as Boolean

    @Suppress("UNCHECKED_CAST")
    private fun coreSettings(): Triple<String, Float, Float>? =
        (Etherwarp::class.java.getMethod("access\$getSoundSettings\$p").invoke(null) as? () -> Any?)?.invoke() as? Triple<String, Float, Float>
}
