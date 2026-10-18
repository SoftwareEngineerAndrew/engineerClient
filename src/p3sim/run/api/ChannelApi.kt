package com.engineerclient.p3sim.run.api

import com.engineerclient.p3sim.run.Run
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Who hears or sees something. */
sealed interface Audience {
    /** The whole party (every real player in the world). */
    data object Everyone : Audience
    data class Only(val player: UUID) : Audience
    data class AllBut(val player: UUID) : Audience
}

/**
 * The client channels of the run: chat, titles, the action bar. The sidebar, tab list and map are NOT posted to: the HUD
 * module draws them from RunState (or from the contributors below). Implemented by [com.engineerclient.p3sim.clear.hud.HudModule].
 * `text` is Hypixel's legacy `§`-coded form: Odin and Devonian read the plain string, so it must read exactly as Hypixel's
 * line does. Server thread only; the HUD module sends the real packets to each real player.
 */
interface ChannelService {
    /**
     * A chat line as plain system chat. The default sends it straight through `Sim.chat`, so every module can talk to the
     * player from day one, before the HUD module is built; the HUD module overrides it to also record it, etc.
     */
    fun chat(text: String, to: Audience = Audience.Everyone) = ChannelDefaults.chat(text, to)

    /** A title and subtitle (times, title, subtitle, in Hypixel's order). The default goes through `Sim.title`. */
    fun title(title: String, subtitle: String = "", fadeIn: Int = 0, stay: Int = 30, fadeOut: Int = 5, to: Audience = Audience.Everyone) =
        ChannelDefaults.title(title, subtitle, fadeIn, stay, fadeOut, to)

    /**
     * Sets one segment of the action bar. The HUD joins all live segments (health, defense, mana, then `N/M Secrets` when in a
     * room, then these by [priority]) every 10 ticks as Hypixel lays them out. A segment lives [ttlTicks] ticks, or until replaced.
     */
    fun actionBarSegment(key: String, text: String, priority: Int = 0, ttlTicks: Int = 20, to: Audience = Audience.Everyone) {}

    fun clearActionBarSegment(key: String) {}
}

/** What the channels do before the HUD module is filled in: the existing `Sim` helpers. Server thread only. */
internal object ChannelDefaults {
    fun chat(text: String, to: Audience) = forAudience(to) { com.engineerclient.p3sim.Sim.chat(text) }

    fun title(title: String, subtitle: String, fadeIn: Int, stay: Int, fadeOut: Int, to: Audience) =
        forAudience(to) { com.engineerclient.p3sim.Sim.title(title, subtitle, fadeIn, stay, fadeOut) }

    private fun forAudience(to: Audience, block: () -> Unit) {
        val sim = com.engineerclient.p3sim.Sim
        when (to) {
            Audience.Everyone -> sim.everyone(block)
            is Audience.Only -> sim.players().firstOrNull { it.uuid == to.player }?.let { p -> sim.asPlayer(p) { block() } }
            is Audience.AllBut -> sim.players().filter { it.uuid != to.player }.forEach { p -> sim.asPlayer(p) { block() } }
        }
    }
}

/** Extra sidebar lines (below the ones the HUD draws from RunState). Rarely needed. */
fun interface SidebarContributor { fun lines(run: Run): List<String> }

/** Extra tab-list rows (each a `§`-coded line) added to the dungeon-stats column. */
fun interface TabContributor { fun rows(run: Run): List<String> }

/** A 128x128 canvas of the dungeon map item (colour indexes of the map palette). */
interface MapCanvas {
    val size: Int
    fun set(x: Int, y: Int, color: Int)
    fun get(x: Int, y: Int): Int
}

/** Draws extra marks on the map after the HUD has drawn rooms, doors and players (a puzzle's `?`, say). */
fun interface MapContributor { fun paint(run: Run, canvas: MapCanvas) }

/** Where modules register extra HUD content; the HUD module reads these lists. */
object HudRegistry {
    val sidebar = CopyOnWriteArrayList<SidebarContributor>()
    val tab = CopyOnWriteArrayList<TabContributor>()
    val map = CopyOnWriteArrayList<MapContributor>()
}
