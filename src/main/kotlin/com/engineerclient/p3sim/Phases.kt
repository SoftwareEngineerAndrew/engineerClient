package com.engineerclient.p3sim

import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LightningBolt
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.min
import kotlin.random.Random
import net.minecraft.world.level.block.Blocks as B

/** A boss wither: no AI, moved by the phase; named as Hypixel names them. */
class BossWither(name: String, at: Vec3) {
    val e: WitherBoss = WitherBoss(EntityType.WITHER, Sim.level).also { w ->
        w.setNoAi(true); w.isSilent = true; w.isInvulnerable = true; w.setNoGravity(true)
        w.invulnerableTicks = 0
        w.setCustomName(Component.literal("§c§l$name"))
        w.isCustomNameVisible = true
        w.snapTo(at.x, at.y, at.z, 0f, 0f)
        Sim.spawn(w)
    }
    var pos: Vec3 = at
        private set

    fun moveTo(p: Vec3, faceTo: Vec3? = null) {
        pos = p
        val f = faceTo ?: p
        val yaw = if (faceTo != null) Math.toDegrees(Math.atan2(-(f.x - p.x), f.z - p.z)).toFloat() else e.yRot
        e.snapTo(p.x, p.y, p.z, yaw, 0f)
        e.yHeadRot = yaw; e.yBodyRot = yaw
    }

    /** One step toward [target] of at most [speed]; true when there. */
    fun step(target: Vec3, speed: Double, face: Vec3? = target): Boolean {
        val d = target.subtract(pos)
        val len = d.length()
        if (len <= speed) { moveTo(target, face); return true }
        moveTo(pos.add(d.scale(speed / len)), face)
        return false
    }

    fun remove() = e.discard()
}

/** The 3D-closest player to [p] (you, or a bot standing in for one). */
internal fun closest(p: Vec3): Vec3 {
    val me = Sim.player?.position()
    val bots = Party.bots().filter { it.entity != null }.map { it.pos }
    return (listOfNotNull(me) + bots).minByOrNull { it.distanceToSqr(p) } ?: p
}

/** Is the player looking at [at] within [degrees] and [range]. */
fun lookingAt(at: Vec3, degrees: Double, range: Double): Boolean {
    val p = Sim.player ?: return false
    val eye = p.eyePosition
    val to = at.subtract(eye)
    if (to.length() > range) return false
    val look = p.getViewVector(1f)
    val cos = look.dot(to.normalize())
    return cos >= Math.cos(Math.toRadians(degrees))
}

// ====================================================================== P1

/**
 * P1, Maxor (docs/mechanics/maxor.md). The intro on 62-tick lines; crystals on the top platforms
 * from +5 (click one to pick it up). Everything else runs on the 10-tick checks (t 6 mod 10):
 *  - the pylons open on the +166 check; a placement registers one tick after a check, and says
 *    "X/2" with X = 1 + this cycle's earlier placements already charged (28 ticks) (§4.2);
 *  - "The Energy Laser is charging up!" 28 after the second placement; the beacon goes in on the
 *    +206 check whatever the crystals do; the column (73, 222-224, 73) shows yellow / red / black;
 *  - a hit: a check, the laser charged, the beacon in, 200 ticks since the last hit, him within
 *    3.5 of (73.5, 73.5) with his feet at y 225+ (§4.4). The laser resets: top crystals back at
 *    hit + 41, placed ones gone at + 42, pylons open again at + 70.
 * A stun freezes him facing south until "⚠ Maxor is enraged! ⚠" (the party's damage: mostly 6-12
 * ticks with a party, 240 at most), and he moves 1-5 ticks after it. Taunt A comes 161 after the
 * beacon (never stunned) or 161-164 after an enrage; a hit inside it is silent, its stun line 62
 * after the taunt (§5.3). He dies 0-13 after the second stun line (at once after a silent hit),
 * "I'M TOO YOUNG TO DIE AGAIN!" at stun 2 + 82 if he's still there, despawn at kill + 80, Storm at
 * kill + 102.
 */
class P1Maxor : Fight.Phase("P1") {
    override val restart get() = Fight.Start.P1
    private lateinit var maxor: BossWither
    private val tops = ArrayList<EndCrystal>()
    private val onPylon = arrayOfNulls<EndCrystal>(2)
    private var carrying = 0
    private val placed = BooleanArray(2)
    /** Placement ticks this laser cycle (the "X/2" counter). */
    private val placeTimes = ArrayList<Int>()
    /** Placements clicked, taken on the next check (pylon to who). */
    private val pending = HashMap<Int, String>()
    private var open = false
    private var botCycleAt = 166
    private var chargeAt = -1
    private var beacon = false
    private var hits = 0
    private var lastHit = -1000
    private var stunned = false
    private var enrageAt = -1
    private var moveAt = 170
    private var stunLines = 0
    private var tauntAt = -1
    private var killAt = -1
    private val TOPS = listOf(Vec3(64.5, 238.375, 50.5), Vec3(82.5, 238.375, 50.5))
    private val PYLONS = listOf(Vec3(52.5, 224.375, 41.5), Vec3(94.5, 224.375, 41.5))
    private val BEAM = Vec3(73.5, 226.0, 73.5)
    private val TAUNTS_A = listOf(
        "YOUR WEAPONS CAN'T PIERCE THROUGH MY SHIELD!", "YOUR MOBILITY TRICKS DON'T WORK IN MY DOMAIN!",
        "I HOPE YOU LIKE EXPLOSIONS TOO!", "MY MINIONS WILL HAVE TO WIPE THE FLOOR AFTER I'M DONE WITH YOU ALL!",
    )

    override fun start() {
        val p = Sim.player ?: return
        val s = Spots.P1
        Sim.tp(p, s.x, s.y, s.z, s.yaw, s.pitch)
        SimItems.giveHotbar(p, p3 = false)
        Sim.boss("Maxor", "WELL! WELL! WELL! LOOK WHO'S HERE!")
        Blocks.play("p1strip")
        Party.standAt(listOf(Vec3(71.5, 221.0, 16.5), Vec3(75.5, 221.0, 16.5), Vec3(69.5, 221.0, 18.5), Vec3(77.5, 221.0, 18.5)))
    }

    override fun stop() { tops.forEach { it.discard() }; onPylon.forEach { it?.discard() }; if (::maxor.isInitialized) maxor.remove() }

    private fun check() = t % 10 == 6

    override fun tick() {
        when (t) {
            5 -> { maxor = BossWither("Maxor", Vec3(73.0, 226.0, 53.0)); TOPS.forEach { tops += spawnCrystal(it) } }
            62 -> Sim.boss("Maxor", "I'VE BEEN TOLD I COULD HAVE A BIT OF FUN WITH YOU.")
            124 -> Sim.boss("Maxor", "DON'T DISAPPOINT ME, I HAVEN'T HAD A GOOD FIGHT IN A WHILE.")
            166 -> {
                open = true
                Blocks.set(BlockPos(73, 221, 73), B.AIR.defaultBlockState()); Blocks.set(BlockPos(73, 222, 73), B.AIR.defaultBlockState())
                Sim.note("The pylons take crystals now: pick one up on top, right-click a pylon (or let the bots).")
            }
            206 -> { beacon = true; Blocks.set(BlockPos(73, 221, 73), B.BEACON.defaultBlockState()) }
        }
        if (!::maxor.isInitialized) return
        if (killAt >= 0) { killed(); return }
        // Placements: one tick after a check.
        if (open && t % 10 == 7) {
            pending.forEach { (i, by) -> place(i, by) }
            pending.clear()
            // The bots: the first check they can each cycle, both pylons unless you're carrying one for the other.
            if (P3Sim.bots && t >= botCycleAt + 1) {
                val free = (0..1).filter { !placed[it] }
                val mine = if (carrying > 0) 1 else 0
                free.drop(mine).forEach { place(it, "bot") }
                botCycleAt = Int.MAX_VALUE
            }
        }
        if (chargeAt >= 0 && t == chargeAt) Sim.chat("§aThe Energy Laser is charging up!")
        if (check()) column()
        // Taunt A (an ability: a hit inside it is silent).
        if (t == tauntAt) Sim.boss("Maxor", TAUNTS_A.random())
        if (stunned && t == enrageAt) { Sim.chat("§c⚠ Maxor is enraged! ⚠"); stunned = false; moveAt = t + 1 + Random.nextInt(5); tauntAt = t + 161 + Random.nextInt(4) }
        if (t == 205 && tauntAt < 0) tauntAt = 206 + 161
        // Moving: from 170, chasing the closest player; frozen facing south while stunned; the head on the closest before.
        val target = closest(maxor.pos).add(0.0, 1.0, 0.0)
        if (stunned) maxor.moveTo(maxor.pos, maxor.pos.add(0.0, 0.0, 1.0))
        else if (t >= moveAt) { val d = maxor.pos.distanceTo(target); if (d > 3.0) maxor.step(target, min(0.9, 0.24 + 0.02 * d)) else maxor.moveTo(maxor.pos, target) }
        else maxor.moveTo(maxor.pos, target)
        // The laser.
        if (check() && charged() && beacon && t - lastHit >= 200 && inBeam()) hit()
    }

    private fun charged() = chargeAt >= 0 && t >= chargeAt

    private fun inBeam() = maxor.pos.y >= 225.0 && Math.hypot(maxor.pos.x - BEAM.x, maxor.pos.z - BEAM.z) <= 3.5

    private fun inAbility() = tauntAt >= 0 && t >= tauntAt - 1 && t < tauntAt + 62

    private fun spawnCrystal(at: Vec3): EndCrystal {
        val c = EndCrystal(EntityType.END_CRYSTAL, Sim.level)
        c.setShowBottom(false)
        c.isInvulnerable = true
        c.snapTo(at.x, at.y, at.z, 0f, 0f)
        return Sim.spawn(c)
    }

    /** A click on a crystal: picks it up. */
    fun useCrystal(c: EndCrystal): Boolean {
        if (c !in tops && c !in onPylon) return false
        if (c in tops) {
            c.discard(); tops -= c; carrying++
            Sim.chat("§a${Sim.me} picked up an Energy Crystal!")
        }
        return true
    }

    /** A right click near a pylon while carrying: placed on the next check. */
    fun usePylon(at: Vec3): Boolean {
        if (carrying <= 0 || !open) return false
        val i = PYLONS.indexOfFirst { Math.hypot(it.x - at.x, it.z - at.z) < 3.5 && Math.abs(it.y - at.y) < 4 }
        if (i < 0 || placed[i] || i in pending) return false
        carrying--
        pending[i] = Sim.me
        return true
    }

    private fun place(i: Int, by: String) {
        if (placed[i]) return
        placed[i] = true
        onPylon[i] = spawnCrystal(PYLONS[i])
        val x = 1 + placeTimes.count { t >= it + 28 }
        placeTimes += t
        Sim.chat("§c$x§r§a/2 Energy Crystals are now active!")
        if (placed[0] && placed[1] && chargeAt < 0) chargeAt = t + 28
    }

    /** The beam column on the checks: yellow (one charged), red (armed), black. */
    private fun column() {
        val n = placeTimes.count { t >= it + 28 }
        val state = when {
            charged() && beacon -> B.RED_STAINED_GLASS
            n >= 1 -> B.YELLOW_STAINED_GLASS
            else -> B.BLACK_STAINED_GLASS
        }.defaultBlockState()
        for (y in 223..224) if (Blocks.get(BlockPos(73, y, 73)) != state) Blocks.set(BlockPos(73, y, 73), state)
    }

    private fun hit() {
        hits++
        lastHit = t
        chargeAt = -1
        val at = t
        Fight.later(41, "maxor crystals") { if (Fight.phase === this && killAt < 0) TOPS.forEach { tops += spawnCrystal(it) } }
        Fight.later(42, "maxor pylons") { if (Fight.phase === this) { onPylon.forEach { it?.discard() }; onPylon.fill(null); placed.fill(false) } }
        Fight.later(70, "maxor reset") { if (Fight.phase === this) { placeTimes.removeAll { it <= at + 42 }; botCycleAt = at + 70 + Random.nextInt(0, 30) } }
        if (inAbility()) {
            // Silent: the stun line waits for the ability (62 after taunt A); a party kills him at once on the second.
            val lineAt = tauntAt + 62
            if (hits >= 2) killAt = t + Random.nextInt(0, 5)
            Fight.later(lineAt - t, "maxor late stun") { if (Fight.phase === this) stunLine(freeze = killAt < 0) }
        } else stunLine(freeze = true)
    }

    private fun stunLine(freeze: Boolean) {
        stunLines++
        Sim.boss("Maxor", if (Random.nextBoolean()) "THAT BEAM! IT HURTS! IT HURTS!!" else "YOU TRICKED ME!")
        if (stunLines == 2 || hits >= 2) Fight.later(82, "too young") { if (Fight.phase === this && (killAt < 0 || t < killAt + 80)) Sim.boss("Maxor", "I'M TOO YOUNG TO DIE AGAIN!") }
        if (!freeze) return
        stunned = true
        // No ability while stunned: the taunt waits for the enrage.
        if (tauntAt > t) tauntAt = -1
        if (hits >= 2) {
            // Killed while stunned the second time: 0-29 after the line, median 6 (five-player), 8-15 solo.
            if (killAt < 0) killAt = t + if (P3Sim.bots) 2 + Random.nextInt(12) else 8 + Random.nextInt(8)
            return
        }
        // The stun ends on damage: a party mostly bursts it in 6-12; otherwise 30-199; never past 240.
        enrageAt = t + when {
            P3Sim.bots && Random.nextInt(5) != 0 -> 6 + Random.nextInt(7)
            else -> 100 + Random.nextInt(100)
        }.coerceAtMost(240)
    }

    private fun killed() {
        val since = t - killAt
        if (since == 0) {
            Blocks.set(BlockPos(73, 221, 73), B.BEDROCK.defaultBlockState())
            onPylon.forEach { it?.discard() }; onPylon.fill(null)
        }
        if (since == 78) Blocks.play("p1end", skip = -24)
        if (since == 80) maxor.remove()
        if (since == 102) Fight.begin(P2Storm())
    }

    fun status() = when {
        killAt >= 0 -> "Maxor dead"
        hits > 0 -> "Hits $hits/2" + if (stunned) " (stunned)" else ""
        else -> "Crystals ${placed.count { it }}/2" + if (carrying > 0) " (carrying)" else ""
    }
}
