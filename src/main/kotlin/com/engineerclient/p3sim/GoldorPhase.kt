package com.engineerclient.p3sim

import com.engineerclient.rotation.P3Sections
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.monster.Giant
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * P3: Goldor. Everything as measured in `docs/mechanics/goldor.md` (n = server ticks since "Who
 * dares trespass into my domain?"):
 *  - four sections of terminals, levers and a device (7/8/7/7), counted in chat as Hypixel does;
 *    a later section's device done early counts for that section later;
 *  - between sections a gate (blown with Superboom or a Dungeonbreaker, only once its section is
 *    in progress; else it goes 5 s after the section ends) and a door that opens at the later of
 *    the section's last completion and its gate;
 *  - death ticks at n = 60k-1: anyone in the next section ahead (and S4 while S1 is in progress)
 *    is hit;
 *  - Goldor walks the track at 0.06/tick from (80, 119, 40), sprints to the section in progress
 *    (0.6) when he is still in the one whose door just opened, and after "The Core entrance is
 *    opening!" flies into the core (0.8) once everyone is inside (4 ticks after the opening at the
 *    earliest), and dies; his Frenzy hits you every 10 ticks 2-14 blocks from him there;
 *  - Necron's first line 82 ticks (81-83) after Goldor's death, then P4.
 * [from] 1-4 starts at that section (the earlier ones done), 5 at the core opening.
 */
class GoldorPhase(val from: Int, val arrived: Boolean = false) : Fight.Phase("P3") {
    override val restart get() = when (from) { 2 -> Fight.Start.S2; 3 -> Fight.Start.S3; 4 -> Fight.Start.S4; 5 -> Fight.Start.CORE; else -> Fight.Start.P3 }

    val stations = Station.all()
    /** The section in progress (1-4), 5 once the core is open. */
    var section = 1
        private set
    /** n: server ticks since Goldor's first line. */
    val n get() = t + nOffset
    private var nOffset = 0
    private val sectionStart = IntArray(6)
    private val sectionEnd = IntArray(6) { -1 }
    private val gateDown = BooleanArray(5)
    private val gateAt = IntArray(5) { -1 }
    private val doorOpen = BooleanArray(5)
    private var autoGateAt = IntArray(5) { -1 }
    private var coreAt = -1
    private var everyoneInAt = -1
    private var deadAt = -1
    private var arrivedAt = -1
    private var necronAt = -1
    private var deaths = 0
    private var pendingLine = -1
    /** A taunt for the next 62-tick slot (after a terminal of yours), or null. */
    private var pendingTaunt: String? = null

    val devices = Devices(this)
    val goldor = Goldor()

    fun station(section: Int, label: String) = stations.first { it.section == section && it.label == label }
    fun count(section: Int) = stations.count { it.section == section && it.done }

    override fun start() {
        stations.forEach { it.spawnStands() }
        devices.start()
        Stats.reset(from)
        val startN = when (from) { 2 -> 252; 3 -> 438; 4 -> 641; 5 -> 797; else -> 0 }
        nOffset = startN
        // Earlier sections: done, their gates and doors open, as if a party had just done them.
        for (s in 1 until from.coerceAtMost(5)) {
            stations.filter { it.section == s }.forEach {
                it.done = true; it.doneBy = "-"; it.refreshStands()
                if (it.kind == Station.Kind.DEVICE) devices.shownDone(it.label)
                it.lever?.let { l -> Blocks.get(l)?.takeIf { b -> b.hasProperty(LeverBlock.POWERED) }?.let { b -> Blocks.set(l, b.setValue(LeverBlock.POWERED, true)) } }
            }
            gateDown[s] = true; doorOpen[s] = true
            if (s <= 3) { Blocks.finish("gate${s}${s + 1}"); Blocks.finish("door$s") }
            sectionEnd[s] = startN
        }
        if (from >= 2) Blocks.finish("p3start")
        section = from.coerceAtMost(5)
        sectionStart[section] = startN
        goldor.spawn(startN)
        Party.startP3(this)
        Sim.player?.let { player ->
            if (!arrived) {
                val spot = Spots.p3Start(from)
                Sim.tp(player, spot.x, spot.y, spot.z, spot.yaw, spot.pitch)
                SimItems.giveHotbar(player, p3 = true)
            } else {
                // From Storm: the Superboom goes back in slot 1.
                player.inventory.setItem(0, SimItems.SUPERBOOM)
            }
        }
        if (from == 1) {
            Sim.boss("Goldor", "Who dares trespass into my domain?")
            // The red pad's drop hole: its frames run 24-30 ticks after this line.
            Blocks.play("p3start")
        } else if (from == 5) {
            openCore()
        } else {
            Sim.note("Starting at §fS$from§7 (n = $startN, the median fast run's).")
        }
    }

    override fun stop() {
        Terminals.closeAll()
        devices.stop()
        goldor.remove()
    }

    override fun tick() {
        val n = n
        devices.tick()
        // Goldor's intro, on its 62-tick grid.
        if (from == 1) when (t) {
            62 -> Sim.boss("Goldor", "Little ants, plotting and scheming, thinking they are invincible...")
            124 -> Sim.boss("Goldor", "I won't let you break the factory core, I gave my life to my Master.")
            186 -> Sim.boss("Goldor", "No one matches me in close quarters.")
        }
        if (pendingLine >= 0 && n % 62 == 0) {
            Sim.boss("Goldor", listOf("The little ants have a brain it seems.", "I will replace that gate with a stronger one!", "YOUR END IS NEAR!!")[pendingLine.coerceIn(0, 2)])
            pendingLine = -1
        } else if (pendingTaunt != null && n % 62 == 0) {
            Sim.boss("Goldor", pendingTaunt!!)
            pendingTaunt = null
        }
        // Stand names refresh on a 20-tick grid.
        if (n % 20 == 0) stations.forEach { it.refreshStands() }
        // Gates that open by themselves 5 s after their section ended.
        for (s in 1..3) if (autoGateAt[s] >= 0 && n >= autoGateAt[s] && !gateDown[s]) blowGate(s, null)
        // Death ticks: the chat line lands at n = 60k-1 (goldor.md, death ticks).
        if (section <= 4 && n % 60 == 59) deathTick()
        goldor.tick(this)
        // Every 40 ticks Goldor carves an 11x11x11 box around himself; every 200 a TNT cube near the section in progress goes.
        if (n % 40 == 37 && !goldor.flying) carve()
        if (n >= 237 && (n - 237) % 200 == 0 && section <= 4) tnt()
        // The core: everyone in, then Goldor flies in and dies.
        if (section == 5) coreTick()
        Party.tickP3(this)
    }

    // ------------------------------------------------------------------ completions

    fun complete(st: Station, by: String) {
        if (st.done) return
        val inProgress = st.section == section
        val early = st.kind == Station.Kind.DEVICE && st.section > section
        if (!inProgress && !early) return
        st.done = true
        st.doneBy = by
        st.doneAt = n
        val what = when (st.kind) { Station.Kind.TERMINAL -> "activated a terminal!"; Station.Kind.LEVER -> "activated a lever!"; Station.Kind.DEVICE -> "completed a device!" }
        val shown = section.coerceAtMost(4)
        val k = count(shown)
        Sim.chat("${nameColour(by)}$by§r§a $what (§r§c$k§r§a/${Station.total(shown)})")
        if (by == Sim.me) Stats.done(st, n)
        // Taunts ride the 62-tick grid (goldor.md, other lines).
        if (by == Sim.me && st.kind == Station.Kind.TERMINAL && pendingTaunt == null) pendingTaunt = "Stop touching those terminals!"
        if (st.kind == Station.Kind.TERMINAL || st.kind == Station.Kind.LEVER) Sim.sound(SoundEvents.NOTE_BLOCK_PLING, 0.6f, 2f, st.at)
        if (inProgress && count(section) >= Station.total(section)) sectionDone(section)
    }

    private fun nameColour(name: String) = if (name == Sim.me) "§b" else "§a"

    private fun sectionDone(s: Int) {
        sectionEnd[s] = n
        if (s <= 3) pendingLine = s - 1
        if (s == 4) { openCore(); return }
        if (gateDown[s]) openDoor(s)
        else {
            Sim.chat("§aThe gate will open in 5 seconds!")
            autoGateAt[s] = n + 100
        }
    }

    private fun openDoor(s: Int) {
        if (doorOpen[s]) return
        doorOpen[s] = true
        Blocks.play("door$s")
        if (s == 1) Blocks.play("ss_s1done")
        section = s + 1
        sectionStart[section] = n
        Stats.section(s, sectionEnd[s].coerceAtLeast(gateAt[s]) - sectionStart[s], n)
        // The section ends with its door (max(last completion, gate)): Goldor's catch-up cue.
        goldor.sectionEnded(s)
        // Stations of the new section that were done early already count.
        if (count(section) >= Station.total(section)) sectionDone(section)
    }

    /** Blows gate [s] (between S[s] and S[s+1]) if it can go now. [by]: who, null when it goes by itself. */
    fun blowGate(s: Int, by: String?): Boolean {
        if (s !in 1..3 || gateDown[s]) return false
        if (by != null && section < s) return false
        gateDown[s] = true
        gateAt[s] = n
        Sim.chat("§aThe gate has been destroyed!")
        Sim.sound(SoundEvents.GENERIC_EXPLODE, 2f, 1f, GATE_CENTRES[s])
        Blocks.play("gate$s${s + 1}")
        if (sectionEnd[s] >= 0) openDoor(s)
        return true
    }

    /** The gate whose blocks are within [r] of [p], or 0. */
    fun gateNear(p: Vec3, r: Double): Int {
        for (s in 1..3) if (GATE_BOXES[s].inflate(r).contains(p)) return s
        return 0
    }

    // ------------------------------------------------------------------ death ticks

    private fun deathTick() {
        val p = Sim.player ?: return
        if (p.isSpectator || p.isCreative || SimItems.cloaked) return
        if (inSafeSpot(p.position())) return
        // Only the next section ahead, and S4 while S1 is in progress (goldor.md, death ticks).
        val at = P3Sections.sectionAt(p.x, p.y, p.z)
        if (at != section + 1 && !(section == 1 && at == 4)) return
        deaths++
        Stats.deathTick(n)
        Sim.boss("Goldor", "What do you think you are doing there!")
        when (P3Sim.deathTicks) {
            0 -> {}
            1 -> Sim.title("", "§cDeath tick §7(S$at ahead of S$section)", 0, 25, 5)
            else -> Masks.hit(p, "Goldor")
        }
    }

    private fun inSafeSpot(v: Vec3) = CORE_BOX.contains(v) || STRIP.contains(v)

    private fun carve() {
        val g = goldor.position
        val x0 = Math.floor(g.x).toInt(); val z0 = Math.floor(g.z).toInt()
        val air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
        val level = Sim.level
        val p = BlockPos.MutableBlockPos()
        for (x in x0 - 5..x0 + 5) for (z in z0 - 5..z0 + 5) for (y in 114..124) {
            p.set(x, y, z)
            if (!level.getBlockState(p).isAir) Blocks.set(p.immutable(), air)
        }
    }

    private val tntDone = HashSet<Int>()

    /** One TNT cube along the section in progress (the first: S1's at x99-101 z85-87). */
    private fun tnt() {
        val i = TNT_BY_SECTION[section.coerceIn(1, 4)].firstOrNull { it !in tntDone } ?: return
        tntDone += i
        val (xs, ys, zs) = TNT_CUBES[i]
        val air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
        for (x in xs..xs + 2) for (y in ys..ys + 2) for (z in zs..zs + 2) Blocks.set(BlockPos(x, y, z), air)
    }

    // ------------------------------------------------------------------ the core

    private fun openCore() {
        section = 5
        coreAt = n
        Sim.chat("§aThe Core entrance is opening!")
        if (from != 5) Stats.section(4, n - sectionStart[4], n)
        Blocks.play("core")
        Stats.p3(n)
    }

    /** True when everyone (you and the bots) is in the core. */
    private fun everyoneIn(): Boolean {
        val p = Sim.player ?: return false
        if (!CORE_BOX.contains(p.position())) return false
        return Party.allIn(CORE_BOX)
    }

    private fun coreTick() {
        // Departure: on the last player's entry, 4 ticks after the opening at the earliest.
        if (everyoneInAt < 0 && n >= coreAt + 4 && everyoneIn()) {
            everyoneInAt = n
            goldor.fly(n)
        }
        if (deadAt < 0 && goldor.flying) {
            val arrived = goldor.arrived
            if (arrived && arrivedAt < 0) {
                arrivedAt = n
                Sim.boss("Goldor", "You have done it, you destroyed the factory...")
                // A script on a 62-tick cadence that runs on past his death and Necron's start.
                Fight.later(62, "goldor arrived 1") { Sim.boss("Goldor", "But you have nowhere to hide anymore!") }
                Fight.later(124, "goldor arrived 2") { Sim.boss("Goldor", "YOU ARE FACE TO FACE WITH GOLDOR!") }
                Fight.later(186, "goldor arrived 3") { Sim.boss("Goldor", "....") }
            }
            if (n >= goldor.killAt) die()
        }
        // Frenzy: every ~10 ticks while you're 2-14 blocks from him (goldor.md, damage).
        if (deadAt < 0 && n % 10 == 0) Sim.player?.let { p ->
            val d = p.position().distanceTo(goldor.position)
            if (!p.isSpectator && !p.isCreative && d in 2.0..14.0)
                Sim.chat("§cGoldor's Frenzy hit you for ${"%,d".format(30000 + kotlin.random.Random.nextInt(10001))} damage.")
        }
        if (deadAt >= 0 && necronAt < 0 && n >= deadAt + 82) {
            necronAt = n
            Sim.boss("Goldor", "Necron, forgive me.")
            Stats.goldorDone(n)
            if (P3Sim.p3Only) { Sim.note("P3 done. §fMenu > P4§7 to go on to Necron."); return }
            Blocks.play("p3end")
            // Necron's first line comes with "Necron, forgive me." (82 ticks after death, 81-83).
            Fight.later(0, "necron") { Fight.begin(P4Necron(fromP3 = true)) }
        }
    }

    private fun die() {
        deadAt = n
        if (arrivedAt < 0) Sim.boss("Goldor", "....")
        goldor.die()
    }

    companion object {
        /** Where the core counts as entered (DungeonSplits.everyoneInCore). */
        val CORE_BOX = AABB(39.0, 0.0, 54.0, 71.0, 155.5, 118.0)
        /** In front of the core door: outside every section, never hit by a death tick. */
        val STRIP = AABB(45.0, 100.0, 50.0, 65.0, 160.0, 54.5)
        /** Gate i/i+1, by i. */
        val GATE_BOXES = arrayOf(AABB.ofSize(Vec3.ZERO, 0.0, 0.0, 0.0), AABB(93.0, 113.0, 121.0, 108.0, 138.0, 125.0), AABB(16.0, 113.0, 125.0, 20.0, 138.0, 140.0), AABB(1.0, 113.0, 48.0, 16.0, 138.0, 52.0))
        /** TNT cubes (min corner) along the track, and the ones each section's timer takes. */
        val TNT_CUBES = listOf(Triple(99, 127, 85), Triple(99, 127, 103), Triple(71, 127, 131), Triple(53, 127, 131), Triple(35, 127, 131), Triple(7, 127, 103), Triple(7, 127, 85), Triple(7, 127, 67), Triple(35, 129, 39), Triple(53, 127, 39), Triple(71, 129, 39))
        val TNT_BY_SECTION = arrayOf(listOf(), listOf(0, 1), listOf(2, 3, 4), listOf(5, 6, 7), listOf(8, 9, 10))
        val GATE_CENTRES = arrayOf(Vec3.ZERO, Vec3(100.0, 118.0, 122.5), Vec3(17.5, 118.0, 132.0), Vec3(8.0, 118.0, 49.5))
    }

    // ------------------------------------------------------------------ Goldor

    /** Goldor on his track (a giant you see, an invisible wither for the boss bar). */
    class Goldor {
        private var giant: Giant? = null
        private var wither: WitherBoss? = null
        /** Distance along the track from the S4/S1 corner. */
        var s = START_S
        private var speed = WALK
        private var sprintTo = -1.0
        var flying = false
            private set
        private var flyFrom = Vec3.ZERO
        private var pos = Vec3.ZERO
        var killAt = Int.MAX_VALUE
            private set
        val arrived get() = flying && Math.hypot(pos.x - CORE_POINT.x, pos.z - CORE_POINT.z) < 0.5
        val position: Vec3 get() = pos

        fun spawn(n: Int) {
            s = (START_S + WALK * n) % LOOP
            pos = trackPos(s)
            val level = Sim.level
            val g = Giant(EntityType.GIANT, level)
            g.setNoAi(true); g.isSilent = true; g.isInvulnerable = true; g.setNoGravity(true)
            g.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.GOLDEN_SWORD))
            g.setItemSlot(EquipmentSlot.HEAD, ItemStack(Items.GOLDEN_HELMET))
            g.setItemSlot(EquipmentSlot.CHEST, ItemStack(Items.GOLDEN_CHESTPLATE))
            g.setItemSlot(EquipmentSlot.LEGS, ItemStack(Items.GOLDEN_LEGGINGS))
            g.setItemSlot(EquipmentSlot.FEET, ItemStack(Items.GOLDEN_BOOTS))
            g.snapTo(pos.x, pos.y - 8, pos.z, yaw(), 0f)
            giant = Sim.spawn(g)
            val w = WitherBoss(EntityType.WITHER, level)
            w.setNoAi(true); w.isSilent = true; w.isInvulnerable = true; w.setNoGravity(true); w.isInvisible = true
            w.invulnerableTicks = 0
            w.setCustomName(Component.literal("§c§lGoldor"))
            w.snapTo(pos.x, pos.y, pos.z, yaw(), 0f)
            wither = Sim.spawn(w)
        }

        fun remove() { giant?.discard(); wither?.discard(); giant = null; wither = null }

        /** Section [sec] ended (its door opened): if he is still in its segment, he sprints to the next one's start. */
        fun sectionEnded(sec: Int) {
            if (flying || sec !in 1..3) return
            val inIt = if (sec == 1) s >= S1_ENTRY || s < BOUNDS[1] else segment(s) == sec - 1
            if (inIt) { sprintTo = SPRINT_TO[sec - 1]; speed = SPRINT }
        }

        fun fly(n: Int) {
            if (flying) return
            flying = true
            flyFrom = pos
            // Killed in flight 55 ticks after leaving (median; 15-113): the setting.
            killAt = n + P3Sim.goldorKill.toInt()
        }

        fun die() {
            wither?.discard(); wither = null
            giant?.let { g -> Fight.later(40, "goldor body") { g.discard() } }
        }

        fun tick(phase: GoldorPhase) {
            if (giant == null) return
            if (flying) {
                val to = CORE_POINT.subtract(pos)
                val d = to.horizontalDistance()
                if (d > 0.01) {
                    val step = minOf(FLY, d)
                    pos = pos.add(to.x / d * step, -0.03, to.z / d * step)
                    if (pos.y < CORE_POINT.y) pos = Vec3(pos.x, CORE_POINT.y, pos.z)
                }
            } else {
                if (sprintTo >= 0) {
                    // Distance left, round the loop's seam (a sprint from the S4 line into S1).
                    val left = ((sprintTo - s) % LOOP + LOOP) % LOOP
                    if (left <= SPRINT) { s = sprintTo; sprintTo = -1.0; speed = WALK } else s += SPRINT
                } else s += WALK
                s %= LOOP
                pos = trackPos(s)
            }
            val yaw = if (flying) Math.toDegrees(Math.atan2(-(CORE_POINT.x - pos.x), CORE_POINT.z - pos.z)).toFloat() else yaw()
            giant?.let { it.snapTo(pos.x, pos.y - 8, pos.z, yaw, 0f); it.yHeadRot = yaw; it.yBodyRot = yaw }
            wither?.let { it.snapTo(pos.x, pos.y, pos.z, yaw, 0f) }
        }

        private fun yaw(): Float {
            // Walking direction along the loop (S1: +z, S2: -x, S3: -z, S4: +x).
            return when (segment(s)) { 0 -> 0f; 1 -> 90f; 2 -> 180f; else -> -90f }
        }

        companion object {
            const val WALK = 0.06
            const val SPRINT = 0.60
            const val FLY = 0.80
            /** Where the four lines cross (S1 x 99.55, S2 z 131.7, S3 x 8.4, S4 z 40.0; goldor.md, the track). */
            private val CORNERS = listOf(Vec3(99.55, 119.0, 40.0), Vec3(99.55, 119.0, 131.7), Vec3(8.4, 118.5, 131.7), Vec3(8.4, 118.0, 40.0))
            /** y along each line: S1 119, S2 118.1-118.9, S3 118, S4 rising 118.1 -> 119. */
            private val Y_FROM = doubleArrayOf(119.0, 118.5, 118.0, 118.1)
            private val Y_TO = doubleArrayOf(119.0, 118.5, 118.0, 119.0)
            /** s at each segment's start (S1 0-90.7, S2 -182.1, S3 -272.8, S4 -364.2), and the loop's end. */
            val BOUNDS = doubleArrayOf(0.0, 90.7, 182.1, 272.8, 364.2)
            const val LOOP = 364.2
            /** (80, 119, 40): where he is at "Who dares trespass", 19.5 blocks before the S1 corner. */
            const val START_S = 344.7
            /** Where the S1 segment starts for the catch-up: on the S4 line between x 95.5 (s 360.2) and 98 (362.7). */
            const val S1_ENTRY = 361.5
            /** Where a catch-up sprint ends, ~1.6 past the corner (S2 92.2-92.4, S3 183.6-183.8, S4 274.3-276.5). */
            val SPRINT_TO = doubleArrayOf(92.3, 183.7, 275.0)
            val CORE_POINT = Vec3(54.5, 117.0, 40.5)

            /** The segment (0-3: S1-S4) [s] is in. */
            fun segment(s: Double): Int { var i = 0; while (i < 3 && s >= BOUNDS[i + 1]) i++; return i }

            fun trackPos(s: Double): Vec3 {
                val i = segment(s)
                val f = ((s - BOUNDS[i]) / (BOUNDS[i + 1] - BOUNDS[i])).coerceIn(0.0, 1.0)
                val a = CORNERS[i]; val b = CORNERS[(i + 1) % 4]
                return Vec3(a.x + (b.x - a.x) * f, Y_FROM[i] + (Y_TO[i] - Y_FROM[i]) * f, a.z + (b.z - a.z) * f)
            }
        }
    }

    /** Lever blocks: our own, so the click is ours (no redstone). */
    fun leverAt(pos: BlockPos): Station? = stations.firstOrNull { it.lever == pos }

    fun pullLever(st: Station, by: String) {
        val lever = st.lever ?: return
        if (st.done) { if (by == Sim.me) Sim.chat("§cSomeone has already activated this lever!"); return }
        if (st.section != section) { if (by == Sim.me) Sim.chat("§cThis lever doesn't seem to be responsive at the moment."); return }
        Blocks.get(lever)?.takeIf { it.hasProperty(LeverBlock.POWERED) }?.let { Blocks.set(lever, it.setValue(LeverBlock.POWERED, true)) }
        Sim.sound(SoundEvents.LEVER_CLICK, 0.3f, 0.6f, Vec3.atCenterOf(lever))
        complete(st, by)
    }

    /** A click on a terminal's stand. */
    fun useTerminal(st: Station) {
        val p = Sim.player ?: return
        if (st.done) { Sim.chat("§cThis Terminal has already been completed!"); return }
        if (st.section != section) { Sim.chat("§cThis Terminal doesn't seem to be responsive at the moment."); return }
        if (Party.busyAt(st)) { Sim.chat("§cSomeone is already using this terminal!"); return }
        Terminals.open(p, st)
    }

    /** Player-facing state for the menu's status line. */
    fun status(): String = when {
        necronAt >= 0 -> "P3 done"
        deadAt >= 0 -> "Goldor dead"
        section == 5 -> "Core open" + if (everyoneInAt >= 0) ", Goldor flying" else ""
        else -> "S$section ${count(section)}/${Station.total(section)}" + (if (!gateDown[section.coerceAtMost(3)] && section <= 3) ", gate up" else "")
    }

    val deathsTaken get() = deaths
    fun gateIsDown(s: Int) = gateDown.getOrElse(s) { true }
    fun doorIsOpen(s: Int) = doorOpen.getOrElse(s) { true }
}
