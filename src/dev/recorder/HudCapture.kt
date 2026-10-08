package com.devgineerclient.recorder

import com.devgineerclient.DevgineerClient
import com.devgineerclient.mixin.RecBossOverlayAccessor
import com.devgineerclient.mixin.RecGuiAccessor
import com.devgineerclient.mixin.RecTabOverlayAccessor
import com.odtheking.odin.events.RenderBossBarEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.gui.components.toasts.Toast
import net.minecraft.client.multiplayer.chat.GuiMessage
import net.minecraft.client.multiplayer.chat.GuiMessageSource
import net.minecraft.client.multiplayer.chat.GuiMessageTag
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MessageSignature
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.Objective
import net.minecraft.world.scores.PlayerScoreEntry
import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.scores.Scoreboard
import java.util.Base64
import java.util.UUID

/**
 * What the HUD showed, for the Dungeon Recorder: chat as the chat box got it (offered, shown,
 * deleted, cleared, and what mods dropped before it got there), titles and the action bar as set
 * (by the server or by a mod), toasts, the tab list as drawn, every scoreboard display slot with its
 * styled lines, and the boss bars (with whether a mod hid them).
 *
 * The packets already hold what the server sent; these lines are what a player actually saw after
 * every mod had changed, added or hidden things, which is what a mod built from a recording has to
 * reproduce.
 *
 * Lines: chatui, chatdel, chatclear, chat.dropped, hud, toast, tab, board, kfboard, bars. The tab
 * list, boards and bars are polled every tick and written when they change; a keyframe writes all
 * of them in full (tagged "kf"). Game thread throughout.
 */
object HudCapture {

    private var installed = false
    private var lastSession: RecorderSession? = null

    fun install() {
        if (installed) return
        installed = true
        ClientReceiveMessageEvents.GAME_CANCELED.register { msg, overlay ->
            if (Rec.active) DevgineerClient.safely("recorder chat dropped") { dropped("game", msg, overlay) }
        }
        ClientReceiveMessageEvents.CHAT_CANCELED.register { msg, _, _, _, _ ->
            if (Rec.active) DevgineerClient.safely("recorder chat dropped") { dropped("chat", msg, false) }
        }
        // Lowest priority, cancelled events included: whether the bar was really drawn after every listener.
        on<RenderBossBarEvent>(priority = Int.MIN_VALUE) {
            if (!Rec.active) return@on
            try { drawn[bossBar.id] = !isCancelled } catch (_: Throwable) {}
        }
        on<TickEvent.End> { if (Rec.active) DevgineerClient.safely("recorder hud") { tick() } }
        Rec.onKeyframe("hud") { keyframe() }
        EventBus.subscribe(this)
    }

    // ------------------------------------------------------------------ chat (ChatDisplayTapMixin)

    fun chatOffered(c: Component, sig: MessageSignature?, src: GuiMessageSource?, tag: GuiMessageTag?) {
        if (!Rec.active) return
        Rec.emit("chatui", chatBody("offered", c, sig, src, tag, null))
    }

    fun chatShown(m: GuiMessage) {
        if (!Rec.active) return
        Rec.emit("chatui", chatBody("shown", m.content(), m.signature(), m.source(), m.tag(), m.addedTime()))
    }

    fun chatDeleted(sig: MessageSignature?) {
        if (!Rec.active) return
        Rec.emit("chatdel", "\"sig\":${sig(sig)}")
    }

    fun chatCleared(history: Boolean) {
        if (!Rec.active) return
        Rec.emit("chatclear", "\"history\":$history")
    }

    private fun dropped(src: String, c: Component, overlay: Boolean) {
        val sb = StringBuilder(256)
        sb.append("\"src\":\"").append(src).append("\",\"overlay\":").append(overlay)
        if (Rec.privateText(c.string)) sb.append(",\"hidden\":\"private\"") else { sb.append(",\"c\":"); RichJson.component(sb, c) }
        Rec.emit("chat.dropped", sb.toString())
    }

    private fun chatBody(stage: String, c: Component, sig: MessageSignature?, src: GuiMessageSource?, tag: GuiMessageTag?, added: Int?): String {
        val sb = StringBuilder(256)
        sb.append("\"stage\":\"").append(stage).append('"')
        if (Rec.privateText(c.string)) { sb.append(",\"hidden\":\"private\""); return sb.toString() }
        sb.append(",\"src\":").append(RecorderFiles.q(src?.name))
        sb.append(",\"tag\":").append(RecorderFiles.q(tag?.logTag()))
        if (sig != null) sb.append(",\"sig\":").append(sig(sig))
        if (added != null) sb.append(",\"added\":").append(added)
        sb.append(",\"c\":"); RichJson.component(sb, c)
        return sb.toString()
    }

    /** A message signature as base64, which is how chatdel lines point back at the line they delete. */
    private fun sig(s: MessageSignature?): String =
        if (s == null) "null" else RecorderFiles.q(Base64.getEncoder().encodeToString(s.bytes()))

    // ------------------------------------------------------------------ titles and action bar (GuiTitleTapMixin)

    /** Hypixel re-sends the same action bar many times a second; identical ones are counted, not repeated. */
    private val actionBars = RepeatCounter<Pair<Component, Boolean>>()

    fun actionBar(c: Component, animate: Boolean) {
        if (!Rec.active) return
        val prevRepeats = actionBars.offer(c to animate) ?: return
        val sb = StringBuilder(256)
        sb.append("\"what\":\"actionbar\",\"anim\":").append(animate).append(",\"prevRepeats\":").append(prevRepeats).append(",\"c\":")
        RichJson.component(sb, c)
        Rec.emit("hud", sb.toString())
    }

    fun hudText(what: String, c: Component) {
        if (!Rec.active) return
        val sb = StringBuilder(256)
        sb.append("\"what\":").append(RecorderFiles.q(what)).append(",\"c\":")
        RichJson.component(sb, c)
        Rec.emit("hud", sb.toString())
    }

    fun hudTimes(fadeIn: Int, stay: Int, fadeOut: Int) {
        if (!Rec.active) return
        Rec.emit("hud", "\"what\":\"times\",\"times\":[$fadeIn,$stay,$fadeOut]")
    }

    fun hudPlain(what: String) {
        if (!Rec.active) return
        Rec.emit("hud", "\"what\":${RecorderFiles.q(what)}")
    }

    // ------------------------------------------------------------------ toasts (ToastTapMixin)

    fun toast(t: Toast) {
        if (!Rec.active) return
        val sb = StringBuilder(128)
        sb.append("\"class\":").append(RecorderFiles.q(t.javaClass.name))
        RichJson.member(sb, "token") { val tok = t.token; if (tok === Toast.NO_TOKEN) false else { sb.append(RecorderFiles.q(tok.toString())); true } }
        RichJson.member(sb, "w") { sb.append(t.width()); true }
        RichJson.member(sb, "h") { sb.append(t.height()); true }
        Rec.emit("toast", sb.toString())
    }

    // ------------------------------------------------------------------ per tick: tab, boards, bars

    private val tabRows = RowDiff()
    private var tabHeader: Component? = null
    private var tabFooter: Component? = null
    private var tabOpen: Boolean? = null
    private var tabSeen = false

    private val boardKeys = HashMap<DisplaySlot, String>()
    private val drawn = HashMap<UUID, Boolean>()
    private var lastBars = ""

    private fun tick() {
        val session = Rec.session
        if (session !== lastSession) { lastSession = session; resetDiff() }
        guarded("tab") { tab(full = false) }
        guarded("board") { boards(full = false) }
        guarded("bars") { bars(full = false) }
    }

    private fun resetDiff() {
        tabRows.reset(); tabHeader = null; tabFooter = null; tabOpen = null; tabSeen = false; tabCache.clear()
        boardKeys.clear(); lastBars = ""
        actionBars.reset()
    }

    /** One failing part (a mod's odd tab entry, say) must not stop the others; each logs and carries on. */
    private inline fun guarded(what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) {
            DevgineerClient.logger.error("[dc] recorder $what failed", t)
            Rec.emit("error", "\"p\":${RecorderFiles.q("hud:$what")},\"err\":${RecorderFiles.q(t.toString())}")
        }
    }

    private fun kf(full: Boolean) = if (full) "\"kf\":${Rec.keyframeId}," else ""

    /**
     * What a tab row is made of (the shown name is built from the display name, or the team's
     * decoration of the profile name, and the game mode): equal keys make the same row, so its JSON
     * (a codec encode, about 8 µs) is built only when one of these changed.
     */
    private class TabKey(val disp: Any?, val team: Any?, val prefix: Any?, val suffix: Any?, val color: Any?,
                         val latency: Int, val mode: Any?, val order: Int, val name: String) {
        fun same(o: TabKey) = disp === o.disp && team === o.team && prefix == o.prefix && suffix == o.suffix && color == o.color &&
            latency == o.latency && mode == o.mode && order == o.order && name == o.name
    }
    private class TabRow(val key: TabKey, val json: String)
    /** The last row built for each PlayerInfo shown (game thread). */
    private var tabCache = java.util.IdentityHashMap<Any, TabRow>()

    /** The tab list rows in drawn order; only changed rows are written ([i, ...]) with the row count "count" (not "n": that is the envelope's server tick). */
    private fun tab(full: Boolean) {
        val mc = DevgineerClient.mc
        if (mc.player == null) return
        val overlay = mc.gui.hud.tabList
        val acc = overlay as RecTabOverlayAccessor
        val infos = acc.`dc$recPlayerInfos`()
        if (full) tabCache.clear()
        val next = java.util.IdentityHashMap<Any, TabRow>(infos.size * 2)
        val rows = infos.map { info ->
            val p = info.profile
            val team = info.team
            val key = TabKey(info.tabListDisplayName, team, team?.playerPrefix, team?.playerSuffix, team?.color,
                info.latency, info.gameMode, info.tabListOrder, p.name())
            val cached = tabCache[info]
            val json = if (cached != null && cached.key.same(key)) cached.json else {
                val sb = StringBuilder(160)
                sb.append(RecorderFiles.q(p.id().toString())).append(',').append(RecorderFiles.q(p.name())).append(',')
                RichJson.component(sb, overlay.getNameForDisplay(info))
                sb.append(',').append(info.latency).append(',').append(RecorderFiles.q(info.gameMode.serializedName))
                    .append(',').append(RecorderFiles.q(team?.name)).append(',').append(info.tabListOrder)
                sb.toString()
            }
            next[info] = TabRow(key, json)
            json
        }
        tabCache = next
        if (full) tabRows.reset()
        val d = tabRows.diff(rows)
        val header = acc.`dc$recHeader`()
        val footer = acc.`dc$recFooter`()
        val open = acc.`dc$recVisible`()
        val headerChanged = full || !tabSeen || header != tabHeader
        val footerChanged = full || !tabSeen || footer != tabFooter
        val openChanged = full || open != tabOpen
        if (d.changed.isEmpty() && !d.countChanged && !headerChanged && !footerChanged && !openChanged) return
        tabSeen = true; tabHeader = header; tabFooter = footer; tabOpen = open
        val sb = StringBuilder(512)
        sb.append(kf(full)).append("\"open\":").append(open).append(",\"count\":").append(rows.size)
        if (headerChanged) { sb.append(",\"header\":"); comp(sb, header) }
        if (footerChanged) { sb.append(",\"footer\":"); comp(sb, footer) }
        sb.append(",\"rows\":[")
        d.changed.forEachIndexed { k, i -> if (k > 0) sb.append(','); sb.append('[').append(i).append(',').append(rows[i]).append(']') }
        sb.append(']')
        Rec.emit("tab", sb.toString())
    }

    /** As `Gui.displayScoreboardSidebar` draws them: hidden holders dropped, highest score first, ties on the name, 15 lines. */
    private fun sidebarEntries(board: Scoreboard, obj: Objective): List<PlayerScoreEntry> =
        board.listPlayerScores(obj)
            .filter { !it.isHidden }
            .sortedWith(compareByDescending<PlayerScoreEntry> { it.value() }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.owner() })
            .take(15)

    /**
     * Every display slot that has an objective: its title and lines as drawn. The sidebar uses the
     * same entries, order and 15-line cap the renderer does ([sidebarEntries]); the other slots list
     * every score. A slot that loses its objective is written once with obj null.
     */
    private fun boards(full: Boolean) {
        val board = DevgineerClient.mc.level?.scoreboard ?: return
        if (full) boardKeys.clear()
        for (slot in DisplaySlot.entries) {
            val obj = board.getDisplayObjective(slot)
            if (obj == null) {
                if (boardKeys.remove(slot) != null) Rec.emit("board", "\"slot\":${RecorderFiles.q(slot.serializedName)},\"obj\":null")
                continue
            }
            val entries = if (slot == DisplaySlot.SIDEBAR) sidebarEntries(board, obj)
                else board.listPlayerScores(obj).sortedBy { it.owner() }
            val lines = entries.map { Triple(it, PlayerTeam.formatNameForTeam(board.getPlayersTeam(it.owner()), it.ownerName()), it.display()) }
            // A cheap fingerprint first, so the styled JSON is only built when something changed.
            val key = obj.name + '\u0000' + obj.displayName.hashCode() + '\u0000' +
                lines.joinToString("\u0001") { (e, line, disp) -> "${e.owner()}\u0002${e.value()}\u0002${line.hashCode()}\u0002${disp?.hashCode()}" }
            if (!full && boardKeys[slot] == key) continue
            boardKeys[slot] = key
            val sb = StringBuilder(1024)
            sb.append(kf(full)).append("\"slot\":").append(RecorderFiles.q(slot.serializedName)).append(",\"obj\":").append(RecorderFiles.q(obj.name))
            sb.append(",\"title\":"); RichJson.component(sb, obj.displayName)
            sb.append(",\"lines\":[")
            lines.forEachIndexed { i, (e, line, disp) ->
                if (i > 0) sb.append(',')
                sb.append('[').append(RecorderFiles.q(e.owner())).append(',').append(e.value()).append(',')
                RichJson.component(sb, line)
                if (disp != null) { sb.append(','); RichJson.component(sb, disp) }
                sb.append(']')
            }
            sb.append(']')
            Rec.emit("board", sb.toString())
        }
    }

    /** Boss bars as held by the HUD (progress as drawn, mid-animation), and whether Odin's listeners let it draw. */
    private fun bars(full: Boolean) {
        val events = (DevgineerClient.mc.gui.hud.bossOverlay as RecBossOverlayAccessor).`dc$recEvents`()
        drawn.keys.retainAll(events.keys)
        val sb = StringBuilder(256)
        var i = 0
        for ((id, e) in events) {
            if (i++ > 0) sb.append(',')
            sb.append('[').append(RecorderFiles.q(id.toString())).append(',')
            RichJson.component(sb, e.name)
            sb.append(','); PacketJson.num(sb, e.progress)
            sb.append(',').append(RecorderFiles.q(e.color.getName())).append(',').append(RecorderFiles.q(e.overlay.getName()))
                .append(',').append(e.shouldDarkenScreen()).append(',').append(e.shouldPlayBossMusic()).append(',').append(e.shouldCreateWorldFog())
                .append(',').append(drawn[id]?.toString() ?: "null").append(']')
        }
        val now = sb.toString()
        if (!full && now == lastBars) return
        lastBars = now
        Rec.emit("bars", kf(full) + "\"d\":[$now]")
    }

    // ------------------------------------------------------------------ keyframe

    private fun keyframe() {
        guarded("kf hud") { hudState() }
        guarded("kf tab") { tab(full = true) }
        guarded("kf board") { boards(full = true) }
        guarded("kf board state") { boardState() }
        guarded("kf bars") { bars(full = true) }
    }

    /** What the title and action bar show right now, with their timers. */
    private fun hudState() {
        val g = DevgineerClient.mc.gui.hud as RecGuiAccessor
        val sb = StringBuilder(256)
        sb.append(kf(true)).append("\"what\":\"state\",\"title\":"); comp(sb, g.`dc$recTitle`())
        sb.append(",\"subtitle\":"); comp(sb, g.`dc$recSubtitle`())
        sb.append(",\"titleTime\":").append(g.`dc$recTitleTime`())
        sb.append(",\"times\":[").append(g.`dc$recTitleFadeIn`()).append(',').append(g.`dc$recTitleStay`()).append(',').append(g.`dc$recTitleFadeOut`()).append(']')
        sb.append(",\"actionbar\":"); comp(sb, g.`dc$recOverlayMessage`())
        sb.append(",\"actionbarTime\":").append(g.`dc$recOverlayMessageTime`()).append(",\"anim\":").append(g.`dc$recAnimateOverlay`())
        Rec.emit("hud", sb.toString())
    }

    /** The whole scoreboard: every objective, every team and every score, for a reader starting mid-file. */
    private fun boardState() {
        val board = DevgineerClient.mc.level?.scoreboard ?: return
        val sb = StringBuilder(4096)
        sb.append("\"kf\":").append(Rec.keyframeId).append(",\"objectives\":[")
        board.objectives.forEachIndexed { i, o -> if (i > 0) sb.append(','); objective(sb, board, o) }
        sb.append("],\"teams\":[")
        board.playerTeams.forEachIndexed { i, t ->
            if (i > 0) sb.append(',')
            sb.append('[').append(RecorderFiles.q(t.name)).append(',')
            RichJson.component(sb, t.displayName); sb.append(',')
            RichJson.component(sb, t.playerPrefix); sb.append(',')
            RichJson.component(sb, t.playerSuffix); sb.append(',')
            sb.append(RecorderFiles.q(t.color.map { it.serializedName }.orElse("reset"))).append(",[")
            t.players.sorted().forEachIndexed { k, p -> if (k > 0) sb.append(','); sb.append(RecorderFiles.q(p)) }
            sb.append("]]")
        }
        sb.append(']')
        Rec.emit("kfboard", sb.toString())
    }

    private fun objective(sb: StringBuilder, board: Scoreboard, o: Objective) {
        sb.append("{\"name\":").append(RecorderFiles.q(o.name))
        RichJson.member(sb, "criteria") { sb.append(RecorderFiles.q(o.criteria.name)); true }
        sb.append(",\"title\":"); RichJson.component(sb, o.displayName)
        sb.append(",\"render\":").append(RecorderFiles.q(o.renderType.serializedName))
        sb.append(",\"scores\":[")
        board.listPlayerScores(o).sortedBy { it.owner() }.forEachIndexed { i, e ->
            if (i > 0) sb.append(',')
            sb.append('[').append(RecorderFiles.q(e.owner())).append(',').append(e.value())
            e.display()?.let { sb.append(','); RichJson.component(sb, it) }
            sb.append(']')
        }
        sb.append("]}")
    }

    private fun comp(sb: StringBuilder, c: Component?) { if (c == null) sb.append("null") else RichJson.component(sb, c) }
}

/**
 * Which rows of a list changed since the last call: indices whose row differs (or is new), and
 * whether the count changed (rows past the new count are gone). The first call after [reset]
 * reports every row. Pure, for the tab list.
 */
internal class RowDiff {
    class Result(val changed: List<Int>, val countChanged: Boolean)

    private var last: List<String>? = null

    fun reset() { last = null }

    fun diff(rows: List<String>): Result {
        val before = last
        last = rows
        if (before == null) return Result(rows.indices.toList(), true)
        val changed = rows.indices.filter { it >= before.size || before[it] != rows[it] }
        return Result(changed, before.size != rows.size)
    }
}

/**
 * Collapses identical consecutive values: [offer] returns null for a repeat of the last value (and
 * counts it), or, for a new value, how many repeats the previous one had. Pure, for the action bar.
 */
internal class RepeatCounter<T> {
    private var last: Any? = NONE
    private var repeats = 0

    fun reset() { last = NONE; repeats = 0 }

    fun offer(v: T): Int? {
        if (last != NONE && last == v) { repeats++; return null }
        val prev = repeats
        last = v; repeats = 0
        return prev
    }

    private object NONE
}
