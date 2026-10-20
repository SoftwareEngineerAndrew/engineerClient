package com.engineerclient.p3sim.clear.mobs

import com.engineerclient.p3sim.SimServer
import com.engineerclient.p3sim.pack.mobs.MobArchetype
import com.engineerclient.p3sim.pack.mobs.MobSkin
import com.google.common.collect.ImmutableMultimap
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.GameType
import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.scores.Scoreboard
import net.minecraft.world.scores.Team
import java.util.EnumSet
import java.util.UUID

/**
 * Everything a client needs to treat a body as a real fake player, the way Hypixel does it (FORMAT-mobs 3.2, gap-odin-seam 4.2):
 * a player-info entry (named after the mob, see [profileName]; the `textures` property, not listed in the tab, survival) BEFORE the entity is
 * added, and a team for that name that never shows a name tag, so only the armor-stand tag above the mob is read. The client
 * refuses a player add without its info entry, so [announce] is sent to everyone when the mob is made (not when a client starts
 * tracking it) and [retract] when it is removed; a player who joins later gets [announce] again for every live disguise.
 *
 * Server thread only.
 */
object Disguise {
    private val log = org.slf4j.LoggerFactory.getLogger("engineerclient")

    /**
     * The profile name Hypixel gives a mob's fake player: the mob's own name, except the Angry Archaeologist ("Diamond Guy") and the
     * Frozen Adventurer (a "Lost Adventurer" body). ADR, 300 runs: every disguise tag's body (tag id minus one) carries one of these.
     * Random 10-character names are only the NPCs' (Mort).
     */
    fun profileName(arch: MobArchetype): String = if (BloodLooks.isBlood(arch)) BloodLooks.profileName(arch) else when (arch.id) {
        "angry_archaeologist" -> "Diamond Guy"
        "frozen_adventurer" -> "Lost Adventurer"
        else -> arch.name
    }

    /** The team name of a fake player: Hypixel's `fkt_` plus the first 11 characters of its name. */
    fun teamName(profileName: String): String = "fkt_" + profileName.take(11)

    /**
     * The profile of a fake player: [uuid] (version 2), [name], and its skin. The client shows another player's skin only when its
     * `textures` property is signed (PlayerInfo asks the SkinManager for a secure skin and falls back to Steve or Alex), so the skin goes
     * out with Hypixel's own signed value when [SignedSkins] has it, and unsigned (a default skin on screen) only when it does not.
     * A skin with variants (the Lost Adventurer's three) takes the one that goes with the dragon set [MobGear.lostSet] gives [uuid].
     */
    fun profile(uuid: UUID, name: String, skin: MobSkin?): GameProfile {
        val chosen = skin?.let { pickVariant(it, uuid) }
        val signed = chosen?.let { SignedSkins.of(it.id) }
        val props = when {
            signed != null -> PropertyMap(ImmutableMultimap.of("textures", Property("textures", signed.first, signed.second)))
            chosen?.value != null -> PropertyMap(ImmutableMultimap.of("textures", Property("textures", chosen.value)))
            else -> PropertyMap.EMPTY
        }
        return GameProfile(uuid, name, props)
    }

    /** [skin] itself, or the variant whose id is the skin of [uuid]'s Lost Adventurer set (a body's skin goes with its armour, Dungeon Recorder 595 of 596). */
    private fun pickVariant(skin: MobSkin, uuid: UUID): MobSkin {
        if (skin.variants.size < 2) return skin
        val want = MobGear.lostSet(uuid).skin
        return skin.variants.firstOrNull { it.id.trimStart('0') == want.trimStart('0') } ?: skin
    }

    /** A skin the pack lacks for [arch]: the Shadow Assassin's (recorded, signed in [SignedSkins]). */
    fun missingSkin(arch: MobArchetype): MobSkin? = if (arch.id == "shadow_assassin") MobSkin("3399e00f404411e465d74388df132d51fe868ecf86f1c073faffa1d9172ec0f3", null, null) else null


    /** The packets that put the fake player's info and name-hiding team on a client. */
    fun announcePackets(profile: GameProfile, base: ServerPlayer): List<Packet<ClientGamePacketListener>> {
        val entry = ClientboundPlayerInfoUpdatePacket.Entry(profile.id, profile, false, 1, GameType.SURVIVAL, null, true, 0, null)
        val info = ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, base)
        // The public constructors only take real players, so the packet is made from one and its two fields are replaced.
        replaceField(info, "actions", EnumSet.of(
            ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LIST_ORDER))
        replaceField(info, "entries", listOf(entry))
        val team = PlayerTeam(Scoreboard(), teamName(profile.name))
        team.nameTagVisibility = Team.Visibility.NEVER
        team.collisionRule = Team.CollisionRule.NEVER
        return listOf(
            info,
            ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true),
            ClientboundSetPlayerTeamPacket.createPlayerPacket(team, profile.name, ClientboundSetPlayerTeamPacket.Action.ADD),
        )
    }

    /** The packets that take the fake player's info and team away again. */
    fun retractPackets(uuid: UUID, profileName: String): List<Packet<ClientGamePacketListener>> {
        val team = PlayerTeam(Scoreboard(), teamName(profileName))
        return listOf(ClientboundPlayerInfoRemovePacket(listOf(uuid)), ClientboundSetPlayerTeamPacket.createRemovePacket(team))
    }

    private fun replaceField(target: Any, name: String, value: Any) {
        val f = ClientboundPlayerInfoUpdatePacket::class.java.getDeclaredField(name)
        f.isAccessible = true
        f.set(target, value)
    }

    /** Sends the info and team to everyone in the world now. False (with a log line) if the packet could not be built; the caller then falls back to an ordinary body. */
    fun announce(profile: GameProfile): Boolean {
        val server = SimServer.server ?: return false
        val base = server.playerList.players.firstOrNull() ?: return false
        return try {
            for (p in announcePackets(profile, base)) server.playerList.broadcastAll(p)
            true
        } catch (t: Throwable) { log.warn("[ads] cannot announce a fake player: ${t.message}"); false }
    }

    /** Sends [announcePackets] to one player (a late joiner). */
    fun announceTo(player: ServerPlayer, profile: GameProfile) {
        try { for (p in announcePackets(profile, player)) player.connection.send(p) } catch (t: Throwable) { log.warn("[ads] cannot announce a fake player to ${player.scoreboardName}: ${t.message}") }
    }

    fun retract(uuid: UUID, profileName: String) {
        val server = SimServer.server ?: return
        try { for (p in retractPackets(uuid, profileName)) server.playerList.broadcastAll(p) } catch (t: Throwable) { log.warn("[ads] cannot retract a fake player: ${t.message}") }
    }
}
