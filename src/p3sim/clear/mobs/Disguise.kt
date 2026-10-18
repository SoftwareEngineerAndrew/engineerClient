package com.engineerclient.p3sim.clear.mobs

import com.engineerclient.p3sim.SimServer
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
 * a player-info entry (random 10-character name, the `textures` property, not listed in the tab, survival) BEFORE the entity is
 * added, and a team for that name that never shows a name tag, so only the armor-stand tag above the mob is read. The client
 * refuses a player add without its info entry, so [announce] is sent to everyone when the mob is made (not when a client starts
 * tracking it) and [retract] when it is removed; a player who joins later gets [announce] again for every live disguise.
 *
 * Server thread only.
 */
object Disguise {
    private val log = org.slf4j.LoggerFactory.getLogger("engineerclient")

    /** The team name of a fake player: Hypixel's `fkt_` plus the first 11 characters of its name. */
    fun teamName(profileName: String): String = "fkt_" + profileName.take(11)

    /** The profile of a fake player: [uuid] (version 2), [name], and the skin as an UNSIGNED `textures` property when the pack has one (an unsigned skin may not render on the 26.3 client; then it is a default skin). */
    fun profile(uuid: UUID, name: String, skin: MobSkin?): GameProfile {
        val value = skin?.value
        val props = if (value != null) PropertyMap(ImmutableMultimap.of("textures", Property("textures", value))) else PropertyMap.EMPTY
        return GameProfile(uuid, name, props)
    }

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
