package com.coffeeclient.misc

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.EntityEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.features.impl.boss.TerminalSounds
import com.odtheking.odin.utils.playSoundAtPlayer
import com.odtheking.odin.utils.skyblock.dungeon.terminals.TerminalUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.equalsOneOf
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.M7Phases
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.Vec3

/** Small independent toggles that don't warrant their own module. */
object RandomStuff : Module(
    name = "Random Stuff",
    key = null,
    category = Category.custom("Coffee Client", 860, 10),
    description = "A collection of small unrelated QoL toggles."
) {
    private val hideDamage by BooleanSetting("Hide Damage Indicators", false, desc = "Suppresses the red hurt-flash overlay when you take damage.")
    private val hideArmorStands by BooleanSetting("Hide Armor Stands", false, desc = "In dungeons only: hides every armor stand (except terminals, active or inactive) and removes fishing bobbers' extended line.")

    private val muteDing by BooleanSetting("Mute Completion Ding", true, desc = "In P3, mutes the ding that plays every time anyone completes a terminal, lever or device (Hypixel's pling with each \"activated a terminal!\" message). Odin's own Terminal Sounds still play.")
    private val keepGateDing by BooleanSetting("Keep Gate & Core Ding", true, desc = "Still plays the ding for \"The gate has been destroyed!\" and \"The Core entrance is opening!\".").withDependency { muteDing }
    private val ding = CompletionDing()
    private val mutePartyInBoss by BooleanSetting("Mute Party Chat In Boss", true, desc = "In a dungeon boss, party chat messages make no sound (Hypixel's chat ping). Guild and private messages still do.")
    private val partyPing = PartyPing()

    /** Off: the sidebar is the game's own, where the game puts it, whatever the Scoreboard HUD says. */
    private val movableScoreboard by BooleanSetting("Movable Scoreboard", true, desc = "The sidebar drawn by the Scoreboard HUD, where you put it and at its scale. Off: the normal scoreboard, where the game puts it.")

    /** The sidebar where you put it: vanilla's own drawing, moved (ScoreboardMove). Off: where vanilla puts it. */
    private val scoreboardHud by HUD("Scoreboard", "Moves and scales the sidebar scoreboard. Off: it stays where the game puts it.", false, 400, 100, 1f) { example ->
        if (!movableScoreboard) return@HUD 0 to 0
        val size = ScoreboardMove.draw(this)
        if (example && size.first == 0) {
            fill(0, 0, 80, 60, 0x66000000)
            text(mc.font, "Scoreboard", 18, 2, -1, false)
            return@HUD 80 to 60
        }
        size
    }

    // --- Hide Armor Stands ----------------------------------------------------------------------

    private val STARRED_TAG = Regex("^.*✯ .*\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?.?❤$")

    /** Stands spawned but not judged yet: their name and equipment may still be on the way. */
    private val pendingArmorStands = mutableSetOf<ArmorStand>()

    /**
     * Where the "Wither Key"/"Blood Key" stands are. The key you see spinning is a second, unnamed
     * stand in the same place, so it can only be kept by where it is.
     */
    private val keyPositions = mutableListOf<Vec3>()
    private const val KEY_RADIUS = 1.5

    /** Each tick: every queued stand is kept, removed, or (under half a second old) left to wait. */
    private fun resolveArmorStands() {
        if (pendingArmorStands.isEmpty()) return
        pendingArmorStands.removeIf { stand ->
            when {
                !stand.isAlive -> true
                isKeptArmorStand(stand) -> true
                // A key's model stand can come through a tick or two after its named one.
                stand.tickCount < 10 -> false
                else -> { stand.remove(Entity.RemovalReason.DISCARDED); true }
            }
        }
    }

    /**
     * The stands that are never hidden: all of them in P3 (Goldor's terminals, active or not), a
     * starred mob's name tag, a Wither/Blood Key's named stand, and anything sitting on a key -
     * the key's visible model.
     */
    private fun isKeptArmorStand(stand: ArmorStand): Boolean {
        if (DungeonUtils.getF7Phase() == M7Phases.P3) return true
        val name = stand.name.string
        if (STARRED_TAG.matches(name)) return true
        if (name.equalsOneOf("Wither Key", "Blood Key")) {
            keyPositions.add(stand.position())
            return true
        }
        return keyPositions.any { stand.position().distanceTo(it) <= KEY_RADIUS }
    }

    /** A held chat ping that turned out not to be party chat's. */
    private fun replayPing(p: PartyPing.Ping) = mc.execute {
        mc.level?.playLocalSound(p.x, p.y, p.z, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1f, 1f, false)
    }

    init {
        // Completion ding (see CompletionDing): both packets on the network thread, in arrival order.
        onReceive<ClientboundSoundPacket> {
            if (muteDing && DungeonUtils.getF7Phase() == M7Phases.P3 &&
                ding.sound(sound.value() == SoundEvents.NOTE_BLOCK_PLING.value(), volume, pitch, System.currentTimeMillis())) it.cancel()
        }
        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (overlay || !muteDing || !keepGateDing || DungeonUtils.getF7Phase() != M7Phases.P3) return@onReceive
            if (content.string.replace(Regex("§."), "").trim() !in CompletionDing.KEPT) return@onReceive
            val replay = ding.keptMessage(System.currentTimeMillis()) ?: return@onReceive
            // Odin's Terminal Sounds already plays its own for these while you're in a terminal.
            if (TerminalSounds.enabled && TerminalSounds.clickSounds && TerminalUtils.currentTerm != null) return@onReceive
            playSoundAtPlayer(SoundEvents.NOTE_BLOCK_PLING.value(), replay.volume, replay.pitch)
        }
        // Party chat ping in boss (see PartyPing): the ping comes just before its line.
        onReceive<ClientboundSoundPacket> {
            if (!mutePartyInBoss || !DungeonUtils.inBoss) return@onReceive
            val isPing = sound.value() == SoundEvents.EXPERIENCE_ORB_PICKUP && source == SoundSource.PLAYERS && volume == 1f && pitch == 1f
            if (partyPing.sound(isPing, x, y, z, System.currentTimeMillis())) it.cancel()
        }
        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (overlay) return@onReceive
            partyPing.chat(PartyPing.isPartyLine(content.string.replace(Regex("§."), "").trim()), System.currentTimeMillis())?.let(::replayPing)
        }
        ClientTickEvents.END_CLIENT_TICK.register { partyPing.expired(System.currentTimeMillis())?.let(::replayPing) }

        on<TickEvent.End> {
            if (hideDamage) mc.player?.hurtTime = 0
            resolveArmorStands()
            ScoreboardMove.active = enabled && movableScoreboard && scoreboardHud.enabled
        }

        // Hide Armor Stands: a stand's name and equipment (what tells a key or a starred mob's tag
        // apart from decoration) arrive in packets a tick or two after the stand itself, so it is
        // queued here and judged in [resolveArmorStands], not removed on sight.
        on<EntityEvent.Add> {
            if (!hideArmorStands || !DungeonUtils.inDungeons) return@on
            when (entity.type) {
                EntityTypes.ARMOR_STAND -> pendingArmorStands.add(entity as ArmorStand)
                // The cheapest way to kill a fishing line stretched across the room: no bobber left
                // to draw it to. On the same toggle rather than a setting of its own.
                EntityTypes.FISHING_BOBBER -> entity.remove(Entity.RemovalReason.DISCARDED)
                else -> {}
            }
        }

        on<LevelEvent.Load> { pendingArmorStands.clear(); keyPositions.clear() }
    }
}
