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
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import net.minecraft.world.level.block.Blocks as B

// ====================================================================== P2

/**
 * P2, Storm (docs/mechanics/storm.md, storm-crush.md, maxor-storm-movement.md §3): his diamond
 * route at 0.40, parked at (102.375, 183, 52.375) from ~424; pads (you or a bot on the square) read
 * on the 20-tick checks (t 19 mod 20), each read steps its pillar 5 blocks, one per 4 ticks; the
 * floor cycle (28 wait, drawn up to 189, 24, back to 186 armed); a step onto his hitbox pushes him
 * down (§3.4); lightning at 548 with Giga Lightning at +10/+20 for anyone not under a pillar (again
 * at +940, strikes +36/+46); he leaves at lightning + 139 chasing the 3D-closest player; a check
 * with him in a pillar's zone, head at its bottom and the pillar stepped within 60 crushes him. A
 * crush pins him until a Mage beam (a Hyperion at him, or the party's Mage bot, storm.md §5); 2-3
 * after the enrage line he flies to Yellow (Purple crush) or chases slower (§4); the pillar resets
 * 20 after and is spent. Crush 2 pins for good, dead 0-30 later (§7). Taunts from ~899 (§6).
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

    private val ROUTE = listOf(Vec3(73.0, 183.0, 83.0), Vec3(43.0, 183.0, 53.0), Vec3(73.0, 183.0, 23.0), Vec3(103.0, 183.0, 53.0))
    /** Movement §3.1: the first position within 1 block of the last waypoint ends the route, 0.88 short. */
    private val PARK = Vec3(102.375, 183.0, 52.375)
    private var leg = 0
    private var crushes = 0
    private var pinnedUntilBeam = false
    private var pinnedAt = -1
    private var enrageAt = -1
    private var takeoffAt = -1
    private var lastCrush: Pillar? = null
    private var flyingToYellow = false
    private var deadAt = -1
    private var lightningAt = 548
    /** Storm.md §3: a second line ~940 after the first if he lives, strikes at +36/+46. */
    private val lightning2At get() = lightningAt + 940
    private var chasing = false
    /** After the flight (or a Yellow/Green crush): the slower chase of storm.md §4. */
    private var laterChase = false
    /** Storm.md §3: one roll per run (1,783-18,194, median 9,350), both strikes alike. */
    private var gigaDamage = 9350.0
    private var nextTaunt = -1
    /** Storm.md §6 pool (the "..." lines as the doc records them). */
    private val TAUNTS = listOf(
        "BEGONE PILLAR!", "No more adventurers...", "FINALLY! This took way too long.", "Not just your land...",
        "This factory is too small for me!", "Slowing me down will be your greatest accomplishment!",
        "The days are numbered...", "Now that you're a Ghost...", "The Age of Men is over...", "THAT WAS ONLY IN MY WAY!",
    )

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
        // Storm.md §2 opening drop: Yellow and Purple pads held through the t 19 and 39 checks.
        Party.standAt(listOf(Vec3(32.5, 170.0, 94.5), Vec3(114.5, 170.0, 94.5), Vec3(73.5, 169.0, 60.5), Vec3(73.5, 169.0, 45.5)))
        val r = Random.nextDouble()
        gigaDamage = Math.round(if (r < 0.5) 1783 + r * 2 * (9350 - 1783) else 9350 + (r - 0.5) * 2 * (18194 - 9350)).toDouble()
        // Storm.md §6: first taunt t 882-999, median 899.
        val q = Random.nextDouble()
        nextTaunt = if (q < 0.5) Random.nextInt(882, 900) else if (q < 0.85) Random.nextInt(900, 921) else Random.nextInt(921, 1000)
    }

    override fun stop() { if (::storm.isInitialized) storm.remove() }

    override fun tick() {
        when (t) {
            40 -> Party.standAt(listOf(Vec3(32.5, 169.0, 86.5), Vec3(114.5, 169.0, 86.5), Vec3(73.5, 169.0, 60.5), Vec3(73.5, 169.0, 45.5)))
            62 -> Sim.boss("Storm", "Don't boast about beating this simple-minded Wither.")
            124 -> Sim.boss("Storm", "My abilities are unparalleled, in many ways I am the last bastion.")
            186 -> Sim.boss("Storm", "The memory of your death will be your fondest, focus up!")
            424 -> Sim.boss("Storm", "The power of lightning is quite phenomenal. A single strike can vaporize a person whole.")
            486 -> Sim.boss("Storm", "I'd be happy to show you what that's like!")
        }
        if (t == lightningAt || (t == lightning2At && deadAt < 0)) Sim.boss("Storm", if (Random.nextBoolean()) "ENERGY HEED MY CALL!" else "THUNDER LET ME BE YOUR CATALYST!")
        if (t == lightningAt + 10 || t == lightningAt + 20) giga()
        if (deadAt < 0 && (t == lightning2At + 36 || t == lightning2At + 46)) giga()
        if (deadAt < 0 && t == nextTaunt) { Sim.boss("Storm", TAUNTS.random()); nextTaunt = t + Random.nextInt(60, 64) }
        pillars.forEach { tickPillar(it) }
        if (deadAt >= 0 && t >= deadAt) {
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
        // Storm.md §5: the pin ends 0-1 after a Mage beam (yours, even on the crush tick, or the bot's).
        if (pinnedUntilBeam && crushes < 2) {
            if (beamed && t >= pinnedAt && takeoffAt < 0) { val e = t + Random.nextInt(2); if (enrageAt < 0 || e < enrageAt) enrageAt = e }
            if (enrageAt >= 0 && t >= enrageAt && takeoffAt < 0) release()
            if (takeoffAt >= 0 && t >= takeoffAt) takeoff()
        }
        beamed = false
    }

    private var beamed = false

    /** You used a Hyperion (or swung one) at him. */
    fun beam() { if (lookingAt(storm.pos.add(0.0, 2.0, 0.0), 6.0, 45.0)) beamed = true }

    /** Storm.md §5 crush -> enrage with a Mage: median 10, 52/130 at 0-4, bump at 21-25 (24 most), tail to ~60. */
    private fun pinLength(): Int {
        val r = Random.nextDouble()
        return when {
            r < 0.40 -> Random.nextInt(0, 5)
            r < 0.50 -> Random.nextInt(5, 10)
            r < 0.72 -> Random.nextInt(10, 21)
            r < 0.80 -> 24
            r < 0.90 -> Random.nextInt(21, 26)
            else -> Random.nextInt(26, 61)
        }
    }

    /** Storm.md §7 crush 2 -> death line: 0-30, median 6, p10 3, p90 23, a bump at 22-30. */
    private fun deathDelay(): Int {
        val r = Random.nextDouble()
        return when {
            r < 0.10 -> Random.nextInt(0, 3)
            r < 0.55 -> Random.nextInt(3, 8)
            r < 0.70 -> Random.nextInt(8, 11)
            r < 0.85 -> Random.nextInt(11, 22)
            r < 0.92 -> Random.nextInt(22, 25)
            else -> Random.nextInt(25, 31)
        }
    }

    private fun move() {
        if (pinnedUntilBeam) return
        if (flyingToYellow) {
            val pt = Vec3(46.0, 172.8, 65.0)
            val d = Math.hypot(storm.pos.x - pt.x, storm.pos.z - pt.z)
            if (d <= 2.4) { flyingToYellow = false; laterChase = true; return }
            // Movement §3.5 skipped moves: ~2% at x 94-82, 6-8% at x 82-70.
            val skip = if (storm.pos.x > 82) 0.02 else if (storm.pos.x > 70) 0.07 else 0.0
            if (Random.nextDouble() < skip) return
            storm.step(pt, min(0.7157, 0.36 + 0.0134 * d)); return
        }
        if (laterChase) {
            // Storm.md §4: horizontal 0.145 + 0.0205·d to ~3.3 above the 3D-closest; circles within ~3.
            val target = closest(storm.pos)
            val aim = target.add(0.0, 3.3, 0.0)
            val d = Math.hypot(aim.x - storm.pos.x, aim.z - storm.pos.z)
            if (d > 3.0) {
                val sp = 0.145 + 0.0205 * d
                val dy = (aim.y - storm.pos.y).coerceIn(-0.3, 0.3)
                storm.moveTo(Vec3(storm.pos.x + (aim.x - storm.pos.x) / d * sp, storm.pos.y + dy, storm.pos.z + (aim.z - storm.pos.z) / d * sp), target)
            }
            return
        }
        if (chasing) {
            // Movement §3.3: min(0.9, 0.2 + 0.023·d), d 3D to 3 above the 3D-closest player's feet.
            val target = closest(storm.pos)
            val aim = target.add(0.0, 3.0, 0.0)
            if (Math.hypot(storm.pos.x - aim.x, storm.pos.z - aim.z) > 3.0) storm.step(aim, min(0.9, 0.2 + 0.023 * storm.pos.distanceTo(aim)), target)
            return
        }
        if (leg < ROUTE.size) {
            if (storm.pos.distanceTo(ROUTE[leg]) < 1.0) { leg++; if (leg == ROUTE.size) storm.moveTo(PARK) }
            if (leg < ROUTE.size) storm.step(ROUTE[leg], 0.40)
        }
        if (t >= lightningAt + 139) chasing = true
    }

    private fun check() {
        // Storm.md §2: anyone on the square counts, bots included.
        val on = listOfNotNull(Sim.player?.position()) + Party.bots().filter { it.entity != null }.map { it.pos }
        for (pl in pillars) {
            if (pl.pad != null && pl.resting() && on.any { pl.pad.contains(it) }) pl.steps = 5
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
        flyingToYellow = false
        if (crushes >= 2) { deadAt = t + deathDelay(); pinnedUntilBeam = true; return }
        pinnedUntilBeam = true
        pinnedAt = t
        lastCrush = pl
        takeoffAt = -1
        enrageAt = if (P3Sim.bots && Party.myRole != Party.Role.CORE) t + pinLength() else -1
    }

    /** The enrage line; he holds still until takeoff 2-3 after it (median 2, movement §3.5). */
    private fun release() {
        if (crushes >= 2) return
        Sim.chat("§c⚠ Storm is enraged! ⚠")
        takeoffAt = t + if (Random.nextDouble() < 0.6) 2 else 3
    }

    /** Only Purple -> Yellow is a flight; after Yellow/Green he just chases (storm.md §4). */
    private fun takeoff() {
        pinnedUntilBeam = false
        takeoffAt = -1; enrageAt = -1
        chasing = false
        if (lastCrush?.name == "Purple") { flyingToYellow = true; laterChase = false } else laterChase = true
    }

    /** Movement §3.4: a step into his hitbox (y..y+3.5) pushes him down by the overlap, to 169 at most. */
    private fun pushDown(pl: Pillar) {
        if (!::storm.isInitialized) return
        val s = storm.pos
        if (s.x + 0.45 <= pl.minX || s.x - 0.45 >= pl.minX + 7 || s.z + 0.45 <= pl.minZ || s.z - 0.45 >= pl.minZ + 7) return
        if (s.y >= pl.bottom + 1 || s.y + 3.5 <= pl.bottom) return
        val y = max(169.0, pl.bottom - 3.5)
        if (y < s.y) storm.moveTo(Vec3(s.x, y, s.z))
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
            if (pl.bottom > 169) { pl.bottom--; layer(pl, pl.bottom, true); pl.lastStep = t; pushDown(pl); Sim.sound(SoundEvents.PISTON_EXTEND, 0.6f, 0.8f, Vec3(pl.minX + 3.5, pl.bottom.toDouble(), pl.minZ + 3.5)) }
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
            if (pl.bottom > pl.lowerTo) { pl.bottom--; layer(pl, pl.bottom, true); pushDown(pl); pl.nextStep = t + 4 } else pl.lowerTo = -1
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
        Sim.chat("§cStorm's§r§7 Giga Lightning hit you for §r§c${String.format(Locale.US, "%,.1f", gigaDamage)}§r§7 true damage.")
        Stats.lightning()
    }

    fun status() = when {
        deadAt >= 0 -> "Storm dead"
        pinnedUntilBeam -> "Pinned (crush $crushes)"
        else -> "Crushes $crushes/2" + pillars.filter { it.pad != null }.joinToString("") { " ${it.name[0]}${if (it.spent) "x" else it.bottom}" }
    }
}
