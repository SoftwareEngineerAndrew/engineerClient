package com.engineerclient.p3sim

import net.minecraft.world.entity.EntityTypes
import com.engineerclient.index
import com.engineerclient.EngineerClient
import com.google.gson.JsonParser
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LightningBolt
import net.minecraft.world.entity.item.PrimedTnt
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin
import kotlin.random.Random

// ====================================================================== P4

/**
 * P4, Necron, as the fastest script, as Hypixel runs it since the boss update (2026-10-05/06; one
 * stun where there were two): his intro lines 42 apart from 0, volley 1 at V1 = 20, off mid at
 * L1 = 80 (a 7-tick 0.25 b/t sidestep 45-75° off south, 3 held, then 0.49 b/t at the closest
 * player) and teleported back at B1 = 98. Volley 2 155 after volley 1, at the platform that goes
 * at volley 2 + 100; L2 = volley 2 + 60 (the pair of skulls), B2 = L2 + 5. His one ARGH is the
 * taunt + 82 (270: 269-273 however late he was back), "Let's make some space!" 62 after it; the
 * death throes from ARGH + 40, ten TNT at ARGH + 100, gone at + 119, the end-of-run chat at + 146.
 * Nuclear Frenzy pulses on its 20-tick grid while he waits at mid (B1 to volley 2, B2 to the
 * throes). "All this, for nothing..." and "my master" are gone.
 *
 * Measured on 176 post-update F7 Better PF recordings and 3 Boss Recorder files (server gameTime).
 * The script doesn't wait for damage (the sim has no boss health): B1 and B2 are the measured
 * medians of fast runs, so every run is the fastest possible Necron.
 */
class P4Necron(val fromP3: Boolean = false) : Fight.Phase("P4") {
    override val restart get() = Fight.Start.P4
    private lateinit var necron: BossWither

    companion object {
        private val MID = Vec3(54.0, 66.0, 76.0)
        /** His intro lines, [INTRO] apart from his first (62 before the update). */
        private const val INTRO = 42
        private const val V1 = 20
        private const val L1 = 80; private const val B1 = L1 + 18
        /** Nuclear Frenzy's 20-tick grid: pulses on n ≡ [FRENZY] (mod 20) (5 before the update). */
        private const val FRENZY = 8
        private val TAUNT1 = max(3 * INTRO + 62, B1)
        private val ARGH1 = TAUNT1 + 82
        private val SPACE = ARGH1 + 62
        /** Volley 2: 155 after volley 1 (p10-p90 152-161), no longer after the ARGH. */
        private const val V2 = V1 + 155
        private const val L2 = V2 + 60; private const val B2 = L2 + 5
        /** The side platform goes 100 server ticks after volley 2's first fireball. */
        private const val BREAK = V2 + 100
        /** The death throes: 24 wither.hurt pulses 5 apart from ARGH + 40, an explosion on every other one. */
        private val THROES = ARGH1 + 40
        private val TNT = ARGH1 + 100
        /** His removal (Goldor's body goes in the same tick). */
        val GONE = ARGH1 + 119
        private val STATS = ARGH1 + 146
        /** Fireballs leave his centre head (2.97 up) at 0.5 b/t, pitched 11.8° down, and speed up as vanilla's do. */
        const val HEAD_Y = 2.97
        val PITCH = Math.toRadians(11.8)
        /** Conjecture: only players near mid report Nuclear Frenzy. */
        const val FRENZY_RANGE = 12.0
        val TAUNTS = listOf("Sometimes when you have a problem, you just need to destroy it all and start again.", "WITNESS MY RAW NUCLEAR POWER!")
        /** The five platforms he can break (never S), by their centres. */
        val PLATFORMS = mapOf("N" to Vec3(54.0, 63.0, 42.0), "NW" to Vec3(28.0, 63.0, 50.0), "NE" to Vec3(80.0, 63.0, 50.0), "SW" to Vec3(28.0, 63.0, 102.0), "SE" to Vec3(80.0, 63.0, 102.0))
        /** The lava pillars and how often each one falls in a run (the first one is any of them). */
        val PILLARS = listOf(Triple(92, 76, 0.75), Triple(82, 48, 0.73), Triple(26, 48, 0.72), Triple(54, 38, 0.71), Triple(26, 104, 0.70), Triple(82, 104, 0.69), Triple(16, 76, 0.52))
        const val CLASSES = "Healer,Berserk,Archer,Tank,Mage"
    }

    /** The sidestep's direction: 45-75° left or right of south, rising or sinking a little. */
    private val side = Random.nextDouble(45.0, 75.0).let { if (Random.nextBoolean()) it else -it }.let { Math.toRadians(it) }
        .let { Vec3(-sin(it), Random.nextDouble(-0.9, 0.9), cos(it)) }
    private val taunt1 = TAUNTS.random()
    private val platform = PLATFORMS.keys.random()
    /** Hypixel sends the bar every ~20 ticks on its own phase. */
    private val barPhase = Random.nextInt(20)
    private val spawned = ArrayList<Entity>()
    private var tnts = emptyList<Entity>()

    override fun start() {
        val p = Sim.player
        if (!fromP3 && p != null) {
            Blocks.finish("p3end")
            val s = Spots.P4
            Sim.tp(p, s.x, s.y, s.z, s.yaw, s.pitch)
        }
        Party.startP4(fromP3)
        necron = BossWither("Necron", MID)
        Sim.boss("Necron", "You went further than any human before, congratulations.")
        // The same bar as Goldor's, renamed; 0 through the intro.
        BossBar.show("§c§lNecron", 0f)
        pillars = Pillars.plan()
        storm = Storm.plan()
    }

    override fun stop() {
        spawned.forEach { it.discard() }; spawned.clear()
        if (::necron.isInitialized) necron.remove()
    }

    override fun tick() {
        when (t) {
            INTRO -> Sim.boss("Necron", "I'm afraid, your journey ends now.")
            2 * INTRO -> Sim.boss("Necron", "Goodbye.")
            3 * INTRO -> Sim.boss("Necron", "That's a very impressive trick. I guess I'll have to handle this myself.")
            TAUNT1 -> Sim.boss("Necron", taunt1)
            ARGH1 -> Sim.boss("Necron", "ARGH!")
            SPACE -> Sim.boss("Necron", "Let's make some space!")
            BREAK -> breakPlatform()
            L2 -> skulls()
            TNT -> tnts = List(10) { spawn(PrimedTnt(Sim.level, necron.pos.x, necron.pos.y, necron.pos.z, null)) }
            TNT + 62 -> tnts.forEach { it.discard() }
            GONE - 20 -> necron.dieAnim()
            GONE -> necron.remove()
            STATS -> stats(first = true)
            STATS + 3 -> { stats(first = false); Sim.note("Done. §fMenu§7 to go again.") }
        }
        move()
        if (t % 20 == barPhase) BossBar.progress(round(health() * 100) / 100f)
        if (t >= V1 && t < V1 + 80 && (t - V1) % 10 == 0) fire(0.0)
        if (t >= V2 && t < V2 + 80 && (t - V2) % 10 == 0) fire(yawTo(PLATFORMS.getValue(platform)))
        if (t % 20 == FRENZY && (t in B1 + 1 until V2 || t in B2 + 1 until THROES)) frenzy()
        if (t >= THROES && (t - THROES) % 5 == 0 && (t - THROES) / 5 < 24) throe((t - THROES) / 5)
        pillars.tick(t)
        storm.tick(t)
    }

    /**
     * His health as the bar shows it in recorded runs: 0 through the intro, full when he
     * leaves, 0.8 by the time he is back (B1), down to 0.25 within ~15 of volley 2, 0.05 by B2 and 0
     * when the throes start.
     */
    private fun health(): Float = when {
        t < L1 -> 0f
        t < B1 -> 1f - 0.2f * (t - L1) / (B1 - L1)
        t < V2 - 5 -> 0.8f
        t < V2 + 10 -> 0.8f - 0.55f * (t - V2 + 5) / 15
        t < L2 -> 0.25f
        t < B2 -> 0.25f - 0.2f * (t - L2) / (B2 - L2)
        t < THROES -> 0.05f
        else -> 0f
    }

    /** Sidestep, hold, fly at the closest player; teleported back to exactly mid at B. Faces south for volley 1 and the platform for volley 2. */
    private fun move() {
        val target = PLATFORMS.getValue(platform)
        when {
            t in L1 until L1 + 7 -> necron.moveTo(necron.pos.add(side.scale(0.25)), necron.pos.add(side.x, 0.0, side.z))
            t in L1 + 7 until L1 + 10 -> {}
            t in L1 + 10 until B1 -> necron.step(chase(), 0.49)
            t in L2 until B2 -> necron.step(chase(), 0.63)
            t == B1 || t == B2 -> {
                necron.moveTo(MID, closest(MID))
                // The teleport back plays enderman.teleport (v10, pitch 31/63) at mid.
                Sim.sound(SoundEvents.ENDERMAN_TELEPORT, 10f, 31 / 63f, MID)
            }
            t in V1 until V1 + 80 -> necron.moveTo(MID, MID.add(0.0, 0.0, 10.0))
            t in V2 until L2 -> necron.moveTo(MID, target)
            t < GONE && t % 5 == 0 -> necron.moveTo(necron.pos, closest(necron.pos))
        }
    }

    private fun chase(): Vec3 = closest(necron.pos).add(0.0, 2.0, 0.0)

    private fun yawTo(p: Vec3) = Math.atan2(-(p.x - MID.x), p.z - MID.z)

    private fun <T : Entity> spawn(e: T): T { Sim.spawn(e); spawned += e; return e }

    /**
     * A fireball: a real one, from his centre head, 0.5 b/t at [yaw] (radians, 0 = south),
     * 11.8° down, gaining vanilla's 0.1 a tick (inertia 0.95), as the recorded velocities show. No
     * launch sound; on a block it's an explosion of radius 0 with explode v4 p0.6-0.8.
     */
    private fun fire(yaw: Double) {
        val dir = Vec3(-sin(yaw) * cos(PITCH), -sin(PITCH), cos(yaw) * cos(PITCH))
        val from = necron.pos.add(0.0, HEAD_Y, 0.0)
        spawn(Fireball(Sim.level).apply { snapTo(from.x, from.y, from.z, 0f, 0f); deltaMovement = dir.scale(0.5) })
    }

    /** The pair of wither skulls he fires as he leaves mid for trip 2 (L2 + 0), from his side heads at the closest player. */
    private fun skulls() {
        val at = closest(necron.pos).add(0.0, 1.0, 0.0)
        val d = at.subtract(necron.pos)
        val right = Vec3(-d.z, 0.0, d.x).let { if (it.lengthSqr() < 1e-4) Vec3(1.0, 0.0, 0.0) else it.normalize() }
        for (s in listOf(-1.3, 1.3)) {
            val from = necron.pos.add(right.scale(s)).add(0.0, 2.2, 0.0)
            val dir = at.subtract(from).normalize()
            spawn(Skull(Sim.level).apply { snapTo(from.x, from.y, from.z, 0f, 0f); deltaMovement = dir.scale(0.1) })
        }
    }

    /** One whole side platform (y 59-63) to air in a single tick, volley 2's target, 100 after the volley. */
    private fun breakPlatform() {
        Data.platforms[platform]?.forEach { (pos, s) -> Blocks.set(pos, s) }
    }

    /**
     * Nuclear Frenzy: a pulse every 20 ticks at mid while he waits, explode v30 p31/63 and
     * wither.ambient v30 p44/63 at him (heard by all); the chat line only for players near him (it
     * isn't lethal on its own, so it doesn't go through Masks).
     */
    private fun frenzy() {
        Sim.sound(SoundEvents.GENERIC_EXPLODE, 30f, 31 / 63f, necron.pos)
        Sim.sound(SoundEvents.WITHER_AMBIENT, 30f, 44 / 63f, necron.pos)
        val p = Sim.player ?: return
        if (p.position().distanceTo(MID) > FRENZY_RANGE || SimItems.cloaked) return
        Sim.chat("§cNecron's§r§7 Nuclear Frenzy hit you for §r§c57,600§r§7 damage.")
    }

    /**
     * Pulse [i] of his death throes (ARGH + 40 to + 155, on past his removal): wither.hurt v15 p1 at
     * him, and on every other pulse explode v15 p31/63 (the explosion particle with it is conjecture).
     */
    private fun throe(i: Int) {
        Sim.sound(SoundEvents.WITHER_HURT, 15f, 1f, necron.pos)
        if (i % 2 == 0) {
            Sim.sound(SoundEvents.GENERIC_EXPLODE, 15f, 31 / 63f, necron.pos)
            val at = necron.pos.add(0.0, 1.5, 0.0)
            Sim.level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y, at.z, 1, 0.0, 0.0, 0.0, 0.0)
        }
    }

    // ------------------------------------------------------------------ the end-of-run chat

    /**
     * The two blocks Hypixel sends at ARGH + 146 and 3 later: the summary with the experience
     * lines, then the "Floor VII Stats" block. Each line is centred with
     * spaces = ceil((160 - width / 2) / 4). The numbers are typical of recorded runs (score
     * 298-311 S+, Catacombs XP 46-50k, class XP 0.76 of it, team bonus a quarter of that, bits in
     * most runs); a full run's time when the sim knows it (started at P1).
     */
    private fun stats(first: Boolean) {
        val bar = "§a§l" + "▬".repeat(64)
        val classes = CLASSES.split(",")
        val mine = classes[P3Sim.classS.index.coerceIn(0, 4)]
        val total = Stats.runTicks()
        val scoreLine = "Team Score: §r§a${score} §r§f(§r§b§lS+§r§f)"
        val defeated = if (total > 0) "§r§c☠ §r§eDefeated §r§cMaxor, Storm, Goldor, and Necron §r§ein §r§a%02dm %02ds".format(total / 1200, total / 20 % 60) else null
        val out = ArrayList<String>()
        out += bar
        if (first) {
            out += centre("§r§cThe Catacombs §r§8- §r§eFloor VII"); out += ""; out += centre(scoreLine)
            defeated?.let { out += centre(it) }
            out += centre("§6> §e§lEXTRA STATS §6<")
            if (bits > 0) out += centre("§r§8+§r§b$bits Bits")
            out += centre("§r§8+§r§3${xp(cataXp)} Catacombs Experience")
            out += centre("§r§8+§r§3${xp(cataXp * 0.76)} $mine Experience")
            for (c in classes.filter { it != mine }.shuffled()) out += centre("§r§8+§r§3${xp(cataXp * 0.19)} $c Experience §r§b(Team Bonus)")
        } else {
            out += centre("§r§cThe Catacombs §r§8- §r§eFloor VII Stats"); out += ""; out += centre(scoreLine)
            defeated?.let { out += centre(it) }
            out += ""
            out += centre("Total Damage as $mine: §r§a${"%,d".format(java.util.Locale.US, Random.nextLong(600_000_000, 900_000_000))}")
            out += centre("Ally Healing: §r§a${"%,d".format(java.util.Locale.US, if (mine == "Healer") Random.nextInt(50_000, 150_000) else Random.nextInt(800, 4000))}")
            out += centre("Enemies Killed: §r§a${Random.nextInt(120, 180)}")
            out += centre("Deaths: §r§c0")
            out += centre("Secrets Found: §r§b${Random.nextInt(3, 13)}")
            out += ""
        }
        out += bar
        out.forEach { Sim.chat(it) }
    }

    private val score = Random.nextInt(300, 312)
    private val cataXp = Random.nextDouble(46_500.0, 50_000.0)
    private val bits = if (Random.nextDouble() < 0.72) Random.nextInt(45, 80) else 0

    private fun xp(v: Double) = "%,.1f".format(java.util.Locale.US, v).removeSuffix(".0")

    /** Hypixel's centring: a §f, then spaces to put the text's middle at 160 px (4 px a space). */
    private fun centre(text: String): String {
        var w = 0; var bold = false; var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '§' && i + 1 < text.length) { val f = text[i + 1]; if (f == 'l') bold = true else if (f in "0123456789abcdefr") bold = false; i += 2; continue }
            w += when (c) {
                '!', ',', '.', ':', ';', '\'', '|' -> 2
                'i' -> 2; 'l', '`' -> 3; ' ', 'I', '[', ']', '"' -> 4
                't', 'f', 'k', '(', ')', '<', '>', '*', '{', '}', '☠' -> 5
                '@' -> 7
                else -> 6
            } + if (bold) 1 else 0
            i++
        }
        return "§f" + " ".repeat(ceil((160 - w / 2.0) / 4).toInt().coerceAtLeast(0)) + text
    }

    fun status() = if (t < GONE) "Necron ${t / 20}s" else "Necron dead"

    // ------------------------------------------------------------------ entities

    /** A fireball that only flies: no explosion, no entity hits, can't be hit back. Gone after 5 s. */
    private class Fireball(level: ServerLevel) : LargeFireball(EntityTypes.FIREBALL, level) {
        private var life = 100
        override fun tick() { super.tick(); if (--life <= 0) discard() }
        override fun onHit(result: HitResult) {
            if (result.type == HitResult.Type.BLOCK) {
                val l = level() as ServerLevel
                l.sendParticles(ParticleTypes.EXPLOSION, x, y, z, 1, 0.0, 0.0, 0.0, 0.0)
                l.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE.value(), net.minecraft.sounds.SoundSource.BLOCKS, 4f, Random.nextInt(38, 52) / 63f)
            }
            discard()
        }
        override fun canHitEntity(entity: Entity) = false
        override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float) = false
    }

    /** A wither skull that only flies (puff on a block, no explosion). */
    private class Skull(level: ServerLevel) : WitherSkull(EntityTypes.WITHER_SKULL, level) {
        private var life = 100
        override fun tick() { super.tick(); if (--life <= 0) discard() }
        override fun onHit(result: HitResult) {
            (level() as ServerLevel).sendParticles(ParticleTypes.SMOKE, x, y, z, 6, 0.2, 0.2, 0.2, 0.01)
            discard()
        }
        override fun canHitEntity(entity: Entity) = false
        override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float) = false
    }

    // ------------------------------------------------------------------ lava pillars

    private lateinit var pillars: Pillars
    private lateinit var storm: Storm

    /**
     * The lava pillars: a source column falls from y85 at one random pillar from about L1 + 15 (one
     * block every ~10 ticks, flowing lava around it) and then eats into the platform under it; as he
     * leaves mid the second time (L2) the others start too, one a tick, each with its measured odds. The frames are one recorded run's ([Blocks] "p4": the corner pillar
     * at (82, 48), and the side one at (16, 76)), mirrored onto the other pillars, as the arena is
     * symmetric about x = 54 and z = 76; (54, 38) gets the falling column only.
     */
    private class Pillars(val frames: List<Triple<Int, BlockPos, BlockState>>) {
        private var next = 0
        fun tick(t: Int) {
            while (next < frames.size && frames[next].first <= t) { Blocks.set(frames[next].second, frames[next].third); next++ }
        }

        companion object {
            fun plan(): Pillars {
                val first = PILLARS.random()
                val out = ArrayList<Triple<Int, BlockPos, BlockState>>()
                out += pillar(first.first, first.second, L1 + 15 + Random.nextInt(-3, 5))
                var at = L2 + Random.nextInt(-2, 5)
                for (p in PILLARS.shuffled()) if (p !== first && Random.nextDouble() < p.third) out += pillar(p.first, p.second, at++)
                out.sortBy { it.first }
                return Pillars(out)
            }

            private fun pillar(x: Int, z: Int, start: Int): List<Triple<Int, BlockPos, BlockState>> {
                val side = z == 76
                val t = if (side) Data.sideFall else Data.cornerFall
                val (sx, sz) = if (side) 16 to 76 else 82 to 48
                val flipX = (x - 54) * (sx - 54) < 0; val flipZ = (z - 76) * (sz - 76) < 0
                return t.mapNotNull { (dt, p, s) ->
                    if (x == 54 && p.y < 64) return@mapNotNull null
                    val dx = p.x - sx; val dz = p.z - sz
                    val nx = if (x == 54) x + dx else x + if (flipX) -dx else dx
                    val nz = if (x == 54) z + dz else z + if (flipZ) -dz else dz
                    Triple(start + dt, BlockPos(nx, p.y, nz), s)
                }
            }
        }
    }

    // ------------------------------------------------------------------ the lightning

    /**
     * The lightning: from n ≈ 22 (61 before the boss update), 30 bolts about 10 ticks apart strike
     * around one of seven light columns (each about as often), the column's sea lanterns climbing
     * through its iron blocks until 13 ticks after the last bolt. Frames and bolt times are one
     * recorded run per column kind; where each bolt lands around the column (median 8.6 blocks
     * away, at y 63) is drawn at random.
     */
    private class Storm(val bolts: List<Int>, val frames: List<Triple<Int, BlockPos, BlockState>>, val centre: Vec3) {
        private var nextBolt = 0; private var next = 0
        fun tick(t: Int) {
            while (next < frames.size && frames[next].first <= t) { Blocks.set(frames[next].second, frames[next].third); next++ }
            while (nextBolt < bolts.size && bolts[nextBolt] <= t) {
                nextBolt++
                val a = Random.nextDouble(Math.PI * 2); val r = 12 * kotlin.math.sqrt(Random.nextDouble())
                val bolt = LightningBolt(EntityTypes.LIGHTNING_BOLT, Sim.level)
                bolt.setVisualOnly(true)
                bolt.snapTo(centre.x + r * cos(a), 63.0, centre.z + r * sin(a), 0f, 0f)
                Sim.spawn(bolt)
            }
        }

        companion object {
            fun plan(): Storm {
                val (x0, z0, kind) = Data.columns.randomOrNull() ?: return Storm(emptyList(), emptyList(), Vec3.ZERO)
                val s = Data.storms[kind] ?: return Storm(emptyList(), emptyList(), Vec3.ZERO)
                val start = 22
                val frames = ArrayList<Triple<Int, BlockPos, BlockState>>()
                for ((dt, y, st) in s.frames) for (dx in 0..2) for (dz in 0..2) frames += Triple(start + dt, BlockPos(x0 + dx, y, z0 + dz), st)
                return Storm(s.bolts.map { start + it }, frames, Vec3(x0 + 1.5, 63.0, z0 + 1.5))
            }
        }
    }

    // ------------------------------------------------------------------ data

    /** `p4-necron.json` and the pillar frames of the "p4" animation. */
    private object Data {
        class StormData(val bolts: List<Int>, val frames: List<Triple<Int, Int, BlockState>>)

        private val root by lazy {
            runCatching {
                P4Necron::class.java.getResourceAsStream("/assets/engineerclient/p3sim/p4-necron.json")!!.use { JsonParser.parseString(it.readBytes().toString(Charsets.UTF_8)).asJsonObject }
            }.onFailure { EngineerClient.logger.warn("[p3sim] p4-necron.json", it) }.getOrNull()
        }

        private val states = HashMap<String, BlockState>()
        private fun state(s: String) = states.getOrPut(s) { Arena.parse(if (':' in s) s else "minecraft:$s") }

        val platforms: Map<String, List<Pair<BlockPos, BlockState>>> by lazy {
            root?.getAsJsonObject("platforms")?.entrySet()?.associate { (k, v) ->
                k to v.asJsonArray.map { e -> val a = e.asJsonArray; BlockPos(a[0].asInt, a[1].asInt, a[2].asInt) to state(a[3].asString) }
            } ?: emptyMap()
        }

        val columns: List<Triple<Int, Int, String>> by lazy {
            root?.getAsJsonObject("columns")?.entrySet()?.map { (_, v) -> val a = v.asJsonArray; Triple(a[0].asInt, a[1].asInt, a[2].asString) } ?: emptyList()
        }

        val storms: Map<String, StormData> by lazy {
            val lantern = state("sea_lantern"); val iron = state("iron_block")
            root?.getAsJsonObject("storms")?.entrySet()?.associate { (k, v) ->
                val o = v.asJsonObject
                k to StormData(o.getAsJsonArray("bolts").map { it.asInt },
                    o.getAsJsonArray("frames").map { e -> val a = e.asJsonArray; Triple(a[0].asInt, a[1].asInt, if (a[2].asString == "s") lantern else iron) })
            } ?: emptyMap()
        }

        /** The "p4" frames nearest to pillar ([x], [z]), lava only, dt from that pillar's first frame. */
        private fun fall(x: Int, z: Int): List<Triple<Int, BlockPos, BlockState>> {
            val f = Blocks.anim("p4")?.frames ?: return emptyList()
            val mine = f.filter { fr ->
                !fr.state.isAir && PILLARS.minBy { (px, pz) -> (fr.pos.x - px) * (fr.pos.x - px) + (fr.pos.z - pz) * (fr.pos.z - pz) }.let { it.first == x && it.second == z }
            }
            val t0 = mine.minOfOrNull { it.dt } ?: return emptyList()
            return mine.map { Triple(it.dt - t0, it.pos, it.state) }
        }

        val cornerFall by lazy { fall(82, 48) }
        val sideFall by lazy { fall(16, 76) }
    }
}
