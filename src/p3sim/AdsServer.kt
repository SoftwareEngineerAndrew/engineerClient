package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import com.engineerclient.mixin.AdsServerLoginAccessor
import com.google.common.collect.ImmutableMultimap
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import io.netty.buffer.Unpooled
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerLoginPacketListenerImpl
import net.minecraft.util.HttpUtil
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * ADS on the server: your sim, listed in the ADS lobby (a Minecraft server anyone can join, mod or not) with
 * your head in its Dungeons menu. Someone who clicks it is sent by the ADS proxy into your game.
 *
 * Hosting opens your sim on this computer only (like P3 parties: [com.engineerclient.mixin.SimPartyLanMixin])
 * and connects out to the proxy's host tunnel, so nothing is opened to the internet here. The proxy proves to
 * Mojang that we are this account (the same check as joining a server), then every connection it makes to our
 * server comes as a stream over the tunnel (frames as in ads-server's PROTOCOL.md). The proxy has checked each
 * player's account already and forwards who they are (Velocity's modern forwarding, signed with a secret the
 * proxy hands us), so their login here takes that instead of a second Mojang check ([forwards]).
 */
object AdsServer {
    /** The proxy's host tunnel, unless config/engineerclient/ads-server.txt names another (host:port). */
    private const val DEFAULT_PROXY = "127.0.0.1:25570"
    private val PLAYER_INFO = Identifier.fromNamespaceAndPath("velocity", "player_info")
    /** Velocity's forwarding versions we take: 1 (default) and 4 (lazy session, the same fields). */
    private const val FORWARDING_ASK = 4
    private const val MAX_FRAME = 1 shl 20
    private const val HELLO = 1; private const val CHALLENGE = 2; private const val JOINED = 3; private const val WELCOME = 4
    private const val OPEN = 5; private const val DATA = 6; private const val CLOSE = 7; private const val INFO = 8
    private const val PING = 9; private const val ERROR = 10

    private val mc get() = net.minecraft.client.Minecraft.getInstance()

    private class Host(val port: Int) {
        @Volatile var socket: Socket? = null
        @Volatile var out: DataOutputStream? = null
        @Volatile var secret: ByteArray? = null
        @Volatile var closed = false
        val streams = ConcurrentHashMap<Int, Socket>()
    }

    @Volatile private var host: Host? = null

    /** Hosting on the ADS server (connecting or listed). */
    @JvmStatic val active: Boolean get() = host != null
    /** Listed in the lobby: the proxy took us. */
    val listed: Boolean get() = host?.secret != null

    /**
     * This game is in someone else's sim through the ADS server (client side): its server said so on join
     * ("adsHello"). Each new world through the proxy starts without it.
     */
    @Volatile var inHostsSim = false
        private set

    private fun proxyAddress(): String = runCatching {
        val f = mc.gameDirectory.toPath().resolve("config").resolve("engineerclient").resolve("ads-server.txt")
        if (Files.exists(f)) Files.readAllLines(f).firstOrNull { it.isNotBlank() }?.trim() else null
    }.getOrNull() ?: DEFAULT_PROXY

    private val version: String by lazy {
        FabricLoader.getInstance().getModContainer("engineerclient").map { it.metadata.version.friendlyString }.orElse("?")
    }

    /** Lists your sim on the ADS server (the menu's Host on Server). */
    fun start() {
        if (host != null) return
        if (P3Party.hosting) { EngineerClient.msg("§cClose your party first."); return }
        val server = mc.singleplayerServer ?: return
        if (!SimServer.isSim(server)) return
        val port = if (server.isPublished) server.port else HttpUtil.getAvailablePort()
        val h = Host(port)
        // (Before publishing: the LAN mixin reads [active] to keep the listener on 127.0.0.1.)
        host = h
        if (!server.isPublished && !server.publishServer(MinecraftServer.MultiplayerScope.LAN, false, port)) {
            host = null
            EngineerClient.msg("§cCouldn't open your sim for the server.")
            return
        }
        EngineerClient.msg("§7Connecting to the ADS server...")
        Thread({ run(h) }, "ads-server").apply { isDaemon = true }.start()
        Thread({ heartbeat(h) }, "ads-server-info").apply { isDaemon = true }.start()
    }

    /** Takes your sim off the ADS server: everyone who came through it is dropped ([unpublish] false: the server is stopping anyway). */
    fun stop(unpublish: Boolean = true) {
        val h = host ?: return
        host = null
        h.closed = true
        h.streams.values.forEach { runCatching { it.close() } }
        h.streams.clear()
        runCatching { h.socket?.close() }
        if (unpublish) mc.singleplayerServer?.let { s -> if (SimServer.isSim(s) && s.isPublished) runCatching { s.unpublishServer() } }
    }

    // ------------------------------------------------------------------ the tunnel

    private fun send(h: Host, type: Int, body: ByteArray = ByteArray(0), off: Int = 0, len: Int = body.size) {
        val out = h.out ?: return
        synchronized(out) { out.writeInt(len + 1); out.writeByte(type); out.write(body, off, len); out.flush() }
    }

    private fun sendId(h: Host, type: Int, id: Int, data: ByteArray? = null, n: Int = 0) {
        val out = h.out ?: return
        synchronized(out) { out.writeInt(n + 3); out.writeByte(type); out.writeShort(id); if (n > 0) out.write(data!!, 0, n); out.flush() }
    }

    private fun send(h: Host, type: Int, json: JsonObject) = send(h, type, json.toString().toByteArray())

    private fun run(h: Host) {
        var why: String? = null
        try {
            val addr = proxyAddress()
            val s = Socket()
            s.connect(InetSocketAddress(addr.substringBeforeLast(':'), addr.substringAfterLast(':').toInt()), 10_000)
            s.tcpNoDelay = true
            s.soTimeout = 45_000
            h.socket = s
            if (h.closed) return
            h.out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 64 * 1024))
            val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 64 * 1024))
            val user = mc.user
            send(h, HELLO, JsonObject().apply { addProperty("v", 1); addProperty("name", user.name); addProperty("uuid", user.profileId.toString()); addProperty("mod", version) })
            while (!h.closed) {
                val n = inp.readInt()
                if (n < 1 || n > MAX_FRAME) error("bad frame length $n")
                val type = inp.readUnsignedByte()
                val body = ByteArray(n - 1)
                inp.readFully(body)
                when (type) {
                    CHALLENGE -> {
                        // The proxy asks Mojang whether this account joined "server" serverId: only this game can have.
                        val id = JsonParser.parseString(String(body)).asJsonObject["serverId"].asString
                        mc.services().sessionService().joinServer(user.profileId, user.accessToken, id)
                        send(h, JOINED)
                    }
                    WELCOME -> {
                        h.secret = JsonParser.parseString(String(body)).asJsonObject["secret"].asString.toByteArray()
                        EngineerClient.msg("§aYour sim is listed on the ADS server.")
                    }
                    OPEN -> open(h, id(body))
                    DATA -> h.streams[id(body)]?.let { st -> runCatching { st.getOutputStream().write(body, 2, body.size - 2) }.onFailure { closeStream(h, id(body), st) } }
                    CLOSE -> h.streams.remove(id(body))?.let { runCatching { it.close() } }
                    PING -> send(h, PING)
                    ERROR -> { why = String(body); break }
                }
            }
        } catch (e: Exception) {
            if (why == null && !h.closed) why = e.message ?: e.javaClass.simpleName
        } finally {
            runCatching { h.socket?.close() }
            if (host === h) {
                EngineerClient.msg("§cOff the ADS server${why?.let { ": $it" } ?: ""}.")
                mc.execute { if (host === h) stop() }
            }
        }
    }

    private fun id(body: ByteArray) = ((body[0].toInt() and 0xff) shl 8) or (body[1].toInt() and 0xff)

    /** A stream the proxy opened: a connection into our own server for it. */
    private fun open(h: Host, id: Int) {
        val s = runCatching { Socket(InetAddress.getLoopbackAddress(), h.port).also { it.tcpNoDelay = true } }.getOrElse { sendId(h, CLOSE, id); return }
        h.streams[id] = s
        Thread({
            val buf = ByteArray(32 * 1024)
            try {
                val inp = s.getInputStream()
                while (true) { val n = inp.read(buf); if (n < 0) break; if (n > 0) sendId(h, DATA, id, buf, n) }
            } catch (_: Exception) {
            } finally { closeStream(h, id, s) }
        }, "ads-server-$id").apply { isDaemon = true }.start()
    }

    private fun closeStream(h: Host, id: Int, s: Socket) {
        runCatching { s.close() }
        if (h.streams.remove(id, s)) runCatching { sendId(h, CLOSE, id) }
    }

    /** Who's in your sim, for your head's lore in the lobby (when it changes), and a ping now and then. */
    private fun heartbeat(h: Host) {
        var last = ""
        var tick = 0
        while (!h.closed) {
            Thread.sleep(3000)
            if (h.secret == null) continue
            val players = P3Party.players()
            val info = JsonObject().apply {
                addProperty("what", "ADS")
                add("players", JsonArray().apply { players.forEach { add(it) } })
                addProperty("max", 5)
            }.toString()
            runCatching {
                if (info != last) { send(h, INFO, info.toByteArray()); last = info }
                else if (++tick % 5 == 0) send(h, PING)
            }
        }
    }

    // ------------------------------------------------------------------ logins through the proxy

    /** Whether a network login into [server] comes through the ADS proxy (and so takes the player it forwards). */
    @JvmStatic
    fun forwards(server: MinecraftServer): Boolean = host != null && SimServer.isSim(server)

    private fun proxied(server: MinecraftServer, handler: ServerLoginPacketListenerImpl) =
        forwards(server) && !(handler as AdsServerLoginAccessor).`ec$connection`().isMemoryConnection

    fun register() {
        SimNet.onClient("adsHello") { inHostsSim = true }
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register { _, _, _ -> inHostsSim = false }
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> inHostsSim = false }
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            if (forwards(server) && handler.player.uuid != server.singleplayerProfile?.id()) SimNet.send(handler.player, "adsHello")
        }
        ServerLoginConnectionEvents.QUERY_START.register { handler, server, sender, _ ->
            if (proxied(server, handler)) sender.sendPacket(PLAYER_INFO, FriendlyByteBuf(Unpooled.wrappedBuffer(byteArrayOf(FORWARDING_ASK.toByte()))))
        }
        ServerLoginNetworking.registerGlobalReceiver(PLAYER_INFO) { server, handler, understood, buf, sync, _ ->
            if (!proxied(server, handler)) return@registerGlobalReceiver
            if (!understood) { handler.disconnect(Component.literal("Join this sim through the ADS server.")); return@registerGlobalReceiver }
            val profile = runCatching { forwarded(buf) }.getOrElse {
                EngineerClient.logger.warn("[ads] proxied login refused: ${it.message}")
                handler.disconnect(Component.literal("Couldn't check your login with the ADS server."))
                return@registerGlobalReceiver
            }
            // The same account as the host: letting it in would throw the host out of their own sim.
            if (profile.id() == server.singleplayerProfile?.id()) { handler.disconnect(Component.literal("That's the host's own account.")); return@registerGlobalReceiver }
            sync.waitFor(server.submit { (handler as AdsServerLoginAccessor).`ec$setAuthenticatedProfile`(profile) })
        }
    }

    /** The player the proxy forwards: [signature 32][version][address][uuid][name][properties], signed with the secret it gave us. */
    private fun forwarded(buf: FriendlyByteBuf): GameProfile {
        val secret = host?.secret ?: error("not listed yet")
        val sig = ByteArray(32).also { buf.readBytes(it) }
        val data = ByteArray(buf.readableBytes()).also { buf.getBytes(buf.readerIndex(), it) }
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }.doFinal(data)
        if (!MessageDigest.isEqual(sig, mac)) error("bad signature")
        val v = buf.readVarInt()
        if (v != 1 && v != FORWARDING_ASK) error("forwarding version $v")
        buf.readUtf(Short.MAX_VALUE.toInt()) // their address
        val uuid = buf.readUUID()
        val name = buf.readUtf(16)
        val props = ImmutableMultimap.builder<String, Property>()
        repeat(buf.readVarInt()) {
            val k = buf.readUtf(Short.MAX_VALUE.toInt())
            val value = buf.readUtf(Short.MAX_VALUE.toInt())
            val s = if (buf.readBoolean()) buf.readUtf(Short.MAX_VALUE.toInt()) else null
            props.put(k, Property(k, value, s))
        }
        return GameProfile(uuid, name, PropertyMap(props.build()))
    }
}
