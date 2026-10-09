package com.engineerclient.p3sim

import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Relative
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/** Small server-side helpers for the sim (server thread only). */
object Sim {
    /** Every entity the sim spawns carries this tag, so a reset (or the next start) can clear them. */
    const val TAG = "p3sim"

    val level: ServerLevel get() = SimServer.level ?: error("sim not running")
    val player: ServerPlayer? get() = SimServer.player
    private var guarded = false

    /**
     * Hypixel's "block protection": the server keeps the world as it is and the client may try anything.
     * A vanilla break or placement goes ahead on the client (its own prediction), the server refuses it
     * and sends the real block back (vanilla does that for a refused break or use). Only the sim's own
     * handlers (SimItems: Dungeonbreaker, levers, Superboom, devices) change blocks, through [Blocks].
     */
    fun guardBlocks() {
        if (guarded) return
        guarded = true
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register { level, _, _, _, _ -> !SimServer.isSimLevel(level) }
        // Registered after SimItems' hooks, so it only sees what they passed on (a block item's placement).
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register { _, level, _, _ ->
            if (SimServer.isSimLevel(level)) net.minecraft.world.InteractionResult.FAIL else net.minecraft.world.InteractionResult.PASS
        }
    }

    val me: String get() = player?.gameProfile?.name ?: "You"

    /** A chat line as Hypixel sends it: plain system chat, `§` codes and all (Odin reads these). */
    fun chat(text: String) {
        Recorder.event(text)
        player?.sendSystemMessage(Component.literal(text))
    }

    /** A chat line as a styled component (no `§` left in its text), the way Hypixel sends most of its lines. */
    fun chatStyled(text: String) {
        Recorder.event(text)
        player?.sendSystemMessage(legacy(text))
    }

    /** A line from the sim itself (not something Hypixel says): marked so it can't be mistaken. */
    fun note(text: String) = chat("§8[§6P3 Sim§8] §7$text")

    /** `§` text as a styled component (no codes left in its string), as Hypixel's names come. */
    fun legacy(text: String): Component {
        val out = Component.empty()
        var style = net.minecraft.network.chat.Style.EMPTY
        val sb = StringBuilder()
        var i = 0
        fun flush() { if (sb.isNotEmpty()) { out.append(Component.literal(sb.toString()).withStyle(style)); sb.clear() } }
        while (i < text.length) {
            val c = text[i]
            if (c == '§' && i + 1 < text.length) {
                val f = net.minecraft.ChatFormatting.getByCode(text[i + 1])
                if (f != null) {
                    flush()
                    style = if (f == net.minecraft.ChatFormatting.RESET) net.minecraft.network.chat.Style.EMPTY
                        else if (f.isColor) net.minecraft.network.chat.Style.EMPTY.withColor(f) else style.applyFormat(f)
                    i += 2; continue
                }
            }
            sb.append(c); i++
        }
        flush()
        return out
    }

    /** A `[BOSS]` line, with the wither.ambient (5, 1.19) Hypixel plays on every one (at the boss when [at] is given). */
    fun boss(name: String, line: String, at: Vec3? = null, stand: Boolean = true) {
        chat("§4[BOSS] $name§r§c: $line")
        if (stand) BossWither.speak(name, line)
        // At the boss: Hypixel plays it within a block of him, not at the player.
        sound(net.minecraft.sounds.SoundEvents.WITHER_AMBIENT, 5f, 1.19f, at)
    }

    fun title(title: String, sub: String = "", fadeIn: Int = 0, stay: Int = 30, fadeOut: Int = 5) {
        Recorder.event("title: $title | $sub")
        val p = player ?: return
        // Hypixel's order: times, title, subtitle.
        p.connection.send(ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut))
        p.connection.send(ClientboundSetTitleTextPacket(legacy(title)))
        p.connection.send(ClientboundSetSubtitleTextPacket(legacy(sub)))
    }

    fun sound(sound: SoundEvent, volume: Float = 1f, pitch: Float = 1f, at: Vec3? = null, source: SoundSource = SoundSource.MASTER) {
        val p = player ?: return
        val pos = at ?: p.position()
        level.playSound(null, pos.x, pos.y, pos.z, sound, source, volume, pitch)
    }

    fun sound(sound: Holder<SoundEvent>, volume: Float = 1f, pitch: Float = 1f, at: Vec3? = null, source: SoundSource = SoundSource.MASTER) = sound(sound.value(), volume, pitch, at, source)

    fun <T : Entity> spawn(e: T): T {
        e.addTag(TAG)
        level.addFreshEntity(e)
        return e
    }

    /** Removes every entity the sim made (including ones saved with the world last time). */
    fun clearEntities() {
        val l = level
        val all = ArrayList<Entity>()
        l.allEntities.forEach { if (it.entityTags().contains(TAG)) all += it }
        all.forEach { it.discard() }
    }

    fun tp(p: ServerPlayer, x: Double, y: Double, z: Double, yaw: Float? = null, pitch: Float? = null) {
        p.teleportTo(level, x, y, z, emptySet<Relative>(), yaw ?: p.yRot, pitch ?: p.xRot, false)
        p.deltaMovement = Vec3.ZERO
        p.fallDistance = 0.0
    }

    fun box(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double) = AABB(x0, y0, z0, x1, y1, z1)

    fun pos(x: Int, y: Int, z: Int) = BlockPos(x, y, z)

    /** Runs a server command quietly as the server (time, effects and the like). */
    fun command(cmd: String) {
        val s = SimServer.server ?: return
        s.commands.performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), cmd)
    }
}
