package com.engineerclient.misc

import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.events.core.onSend
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.render.Etherwarp
import com.odtheking.odin.utils.isEtherwarpItem
import com.odtheking.odin.utils.skyblock.ActionBarListener
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
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
 * Zpew (zero ping etherwarp camera), the camera half of rsm's Ether "Zpew" (github.com/rs-mod/rsm, Ether.java):
 * the moment you etherwarp, the camera jumps to where you will land instead of waiting a round trip for the server's
 * teleport. Your player isn't moved; only the view (ZpewCameraMixin). rsm's No Rotate is left out.
 *
 * Each sneaking right click with an etherwarp item (sent as a use-item packet) is traced from where the camera is now
 * (so several warps in a row chain), and the landing (block centre, 1.05 above it, 2.05 on a wall/fence) is queued. Each server teleport
 * takes the oldest queued landing: if it isn't exactly there, or nothing is left queued, the camera goes back to the
 * player. A warp the server never answers lets go after the timeout.
 *
 * Off where rsm turns it off: F7/M7 boss, and Boulder / Teleport Maze (except on the room's edge), and with under
 * 180 mana. A right click on a chest, lever, button and the like opens that instead of warping, so it's skipped.
 */
object Zpew : Module(
    name = "Zpew",
    category = Category.custom("Engineer Client", 860, 10),
    description = "Zero ping etherwarp camera: your view jumps to the etherwarp landing as you click, before the server teleports you.",
    key = null,
) {
    private val timeout by NumberSetting("Timeout", 20, 2..40, 1, desc = "A warp the server hasn't answered after this long puts the camera back on you.", unit = " ticks")

    private class Pending(val pos: Vec3, val tick: Long)

    /** Where the camera is drawn while a warp is on its way (null: at the player). */
    @Volatile private var camera: Vec3? = null
    private val pending = ArrayDeque<Pending>()
    private var serverTicks = 0L

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

    init {
        on<LevelEvent.Load> { reset() }

        on<TickEvent.Server> {
            serverTicks++
            val first = pending.firstOrNull()
            if (first != null && serverTicks - first.tick >= timeout) reset()
            else if (first == null && camera != null) camera = null
        }

        onSend<ServerboundUseItemPacket> {
            val p = mc.player ?: return@onSend
            val level = mc.level ?: return@onSend
            if (!p.lastSentInput.shift()) return@onSend
            val stack = p.getItemInHand(hand)
            val data = stack.isEtherwarpItem() ?: return@onSend
            if (ActionBarListener.maxMana > 0 && ActionBarListener.currentMana < 180) return@onSend
            if (!allowedHere()) return@onSend
            (mc.hitResult as? BlockHitResult)?.let { hit ->
                val block = level.getBlockState(hit.blockPos).block
                if (ignored.any { it.isInstance(block) }) return@onSend
            }

            val from = camera ?: p.position()
            val ether = Etherwarp.getEtherPos(from, Etherwarp.getEtherwarpDistance(data), false, true, true)
            val pos = ether.pos
            if (!ether.succeeded || pos == null) return@onSend
            // A block taller than a cell (wall, fence, closed gate: collision top 1.5) is landed on a cell higher (alldata #items.etherwarp-landing).
            val tall = level.getBlockState(pos).getCollisionShape(level, pos).let { !it.isEmpty && it.max(Direction.Axis.Y) > 1.0 }
            val landing = Vec3(pos.x + 0.5, pos.y + if (tall) 2.05 else 1.05, pos.z + 0.5)
            pending.addLast(Pending(landing, serverTicks))
            camera = landing
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
