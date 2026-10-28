package com.engineerclient.adr

import com.engineerclient.EngineerClient
import net.minecraft.client.multiplayer.PlayerInfo
import net.minecraft.network.chat.Component
import net.minecraft.world.level.GameType
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerScoreEntry
import net.minecraft.world.scores.PlayerTeam

/**
 * What Hypixel shows on the HUD: the tab list, the sidebar, boss bars, titles and the action bar. Text is
 * kept with its § colour codes, as the game draws it. Only changes are written:
 *
 *  - {"k":"tab","t":t,"n":rows?,"d":[[row, text], ...]}: the tab list's rows (top to bottom, then the
 *    next column, as the game orders them), only the rows that changed that tick; "n" when the row
 *    count changes. Hypixel rewrites it about once a second.
 *  - {"k":"sb","t":t,"title":text?,"n":lines?,"d":[[line, text], ...]}: the sidebar, line 0 at the top,
 *    the same way; "title" and "n" only when they change.
 *  - {"k":"bar","t":t,"i":bar,"m":name?,"p":progress?} a boss bar (numbered in the order they came)
 *    added or changed, progress to 3 decimals; {"k":"bar","t":t,"i":bar,"x":1} gone.
 *  - {"k":"title","t":t,"m":text} / {"k":"sub","t":t,"m":text}: a title or subtitle sent; an empty
 *    title when they're cleared.
 *  - {"k":"ab","t":t,"m":text}: the action bar, when its text changes (Hypixel resends it every few
 *    ticks).
 */
class HudCapture(private val emit: (String) -> Unit) {

    private val tabRows = ArrayList<String>()
    private val tabComps = ArrayList<Component?>()
    private val sideRows = ArrayList<String>()
    private var sideTitle = ""
    private val bars = HashMap<java.util.UUID, Int>()
    private val barProgress = HashMap<Int, Int>()
    private var actionBar = ""

    fun tick(t: Int) {
        tab(t)
        sidebar(t)
    }

    private fun tab(t: Int) {
        val infos = EngineerClient.mc.connection?.listedOnlinePlayers ?: return
        val rows = infos.sortedWith(ORDER).take(80)
        val sb = StringBuilder()
        for ((i, info) in rows.withIndex()) {
            val comp = info.tabListDisplayName
            // The same Component as last tick (the game keeps it until the server sends a new one): nothing to read.
            if (i < tabComps.size && comp != null && comp === tabComps[i]) continue
            val text = if (comp != null) ADR.legacyText(comp) else ADR.legacyText(PlayerTeam.formatNameForTeam(info.team, Component.literal(info.profile.name())))
            if (i < tabComps.size) tabComps[i] = comp else tabComps.add(comp)
            if (i < tabRows.size && tabRows[i] == text) continue
            if (i < tabRows.size) tabRows[i] = text else tabRows.add(text)
            if (sb.isNotEmpty()) sb.append(',')
            sb.append('[').append(i).append(',').append(GeometryCapture.jsonString(text)).append(']')
        }
        val resized = rows.size != tabRows.size
        while (tabRows.size > rows.size) { tabRows.removeAt(tabRows.size - 1); tabComps.removeAt(tabComps.size - 1) }
        if (sb.isEmpty() && !resized) return
        emit("""{"k":"tab","t":$t${if (resized) ",\"n\":${rows.size}" else ""},"d":[$sb]}""")
    }

    private fun sidebar(t: Int) {
        val board = EngineerClient.mc.level?.scoreboard ?: return
        val obj = board.getDisplayObjective(DisplaySlot.SIDEBAR)
        val title = obj?.displayName?.let { ADR.legacyText(it) } ?: ""
        val lines = if (obj == null) emptyList() else board.listPlayerScores(obj).filter { !it.isHidden }
            .sortedWith(SIDEBAR_ORDER).take(15)
            .map { ADR.legacyText(PlayerTeam.formatNameForTeam(board.getPlayersTeam(it.owner()), it.ownerName())) }
        val sb = StringBuilder()
        for ((i, text) in lines.withIndex()) {
            if (i < sideRows.size && sideRows[i] == text) continue
            if (i < sideRows.size) sideRows[i] = text else sideRows.add(text)
            if (sb.isNotEmpty()) sb.append(',')
            sb.append('[').append(i).append(',').append(GeometryCapture.jsonString(text)).append(']')
        }
        val resized = lines.size != sideRows.size
        while (sideRows.size > lines.size) sideRows.removeAt(sideRows.size - 1)
        val retitled = title != sideTitle
        sideTitle = title
        if (sb.isEmpty() && !resized && !retitled) return
        emit("""{"k":"sb","t":$t${if (retitled) ",\"title\":${GeometryCapture.jsonString(title)}" else ""}${if (resized) ",\"n\":${lines.size}" else ""},"d":[$sb]}""")
    }

    fun bossBar(t: Int, id: java.util.UUID, name: Component?, progress: Float?, removed: Boolean) {
        val i = bars.getOrPut(id) { bars.size }
        if (removed) { barProgress.remove(i); emit("""{"k":"bar","t":$t,"i":$i,"x":1}"""); return }
        val p = progress?.let { Math.round(it * 1000) }
        val pChanged = p != null && barProgress.put(i, p) != p
        if (name == null && !pChanged) return
        val sb = StringBuilder("""{"k":"bar","t":$t,"i":$i""")
        if (name != null) sb.append(",\"m\":").append(GeometryCapture.jsonString(ADR.legacyText(name)))
        if (pChanged) sb.append(",\"p\":").append(p!! / 1000.0)
        emit(sb.append('}').toString())
    }

    fun title(t: Int, sub: Boolean, text: Component?) =
        emit("""{"k":"${if (sub) "sub" else "title"}","t":$t,"m":${GeometryCapture.jsonString(text?.let { ADR.legacyText(it) } ?: "")}}""")

    fun actionBar(t: Int, text: Component) {
        val s = ADR.legacyText(text)
        if (s == actionBar) return
        actionBar = s
        emit("""{"k":"ab","t":$t,"m":${GeometryCapture.jsonString(s)}}""")
    }

    private companion object {
        /** The tab list's own order (PlayerTabOverlay's comparator). */
        val ORDER: Comparator<PlayerInfo> = compareBy<PlayerInfo> { -it.tabListOrder }
            .thenBy { if (it.gameMode == GameType.SPECTATOR) 1 else 0 }
            .thenBy { it.team?.name ?: "" }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.profile.name() }
        /** The sidebar's own order (Gui's): highest score first, then by name. */
        val SIDEBAR_ORDER: Comparator<PlayerScoreEntry> = compareByDescending<PlayerScoreEntry> { it.value() }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.owner() }
    }
}
