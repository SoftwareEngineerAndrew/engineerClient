package com.engineerclient.adr

import com.engineerclient.EngineerClient
import com.engineerclient.pf.PlayerStats
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletionStage

/**
 * ADR Online (undonecoffee.com/adr/online): while ADR is on, a WebSocket to the
 * site says who you are, your party ([PartyTracker]), where you are and your PBs, so the page can
 * list everyone online by party. It signs in with this install's owner token (the one uploads
 * carry); nothing comes back but pongs.
 *
 * [openPage] signs the browser in too: a fresh key, registered with the site under the owner token,
 * goes to the page in the link's #fragment (never sent anywhere else). A new key replaces the last.
 */
object OnlineLink {
    private const val SITE = "undonecoffee.com"
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var connecting = false
    /** Replaced by this install's own game elsewhere: off until a restart. */
    @Volatile private var stopped = false
    @Volatile private var retryAt = 0L
    private var backoff = 5_000L
    private var sentParty = -1
    private var partyChangedAt = 0L
    private var sentWhere = ""
    private var sentStats = ""
    private var lastPing = 0L

    /** Every client tick while ADR is on. */
    fun tick() {
        if (stopped) return
        val now = System.currentTimeMillis()
        val ws = socket
        if (ws == null) {
            if (!connecting && now >= retryAt && EngineerClient.mc.player != null) connect()
            return
        }
        // The party: once it has held still a moment (a /p list or a Party Finder join comes in bursts).
        val v = PartyTracker.state.version
        if (v != sentParty) {
            if (partyChangedAt == 0L) partyChangedAt = now
            if (now - partyChangedAt > 400) { sentParty = v; partyChangedAt = 0L; send(party()) }
        }
        val floor = DungeonUtils.floor?.name
        val where = if (floor != null) "dungeon|$floor" else "game"
        if (where != sentWhere) {
            sentWhere = where
            send(JsonObject().apply { addProperty("t", "where"); addProperty("where", where.substringBefore('|')); floor?.let { addProperty("floor", it) }; addProperty("since", now) })
        }
        if (now - lastPing > 30_000) {
            lastPing = now
            runCatching { ws.sendText("ping", true) }
            stats()?.let { if (it.toString() != sentStats) { sentStats = it.toString(); send(it) } }
        }
    }

    private fun party(): JsonObject = synchronized(PartyTracker.state) {
        val s = PartyTracker.state
        JsonObject().apply {
            addProperty("t", "party")
            addProperty("known", s.known)
            s.doubt?.let { addProperty("doubt", it) }
            add("members", JsonArray().apply {
                for (m in s.members.values) add(JsonObject().apply {
                    addProperty("n", m.name)
                    addProperty("r", when (m.role) { PartyState.Role.LEADER -> "L"; PartyState.Role.MOD -> "M"; else -> "" })
                    if (!m.online) addProperty("off", true)
                })
            })
        }
    }

    /** Your own PBs and Catacombs, once looked up (ADR's Party Finder stats). */
    private fun stats(): JsonObject? {
        val st = (PlayerStats.lookup(EngineerClient.mc.user.name) as? PlayerStats.Ready)?.stats ?: return null
        return JsonObject().apply {
            addProperty("t", "stats")
            addProperty("cataXp", st.cataXp)
            st.sPlus["f"]?.get("7")?.let { addProperty("f7", it.toLong()) }
            st.sPlus["m"]?.get("7")?.let { addProperty("m7", it.toLong()) }
        }
    }

    private fun hello() = JsonObject().apply {
        val u = EngineerClient.mc.user
        addProperty("t", "hello"); addProperty("name", u.name); addProperty("uuid", u.profileId.toString().replace("-", ""))
    }

    private fun connect() {
        connecting = true
        http.newWebSocketBuilder().header("Authorization", "Bearer ${ADR.token()}").connectTimeout(Duration.ofSeconds(15))
            .buildAsync(URI.create("wss://$SITE/adr/api/online/game"), Listener())
            .whenComplete { ws, err ->
                connecting = false
                if (err != null) { retryLater(); return@whenComplete }
                socket = ws
                backoff = 5_000L
                sentParty = -1; sentWhere = ""; sentStats = ""; lastPing = 0L
                runCatching { ws.sendText(hello().toString(), true) }
            }
    }

    private fun retryLater() {
        socket = null
        retryAt = System.currentTimeMillis() + backoff
        backoff = (backoff * 2).coerceAtMost(5 * 60_000L)
    }

    private fun send(o: JsonObject) { socket?.let { runCatching { it.sendText(o.toString(), true) } } }

    /** ADR turned off: offline. */
    fun close() {
        val ws = socket
        socket = null
        runCatching { ws?.sendClose(WebSocket.NORMAL_CLOSURE, "bye") }
    }

    private class Listener : WebSocket.Listener {
        override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? { ws.request(1); return null }
        override fun onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            if (socket === ws) { if (statusCode == 4002) { stopped = true; socket = null } else retryLater() }
            return null
        }
        override fun onError(ws: WebSocket, error: Throwable) { if (socket === ws) retryLater() }
    }

    // ------------------------------------------------------------------ signing the page in

    /** ADR's "Open Online Page" and /adr online: the page, signed in, in your browser. */
    fun openPage() {
        Thread.ofPlatform().name("engineerclient-adr-online").daemon(true).start {
            try {
                val u = EngineerClient.mc.user
                val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
                val body = JsonObject().apply { addProperty("key", key); addProperty("name", u.name); addProperty("uuid", u.profileId.toString().replace("-", "")) }
                val res = http.send(HttpRequest.newBuilder(URI.create("https://$SITE/adr/api/online/key"))
                    .header("Content-Type", "application/json").header("Authorization", "Bearer ${ADR.token()}")
                    .timeout(Duration.ofSeconds(20)).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString())
                if (res.statusCode() != 200) {
                    EngineerClient.msg("§cADR: the site didn't sign you in (${res.body().take(80)}). Try again in a minute.")
                    return@start
                }
                browse("https://$SITE/adr/online#k=$key")
                EngineerClient.msg("§aADR Online opened in your browser§7 - signed in there (any older sign-in is signed out).")
            } catch (t: Throwable) {
                EngineerClient.logger.warn("[ec] adr: online sign-in failed", t)
                EngineerClient.msg("§cADR: couldn't reach the site.")
            }
        }
    }

    /** Opens [url] in the default browser, without it ever being shown in game. */
    private fun browse(url: String) {
        val os = System.getProperty("os.name").lowercase()
        val cmd = when {
            os.contains("win") -> arrayOf("rundll32", "url.dll,FileProtocolHandler", url)
            os.contains("mac") -> arrayOf("open", url)
            else -> arrayOf("xdg-open", url)
        }
        Runtime.getRuntime().exec(cmd)
    }
}
