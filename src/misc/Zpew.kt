package com.engineerclient.misc

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.events.core.onSend
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.render.Etherwarp
import com.odtheking.odin.utils.customData
import com.odtheking.odin.utils.isEtherwarpItem
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.skyblock.ActionBarListener
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.entity.Relative
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
 * Zpew (zero ping camera), the camera half of rsm's Ether "Zpew" (github.com/rs-mod/rsm, Ether.java): the moment you
 * etherwarp or teleport (AOTE, AOTV, Hyperion), the camera jumps to where you will land instead of waiting a round trip
 * for the server's teleport. Your player isn't moved; only the view (ZpewCameraMixin). rsm's No Rotate is left out.
 *
 * Each click (a use-item packet) is worked out from where the camera is now (so several in a row chain), and the landing
 * is queued. Each server teleport takes the oldest queued landing: if it isn't exactly there, or nothing is left queued,
 * the camera goes back to the player. A click the server never answers lets go after the timeout.
 *
 * - Etherwarp (sneaking, an etherwarp item): Odin's etherwarp ray from the sneak eye; the landing is the block's centre
 *   1.05 above it, 2.05 on a wall/fence/closed gate (alldata #items.etherwarp-landing).
 * - Teleport (AOTE / AOTV unsneaked, Hyperion-type blades with all three scrolls either way): alldata's blink law
 *   ([BlinkLaw]). 8 blocks + Transmission tuners, 10 for Wither Impact. A Wither Impact within 125 ms of the last isn't
 *   cast by the server (rsm's gate), so it isn't predicted.
 * - The look (alldata #items.teleport-look): a plain click casts with its own yaw and pitch; a click on a block casts
 *   with the look of the last move packet sent.
 *
 * Off where rsm turns it off: F7/M7 boss, and Boulder / Teleport Maze (except on the room's edge); etherwarp under 180
 * mana. A right click on a chest, lever, button and the like opens that instead, so it's skipped.
 */
object Zpew : Module(
    name = "Zpew",
    category = Category.custom("Engineer Client", 860, 10),
    description = "Zero ping camera: your view jumps to where an etherwarp or AOTE/AOTV/Hyperion teleport lands as you click, before the server moves you.",
    key = null,
) {
    private val etherwarp by BooleanSetting("Etherwarp", true, desc = "Sneaking etherwarps.")
    private val teleport by BooleanSetting("Teleport", true, desc = "AOTE, AOTV and Hyperion (Wither Impact) teleports.")
    private val timeout by NumberSetting("Timeout", 20, 2..40, 1, desc = "A click the server hasn't answered after this long puts the camera back on you.", unit = " ticks")

    private class Pending(val pos: Vec3, val tick: Long)

    /** Where the camera is drawn while a teleport is on its way (null: at the player). */
    @Volatile private var camera: Vec3? = null
    private val pending = ArrayDeque<Pending>()
    private var serverTicks = 0L
    private var lastWitherImpact = 0L

    /** The look of the last move packet sent with one: the server casts a block click with it. */
    private var moveYaw = Float.NaN
    private var movePitch = Float.NaN
    /** A use-item-on (block click) was sent this client tick: the use-item after it casts with the move look. */
    private var blockClickThisTick = false

    private val WITHER_BLADES = setOf("NECRON_BLADE", "SCYLLA", "HYPERION", "VALKYRIE", "ASTRAEA")
    private const val WITHER_IMPACT_GATE_MS = 125L

    private val ignored = listOf(
        HopperBlock::class.java, AnvilBlock::class.java, ChestBlock::class.java, EnderChestBlock::class.java,
        DispenserBlock::class.java, LeverBlock::class.java, ButtonBlock::class.java, CauldronBlock::class.java,
    )

    /** The camera position for ZpewCameraMixin (feet height; the eye height is added there), or null. */
    @JvmStatic fun cameraPos(): Vec3? = if (enabled) camera else null

    private fun reset() {
        pending.clear()
        camera = null
    }

    override fun onDisable() {
        reset()
        super.onDisable()
    }

    private fun allowedHere(): Boolean {
        val p = mc.player ?: return false
        if (DungeonUtils.inBoss && DungeonUtils.isFloor(7)) return false
        if (DungeonUtils.currentRoomName in listOf("Boulder", "Teleport Maze")) {
            // On the room's edge (between rooms) is fine.
            return Math.floorMod(p.blockX + 201, 32) == 0 || Math.floorMod(p.blockZ + 201, 32) == 0
        }
        return true
    }

    private fun queue(landing: Vec3) {
        pending.addLast(Pending(landing, serverTicks))
        camera = landing
    }

    init {
        on<LevelEvent.Load> { reset() }

        on<TickEvent.Server> {
            serverTicks++
            val first = pending.firstOrNull()
            if (first != null && serverTicks - first.tick >= timeout) reset()
            else if (first == null && camera != null) camera = null
        }

        on<TickEvent.End> { blockClickThisTick = false }

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
            val from = camera ?: p.position()

            val etherData = if (sneaking) stack.isEtherwarpItem() else null
            if (etherData != null) {
                if (!etherwarp) return@onSend
                if (ActionBarListener.maxMana > 0 && ActionBarListener.currentMana < 180) return@onSend
                val ether = Etherwarp.getEtherPos(from, Etherwarp.getEtherwarpDistance(etherData), false, true, true)
                val pos = ether.pos
                if (!ether.succeeded || pos == null) return@onSend
                // A block taller than a cell (wall, fence, closed gate: collision top 1.5) is landed on a cell higher (alldata #items.etherwarp-landing).
                val tall = level.getBlockState(pos).getCollisionShape(level, pos).let { !it.isEmpty && it.max(Direction.Axis.Y) > 1.0 }
                queue(Vec3(pos.x + 0.5, pos.y + if (tall) 2.05 else 1.05, pos.z + 0.5))
                return@onSend
            }

            if (!teleport) return@onSend
            val id = stack.itemId
            val distance = when {
                id == "ASPECT_OF_THE_END" || id == "ASPECT_OF_THE_VOID" -> 8 + (stack.customData.getInt("tuned_transmission").orElse(0))
                id in WITHER_BLADES && stack.customData.getListOrEmpty("ability_scroll").size == 3 -> 10
                else -> return@onSend
            }
            val witherImpact = id in WITHER_BLADES
            val now = System.currentTimeMillis()
            if (witherImpact && now - lastWitherImpact < WITHER_IMPACT_GATE_MS) return@onSend

            val (yaw, pitch) = if (blockClickThisTick && !moveYaw.isNaN()) moveYaw to movePitch else yRot to xRot
            val to = BlinkLaw.predict(from.x, from.y, from.z, yaw, pitch, sneaking, distance,
                solid = { x, y, z -> BlinkLaw.isSolid(BuiltInRegistries.BLOCK.getKey(level.getBlockState(BlockPos(x, y, z)).block).path) },
                top = { x, y, z ->
                    val bp = BlockPos(x, y, z)
                    val shape = level.getBlockState(bp).getCollisionShape(level, bp)
                    if (shape.isEmpty) 0.0 else shape.max(Direction.Axis.Y)
                },
            ) ?: return@onSend
            if (witherImpact) lastWitherImpact = now
            queue(Vec3(to[0], to[1], to[2]))
        }

        // Teleports arrive on the network thread: handled on the client thread, just before the game moves you.
        onReceive<ClientboundPlayerPositionPacket> {
            val c = change
            val rel = relatives
            mc.execute {
                val p = mc.player ?: return@execute
                val x = if (Relative.X in rel) p.x + c.position.x else c.position.x
                val y = if (Relative.Y in rel) p.y + c.position.y else c.position.y
                val z = if (Relative.Z in rel) p.z + c.position.z else c.position.z
                val sent = pending.removeFirstOrNull()
                if (sent == null || sent.pos.x != x || sent.pos.y != y || sent.pos.z != z || pending.isEmpty()) reset()
            }
        }
    }
}
