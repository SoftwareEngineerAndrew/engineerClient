package com.engineerclient.p3sim.run

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.p3sim.Fight
import com.engineerclient.p3sim.P3Sim
import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.SimNet
import com.odtheking.odin.events.PacketEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.EventPriority
import com.odtheking.odin.events.core.on
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket
import net.minecraft.network.protocol.common.custom.BrandPayload
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Hypixel's mod API for the mods that read it. On Hypixel the server tells a mod using the API three things in the login tick: a hello (the
 * environment, which makes the API believe it is connected), the location (`hyevent:location`: skyblock, mode `dungeon`) and, when the mod
 * asks (`hypixel:party_info`), the party. Blade Addons, DTMap and Devonian read them; with none sent they stay at "unknown location" and
 * "no party" for the whole run.
 *
 * The sim's host cannot send these as real packets (the API's clientbound payload has no encoder), so the server sends a SimNet message
 * ([HypixelWire]) and each game turns it into the API's own payload and hands that to the game's connection as if it had arrived: the API's
 * receivers, its callbacks and the mods' handlers then run exactly as for Hypixel's. The mod API is reached by reflection, so the jar needs no
 * dependency on it, and without the mod nothing here does anything.
 *
 * The other way round the game would send the API's serverbound payloads (`hypixel:register`, `hypixel:party_info`) to the sim's server, whose
 * decoder cannot read them and would drop the connection. They are cancelled on the way out while a sim is open; a party request is answered
 * from the run ([PARTY_REQUEST_KIND] then [PARTY_KIND], 0 to 3 server ticks later like Hypixel's).
 *
 * The server brand (F3) is sent with the location, as Hypixel sends it in the login.
 */
object HypixelModApi {
    private val log = LoggerFactory.getLogger("engineerclient")

    private const val PKG = "net.hypixel.modapi."
    private const val HELLO = PKG + "packet.impl.clientbound.ClientboundHelloPacket"
    private const val LOCATION = PKG + "packet.impl.clientbound.event.ClientboundLocationPacket"
    private const val PARTY_INFO = PKG + "packet.impl.clientbound.ClientboundPartyInfoPacket"
    private const val PARTY_MEMBER = "$PARTY_INFO\$PartyMember"
    private const val PARTY_ROLE = "$PARTY_INFO\$PartyRole"
    private const val SERIALIZER = PKG + "serializer.PacketSerializer"
    private const val PAYLOAD = PKG + "fabric.payload.ClientboundHypixelPayload"
    private const val API = PKG + "HypixelModAPI"
    private const val ENVIRONMENT_TYPE = "net.hypixel.data.region.Environment"
    private const val SERVER_TYPE = "net.hypixel.data.type.ServerType"

    /** The hello's environment, and the location's mode and map, as Hypixel's dungeon servers send them. */
    private const val ENVIRONMENT = "PRODUCTION"
    private const val MODE = "dungeon"
    private const val MAP = "Dungeon"

    /** The API is not installed (or changed shape): said once, and nothing is tried again. */
    @Volatile private var unavailable = false

    // ---- client side ------------------------------------------------------------------------------------------------------------------

    /** Wires the client: the SimNet receivers, and the cancelling of the API's serverbound payloads. Once, at game start (client thread). */
    fun install() {
        SimNet.onClient(HypixelWire.LOCATION_KIND) { data ->
            // Hypixel's music manager is already running when a dungeon starts (a track every 10 to 20 minutes); a fresh world's would start its first
            // track five seconds in. Stopping once draws the next track's delay as the game does between two tracks.
            EngineerClient.safely("ads music") { mc.musicManager.stopPlaying() }
            HypixelWire.decodeLocation(data)?.let { deliverLocation(it) }
        }
        SimNet.onClient(HypixelWire.PARTY_KIND) { data -> deliverParty(HypixelWire.decodeParty(data)) }
        SimNet.onServer(HypixelWire.PARTY_REQUEST_KIND) { player, _ -> partyRequested(player) }
        // The game's mods talk to the API as soon as they believe they are on Hypixel; the sim's server could not read what they send.
        on<PacketEvent.Send>(EventPriority.HIGHEST) {
            val payload = (packet as? ServerboundCustomPayloadPacket)?.payload() ?: return@on
            val id = payload.type().id()
            if (id.namespace != "hypixel" || !P3Sim.inSim) return@on
            cancel()
            if (id.path == "party_info") SimNet.send(HypixelWire.PARTY_REQUEST_KIND)
        }
        // A world with no advancements: the join's two (empty) advancement updates would still make the game's advancement tracker exist.
        on<PacketEvent.Receive>(EventPriority.HIGHEST) {
            if (packet is net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket && P3Sim.inSim) cancel()
        }
        EventBus.subscribe(this)
    }

    /** What this game was told last, for `/ads dev worldenv`. */
    @Volatile private var told: String? = null

    /** One line on the mod API from this game's side. */
    fun status(): String = when {
        unavailable -> "Hypixel mod API: not installed, or it would not take the packets (see the log)"
        told != null -> "Hypixel mod API told: $told"
        else -> "Hypixel mod API: nothing told yet (a run tells it at its start)"
    }

    /** The hello and the location, as the API receives them from Hypixel. Client thread. */
    internal fun deliverLocation(serverName: String) {
        if (unavailable) return
        try {
            deliver(helloPacket())
            deliver(locationPacket(serverName))
            told = "hello, then location $serverName (SKYBLOCK, $MODE)"
        } catch (e: ClassNotFoundException) {
            unavailable = true
            log.info("[ads] the Hypixel mod API is not installed; mods that read it will not see the dungeon")
        } catch (t: Throwable) {
            unavailable = true
            log.warn("[ads] could not hand the location to the Hypixel mod API", t)
        }
    }

    /** The party the API was asked for. Client thread. */
    internal fun deliverParty(members: List<HypixelWire.Member>) {
        if (unavailable) return
        try {
            deliver(partyPacket(members))
            told = (told?.substringBefore("; party") ?: "") + "; party of ${members.size} (${members.count { it.leader }} leader)"
        } catch (e: ClassNotFoundException) {
            unavailable = true
        } catch (t: Throwable) {
            unavailable = true
            log.warn("[ads] could not hand the party to the Hypixel mod API", t)
        }
    }

    /** `ClientboundHelloPacket(PRODUCTION)`. Throws [ClassNotFoundException] without the mod. */
    fun helloPacket(): Any {
        val environment = Class.forName(ENVIRONMENT_TYPE)
        return Class.forName(HELLO).getConstructor(environment).newInstance(environment.enumConstants.first { (it as Enum<*>).name == ENVIRONMENT })
    }

    /** `ClientboundLocationPacket(serverName, SKYBLOCK, null, "dungeon", "Dungeon")`. */
    fun locationPacket(serverName: String): Any {
        val serverType = Class.forName(SERVER_TYPE)
        val skyblock = (serverType.getMethod("valueOf", String::class.java).invoke(null, "SKYBLOCK") as java.util.Optional<*>).get()
        return Class.forName(LOCATION).getConstructor(String::class.java, serverType, String::class.java, String::class.java, String::class.java)
            .newInstance(serverName, skyblock, null, MODE, MAP)
    }

    /** `ClientboundPartyInfoPacket(2, true, members)`: the first leader listed is `LEADER`, everyone else `MEMBER`. */
    fun partyPacket(members: List<HypixelWire.Member>): Any {
        val role = Class.forName(PARTY_ROLE)
        val memberCtor = Class.forName(PARTY_MEMBER).getConstructor(UUID::class.java, role)
        val map = LinkedHashMap<UUID, Any>()
        for (m in members) map[m.uuid] = memberCtor.newInstance(m.uuid, role.enumConstants.first { (it as Enum<*>).name == if (m.leader) "LEADER" else "MEMBER" })
        return Class.forName(PARTY_INFO).getConstructor(Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Map::class.java).newInstance(2, true, map)
    }

    /**
     * The API's own payload for [packet] (a clientbound Hypixel packet object): its fields written behind the success flag and read back by the API's
     * codec, which is what the game's connection does with the bytes of a real one.
     */
    fun payloadOf(packet: Any): CustomPacketPayload {
        val serializer = Class.forName(SERIALIZER)
        val id = packet.javaClass.getMethod("getIdentifier").invoke(packet) as String
        val buf: ByteBuf = Unpooled.buffer()
        val writer = serializer.getConstructor(ByteBuf::class.java).newInstance(buf)
        serializer.getMethod("writeBoolean", Boolean::class.javaPrimitiveType).invoke(writer, true)
        packet.javaClass.getMethod("write", serializer).invoke(packet, writer)
        val type = CustomPacketPayload.Type<CustomPacketPayload>(Identifier.parse(id))
        @Suppress("UNCHECKED_CAST")
        val codec = Class.forName(PAYLOAD).getMethod("buildCodec", CustomPacketPayload.Type::class.java).invoke(null, type) as StreamCodec<ByteBuf, CustomPacketPayload>
        return codec.decode(buf)
    }

    /**
     * Gives the API [packet] the way a server would: its payload handled by the game's connection, which hands it to the API's receiver (and its
     * callbacks). The API's own `handle` is the fallback when the connection is not there yet.
     */
    private fun deliver(packet: Any) {
        val payload = payloadOf(packet)
        val connection = mc.connection
        if (connection != null) connection.handleCustomPayload(ClientboundCustomPayloadPacket(payload))
        else {
            val api = Class.forName(API).getMethod("getInstance").invoke(null)
            api.javaClass.getMethod("handle", Class.forName(PKG + "packet.ClientboundHypixelPacket")).invoke(api, packet)
        }
    }

    // ---- server side ------------------------------------------------------------------------------------------------------------------

    private class Pending(val player: UUID, val dueTick: Int)

    private val pending = ArrayList<Pending>()
    private var serverName: String? = null
    private var random = kotlin.random.Random(0)

    /** The server tick the run started on (the login, for the keep-alive clock) and the last keep-alive id sent. */
    private var startTick = 0
    private var keepAliveId = 0L

    /** One line on the server side for `/ads dev worldenv`. */
    fun serverStatus(): String = "Run server name ${serverName ?: "none"}, $keepAliveId keep-alives sent, ${pending.size} party answers waiting"

    /** A run begins: a server name for it, and the location and brand to every player in the sim. Server thread. */
    fun runStarted(run: Run) {
        pending.clear()
        startTick = Fight.serverTick; keepAliveId = 0
        random = run.subRandom("hypixel")
        serverName = HypixelWire.serverName(random)
        for (p in Sim.players()) sendLocation(p)
    }

    /** A late joiner of the run's party gets the same location. */
    fun playerJoined(player: ServerPlayer) { if (serverName != null) sendLocation(player) }

    /** The run is over: nothing more to answer. */
    fun runEnded() { pending.clear(); serverName = null }

    private fun sendLocation(p: ServerPlayer) {
        val name = serverName ?: return
        // The brand first, as in the login.
        p.connection.send(ClientboundCustomPayloadPacket(BrandPayload(HypixelWire.BRAND)))
        SimNet.send(p, HypixelWire.LOCATION_KIND, HypixelWire.encodeLocation(name))
    }

    /** A game's mods asked for the party: the answer follows 0 to 3 server ticks later ([tick]). Nothing outside a run. */
    private fun partyRequested(player: ServerPlayer) {
        val run = Runs.current ?: return
        if (run.isOver) return
        pending += Pending(player.uuid, Fight.serverTick + HypixelWire.partyDelayTicks(random))
    }

    /** Every server tick of a run: the keep-alive, then the party answers that are due. */
    fun tick(run: Run) {
        keepAlive()
        if (pending.isEmpty()) return
        val due = pending.filter { it.dueTick <= Fight.serverTick }
        if (due.isEmpty()) return
        pending.removeAll(due.toSet())
        val humans = Sim.players()
        val members = HypixelWire.partyMembers(humans.map { it.uuid }, run.state.players.values.filter { it.isBot }.map { it.uuid })
        val text = HypixelWire.encodeParty(members)
        for (d in due) humans.firstOrNull { it.uuid == d.player }?.let { SimNet.send(it, HypixelWire.PARTY_KIND, text) }
    }

    /**
     * Hypixel pings the client with a keep-alive 14 server ticks after the login and every 11 ticks after that, numbered 1, 2, 3...; the integrated
     * server never pings its owner. The owner's game answers any keep-alive and the server ignores an answer it did not ask for from its own
     * owner, so there is no timeout. Only the host gets them: a joiner over the LAN is a remote player, whose connection the server times out on an
     * answer it did not ask for.
     */
    private fun keepAlive() {
        val dt = Fight.serverTick - startTick - KEEP_ALIVE_FIRST
        if (dt < 0 || dt % KEEP_ALIVE_EVERY != 0) return
        Sim.host?.connection?.send(ClientboundKeepAlivePacket(++keepAliveId))
    }

    /** Ticks from the login to the first keep-alive, and between two (measured: 14, then 275 of 275 gaps exactly 11). */
    const val KEEP_ALIVE_FIRST = 14
    const val KEEP_ALIVE_EVERY = 11
}
