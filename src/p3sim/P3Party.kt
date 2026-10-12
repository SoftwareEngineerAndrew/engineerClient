package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.util.HttpUtil
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * P3 Sim parties: you host your sim and get a 4-letter code; friends type it in and join your arena.
 *
 * Hosting opens the sim's server to this computer only (the game's Open to LAN, on 127.0.0.1 and not
 * announced: [com.engineerclient.mixin.SimPartyLanMixin]), then connects out to the relay on
 * undonecoffee.com, which hands out the code. Each joiner the relay announces gets its own local
 * connection into the server; bytes go back and forth over the relay, which passes them on unread.
 * Joining: a port on this computer for the game to connect to, piped to the relay with the code.
 * The Minecraft login runs through all of it unchanged (Mojang checks the account, the connection is
 * encrypted game to game). Both sides must have the same EngineerClient version.
 */
object P3Party {
    private const val RELAY = "wss://undonecoffee.com/p3sim/api"
    /** Bytes per relay message, at most. */
    private const val CHUNK = 32 * 1024

    /** Set while hosting: the server's published listener binds 127.0.0.1 and isn't announced on the LAN. */
    @JvmStatic @Volatile var loopbackOnly = false
        private set

    private val mc get() = net.minecraft.client.Minecraft.getInstance()

    /**
     * EngineerClient's version, without the build part or the channel (the relay matches host and joiner on it):
     * 2026.10.12-beta+44b18f5, 2026.10.12+44b18f5.local and 2026.10.12 all join each other.
     */
    private val version: String by lazy {
        FabricLoader.getInstance().getModContainer("engineerclient").map { it.metadata.version.friendlyString }.orElse("?").substringBefore('+').substringBefore('-')
    }

    private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build() }

    // ------------------------------------------------------------------ hosting

    private class Host(val port: Int) {
        @Volatile var ws: WebSocket? = null
        @Volatile var code: String? = null
        val sockets = ConcurrentHashMap<Int, Socket>()
        val sendLock = Any()
    }

    @Volatile private var host: Host? = null

    val hosting get() = host != null
    /** The party's code, null until the relay gives it. */
    val code: String? get() = host?.code

    /** Everyone in your sim (you first). */
    fun players(): List<String> = runCatching { SimServer.server?.playerList?.players?.map { it.gameProfile.name }.orEmpty() }.getOrDefault(emptyList())

    /** Opens your sim to a party (the menu's Host Party). */
    fun host() {
        if (host != null) return
        val server = mc.singleplayerServer ?: return
        if (!SimServer.isSim(server)) return
        loopbackOnly = true
        val port = if (server.isPublished) server.port else HttpUtil.getAvailablePort()
        if (!server.isPublished && !server.publishServer(MinecraftServer.MultiplayerScope.LAN, false, port)) {
            loopbackOnly = false
            EngineerClient.msg("§cCouldn't open your sim to a party.")
            return
        }
        val h = Host(port)
        host = h
        connect("$RELAY/host?v=${enc(version)}",
            onText = { t -> onHostText(h, t) },
            onBinary = { b -> if (b.isNotEmpty()) h.sockets[b[0].toInt() and 0xff]?.let { s -> runCatching { s.getOutputStream().write(b, 1, b.size - 1) }.onFailure { runCatching { s.close() } } } },
            onClose = { why -> if (host === h) { EngineerClient.msg("§cThe party closed${why?.let { ": $it" } ?: ""}."); mc.execute { stopHosting() } } },
        ).whenComplete { ws, err ->
            if (err != null) { EngineerClient.msg("§cCouldn't reach the party relay: ${err.cause?.message ?: err.message}"); mc.execute { stopHosting() } }
            else h.ws = ws
        }
    }

    private fun onHostText(h: Host, text: String) {
        val m = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return
        when (m.get("t")?.asString) {
            "code" -> { h.code = m.get("code").asString; EngineerClient.msg("§7Party code: §b§l${h.code}§7") }
            "open" -> {
                val id = m.get("id").asInt
                // A connection into your own server for this joiner; what it sends back goes to the relay as [id][bytes].
                val s = runCatching { Socket(InetAddress.getLoopbackAddress(), h.port).also { it.tcpNoDelay = true } }.getOrElse {
                    sendText(h, """{"t":"close","id":$id}"""); return
                }
                h.sockets[id] = s
                pump("p3party-host-$id", s, { buf, n -> sendBinary(h, ByteArray(n + 1).also { it[0] = id.toByte(); System.arraycopy(buf, 0, it, 1, n) }) }) {
                    if (h.sockets.remove(id, s)) sendText(h, """{"t":"close","id":$id}""")
                }
            }
            "close" -> h.sockets.remove(m.get("id").asInt)?.let { runCatching { it.close() } }
        }
    }

    private fun sendText(h: Host, t: String) = synchronized(h.sendLock) { runCatching { h.ws?.sendText(t, true)?.get(10, TimeUnit.SECONDS) } }
    private fun sendBinary(h: Host, b: ByteArray) = synchronized(h.sendLock) { h.ws?.sendBinary(ByteBuffer.wrap(b), true)?.get(30, TimeUnit.SECONDS) }

    /** Closes the party: joiners are dropped, your sim goes on alone ([unpublish] false: the server is stopping anyway). */
    fun stopHosting(unpublish: Boolean = true) {
        val h = host ?: return
        host = null
        h.sockets.values.forEach { runCatching { it.close() } }
        h.sockets.clear()
        runCatching { h.ws?.sendClose(WebSocket.NORMAL_CLOSURE, "closed") }
        runCatching { h.ws?.abort() }
        if (unpublish) mc.singleplayerServer?.let { s -> if (SimServer.isSim(s) && s.isPublished) runCatching { s.unpublishServer() } }
        loopbackOnly = false
    }

    // ------------------------------------------------------------------ joining

    /** Joins the party with [code] (the menu's Join): leaves where you are and connects to the host. */
    fun join(code: String) {
        val c = code.trim().uppercase()
        if (!Regex("[A-HJ-NP-Z2-9]{4}").matches(c)) { EngineerClient.msg("§cA party code is 4 letters and numbers."); return }
        if (host != null) { EngineerClient.msg("§cClose your own party first."); return }
        val local = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val sock = CompletableFuture<Socket>()
        val lock = Any()
        var wsRef: WebSocket? = null
        connect("$RELAY/join/$c?v=${enc(version)}",
            onText = { t ->
                val m = runCatching { JsonParser.parseString(t).asJsonObject }.getOrNull()
                when (m?.get("t")?.asString) {
                    "error" -> { runCatching { local.close() }; EngineerClient.msg("§c${m.get("msg").asString}") }
                    "ok" -> {
                        // The game connects here; what it sends goes to the relay, and the relay's bytes come back to it.
                        Thread({
                            val s = runCatching { local.soTimeout = 30_000; local.accept().also { it.tcpNoDelay = true } }.getOrNull()
                            runCatching { local.close() }
                            if (s == null) { runCatching { wsRef?.abort() }; return@Thread }
                            sock.complete(s)
                            pump("p3party-join", s, { buf, n -> synchronized(lock) { wsRef?.sendBinary(ByteBuffer.wrap(buf, 0, n), true)?.get(30, TimeUnit.SECONDS) } }) {
                                runCatching { wsRef?.sendClose(WebSocket.NORMAL_CLOSURE, "left") }
                            }
                        }, "p3party-accept").apply { isDaemon = true }.start()
                        mc.execute {
                            if (mc.level != null) mc.disconnectFromWorld(Component.literal("Joining party $c"))
                            ConnectScreen.startConnecting(TitleScreen(), mc, ServerAddress("127.0.0.1", local.localPort),
                                ServerData("P3 Sim party $c", "127.0.0.1:${local.localPort}", ServerData.Type.OTHER), false, null)
                        }
                    }
                }
            },
            onBinary = { b -> runCatching { sock.get(30, TimeUnit.SECONDS).getOutputStream().write(b) } },
            onClose = { _ -> runCatching { local.close() }; if (sock.isDone) runCatching { sock.get().close() } },
        ).whenComplete { ws, err ->
            if (err != null) { runCatching { local.close() }; EngineerClient.msg("§cCouldn't reach the party relay: ${err.cause?.message ?: err.message}") }
            else wsRef = ws
        }
    }

    // ------------------------------------------------------------------ plumbing

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    /** Reads [s] until it closes, handing each read to [send]; then [done]. */
    private fun pump(name: String, s: Socket, send: (ByteArray, Int) -> Unit, done: () -> Unit) {
        Thread({
            val buf = ByteArray(CHUNK)
            try {
                val inp = s.getInputStream()
                while (true) { val n = inp.read(buf); if (n < 0) break; if (n > 0) send(buf, n) }
            } catch (_: Exception) {
            } finally { runCatching { s.close() }; done() }
        }, name).apply { isDaemon = true }.start()
    }

    /** A WebSocket to the relay; messages whole (text or binary), [onClose] once with the reason given, if any. */
    private fun connect(url: String, onText: (String) -> Unit, onBinary: (ByteArray) -> Unit, onClose: (String?) -> Unit): CompletableFuture<WebSocket> {
        val listener = object : WebSocket.Listener {
            private val text = StringBuilder()
            private val bin = ByteArrayOutputStream()
            private var closed = false
            private fun close(why: String?) { if (!closed) { closed = true; EngineerClient.safely("p3party close") { onClose(why) } } }
            override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                text.append(data)
                if (last) { val t = text.toString(); text.setLength(0); EngineerClient.safely("p3party text") { onText(t) } }
                ws.request(1); return null
            }
            override fun onBinary(ws: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
                val a = ByteArray(data.remaining()); data.get(a); bin.write(a)
                if (last) { val b = bin.toByteArray(); bin.reset(); EngineerClient.safely("p3party bytes") { onBinary(b) } }
                ws.request(1); return null
            }
            override fun onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? { close(reason.takeIf { it.isNotBlank() && it != "bye" }); return null }
            override fun onError(ws: WebSocket, error: Throwable) { close(error.message) }
        }
        return http.newWebSocketBuilder().buildAsync(URI.create(url), listener)
    }
}
