package com.engineerclient.clearbreakdown

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
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
import com.odtheking.odin.utils.handlers.schedule
import com.odtheking.odin.utils.network.hypixelapi.RequestUtils
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import kotlinx.coroutines.launch
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
 * Only a run that is won posts (its "☠ Defeated" line, then Hypixel's end line); one that ends any
 * other way posts nothing, and a world load starts over. While this is on, Odin's Secrets Counter
 * stays quiet (SecretsCounterMixin): the totals are here.
 */
object ClearBreakdown : Module(
    name = "Clear Breakdown",
    category = Category.custom("Engineer Client", 860, 10),
    description = "At the end of a finished run, everyone's rooms (cleared alone - cleared) and secrets; hover for the rooms in order and the secrets room by room. Replaces Odin's Secrets Counter lines while on.",
    key = null,
) {
    private val CONTROL_CODES = Regex("§.")
    private val DEFEATED = Regex("^\\s*☠ Defeated ")
    private val END = Regex("^\\s*(?:Master Mode )?(?:The )?Catacombs - (?:Entrance|Floor .{1,3})$")
    /** Rooms that don't count as clears: fairy rooms and the green start room. */
    private val NOT_CLEARS = setOf(RoomType.FAIRY, RoomType.ENTRANCE)

    // Fresh objects each run (not cleared in place): a breakdown waiting on the API keeps its run's.
    var clears = ClearCredit(); private set
    var secrets = SecretCredit(); private set
    /** A run is being tracked: from Mort until it posts or the world changes. */
    var active = false; private set
    private var won = false
    private var syncOpen = false
    /** Everyone in the run, in party order: name -> class colour. */
    val roster = LinkedHashMap<String, Char>()
    /** Lifetime secrets at the start, from the API. */
    private val before = ConcurrentHashMap<String, Long>()
    /** Party members' sightings of where players are: (sender, player) -> room key, "" for no room. */
    val relayed = HashMap<Pair<String, String>, String>()

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
        on<FloorEnterEvent> { EngineerClient.safely("clear breakdown sync") { OdinSync.connect(); syncOpen = true } }

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
            EngineerClient.safely("clear breakdown checkmark") {
                if (!active) return@safely
                val key = roomKey(room)
                if (room.type !in NOT_CLEARS) clears.onCheckmark(key, roomName(room), when (checkmark) {
                    MapCheckmark.WHITE -> Mark.WHITE
                    MapCheckmark.GREEN -> Mark.GREEN
                    MapCheckmark.RED -> Mark.RED
                    else -> Mark.OTHER
                })
                // Green: every secret in the room is found.
                if (checkmark == MapCheckmark.GREEN) room.data?.maxSecrets?.let { secrets.observe(key, roomName(room), it) }
            }
        }

        on<SecretsUpdateEvent> {
            EngineerClient.safely("clear breakdown secrets") { if (active) secrets.observe(roomKey(room), roomName(room), foundSecrets) }
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
        active = false; won = false
        roster.clear(); before.clear(); relayed.clear()
        Sightings.reset()
    }

    private fun closeSync() {
        if (syncOpen) { OdinSync.shutdown(); syncOpen = false }
    }

    private fun onChat(text: String) {
        if (!DungeonUtils.inDungeons) return
        // A P3 Sim practice is no run.
        if (P3Sim.inSim && Practice.active) return
        when {
            text == SplitTracker.MORT -> start()
            DEFEATED.containsMatchIn(text) -> if (active) won = true
            END.containsMatchIn(text) -> if (active && won) finish()
        }
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
        OdinMod.scope.launch { RequestUtils.pullSecrets(name).onSuccess { before[name] = it } }
    }

    private fun tick() {
        if (!active || !DungeonUtils.inDungeons) return
        // Teammates the tab list hadn't listed yet at Mort.
        for (p in DungeonUtils.dungeonTeammates) join(p)
        if (DungeonUtils.inBoss) return closeSync()
        Sightings.tick()
        val where = presence()
        clears.tick(where)
        secrets.tick(where)
    }

    /** Which room each player is in this tick (null: none, dead or gone). */
    private fun presence(): Map<String, String?> {
        val team = DungeonUtils.dungeonTeammates.associateBy { it.name }
        val out = HashMap<String, String?>()
        for (name in roster.keys) {
            val p = team[name]
            out[name] = if (p == null || p.isDead) null else roomOf(p)
        }
        return out
    }

    private fun roomOf(p: DungeonPlayer): String? {
        val entity = if (p.name == me) mc.player else p.entity
        if (entity != null) return roomAt(entity.x, entity.z)?.let(::roomKey)
        relayed.entries.firstOrNull { it.key.second == p.name }?.let { return it.value.ifEmpty { null } }
        // Odin's map icon, the way its map draws it (RunRecorder's formula): not placed yet is 0.
        if (p.mapPos.data == 0L) return null
        val x = ((p.mapPos.x + 128) / 2.0 - DungeonScan.startX) * 32.0 / DungeonScan.roomGap - 200
        val z = ((p.mapPos.z + 128) / 2.0 - DungeonScan.startY) * 32.0 / DungeonScan.roomGap - 200
        return roomAt(x, z)?.let(::roomKey)
    }

    /** The run is won: wait for the API to count the run (as Odin's Secrets Counter does), then post. */
    private fun finish() {
        active = false
        val names = roster.toMap()
        val start = before.toMap()
        val runClears = clears
        val runSecrets = secrets
        schedule(30) {
            OdinMod.scope.launch {
                val totals = names.keys.associateWith { n ->
                    val after = RequestUtils.pullSecrets(n).getOrNull()
                    val was = start[n]
                    if (after != null && was != null) (after - was).coerceAtLeast(0L).toInt() else null
                }
                mc.execute { EngineerClient.safely("clear breakdown post") { post(names, runClears.clears(), runSecrets.resolve(totals)) } }
            }
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
