package com.engineerclient.p3sim.clear.secrets

import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.SimItems
import com.engineerclient.p3sim.clear.blood.HeadTextures
import com.engineerclient.p3sim.pack.rooms.SkullTexture
import com.engineerclient.p3sim.run.RunBlocks
import com.google.common.collect.ImmutableMultimap
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.alchemy.PotionContents
import net.minecraft.world.item.alchemy.Potions
import net.minecraft.world.item.component.ResolvableProfile
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.SkullBlock
import net.minecraft.world.level.block.WallSkullBlock
import net.minecraft.world.level.block.entity.SkullBlockEntity
import net.minecraft.world.level.block.state.BlockState
import java.util.UUID

/**
 * The Minecraft side of the secrets: the blocks, entities and items a secret is made of. Everything here runs on the sim server
 * thread, in a loaded chunk near a player. The decisions (what, when, how many) are made elsewhere, in the pure classes of this
 * package; this file only touches the world. Not unit-tested (it needs a running world): see the in-game steps in the report.
 */
object SecretWorld {
    /** The block state a chest site stands as: facing already rotated into the world. */
    fun chestState(site: SecretSite): BlockState {
        val face = site.facing?.let { Direction.byName(it) }?.takeIf { it.axis.isHorizontal } ?: Direction.NORTH
        return Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, face)
    }

    /** The trapped chest (the Mimic's) of a site: a trapped chest, which never opens, facing the same way. */
    fun trappedChestState(site: SecretSite): BlockState =
        Blocks.TRAPPED_CHEST.defaultBlockState().setValue(ChestBlock.FACING, chestState(site).getValue(ChestBlock.FACING))

    fun isChestBlock(state: BlockState?) = state != null && (state.`is`(Blocks.CHEST) || state.`is`(Blocks.TRAPPED_CHEST))
    fun isSkullBlock(state: BlockState?) = state != null && state.block is SkullBlock

    /** Puts the site's chest in the world if there is not one already (a baked chest is left alone). True when something was written. */
    fun ensureChest(site: SecretSite): Boolean {
        if (isChestBlock(RunBlocks.get(site.x, site.y, site.z))) return false
        return RunBlocks.set(site.x, site.y, site.z, chestState(site))
    }

    /**
     * Puts a player head with the given skull texture at the site (floor head, rotated with the room), and gives its block entity the
     * profile. Both Odin's profile id and the texture value are set (the textures table of the rooms pack carries both). True if a
     * block was written.
     */
    fun ensureSkull(site: SecretSite, tex: SkullTexture?): Boolean = ensureSkull(site.x, site.y, site.z, site.quarterTurns, tex)

    fun ensureSkull(x: Int, y: Int, z: Int, quarterTurns: Int, tex: SkullTexture?): Boolean {
        val existing = RunBlocks.get(x, y, z)
        var wrote = false
        if (!isSkullBlock(existing)) {
            val rot = Math.floorMod(quarterTurns * 4, 16)
            wrote = RunBlocks.set(x, y, z, Blocks.PLAYER_HEAD.defaultBlockState().setValue(SkullBlock.ROTATION, rot))
        }
        if (tex != null) giveProfile(BlockPos(x, y, z), tex)
        return wrote
    }

    /** The head a used Redstone Key leaves on its socket: on top of the block (a floor head) or against its side (a wall head). */
    fun placeHead(head: HeadCell, tex: SkullTexture?): Boolean {
        val state = if (head.wallFacing == null) Blocks.PLAYER_HEAD.defaultBlockState().setValue(SkullBlock.ROTATION, head.rotation)
        else Blocks.PLAYER_WALL_HEAD.defaultBlockState().setValue(WallSkullBlock.FACING, Direction.byName(head.wallFacing)?.takeIf { it.axis.isHorizontal } ?: Direction.NORTH)
        val wrote = RunBlocks.set(head.pos.x, head.pos.y, head.pos.z, state)
        if (tex != null) giveProfile(BlockPos(head.pos.x, head.pos.y, head.pos.z), tex)
        return wrote
    }

    private fun giveProfile(pos: BlockPos, tex: SkullTexture) {
        val level = RunBlocks.level ?: return
        val be = level.getBlockEntity(pos) as? SkullBlockEntity ?: return
        val id = tex.profileId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: UUID.nameUUIDFromBytes(tex.id.toByteArray())
        val props = tex.value?.let { PropertyMap(ImmutableMultimap.of("textures", Property("textures", it))) } ?: PropertyMap(ImmutableMultimap.of())
        val stack = ItemStack(Items.PLAYER_HEAD)
        stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(GameProfile(id, "", props)))
        be.applyComponentsFromItemStack(stack)
        be.setChanged()
        val state = level.getBlockState(pos)
        level.sendBlockUpdated(pos, state, state, 3)
    }

    /**
     * The rooms pack's texture roles of the item heads the sim makes. `essence_skin` is the skin every real wither essence skull wears
     * (the pack's `essence` entry only carries the profile id Odin matches); the others are the Revive Stone and Training Weights heads.
     * A pack without a role gives a plain head.
     */
    const val ESSENCE_SKIN_ROLE = "essence_skin"
    private const val REVIVE_ROLE = "revive_stone"
    private const val WEIGHTS_ROLE = "training_weights"

    /**
     * The essence skull's texture: the pack's `essence` entry keeps its profile id (the one Odin matches), the skin is [skin], the pack's
     * `essence_skin` entry. Without that entry the skull is a plain head that Odin still matches.
     */
    fun essenceTexture(packEntry: SkullTexture?, skin: SkullTexture?): SkullTexture =
        SkullTexture(skin?.id ?: packEntry?.id ?: "essence", skin?.let { it.value ?: HeadTextures.valueFor(it.id) }, packEntry?.profileId, "essence")

    /** The texture of a role (`essence`, `redstoneKey`) in the rooms pack's table, or null. */
    fun textureFor(textures: List<SkullTexture>, role: String): SkullTexture? = textures.firstOrNull { it.role == role }

    fun remove(x: Int, y: Int, z: Int) { RunBlocks.set(x, y, z, Blocks.AIR.defaultBlockState()) }

    /** A lever block flipped on (the cells it opens are handled by the caller). */
    fun powerLever(x: Int, y: Int, z: Int) {
        val st = RunBlocks.get(x, y, z) ?: return
        if (st.block is LeverBlock && !st.getValue(LeverBlock.POWERED)) RunBlocks.set(x, y, z, st.setValue(LeverBlock.POWERED, true))
    }

    /** Tells one player the block's real state again (the client flipped a lever on its own when it clicked; the sim decides). */
    fun resend(p: ServerPlayer, x: Int, y: Int, z: Int) {
        val level = RunBlocks.level ?: return
        p.connection.send(net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket(level, BlockPos(x, y, z)))
    }

    fun isLever(x: Int, y: Int, z: Int) = RunBlocks.get(x, y, z)?.block is LeverBlock

    // ---- entities --------------------------------------------------------------------------------------------------------------

    /**
     * A bat for a bat secret, as Hypixel's: a vanilla bat put at the centre of its block, with 100 health and a permanent fire resistance
     * (the orange swirl on it), so lava or fire never takes it; the sim kills it on any hit it is told of. Silent (the sim plays Odin's quiet
     * bat sounds itself) and persistent. Its own AI runs, as on Hypixel (Dungeon Recorder, 297 secret bats: 87 percent hang still at the
     * block's y + 0.1, where a resting bat clings under a solid block, and fly off once a player is near, the first move a p25 of 4 blocks
     * from the recorder, vanilla's 4-block wake; flights are vanilla's darting legs at about 0.1 to 0.16 blocks a tick): it hangs when
     * the block above is solid and flies about when woken or when it has nothing to cling to.
     */
    fun spawnBat(site: SecretSite): UUID? {
        val level = RunBlocks.level ?: return null
        val bat = EntityTypes.BAT.create(level, EntitySpawnReason.EVENT) ?: return null
        bat.setPos(site.x + 0.5, site.y + 0.5, site.z + 0.5)
        bat.isSilent = true
        bat.setPersistenceRequired()
        bat.isResting = level.getBlockState(net.minecraft.core.BlockPos(site.x, site.y + 1, site.z)).isRedstoneConductor(level, net.minecraft.core.BlockPos(site.x, site.y + 1, site.z))
        bat.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)?.baseValue = BAT_HEALTH.toDouble()
        bat.health = BAT_HEALTH
        bat.addEffect(MobEffectInstance(MobEffects.FIRE_RESISTANCE, MobEffectInstance.INFINITE_DURATION, 0, false, true))
        Sim.spawn(bat)
        return bat.uuid
    }

    /** A secret bat's health: any hit that reaches the sim kills it, so this only has to match what the client is told. */
    const val BAT_HEALTH = 100f

    /** A bat's death: it falls and the body stays 20 ticks (vanilla's death). */
    fun killBat(bat: Bat) {
        val level = RunBlocks.level ?: return
        if (bat.isAlive) bat.kill(level)
    }

    /** A floor item for an item secret. The name is the stack's name, which Odin's item-secret detection reads. */
    fun spawnItem(site: SecretSite, item: LootItem): UUID? {
        val level = RunBlocks.level ?: return null
        val e = ItemEntity(level, site.x + 0.5, site.y + 0.25, site.z + 0.5, stack(item, item.floorCount))
        e.setDeltaMovement(0.0, 0.0, 0.0)
        e.setUnlimitedLifetime()
        e.setPickUpDelay(32767) // vanilla never picks it up; the sim does, inside its own box (see [pickUp])
        Sim.spawn(e)
        return e.uuid
    }

    /** The pickup itself, once a player's box reaches the item: the vanilla touch (inventory, the take packet Odin reads, removal). */
    fun pickUp(e: ItemEntity, p: ServerPlayer): Boolean {
        e.setNoPickUpDelay()
        e.playerTouch(p)
        return e.isRemoved || e.item.isEmpty
    }

    /** A teammate takes the item: everyone sees it fly to them (when the teammate has a body in the world), then it is gone. */
    fun takeBy(e: ItemEntity, collector: net.minecraft.world.entity.Entity?) {
        if (collector != null) {
            val pkt = net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket(e.id, collector.id, e.item.count)
            for (p in Sim.players()) p.connection.send(pkt)
        }
        e.discard()
    }

    fun entity(id: UUID): net.minecraft.world.entity.Entity? = RunBlocks.level?.getEntity(id)

    // ---- items -----------------------------------------------------------------------------------------------------------------

    /** How an item looks: the base item, Hypixel's item model, whether it glints or is unbreakable, and the pack texture role of a head's skin. */
    private class Look(val base: net.minecraft.world.item.Item, val model: String?, val glint: Boolean = false, val unbreakable: Boolean = false, val skinRole: String? = null)

    private fun look(item: LootItem): Look = when (item) {
        LootItem.SUPERBOOM -> Look(Items.PAPER, "uncategorized/superboom_tnt")
        LootItem.REVIVE_STONE -> Look(Items.PLAYER_HEAD, null, skinRole = REVIVE_ROLE)
        LootItem.SPIRIT_LEAP -> Look(Items.ENDER_PEARL, "island_relevant/dungeons/spirit_leap", glint = true)
        LootItem.HEALING_POTION -> Look(Items.SPLASH_POTION, null)
        LootItem.DECOY -> Look(Items.POLAR_BEAR_SPAWN_EGG, "uncategorized/decoy")
        LootItem.INFLATABLE_JERRY -> Look(Items.POLAR_BEAR_SPAWN_EGG, "uncategorized/inflatable_jerry")
        LootItem.TRAINING_WEIGHTS -> Look(Items.PLAYER_HEAD, null, skinRole = WEIGHTS_ROLE)
        LootItem.TRAP -> Look(Items.HEAVY_WEIGHTED_PRESSURE_PLATE, "uncategorized/trap")
        LootItem.DEFUSE_KIT -> Look(Items.SHEARS, null, glint = true, unbreakable = true)
        LootItem.CANDYCOMB -> Look(Items.PAPER, "island_relevant/foraging_2/salts/candycomb")
    }

    /**
     * The item as a player holds it or finds it on the floor: Hypixel's components (name in its colour, tooltip style by rarity, item
     * model, glint, hidden lists, a head's skin from the rooms pack's [textures], the potion's contents) and a custom-data id (what the
     * other modules match).
     */
    fun stack(item: LootItem, count: Int, textures: List<SkullTexture> = emptyList()): ItemStack {
        if (item == LootItem.SUPERBOOM) return SimItems.SUPERBOOM.copyWithCount(count)
        val l = look(item)
        val s = ItemStack(l.base, count)
        val colour = net.minecraft.ChatFormatting.getByCode(item.colour[1])
        s.set(DataComponents.CUSTOM_NAME, Component.literal(item.displayName).withStyle { st -> st.withItalic(false).let { if (colour != null) it.withColor(colour) else it } })
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().also {
            it.putString("id", item.id); it.putBoolean("p3sim", true)
            if (item == LootItem.HEALING_POTION) { it.putInt("splash", 1); it.putInt("potion_level", 8) }
        }))
        if (l.glint) s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        if (l.unbreakable) s.set(DataComponents.UNBREAKABLE, net.minecraft.util.Unit.INSTANCE)
        if (l.skinRole != null) {
            HeadTextures.resolve(l.skinRole, textures)?.let { tex ->
                val profile = GameProfile(tex.profileId, "", PropertyMap(ImmutableMultimap.of("textures", Property("textures", tex.value))))
                s.set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile))
            }
        }
        if (item == LootItem.HEALING_POTION) {
            s.set(DataComponents.POTION_CONTENTS, PotionContents(java.util.Optional.of(Potions.HEALING), java.util.Optional.empty(), listOf(MobEffectInstance(MobEffects.INSTANT_HEALTH, 20, 7)), java.util.Optional.empty()))
        }
        return SimItems.hy(s, l.model, item.style, unbreakable = l.unbreakable)
    }

    /** Hands a stack to a player; whatever does not fit drops at their feet. */
    fun give(p: ServerPlayer, stack: ItemStack) {
        if (!p.inventory.add(stack) && !stack.isEmpty) Sim.spawn(ItemEntity(p.level(), p.x, p.y + 0.5, p.z, stack))
    }
}
