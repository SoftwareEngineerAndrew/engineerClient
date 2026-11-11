package com.engineerclient.misc

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
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
 * Off where rsm turns it off: F7/M7 boss, and Boulder / Teleport Maze (except on the room's edge). A right click on a
 * chest, lever, button and the like opens that instead, so it's skipped.
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
    private val debugLog by BooleanSetting("Debug Log", false, desc = "Writes every click, teleport and camera move to logs/zpew.log (for working out a flicker).")

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
    @JvmStatic fun cameraPos(): Vec3? {
        val c = if (enabled) camera else null
        if (debugLog && c !== lastDrawn) { log("FRAME camera ${fmt(c)}"); lastDrawn = c }
        return c
    }
    private var lastDrawn: Vec3? = null

    /** ZpewTeleportMixin: the game has just moved you to [x], [y], [z] for a server teleport. */
    @JvmStatic fun onTeleportApplied(x: Double, y: Double, z: Double) {
        val r = queue.teleport(doubleArrayOf(x, y, z), ticks)
        if (r == ZpewQueue.NONE) return
        sync()
        if (debugLog) log("TP ${fmt(x, y, z)} answered=${when (r) { ZpewQueue.LATE -> "late"; ZpewQueue.UNMATCHED -> "none"; else -> r.toString() }} waiting=${queue.size} rtt=${"%.1f".format(queue.roundTrip)}±${"%.1f".format(queue.spread)} -> camera ${fmt(camera)}")
    }

    // ---- Debug Log: logs/zpew.log, one line per event, real ms and client tick ----
    private var logOut: java.io.BufferedWriter? = null
    private fun log(line: String) {
        val out = logOut ?: runCatching {
            val f = mc.gameDirectory.toPath().resolve("logs").resolve("zpew.log")
            java.nio.file.Files.createDirectories(f.parent)
            java.nio.file.Files.newBufferedWriter(f, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
        }.getOrNull()?.also { logOut = it } ?: return
        runCatching { out.write("${System.currentTimeMillis()} t$ticks $line"); out.newLine() }
    }
    private fun fmt(v: Vec3?) = if (v == null) "player" else fmt(v.x, v.y, v.z)
    private fun fmt(x: Double, y: Double, z: Double) = "(%.2f, %.2f, %.2f)".format(x, y, z)

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
            val dropped = queue.expire(ticks)
            if (dropped > 0) {
                sync()
                if (debugLog) log("EXPIRE $dropped limit=${"%.1f".format(queue.limit())} waiting=${queue.size} -> camera ${fmt(camera)}")
            }
            if (!debugLog && logOut != null) { runCatching { logOut?.close() }; logOut = null }
            logOut?.let { runCatching { it.flush() } }
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
            if (debugLog) log("CLICK ${if (click.ether) "ether" else "blink ${click.distance.toInt()}"} look=(%.1f, %.1f)${if (blockClickThisTick) " block" else ""} sneak=$sneaking expected=$expected player ${fmt(p.x, p.y, p.z)} waiting=${queue.size} -> camera ${fmt(camera)}".format(yaw, pitch))
        }
    }
}
