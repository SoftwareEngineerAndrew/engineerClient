package com.engineerclient.p3sim

import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import net.minecraft.world.level.block.Blocks as B

// ====================================================================== P2

/**
 * P2, Storm, as measured on Hypixel: his diamond route at 0.40, parked at (102.375, 183, 52.375) from
 * ~424, a Lightning Fireball every ~44 ticks on the way (each impact may seed a Static Field); pads
 * (you or a bot on the square) read on the 20-tick checks (t 19 mod 20), each read steps its pillar
 * 5 blocks, one per 4 ticks; the floor cycle (28 wait, drawn up to 189, 24, back to 186 armed); a
 * step onto his hitbox pushes him down; lightning at 548 with Giga Lightning at +10/+19-20
 * for everyone (a 40-bolt ring round a pillar you hide under, else 31 bolts around you; again at
 * +940, strikes +36/+46); he leaves at lightning + 139 chasing the 3D-closest player; a check with
 * him in a pillar's zone, head at its bottom and the pillar stepped within 60 crushes him. A crush
 * pins him until a Mage beam (a Hyperion at him, or the party's Mage bot); 2-3 after
 * the enrage line he flies to Yellow (Purple crush) or chases slower, skulls in pairs every 5
 * ticks; the pillar resets 20 after and is spent. Crush 2 pins for good, dead 0-30 later, and
 * his body and its lightning storm go on into P3 ([StormCorpse]). Taunts from ~899.
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
        val square get() = AABB(minX.toDouble(), 168.5, minZ.toDouble(), minX + 7.0, 170.5, minZ + 7.0)
        val centre get() = Vec3(minX + 3.5, 169.0, minZ + 3.5)
        fun under(p: Vec3) = square.contains(p)
        fun resting() = !spent && steps == 0 && floorAt < 0 && !raising && lowerTo < 0 && bottom > 169
    }

    private val pillars = listOf(
        Pillar("Purple", 97, 62, 175, AABB(111.0, 169.5, 91.0, 118.0, 171.5, 98.0)),
        Pillar("Yellow", 43, 62, 175, AABB(29.0, 169.5, 91.0, 36.0, 171.5, 98.0)),
        Pillar("Green", 43, 38, 175, AABB(29.0, 169.5, 9.0, 36.0, 171.5, 16.0)),
        Pillar("Red", 97, 38, 181, null),
    )
    private val purple get() = pillars[0]
    private val yellow get() = pillars[1]
    private val footprint: List<Pair<Int, Int>> by lazy {
        Blocks.extra("anims-p124.json", "pillars")?.getAsJsonArray("footprint")?.map { val a = it.asJsonArray; a[0].asInt to a[1].asInt }
            ?: (0..6).flatMap { x -> (0..6).map { z -> x to z } }.filter { (x, z) -> !((x == 0 || x == 6) && (z < 2 || z > 4)) && !((x == 1 || x == 5) && (z == 0 || z == 6)) }
    }

    private val ROUTE = listOf(Vec3(73.0, 183.0, 83.0), Vec3(43.0, 183.0, 53.0), Vec3(73.0, 183.0, 23.0), Vec3(103.0, 183.0, 53.0))
    /** The first position within 1 block of the last waypoint ends the route, 0.88 short. */
    private val PARK = Vec3(102.375, 183.0, 52.375)
    private var leg = 0
    private var crushes = 0
    private var pinnedUntilBeam = false
    private var pinnedAt = -1
    private var enrageAt = -1
    private var enragedAt = -1
    private var takeoffAt = -1
    private var crush2At = -1
    private var lastCrush: Pillar? = null
    private var flyingToYellow = false
    private var deadAt = -1
    private var lightningAt = 548
    /** The second strike at line +19 or +20, about equally often. */
    private val giga2 = 19 + Random.nextInt(2)
    /** A second line ~940 after the first if he lives, strikes at +36/+46. */
    private val lightning2At get() = lightningAt + 940
    private var chasing = false
    /** After the flight (or a Yellow/Green crush): the slower chase. */
    private var laterChase = false
    /** When his current chase began (for the bots' fallback). */
    private var chaseSince = -1
    /** His body went on to [StormCorpse]: this phase doesn't remove it. */
    private var handed = false
    /** One roll per run (1,783-18,194, median 9,350), both strikes alike. */
    private var gigaDamage = 9350.0
    private var nextTaunt = -1
    /** His taunt pool, full texts as Hypixel sends them. */
    private val TAUNTS = listOf(
        "BEGONE PILLAR!", "No more adventurers, no more heroes, death and thunder!", "FINALLY! This took way too long.",
        "Not just your land, but every kingdom will soon be ruled by our army of undead!",
        "This factory is too small for me!", "Slowing me down will be your greatest accomplishment!",
        "The days are numbered until I am finally unleashed again on the world!", "Now that you're a Ghost, can you help me clean up?",
        "The Age of Men is over, we are creating tens, hundreds of withers!!", "THAT WAS ONLY IN MY WAY!",
    )

    override fun start() {
        val p = Sim.player
        if (p != null && Fight.previous !is P1Maxor) {
            val s = Spots.PURPLE_PAD
            Sim.tp(p, s.x, s.y, s.z, s.yaw, s.pitch)
            SimItems.giveHotbar(p, p3 = false)
        }
        storm = BossWither("Storm", Vec3(103.0, 188.0, 53.0))
        line("Pathetic Maxor, just like expected.")
        startBots()
        val r = Random.nextDouble()
        gigaDamage = Math.round(if (r < 0.5) 1783 + r * 2 * (9350 - 1783) else 9350 + (r - 0.5) * 2 * (18194 - 9350)).toDouble()
        // First taunt t 882-999, median 899.
        val q = Random.nextDouble()
        nextTaunt = if (q < 0.5) Random.nextInt(882, 900) else if (q < 0.85) Random.nextInt(900, 921) else Random.nextInt(921, 1000)
        // The first fireball at t 71-252, median 113.
        nextFireball = 75 + (Random.nextDouble().pow(2.0) * 150).toInt()
    }

    override fun stop() {
        if (::storm.isInitialized && !handed) storm.remove()
        StormFx.clearSpeech()
    }

    private fun line(text: String) = StormFx.line(storm, text)

    override fun tick() {
        when (t) {
            62 -> line("Don't boast about beating this simple-minded Wither.")
            124 -> line("My abilities are unparalleled, in many ways I am the last bastion.")
            186 -> line("The memory of your death will be your fondest, focus up!")
            424 -> line("The power of lightning is quite phenomenal. A single strike can vaporize a person whole.")
            486 -> line("I'd be happy to show you what that's like!")
        }
        if (t == lightningAt || (t == lightning2At && deadAt < 0)) line(if (Random.nextBoolean()) "ENERGY HEED MY CALL!" else "THUNDER LET ME BE YOUR CATALYST!")
        if (t == lightningAt + 10 || t == lightningAt + giga2) giga()
        if (deadAt < 0 && (t == lightning2At + 36 || t == lightning2At + 46)) giga()
        if (deadAt < 0 && t == nextTaunt) { line(TAUNTS.random()); nextTaunt = t + Random.nextInt(60, 64) }
        pillars.forEach { tickPillar(it) }
        fields.removeAll { !it.tick() }
        bar()
        tickBots()
        StormFx.follow()
        if (deadAt >= 0 && t >= deadAt) {
            when (t - deadAt) {
                0 -> { line("I should have known that I stood no chance."); handed = true; StormCorpse(storm).start() }
                62 -> line("At least my son died by your hands.")
                102 -> Fight.begin(GoldorPhase(1, arrived = true))
            }
            return
        }
        move()
        if (t % 20 == 19) check()
        attacks()
        // The pin ends 0-1 after a Mage beam (yours, even on the crush tick, or the bot's).
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

    /** Crush -> enrage with a Mage: median 10, ~40% at 0-4, a bump at 21-25 (24 most), tail to ~60. */
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

    /** Crush 2 -> death line: 0-30, median 6, p10 3, p90 23, a bump at 22-30. */
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
            if (d <= 2.4) { flyingToYellow = false; laterChase = true; chaseSince = t; return }
            // Skipped moves: ~2% at x 94-82, 6-8% at x 82-70.
            val skip = if (storm.pos.x > 82) 0.02 else if (storm.pos.x > 70) 0.07 else 0.0
            if (Random.nextDouble() < skip) return
            storm.step(pt, min(0.7157, 0.36 + 0.0134 * d)); return
        }
        if (laterChase) {
            // Horizontal 0.145 + 0.0205·d to ~3.3 above the 3D-closest; circles within ~3.
            val target = closest(storm.pos)
            val aim = target.add(0.0, 3.3, 0.0)
            val d = Math.hypot(aim.x - storm.pos.x, aim.z - storm.pos.z)
            if (d > 3.0) {
                val sp = 0.145 + 0.0205 * d
                val dy = (aim.y - storm.pos.y).coerceIn(-0.3, 0.3)
                storm.moveTo(Vec3(storm.pos.x + (aim.x - storm.pos.x) / d * sp, storm.pos.y + dy, storm.pos.z + (aim.z - storm.pos.z) / d * sp), target)
            } else storm.moveTo(storm.pos, target)
            return
        }
        if (chasing) {
            // min(0.9, 0.2 + 0.023·d), d 3D to 3 above the 3D-closest player's feet.
            val target = closest(storm.pos)
            val aim = target.add(0.0, 3.0, 0.0)
            if (Math.hypot(storm.pos.x - aim.x, storm.pos.z - aim.z) > 3.0) storm.step(aim, min(0.9, 0.2 + 0.023 * storm.pos.distanceTo(aim)), target)
            return
        }
        if (leg < ROUTE.size) {
            if (storm.pos.distanceTo(ROUTE[leg]) < 1.0) { leg++; if (leg == ROUTE.size) storm.moveTo(PARK) }
            if (leg < ROUTE.size) storm.step(ROUTE[leg], 0.40)
        }
        if (t >= lightningAt + 139) { chasing = true; chaseSince = t }
    }

    private fun check() {
        // Anyone on the square counts, bots included.
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
        line(if (Random.nextBoolean()) "Ouch, that hurt!" else "Oof")
        storm.armour(false)
        pl.resetAt = t + 20
        pl.spent = true
        pl.steps = 0; pl.nextStep = -1
        storm.moveTo(storm.pos)
        flyingToYellow = false; chasing = false; laterChase = false
        stuck = null
        if (crushes >= 2) { deadAt = t + deathDelay(); crush2At = t; pinnedUntilBeam = true; return }
        pinnedUntilBeam = true
        pinnedAt = t
        lastCrush = pl
        takeoffAt = -1
        enrageAt = if (P3Sim.bots && P3Sim.myClass != DungeonClass.MAGE) t + pinLength() else -1
    }

    /** The enrage line; he holds still until takeoff 2-3 after it (median 2). */
    private fun release() {
        if (crushes >= 2) return
        Sim.chat("§c⚠ Storm is enraged! ⚠")
        enragedAt = t
        takeoffAt = t + if (Random.nextDouble() < 0.6) 2 else 3
        // His skull volleys start 6-47 after the enrage (median 17).
        nextSkull = t + 6 + (Random.nextDouble().pow(1.6) * 41).toInt()
        yellowPlan()
    }

    /** Only Purple -> Yellow is a flight; after Yellow/Green he just chases. */
    private fun takeoff() {
        pinnedUntilBeam = false
        storm.armour(true)
        takeoffAt = -1; enrageAt = -1
        chasing = false
        if (lastCrush?.name == "Purple") { flyingToYellow = true; laterChase = false } else { laterChase = true; chaseSince = t }
    }

    /** A step into his hitbox (y..y+3.5) pushes him down by the overlap, to 169 at most. */
    private fun pushDown(pl: Pillar) {
        if (!::storm.isInitialized || handed) return
        val s = storm.pos
        if (s.x + 0.45 <= pl.minX || s.x - 0.45 >= pl.minX + 7 || s.z + 0.45 <= pl.minZ || s.z - 0.45 >= pl.minZ + 7) return
        if (s.y >= pl.bottom + 1 || s.y + 3.5 <= pl.bottom) return
        val y = max(169.0, pl.bottom - 3.5)
        if (y < s.y) storm.moveTo(Vec3(s.x, y, s.z))
    }

    private fun tickPillar(pl: Pillar) {
        if (pl.resetAt >= 0 && t >= pl.resetAt) {
            // Everything below goes at once; the pillar then hangs with its bottom at 183 for good
            // (181-186 by Goldor's line on Hypixel; Blocks.prepare's P3 state).
            pl.resetAt = -1
            for (y in 169 until RESET_BOTTOM) layer(pl, y, false)
            for (y in RESET_BOTTOM..189) layer(pl, y, true)
            pl.bottom = RESET_BOTTOM
            return
        }
        if (pl.spent) return
        if (pl.nextStep >= 0 && t >= pl.nextStep && pl.steps > 0) {
            if (pl.bottom > 169) { pl.bottom--; layer(pl, pl.bottom, true); pl.lastStep = t; pushDown(pl); piston(pl, SoundEvents.PISTON_EXTEND, pl.bottom) }
            pl.steps--
            pl.nextStep = if (pl.steps > 0) t + 4 else -1
            if (pl.bottom <= 169) { pl.steps = 0; pl.nextStep = -1; pl.floorAt = t }
        }
        if (pl.floorAt >= 0 && t >= pl.floorAt + 28) { pl.floorAt = -1; pl.raising = true; pl.nextStep = t }
        if (pl.raising && t >= pl.nextStep) {
            if (pl.bottom < 189) { layer(pl, pl.bottom, false); piston(pl, SoundEvents.PISTON_CONTRACT, pl.bottom); pl.bottom++; pl.nextStep = t + 4 }
            else { pl.raising = false; pl.lowerTo = 186; pl.nextStep = t + 24 }
        }
        if (pl.lowerTo >= 0 && t >= pl.nextStep) {
            if (pl.bottom > pl.lowerTo) { pl.bottom--; layer(pl, pl.bottom, true); pushDown(pl); piston(pl, SoundEvents.PISTON_EXTEND, pl.bottom); pl.nextStep = t + 4 } else pl.lowerTo = -1
        }
    }

    /** piston.extend / .contract (10, 0.49) at the pillar's west edge, z + 2, the layer that changed. */
    private fun piston(pl: Pillar, s: SoundEvent, y: Int) = Sim.sound(s, 3f, 1f, Vec3(pl.minX + 0.5, y + 0.5, pl.minZ + 2.5), net.minecraft.sounds.SoundSource.BLOCKS)

    /** Every footprint column polished diorite: Hypixel never puts the plain diorite edges back. */
    private fun layer(pl: Pillar, y: Int, solid: Boolean) {
        val st = if (solid) B.POLISHED_DIORITE.defaultBlockState() else B.AIR.defaultBlockState()
        for ((dx, dz) in footprint) Blocks.set(BlockPos(pl.minX + dx, y, pl.minZ + dz), st)
    }

    // ------------------------------------------------------------------ lightning and attacks

    private fun everyone(): List<Vec3> = listOfNotNull(Sim.player?.position()) + Party.bots().filter { it.entity != null }.map { it.pos }

    /**
     * Giga Lightning: for whoever hides under a pillar, a ring of 40 bolts of radius 7
     * round (minX + 4, minZ + 4); for anyone else 31 bolts within ±8 of them, one on them; with
     * Hypixel's own thunder (2, 1.4). Only you take the hit, unless you're under a pillar.
     */
    private fun giga() {
        val rings = HashSet<Pillar>()
        for (p in everyone()) {
            val under = pillars.firstOrNull { it.under(p) }
            if (under != null) { rings += under; continue }
            StormFx.bolt(p.x + Random.nextDouble(-0.5, 0.5), p.z + Random.nextDouble(-1.0, 1.0), p.y)
            repeat(30) { StormFx.bolt(p.x + Random.nextDouble(-8.0, 8.0), p.z + Random.nextDouble(-8.0, 8.0), p.y) }
        }
        for (pl in rings) for (i in 0 until 40) {
            val a = 2 * Math.PI * i / 40
            StormFx.bolt(pl.minX + 4 + 7 * cos(a), pl.minZ + 4 + 7 * sin(a), 169.0)
        }
        val p = Sim.player ?: return
        Sim.sound(SoundEvents.LIGHTNING_BOLT_THUNDER, 2f, 1.4f)
        if (pillars.any { it.under(p.position()) } || SimItems.cloaked) return
        StormFx.hit("Giga Lightning", gigaDamage, trueDamage = true)
        Stats.lightning()
    }

    private var nextFireball = -1
    private var nextSkull = -1
    private val frenzyPhase = Random.nextInt(10)

    /**
     * His other attacks. On the opening route (t ~75-424): a Lightning Fireball at a random player
     * every 37-70 ticks (median 44); its impact hits you for 75,000 near it and, about half the
     * time, seeds a Static Field there. From 6-47 after the enrage until crush 2:
     * a pair of wither skulls from his side heads every 5 ticks at the closest player.
     * While he's free after crush 1: his Frenzy hits you
     * within 6.3 blocks, about every 10 ticks (mostly ~2,000-2,200).
     */
    private fun attacks() {
        if (leg < ROUTE.size && t >= nextFireball && nextFireball >= 0) {
            fireball()
            val r = Random.nextDouble()
            nextFireball = t + if (r < 0.8) Random.nextInt(37, 52) else Random.nextInt(52, 71)
        }
        val free = crushes == 1 && !pinnedUntilBeam
        if (free && nextSkull >= 0 && t >= nextSkull) { skulls(); nextSkull = t + 5 }
        val p = Sim.player
        if (free && p != null && t % 10 == frenzyPhase && p.position().distanceTo(storm.pos.add(0.0, 1.5, 0.0)) <= 6.3 && !SimItems.cloaked) {
            val v = if (Random.nextDouble() < 0.7) Random.nextDouble(1990.0, 2170.0) else Random.nextDouble(2170.0, 14000.0)
            StormFx.hit("Frenzy", Math.round(v * 10) / 10.0)
        }
    }

    private fun fireball() {
        val targets = everyone().ifEmpty { return }
        val to = targets.random().add(0.0, 1.0, 0.0)
        val from = storm.pos.add(0.0, 0.8, 0.0)
        val dir = to.subtract(from).normalize()
        val f = StormFireball(Sim.level) { at -> impact(at) }
        f.setOwner(storm.e)
        f.snapTo(from.x, from.y, from.z, 0f, 0f)
        f.accelerationPower = 0.15
        f.deltaMovement = dir.scale(0.001)
        Sim.spawn(f)
    }

    private fun impact(at: Vec3) {
        if (Fight.phase !== this) return
        StormFx.burst(at)
        val p = Sim.player
        if (p != null && p.position().add(0.0, 0.9, 0.0).distanceTo(at) <= 3.5 && !SimItems.cloaked) StormFx.hit("Lightning Fireball", 75000.0)
        if (Random.nextDouble() < 0.55) fields += Field(at, Random.nextDouble() < 0.55, t)
    }

    private fun skulls() {
        val target = closest(storm.pos).add(0.0, 1.0, 0.0)
        val r = Math.toRadians(storm.e.yRot.toDouble())
        val side = Vec3(cos(r), 0.0, sin(r))
        for (s in listOf(-1.3, 1.3)) {
            val from = storm.pos.add(0.0, 2.2, 0.0).add(side.scale(s))
            val k = StormSkull(Sim.level)
            k.setOwner(storm.e)
            k.snapTo(from.x, from.y, from.z, 0f, 0f)
            k.accelerationPower = 0.1
            k.deltaMovement = target.subtract(from).normalize().scale(0.001)
            Sim.spawn(k)
        }
    }

    /**
     * A Static Field: 4 bolts on the spot, then 6 rings of 4 at ±1.8·k,
     * all X (corners) or all + (axes), at +3, 7, 12, 16, 21, 25. You take 10,800 once when a bolt
     * lands within 3 blocks of you, in that tick.
     */
    private inner class Field(val c: Vec3, val plus: Boolean, val start: Int) {
        var hit = false

        fun tick(): Boolean {
            val k = RING_T.indexOf(t - start)
            if (k < 0) return t - start < RING_T.last()
            val a = 1.8 * k
            val pts = when {
                k == 0 -> List(4) { 0.0 to 0.0 }
                plus -> listOf(a to 0.0, -a to 0.0, 0.0 to a, 0.0 to -a)
                else -> listOf(a to a, a to -a, -a to a, -a to -a)
            }
            val p = Sim.player?.position()
            for ((dx, dz) in pts) {
                val b = StormFx.bolt(c.x + dx, c.z + dz, c.y + 0.5)
                if (!hit && p != null && Math.hypot(b.x - p.x, b.z - p.z) <= 3.0 && Math.abs(b.y - p.y) < 3 && !SimItems.cloaked) {
                    hit = true
                    StormFx.hit("Static Field", 10800.0)
                }
            }
            return k < RING_T.size - 1
        }
    }

    private val fields = ArrayList<Field>()

    // ------------------------------------------------------------------ boss bar

    private val barPhase = Random.nextInt(20)

    /**
     * Hypixel resends the bar about once a second (changes land 0-24 ticks after the event): 1.0 from his first line; from crush 1 his health falls from ~0.95 to 0.45, where the
     * enrage comes; 0.45 while free; from crush 2 ~0.40 down to 0 at the death line.
     */
    private fun bar() {
        if (t % 20 != barPhase && !(t == 0 && Fight.previous !is P1Maxor)) return
        val v = when {
            deadAt >= 0 && t >= deadAt -> 0f
            crush2At >= 0 -> (0.40 * (1 - (t - crush2At).toDouble() / max(1, deadAt - crush2At))).toFloat()
            enragedAt >= 0 -> 0.45f
            crushes == 1 -> if (enrageAt > pinnedAt) (0.45 + 0.50 * (1 - (t - pinnedAt).toDouble() / (enrageAt - pinnedAt))).toFloat()
                else max(0.47, 0.95 - 0.005 * (t - pinnedAt)).toFloat()
            else -> 1f
        }
        BossBar.show("§c§lStorm", v)
    }

    // ------------------------------------------------------------------ the party

    private val botTo = HashMap<Party.Bot, Vec3>()
    private val role = HashMap<String, Party.Bot>()
    /** The fallback when he isn't crushed: the pillar being worked, and where the bait stands. */
    private var stuck: Pillar? = null
    private var yellowPress = -1

    private fun live() = Party.bots().filter { it.entity != null }

    /**
     * The bots play a real party's P2: Archer on
     * Yellow's pad, Tank on Purple's for the opening drop; the Healer pre-devs in P3; Berserk and
     * Mage about mid. They hide under Yellow and Purple for the lightning, then the lure stands
     * 35-45 out south of Purple, the Purple pad is held for the t 639 check (186 -> 181, crush 1
     * at 699) and Yellow's for the two checks from ~enrage + 35 (-> 171), with the bait on Yellow
     * at (46, 170, 66). A job of your class goes to the next bot in line, so P2 plays out whoever
     * you are.
     */
    private fun startBots() {
        if (!P3Sim.bots) return
        val bots = Party.bots()
        val taken = HashSet<Party.Bot>()
        fun pick(key: String, vararg order: DungeonClass) {
            val b = order.firstNotNullOfOrNull { c -> bots.firstOrNull { it.clazz == c && it !in taken } } ?: bots.firstOrNull { it !in taken } ?: return
            taken += b; role[key] = b
        }
        pick("yellow", DungeonClass.ARCHER, DungeonClass.BERSERK, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER)
        pick("purple", DungeonClass.TANK, DungeonClass.MAGE, DungeonClass.BERSERK, DungeonClass.HEALER, DungeonClass.ARCHER)
        pick("lure", DungeonClass.BERSERK, DungeonClass.MAGE, DungeonClass.HEALER, DungeonClass.ARCHER, DungeonClass.TANK)
        pick("other", DungeonClass.MAGE, DungeonClass.HEALER, DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.TANK)
        val p3 = role["other"]?.clazz == DungeonClass.HEALER
        val at = mapOf("yellow" to Vec3(32.5, 170.0, 94.5), "purple" to Vec3(114.5, 170.0, 94.5), "lure" to Vec3(73.5, 169.0, 60.5),
            "other" to if (p3) PREDEV else Vec3(73.5, 169.0, 45.5))
        Party.standAt(bots.map { b -> role.entries.firstOrNull { it.value === b }?.key?.let { at[it] } ?: Vec3(73.5, 169.0, 50.5) })
        if (p3) role.remove("other")
    }

    private fun send(key: String, to: Vec3) { role[key]?.let { botTo[it] = to } }

    private fun tickBots() {
        if (!P3Sim.bots || role.isEmpty()) return
        when (t) {
            40 -> { send("yellow", Vec3(32.5, 169.0, 86.5)); send("purple", Vec3(114.5, 169.0, 86.5)) }
            520 -> { send("yellow", Vec3(46.5, 169.0, 65.5)); send("lure", Vec3(45.0, 169.0, 67.0)); send("purple", Vec3(100.5, 169.0, 65.5)); send("other", Vec3(99.0, 169.0, 67.0)) }
            575 -> { send("yellow", YELLOW_WAIT);send("lure", LURE); send("purple", PURPLE_WAIT); send("other", Vec3(58.0, 169.0, 68.0)) }
            635 -> send("purple", Vec3(114.5, 170.0, 94.5))
            641 -> send("purple", PURPLE_WAIT)
        }
        if (yellowPress >= 0 && crushes == 1) {
            if (t == yellowPress - 12) send("yellow", Vec3(32.5, 170.0, 94.5))
            if (t == yellowPress + 22) send("yellow", YELLOW_WAIT)
        }
        // The bait walks onto Yellow (the closest one when he gets there); the lure keeps clear.
        if (crushes == 1 && t == takeoffAt) { send("lure", Vec3(60.0, 169.0, 80.0)); send(baitKey(), BAIT_ON) }
        fallback()
        // Dead: everyone leaps onto the SS player 5-20 after the line.
        if (deadAt >= 0 && t == deadAt + 8) { botTo.clear(); role.clear(); Party.standAt(StormEnd.SS_LANDING); return }
        for ((b, to) in botTo) {
            val e = b.entity ?: continue
            val d = to.subtract(b.pos)
            val len = d.length()
            if (len < 1e-3) continue
            // Walking (Hypixel speed ~0.6 a tick); a long way is an etherwarp/leap.
            b.pos = if (len <= 0.6 || len > 30) to else b.pos.add(d.scale(0.6 / len))
            if (len > 0.6) b.yaw = Math.toDegrees(Math.atan2(-d.x, d.z)).toFloat()
            e.snapTo(b.pos.x, b.pos.y, b.pos.z, b.yaw, 0f)
            e.yHeadRot = b.yaw; e.yBodyRot = b.yaw
        }
    }

    /** Yellow's pad for the first check >= enrage + 35 and the next (181 -> 171 before he lands). */
    private fun yellowPlan() {
        if (!P3Sim.bots || lastCrush !== purple || yellow.spent) return
        var c = t + 35
        while (c % 20 != 19) c++
        yellowPress = c
    }

    /** Who baits him at Yellow: the Mage, or the lure when you're the Mage. */
    private fun baitKey() = if (role.containsKey("other")) "other" else "lure"

    /**
     * When he's been chasing 60 ticks without a crush (you took the aggro, a press was missed),
     * the party works the next unspent pillar: a bait on its far side holds him in its zone and
     * someone stays on its pad, stepping it down onto him every check until the floor pins him
     * (a pillar stepped onto him pushes him down, to the floor if it keeps going).
     */
    private fun fallback() {
        if (deadAt >= 0 || pinnedUntilBeam || flyingToYellow || !(chasing || laterChase) || t - chaseSince < 60) return
        if (stuck == null) {
            val pl = listOf(purple, yellow, pillars[2]).firstOrNull { !it.spent } ?: return
            stuck = pl
            val c = pl.centre
            val dir = Vec3(c.x - storm.pos.x, 0.0, c.z - storm.pos.z).let { if (it.lengthSqr() < 1e-4) Vec3(1.0, 0.0, 0.0) else it.normalize() }
            send(baitKey(), Vec3(c.x + dir.x * 1.5, 170.0, c.z + dir.z * 1.5))
            send("purple", Vec3(pl.pad!!.minX + 3.5, 170.0, pl.pad.minZ + 3.5))
        }
    }

    fun status() = when {
        deadAt >= 0 -> "Storm dead"
        pinnedUntilBeam -> "Pinned (crush $crushes)"
        else -> "Crushes $crushes/2" + pillars.filter { it.pad != null }.joinToString("") { " ${it.name[0]}${if (it.spent) "x" else it.bottom}" }
    }

    companion object {
        /** Where crushed pillars hang from their reset on (Blocks.prepare's P3 world). */
        const val RESET_BOTTOM = 183
        /** The Healer's pre-dev spot in P3. */
        val PREDEV = Vec3(56.5, 114.0, 95.5)
        /** The lure: 35-45 from his parking spot on a line through Purple's zone. */
        val LURE = Vec3(94.5, 165.0, 92.0)
        /** Purple's presser waiting off the square, further from him than the lure. */
        val PURPLE_WAIT = Vec3(116.0, 170.0, 103.0)
        /** Yellow's presser waiting just off the square (z < 91). */
        val YELLOW_WAIT = Vec3(32.5, 170.0, 88.5)
        /** The bait at crush 2: standing on Yellow. */
        val BAIT_ON = Vec3(46.0, 170.0, 66.0)
        val RING_T = listOf(0, 3, 7, 12, 16, 21, 25)
    }
}
