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

/**
 * Small server-side helpers for the sim (server thread only).
 *
 * In a party ([P3Party]) everyone plays the same fight: whatever one player does (a click, an item, a terminal, a
 * menu choice) runs as theirs ([asPlayer]): [player] and [me] are them, and what it says goes to them alone, as
 * Hypixel's own lines do. The fight's own ticks and [everyone] speak to the whole party.
 */
object Sim {
    /** Every entity the sim spawns carries this tag, so a reset (or the next start) can clear them. */
    const val TAG = "p3sim"

    val level: ServerLevel get() = SimServer.level ?: error("sim not running")

    /**
     * The player this is about: whoever's click, item, packet or menu choice is being handled ([asPlayer]), else
     * (the fight's own ticks, the client thread) the host, whose game it is.
     */
    val player: ServerPlayer? get() = acting ?: SimServer.player

    /** The player set by [asPlayer], on the sim server's thread only (null: none, the fight's own). */
    val acting: ServerPlayer? get() = actor?.takeIf { SimServer.server?.isSameThread == true }
    private var actor: ServerPlayer? = null

    /** Inside [everyone]: what's said goes to the whole party, even from one player's action. */
    private var all = false
    /** Whether [everyone] is in force (what's queued from it keeps it). */
    val toAll: Boolean get() = all && SimServer.server?.isSameThread == true

    /** Everyone in the sim, the host first. */
    fun players(): List<ServerPlayer> = SimServer.server?.playerList?.players?.toList().orEmpty()

    /** The host: the player whose game it is (the first in), whoever [player] is now. */
    val host: ServerPlayer? get() = SimServer.player

    fun isHost(p: ServerPlayer?) = p != null && p === SimServer.player

    /** Runs [run] as [p]'s: [player], [me], chat, titles and sounds are theirs, and so is what [run] queues ([Fight.later]). */
    fun <T> asPlayer(p: ServerPlayer?, toAll: Boolean = false, run: () -> T): T {
        val was = actor; val wasAll = all
        actor = p; all = toAll
        try { return run() } finally { actor = was; all = wasAll }
    }

    /**
     * SimActorMixin: a packet from [p] is being handled on the sim's server (a click in a window, a block hit, a use):
     * it acts as theirs until [leavePacket]. [Fight.tick] clears whatever a failed handler left.
     */
    @JvmStatic fun enterPacket(p: ServerPlayer) {
        val s = SimServer.server ?: return
        if (!s.isSameThread || p.level().server !== s) return
        actor = p; all = false
    }

    @JvmStatic fun leavePacket(p: ServerPlayer) { if (actor === p && SimServer.server?.isSameThread == true) actor = null }

    /** Back to the fight's own (the head of each tick). */
    fun clearActor() { actor = null; all = false }

    /** Runs [run] once for each player in the sim, as theirs ([asPlayer]). */
    fun forEachPlayer(run: (ServerPlayer) -> Unit) { for (p in players()) asPlayer(p) { run(p) } }

    /** Runs [run] with what it says going to everyone (a progress line, a boss line, a gate), whoever caused it. */
    fun <T> everyone(run: () -> T): T {
        val wasAll = all
        all = true
        try { return run() } finally { all = wasAll }
    }

    /** Who hears a line: the acting player alone, or (the fight's own, or [everyone]) the whole party. */
    private fun audience(): List<ServerPlayer> {
        val a = acting
        return if (a != null && !toAll) listOf(a) else players()
    }

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

    /** The host's name: the stats, ghost runs and run recordings are the host's. */
    val hostName: String get() = host?.gameProfile?.name ?: "You"

    /** Whether [name] (a station's doer, say) is a player in the sim, not a bot. */
    fun isPlayerName(name: String?) = name != null && players().any { it.gameProfile.name == name }

    /** A chat line as Hypixel sends it: plain system chat, `§` codes and all (Odin reads these). */
    fun chat(text: String) {
        val to = audience()
        if (to.any { isHost(it) }) Recorder.event(text)
        to.forEach { it.sendSystemMessage(Component.literal(text)) }
    }

    /** A chat line as a styled component (no `§` left in its text), the way Hypixel sends most of its lines. */
    fun chatStyled(text: String) {
        val to = audience()
        if (to.any { isHost(it) }) Recorder.event(text)
        to.forEach { it.sendSystemMessage(legacy(text)) }
    }

    /** A line to everyone but the acting player (what the others see of someone's death, say). */
    fun chatOthers(text: String) {
        val a = acting ?: return
        for (p in players()) if (p !== a) {
            if (isHost(p)) Recorder.event(text)
            p.sendSystemMessage(Component.literal(text))
        }
    }

    /** A line from the sim itself (not something Hypixel says): marked so it can't be mistaken. */
    fun note(text: String) = chat("§8[§6P3 Sim Beta§8] §7$text")

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

    /** A `[BOSS]` line, to everyone, with the wither.ambient (5, 1.19) Hypixel plays on every one (at the boss when [at] is given). */
    fun boss(name: String, line: String, at: Vec3? = null, stand: Boolean = true) = everyone {
        chat("§4[BOSS] $name§r§c: $line")
        if (stand) BossWither.speak(name, line)
        // At the boss: Hypixel plays it within a block of him, not at the player.
        sound(net.minecraft.sounds.SoundEvents.WITHER_AMBIENT, 5f, 1.19f, at)
    }

    fun title(title: String, sub: String = "", fadeIn: Int = 0, stay: Int = 30, fadeOut: Int = 5) {
        val to = audience()
        if (to.any { isHost(it) }) Recorder.event("title: $title | $sub")
        for (p in to) {
            // Hypixel's order: times, title, subtitle.
            p.connection.send(ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut))
            p.connection.send(ClientboundSetTitleTextPacket(legacy(title)))
            p.connection.send(ClientboundSetSubtitleTextPacket(legacy(sub)))
        }
    }

    /**
     * A sound: at [at] in the world (heard there by whoever is near), or with no [at] at the player it's for: the
     * acting player alone, or (the fight's own, [everyone]) each player at their own position.
     */
    fun sound(sound: SoundEvent, volume: Float = 1f, pitch: Float = 1f, at: Vec3? = null, source: SoundSource = SoundSource.MASTER) {
        if (at != null) { level.playSound(null, at.x, at.y, at.z, sound, source, volume, pitch); return }
        val holder = net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound)
        val seed = level.random.nextLong()
        for (p in audience()) p.connection.send(net.minecraft.network.protocol.game.ClientboundSoundPacket(holder, source, p.x, p.y, p.z, volume, pitch, seed))
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

/**
 * One value per player (keyed by [Sim.player]'s UUID): an item's cooldown, a mask's timer, a terminal's click
 * window, so each player in a party has their own. Alone it is one value, as before.
 */
class PerPlayer<T>(private val init: () -> T) : kotlin.properties.ReadWriteProperty<Any?, T> {
    private class Box<T>(var v: T)
    private val map = java.util.concurrent.ConcurrentHashMap<java.util.UUID, Box<T>>()
    init { all += this }
    private fun box(): Box<T> = map.getOrPut(Sim.player?.uuid ?: NOBODY) { Box(init()) }
    override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): T = box().v
    override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: T) { box().v = value }
    /** Every player's value, as it stands. */
    fun values(): List<T> = map.values.map { it.v }
    companion object {
        private val NOBODY = java.util.UUID(0, 0)
        private val all = java.util.concurrent.CopyOnWriteArrayList<PerPlayer<*>>()
        /** Everyone's values back to new (a new sim). */
        fun clearAll() = all.forEach { it.map.clear() }
    }
}

fun <T> perPlayer(init: () -> T) = PerPlayer(init)
