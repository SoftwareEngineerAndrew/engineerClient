package com.engineerclient.clearbreakdown

import com.engineerclient.EngineerClient.mc
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.SkullBlock
import net.minecraft.world.level.block.entity.SkullBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.phys.AABB
import java.util.UUID

/**
 * Secrets this client sees happen, the way ADR records them: an item picked up (the packet names
 * who picked it up), a chest lid opening, a wither essence skull going, a secret bat squeaking (the
 * quiet squeak only secret bats make). Who could have got it is whoever was in reach (6 blocks),
 * or exactly you when you clicked that chest or skull just before. Each goes to
 * [ClearBreakdown.secrets], and into [outbox] for the party (ADR Online).
 *
 * A sighting nobody was in reach of is dropped rather than counted for nobody: Odin's room count
 * then gives it to whoever was in the room.
 */
object Sightings {
    /** Sightings not yet sent to the party. */
    val outbox = ArrayList<Relayed>()

    private const val REACH_SQ = 6.0 * 6.0
    private const val CLICK_MS = 1000L
    /** Chunks around you searched for essence skulls, as ADR's skull scan (a twentieth each tick). */
    private const val SKULL_CHUNKS = 12
    private val WITHER_ESSENCE_ID: UUID = UUID.fromString("2865274b-3097-394e-8149-ec629c72d850")
    /** Odin's list of the items dungeon secrets drop (EventDispatcher.dungeonItemDrops, private there). */
    private val SECRET_ITEMS = listOf(
        "Health Potion VIII Splash Potion", "Healing Potion 8 Splash Potion", "Healing Potion VIII Splash Potion", "Healing VIII Splash Potion", "Healing 8 Splash Potion",
        "Decoy", "Inflatable Jerry", "Spirit Leap", "Trap", "Training Weights", "Defuse Kit", "Dungeon Chest Key", "Treasure Talisman", "Revive Stone", "Architect's First Draft",
        "Secret Dye", "Candycomb",
    )

    private val essence = HashSet<BlockPos>()
    private val clicks = HashMap<BlockPos, Long>()
    private var scanTick = 0

    fun reset() {
        outbox.clear(); essence.clear(); clicks.clear()
    }

    private val tracking get() = ClearBreakdown.active && DungeonUtils.inClear

    /** You right-clicked a block (a chest or skull you might be looting). */
    fun onClick(pos: BlockPos) {
        if (tracking) clicks[pos.immutable()] = System.currentTimeMillis()
    }

    fun onPickup(itemId: Int, playerId: Int) {
        if (!tracking) return
        val level = mc.level ?: return
        val item = level.getEntity(itemId) as? ItemEntity ?: return
        val name = item.item.hoverName.string
        if (SECRET_ITEMS.none { name.contains(it, ignoreCase = true) }) return
        val by = (level.getEntity(playerId) as? Player)?.name?.string
        if (by != null && by in ClearBreakdown.roster) seen("item:$itemId", item.x, item.z, setOf(by), true)
        else seen("item:$itemId", item.x, item.z, near(item.x, item.y, item.z), false)
    }

    fun onChestOpen(pos: BlockPos) {
        if (tracking) seenBlock("chest", pos)
    }

    fun onBlockChange(pos: BlockPos, updated: BlockState) {
        if (!tracking || updated.block is SkullBlock) return
        if (essence.remove(pos)) seenBlock("essence", pos)
    }

    fun onBat(x: Double, y: Double, z: Double) {
        if (!tracking) return
        // Keyed by the bat (its entity id is the server's, the same on every client, and its hurt and
        // death squeaks are one key); by place and second only if it's already gone.
        val bat = mc.level?.getEntitiesOfClass(Bat::class.java, AABB(x - 3, y - 3, z - 3, x + 3, y + 3, z + 3))?.minByOrNull { it.distanceToSqr(x, y, z) }
        val key = if (bat != null) "bat:${bat.id}"
        else "bat:${Math.floorDiv(x.toInt(), 2)},${Math.floorDiv(y.toInt(), 2)},${Math.floorDiv(z.toInt(), 2)}:${System.currentTimeMillis() / 1000}"
        seen(key, x, z, near(x, y, z), false)
    }

    /** Every tick of a clear: a twentieth of the chunks around you searched for essence skulls. */
    fun tick() {
        if (!tracking) return
        val level = mc.level ?: return
        val player = mc.player ?: return
        val cx = player.blockPosition().x shr 4
        val cz = player.blockPosition().z shr 4
        val slice = scanTick++ % 20
        for (x in cx - SKULL_CHUNKS..cx + SKULL_CHUNKS) for (z in cz - SKULL_CHUNKS..cz + SKULL_CHUNKS) {
            if (Math.floorMod(x * 7 + z, 20) != slice) continue
            val chunk = level.chunkSource.getChunk(x, z, ChunkStatus.FULL, false) ?: continue
            for (be in chunk.blockEntities.values) {
                if (be !is SkullBlockEntity) continue
                if (be.ownerProfile?.partialProfile()?.id == WITHER_ESSENCE_ID) essence += be.blockPos.immutable()
            }
        }
    }

    private fun seenBlock(kind: String, pos: BlockPos) {
        val key = "$kind:${pos.x},${pos.y},${pos.z}"
        val clicked = clicks[pos]?.let { System.currentTimeMillis() - it <= CLICK_MS } == true
        val x = pos.x + 0.5; val y = pos.y + 0.5; val z = pos.z + 0.5
        if (clicked) seen(key, x, z, setOf(ClearBreakdown.me), true) else seen(key, x, z, near(x, y, z), false)
    }

    private fun seen(key: String, x: Double, z: Double, candidates: Set<String>, exact: Boolean) {
        if (candidates.isEmpty()) return
        val room = ClearBreakdown.roomAt(x, z) ?: return
        val roomKey = ClearBreakdown.roomKey(room)
        val roomName = ClearBreakdown.roomName(room)
        ClearBreakdown.secrets.sighting(key, roomKey, roomName, candidates, exact, System.currentTimeMillis())
        outbox += Relayed.Secret(key, roomKey, roomName, candidates.toList(), exact)
    }

    /** Players in the run, alive, within reach of a point. */
    private fun near(x: Double, y: Double, z: Double): Set<String> {
        val team = DungeonUtils.dungeonTeammates.associateBy { it.name }
        val out = HashSet<String>()
        for (name in ClearBreakdown.roster.keys) {
            val p = team[name] ?: continue
            if (p.isDead) continue
            val e = (if (name == ClearBreakdown.me) mc.player else p.entity) ?: continue
            if (e.distanceToSqr(x, y, z) <= REACH_SQ) out += name
        }
        return out
    }
}
