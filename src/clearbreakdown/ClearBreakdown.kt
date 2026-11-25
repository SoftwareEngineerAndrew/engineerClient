package com.engineerclient.clearbreakdown

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.dungeon.ClearInfo
import com.engineerclient.p3sim.P3Sim
import com.engineerclient.p3sim.Practice
import com.adm.AdmMod
import com.adm.events.LevelEvent
import com.adm.events.TickEvent
import com.adm.events.core.SubscribeWhile
import com.adm.events.core.on
import com.adm.events.core.onReceive
import com.adm.features.impl.boss.ExtraStats
import com.adm.utils.hexText
import com.adm.utils.network.hypixelapi.RequestUtils
import com.adm.utils.skyblock.dungeon.DungeonClass
import com.adm.utils.skyblock.dungeon.DungeonUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import java.util.concurrent.ConcurrentHashMap

/**
 * Clear Info's Clear Breakdown, at the end of every run and below Extra Stats: each player's secrets this run from the Hypixel
 * API ([Mode.TOTALS]), or with the rooms they cleared and where their secrets were ([Mode.FULL], from [RoomTracker]).
 * Your own count is the stats box's when Hypixel shows it.
 */
object ClearBreakdown {
    enum class Mode(private val label: String) { TOTALS("Secret Totals"), FULL("Full Breakdown"); override fun toString() = label }

    class Member(val name: String, val clazz: DungeonClass)

    /** Boss lookups, so the post never waits on the API: the first this long into the boss, then one every [POLL_MS]. */
    private const val FIRST_POLL_MS = 15_000L
    private const val POLL_MS = 30_000L
    /** The longest a post waits on Extra Stats and on lookups the boss didn't get to. */
    private const val END_WAIT_MS = 5_000L
    /** A run with no boss ends with the API behind: a total that hasn't moved is asked again after this. */
    private const val LAGGED_RETRY_MS = 3_000L

    private const val MORT = "[NPC] Mort: Here, I found this map when I first entered the dungeon."
    private val endLine = Regex("^\\s*(?:Master Mode )?(?:The )?Catacombs - (?:Entrance|Floor .{1,3})$")
    private val ownLine = Regex("^\\s*Secrets Found: (\\d+)$")
    private val deathLine = Regex("☠ (\\w{1,16}) (.+) and became a ghost\\.$")

    /** One run's state, fresh each run: a late API answer lands in its own run's. */
    private class Run {
        val members = LinkedHashMap<String, Member>()
        val before = ConcurrentHashMap<String, Long>()
        val latest = ConcurrentHashMap<String, Long>()
        val deaths = HashMap<String, ArrayList<String>>()
        @Volatile var own: Int? = null
        var nextPollMs = 0L
        var inBoss = false
        // Set when the run ends.
        var endMs = 0L
        var summaries = 0
        @Volatile var totalsIn = false
        var clears: ClearCredit? = null
        var secrets: SecretCredit? = null
    }

    private var run: Run? = null
    private var ending: Run? = null
    private var tickCount = 0

    /** A run is being tracked: from Mort until its end line or a world change. */
    @Volatile var active = false
        private set(value) {
            if (field == value) return
            field = value
            SubscribeWhile.changed()
        }

    val me: String get() = mc.user.name
    val mode: Mode get() = ClearInfo.breakdownMode
    val roster: Map<String, Member> get() = run?.members ?: emptyMap()

    init {
        // A run that just ended still posts after its world goes.
        on<LevelEvent.Load> { EngineerClient.safely("clear breakdown reset") { val e = ending; reset(); ending = e } }

        // Off the network, as Splits takes it: a chat cleaner can't hide the run's lines.
        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (overlay || !DungeonUtils.inDungeons) return@onReceive
            val text = it.chatText
            if (text != MORT && !text.endsWith(" and became a ghost.") && !text.contains("Catacombs - ") && !text.contains("Secrets Found: ")) return@onReceive
            mc.execute { EngineerClient.safely("clear breakdown chat") { onChat(text) } }
        }

        on<TickEvent.End> { EngineerClient.safely("clear breakdown tick") { tick() } }
    }

    fun install() {
        SubscribeWhile.add(this, ::reset) { ClearInfo.clearBreakdownOn }
        RoomTracker.install()
    }

    private fun reset() {
        run = null; ending = null
        active = false
        RoomTracker.reset()
    }

    private fun onChat(text: String) {
        if (text == MORT) {
            if (P3Sim.inSim && Practice.active) return
            reset()
            run = Run().apply { for (p in DungeonUtils.dungeonTeammates) join(this, p.name, p.clazz) }
            active = true
            return
        }
        val r = run ?: return
        if (text.contains("Secrets Found: ")) ownLine.find(text)?.let { r.own = it.groupValues[1].toIntOrNull() }
        else if (active && text.endsWith(" and became a ghost.")) deathLine.find(text)?.let { m ->
            val name = if (m.groupValues[1] == "You") me else m.groupValues[1]
            val cause = m.groupValues[2]
            r.deaths.getOrPut(name) { ArrayList() } += if (cause.startsWith("were ")) "was " + cause.substring(5) else cause
        }
        else if (active && endLine.containsMatchIn(text)) end(r)
    }

    /** Someone in the run: a line at the end, and their lifetime secrets now. */
    private fun join(r: Run, name: String, clazz: DungeonClass) {
        if (name in r.members) return
        r.members[name] = Member(name, clazz)
        AdmMod.scope.launch { RequestUtils.pullSecrets(name).onSuccess { r.before[name] = it } }
    }

    private fun tick() {
        val r = run
        if (active && r != null && DungeonUtils.inDungeons) {
            // Teammates the tab list hadn't listed yet at Mort.
            if (++tickCount % 20 == 0) for (p in DungeonUtils.dungeonTeammates) join(r, p.name, p.clazz)
            if (DungeonUtils.inBoss) pollTotals(r)
        }
        val e = ending ?: return
        val now = System.currentTimeMillis()
        val statsDone = !ClearInfo.extraStatsOn || ExtraStats.summaries != e.summaries
        if ((statsDone && e.totalsIn) || now - e.endMs > END_WAIT_MS) {
            ending = null
            post(e)
        }
    }

    private fun pollTotals(r: Run) {
        r.inBoss = true
        val now = System.currentTimeMillis()
        if (r.nextPollMs == 0L) r.nextPollMs = now + FIRST_POLL_MS
        if (now < r.nextPollMs) return
        r.nextPollMs = now + POLL_MS
        for (name in r.members.keys) AdmMod.scope.launch { RequestUtils.pullSecrets(name).onSuccess { r.latest[name] = it } }
    }

    /** The run is over, won or not: the rest of the totals are asked for while Extra Stats comes in. */
    private fun end(r: Run) {
        active = false
        r.clears = RoomTracker.clears; r.secrets = RoomTracker.secrets
        r.endMs = System.currentTimeMillis()
        r.summaries = ExtraStats.summaries
        ending = r
        val missing = r.members.keys.filter { !r.latest.containsKey(it) }
        if (missing.isEmpty()) { r.totalsIn = true; return }
        AdmMod.scope.launch {
            withTimeoutOrNull(END_WAIT_MS - 500) {
                missing.map { n -> async { RequestUtils.pullSecrets(n).onSuccess { r.latest[n] = it } } }.awaitAll()
                if (!r.inBoss) {
                    val stuck = missing.filter { r.latest[it] != null && r.latest[it] == r.before[it] }
                    if (stuck.isNotEmpty()) {
                        delay(LAGGED_RETRY_MS)
                        stuck.map { n -> async { RequestUtils.pullSecrets(n).onSuccess { r.latest[n] = it } } }.awaitAll()
                    }
                }
            }
            r.totalsIn = true
        }
    }

    private fun gained(r: Run, name: String): Int? {
        if (name == me) r.own?.let { return it }
        val after = r.latest[name] ?: return null
        val was = r.before[name] ?: return null
        return (after - was).coerceAtLeast(0L).toInt()
    }

    private fun post(r: Run) {
        val totals = r.members.keys.associateWith { gained(r, it) }
        if (mode == Mode.TOTALS) {
            EngineerClient.msg("§6Secrets")
            for (m in r.members.values.sortedWith(compareBy<Member> { it.clazz.ordinal }.thenByDescending { totals[it.name] ?: -1 })) {
                val n = totals[m.name]
                EngineerClient.msg(hexText("${m.clazz.hexCode}${m.name}§7: ").append(Component.literal(if (n == null) "§c?" else "§f$n §7secrets")))
            }
            return
        }
        val rooms = r.clears?.clears().orEmpty()
        val found = r.secrets?.resolve(totals).orEmpty()
        EngineerClient.msg("§6Clear Breakdown")
        for (m in r.members.values) {
            val p = BreakdownText.line(m.name, m.clazz.hexCode, rooms[m.name].orEmpty(), found[m.name])
            val line = hexText(p.name).append(hover(p.rooms, p.roomsHover)).append(Component.literal(" ")).append(hover(p.secrets, p.secretsHover))
            BreakdownText.deaths(r.deaths[m.name].orEmpty())?.let { (text, causes) -> line.append(Component.literal(" ")).append(hover(text, causes)) }
            EngineerClient.msg(line)
        }
    }

    private fun hover(text: String, hover: String) =
        Component.literal(text).withStyle { it.withHoverEvent(HoverEvent.ShowText(Component.literal(hover))) }
}
