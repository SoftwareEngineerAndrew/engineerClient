package com.engineerclient.p3sim.clear.layout

import com.engineerclient.EngineerClient
import com.engineerclient.p3sim.Arena
import com.engineerclient.p3sim.pack.rooms.RoomBlockEntity
import com.engineerclient.p3sim.pack.rooms.SkullTexture
import com.google.common.collect.ImmutableMultimap
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponentMap
import net.minecraft.core.component.DataComponentPatch
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.component.ResolvableProfile
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignText
import net.minecraft.world.level.block.entity.SkullBlockEntity
import net.minecraft.world.level.block.state.BlockState
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Block states of the room pack as Minecraft states, parsed once and shared (the chunk generator's workers read this). */
object BlockStates {
    private val parsed = ConcurrentHashMap<String, BlockState>()

    fun parse(state: String): BlockState = parsed.computeIfAbsent(state) { Arena.parse(it) }

    /** [state] turned [quarterTurns] clockwise (`BlockState.rotate`, FORMAT-rooms 3.3). */
    fun rotated(state: String, quarterTurns: Int): BlockState {
        val s = parse(state)
        return if (quarterTurns.mod(4) == 0) s else s.rotate(rotation(quarterTurns))
    }

    fun rotation(quarterTurns: Int): Rotation = when (quarterTurns.mod(4)) {
        0 -> Rotation.NONE
        1 -> Rotation.CLOCKWISE_90
        2 -> Rotation.CLOCKWISE_180
        else -> Rotation.COUNTERCLOCKWISE_90
    }
}

/**
 * Makes the block entities the rooms need: player heads with their textures (the wither essence and redstone key heads Odin and the
 * secrets look for, and the decoration), signs with their text, and an empty one for every other kind of block that has one (chests,
 * beds, banners, dispensers...). Without them a chest or a bed written straight into a chunk section would have nothing to render.
 * Items, banner patterns and chest loot are not in the room pack; the secrets module fills what a secret holds.
 */
class BlockEntityMaker(textures: List<SkullTexture>) {
    private val profiles: List<ResolvableProfile?> = textures.map { t ->
        if (t.value == null) return@map null
        try {
            // Odin matches the profile id, so the pack's one is kept; a head without one gets a stable id of its texture.
            val id = t.profileId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: UUID.nameUUIDFromBytes("ads-skull:${t.id}".toByteArray())
            // A name the network codec accepts (letters, digits, underscore, up to 16): the start of the texture id.
            val name = t.id.take(12).ifEmpty { "ads" }
            val map = PropertyMap(ImmutableMultimap.of("textures", Property("textures", t.value)))
            ResolvableProfile.createResolved(GameProfile(id, name, map))
        } catch (e: Exception) {
            EngineerClient.logger.warn("[ads] skull texture ${t.id} unusable: ${e.message}")
            null
        }
    }

    /** Whether a block of this state gets a block entity from the rooms (an entity block, not one the sim must never have running). */
    fun wants(state: BlockState): Boolean = state.block is EntityBlock && state.block !in NEVER

    /** Whether a block of this state is one whose entity the sim must never have: a world write that makes one by itself has to take it off again. */
    fun forbids(state: BlockState): Boolean = state.block is EntityBlock && state.block in NEVER

    /** A new block entity for [state] at [pos] with the pack record's data on it, or null if the block has none. */
    fun create(pos: BlockPos, state: BlockState, record: RoomBlockEntity?): BlockEntity? {
        val block = state.block as? EntityBlock ?: return null
        val be = block.newBlockEntity(pos, state) ?: return null
        apply(be, record)
        return be
    }

    /** Puts the record's texture or text on an existing block entity (a resync that put the block back). */
    fun apply(be: BlockEntity, record: RoomBlockEntity?) {
        if (record == null) return
        try {
            when (be) {
                is SkullBlockEntity -> record.tex?.let { profiles.getOrNull(it) }?.let { profile ->
                    // The head's components set directly (no ItemStack: not needed, and an item's own components are bound late).
                    be.applyComponents(DataComponentMap.builder().set(DataComponents.PROFILE, profile).build(), DataComponentPatch.EMPTY)
                }
                is SignBlockEntity -> record.text?.let { lines ->
                    val l = (0 until 4).map { Component.literal(lines.getOrNull(it) ?: "") }
                    try { be.setText(SignText(l.toTypedArray(), l.toTypedArray(), DyeColor.BLACK, false), true) }
                    // A sign still on a chunk being generated has no level yet: setText stores the text, then fails telling the level about it.
                    catch (e: NullPointerException) { if (be.level != null) throw e }
                }
            }
        } catch (e: Exception) {
            EngineerClient.logger.warn("[ads] block entity at ${be.blockPos} unusable: ${e.message}")
        }
    }

    companion object {
        /**
         * Blocks whose entity would DO something in the sim world (spawn mobs, run commands, pull items, fire) or that Hypixel's chunks do not
         * carry one for: a hopper block in a room is decoration there, so the sim sends none (229 per dungeon that the real server never sends).
         *
         * A dispenser is the same: the real server only marks it (the chunk's entity is `{id:"Trap"}`, nothing it could dispense) and runs the trap
         * itself, while a vanilla dispenser with an entity answers any power next to it, a tripwire hook included, with an empty "fail" click. Without an
         * entity vanilla finds nothing to dispense and stays silent. The traps module shoots arrows itself.
         */
        private val NEVER: Set<Block> = setOf(
            Blocks.SPAWNER, Blocks.TRIAL_SPAWNER, Blocks.VAULT, Blocks.COMMAND_BLOCK, Blocks.CHAIN_COMMAND_BLOCK,
            Blocks.REPEATING_COMMAND_BLOCK, Blocks.STRUCTURE_BLOCK, Blocks.JIGSAW, Blocks.HOPPER, Blocks.DISPENSER, Blocks.DROPPER,
        )
    }
}
