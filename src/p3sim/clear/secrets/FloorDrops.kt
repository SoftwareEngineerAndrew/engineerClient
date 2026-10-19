package com.engineerclient.p3sim.clear.secrets

import com.engineerclient.EngineerClient
import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.SimStand
import com.engineerclient.p3sim.clear.blood.HeadTextures
import com.engineerclient.p3sim.clear.state.StateRules
import com.engineerclient.p3sim.run.Run
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import com.google.common.collect.ImmutableMultimap
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ResolvableProfile
import java.util.UUID
import kotlin.random.Random

/**
 * What a dying mob drops for the party lies on the floor until someone takes it, as Hypixel shows it: a name stand (`§9Superboom TNT`,
 * `§9Superboom TNT §8x2`, `§6Revive Stone`, `§dBlessing of Power`) 0.531 over the mob's feet and under it an invisible model stand, spinning
 * and bobbing like a key's, that wears the item (a Revive Stone's or a blessing's head; Superboom TNT holds its item). The "has obtained" line
 * and the item come with the pickup ([take]).
 *
 * Measured (ADR, 60 F7 clears): the stands sit at feet + 0.531 and their model 0.237 below; from the drop to the line a median 9 ticks for
 * Superboom TNT (p10 0, p90 107), 11 for a Revive Stone (0 / 62), 1 for a blessing (-1 / 63); drops of one kill fall half a block apart.
 * A bot's drop is taken after that measured wait; a human takes one by walking within [RADIUS].
 */
object FloorDrops {
    const val NAME_ABOVE_FEET = 0.531
    const val MODEL_BELOW_NAME = 0.237
    const val RADIUS = 2.5
    /** The blessing drop's head (one skin for all four blessings; recorded). */
    const val BLESSING_TEXTURE = "ab436edb7b805e133f7d3489d46ce0636f7e1cb37b67689faa12e698dbbd7c66"
    /** A drop nobody comes for is taken by the killer after this many ticks at most (the longest recorded waits are about 500). */
    const val LONGEST = 500

    private class Drop(val name: String, val model: ItemStack, val held: Boolean, val x: Double, val y: Double, val z: Double, val bornT: Int, val dueT: Int, val owner: UUID?, val take: (UUID?) -> Unit) {
        var stands: List<ArmorStand> = emptyList()
        var age = 0
    }

    private val drops = ArrayList<Drop>()

    /** Ticks a bot leaves its drop lying: the measured spread of drop-to-line gaps (p10 0, p50 9, p90 ~60), drawn piecewise. */
    fun botWait(u: Double): Int {
        val q = doubleArrayOf(0.0, 0.1, 0.5, 0.9, 1.0); val v = doubleArrayOf(0.0, 0.0, 9.0, 62.0, 120.0)
        for (i in 1 until q.size) if (u <= q[i]) return Math.round(v[i - 1] + (v[i] - v[i - 1]) * (u - q[i - 1]) / (q[i] - q[i - 1])).toInt()
        return 120
    }

    /** A head stack wearing texture [key] (a texture id or a rooms-pack role). */
    fun head(key: String, run: Run?): ItemStack {
        val stack = ItemStack(Items.PLAYER_HEAD)
        HeadTextures.resolve(key, run?.packs?.rooms?.textures.orEmpty())?.let { tex ->
            stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(GameProfile(tex.profileId, "drop", PropertyMap(ImmutableMultimap.of("textures", Property("textures", tex.value))))))
        }
        return stack
    }

    /**
     * Drops [name] at ([x], [feetY], [z]) for [owner] (the killer); [take] runs once with whoever picks it up (null when the run ends first is
     * never called). [slot] spreads the drops of one kill half a block apart.
     */
    fun drop(run: Run, name: String, model: ItemStack, held: Boolean, x: Double, feetY: Double, z: Double, slot: Int, owner: UUID?, rnd: Random, take: (UUID?) -> Unit) {
        val bot = owner?.let { run.state.players[it]?.isBot } == true
        val wait = if (bot) botWait(rnd.nextDouble()) else LONGEST
        val d = Drop(name, model, held, Math.floor(x) + 0.5 + 0.5 * slot, feetY + NAME_ABOVE_FEET, Math.floor(z) + 0.5 + 0.5 * slot, run.t, run.t + wait, owner, take)
        EngineerClient.safely("ads floor drop") { d.stands = build(d) }
        drops += d
    }

    private fun build(d: Drop): List<ArmorStand> {
        fun stand(y: Double, name: String?): ArmorStand {
            val s = SimStand(Sim.level)
            s.snapTo(d.x, y, d.z, 0f, 0f)
            s.isInvisible = true; s.isPermanentlyInvulnerable = true; s.isSilent = true
            if (name != null) { s.setCustomName(Sim.legacy(name)); s.isCustomNameVisible = true }
            return s
        }
        val named = Sim.spawn(stand(d.y, d.name))
        val model = stand(d.y - MODEL_BELOW_NAME, null)
        model.setItemSlot(if (d.held) EquipmentSlot.MAINHAND else EquipmentSlot.HEAD, d.model.copy())
        return listOf(named, Sim.spawn(model))
    }

    /** Spins the models, hands drops to whoever is close (or to a bot owner when its wait is up). Server thread. */
    fun tick(run: Run) {
        if (drops.isEmpty()) return
        val t = run.t
        val it = drops.iterator()
        while (it.hasNext()) {
            val d = it.next()
            d.age++
            d.stands.getOrNull(1)?.let { m -> if (!m.isRemoved) m.snapTo(d.x, d.y - MODEL_BELOW_NAME + bob(d.age), d.z, m.yRot + StateRules.STAND_SPIN_DEG, 0f) }
            val taker = run.state.players.values.filter { p -> !p.ghost && !p.isBot && Math.abs(p.y - (d.y - NAME_ABOVE_FEET)) < 4.0 && Math.hypot(p.x - d.x, p.z - d.z) <= RADIUS }
                .minByOrNull { p -> Math.hypot(p.x - d.x, p.z - d.z) }?.uuid
                ?: if (t >= d.dueT) (d.owner ?: run.state.players.keys.firstOrNull()) else null
            if (taker != null || t >= d.dueT) {
                d.stands.forEach { s -> s.discard() }
                it.remove()
                EngineerClient.safely("ads floor drop take") { d.take(taker) }
            }
        }
    }

    private fun bob(age: Int): Double {
        val half = StateRules.STAND_BOB_HALF_PERIOD
        val p = age % (2 * half)
        return StateRules.STAND_BOB_MIN + StateRules.STAND_BOB_STEP * (if (p <= half) p else 2 * half - p)
    }

    /** Takes every drop away without a pickup (the run ended or jumped). */
    fun clear() {
        for (d in drops) d.stands.forEach { it.discard() }
        drops.clear()
    }
}
