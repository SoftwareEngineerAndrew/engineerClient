package com.engineerclient.p3sim.clear.state

import com.engineerclient.p3sim.CarriedBlock
import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.SimStand
import com.engineerclient.p3sim.clear.blood.HeadTextures
import com.engineerclient.p3sim.pack.DoorModel
import com.engineerclient.p3sim.run.KeyKind
import com.engineerclient.p3sim.run.PlacedDoor
import com.engineerclient.p3sim.run.RunBlocks
import com.engineerclient.p3sim.run.Runs
import com.engineerclient.p3sim.run.api.Audience
import com.engineerclient.p3sim.run.api.Services
import com.google.common.collect.ImmutableMultimap
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ResolvableProfile
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import java.util.UUID

/**
 * The real [StateEffects]: chat through the channel service, sounds through `Sim.sound`, door blocks through `RunBlocks`, key stands
 * as armor stands spawned through `Sim.spawn`. Server thread only.
 */
object McStateEffects : StateEffects {
    /** Per key stand: the named stand ("Wither Key" over it) and the model stand wearing the key's head. */
    private val stands = HashMap<Int, List<ArmorStand>>()

    override fun chat(text: String, to: UUID?) {
        Services.channels.chat(text, if (to == null) Audience.Everyone else Audience.Only(to))
    }

    override fun sound(cue: Cue, to: UUID?) {
        val ev = BuiltInRegistries.SOUND_EVENT.getValue(Identifier.parse(cue.id)) ?: return
        val source = when (cue.source) {
            CueSource.MASTER -> SoundSource.MASTER; CueSource.BLOCKS -> SoundSource.BLOCKS
            CueSource.HOSTILE -> SoundSource.HOSTILE; CueSource.PLAYERS -> SoundSource.PLAYERS
        }
        for (p in Sim.players()) if (to == null || p.uuid == to) Sim.asPlayer(p) { Sim.sound(ev, cue.volume, cue.pitch, source = source) }
    }

    private fun blockOf(door: PlacedDoor, blocks: BodyBlocks): BlockState = when (blocks) {
        BodyBlocks.BARRIER -> Blocks.BARRIER.defaultBlockState()
        BodyBlocks.AIR -> Blocks.AIR.defaultBlockState()
        BodyBlocks.CLOSED -> DoorModel.bodyBlock(door.kind)
            ?.let { BuiltInRegistries.BLOCK.getValue(Identifier.parse(it)).defaultBlockState() } ?: Blocks.AIR.defaultBlockState()
    }

    /** Door blocks whose chunk was not loaded when they were set (a door several rooms from every player): written when it loads. Keyed by block position. */
    private val pendingBlocks = LinkedHashMap<Long, Pair<BlockPos, BlockState>>()

    override fun setBody(door: PlacedDoor, blocks: BodyBlocks, onlyFirst: Boolean) {
        val state = blockOf(door, blocks)
        val cells = door.bodyCells()
        for ((i, c) in cells.withIndex()) {
            if (onlyFirst && i > 0) break
            val pos = BlockPos(c.x, c.y, c.z)
            // Setting a block in a chunk that is not loaded makes the server load and generate it on the spot (tens of ms): wait for a player to load it.
            if (RunBlocks.isLoaded(c.x, c.z)) { pendingBlocks.remove(pos.asLong()); RunBlocks.set(c.x, c.y, c.z, state) }
            else pendingBlocks[pos.asLong()] = pos to state
        }
    }

    /** One opening door's animation: a bat and the falling block it carries for each of the door's blocks. */
    private class DoorDebris(val bats: List<Bat>, val blocks: List<CarriedBlock>, val baseY: List<Double>, val x: List<Double>, val z: List<Double>) {
        var age = 0
    }

    private val debris = ArrayList<DoorDebris>()

    /**
     * What a door that opens shows: in the open tick an invisible bat and a falling block of the door's block at every one of its
     * cells (y of the cell + 0.34375); a tick later each block rides its bat; from tick 7 the bats sink about 4 blocks, so the door
     * seems to drop into the floor; at tick 23 all of them are gone. A door whose chunk is not loaded shows nothing.
     */
    override fun spawnDoorDebris(door: PlacedDoor) {
        val block = blockOf(door, BodyBlocks.CLOSED)
        if (block.isAir) return
        val bats = ArrayList<Bat>(); val blocks = ArrayList<CarriedBlock>(); val ys = ArrayList<Double>(); val xs = ArrayList<Double>(); val zs = ArrayList<Double>()
        for (c in door.bodyCells()) {
            if (!RunBlocks.isLoaded(c.x, c.z)) continue
            val x = c.x + 0.5; val z = c.z + 0.5; val y = c.y + StateRules.DEBRIS_DY
            val b = Bat(EntityType.BAT, Sim.level)
            b.isSilent = true; b.isInvulnerable = true; b.isInvisible = true
            b.addEffect(MobEffectInstance(MobEffects.INVISIBILITY, -1, 0, false, false))
            b.setNoAi(true); b.setNoGravity(true)
            b.snapTo(x, y, z, 0f, 0f)
            val f = CarriedBlock(Sim.level, block)
            f.snapTo(x, y, z, 0f, 0f)
            Sim.spawn(b); Sim.spawn(f)
            bats += b; blocks += f; ys += y; xs += x; zs += z
        }
        if (bats.isNotEmpty()) debris += DoorDebris(bats, blocks, ys, xs, zs)
    }

    private fun tickDebris() {
        val it = debris.iterator()
        while (it.hasNext()) {
            val d = it.next()
            d.age++
            when {
                d.age >= StateRules.DEBRIS_REMOVE -> { d.blocks.forEach { b -> b.discard() }; d.bats.forEach { b -> b.discard() }; it.remove() }
                d.age == StateRules.DEBRIS_RIDE_DELAY -> for (i in d.bats.indices) if (!d.bats[i].isRemoved && !d.blocks[i].isRemoved) d.blocks[i].startRiding(d.bats[i], true, false)
                d.age >= StateRules.DEBRIS_SINK_START && d.age <= StateRules.DEBRIS_LAST_DROP_TICK -> {
                    val drop = StateRules.DEBRIS_FIRST_DROP + StateRules.DEBRIS_DROP_PER_TICK * (d.age - StateRules.DEBRIS_SINK_START).coerceAtLeast(0)
                    for (i in d.bats.indices) if (!d.bats[i].isRemoved) d.bats[i].snapTo(d.x[i], d.baseY[i] - drop, d.z[i], 0f, 0f)
                }
            }
        }
    }

    /** A key stand to keep in the world: where it is and what it is, so it can be made again once its chunk is loaded (or came back from an unload). */
    private class StandSpec(val kind: KeyKind, val x: Double, val z: Double, var y: Double) {
        /** Ticks since the model stand was made: its spin and bob run on this. */
        var age = 0
    }

    /** Name stands of picked-up keys, discarded after [StateRules.NAME_STAND_LINGER] more ticks. */
    private class Lingering(val stand: ArmorStand, var left: Int)

    private val lingering = ArrayList<Lingering>()

    private val specs = HashMap<Int, StandSpec>()
    private var ticks = 0

    override fun tick() {
        tickDebris()
        tickStands()
        if (++ticks % RECHECK_TICKS != 0) return
        if (pendingBlocks.isNotEmpty()) {
            val it = pendingBlocks.values.iterator()
            while (it.hasNext()) {
                val (pos, state) = it.next()
                if (RunBlocks.isLoaded(pos.x, pos.z)) { RunBlocks.set(pos.x, pos.y, pos.z, state); it.remove() }
            }
        }
        for ((id, spec) in specs.entries.toList()) {
            val live = stands[id]
            if (live != null && live.none { it.isRemoved }) continue
            if (!RunBlocks.isLoaded(Math.floor(spec.x).toInt(), Math.floor(spec.z).toInt())) continue
            live?.forEach { it.discard() }
            materialise(id, spec)
        }
    }

    /** The model stand spins 2 degrees a tick and bobs up and down; the lingering name stands of picked-up keys expire. */
    private fun tickStands() {
        for ((id, spec) in specs) {
            val model = stands[id]?.getOrNull(1) ?: continue
            if (model.isRemoved) continue
            spec.age++
            model.snapTo(spec.x, spec.y - StateRules.MODEL_STAND_BELOW + bob(spec.age), spec.z, model.yRot + StateRules.STAND_SPIN_DEG, 0f)
        }
        val it = lingering.iterator()
        while (it.hasNext()) { val l = it.next(); if (--l.left <= 0) { l.stand.discard(); it.remove() } }
    }

    override fun reset() { pendingBlocks.clear(); clearDebris() }

    private fun clearDebris() {
        for (d in debris) { d.blocks.forEach { it.discard() }; d.bats.forEach { it.discard() } }
        debris.clear()
    }

    /** The y of the first solid block under ([x], [z]) near [guess], less the stand offset; [guess] when the floor cannot be found. */
    private fun floorY(x: Double, z: Double, guess: Double): Double {
        val bx = Math.floor(x).toInt(); val bz = Math.floor(z).toInt()
        val from = Math.floor(guess).toInt() + 3
        for (y in from downTo from - 14) {
            val s = RunBlocks.get(bx, y, bz) ?: return guess
            if (!s.isAir && !s.getCollisionShape(RunBlocks.level!!, BlockPos(bx, y, bz)).isEmpty) return y + 1 - StateRules.STAND_Y_BELOW_SURFACE
        }
        return guess
    }

    override fun spawnStand(id: Int, kind: KeyKind, x: Double, z: Double, floorGuessY: Double): Double {
        val spec = StandSpec(kind, x, z, floorGuessY)
        specs[id] = spec
        // A stand in a chunk nobody has loaded waits (tick makes it): reading the floor there would load the chunk on the server thread.
        if (!RunBlocks.isLoaded(Math.floor(x).toInt(), Math.floor(z).toInt())) return floorGuessY
        return materialise(id, spec)
    }

    private fun materialise(id: Int, spec: StandSpec): Double {
        val kind = spec.kind
        // Hypixel's key stands are block-centred (.5, .5) in 78 % of the recordings and on block corners in the rest; the centre is used.
        val x = Math.floor(spec.x) + 0.5; val z = Math.floor(spec.z) + 0.5
        val y = floorY(x, z, spec.y)
        spec.y = y; spec.age = 0
        fun stand(name: String?, head: ItemStack?, at: Double): ArmorStand {
            val s = SimStand(Sim.level)
            s.snapTo(x, at, z, 0f, 0f)
            // Not a marker: the real stands have a 0.5 x 1.975 box.
            s.isInvisible = true; s.isInvulnerable = true; s.isSilent = true
            if (name != null) { s.setCustomName(Sim.legacy(name)); s.isCustomNameVisible = true }
            if (head != null) s.setItemSlot(EquipmentSlot.HEAD, head)
            return Sim.spawn(s)
        }
        val named = if (kind == KeyKind.WITHER) "§8Wither Key" else "§cBlood Key"
        // The key's model stand carries no name: it wears the key's player-head skin, 0.25 under the name stand.
        stands[id] = listOf(stand(named, null, y), stand(null, keyHead(kind), y - StateRules.MODEL_STAND_BELOW))
        return y
    }

    private fun keyHead(kind: KeyKind): ItemStack {
        val tex = HeadTextures.resolve(if (kind == KeyKind.WITHER) WITHER_KEY_ROLE else BLOOD_KEY_ROLE, Runs.current?.packs?.rooms?.textures.orEmpty())
        val stack = ItemStack(Items.PLAYER_HEAD)
        if (tex != null) {
            val gp = GameProfile(tex.profileId, "key", PropertyMap(ImmutableMultimap.of("textures", Property("textures", tex.value))))
            stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(gp))
        }
        return stack
    }

    /** A pickup takes the spinning model away at once; the name stand stays two more ticks. */
    override fun removeStand(id: Int) {
        specs.remove(id)
        val l = stands.remove(id) ?: return
        l.getOrNull(1)?.discard()
        l.getOrNull(0)?.let { lingering += Lingering(it, StateRules.NAME_STAND_LINGER) }
    }

    override fun clearStands() {
        specs.clear(); stands.values.forEach { l -> l.forEach { it.discard() } }; stands.clear()
        lingering.forEach { it.stand.discard() }; lingering.clear()
        clearDebris()
    }

    /** The bob of the model stand [age] ticks after it was made: a triangle wave of 0.01 a tick from 0.04 to 0.22 and back. */
    internal fun bob(age: Int): Double {
        val half = StateRules.STAND_BOB_HALF_PERIOD
        val p = age % (2 * half)
        return StateRules.STAND_BOB_MIN + StateRules.STAND_BOB_STEP * (if (p <= half) p else 2 * half - p)
    }

    /** The rooms pack's texture roles of the two key skins. A pack without them gives a plain head. */
    private const val BLOOD_KEY_ROLE = "blood_key"
    private const val WITHER_KEY_ROLE = "wither_key"

    /** Ticks between looks at what is waiting for a chunk (a second). */
    private const val RECHECK_TICKS = 20
}
