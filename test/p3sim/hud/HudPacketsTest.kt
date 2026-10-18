package com.engineerclient.p3sim.clear.hud

import io.netty.buffer.Unpooled
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket
import net.minecraft.network.protocol.game.ClientboundSetScorePacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ClientboundTabListPacket
import net.minecraft.world.scores.DisplaySlot
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Builds every packet the HUD sends, writes it to bytes and reads it back, the way the connection does, and checks what a client mod
 * would see. Needs Minecraft's registries booted, which works headless.
 */
class HudPacketsTest {
    companion object {
        lateinit var access: RegistryAccess
        @BeforeAll @JvmStatic fun boot() {
            net.minecraft.SharedConstants.tryDetectVersion()
            net.minecraft.server.Bootstrap.bootStrap()
            access = RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY)
            HudPackets.registries = { access }
        }
    }

    private fun buf() = RegistryFriendlyByteBuf(Unpooled.buffer(), access)

    @Suppress("UNCHECKED_CAST")
    private fun <T : Packet<*>> roundTrip(p: Packet<*>, codec: net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, T>): T {
        val b = buf()
        codec.encode(b, p as T)
        return codec.decode(b)
    }

    @Test fun sidebarObjectiveAndLinesAreWhatOdinAndDevonianParse() {
        val show = HudPackets.sidebarShow()
        val obj = roundTrip(show[0], ClientboundSetObjectivePacket.STREAM_CODEC)
        assertEquals("SBScoreboard", obj.objectiveName)
        assertEquals(ClientboundSetObjectivePacket.METHOD_ADD, obj.method)
        assertEquals("SKYBLOCK", obj.displayName.string)
        val disp = show[1] as ClientboundSetDisplayObjectivePacket
        assertEquals(DisplaySlot.SIDEBAR, disp.slot)

        val ops = SidebarDiff.diff(emptyMap(), SidebarDiff.scored(listOf("Cleared: §c31% §8(53)", "Time Elapsed: §a13s")))
        val packets = HudPackets.sidebarOps(ops)
        // Per line: team create, score, member add.
        assertEquals(6, packets.size)
        val team = roundTrip(packets[0], ClientboundSetPlayerTeamPacket.STREAM_CODEC)
        assertEquals("team_2", team.name)
        val params = team.parameters.get()
        assertEquals("Cleared: 31% (53)", params.playerPrefix.string + params.playerSuffix.string)
        assertTrue(Regex("^Cleared: (\\d+)% \\(\\d+\\)$").matches(params.playerPrefix.string + params.playerSuffix.string))
        val score = roundTrip(packets[1], ClientboundSetScorePacket.STREAM_CODEC)
        assertEquals(SidebarModel.holder(2), score.owner())
        assertEquals(2, score.score())
        assertEquals("SBScoreboard", score.objectiveName())
        val member = roundTrip(packets[2], ClientboundSetPlayerTeamPacket.STREAM_CODEC)
        assertEquals(listOf(SidebarModel.holder(2)), member.players.toList())

        val change = HudPackets.sidebarOps(SidebarOps(emptyList(), emptyList(), listOf(1 to "Time Elapsed: §a14s")))
        val upd = roundTrip(change[0], ClientboundSetPlayerTeamPacket.STREAM_CODEC)
        assertEquals("Time Elapsed: 14s", upd.parameters.get().let { it.playerPrefix.string + it.playerSuffix.string })
        assertEquals(2, HudPackets.sidebarOps(SidebarOps(listOf(3), emptyList(), emptyList())).size)
    }

    @Test fun tabRowsAreEightyFakePlayersWithDisplayNames() {
        val rows = (0 until 80).map { if (it == 1) "[250] Alpha ☠ (Mage L)" else " " }
        val p = HudPackets.tabAddFakes(rows)!!
        val back = roundTrip(p, ClientboundPlayerInfoUpdatePacket.STREAM_CODEC)
        assertEquals(80, back.entries().size)
        assertTrue(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER in back.actions())
        assertEquals("!A-b", back.entries()[1].profile!!.name)
        assertEquals("[250] Alpha ☠ (Mage L)", back.entries()[1].displayName!!.string)
        assertTrue(back.entries().all { it.listed })
        assertTrue(Regex("^\\[(\\d+)] (?:\\[\\w+] )*(\\w+) .*?\\((\\w+)(?: (\\w+))*\\)$").matches(back.entries()[1].displayName!!.string))

        val upd = roundTrip(HudPackets.tabText(rows, listOf(1, 5))!!, ClientboundPlayerInfoUpdatePacket.STREAM_CODEC)
        assertEquals(listOf(TabModel.profileId(1), TabModel.profileId(5)), upd.entries().map { it.profileId })
        assertEquals(setOf(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME), upd.actions())
        assertEquals(null, HudPackets.tabText(rows, emptyList()))
    }

    @Test fun botEntriesAreUnlistedAndFindableByName() {
        val id = java.util.UUID(1, 2)
        val back = roundTrip(HudPackets.tabAddBots(listOf(id to "Botty"))!!, ClientboundPlayerInfoUpdatePacket.STREAM_CODEC)
        assertEquals("Botty", back.entries().single().profile!!.name)
        assertEquals(false, back.entries().single().listed)
        assertEquals(null, HudPackets.tabAddBots(emptyList()))
    }

    @Test fun headerAndFooterCarryTheBlessingText() {
        val st = HudFixtures.state(); st.secrets.blessings["power"] = 5
        val p = roundTrip(HudPackets.tabHeaderFooter(TabModel.HEADER, TabModel.footer(HudFixtures.ctx(st))), ClientboundTabListPacket.STREAM_CODEC)
        assertEquals("You are playing on MC.HYPIXEL.NET", p.header().string)
        assertTrue(Regex("Blessing of Power (X{0,3}(IX|IV|V?I{0,3}))").find(p.footer().string)!!.groupValues[1] == "V")
    }

    @Test fun mapFrameCarriesAllSixteenThousandBytesAndTheMarkers() {
        val st = HudFixtures.bigState(); st.setCheck(1, com.engineerclient.p3sim.run.CheckMark.NONE)
        val bytes = MapModel.render(st)
        val marks = listOf(MapMark(false, -70, -86, 12), MapMark(true, -36, -62, 3))
        val p = roundTrip(HudPackets.mapFrame(bytes, marks), ClientboundMapItemDataPacket.STREAM_CODEC)
        assertEquals(HudConstants.MAP_ID, p.mapId().id)
        assertTrue((p.mapId().id and 1000) == 0, "Odin and Devonian only accept ids with this bit pattern")
        val patch = p.colorPatch().get()
        assertEquals(16384, patch.mapColors().size)
        assertEquals(128, patch.width()); assertEquals(128, patch.height())
        assertTrue(patch.mapColors().contentEquals(bytes))
        assertEquals(0, patch.mapColors()[0].toInt())
        val d = p.decorations().get()
        assertEquals(2, d.size)
        assertEquals(-70, d[0].x().toInt()); assertEquals(12, d[0].rot().toInt())
        assertTrue(d[0].type().`is`(net.minecraft.world.level.saveddata.maps.MapDecorationTypes.BLUE_MARKER))
        assertTrue(d[1].type().`is`(net.minecraft.world.level.saveddata.maps.MapDecorationTypes.FRAME))
    }

    @Test fun markerOnlyPacketHasNoPicture() {
        val p = roundTrip(HudPackets.mapMarks(listOf(MapMark(true, 0, 0, 0))), ClientboundMapItemDataPacket.STREAM_CODEC)
        assertTrue(p.colorPatch().isEmpty)
        assertEquals(1, p.decorations().get().size)
    }

    @Test fun actionBarIsAnOverlayWithThePlainTextOdinReads() {
        val st = HudFixtures.state(); HudFixtures.party(st)
        val line = ActionBarModel.compose(HudStats.DEFAULT, st.players[HudFixtures.me], st, emptyList())
        val p = roundTrip(HudPackets.actionBar(line), ClientboundSystemChatPacket.STREAM_CODEC)
        assertTrue(p.overlay())
        assertTrue(Regex("([\\d|,]+)/([\\d|,]+)\\uE010").containsMatchIn(p.content().string), p.content().string)
        // Hypixel sends one literal string with the section-sign codes in it, not a tree of styled pieces.
        assertTrue(p.content().string.startsWith("§c"), p.content().string)
        assertTrue(p.content().siblings.isEmpty())
    }
}
