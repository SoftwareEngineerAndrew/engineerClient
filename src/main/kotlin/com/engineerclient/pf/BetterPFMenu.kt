package com.engineerclient.pf

import com.engineerclient.EngineerClient
import com.engineerclient.betterpf.BetterPF
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.world.inventory.ContainerInput
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors

/**
 * Better PF Menu: while it's on and you're in a world, undonecoffee.com/betterpf/menu lists you
 * ("not in the menu"), and while the Party Finder menu is open, what it lists. Someone on that page
 * can turn on auto refresh: this clicks the menu's Refresh button every 7.5-9.5 s (random each time), for as long as they
 * watch and the menu is open.
 *
 * One WebSocket (/betterpf/api/pfmenu/share) for as long as you're in a world. The menu is read on
 * the client thread once a second and sent only when it changed; a closed menu counts as still open
 * for a few seconds (so the menus a party opens on click don't flicker you off it).
 */
object BetterPFMenu : Module(
    name = "Better PF Menu",
    category = Category.custom("Engineer Client", 1030, 10),
    description = "Shows your Party Finder menu live on undonecoffee.com/betterpf/menu while you have it open. People there can make it auto refresh.",
) {
    private val allowAuto by BooleanSetting("Allow Auto Refresh", true, desc = "Lets someone watching your menu on the site make it click Refresh every 7.5-9.5 seconds (random) while it's open.")
    private val autoMessage by BooleanSetting("Auto Refresh Message", true, desc = "Says in chat when auto refresh is turned on or off from the site.")

    private const val URL = "wss://${BetterPF.SITE}/betterpf/api/pfmenu/share"
    /** Auto refresh's gap, picked anew after each click: 7.5 to 9.5 s. */
    private fun refreshGap() = 7_500L + (Math.random() * 2_000).toLong()
    private const val LINGER_MS = 5_000L
    private const val RESEND_MS = 15_000L
    private const val PING_MS = 30_000L

    private val CONTROL_CODES = Regex("§.")
    private val memberLine = Regex("^(\\w{1,16}): (\\w+) \\((\\d+)\\)$")

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    /** Everything on the socket happens on this one thread; the game thread only hands it text. */
    private val io = Executors.newSingleThreadScheduledExecutor { Thread(it, "ec-pfmenu").apply { isDaemon = true } }

    // io thread
    private var ws: WebSocket? = null
    private var connecting = false
    private var generation = 0
    private var nextTry = 0L
    private var backoffMs = 1_000L
    private var pending: String? = null
    private var failSaid = false

    // client thread
    @Volatile private var auto = false
    private var lastOpen = 0L
    private var lastRead = 0L
    private var lastSent = ""
    private var lastSentAt = 0L
    private var lastRefresh = 0L
    private var refreshGap = 0L
    private var lastPing = 0L
    private var inWorldAt = 0L

    init {
        on<TickEvent.End> { EngineerClient.safely("pf menu tick") { tick() } }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        if (EngineerClient.mc.player == null) {
            // Out of the world (a server switch is a moment of this): gone from the page after a while.
            if (inWorldAt != 0L && now - inWorldAt > LINGER_MS) { inWorldAt = 0L; lastOpen = 0L; lastSent = ""; io.execute { disconnect("left the world") } }
            return
        }
        inWorldAt = now
        val screen = EngineerClient.mc.screen as? AbstractContainerScreen<*>
        val open = screen != null && clean(screen.title.string).startsWith("Party Finder")
        if (open) lastOpen = now
        if (now - lastRead >= 1_000L) {
            lastRead = now
            // Just closed: the page keeps what the menu last showed for a few seconds.
            val body = when {
                open -> read(screen!!).toString()
                lastOpen != 0L && now - lastOpen <= LINGER_MS -> null
                else -> closed().toString()
            }
            if (body != null && (body != lastSent || now - lastSentAt > RESEND_MS)) {
                lastSent = body; lastSentAt = now
                io.execute { pending = body; flush() }
            }
        }
        if (now - lastPing >= PING_MS) { lastPing = now; io.execute { ws?.sendText("ping", true) } }
        if (open && auto && allowAuto && now - lastRefresh >= refreshGap) {
            lastRefresh = now
            refreshGap = refreshGap()
            clickRefresh(screen!!)
        }
    }

    /** On, but no Party Finder open. */
    private fun closed() = JsonObject().apply {
        addProperty("t", "menu")
        addProperty("name", EngineerClient.mc.user.name)
        addProperty("open", false)
        add("parties", JsonArray())
    }

    /** The menu as the page wants it: each party's leader, floor, note, members and its lines as shown. */
    private fun read(screen: AbstractContainerScreen<*>): JsonObject {
        val parties = JsonArray()
        var refresh = false
        for (slot in screen.menu.slots) {
            if (slot.container === EngineerClient.mc.player?.inventory) continue
            val stack = slot.item
            if (stack.isEmpty) continue
            val name = clean(stack.hoverName.string).trim()
            if (isRefresh(name)) { refresh = true; continue }
            if (!name.endsWith("'s Party")) continue
            val lore = stack.get(DataComponents.LORE)?.lines().orEmpty()
            val party = JsonObject()
            party.addProperty("leader", name.removeSuffix("'s Party"))
            val lines = JsonArray()
            val members = JsonArray()
            var inMembers = false
            var floor: String? = null
            var master = false
            for (line in lore) {
                lines.add(BetterPF.legacyText(line))
                val text = clean(line.string).trim()
                when {
                    text.startsWith("Dungeon: ") -> text.removePrefix("Dungeon: ").let { party.addProperty("dungeon", it); master = it.contains("Master", ignoreCase = true) }
                    text.startsWith("Floor: ") -> text.removePrefix("Floor: ").let { party.addProperty("floor", it); floor = PartyFinderStats.romanFloors[it.trim()] }
                    text.startsWith("Note: ") -> party.addProperty("note", text.removePrefix("Note: "))
                    text.startsWith("Members:") -> inMembers = true
                    inMembers -> memberLine.find(text)?.let { m ->
                        members.add(JsonObject().apply {
                            addProperty("name", m.groupValues[1]); addProperty("cls", m.groupValues[2]); addProperty("level", m.groupValues[3].toInt())
                            // Party Finder Stats' numbers, when it has them (they fill in on later reads).
                            PartyFinderStats.webStats(m.groupValues[1], floor, master)?.let { add("stats", it) }
                        })
                    }
                }
            }
            party.add("lore", lines)
            party.add("members", members)
            parties.add(party)
        }
        return JsonObject().apply {
            addProperty("t", "menu")
            addProperty("name", EngineerClient.mc.user.name)
            addProperty("open", true)
            addProperty("title", clean(screen.title.string))
            addProperty("refresh", refresh)
            add("parties", parties)
        }
    }

    /** One left click on the menu's Refresh button, as a click of your own would send it. */
    private fun clickRefresh(screen: AbstractContainerScreen<*>) {
        val player = EngineerClient.mc.player ?: return
        if (!screen.menu.carried.isEmpty) return
        val slot = screen.menu.slots.firstOrNull { it.container !== player.inventory && isRefresh(clean(it.item.hoverName.string).trim()) } ?: return
        EngineerClient.mc.gameMode?.handleContainerInput(screen.menu.containerId, slot.index, 0, ContainerInput.PICKUP, player)
    }

    // ---------------------------------------------------------------- socket (io thread)

    private fun flush() {
        val socket = ws
        if (socket == null) { connect(); return }
        val body = pending ?: return
        pending = null
        socket.sendText(body, true).exceptionally { t -> io.execute { fail("send: $t") }; null }
    }

    private fun connect() {
        if (ws != null || connecting || System.currentTimeMillis() < nextTry) return
        connecting = true
        val gen = ++generation
        val listener = object : WebSocket.Listener {
            private val text = StringBuilder()
            override fun onOpen(webSocket: WebSocket) {
                io.execute {
                    connecting = false
                    if (gen != generation) { webSocket.abort(); return@execute }
                    ws = webSocket; backoffMs = 1_000L
                    if (failSaid) EngineerClient.msg("§7Better PF Menu: connected again.")
                    failSaid = false
                    EngineerClient.logger.info("[ec] pf menu: connected")
                    flush()
                }
                webSocket.request(1)
            }
            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                text.append(data)
                if (last) { val msg = text.toString(); text.setLength(0); io.execute { if (gen == generation) said(msg) } }
                webSocket.request(1)
                return null
            }
            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                io.execute { if (gen == generation) fail("closed $statusCode $reason") }
                return null
            }
            override fun onError(webSocket: WebSocket, error: Throwable) {
                io.execute { if (gen == generation) fail(error.toString()) }
            }
        }
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(URI.create(URL), listener)
            .whenCompleteAsync({ socket, err ->
                if (gen != generation) { socket?.abort(); return@whenCompleteAsync }
                if (err != null) { connecting = false; fail((err.cause ?: err).toString()) }
            }, io)
    }

    /** The site: {t:"auto", on}. */
    private fun said(msg: String) {
        val obj = runCatching { JsonParser.parseString(msg).asJsonObject }.getOrNull() ?: return
        if (obj.get("t")?.asString != "auto") return
        val on = obj.get("on")?.asBoolean == true
        EngineerClient.mc.execute {
            if (on == auto) return@execute
            auto = on
            if (autoMessage) EngineerClient.msg(
                if (!on) "§7Better PF Menu: auto refresh off."
                else if (allowAuto) "§7Better PF Menu: auto refresh on from the site (Refresh every 7.5-9.5s while the menu is open)."
                else "§7Better PF Menu: the site asked for auto refresh; §fAllow Auto Refresh§7 is off."
            )
        }
    }

    /** Lost the connection: try again, a little later each time. The next menu read resends. */
    private fun fail(why: String) {
        EngineerClient.logger.warn("[ec] pf menu: connection failed: $why")
        if (!failSaid) { failSaid = true; EngineerClient.msg("§cBetter PF Menu: can't reach ${BetterPF.SITE} ($why). Retrying.") }
        disconnect("failed")
        nextTry = System.currentTimeMillis() + backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        EngineerClient.mc.execute { lastSent = "" }
    }

    private fun disconnect(why: String) {
        generation++
        ws?.let { runCatching { it.sendClose(WebSocket.NORMAL_CLOSURE, why) }; it.abort() }
        ws = null; connecting = false; pending = null
        EngineerClient.mc.execute { auto = false }
    }

    override fun onDisable() {
        super.onDisable()
        lastOpen = 0L; lastSent = ""; inWorldAt = 0L
        io.execute { disconnect("off") }
    }

    private fun isRefresh(name: String) = name == "Refresh" || name.startsWith("Refresh ")

    private fun clean(s: String) = CONTROL_CODES.replace(s, "")
}
