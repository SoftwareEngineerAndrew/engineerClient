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
import com.odtheking.odin.utils.skyblock.ActionBarListener
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.level.block.AnvilBlock
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.CauldronBlock
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.DispenserBlock
import net.minecraft.world.level.block.EnderChestBlock
import net.minecraft.world.level.block.HopperBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/**
 * Legit Zpew (zero ping camera; was "Zpew"), the camera half of rsm's Ether "Zpew" (github.com/rs-mod/rsm, Ether.java): the moment you
 * etherwarp or teleport (AOTE, AOTV, Hyperion), the camera jumps to where you will land instead of waiting a round trip
 * for the server's teleport. Your player isn't moved; only the view (ZpewCameraMixin). rsm's No Rotate is left out.
 *
 * Every click is kept with its look until the server answers ([ZpewQueue]): the camera is the end of the chain of
 * waiting clicks, each worked out from the one before. When a teleport is applied (ZpewTeleportMixin, the moment the game
 * moves you, so no frame shows the old spot) it is matched against the waiting clicks and the rest of the chain is worked
 * out again from the real landing. So with high ping (several clicks waiting at once) a wrong guess or a cast the server
 * dropped only moves the camera to the right spot, instead of back to a player still several teleports behind.
 *
 * - Etherwarp (sneaking, an etherwarp item): alldata's etherwarp law ([TeleportLaw.etherwarp]), 57 + tuners blocks.
 * - Teleport (AOTE / AOTV unsneaked, Hyperion-type blades with all three scrolls either way): alldata's blink law
 *   ([TeleportLaw.blink]). 8 blocks + Transmission tuners, 10 for Wither Impact.
 * - Clicks the server likely won't cast (a Wither Impact within 100 ms of the last, an etherwarp with under 180 mana and overflow mana together) stay out
 *   of the camera's chain but are still matched, in case it does.
 * - The look (alldata #items.teleport-look): a plain click casts with its own yaw and pitch; a click on a block casts
 *   with the look of the last move packet sent.
 *
 * Never where the server refuses the ability (alldata #items.room-refusals): the F7/M7 boss, a trap room (cast from), the
 * Teleport Maze and Boulder (cast from or landing in); any other refusal line brings the camera straight back. A right
 * click on a chest, lever, button and the like opens that instead, so it's skipped.
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

    /** One click: worked out again from any start with its own look. */
    private class Click(val ether: Boolean, val yaw: Float, val pitch: Float, val sneaking: Boolean, val distance: Double)

    private val queue = ZpewQueue<Click> { from, c -> predict(from, c) }

    /** Where the camera is drawn while teleports are on their way (null: at the player). */
    @Volatile private var camera: Vec3? = null
    private var ticks = 0L
    private var lastWitherImpact = 0L

    /** The look of the last move packet sent with one: the server casts a block click with it. */
    private var moveYaw = Float.NaN
    private var movePitch = Float.NaN
    /** A use-item-on (block click) was sent this client tick: the use-item after it casts with the move look. */
    private var blockClickThisTick = false

    private val WITHER_BLADES = setOf("NECRON_BLADE", "SCYLLA", "HYPERION", "VALKYRIE", "ASTRAEA")
    /** Wither Impact's recast gate: 100 ms of real time since the last cast, read when the server handles the click (alldata items/hyperion, 18,520 pairs). */
    private const val WITHER_IMPACT_GATE_MS = 100L

    private val ignored = listOf(
        HopperBlock::class.java, AnvilBlock::class.java, ChestBlock::class.java, EnderChestBlock::class.java,
        DispenserBlock::class.java, LeverBlock::class.java, ButtonBlock::class.java, CauldronBlock::class.java,
    )

    /** The camera position for ZpewCameraMixin (feet height; the eye height is added there), or null. */
    @JvmStatic fun cameraPos(): Vec3? = if (enabled) camera else null

    /** ZpewTeleportMixin: the game has just moved you to [x], [y], [z] for a server teleport. */
    @JvmStatic fun onTeleportApplied(x: Double, y: Double, z: Double) {
        if (queue.teleport(doubleArrayOf(x, y, z), ticks) == ZpewQueue.NONE) return
        sync()
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
            pos.set(x, y, z)
            val shape = level.getBlockState(pos).getCollisionShape(level, pos)
            return if (shape.isEmpty) 0.0 else shape.max(Direction.Axis.Y)
        }
    }

    /**
     * Where a click lands from [from], or null when the server refuses it (alldata #items.room-refusals): a trap room
     * refuses every ability by the room you stand in, and the Teleport Maze and Boulder refuse an item teleport that
     * starts or would land in them. Checked for every click of a chain, from where that click is cast.
     */
    private fun predict(from: DoubleArray, c: Click): DoubleArray? {
        val cells = LevelCells(mc.level ?: return null)
        if (inRoom(from[0], from[2]) { it.type == RoomType.TRAP || it.data?.name in NO_TELEPORT }) return null
        val to = if (c.ether) TeleportLaw.etherwarp(from[0], from[1], from[2], c.yaw, c.pitch, c.distance, cells)
        else TeleportLaw.blink(from[0], from[1], from[2], c.yaw, c.pitch, c.sneaking, c.distance.toInt(), cells)
        return to?.takeUnless { inRoom(it[0], it[2]) { r -> r.data?.name in NO_TELEPORT } }
    }

    private val NO_TELEPORT = setOf("Teleport Maze", "Boulder")

    /**
     * Whether [x], [z] is in (or within a block of) a scanned room that [test] picks. The room grid: tile t spans
     * -200 + 32t to + 30, a 1-block gap before the next; a spot a block from a tile's edge counts for both sides, since
     * the server's room reaches the doorway (alldata: refusals "at its edge").
     */
    private fun inRoom(x: Double, z: Double, test: (DungeonRoom) -> Boolean): Boolean {
        val tiles = HashSet<Long>()
        for (dx in -1..1) for (dz in -1..1) {
            tiles += IVec2(Math.floorDiv(kotlin.math.floor(x).toInt() + dx + 200, 32), Math.floorDiv(kotlin.math.floor(z).toInt() + dz + 200, 32)).data
        }
        return DungeonScan.rooms.any { room -> test(room) && room.tiles.any { it.data in tiles } }
    }

    /** The boss room refuses abilities (alldata #items.room-refusals): F7 / M7. */
    private fun allowedHere(): Boolean = mc.player != null && !(DungeonUtils.inBoss && DungeonUtils.isFloor(7))

    /** Hypixel's lines for an ability the room refused (alldata #items.room-refusals): that click won't move you. */
    private val REFUSALS = listOf(
        "You cannot use abilities in this room!",
        "You cannot do that in this room!",
        "A mystical force in this room prevents you from using that ability!",
    )

    init {
        on<LevelEvent.Load> { reset() }

        // Anywhere else abilities are off: the refusal line comes in the click's tick, so the camera comes straight back.
        on<MessageEvent> {
            if (queue.size > 0 && REFUSALS.any { message.contains(it) }) { queue.refuse(); sync() }
        }

        on<TickEvent.End> {
            blockClickThisTick = false
            ticks++
            if (queue.expire(ticks) > 0) sync()
        }

        onSend<ServerboundMovePlayerPacket> {
            if (hasRotation()) { moveYaw = getYRot(0f); movePitch = getXRot(0f) }
        }

        onSend<ServerboundUseItemOnPacket> { blockClickThisTick = true }

        onSend<ServerboundUseItemPacket> {
            val p = mc.player ?: return@onSend
            val level = mc.level ?: return@onSend
            if (!allowedHere()) return@onSend
            (mc.hitResult as? BlockHitResult)?.let { hit ->
                val block = level.getBlockState(hit.blockPos).block
                if (ignored.any { it.isInstance(block) }) return@onSend
            }
            val stack = p.getItemInHand(hand)
            val sneaking = p.lastSentInput.shift()
            val (yaw, pitch) = if (blockClickThisTick && !moveYaw.isNaN()) moveYaw to movePitch else yRot to xRot

            val etherData = if (sneaking) stack.isEtherwarpItem() else null
            val click: Click
            var expected = true
            if (etherData != null) {
                if (!etherwarp) return@onSend
                click = Click(true, yaw, pitch, true, Etherwarp.getEtherwarpDistance(etherData))
                // Overflow mana pays too (it is spent first), so it counts with the bar's mana.
                expected = ActionBarListener.maxMana == 0 || ActionBarListener.currentMana + ActionBarListener.overflowMana >= 180
            } else {
                val id = stack.itemId
                val distance = when {
                    id == "ASPECT_OF_THE_END" || id == "ASPECT_OF_THE_VOID" -> if (aotv) 8 + stack.customData.getInt("tuned_transmission").orElse(0) else return@onSend
                    id in WITHER_BLADES && stack.customData.getListOrEmpty("ability_scroll").size == 3 -> if (hyperion) 10 else return@onSend
                    else -> return@onSend
                }
                click = Click(false, yaw, pitch, sneaking, distance.toDouble())
                if (id in WITHER_BLADES) {
                    val now = System.currentTimeMillis()
                    expected = now - lastWitherImpact >= WITHER_IMPACT_GATE_MS
                    if (expected) lastWitherImpact = now
                }
            }
            queue.click(click, ticks, doubleArrayOf(p.x, p.y, p.z), expected)
            sync()
        }
    }
}
