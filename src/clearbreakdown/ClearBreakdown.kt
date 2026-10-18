package com.engineerclient.clearbreakdown

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.adr.OnlineLink
import com.engineerclient.p3sim.P3Sim
import com.engineerclient.p3sim.Practice
import com.engineerclient.splits.SplitTracker
import com.odtheking.odin.OdinMod
import com.odtheking.odin.events.BlockInteractEvent
import com.odtheking.odin.events.BlockUpdateEvent
import com.odtheking.odin.events.CheckmarkUpdateEvent
import com.odtheking.odin.events.FloorEnterEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.SecretsUpdateEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.features.impl.dungeon.map.tile.DungeonRoom
import com.odtheking.odin.features.impl.dungeon.map.tile.MapCheckmark
import com.odtheking.odin.features.impl.dungeon.map.tile.RoomType
import com.odtheking.odin.utils.network.hypixelapi.RequestUtils
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.level.block.Blocks
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/**
 * At the end of a finished run, a line for everyone in the party:
 *
 *     Andrew: 3-5 12 secrets
 *
 * rooms cleared alone - rooms helped clear, and secrets found, with the rooms in clear order and the
 * secrets room by room on hover. [ClearCredit] decides the rooms, [SecretCredit] the secrets,
 * [BreakdownText] the words; this feeds them.
 *
 * Where someone is comes from what can be seen first: their player in render distance (this
 * client's, or a party member's relayed through ADR Online), and only then Odin's map icon. Secrets
 * the same way: seen happening ([Sightings]), then Odin's room counts (the action bar, Odin's map
 * sync on its own connection, [OdinSync], and green checkmarks) for what nobody saw, all settled
 * against each player's run total from the Hypixel API.
 *
 * Only a run that is won posts (Hypixel's end line and the "☠ Defeated" line, [RunGate]), the moment
 * it is; one that ends any other way posts nothing, and a world load starts over. The API totals
 * are asked for through the boss, where nobody finds secrets, so the post never waits on them. While this is on, Odin's Secrets
 * Counter stays quiet (SecretsCounterMixin): the totals are here.
 */
object ClearBreakdown : Module(
    name = "Clear Breakdown",
    category = Category.custom("Engineer Client", 860, 10),
    description = "At the end of a finished run, everyone's rooms (cleared alone - cleared) and secrets; hover for the rooms in order and the secrets room by room. Replaces Odin's Secrets Counter lines while on.",
    key = null,
) {
    private val CONTROL_CODES = Regex("§.")
    /** Rooms that don't count as clears: fairy rooms and the green start room. */
    private val NOT_CLEARS = setOf(RoomType.FAIRY, RoomType.ENTRANCE)
    /** Every so often the party hears everything this client sees, not just changes: a lost frame heals. */
    private const val FULL_RESEND_TICKS = 100
    /** The boss lookups: the first this long into the boss, then one every [POLL_MS]. */
    private const val FIRST_POLL_MS = 15_000L
    private const val POLL_MS = 30_000L
    /** The longest a post waits on lookups the boss didn't get to. */
    private const val LAST_ASK_MS = 800L

    // Fresh objects each run (not cleared in place): a breakdown waiting on the API keeps its run's.
    var clears = ClearCredit(); private set
    var secrets = SecretCredit(); private set
    /** A run is being tracked: from Mort until it posts or the world changes. */
    var active = false; private set
    private val gate = RunGate()
    @Volatile private var syncOpen = false
    /** Everyone in the run, in party order: name -> class colour. */
    val roster = LinkedHashMap<String, Char>()
    /** Lifetime secrets at the start, from the API (fresh each run: a late answer lands in its own run's). */
    private var before = ConcurrentHashMap<String, Long>()
    /** Lifetime secrets as last asked in the boss, from the API. */
    private var latest = ConcurrentHashMap<String, Long>()
    /** When the next boss lookup goes out (0: not in the boss yet). */
    private var nextPollMs = 0L
    /** Where party members' clients see players (ADR Online). */
    val relayed = RelayedPresence()
    /** Where this client last told the party it sees each player (room key, "" for no room). */
    private val toldSeen = HashMap<String, String>()
    private var relayTick = 0

    val me: String get() = mc.user.name

    fun roomKey(room: DungeonRoom): String = room.tiles.map { it.data }.sorted().joinToString(",")

    fun roomName(room: DungeonRoom): String = room.name ?: room.type.name

    /** The room at a world position, from Odin's room grid. */
    fun roomAt(x: Double, z: Double): DungeonRoom? {
        val tx = (floor(x).toInt() + 201) shr 5
        val tz = (floor(z).toInt() + 201) shr 5
        if (tx !in 0..5 || tz !in 0..5) return null
        return DungeonScan.tiles[tx + tz * 6].room
    }

    init {
        on<LevelEvent.Load> { EngineerClient.safely("clear breakdown reset") { reset(); closeSync() } }
        // Odin posts this one (and checkmarks) from the network thread: over to the client thread.
        on<FloorEnterEvent> { mc.execute { EngineerClient.safely("clear breakdown sync") { OdinSync.connect(); syncOpen = true } } }

        // Chat straight off the network, as Splits takes it: a chat cleaner can't hide the run's lines.
        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (overlay) return@onReceive
            val text = content.string.replace(CONTROL_CODES, "")
            mc.execute { EngineerClient.safely("clear breakdown chat") { onChat(text) } }
        }

        on<TickEvent.End> { EngineerClient.safely("clear breakdown tick") { tick() } }

        // Secrets seen happening (Sightings), off the same packets ADR records them from.
        onReceive<ClientboundTakeItemEntityPacket> {
            val item = itemId; val by = playerId
            mc.execute { EngineerClient.safely("clear breakdown pickup") { Sightings.onPickup(item, by) } }
        }
        onReceive<ClientboundBlockEventPacket> {
            if (b0 != 1 || b1 <= 0 || (block != Blocks.CHEST && block != Blocks.TRAPPED_CHEST)) return@onReceive
            val at = pos.immutable()
            mc.execute { EngineerClient.safely("clear breakdown chest") { Sightings.onChestOpen(at) } }
        }
        onReceive<ClientboundSoundPacket> {
            val sound = sound.value()
            if ((sound != SoundEvents.BAT_HURT && sound != SoundEvents.BAT_DEATH) || volume != 0.1f) return@onReceive
            val x = x; val y = y; val z = z
            mc.execute { EngineerClient.safely("clear breakdown bat") { Sightings.onBat(x, y, z) } }
        }
        on<BlockInteractEvent> { EngineerClient.safely("clear breakdown click") { Sightings.onClick(pos) } }
        on<BlockUpdateEvent> { EngineerClient.safely("clear breakdown block") { Sightings.onBlockChange(pos, updated) } }

        on<CheckmarkUpdateEvent> {
            val key = roomKey(room); val name = roomName(room); val counts = room.type !in NOT_CLEARS
            val mark = when (checkmark) {
                MapCheckmark.WHITE -> Mark.WHITE
                MapCheckmark.GREEN -> Mark.GREEN
                MapCheckmark.RED -> Mark.RED
                else -> Mark.OTHER
            }
            // Green: every secret in the room is found.
            val max = if (mark == Mark.GREEN) room.data?.maxSecrets else null
            mc.execute {
                EngineerClient.safely("clear breakdown checkmark") {
                    if (!active) return@safely
                    if (counts) clears.onCheckmark(key, name, mark)
                    max?.let { secrets.observe(key, name, it) }
                }
            }
        }

        on<SecretsUpdateEvent> {
            EngineerClient.safely("clear breakdown secrets") { if (active) secrets.observe(roomKey(room), roomName(room), foundSecrets) }
        }

        // Party members' sightings, through ADR Online.
        OnlineLink.onRelay = { text ->
            RelayCodec.decode(text)?.let { (from, items) -> mc.execute { EngineerClient.safely("clear breakdown relay") { fromParty(from, items) } } }
        }

        OdinSync.onPayload = { player, tiles, found ->
            val key = tiles.sorted().joinToString(",")
            mc.execute {
                EngineerClient.safely("clear breakdown odin sync") {
                    if (!active || player == me || player !in roster) return@safely
                    DungeonScan.rooms.firstOrNull { roomKey(it) == key }?.let { secrets.observe(key, roomName(it), found) }
                }
            }
        }
    }

    override fun onDisable() {
        super.onDisable()
        reset()
        closeSync()
    }

    private fun reset() {
        clears = ClearCredit(); secrets = SecretCredit()
        active = false; gate.reset()
        roster.clear(); before = ConcurrentHashMap(); latest = ConcurrentHashMap(); nextPollMs = 0L; relayed.clear(); toldSeen.clear()
        Sightings.reset()
    }

    private fun closeSync() {
        if (syncOpen) { OdinSync.shutdown(); syncOpen = false }
    }

    private fun onChat(text: String) {
        if (!DungeonUtils.inDungeons) return
        // A P3 Sim practice is no run.
        if (P3Sim.inSim && Practice.active) return
        if (text == SplitTracker.MORT) start()
        else if (active && gate.line(text)) finish()
    }

    private fun start() {
        reset()
        active = true
        for (p in DungeonUtils.dungeonTeammates) join(p)
    }

    /** Someone in the run: a line at the end, and their lifetime secrets now. */
    private fun join(p: DungeonPlayer) {
        if (p.name in roster) return
        roster[p.name] = p.clazz.colorCode
        val name = p.name
        val into = before
        OdinMod.scope.launch { RequestUtils.pullSecrets(name).onSuccess { into[name] = it } }
    }

    private fun tick() {
        if (!active || !DungeonUtils.inDungeons) return
        // Teammates the tab list hadn't listed yet at Mort.
        for (p in DungeonUtils.dungeonTeammates) join(p)
        if (DungeonUtils.inBoss) { pollTotals(); return closeSync() }
        Sightings.tick()
        val where = presence()
        clears.tick(where)
        secrets.tick(where)
        if (++relayTick % 20 == 0) toParty(full = relayTick % FULL_RESEND_TICKS == 0)
    }

    /**
     * Once a second: what this client saw for the party - its secret sightings, and where it sees
     * players by their entity (room changes, and when it stops seeing someone; everything it sees
     * when [full]). Map icons and Odin's counts aren't sent; every client has those. Nothing counts
     * as told without a socket to tell it on.
     */
    private fun toParty(full: Boolean) {
        val items = ArrayList<Relayed>(Sightings.outbox)
        val team = DungeonUtils.dungeonTeammates.associateBy { it.name }
        val seen = HashMap<String, String>()
        for (name in roster.keys) {
            val p = team[name] ?: continue
            if (p.isDead) continue
            val e = (if (name == me) mc.player else p.entity) ?: continue
            seen[name] = roomAt(e.x, e.z)?.let(::roomKey) ?: ""
        }
        for ((name, room) in seen) if (full || toldSeen[name] != room) items += Relayed.Presence(name, room)
        for (name in toldSeen.keys) if (name !in seen) items += Relayed.Presence(name, null)
        if (items.isEmpty() || !OnlineLink.sendRelay(RelayCodec.frames(items))) return
        Sightings.outbox.clear()
        toldSeen.clear(); toldSeen.putAll(seen)
    }

    /** A party member's sightings: only about people in this run, and only from one of them. */
    private fun fromParty(from: String, items: List<Relayed>) {
        if (!active || from == me || from !in roster) return
        val now = System.currentTimeMillis()
        for (item in items) when (item) {
            is Relayed.Secret -> {
                val candidates = item.candidates.filter { it in roster }.toSet()
                if (candidates.isNotEmpty()) secrets.sighting(item.key, item.roomKey, item.roomName, candidates, item.exact, now)
            }
            is Relayed.Presence -> if (item.player in roster) relayed.update(from, item.player, item.roomKey, now)
        }
    }

    /** Which room each player is in this tick (null: none, dead or gone). */
    private fun presence(): Map<String, String?> {
        val team = DungeonUtils.dungeonTeammates.associateBy { it.name }
        val now = System.currentTimeMillis()
        val out = HashMap<String, String?>()
        for (name in roster.keys) {
            val p = team[name]
            out[name] = if (p == null || p.isDead) null else roomOf(p, now)
        }
        return out
    }

    private fun roomOf(p: DungeonPlayer, now: Long): String? {
        val entity = if (p.name == me) mc.player else p.entity
        if (entity != null) return roomAt(entity.x, entity.z)?.let(::roomKey)
        relayed.roomOf(p.name, now)?.let { return it.ifEmpty { null } }
        // Odin's map icon, the way its map draws it (RunRecorder's formula): not placed yet is 0.
        if (p.mapPos.data == 0L) return null
        val x = ((p.mapPos.x + 128) / 2.0 - DungeonScan.startX) * 32.0 / DungeonScan.roomGap - 200
        val z = ((p.mapPos.z + 128) / 2.0 - DungeonScan.startY) * 32.0 / DungeonScan.roomGap - 200
        return roomAt(x, z)?.let(::roomKey)
    }

    /**
     * In the boss: everyone's lifetime secrets, all at once, [FIRST_POLL_MS] in (the API has caught
     * up on the clear by then) and every [POLL_MS] after, so the totals are in hand when the run ends.
     */
    private fun pollTotals() {
        val now = System.currentTimeMillis()
        if (nextPollMs == 0L) nextPollMs = now + FIRST_POLL_MS
        if (now < nextPollMs) return
        nextPollMs = now + POLL_MS
        val into = latest
        for (name in roster.keys) OdinMod.scope.launch { RequestUtils.pullSecrets(name).onSuccess { into[name] = it } }
    }

    /**
     * The run is won: posted at once with the totals the boss lookups got. Anyone they missed (a
     * boss shorter than [FIRST_POLL_MS]) is asked for now, all together, for at most [LAST_ASK_MS]
     * before it posts anyway ("?" for whoever still has no total).
     */
    private fun finish() {
        active = false
        val names = roster.toMap()
        val start = before; val now = latest
        val runClears = clears; val runSecrets = secrets
        fun postNow() = post(names, runClears.clears(), runSecrets.resolve(names.keys.associateWith { n ->
            val after = now[n]; val was = start[n]
            if (after != null && was != null) (after - was).coerceAtLeast(0L).toInt() else null
        }))
        val missing = names.keys.filter { !now.containsKey(it) && start.containsKey(it) }
        if (missing.isEmpty()) return postNow()
        OdinMod.scope.launch {
            withTimeoutOrNull(LAST_ASK_MS) {
                missing.map { n -> async { RequestUtils.pullSecrets(n).onSuccess { now[n] = it } } }.awaitAll()
            }
            mc.execute { EngineerClient.safely("clear breakdown post") { postNow() } }
        }
    }

    private fun post(names: Map<String, Char>, rooms: Map<String, List<Clear>>, found: Map<String, PlayerSecrets>) {
        EngineerClient.msg("§6Clear Breakdown")
        for ((name, colour) in names) {
            val p = BreakdownText.line(name, colour, rooms[name].orEmpty(), found[name])
            EngineerClient.msg(Component.literal(p.name)
                .append(hover(p.rooms, p.roomsHover))
                .append(Component.literal(" "))
                .append(hover(p.secrets, p.secretsHover)))
        }
    }

    private fun hover(text: String, hover: String) =
        Component.literal(text).withStyle { it.withHoverEvent(HoverEvent.ShowText(Component.literal(hover))) }
}
