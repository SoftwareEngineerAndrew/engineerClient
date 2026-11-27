package com.adm.features.impl.render

import com.mojang.blaze3d.platform.InputConstants
import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.clickgui.settings.impl.DropdownSetting
import com.adm.clickgui.settings.impl.NumberSetting
import com.adm.clickgui.settings.impl.SelectorSetting
import com.adm.events.InputEvent
import com.adm.events.RenderExtractEvent
import com.adm.events.core.EventPriority
import com.adm.events.core.on
import com.adm.events.core.onReceive
import com.adm.features.Module
import com.adm.utils.*
import com.adm.utils.Color.Companion.multiplyAlphaRgba
import com.adm.utils.Color.Companion.withAlpha
import com.adm.utils.render.BoxStyle
import com.adm.utils.render.drawFilledBox
import com.adm.utils.render.drawWireFrameBox
import com.adm.utils.skyblock.Island
import com.adm.utils.skyblock.LocationUtils
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.Pose
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.block.*
import net.minecraft.world.level.block.piston.PistonHeadBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.*

object Etherwarp : Module(
    name = "Etherwarp",
    description = "Provides configurable visual feedback for Etherwarp."
) {
    private val render by BooleanSetting("Show Guess", true, desc = "Shows where Etherwarp will take you.")
    private val color by ColorSetting("Success Color", Colors.MINECRAFT_GREEN, desc = "Color of the box.").withDependency { render }
    private val renderFail by BooleanSetting("Show when failed", true, desc = "Shows the box even when the guess failed.").withDependency { render }
    private val failColor by ColorSetting("Fail Color", Colors.MINECRAFT_RED, desc = "Color of the box if guess failed.").withDependency { renderFail }
    private val renderStyle by SelectorSetting("Box Style", BoxStyle.FILLED_OUTLINE, desc = "Style of the box.").withDependency { render }
    private val fillOpacity by NumberSetting("Fill Opacity", 50, 0..100, 5, desc = "Opacity of the box fill.", unit = "%").withDependency { render && renderStyle != BoxStyle.OUTLINE }
    private val outlineOpacity by NumberSetting("Outline Opacity", 100, 0..100, 5, desc = "Opacity of the box outline.", unit = "%").withDependency { render && renderStyle != BoxStyle.FILLED }
    private val outlineThickness by NumberSetting("Outline Thickness", 3f, 0.5..10.0, 0.5, desc = "Width of the box outline.").withDependency { render && renderStyle != BoxStyle.FILLED }
    private val useServerPosition by BooleanSetting("Use Server Position", false, desc = "Casts from the last position and look sent to the server.").withDependency { render }
    private val fullBlock by BooleanSetting("Full Block", false, desc = "Renders the the 1x1x1 block instead of it's actual size.").withDependency { render }
    private val depth by BooleanSetting("Depth", false, desc = "Renders the box through walls.").withDependency { render }

    private val dropdown by DropdownSetting("Sounds", desc = "Shows settings for playing a custom sound when you etherwarp.")
    private val sounds by BooleanSetting("Custom Sounds", false, desc = "Plays the selected custom sound when you etherwarp.").withDependency { dropdown }
    private val soundSettings = createSoundSettings("Etherwarp Sound", "entity.experience_orb.pickup") { sounds && dropdown }

    private val zpewDropdown by DropdownSetting("Legit Zpew Dropdown", desc = "Shows the zero ping camera settings.", label = "Legit Zpew")
    val legitZpew by BooleanSetting("Legit Zpew", false, desc = "Zero ping camera: your view jumps to where a teleport lands as you click.").withDependency { zpewDropdown }
    val zpewEtherwarp by BooleanSetting("Etherwarp", true, desc = "Sneaking etherwarps.").withDependency { zpewDropdown && legitZpew }
    val zpewSound by BooleanSetting("0 Ping Etherwarp Sound", true, desc = "The etherwarp sound plays as your camera warps, not when the server's arrives.").withDependency { zpewDropdown && legitZpew && zpewEtherwarp }
    val zpewAotv by BooleanSetting("AOTE / AOTV", true, desc = "Aspect of the End and Aspect of the Void teleports (unsneaked).").withDependency { zpewDropdown && legitZpew }
    val zpewAotvSound by BooleanSetting("0 Ping AOTV Sound", true, desc = "The teleport sound plays as your camera teleports, not when the server's arrives.").withDependency { zpewDropdown && legitZpew && zpewAotv }
    val zpewHyperion by BooleanSetting("Hyperion", true, desc = "Wither Impact teleports: Hyperion, Valkyrie, Astraea, Scylla, Necron's Blade with all three scrolls.").withDependency { zpewDropdown && legitZpew }
    val zpewHypeSound by BooleanSetting("0 Ping Hyperion Sound", true, desc = "The teleport and Implosion sounds play as your camera teleports, not when the server's arrive.").withDependency { zpewDropdown && legitZpew && zpewHyperion }
    val zpewPuzzles by BooleanSetting("In Puzzles", false, desc = "Also in puzzle rooms, except ones you can't teleport in.").withDependency { zpewDropdown && legitZpew }
    val zpewDebug by BooleanSetting("Debug Log", false, desc = "Writes every click, teleport and camera jump to config/engineerclient/zpew-debug.log, for checking the predictions.").withDependency { zpewDropdown && legitZpew }
    val zpewCapture by BooleanSetting("Capture Misses", false, desc = "When a prediction is wrong (warps where the server refuses, lands elsewhere, or refuses where the server warps), saves the click and the blocks around it to config/engineerclient/zpew-misses.txt, to fix the law from.").withDependency { zpewDropdown && legitZpew }
    val zpewCaptureHits by BooleanSetting("Capture Hits", false, desc = "Also saves every right prediction to zpew-misses.txt.").withDependency { zpewDropdown && legitZpew && zpewCapture }

    private var etherPos: EtherPos? = null

    private var cachedMainHandItem: ItemStack? = null
    private var cachedEtherDistance: Double? = null
    private var cachedIsConduit = false

    init {
        onReceive<ClientboundSoundPacket> {
            if (!sounds || sound.value() != SoundEvents.ENDER_DRAGON_HURT || volume != 1f || pitch != 0.53968257f) return@onReceive
            playSoundSettings(soundSettings())
            it.cancel()
        }

        on<RenderExtractEvent> (EventPriority.LOW) {
            if (mc.gui.screen() != null || !render) return@on
            val player = mc.player ?: return@on

            val mainHandItem = player.mainHandItem
            if (cachedMainHandItem !== mainHandItem) {
                cachedMainHandItem = mainHandItem
                val data = mainHandItem.isEtherwarpItem()
                cachedEtherDistance = data?.let(::getEtherwarpDistance)
                cachedIsConduit = data?.itemId == "ETHERWARP_CONDUIT"
            }

            val distance = cachedEtherDistance ?: return@on
            if (!player.isShiftKeyDown && !cachedIsConduit) return@on

            val etherPos = (
                if (useServerPosition) castEther(Vec3(player.xLast, player.yLast, player.zLast), distance, false, true, player.lastSentInput.shift(), Vec3.directionFromRotation(player.xRotLast, player.yRotLast))
                else getEtherPos(player.position(), distance, etherWarp = true)
            ).also { etherPos = it }
            if (!etherPos.succeeded && !renderFail) return@on
            val pos = etherPos.pos ?: return@on

            val box = if (fullBlock) AABB(pos) else pos.getBlockBounds()?.move(pos) ?: AABB(pos)
            val boxColor = (if (etherPos.succeeded) color else failColor).withAlpha(1f)
            if (renderStyle != BoxStyle.OUTLINE) drawFilledBox(box.inflate(0.00005), boxColor.multiplyAlphaRgba(fillOpacity / 100f), depth)
            if (renderStyle != BoxStyle.FILLED) drawWireFrameBox(box, boxColor.multiplyAlphaRgba(outlineOpacity / 100f), outlineThickness, depth)
        }

        on<InputEvent> {
            if (key.type != InputConstants.Type.MOUSE || key.value != InputConstants.MOUSE_BUTTON_RIGHT || !LocationUtils.isCurrentArea(Island.SinglePlayer)) return@on
            if (cachedEtherDistance == null || (mc.player?.isShiftKeyDown == false && !cachedIsConduit)) return@on

            etherPos?.pos?.let {
                if (etherPos?.succeeded == false) return@on
                mc.player?.connection?.send(
                    ServerboundMovePlayerPacket.PosRot(
                        it.x + 0.5, it.y + 1.05, it.z + 0.5, mc.player?.yRot ?: 0f,
                        mc.player?.xRot ?: 0f, false, false
                    )
                )
                mc.player?.setPos(it.x + 0.5, it.y + 1.05, it.z + 0.5)
                mc.player?.setDeltaMovement(0.0, 0.0, 0.0)
                if (sounds) playSoundSettings(soundSettings())
                else playSoundAtPlayer(SoundEvents.ENDER_DRAGON_HURT, pitch = 0.53968257f)
            }
        }
    }

    data class EtherPos(val succeeded: Boolean, val pos: BlockPos?, val state: BlockState?) {

        companion object {
            val NONE = EtherPos(false, null, null)
        }
    }

    fun getEtherwarpDistance(data: CompoundTag): Double =
        57.0 + data.getInt("tuned_transmission").orElse(0)

    fun getEtherPos(
        position: Vec3?,
        distance: Double,
        returnEnd: Boolean = false,
        etherWarp: Boolean = false,
        sneaking: Boolean = mc.player?.isCrouching == true
    ): EtherPos = castEther(position, distance, returnEnd, etherWarp, sneaking, mc.player?.lookAngle ?: return EtherPos.NONE)

    private fun castEther(position: Vec3?, distance: Double, returnEnd: Boolean, etherWarp: Boolean, sneaking: Boolean, look: Vec3): EtherPos {
        val player = mc.player ?: return EtherPos.NONE
        if (position == null) return EtherPos.NONE
        val eye = if (player.pose == Pose.SWIMMING) 0.4 else if (sneaking) 1.27 else 1.62
        val fromX = position.x
        val fromY = position.y + eye
        val fromZ = position.z
        val toX = fromX + look.x * distance
        val toY = fromY + look.y * distance
        val toZ = fromZ + look.z * distance
        val hit = traverseVoxels(fromX, fromY, fromZ, toX, toY, toZ, etherWarp)
        return if (hit === EtherPos.NONE && returnEnd) EtherPos(true, BlockPos.containing(toX, toY, toZ), null) else hit
    }

    private fun traverseVoxels(fromX: Double, fromY: Double, fromZ: Double, toX: Double, toY: Double, toZ: Double, etherWarp: Boolean): EtherPos {
        val level = mc.level ?: return EtherPos.NONE
        val dx = toX - fromX
        val dy = toY - fromY
        val dz = toZ - fromZ

        var x = floor(fromX).toInt()
        var y = floor(fromY).toInt()
        var z = floor(fromZ).toInt()
        val stepX = sign(dx).toInt()
        val stepY = sign(dy).toInt()
        val stepZ = sign(dz).toInt()
        val spanX = abs(1.0 / dx)
        val spanY = abs(1.0 / dy)
        val spanZ = abs(1.0 / dz)
        var nextX = (if (stepX < 0) fromX - x else x + 1 - fromX) * spanX
        var nextY = (if (stepY < 0) fromY - y else y + 1 - fromY) * spanY
        var nextZ = (if (stepZ < 0) fromZ - z else z + 1 - fromZ) * spanZ
        var left = abs(floor(toX).toInt() - x) + abs(floor(toY).toInt() - y) + abs(floor(toZ).toInt() - z)

        val cursor = BlockPos.MutableBlockPos()
        while (true) {
            val chunk = level.chunkSource.getChunkNow(x shr 4, z shr 4) ?: return EtherPos.NONE
            val state = chunk.getBlockState(cursor.set(x, y, z))
            val flags = flagsOf(state)
            val stops = if (etherWarp) flags and PASSABLE == 0 else flags and AIR == 0
            if (stops) {
                val hit = cursor.immutable()
                if (flags and PASSABLE != 0) return EtherPos(false, hit, state)
                val feet = y + (flags ushr STAND_OFFSET_SHIFT)
                val clear = !obstructs(chunk, cursor.set(x, feet, z)) && !obstructs(chunk, cursor.set(x, feet + 1, z))
                return EtherPos(clear, hit, state)
            }
            if (left-- == 0) return if (flags and AIR != 0) EtherPos.NONE else EtherPos(false, cursor.immutable(), state)

            if (nextX <= nextY && nextX <= nextZ) {
                nextX += spanX
                x += stepX
            } else if (nextY <= nextZ) {
                nextY += spanY
                y += stepY
            } else {
                nextZ += spanZ
                z += stepZ
            }
        }
    }

    private fun obstructs(chunk: LevelChunk, pos: BlockPos): Boolean = flagsOf(chunk.getBlockState(pos)) and OBSTRUCTS != 0
    private fun flagsOf(state: BlockState): Int = blockFlags[Block.getId(state)]

    private const val STAND_OFFSET_SHIFT = 3
    private const val OBSTRUCTS = 4
    private const val PASSABLE = 2
    private const val AIR = 1

    private fun computeFlags(state: BlockState): Int {
        val block = state.block
        val collision = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)

        val noHitbox = block is SignBlock || block is AbstractBannerBlock || block is TripWireHookBlock || block is BasePressurePlateBlock
        val passable = !noHitbox && when (block) {
            is ButtonBlock, is SkullBlock, is WallSkullBlock, is LadderBlock,
            is BubbleColumnBlock, is FlowerPotBlock, is PistonHeadBlock, is LeverBlock,
            is NetherWartBlock, is ComparatorBlock, is RedstoneTorchBlock, is RepeaterBlock -> true
            else -> collision.isEmpty
        }
        val blocksFeet = when (block) {
            is SkullBlock, is WallSkullBlock, is ComparatorBlock, is RepeaterBlock, is FlowerPotBlock, is LadderBlock -> true
            else -> false
        }
        val standOffset = max(1, ceil(collision.max(Direction.Axis.Y)).toInt())

        return (standOffset shl STAND_OFFSET_SHIFT) or
            (if (state.isAir) AIR else 0) or
            (if (passable) PASSABLE else 0) or
            (if ((!passable && !noHitbox) || blocksFeet) OBSTRUCTS else 0)
    }

    private val blockFlags = IntArray(Block.BLOCK_STATE_REGISTRY.size()) { id ->
        Block.BLOCK_STATE_REGISTRY.byId(id)?.let(::computeFlags) ?: 0
    }
}