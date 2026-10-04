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
 *  - his lines go one at a time, each at least 62 ticks after the one before (a queue: intro,
 *    taunts, a random section line per door, the arrival script, "....");
 *  - Necron's first line 82 ticks (81-83) after Goldor's death, then P4; "Necron, forgive me." 82
 *    after "...." (with Necron's line when he died in flight, later when he reached the core).
 * Measured: tools/p3sim/research/goldor-flow.md.
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
    private var p3endAt = -1
    private var handOff = false
    private var deaths = 0
    /** Goldor's lines waiting their turn (FIFO), and n of the last one said. */
    private val lines = ArrayDeque<String>()
    private var lastLine = -1000
    /** n of "Necron, forgive me." (82 after "...."), or -1. */
    private var forgiveAt = -1
    /** Taunts by the n they are queued at. */
    private val tauntAt = HashMap<Int, String>()
    private var lastTaunt: String? = null

    val devices = Devices(this)
    val goldor = Goldor()

    fun station(section: Int, label: String) = stations.first { it.section == section && it.label == label }
    fun count(section: Int) = stations.count { it.section == section && it.done }

    override fun start() {
        stations.forEach { it.spawnStands() }
        devices.start()
        Stats.reset(from)
        val startN = when (from) { 2 -> 252; 3 -> 433; 4 -> 629; 5 -> 797; else -> 0 }
        nOffset = startN
        // Earlier sections: done, their gates and doors open, as if a party had just done them.
        for (s in 1 until from.coerceAtMost(5)) {
            stations.filter { it.section == s }.forEach { doneAlready(it) }
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
                // From Storm: the Superboom onto the bar where the Hyperion was (a swap: with a saved
                // layout slot 1 holds something else, and after StormEnd the P3 bar is already given).
                val inv = player.inventory
                val hype = (0..8).firstOrNull { SimItems.idOf(inv.getItem(it)) == "HYPERION" }
                val boom = (9 until inv.containerSize).firstOrNull { SimItems.idOf(inv.getItem(it)) == "SUPERBOOM_TNT" }
                if (hype != null && boom != null) {
                    val h = inv.getItem(hype); inv.setItem(hype, inv.getItem(boom)); inv.setItem(boom, h)
                    player.inventoryMenu.broadcastChanges()
                }
            }
        }
        if (from == 1) {
            say("Who dares trespass into my domain?")
            // The intro, then the taunts that queue up behind it (they start at 248 in 112 of 114 runs).
            lines += listOf("Little ants, plotting and scheming, thinking they are invincible...",
                "I won't let you break the factory core, I gave my life to my Master.", "No one matches me in close quarters.")
            val k = kotlin.random.Random.nextInt(100).let { if (it < 2) 0 else if (it < 43) 1 else if (it < 93) 2 else 3 }
            repeat(k) { tauntAt[20 + kotlin.random.Random.nextInt(220)] = taunt() }
            // The red pad's drop hole: its frames run 24-30 ticks after this line.
            Blocks.play("p3start")
        } else if (from == 5) {
            com.engineerclient.practice.TermInfo.simStart(5)
            openCore()
        } else {
            com.engineerclient.practice.TermInfo.simStart(from)
            maybeTaunt()
            Sim.note("Starting at §fS$from§7 (n = $startN, the median fast run's).")
        }
    }

    /** [st] counts as done before the start (by the party: no chat, no swing). */
    private fun doneAlready(st: Station) {
        st.done = true; st.doneBy = "-"; st.refreshStands()
        if (st.kind == Station.Kind.DEVICE) devices.shownDone(st.label)
        st.lever?.let { l -> Blocks.get(l)?.takeIf { b -> b.hasProperty(LeverBlock.POWERED) }?.let { b -> Blocks.set(l, b.setValue(LeverBlock.POWERED, true)) } }
    }

    override fun stop() {
        Terminals.closeAll()
        devices.stop()
        // Handed over to Necron: his body stays where he died until ~290 ticks after Necron's first line
        // (boss recorder: 279-307 in the 9 fights that kept him in range); else gone with the phase.
        val g = goldor
        if (necronAt >= 0) Fight.later(290, "goldor body") { g.remove() } else g.remove()
    }

    override fun tick() {
        val n = n
        devices.tick()
        tauntAt.remove(n)?.let { if (section <= 4) say(it) }
        dialogue()
        // Stand names refresh on a 20-tick grid.
        // Lever stands rename on their own, 1-3 ticks after the pull (pullLever).
        // Recorded renames land 1-2 ticks before each multiple of 20 (TERM-20): set on 18, sent on 19.
        if (n % 20 == 18) stations.forEach { if (it.kind != Station.Kind.LEVER) it.refreshStands() }
        // Gates that open by themselves 5 s after their section ended.
        for (s in 1..3) if (autoGateAt[s] >= 0 && n >= autoGateAt[s] && !gateDown[s]) blowGate(s, null)
        // Death ticks: the chat line lands at n = 60k-1 (goldor.md, death ticks).
        if (section <= 4 && n % 60 == 59) deathTick()
        goldor.tick(this)
        // His carving of the walkway is Blocks' (carveTick); the TNT cubes are left out.
        // The core: everyone in, then Goldor flies in and dies.
        if (section == 5) coreTick()
        Party.tickP3(this)
        if (handOff) { handOff = false; handDialogueOver(); Fight.begin(P4Necron(fromP3 = true)) }
    }

    // ------------------------------------------------------------------ Goldor's lines

    /**
     * Goldor says one line at a time, each at least 62 ticks after the one before (705 of 932 gaps
     * exactly 62, none shorter; the rest waited for a trigger): a FIFO, so a section line queues
     * behind taunts (S1's comes a median 79 ticks after the door, S2's and S3's 1). The death-tick
     * line is not in it.
     */
    private fun say(line: String) {
        if (lines.isEmpty() && n >= lastLine + 62) speak(line) else lines += line
    }

    private fun speak(line: String) {
        Sim.boss("Goldor", line)
        lastLine = n
        // "Necron, forgive me." 82 after "...." (FLIGHT: 120/120 runs; REACH: 82-88).
        if (line == "....") forgiveAt = n + 82
    }

    private fun dialogue() {
        if (lines.isNotEmpty() && n >= lastLine + 62) speak(lines.removeFirst())
        if (forgiveAt in 0..n) { forgiveAt = -1; Sim.boss("Goldor", "Necron, forgive me.") }
    }

    /** P4 starts: what Goldor still has to say runs on through Necron's intro, at the times it would have here. */
    private fun handDialogueOver() {
        var at = lastLine
        for (line in lines) {
            at = maxOf(at + 62, n)
            val dt = at - n
            Fight.later(dt, "goldor line") { Sim.boss("Goldor", line) }
            if (line == "....") forgiveAt = at + 82
        }
        lines.clear()
        if (forgiveAt >= 0) { val dt = forgiveAt - n; forgiveAt = -1; Fight.later(dt, "goldor forgive") { Sim.boss("Goldor", "Necron, forgive me.") } }
    }

    /** A taunt from the pool (no line twice in a row; the ten come about equally often). */
    private fun taunt(): String = TAUNT_POOL.filter { it != lastTaunt }.random().also { lastTaunt = it }

    /** S2-S4: a later taunt in 34% of runs (39 of 114), at any point of those sections. */
    private fun maybeTaunt() {
        if (section in 2..4 && kotlin.random.Random.nextDouble() < 0.13) tauntAt[n + 20 + kotlin.random.Random.nextInt(180)] = taunt()
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
        val line = progressLine(by, what, k, Station.total(shown))
        // Someone else finishing the terminal you're in closes your window first, in the same tick (terminals audit TERM-06).
        if (by != Sim.me && st.kind == Station.Kind.TERMINAL) Terminals.closeFor(st)
        // Hypixel's line is a styled component (name, green text, red count), not a § string.
        Sim.chatStyled(line)
        if (by == Sim.me) Stats.done(st, n)
        // Every progress line (devices too): pling vol 8 at your own position, pitch 4.05 as sent (the client clamps it to 2;
        // Odin's Terminal Sounds keys on the raw 4.047619) (chat-attacks.md §2).
        Sim.sound(SoundEvents.NOTE_BLOCK_PLING, 8f, 4.047619f, source = net.minecraft.sounds.SoundSource.BLOCKS)
        // Hypixel shows each completion as a subtitle too (Odin's Terminal Titles replaces it): 0/40/0, the
        // subtitle a legacy string without the §r's ("§bp3wr§a activated a terminal! (§c3§a/8)").
        Sim.title("", line.replace("§r", ""), 0, 40, 0)
        if (inProgress && count(section) >= Station.total(section)) sectionDone(section)
    }

    private fun nameColour(name: String) = if (name == Sim.me) "§b" else "§a"

    /** `<col><P>§r§a activated a terminal! (§r§c4§r§a/7)`; with a green (`§a`) name the `§r§a` is dropped (chat-attacks.md §1.2). */
    private fun progressLine(by: String, what: String, k: Int, total: Int): String {
        val col = nameColour(by)
        return "$col$by${if (col == "§a") "" else "§r§a"} $what (§r§c$k§r§a/$total)"
    }

    /**
     * Goldor's taunts: 1-3 queue up during S1 (0: 2, 1: 47, 2: 57, 3: 8 of 114 runs), so they follow the
     * intro at 248, 310...; 0-3 more later in 39 of 114 runs. The ten come about equally often
     * (13-26 each) and don't follow from anything the recordings show (goldor-flow.md, dialogue).
     */
    private val TAUNT_POOL = listOf(
        "Do you really think we won't repair everything? Your impact will be minuscule!", "Come closer!",
        "You are breaking precious materials, unforgivable.", "CLOSER!", "There is no stopping me down there!",
        "I am the death zone, you are smart to flee.", "You can't damage me, you can barely slow me down!",
        "Slowing me down only prolongs your pain!", "Closer to me!", "Stop touching those terminals!",
    )
    /** One per door (S1-S3), any of the three whatever the section (S1: 34/46/34, S2: 39/27/48, S3: 40/38/36). */
    private val SECTION_LINES = listOf("The little ants have a brain it seems.", "I will replace that gate with a stronger one!", "YOUR END IS NEAR!!")

    private fun sectionDone(s: Int) {
        sectionEnd[s] = n
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
        // His section line is queued with the door (the later of the last completion and the gate).
        say(SECTION_LINES.random())
        section = s + 1
        sectionStart[section] = n
        maybeTaunt()
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
        // The progress pling comes with this line too (164 of 164 with no progress line near; boss recorder).
        Sim.sound(SoundEvents.NOTE_BLOCK_PLING, 8f, 4.047619f)
        Sim.sound(SoundEvents.GENERIC_EXPLODE, 0.5f, 0.49f, GATE_CENTRES[s])
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
            if (goldor.arrived && arrivedAt < 0) {
                arrivedAt = n
                // Reaching the core alive (14 of 114 runs): his script, queued at once, so it runs 62 apart past his
                // death and Necron's start; his "...." queues behind it.
                say("You have done it, you destroyed the factory...")
                say("But you have nowhere to hide anymore!")
                say("YOU ARE FACE TO FACE WITH GOLDOR!")
            }
            if (n >= goldor.killAt) die()
        }
        goldor.barTick(n)
        // Frenzy: every ~10 ticks while you're 2-14 blocks from him (goldor.md, damage).
        if (deadAt < 0 && n % 10 == 0) Sim.player?.let { p ->
            val d = p.position().distanceTo(goldor.position)
            if (!p.isSpectator && !p.isCreative && d in 2.0..14.0) {
                // Format and sounds as measured: one decimal, explode v0.5 p0.49 + hurt (chat-attacks.md §1.2, §2).
                Sim.chat("§cGoldor's§r§7 Frenzy hit you for §r§c${"%,.1f".format(java.util.Locale.US, 30000 + kotlin.random.Random.nextDouble(10000.0))}§r§7 damage.")
                Sim.sound(SoundEvents.GENERIC_EXPLODE, 0.5f, 0.49f)
                Sim.sound(SoundEvents.PLAYER_HURT, 1f, 1f)
            }
        }
        // The floor under the core goes 2 ticks before Necron's first line (anims-p3.json p3end, dt -2).
        if (deadAt >= 0 && p3endAt < 0 && n >= deadAt + 80 && !P3Sim.p3Only) { p3endAt = n; Blocks.play("p3end") }
        // Necron's first line 82 ticks after Goldor's death (81-83), whichever ending; "Necron, forgive me." comes
        // from the dialogue (82 after "....": in the same tick, just before, when he died in flight).
        if (deadAt >= 0 && necronAt < 0 && n >= deadAt + 82) {
            necronAt = n
            Stats.goldorDone(n)
            // Stopping here, the run's recording ends here too (else it grows until the next start).
            if (P3Sim.p3Only) { Recorder.finish(); Sim.note("P3 done. §fMenu > P4§7 to go on to Necron."); return }
            handOff = true
        }
    }

    private fun die() {
        deadAt = n
        say("....")
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
        val GATE_CENTRES = arrayOf(Vec3.ZERO, Vec3(100.0, 118.0, 122.5), Vec3(17.5, 118.0, 132.0), Vec3(8.0, 118.0, 49.5))
    }

    // ------------------------------------------------------------------ Goldor

    /**
     * Goldor on his track: a plain wither like the others, unarmoured the whole phase (health
     * 1000 / 300000 on the track and in the core; boss recorder), his name on its stand. Hypixel's
     * invisible giants with golden swords stand at the S4/S1 corner (floating greatswords).
     */
    class Goldor {
        private var boss: BossWither? = null
        private val giants = ArrayList<Giant>()
        /** Distance along the track from the S4/S1 corner. */
        var s = START_S
        private var speed = WALK
        private var sprintTo = -1.0
        var flying = false
            private set
        /** Reached the core point alive. */
        var arrived = false
            private set
        private var dead = false
        private var flyAt = 0
        private var nextHurt = 0
        private var pos = Vec3.ZERO
        var killAt = Int.MAX_VALUE
            private set
        val position: Vec3 get() = pos

        fun spawn(n: Int) {
            s = (START_S + WALK * n) % LOOP
            pos = trackPos(s)
            boss = BossWither("Goldor", pos, inv = 0, armoured = false)
            BossBar.show("§c§lGoldor", 1f)
            for (g in GIANTS) {
                val e = SimGiant(Sim.level)
                e.setNoAi(true); e.isSilent = true; e.isInvulnerable = true; e.setNoGravity(true); e.isInvisible = true
                e.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.GOLDEN_SWORD))
                e.snapTo(g.x, g.y, g.z, 0f, 0f)
                giants += Sim.spawn(e)
            }
        }

        fun remove() { boss?.remove(); boss = null; giants.forEach { it.discard() }; giants.clear() }

        /** Section [sec] ended (its door opened): if he is still in its segment, he sprints to the next one's start. */
        fun sectionEnded(sec: Int) {
            if (flying || sec !in 1..3) return
            val inIt = if (sec == 1) s >= S1_ENTRY || s < BOUNDS[1] else segment(s) == sec - 1
            if (inIt) { sprintTo = SPRINT_TO[sec - 1]; speed = SPRINT }
        }

        fun fly(n: Int) {
            if (flying) return
            flying = true
            flyAt = n
            // Killed [P3Sim.goldorKill] after leaving (the setting; median 57 of 201 recorded kills).
            killAt = n + P3Sim.goldorKill.toInt()
        }

        /** Killed: he stops where he is (no death animation) and stays until well into P4. */
        fun die() { dead = true }

        /**
         * The bar: 1.0 on the track and until he leaves, then down as the party hits him, to 0.0 at the
         * kill and after. Hypixel resends it about once a second, so it moves in 20-tick steps (e.g. 1.0,
         * 0.86, 0.29, 0.25, 0.21, 0.0; boss recorder, 60 fights).
         */
        fun barTick(n: Int) {
            if (!flying || n % 20 != 0) return
            BossBar.progress(if (dead) 0f else (killAt - n).toFloat() / (killAt - flyAt).coerceAtLeast(1))
        }

        fun tick(phase: GoldorPhase) {
            val boss = boss ?: return
            val n = phase.n
            if (dead) return
            if (flying) {
                // The party hitting him from the moment he leaves: the red hurt flash every few ticks.
                if (n >= nextHurt) {
                    nextHurt = n + 2 + kotlin.random.Random.nextInt(11)
                    Sim.player?.connection?.send(net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket(boss.e))
                }
                if (!arrived) {
                    val to = CORE_POINT.subtract(pos)
                    val d = to.horizontalDistance()
                    val step = minOf(FLY, d)
                    pos = Vec3(pos.x + to.x / d.coerceAtLeast(1e-6) * step, maxOf(CORE_POINT.y, pos.y - 0.03), pos.z + to.z / d.coerceAtLeast(1e-6) * step)
                    if (d <= FLY) arrived = true
                } else {
                    // Then into the core through its door: 0.4/tick to just inside it, then a 0.07 walk (boss recorder, 4 fights).
                    val v = if (pos.z < CORE_IN_Z) 0.4 else 0.07
                    pos = Vec3(pos.x + (CORE_IN_X - pos.x).coerceIn(-0.1, 0.1), maxOf(116.75, pos.y - 0.006), pos.z + v)
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
            val yaw = when {
                arrived -> 0f
                flying -> Math.toDegrees(Math.atan2(-(CORE_POINT.x - pos.x), CORE_POINT.z - pos.z)).toFloat()
                else -> yaw()
            }
            boss.moveTo(pos, pos.add(-Math.sin(Math.toRadians(yaw.toDouble())), 0.0, Math.cos(Math.toRadians(yaw.toDouble()))))
        }

        private fun yaw(): Float {
            // Walking direction along the loop (S1: +z, S2: -x, S3: -z, S4: +x).
            return when (segment(s)) { 0 -> 0f; 1 -> 90f; 2 -> 180f; else -> -90f }
        }

        companion object {
            /** Hypixel's four greatsword giants, spawned with him at the S4/S1 corner (bosses.md). */
            val GIANTS = listOf(Vec3(81.5, 111.0, 33.5), Vec3(87.5, 111.0, 33.5), Vec3(81.5, 111.0, 40.5), Vec3(87.5, 111.0, 40.5))
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
            /** Reaching it alive he goes on into the core along x ~53.4-53.8, fast until z ~55.8-56.4. */
            const val CORE_IN_X = 53.6
            const val CORE_IN_Z = 56.0

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

    /** A greatsword giant that stays in the peaceful sim world (vanilla deletes monsters there, as it does [SimWither]s). */
    class SimGiant(level: net.minecraft.world.level.Level) : Giant(EntityType.GIANT, level) {
        override fun checkDespawn() {}
    }

    /** Lever blocks: our own, so the click is ours (no redstone). */
    fun leverAt(pos: BlockPos): Station? = stations.firstOrNull { it.lever == pos }

    fun pullLever(st: Station, by: String) {
        val lever = st.lever ?: return
        if (st.done) { if (by == Sim.me) Sim.chat("§cSomeone has already activated this lever!"); return }
        if (st.section != section) { if (by == Sim.me) Sim.chat("§cThis lever doesn't seem to be responsive at the moment."); return }
        Blocks.get(lever)?.takeIf { it.hasProperty(LeverBlock.POWERED) }?.let { Blocks.set(lever, it.setValue(LeverBlock.POWERED, true)) }
        Sim.sound(SoundEvents.LEVER_CLICK, 0.3f, 0.59f, Vec3.atCenterOf(lever))
        complete(st, by)
        // The lever's stand renames 1-3 ticks after the pull, not on the 20-tick grid (devices.md §5).
        if (st.done) Fight.later(1 + kotlin.random.Random.nextInt(3), "lever stand") { if (Fight.phase === this) st.refreshStands() }
    }

    /** A click on a terminal's stand. */
    fun useTerminal(st: Station) {
        val p = Sim.player ?: return
        // Red components, a tick after the click (TERM-17). No "already using" lock on Hypixel (TERM-06).
        if (st.done) { Fight.later(1, "term refusal") { Sim.chatStyled("§cThis Terminal has already been completed!") }; return }
        if (st.section != section) { Fight.later(1, "term refusal") { Sim.chatStyled("§cThis Terminal doesn't seem to be responsive at the moment.") }; return }
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

    /** n when section [s] started here. */
    fun sectionStartN(s: Int) = sectionStart[s]
    fun doorIsOpen(s: Int) = doorOpen.getOrElse(s) { true }
}
