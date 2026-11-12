package com.engineerclient.misc

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onSend
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.features.impl.dungeon.map.tile.DungeonRoom
import com.odtheking.odin.features.impl.dungeon.map.tile.RoomType
import com.odtheking.odin.features.impl.render.Etherwarp
import com.odtheking.odin.utils.IVec2
import com.odtheking.odin.utils.customData
import com.odtheking.odin.utils.isEtherwarpItem
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.phys.Vec3
import kotlin.math.floor

/**
 * Legit Zpew: as you click an etherwarp or a blink (AOTE, AOTV, Hyperion), the camera goes to where the server will put
 * you, a round trip early. Only the view moves (ZpewCameraMixin); the player waits for the server's teleport.
 *
 * Every rule is alldata's (content/11-items): where it lands [TeleportLaw], the look it's cast with
 * (#items.teleport-look), clicks on blocks that act (#items.teleport-door-click), the rooms that refuse
 * (#items.room-refusals) and Wither Impact's 100 ms recast gate (run/server). Clicks waiting on the server are a
 * [ZpewQueue], so spam at high ping chains and corrects itself on each real landing.
 */
object Zpew : Module(
    name = "Legit Zpew",
    category = Category.custom("Engineer Client", 860, 10),
    description = "Zero ping camera: your view jumps to where an etherwarp or AOTE/AOTV/Hyperion teleport lands as you click, before the server moves you.",
    key = null,
) {
    private val etherwarp by BooleanSetting("Etherwarp", true, desc = "Sneaking etherwarps.")
    private val aotv by BooleanSetting("AOTE / AOTV", true, desc = "Aspect of the End and Aspect of the Void teleports (unsneaked).")
    private val hyperion by BooleanSetting("Hyperion", true, desc = "Wither Impact teleports: Hyperion, Valkyrie, Astraea, Scylla, Necron's Blade with all three scrolls.")

    private class Click(val ether: Boolean, val yaw: Float, val pitch: Float, val sneaking: Boolean, val distance: Double, val witherImpact: Boolean = false)

    private val queue = ZpewQueue<Click>(::land)
    @Volatile private var camera: Vec3? = null
    private var ticks = 0L
    private var lastWitherImpact = 0L

    /** The last move packet's look: a block click is cast with it. */
    private var moveYaw = Float.NaN
    private var movePitch = Float.NaN
    /** This tick's block click: null, false (an ordinary block), or true (a block that acts, so no teleport). */
    private var blockClick: Boolean? = null

    private val WITHER_BLADES = setOf("NECRON_BLADE", "SCYLLA", "HYPERION", "VALKYRIE", "ASTRAEA")
    private val ACTS = Regex("^(chest|trapped_chest|ender_chest|lever)$|_button$|_sign$|_fence_gate$|(_head|_skull)$")
    private val NO_TELEPORT = setOf("Teleport Maze", "Boulder")
    private val REFUSALS = listOf(
        "You cannot use abilities in this room!",
        "You cannot do that in this room!",
        "A mystical force in this room prevents you from using that ability!",
    )

    /** Feet; ZpewCameraMixin adds the eye height. */
    @JvmStatic fun cameraPos(): Vec3? = if (enabled) camera else null

    /** ZpewTeleportMixin: the game has just moved you for a server teleport. */
    @JvmStatic fun onTeleportApplied(x: Double, y: Double, z: Double) {
        if (queue.teleport(doubleArrayOf(x, y, z), ticks)) sync()
    }

    private fun sync() {
        camera = queue.camera?.let { Vec3(it[0], it[1], it[2]) }
    }

    private fun reset() {
        queue.clear()
        camera = null
    }

    override fun onDisable() {
        reset()
        super.onDisable()
    }

    private class LevelCells(val level: ClientLevel) : TeleportLaw.Cells {
        private val pos = BlockPos.MutableBlockPos()
        override fun id(x: Int, y: Int, z: Int): String = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos.set(x, y, z)).block).path
        override fun top(x: Int, y: Int, z: Int): Double {
            val shape = level.getBlockState(pos.set(x, y, z)).getCollisionShape(level, pos)
            return if (shape.isEmpty) 0.0 else shape.max(Direction.Axis.Y)
        }
    }

    /** Where [c] lands cast from [from], or null when the server won't move you. */
    private fun land(from: DoubleArray, c: Click): DoubleArray? {
        val cells = LevelCells(mc.level ?: return null)
        if (nearRoom(from) { it.type == RoomType.TRAP || it.data?.name in NO_TELEPORT }) return null
        val to = if (c.ether) TeleportLaw.etherwarp(from[0], from[1], from[2], c.yaw, c.pitch, c.distance, cells)
        else TeleportLaw.blink(from[0], from[1], from[2], c.yaw, c.pitch, c.sneaking, c.distance.toInt(), cells)
        return to?.takeUnless { nearRoom(it) { r -> r.data?.name in NO_TELEPORT } }
    }

    /** In, or a block from, a scanned room [test] picks: the server's room reaches into the doorway. Tile t spans -200 + 32t, 31 wide. */
    private fun nearRoom(p: DoubleArray, test: (DungeonRoom) -> Boolean): Boolean {
        val tiles = HashSet<Long>()
        for (dx in -1..1) for (dz in -1..1)
            tiles += IVec2(Math.floorDiv(floor(p[0]).toInt() + dx + 200, 32), Math.floorDiv(floor(p[2]).toInt() + dz + 200, 32)).data
        return DungeonScan.rooms.any { room -> test(room) && room.tiles.any { it.data in tiles } }
    }

    /** The click as the server will cast it, or null when it isn't a teleport we show. */
    private fun click(packet: ServerboundUseItemPacket): Click? {
        val p = mc.player ?: return null
        if (blockClick == true || (DungeonUtils.inBoss && DungeonUtils.isFloor(7))) return null
        val stack = p.getItemInHand(packet.hand)
        val sneaking = p.lastSentInput.shift()
        val (yaw, pitch) = if (blockClick == false && !moveYaw.isNaN()) moveYaw to movePitch else packet.yRot to packet.xRot
        if (sneaking) stack.isEtherwarpItem()?.let { return if (etherwarp) Click(true, yaw, pitch, true, Etherwarp.getEtherwarpDistance(it)) else null }
        val id = stack.itemId
        val distance = when {
            id == "ASPECT_OF_THE_END" || id == "ASPECT_OF_THE_VOID" -> if (aotv) 8 + stack.customData.getInt("tuned_transmission").orElse(0) else return null
            id in WITHER_BLADES && stack.customData.getListOrEmpty("ability_scroll").size == 3 -> if (hyperion) 10 else return null
            else -> return null
        }
        return Click(false, yaw, pitch, sneaking, distance.toDouble(), id in WITHER_BLADES)
    }

    init {
        on<LevelEvent.Load> { reset() }

        on<MessageEvent> {
            if (!queue.isEmpty && REFUSALS.any { message.contains(it) }) { queue.refuse(); sync() }
        }

        on<TickEvent.End> {
            blockClick = null
            ticks++
            if (queue.expire(ticks)) sync()
        }

        onSend<ServerboundMovePlayerPacket> {
            if (hasRotation()) { moveYaw = getYRot(0f); movePitch = getXRot(0f) }
        }

        onSend<ServerboundUseItemOnPacket> {
            val level = mc.level ?: return@onSend
            blockClick = ACTS.containsMatchIn(BuiltInRegistries.BLOCK.getKey(level.getBlockState(hitResult.blockPos).block).path)
        }

        onSend<ServerboundUseItemPacket> {
            val c = click(this) ?: return@onSend
            val p = mc.player ?: return@onSend
            var counts = true
            if (c.witherImpact) {
                val now = System.currentTimeMillis()
                counts = now - lastWitherImpact >= 100
                if (counts) lastWitherImpact = now
            }
            queue.click(c, ticks, doubleArrayOf(p.x, p.y, p.z), counts)
            sync()
        }
    }
}
