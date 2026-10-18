package com.engineerclient.p3sim.clear.hud

import com.engineerclient.p3sim.Sim
import com.mojang.authlib.GameProfile
import io.netty.buffer.Unpooled
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.chat.numbers.BlankFormat
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action
import net.minecraft.network.protocol.game.ClientboundResetScorePacket
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket
import net.minecraft.network.protocol.game.ClientboundSetScorePacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ClientboundTabListPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.GameType
import net.minecraft.world.level.saveddata.maps.MapDecoration
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes
import net.minecraft.world.level.saveddata.maps.MapId
import net.minecraft.world.level.saveddata.maps.MapItemSavedData
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.Objective
import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.scores.Scoreboard
import net.minecraft.world.scores.Team
import net.minecraft.world.scores.criteria.ObjectiveCriteria
import java.util.EnumSet
import java.util.Optional
import java.util.UUID

/**
 * Builds the real packets for the sidebar, the tab list, the map and the action bar from the pure models. Every packet here is the one
 * Hypixel's server sends (names and shapes from the recordings); nothing is attached to the server's own scoreboard, so the vanilla
 * server state is untouched. Server thread only (a shared throw-away [Scoreboard] is used only to construct packets).
 *
 * Text goes through [Sim.legacy] so a `§` code becomes a style and `Component.getString()` is the plain text Odin and Devonian parse.
 */
object HudPackets {
    private val board = Scoreboard()
    private val log = org.slf4j.LoggerFactory.getLogger("engineerclient")

    private fun text(s: String): Component = Sim.legacy(s)

    // ---- sidebar ----------------------------------------------------------------------------------------------------

    private fun objective(): Objective =
        Objective(board, SidebarModel.OBJECTIVE, ObjectiveCriteria.DUMMY, text(HudConstants.TITLE), ObjectiveCriteria.RenderType.INTEGER, false, BlankFormat.INSTANCE)

    /** Creates the `SBScoreboard` objective and shows it in the sidebar slot. */
    fun sidebarShow(): List<Packet<ClientGamePacketListener>> {
        val o = objective()
        return listOf(ClientboundSetObjectivePacket(o, ClientboundSetObjectivePacket.METHOD_ADD), ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, o))
    }

    fun sidebarHide(): Packet<ClientGamePacketListener> = ClientboundSetObjectivePacket(objective(), ClientboundSetObjectivePacket.METHOD_REMOVE)

    private fun team(score: Int, line: String): PlayerTeam {
        val (prefix, suffix) = HudFormat.splitLine(line)
        val t = PlayerTeam(board, SidebarModel.teamName(score))
        t.setPlayerPrefix(text(prefix))
        t.setPlayerSuffix(text(suffix))
        // Recorded options byte 3: friendly fire and see friendly invisibles; name tags always shown, no collision.
        t.setAllowFriendlyFire(true)
        t.setSeeFriendlyInvisibles(true)
        t.setNameTagVisibility(Team.Visibility.ALWAYS)
        t.setCollisionRule(Team.CollisionRule.NEVER)
        return t
    }

    /** The packets for a sidebar change, in the order Hypixel sends them: removals, then new lines, then text changes. */
    fun sidebarOps(ops: SidebarOps): List<Packet<ClientGamePacketListener>> {
        val out = ArrayList<Packet<ClientGamePacketListener>>()
        for (score in ops.remove) {
            out += ClientboundResetScorePacket(SidebarModel.holder(score), SidebarModel.OBJECTIVE)
            out += ClientboundSetPlayerTeamPacket.createRemovePacket(PlayerTeam(board, SidebarModel.teamName(score)))
        }
        for ((score, line) in ops.add) {
            val t = team(score, line)
            val holder = SidebarModel.holder(score)
            out += ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(t, true)
            out += ClientboundSetScorePacket(holder, SidebarModel.OBJECTIVE, score, Optional.empty(), Optional.empty())
            out += ClientboundSetPlayerTeamPacket.createPlayerPacket(t, holder, ClientboundSetPlayerTeamPacket.Action.ADD)
        }
        for ((score, line) in ops.change) out += ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team(score, line), false)
        return out
    }

    /** Removes every sidebar team that is up (the objective's own removal drops the scores). */
    fun sidebarTeamsRemove(scores: Collection<Int>): List<Packet<ClientGamePacketListener>> =
        scores.map { ClientboundSetPlayerTeamPacket.createRemovePacket(PlayerTeam(board, SidebarModel.teamName(it))) }

    // ---- tab list ---------------------------------------------------------------------------------------------------

    private val entryCodecMethod by lazy {
        ClientboundPlayerInfoUpdatePacket::class.java.getDeclaredMethod("entryCodec", EnumSet::class.java).also { it.isAccessible = true }
    }

    /** The registries the packet text is written with; tests replace it with an empty set. */
    internal var registries: () -> net.minecraft.core.RegistryAccess = { Sim.level.registryAccess() }

    /** Whether the tab packets can be built here (the packet class offers no public constructor for arbitrary entries). */
    @Volatile var tabWorks = true
        private set

    private fun entry(id: UUID, profile: GameProfile, listed: Boolean, name: Component?): ClientboundPlayerInfoUpdatePacket.Entry =
        ClientboundPlayerInfoUpdatePacket.Entry(id, profile, listed, 1, GameType.SURVIVAL, name, false, 0, null)

    /**
     * A player-info update with hand-made entries. The packet class only has constructors that read real players, so the packet is made
     * the way a client makes it: its own entry codec (a private method of the class, found by reflection) writes the entries into a
     * buffer and the public stream codec reads the packet back. Returns null, and turns the tab off for this session, if that fails.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun info(actions: EnumSet<Action>, entries: List<ClientboundPlayerInfoUpdatePacket.Entry>): Packet<ClientGamePacketListener>? {
        if (!tabWorks) return null
        return try {
            val buf = RegistryFriendlyByteBuf(Unpooled.buffer(), registries())
            buf.writeEnumSet(actions, Action::class.java)
            val entryCodec = entryCodecMethod.invoke(null, actions) as StreamCodec<RegistryFriendlyByteBuf, ClientboundPlayerInfoUpdatePacket.Entry>
            val listCodec = entryCodec.apply(ByteBufCodecs.list<RegistryFriendlyByteBuf, ClientboundPlayerInfoUpdatePacket.Entry>())
            listCodec.encode(buf, entries)
            ClientboundPlayerInfoUpdatePacket.STREAM_CODEC.decode(buf)
        } catch (e: Throwable) {
            tabWorks = false
            log.warn("[ads] hud: cannot build tab list packets; the tab list is off for this session", e)
            null
        }
    }

    private fun fakeProfile(i: Int) = GameProfile(TabModel.profileId(i), TabModel.profileName(i))

    /** Adds the 80 invisible players that carry the tab text. */
    fun tabAddFakes(rows: List<String>): Packet<ClientGamePacketListener>? =
        info(EnumSet.of(Action.ADD_PLAYER, Action.UPDATE_GAME_MODE, Action.UPDATE_LISTED, Action.UPDATE_LATENCY, Action.UPDATE_DISPLAY_NAME, Action.UPDATE_LIST_ORDER),
            (0 until TabModel.ROWS).map { entry(TabModel.profileId(it), fakeProfile(it), true, text(rows[it])) })

    /** New text for the rows at [indexes]. */
    fun tabText(rows: List<String>, indexes: List<Int>): Packet<ClientGamePacketListener>? =
        if (indexes.isEmpty()) null else info(EnumSet.of(Action.UPDATE_DISPLAY_NAME), indexes.map { entry(TabModel.profileId(it), fakeProfile(it), true, text(rows[it])) })

    fun tabRemoveFakes(): Packet<ClientGamePacketListener> = ClientboundPlayerInfoRemovePacket((0 until TabModel.ROWS).map { TabModel.profileId(it) })

    /** Real players leave the visible list (listed false) so only the 80 rows show, or come back at the end. */
    fun tabListed(who: List<ServerPlayer>, listed: Boolean): Packet<ClientGamePacketListener>? =
        if (who.isEmpty()) null else info(EnumSet.of(Action.UPDATE_LISTED), who.map { entry(it.uuid, it.gameProfile, listed, null) })

    /** Entries for bots, so `getPlayerInfo(name)` finds them (Odin only adds a teammate it can find); they are not listed. */
    fun tabAddBots(bots: List<Pair<UUID, String>>): Packet<ClientGamePacketListener>? =
        if (bots.isEmpty()) null else info(EnumSet.of(Action.ADD_PLAYER, Action.UPDATE_GAME_MODE, Action.UPDATE_LISTED, Action.UPDATE_LATENCY),
            bots.map { (id, name) -> entry(id, GameProfile(id, name), false, null) })

    fun tabRemoveIds(ids: List<UUID>): Packet<ClientGamePacketListener>? = if (ids.isEmpty()) null else ClientboundPlayerInfoRemovePacket(ids)

    fun tabHeaderFooter(header: String, footer: String): Packet<ClientGamePacketListener> = ClientboundTabListPacket(text(header), text(footer))

    // ---- map --------------------------------------------------------------------------------------------------------

    private fun decos(marks: List<MapMark>): List<MapDecoration> = marks.map {
        MapDecoration(if (it.frame) MapDecorationTypes.FRAME else MapDecorationTypes.BLUE_MARKER, it.x.toByte(), it.y.toByte(), it.rot.toByte(), Optional.empty())
    }

    /** A full 128 x 128 frame (with the current decorations: Odin reads them first, then the colours). */
    fun mapFrame(colors: ByteArray, marks: List<MapMark>): Packet<ClientGamePacketListener> =
        ClientboundMapItemDataPacket(MapId(HudConstants.MAP_ID), 2, false, decos(marks), MapItemSavedData.MapPatch(0, 0, MapModel.SIZE, MapModel.SIZE, colors))

    /** Decorations only: the markers move, the picture is left alone. */
    fun mapMarks(marks: List<MapMark>): Packet<ClientGamePacketListener> =
        ClientboundMapItemDataPacket(MapId(HudConstants.MAP_ID), 2, false, Optional.of(decos(marks)), Optional.empty())

    // ---- action bar -------------------------------------------------------------------------------------------------

    /** Hypixel sends the action bar as an overlay system chat, not as the title packet, and as one literal string with the `§` codes in it. */
    fun actionBar(line: String): Packet<ClientGamePacketListener> = ClientboundSystemChatPacket(Component.literal(line), true)
}
