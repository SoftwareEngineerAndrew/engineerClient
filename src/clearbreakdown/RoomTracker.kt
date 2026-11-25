package com.engineerclient.clearbreakdown

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.adr.OnlineLink
import com.engineerclient.dungeon.ClearInfo
import com.adm.events.BlockInteractEvent
import com.adm.events.BlockUpdateEvent
import com.adm.events.TickEvent
import com.adm.events.core.SubscribeWhile
import com.adm.events.core.on
import com.adm.events.core.onReceive
import com.adm.features.impl.dungeon.map.DungeonScan
import com.adm.features.impl.dungeon.map.tile.DungeonRoom
import com.adm.features.impl.dungeon.map.tile.MapCheckmark
import com.adm.features.impl.dungeon.map.tile.RoomType
import com.adm.utils.skyblock.dungeon.DungeonPlayer
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.AbstractSkullBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.SkullBlockEntity
import net.minecraft.world.phys.AABB
import java.util.IdentityHashMap
import java.util.UUID
import kotlin.math.floor

/**
 * Full Breakdown's view of a clear, on only while one is tracked in that mode. Where each player is: their player in render
 * distance (this client's, or a party member's through ADR Online), else ADM's map icon. Secrets seen happening (an item picked
 * up, a chest lid, an essence skull going, a secret bat) go to [secrets] and to the party; ADM's room counts and checkmarks,
 * which carry its map sync and Odin's, are read off the map each tick for what nobody saw.
 */
object RoomTracker {
    var clears = ClearCredit(); private set
    var secrets = SecretCredit(); private set

    /** Every so often the party hears everything this client sees, not just changes: a lost frame heals. */
    private const val FULL_RESEND_TICKS = 100
    private const val REACH_SQ = 6.0 * 6.0
    private const val CLICK_MS = 1000L
    private val NOT_CLEARS = setOf(RoomType.FAIRY, RoomType.ENTRANCE)
    private val WITHER_ESSENCE_ID = UUID.fromString("2865274b-3097-394e-8149-ec629c72d850")
    private val SECRET_ITEMS = listOf(
        "Health Potion VIII Splash Potion", "Healing Potion 8 Splash Potion", "Healing Potion VIII Splash Potion", "Healing VIII Splash Potion", "Healing 8 Splash Potion",
        "Decoy", "Inflatable Jerry", "Spirit Leap", "Trap", "Training Weights", "Defuse Kit", "Dungeon Chest Key", "Treasure Talisman", "Revive Stone", "Architect's First Draft",
        "Secret Dye", "Candycomb",
    )

    private class Key(val tiles: Int, val text: String)
    private val keys = IdentityHashMap<DungeonRoom, Key>()
    private val byKey = HashMap<String, DungeonRoom>()
    private val marks = IdentityHashMap<DungeonRoom, MapCheckmark>()
    private val counts = IdentityHashMap<DungeonRoom, Int>()
    private val clicks = HashMap<BlockPos, Long>()
    private val outbox = ArrayList<Relayed>()
    /** Where this client last told the party it sees each player (room key, "" for no room). */
    private val toldSeen = HashMap<String, String>()
    // Reused each tick: the credits keep only the latest presence.
    private val where = HashMap<String, String?>()
    private val relayed = RelayedPresence()
    private var ticks = 0

    private val tracking get() = ClearBreakdown.active && DungeonUtils.inClear

    init {
        on<TickEvent.End> { EngineerClient.safely("clear breakdown tick") { tick() } }

        onReceive<ClientboundTakeItemEntityPacket> {
            val item = itemId; val by = playerId
            mc.execute { EngineerClient.safely("clear breakdown pickup") { onPickup(item, by) } }
        }
        onReceive<ClientboundBlockEventPacket> {
            if (b0 != 1 || b1 <= 0 || (block != Blocks.CHEST && block != Blocks.TRAPPED_CHEST)) return@onReceive
            val at = pos.immutable()
            mc.execute { EngineerClient.safely("clear breakdown chest") { if (tracking) seenBlock("chest", at) } }
        }
        onReceive<ClientboundSoundPacket> {
            if (volume != 0.1f) return@onReceive
            val sound = sound.value()
            if (sound != SoundEvents.BAT_HURT && sound != SoundEvents.BAT_DEATH) return@onReceive
            val x = x; val y = y; val z = z
            mc.execute { EngineerClient.safely("clear breakdown bat") { onBat(x, y, z) } }
        }
        on<BlockInteractEvent> { if (tracking) clicks[pos.immutable()] = System.currentTimeMillis() }
        // The skull's block entity is still in the chunk when its block changes.
        on<BlockUpdateEvent> {
            if (old.block !is AbstractSkullBlock || updated.block is AbstractSkullBlock || !tracking) return@on
            val skull = mc.level?.getChunkAt(pos)?.blockEntities?.get(pos) as? SkullBlockEntity ?: return@on
            if (skull.ownerProfile?.partialProfile()?.id == WITHER_ESSENCE_ID) seenBlock("essence", pos.immutable())
        }
    }

    fun install() {
        SubscribeWhile.add(this) { ClearInfo.clearBreakdownOn && ClearBreakdown.active && ClearBreakdown.mode == ClearBreakdown.Mode.FULL }
        OnlineLink.onRelay = { text ->
            RelayCodec.decode(text)?.let { (from, items) -> mc.execute { EngineerClient.safely("clear breakdown relay") { fromParty(from, items) } } }
        }
    }

    fun reset() {
        clears = ClearCredit(); secrets = SecretCredit()
        keys.clear(); byKey.clear(); marks.clear(); counts.clear(); clicks.clear()
        outbox.clear(); toldSeen.clear(); where.clear(); relayed.clear()
    }

    /** The room at a world position, from ADM's room grid. */
    fun roomAt(x: Double, z: Double): DungeonRoom? {
        val tx = (floor(x).toInt() + 201) shr 5
        val tz = (floor(z).toInt() + 201) shr 5
        if (tx !in 0..5 || tz !in 0..5) return null
        return DungeonScan.tiles[tx + tz * 6].room
    }

    // The same on every client; a room only changes key when the map adds a tile to it.
    private fun roomKey(room: DungeonRoom): String {
        val size = room.tiles.size
        keys[room]?.let { if (it.tiles == size) return it.text }
        val text = room.tiles.map { it.data }.sorted().joinToString(",")
        keys[room] = Key(size, text)
        byKey[text] = room
        return text
    }

    private fun roomName(room: DungeonRoom): String = room.name ?: room.type.name

    private fun roomByKey(key: String): DungeonRoom? =
        byKey[key] ?: run { for (room in DungeonScan.rooms) roomKey(room); byKey[key] }

    private fun tick() {
        if (!tracking) return
        val team = DungeonUtils.dungeonTeammates
        val now = System.currentTimeMillis()
        where.clear()
        for (name in ClearBreakdown.roster.keys) {
            var p: DungeonPlayer? = null
            for (i in team.indices) if (team[i].name == name) { p = team[i]; break }
            where[name] = if (p == null || p.isDead) null else roomOf(p, now)
        }
        clears.tick(where)
        secrets.tick(where)
        readMap()
        if (++ticks % 20 == 0) toParty(full = ticks % FULL_RESEND_TICKS == 0)
    }

    /** Checkmarks and found counts off ADM's map: its action bar reading, map sync and green checkmarks all land there. */
    private fun readMap() {
        for (room in DungeonScan.rooms) {
            val mark = room.checkmark
            if (marks.put(room, mark) != mark && room.type !in NOT_CLEARS) {
                val m = when (mark) {
                    MapCheckmark.WHITE -> Mark.WHITE
                    MapCheckmark.GREEN -> Mark.GREEN
                    MapCheckmark.RED -> Mark.RED
                    else -> Mark.OTHER
                }
                if (m != Mark.OTHER) clears.onCheckmark(roomKey(room), roomName(room), m)
            }
            val found = room.foundSecrets ?: continue
            if (counts.put(room, found) != found) secrets.observe(roomKey(room), roomName(room), found)
        }
    }

    private fun roomOf(p: DungeonPlayer, now: Long): String? {
        val entity = if (p.name == ClearBreakdown.me) mc.player else p.entity
        if (entity != null) return roomAt(entity.x, entity.z)?.let(::roomKey)
        relayed.roomOf(p.name, now)?.let { return it.ifEmpty { null } }
        // ADM's map icon, the way its map draws it: not placed yet is 0.
        if (p.mapPos.data == 0L) return null
        val x = ((p.mapPos.x + 128) / 2.0 - DungeonScan.startX) * 32.0 / DungeonScan.roomGap - 200
        val z = ((p.mapPos.z + 128) / 2.0 - DungeonScan.startY) * 32.0 / DungeonScan.roomGap - 200
        return roomAt(x, z)?.let(::roomKey)
    }

    private fun onPickup(itemId: Int, playerId: Int) {
        if (!tracking) return
        val level = mc.level ?: return
        val item = level.getEntity(itemId) as? ItemEntity ?: return
        val name = item.item.hoverName.string
        if (SECRET_ITEMS.none { name.contains(it, ignoreCase = true) }) return
        val by = (level.getEntity(playerId) as? Player)?.name?.string
        if (by != null && by in ClearBreakdown.roster) seen("item:$itemId", item.x, item.z, setOf(by), true)
        else seen("item:$itemId", item.x, item.z, near(item.x, item.y, item.z), false)
    }

    private fun onBat(x: Double, y: Double, z: Double) {
        if (!tracking) return
        // By the bat (its id is the server's, and its hurt and death squeaks are one key); by place and second if it's gone.
        val bat = mc.level?.getEntitiesOfClass(Bat::class.java, AABB(x - 3, y - 3, z - 3, x + 3, y + 3, z + 3))?.minByOrNull { it.distanceToSqr(x, y, z) }
        val key = if (bat != null) "bat:${bat.id}"
        else "bat:${Math.floorDiv(x.toInt(), 2)},${Math.floorDiv(y.toInt(), 2)},${Math.floorDiv(z.toInt(), 2)}:${System.currentTimeMillis() / 1000}"
        seen(key, x, z, near(x, y, z), false)
    }

    private fun seenBlock(kind: String, pos: BlockPos) {
        val key = "$kind:${pos.x},${pos.y},${pos.z}"
        val clicked = clicks[pos]?.let { System.currentTimeMillis() - it <= CLICK_MS } == true
        val x = pos.x + 0.5; val y = pos.y + 0.5; val z = pos.z + 0.5
        if (clicked) seen(key, x, z, setOf(ClearBreakdown.me), true) else seen(key, x, z, near(x, y, z), false)
    }

    // One nobody was in reach of is left to ADM's room count, which gives it to whoever was in the room.
    private fun seen(key: String, x: Double, z: Double, candidates: Set<String>, exact: Boolean) {
        if (candidates.isEmpty()) return
        val room = roomAt(x, z) ?: return
        val roomKey = roomKey(room)
        val roomName = roomName(room)
        secrets.sighting(key, roomKey, roomName, candidates, exact, System.currentTimeMillis())
        outbox += Relayed.Secret(key, roomKey, roomName, candidates.toList(), exact)
    }

    /** Players in the run, alive, within reach of a point. */
    private fun near(x: Double, y: Double, z: Double): Set<String> {
        val out = HashSet<String>()
        for (p in DungeonUtils.dungeonTeammates) {
            if (p.isDead || p.name !in ClearBreakdown.roster) continue
            val e = (if (p.name == ClearBreakdown.me) mc.player else p.entity) ?: continue
            if (e.distanceToSqr(x, y, z) <= REACH_SQ) out += p.name
        }
        return out
    }

    /**
     * Once a second: this client's sightings, and where it sees players by their entity (changes, and everything when
     * [full]). Nothing counts as told without a socket to tell it on.
     */
    private fun toParty(full: Boolean) {
        val seenNow = HashMap<String, String>()
        for (p in DungeonUtils.dungeonTeammates) {
            if (p.isDead || p.name !in ClearBreakdown.roster) continue
            val e = (if (p.name == ClearBreakdown.me) mc.player else p.entity) ?: continue
            seenNow[p.name] = roomAt(e.x, e.z)?.let(::roomKey) ?: ""
        }
        val items = ArrayList<Relayed>(outbox)
        for ((name, room) in seenNow) if (full || toldSeen[name] != room) items += Relayed.Presence(name, room)
        for (name in toldSeen.keys) if (name !in seenNow) items += Relayed.Presence(name, null)
        if (items.isEmpty() || !OnlineLink.sendRelay(RelayCodec.frames(items))) return
        outbox.clear()
        toldSeen.clear(); toldSeen.putAll(seenNow)
    }

    /** A party member's sightings: only about people in this run, and only from one of them. */
    private fun fromParty(from: String, items: List<Relayed>) {
        val roster = ClearBreakdown.roster
        if (!tracking || from == ClearBreakdown.me || from !in roster) return
        val now = System.currentTimeMillis()
        for (item in items) when (item) {
            is Relayed.Secret -> {
                val candidates = item.candidates.filterTo(HashSet()) { it in roster }
                // This game's own name for the room, never the sender's text; a key not in this run is dropped.
                val room = roomByKey(item.roomKey) ?: continue
                if (candidates.isNotEmpty()) secrets.sighting(item.key, item.roomKey, roomName(room), candidates, item.exact, now)
            }
            is Relayed.Presence -> if (item.player in roster) relayed.update(from, item.player, item.roomKey, now)
        }
    }
}
