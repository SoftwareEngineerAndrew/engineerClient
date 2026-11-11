package com.engineerclient.misc

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onSend
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.render.Etherwarp
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
 * Zpew (zero ping camera), the camera half of rsm's Ether "Zpew" (github.com/rs-mod/rsm, Ether.java): the moment you
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
 * - Clicks the server likely won't cast (a Wither Impact within 125 ms of the last, an etherwarp under 180 mana) stay out
 *   of the camera's chain but are still matched, in case it does.
 * - The look (alldata #items.teleport-look): a plain click casts with its own yaw and pitch; a click on a block casts
 *   with the look of the last move packet sent.
 *
 * Off where rsm turns it off: F7/M7 boss, and Boulder / Teleport Maze (except on the room's edge). A right click on a
 * chest, lever, button and the like opens that instead, so it's skipped.
 */
object Zpew : Module(
    name = "Zpew",
    category = Category.custom("Engineer Client", 860, 10),
    description = "Zero ping camera: your view jumps to where an etherwarp or AOTE/AOTV/Hyperion teleport lands as you click, before the server moves you.",
    key = null,
) {
    private val etherwarp by BooleanSetting("Etherwarp", true, desc = "Sneaking etherwarps.")
    private val teleport by BooleanSetting("Teleport", true, desc = "AOTE, AOTV and Hyperion (Wither Impact) teleports.")
    private val timeout by NumberSetting("Timeout", 20, 2..40, 1, desc = "A click the server hasn't answered after this long is taken as refused (sooner once your round trip is known: 4 ticks past it).", unit = " ticks")

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
    private const val WITHER_IMPACT_GATE_MS = 125L

    private val ignored = listOf(
        HopperBlock::class.java, AnvilBlock::class.java, ChestBlock::class.java, EnderChestBlock::class.java,
        DispenserBlock::class.java, LeverBlock::class.java, ButtonBlock::class.java, CauldronBlock::class.java,
    )

    /** The camera position for ZpewCameraMixin (feet height; the eye height is added there), or null. */
    @JvmStatic fun cameraPos(): Vec3? = if (enabled) camera else null

    /** ZpewTeleportMixin: the game has just moved you to [x], [y], [z] for a server teleport. */
    @JvmStatic fun onTeleportApplied(x: Double, y: Double, z: Double) {
        if (queue.size == 0) return
        queue.teleport(doubleArrayOf(x, y, z), ticks)
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

    private fun predict(from: DoubleArray, c: Click): DoubleArray? {
        val cells = LevelCells(mc.level ?: return null)
        return if (c.ether) TeleportLaw.etherwarp(from[0], from[1], from[2], c.yaw, c.pitch, c.distance, cells)
        else TeleportLaw.blink(from[0], from[1], from[2], c.yaw, c.pitch, c.sneaking, c.distance.toInt(), cells)
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

    init {
        on<LevelEvent.Load> { reset() }

        on<TickEvent.End> {
            blockClickThisTick = false
            ticks++
            if (queue.size > 0) { queue.expire(ticks, timeout); sync() }
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
                expected = ActionBarListener.maxMana == 0 || ActionBarListener.currentMana >= 180
            } else {
                if (!teleport) return@onSend
                val id = stack.itemId
                val distance = when {
                    id == "ASPECT_OF_THE_END" || id == "ASPECT_OF_THE_VOID" -> 8 + stack.customData.getInt("tuned_transmission").orElse(0)
                    id in WITHER_BLADES && stack.customData.getListOrEmpty("ability_scroll").size == 3 -> 10
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
