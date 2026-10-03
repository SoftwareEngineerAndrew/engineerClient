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
private fun closest(p: Vec3): Vec3 {
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
 * P1, Maxor (docs/mechanics/maxor.md): the intro on 62-tick lines; crystals on the top platforms
 * from +5 (click one to pick it up), the pylons take them from the +166 check (right-click the
 * pylon); "The Energy Laser is charging up!" 28 after the second; armed from the +206 check. He
 * chases the closest player at min(0.9, 0.24 + 0.02 d), stopping ~3 blocks off; a check with him
 * within 3.5 of the beam (73.5, 73.5) stuns him. Two stuns 200 ticks apart, the party kills him,
 * he despawns 80 later and Storm speaks 102 after the kill.
 */
class P1Maxor : Fight.Phase("P1") {
    override val restart get() = Fight.Start.P1
    private lateinit var maxor: BossWither
    private val crystals = ArrayList<EndCrystal>()
    private var carrying = 0
    private val placed = BooleanArray(2)
    private var placedCount = 0
    private var chargeAt = -1
    private var armed = false
    private var hits = 0
    private var lastHit = -1000
    private var stunnedUntil = -1
    private var killAt = -1
    private var stormAt = -1
    private val TOPS = listOf(Vec3(64.5, 238.4, 50.5), Vec3(82.5, 238.4, 50.5))
    private val PYLONS = listOf(Vec3(52.5, 224.4, 41.5), Vec3(94.5, 224.4, 41.5))
    private val BEAM = Vec3(73.5, 226.0, 73.5)

    override fun start() {
        val p = Sim.player ?: return
        val s = Spots.P1
        Sim.tp(p, s.x, s.y, s.z, s.yaw, s.pitch)
        SimItems.giveHotbar(p, p3 = false)
        Sim.boss("Maxor", "WELL! WELL! WELL! LOOK WHO'S HERE!")
        Blocks.play("p1strip")
        Party.standAt(listOf(Vec3(71.5, 221.0, 16.5), Vec3(75.5, 221.0, 16.5), Vec3(69.5, 221.0, 18.5), Vec3(77.5, 221.0, 18.5)))
    }

    override fun stop() { crystals.forEach { it.discard() }; if (::maxor.isInitialized) maxor.remove() }

    private fun check() = t % 10 == 6

    override fun tick() {
        when (t) {
            5 -> { maxor = BossWither("Maxor", Vec3(73.0, 226.0, 53.0)); TOPS.forEach { spawnCrystal(it) } }
            62 -> Sim.boss("Maxor", "I'VE BEEN TOLD I COULD HAVE A BIT OF FUN WITH YOU.")
            124 -> Sim.boss("Maxor", "DON'T DISAPPOINT ME, I HAVEN'T HAD A GOOD FIGHT IN A WHILE.")
            166 -> Sim.note("The pylons take crystals now: pick one up on top, right-click a pylon (or let the bots).")
        }
        if (!::maxor.isInitialized) return
        // Bots place a crystal at the first check they can, if the other is yours.
        if (P3Sim.bots && t >= 166 && check()) {
            val free = (0..1).firstOrNull { !placed[it] && it != 0 }
            if (free != null) place(free, "bot")
        }
        if (chargeAt >= 0 && t == chargeAt) Sim.chat("§aThe Energy Laser is charging up!")
        if (chargeAt >= 0 && t > chargeAt && t >= 206 && check() && !armed) { armed = true; Blocks.set(BlockPos(73, 221, 73), B.BEACON.defaultBlockState()) }
        val stunned = t < stunnedUntil || killAt >= 0
        if (!stunned && t >= 170) {
            val target = closest(maxor.pos).add(0.0, 1.0, 0.0)
            val d = maxor.pos.distanceTo(target)
            if (d > 3.0) maxor.step(target, min(0.9, 0.24 + 0.02 * d))
        }
        // The laser.
        if (armed && check() && killAt < 0 && t - lastHit >= 200 && Math.hypot(maxor.pos.x - BEAM.x, maxor.pos.z - BEAM.z) <= 3.5) hit()
        if (killAt < 0 && hits == 2 && t >= lastHit + 6) kill()
        if (killAt >= 0 && t == killAt + 80) maxor.remove()
        if (killAt >= 0 && t == killAt + 78) Blocks.play("p1end", skip = -24)
        if (killAt >= 0 && t == killAt + 102) Fight.begin(P2Storm())
    }

    private fun spawnCrystal(at: Vec3) {
        val c = EndCrystal(EntityType.END_CRYSTAL, Sim.level)
        c.setShowBottom(false)
        c.isInvulnerable = true
        c.snapTo(at.x, at.y, at.z, 0f, 0f)
        crystals += Sim.spawn(c)
    }

    /** A click on a crystal: picks it up. */
    fun useCrystal(c: EndCrystal): Boolean {
        if (c !in crystals) return false
        if (TOPS.any { it.distanceTo(c.position()) < 1.0 }) {
            c.discard(); crystals -= c; carrying++
            Sim.chat("§aYou picked up an Energy Crystal!")
        }
        return true
    }

    /** A right click near a pylon while carrying: places it. */
    fun usePylon(at: Vec3): Boolean {
        if (carrying <= 0 || t < 166) return false
        val i = PYLONS.indexOfFirst { it.distanceTo(at) < 3.0 }
        if (i < 0 || placed[i]) return false
        carrying--
        place(i, Sim.me)
        return true
    }

    private fun place(i: Int, by: String) {
        placed[i] = true
        placedCount++
        spawnCrystal(PYLONS[i])
        Sim.chat("§c$placedCount§r§a/2 Energy Crystals are now active!")
        if (placedCount == 2) chargeAt = t + 28
    }

    private fun hit() {
        hits++
        lastHit = t
        Sim.boss("Maxor", if (Random.nextBoolean()) "THAT BEAM! IT HURTS! IT HURTS!!" else "YOU TRICKED ME!")
        stunnedUntil = t + 40
        if (hits == 1) Fight.later(40, "maxor enrage") { if (Fight.phase === this && killAt < 0) Sim.chat("§c⚠ Maxor is enraged! ⚠") }
    }

    private fun kill() {
        killAt = t
        Blocks.set(BlockPos(73, 221, 73), B.BEDROCK.defaultBlockState())
        Fight.later(82, "too young") { if (Fight.phase === this) Sim.boss("Maxor", "I'M TOO YOUNG TO DIE AGAIN!") }
    }

    fun status() = when {
        killAt >= 0 -> "Maxor dead"
        hits > 0 -> "Hits $hits/2"
        else -> "Crystals $placedCount/2" + if (carrying > 0) " (carrying)" else ""
    }
}

// ====================================================================== P2

/**
 * P2, Storm (docs/mechanics/storm.md, storm-crush.md): his diamond route at 0.40 to the parking
 * spot; pads read on the 20-tick checks (t 19 mod 20), each read steps its pillar 5 blocks, one per
 * 4 ticks; the floor cycle (28 wait, drawn up to 189, 24, back to 186 armed); lightning at 548
 * with Giga Lightning at +10/+20 for anyone not under a pillar; he leaves at 687 chasing the
 * closest player; a check with him in a pillar's zone, head at its bottom and the pillar stepped
 * within 60 crushes him. A crush pins him until a Mage beam (a Hyperion at him, or the party's
 * Mage bot) - he then flies to Yellow; the pillar resets 20 after and is spent. Crush 2 kills.
 */
class P2Storm : Fight.Phase("P2") {
    override val restart get() = Fight.Start.P2
    private lateinit var storm: BossWither

    inner class Pillar(val name: String, val minX: Int, val minZ: Int, var bottom: Int, val pad: AABB?) {
        var steps = 0
        var nextStep = -1
        var lastStep = -1000
        var floorAt = -1
        var raising = false
        var lowerTo = -1
        var spent = false
        var resetAt = -1
        val zone get() = AABB(minX.toDouble(), 0.0, minZ.toDouble(), minX + 6.0, 300.0, minZ + 6.0)
        val square get() = AABB(minX.toDouble(), 168.5, minZ.toDouble(), minX + 7.0, 170.5, minZ + 7.0)
        fun under(p: Vec3) = square.contains(p)
        fun resting() = !spent && steps == 0 && floorAt < 0 && !raising && lowerTo < 0 && bottom > 169
    }

    private val pillars = listOf(
        Pillar("Purple", 97, 62, 175, AABB(111.0, 169.5, 91.0, 118.0, 171.5, 98.0)),
        Pillar("Yellow", 43, 62, 175, AABB(29.0, 169.5, 91.0, 36.0, 171.5, 98.0)),
        Pillar("Green", 43, 38, 175, AABB(29.0, 169.5, 9.0, 36.0, 171.5, 16.0)),
        Pillar("Red", 97, 38, 181, null),
    )
    private val footprint: List<Pair<Int, Int>> by lazy {
        Blocks.extra("anims-p124.json", "pillars")?.getAsJsonArray("footprint")?.map { val a = it.asJsonArray; a[0].asInt to a[1].asInt }
            ?: (0..6).flatMap { x -> (0..6).map { z -> x to z } }.filter { (x, z) -> !((x == 0 || x == 6) && (z < 2 || z > 4)) && !((x == 1 || x == 5) && (z == 0 || z == 6)) }
    }
    private val dioriteCols: Set<Pair<Int, Int>> by lazy {
        Blocks.extra("anims-p124.json", "pillars")?.getAsJsonArray("dioriteColumns")?.map { val a = it.asJsonArray; a[0].asInt to a[1].asInt }?.toSet() ?: setOf(0 to 3, 3 to 0, 3 to 6, 6 to 3)
    }

    private val ROUTE = listOf(Vec3(73.0, 183.0, 83.0), Vec3(43.0, 183.0, 53.0), Vec3(73.0, 183.0, 23.0), Vec3(102.375, 183.0, 52.375))
    private var leg = 0
    private var crushes = 0
    private var pinnedUntilBeam = false
    private var pinnedAt = -1
    private var flyingToYellow = false
    private var deadAt = -1
    private var lightningAt = 548
    private var chasing = false

    override fun start() {
        val p = Sim.player
        if (p != null && Fight.previous !is P1Maxor) {
            val s = Spots.PURPLE_PAD
            Sim.tp(p, s.x, s.y, s.z, s.yaw, s.pitch)
            SimItems.giveHotbar(p, p3 = false)
            // Maxor's floor is already gone when you start here.
            Blocks.finish("p1end")
        }
        storm = BossWither("Storm", Vec3(103.0, 188.0, 53.0))
        Sim.boss("Storm", "Pathetic Maxor, just like expected.")
        Party.standAt(listOf(Vec3(32.5, 170.0, 94.5), Vec3(32.5, 170.0, 12.5), Vec3(73.5, 169.0, 60.5), Vec3(114.5, 170.0, 94.5)))
    }

    override fun stop() { if (::storm.isInitialized) storm.remove() }

    override fun tick() {
        when (t) {
            62 -> Sim.boss("Storm", "Don't boast about beating this simple-minded Wither.")
            124 -> Sim.boss("Storm", "My abilities are unparalleled, in many ways I am the last bastion.")
            186 -> Sim.boss("Storm", "The memory of your death will be your fondest, focus up!")
            372 -> Sim.boss("Storm", "The power of lightning is quite phenomenal. A single strike can vaporize a person whole.")
            434 -> Sim.boss("Storm", "I'd be happy to show you what that's like!")
        }
        if (t == lightningAt) Sim.boss("Storm", if (Random.nextBoolean()) "ENERGY HEED MY CALL!" else "THUNDER LET ME BE YOUR CATALYST!")
        if (t == lightningAt + 10 || t == lightningAt + 20) giga()
        pillars.forEach { tickPillar(it) }
        if (deadAt >= 0) {
            when (t - deadAt) {
                0 -> Sim.boss("Storm", "I should have known that I stood no chance.")
                62 -> Sim.boss("Storm", "At least my son died by your hands.")
                40 -> storm.remove()
                102 -> Fight.begin(GoldorPhase(1, arrived = true))
            }
            return
        }
        move()
        if (t % 20 == 19) check()
        // The pin ends at a Mage beam: yours (a Hyperion at him), or the Mage bot's 3-12 ticks in.
        if (pinnedUntilBeam && (t == pinnedAt + mageBeam || (beamed && t > pinnedAt))) release()
        beamed = false
    }

    private var mageBeam = 3
    private var beamed = false

    /** You used a Hyperion (or swung one) at him. */
    fun beam() { if (lookingAt(storm.pos.add(0.0, 2.0, 0.0), 6.0, 45.0)) beamed = true }

    private fun move() {
        if (pinnedUntilBeam) return
        if (flyingToYellow) {
            val pt = Vec3(46.0, 172.8, 65.0)
            val d = Math.hypot(storm.pos.x - pt.x, storm.pos.z - pt.z)
            if (d <= 2.4) { flyingToYellow = false; chasing = true; return }
            storm.step(pt, min(0.7157, 0.36 + 0.0134 * d)); return
        }
        if (chasing) {
            val target = closest(storm.pos)
            val aim = target.add(0.0, 3.3, 0.0)
            val d = Math.hypot(storm.pos.x - aim.x, storm.pos.z - aim.z)
            if (d > 3.0) storm.step(aim, min(0.9, 0.2 + 0.023 * d), target)
            return
        }
        if (leg < ROUTE.size) { if (storm.step(ROUTE[leg], 0.40)) leg++ }
        if (t >= 687) chasing = true
    }

    private fun check() {
        val p = Sim.player
        for (pl in pillars) {
            if (pl.pad != null && p != null && pl.resting() && pl.pad.contains(p.position())) pl.steps = 5
            if (pl.pad != null && pl.steps > 0 && pl.nextStep < 0) pl.nextStep = t
        }
        // Crush.
        if (deadAt >= 0 || pinnedUntilBeam) return
        for (pl in pillars) {
            if (pl.spent) continue
            val s = storm.pos
            val inZone = s.x >= pl.minX && s.x <= pl.minX + 6 && s.z >= pl.minZ && s.z <= pl.minZ + 6
            if (inZone && s.y + 2.975 >= pl.bottom && t - pl.lastStep <= 60) { crush(pl); return }
        }
    }

    private fun crush(pl: Pillar) {
        crushes++
        Sim.boss("Storm", if (Random.nextBoolean()) "Ouch, that hurt!" else "Oof")
        pl.resetAt = t + 20
        pl.spent = true
        pl.steps = 0; pl.nextStep = -1
        storm.moveTo(storm.pos)
        if (crushes >= 2) { Fight.later(6, "storm dead") { if (Fight.phase === this) deadAt = t } ; pinnedUntilBeam = true; return }
        pinnedUntilBeam = true
        pinnedAt = t
        mageBeam = if (P3Sim.bots && Party.myRole != Party.Role.CORE) 3 + Random.nextInt(10) else Int.MAX_VALUE / 2
    }

    private fun release() {
        if (crushes >= 2) return
        pinnedUntilBeam = false
        Sim.chat("§c⚠ Storm is enraged! ⚠")
        chasing = false
        flyingToYellow = true
    }

    private fun tickPillar(pl: Pillar) {
        if (pl.resetAt >= 0 && t >= pl.resetAt) {
            pl.resetAt = -1
            for (y in 169 until 189) layer(pl, y, false)
            pl.bottom = 189
            return
        }
        if (pl.spent) return
        if (pl.nextStep >= 0 && t >= pl.nextStep && pl.steps > 0) {
            if (pl.bottom > 169) { pl.bottom--; layer(pl, pl.bottom, true); pl.lastStep = t; Sim.sound(SoundEvents.PISTON_EXTEND, 0.6f, 0.8f, Vec3(pl.minX + 3.5, pl.bottom.toDouble(), pl.minZ + 3.5)) }
            pl.steps--
            pl.nextStep = if (pl.steps > 0) t + 4 else -1
            if (pl.bottom <= 169) { pl.steps = 0; pl.nextStep = -1; pl.floorAt = t }
        }
        if (pl.floorAt >= 0 && t >= pl.floorAt + 28) { pl.floorAt = -1; pl.raising = true; pl.nextStep = t }
        if (pl.raising && t >= pl.nextStep) {
            if (pl.bottom < 189) { layer(pl, pl.bottom, false); pl.bottom++; pl.nextStep = t + 4 }
            else { pl.raising = false; pl.lowerTo = 186; pl.nextStep = t + 24 }
        }
        if (pl.lowerTo >= 0 && t >= pl.nextStep) {
            if (pl.bottom > pl.lowerTo) { pl.bottom--; layer(pl, pl.bottom, true); pl.nextStep = t + 4 } else pl.lowerTo = -1
        }
    }

    private fun layer(pl: Pillar, y: Int, solid: Boolean) {
        for ((dx, dz) in footprint) {
            val st = if (!solid) B.AIR.defaultBlockState() else if ((dx to dz) in dioriteCols) B.DIORITE.defaultBlockState() else B.POLISHED_DIORITE.defaultBlockState()
            Blocks.set(BlockPos(pl.minX + dx, y, pl.minZ + dz), st)
        }
    }

    private fun giga() {
        val p = Sim.player ?: return
        val bolt = LightningBolt(EntityType.LIGHTNING_BOLT, Sim.level)
        bolt.setVisualOnly(true)
        bolt.snapTo(p.x, p.y, p.z, 0f, 0f)
        Sim.spawn(bolt)
        if (pillars.any { it.under(p.position()) } || SimItems.cloaked) return
        Sim.chat("§cStorm's§r§7 Giga Lightning hit you for §r§c9,350.0§r§7 true damage.")
        Stats.lightning()
    }

    fun status() = when {
        deadAt >= 0 -> "Storm dead"
        pinnedUntilBeam -> "Pinned (crush $crushes)"
        else -> "Crushes $crushes/2" + pillars.filter { it.pad != null }.joinToString("") { " ${it.name[0]}${if (it.spent) "x" else it.bottom}" }
    }
}

// ====================================================================== P4

/**
 * P4, Necron (docs/mechanics/necron.md): his fixed timers. Lines at 0/62/124/186, off mid at 159
 * and back at 177, "ARGH!" at 330, "Let's make some space!" at 392 (a platform goes 45 later),
 * "ARGH!" at 545, "All this, for nothing..." at 607 and gone 61 later, then the end screen.
 */
class P4Necron(val fromP3: Boolean = false) : Fight.Phase("P4") {
    override val restart get() = Fight.Start.P4
    private lateinit var necron: BossWither
    private val MID = Vec3(54.0, 66.0, 76.0)

    override fun start() {
        val p = Sim.player
        if (!fromP3 && p != null) {
            Blocks.finish("p3end")
            val s = Spots.P4
            Sim.tp(p, s.x, s.y, s.z, s.yaw, s.pitch)
        }
        necron = BossWither("Necron", MID)
        Sim.boss("Necron", "You went further than any human before, congratulations.")
        Blocks.play("p4", skip = -(392 - 167))
    }

    override fun stop() { if (::necron.isInitialized) necron.remove() }

    override fun tick() {
        when (t) {
            62 -> Sim.boss("Necron", "I'm afraid, your journey ends now.")
            124 -> Sim.boss("Necron", "Goodbye.")
            186 -> Sim.boss("Necron", "That's a very impressive trick. I guess I'll have to handle this myself.")
            248 -> Sim.boss("Necron", "Sometimes when you have a problem, you just need to destroy it all and start again.")
            330 -> Sim.boss("Necron", "ARGH!")
            392 -> Sim.boss("Necron", "Let's make some space!")
            454 -> Sim.boss("Necron", "WITNESS MY RAW NUCLEAR POWER!")
            545 -> Sim.boss("Necron", "ARGH!")
            607 -> Sim.boss("Necron", "All this, for nothing...")
            668 -> necron.remove()
            693 -> end()
        }
        if (t in 159 until 177) necron.step(Vec3(54.0, 72.0, 100.0), 1.2)
        if (t == 177) necron.moveTo(MID)
        if (t in 452 until 457) necron.step(Vec3(30.0, 72.0, 76.0), 1.2)
        if (t == 457) necron.moveTo(MID)
        if (t < 668 && t % 5 == 0) necron.moveTo(necron.pos, closest(necron.pos))
    }

    private fun end() {
        val total = Stats.runTicks()
        Sim.chat("§a§l▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬")
        Sim.chat("§f                        §r§cThe Catacombs §r§8- §r§eFloor VII")
        if (total > 0) Sim.chat("§f     §r§c☠ §r§eDefeated §r§cMaxor, Storm, Goldor, and Necron §r§ein §r§a%02dm %02ds".format(total / 1200, total / 20 % 60))
        Sim.chat("§a§l▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬")
        Sim.note("Done. §fMenu§7 to go again.")
    }

    fun status() = if (t < 668) "Necron ${t / 20}s" else "Necron dead"
}
