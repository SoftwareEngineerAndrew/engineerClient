package com.engineerclient.betterpf

import com.engineerclient.EngineerClient
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.authlib.exceptions.AuthenticationException
import com.odtheking.odin.utils.sendCommand
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap

/**
 * Better PF's link to the web party finder (undonecoffee.com/betterpf/menu, "Players"): signing in,
 * and a live connection so the page can see where you are and run your own party commands.
 *
 * Signing in ([newCode]): a random code, and Mojang told this account is joining a "server" whose id
 * is made from it - what every Minecraft server checks at login, so it proves the account. The site
 * asks Mojang before taking the code. The code goes to the clipboard, never on screen; a new one
 * signs the old one out everywhere.
 *
 * While signed in and Better PF is on, a WebSocket stays open: this game's whereabouts go out when
 * they change (in a dungeon, which floor), and the site sends back only the party commands this
 * player pressed on the page (/p invite, /p accept, /p leave), and other players' requests as chat
 * lines with a button.
 */
object WebParty {
    private const val SITE = "undonecoffee.com"
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    /** The only commands the site may have this game run. */
    private val COMMAND = Regex("""^p (?:invite|accept) \w{1,16}$|^p leave$""")
    private val NAME = Regex("""^\w{1,16}$""")
    /** Hypixel's party invite: "[MVP+] Name has invited you to join their party!" (or "...join Other's party!"). */
    private val INVITED = Regex("""^(?:\[[^\]]+] )?(\w{1,16}) has invited you to join (?:their|(?:\[[^\]]+] )?(\w{1,16})'s) party!""", RegexOption.MULTILINE)

    @Volatile private var socket: WebSocket? = null
    @Volatile private var connecting = false
    /** Signed out elsewhere (a newer code, or the same code in another game): off until a new code. */
    @Volatile private var stopped = false
    @Volatile private var retryAt = 0L
    /** Which sign-in a connection belongs to: one from before a new code can't switch the new one off. */
    @Volatile private var generation = 0
    private var backoff = 5_000L
    private var lastState = ""
    private var stateSince = 0L
    private var lastPing = 0L
    /** Players this one asked to join: their invite is taken as soon as it comes (name -> until). */
    private val expecting = ConcurrentHashMap<String, Long>()

    // ------------------------------------------------------------------ signing in

    /** Better PF's "Copy Web Sign-in Code": a new code on the clipboard (the old one signed out). */
    fun newCode() {
        Thread.ofPlatform().name("engineerclient-betterpf-signin").daemon(true).start {
            try {
                val mc = EngineerClient.mc
                val user = mc.user
                val code = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
                // Mojang only ever sees a hash of the code.
                mc.services().sessionService().joinServer(user.profileId, user.accessToken, sha256("betterpf-lfg|$code").substring(0, 40))
                val body = JsonObject().apply { addProperty("name", user.name); addProperty("code", code) }
                val res = http.send(HttpRequest.newBuilder(URI.create("https://$SITE/betterpf/api/lfg/claim")).header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(20)).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString())
                if (res.statusCode() != 200) {
                    EngineerClient.msg("§cBetter PF: the site didn't take the code (${res.body().take(80)}). Try again in a minute.")
                    return@start
                }
                BetterPF.webCode = code
                com.odtheking.odin.features.ModuleManager.saveConfigurations()
                mc.execute { mc.keyboardHandler.clipboard = code }
                val url = "https://$SITE/betterpf/menu"
                EngineerClient.msg(Component.literal("§aSign-in code copied§7 - paste it at the top of ").append(
                    Component.literal("§b§n$SITE/betterpf/menu").withStyle { s ->
                        s.withClickEvent(ClickEvent.OpenUrl(URI.create(url))).withHoverEvent(HoverEvent.ShowText(Component.literal("§7$url")))
                    }
                ).append(Component.literal("§7. It isn't shown anywhere; any older code is signed out.")))
                generation++
                close()
                stopped = false
                retryAt = 0L
                backoff = 5_000L
            } catch (e: AuthenticationException) {
                EngineerClient.msg("§cBetter PF: Minecraft wouldn't confirm your account (${e.message ?: "no reason given"}). Try again in a minute.")
            } catch (t: Throwable) {
                EngineerClient.logger.warn("[ec] betterpf: web sign-in failed", t)
                EngineerClient.msg("§cBetter PF: couldn't get a code - the site can't be reached.")
            }
        }
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------ the connection

    /** Every client tick while Better PF is on: keeps the connection, and says where you are when it changes. */
    fun tick() {
        val code = BetterPF.webCode
        if (code.length != 43 || stopped) return
        val now = System.currentTimeMillis()
        val ws = socket
        if (ws == null) {
            if (!connecting && now >= retryAt) connect(code)
            return
        }
        val floor = DungeonUtils.floor?.name
        val state = if (floor != null) "dungeon|$floor" else "game"
        if (state != lastState) {
            if (state.substringBefore('|') != lastState.substringBefore('|') || stateSince == 0L) stateSince = now
            lastState = state
            send(JsonObject().apply {
                addProperty("t", "state"); addProperty("where", state.substringBefore('|'))
                if (floor != null) addProperty("floor", floor)
                addProperty("since", stateSince)
            })
        }
        // (Answered by the site without waking it up.)
        if (now - lastPing > 30_000) { lastPing = now; runCatching { ws.sendText("ping", true) } }
    }

    private fun connect(code: String) {
        connecting = true
        val gen = generation
        http.newWebSocketBuilder().header("Authorization", "Bearer $code").connectTimeout(Duration.ofSeconds(15))
            .buildAsync(URI.create("wss://$SITE/betterpf/api/lfg/game"), Listener(gen))
            .whenComplete { ws, err ->
                connecting = false
                if (err == null && (gen != generation || socket != null)) { runCatching { ws.sendClose(WebSocket.NORMAL_CLOSURE, "extra") }; return@whenComplete }
                if (gen != generation) return@whenComplete
                if (err == null) { socket = ws; backoff = 5_000L; lastState = ""; return@whenComplete }
                val status = (err.cause as? WebSocketHandshakeException ?: err as? WebSocketHandshakeException)?.response?.statusCode()
                if (status == 401) {
                    stopped = true
                    EngineerClient.msg("§7Better PF: signed out of the web party finder - §fCopy Web Sign-in Code§7 in Better PF to sign in again.")
                } else retryLater()
            }
    }

    private fun retryLater() {
        socket = null
        retryAt = System.currentTimeMillis() + backoff
        backoff = (backoff * 2).coerceAtMost(5 * 60_000L)
    }

    private fun send(o: JsonObject) { socket?.let { runCatching { it.sendText(o.toString(), true) } } }

    /** Better PF turned off (or a new code): the connection closed. */
    fun close() {
        val ws = socket
        socket = null
        lastState = ""
        runCatching { ws?.sendClose(WebSocket.NORMAL_CLOSURE, "bye") }
    }

    private class Listener(private val gen: Int) : WebSocket.Listener {
        private val text = StringBuilder()

        override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            text.append(data)
            if (last) {
                val m = text.toString()
                text.setLength(0)
                if (m != "pong" && m.length < 16_384 && gen == generation) EngineerClient.safely("betterpf web message") { message(m) }
            }
            ws.request(1)
            return null
        }

        override fun onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            if (socket === ws && gen == generation) {
                if (statusCode == 4001 || statusCode == 4002) { stopped = true; socket = null } else retryLater()
            }
            return null
        }

        override fun onError(ws: WebSocket, error: Throwable) {
            if (socket === ws && gen == generation) retryLater()
        }
    }

    private fun message(m: String) {
        val o = JsonParser.parseString(m).asJsonObject
        val str = { k: String -> o[k]?.takeIf { it.isJsonPrimitive }?.asString }
        when (str("t")) {
            // Only what this player pressed on the page; checked again here all the same.
            "run" -> {
                val cmd = str("cmd") ?: return
                if (!COMMAND.matches(cmd)) return
                EngineerClient.mc.execute {
                    sendCommand(cmd)
                    EngineerClient.msg("§7Better PF (web): §f/$cmd")
                }
            }
            "notice" -> {
                val text = str("text")?.take(300) ?: return
                val cmd = str("cmd")?.takeIf { COMMAND.matches(it) }
                val line = Component.literal("§7Better PF: $text")
                if (cmd != null) line.append(Component.literal(" §a§l[${str("label")?.take(16) ?: "Yes"}]").withStyle { s ->
                    s.withClickEvent(ClickEvent.RunCommand("/$cmd")).withHoverEvent(HoverEvent.ShowText(Component.literal("§7/$cmd")))
                })
                EngineerClient.msg(line)
            }
            "expect" -> {
                val from = str("from")?.takeIf { NAME.matches(it) } ?: return
                expecting[from.lowercase()] = System.currentTimeMillis() + 3 * 60_000L
            }
            "bye" -> { stopped = true; socket = null }
        }
    }

    /** A system chat line (plain text): an invite from someone this player asked to join is taken. */
    fun onChat(text: String) {
        if (expecting.isEmpty()) return
        val found = INVITED.find(text) ?: return
        val from = found.groupValues[1]
        val until = expecting.remove(from.lowercase()) ?: return
        if (until < System.currentTimeMillis()) return
        // Into someone else's party (they're a member inviting): its leader's name too, a moment
        // later, in case that's the one Hypixel wants - the wrong one is just "no invite".
        val leader = found.groupValues[2].takeIf { it.isNotEmpty() && !it.equals(from, true) }
        EngineerClient.mc.execute {
            sendCommand("p accept $from")
            EngineerClient.msg("§7Better PF (web): joining §f${leader ?: from}§7's party - you asked to join.")
        }
        if (leader != null) Thread.ofVirtual().start {
            Thread.sleep(1500)
            EngineerClient.mc.execute { sendCommand("p accept $leader") }
        }
    }

    // ------------------------------------------------------------------ /betterpf lfg

    /** /betterpf lfg [f7|m7]: say you're looking to play (the page's last settings); [floor] null: as last time. */
    fun look(floor: String?) {
        if (!lookReady()) return
        send(JsonObject().apply { addProperty("t", "look"); if (floor != null) addProperty("floor", floor) })
    }

    /** /betterpf lfg off. */
    fun unlook() {
        if (!lookReady()) return
        send(JsonObject().apply { addProperty("t", "unlook") })
    }

    private fun lookReady(): Boolean {
        if (BetterPF.webCode.length != 43 || stopped) {
            EngineerClient.msg("§7Better PF: sign in first - §fCopy Web Sign-in Code§7 in Better PF's settings, then paste it at §b$SITE/betterpf/menu§7.")
            return false
        }
        if (socket == null) {
            EngineerClient.msg("§7Better PF: not connected to the site yet - try again in a few seconds.")
            return false
        }
        return true
    }
}
