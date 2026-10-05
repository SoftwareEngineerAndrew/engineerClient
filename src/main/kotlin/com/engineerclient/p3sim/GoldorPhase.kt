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
    /** Ticks the phase runs before "Who dares" when it follows StormEnd (S1 levers are live from then). */
    private val LEAD_IN = 3
    private val sectionStart = IntArray(6)
    private val sectionEnd = IntArray(6) { -1 }
    private val gateDown = BooleanArray(5)
    private val gateAt = IntArray(5) { -1 }
    private val doorOpen = BooleanArray(5)
    private var autoGateAt = IntArray(5) { -1 }
    private var coreAt = -1
    private var everyoneInAt = -1
    private var deadAt = -1
    /** Frenzy hits in a row (DEATH-08: the third one killed you, 2,849 then 44k then 54k; on alpha the second). */
    private var frenzyHits = 0
    private var arrivedAt = -1
    private var necronAt = -1
    private var p3endAt = -1
    private var handOff = false
    private var deaths = 0
    /** This 60-tick window's death tick lands on 60k-2 instead of 60k-1. */
    private var dtEarly = false
    /** Goldor's lines waiting their turn (FIFO), and n of the last one said. */
    private val lines = ArrayDeque<String>()
    private var lastLine = -1000
    /** n of "Necron, forgive me." (82 after "...."), or -1. */
    private var forgiveAt = -1
    /** Taunts by the n they are queued at. */
    private val tauntAt = HashMap<Int, String>()
    private var lastTaunt: String? = null

    /** Whether Goldor has been in each section's segment since it started (death ticks: its zone turns lethal once he walks on). */
    private val goldorReached = BooleanArray(6)
    private fun goldorSeg() = GoldorPhase.Goldor.segment(goldor.s) + 1

    val devices = Devices(this)
    private val arenaReplay = ArenaFixes.replay()
    val goldor = Goldor()

    fun station(section: Int, label: String) = stations.first { it.section == section && it.label == label }
    fun count(section: Int) = stations.count { it.section == section && it.done }

    override fun start() {
        stations.forEach { it.spawnStands() }
        devices.start()
        Stats.reset(from)
        val startN = when (from) { 2 -> 252; 3 -> 433; 4 -> 629; 5 -> 797; else -> 0 }
        nOffset = startN
        arenaReplay?.begin(startN)
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
        goldor.spawn(startN, bar = !(from == 1 && arrived))
        Party.startP3(this)
        GhostCapture.start(this)
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
        // From StormEnd the phase starts LEAD_IN ticks before the line, so S1 levers work then (LEV-06: first credit up to 1-2 ticks
        // before it). Fight.later(LEAD_IN) fires before the phase's tick with t = LEAD_IN - 1, so this offset puts "Who dares" at n = 0
        // (its phase ticks run n = -2, -1, 0, ...): death ticks then land at line + 59 as on main (72/98; 58 in 26/98).
        if (from == 1 && arrived) nOffset = -(LEAD_IN - 1)
        if (from == 1) {
          val opening = {
            // The bar turns to Goldor's with this line, not with his spawn ticks before it.
            if (arrived) BossBar.show("§c§lGoldor", 1f)
            say("Who dares trespass into my domain?")
            // The intro, then the taunts that queue up behind it (they start at 248 in 112 of 114 runs).
            lines += listOf("Little ants, plotting and scheming, thinking they are invincible...",
                "I won't let you break the factory core, I gave my life to my Master.", "No one matches me in close quarters.")
            val k = kotlin.random.Random.nextInt(100).let { if (it < 2) 0 else if (it < 43) 1 else if (it < 93) 2 else 3 }
            repeat(k) { tauntAt[20 + kotlin.random.Random.nextInt(220)] = taunt() }
            // The red pad's drop hole: its frames run 24-30 ticks after this line.
            Blocks.play("p3start")
          }
          if (arrived) Fight.later(LEAD_IN, "who dares") { if (Fight.phase === this) opening() } else opening()
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
        GhostCapture.stopped(this)
        Terminals.closeAll()
        devices.stop()
        // Handed over to Necron: his body stays where he died until ~290 ticks after Necron's first line
        // (boss recorder: 279-307 in the 9 fights that kept him in range); else gone with the phase.
        val g = goldor
        if (necronAt >= 0) Fight.later(290, "goldor body") { g.remove() } else g.remove()
    }

    override fun tick() {
        val n = n
        if (n == -1) goldor.tick(this)   // FLOW-25: he moves from n=-1
        if (n < 0) return
        devices.tick()
        innerChamber()
        tauntAt.remove(n)?.let { if (section <= 4) say(it) }
        dialogue()
        // Stand names refresh on a 20-tick grid.
        // Lever stands rename on their own, 1-3 ticks after the pull (pullLever).
        // Recorded renames land 1-2 ticks before each multiple of 20 (TERM-20): set on 18, sent on 19.
        // STANDS-14: the grid's phase is 18 or 19 per run (set on 18, sent 19: 21 of 36 runs; set on 17, sent 18: 15 of 36), picked in Fight.begin.
        if (n % 20 == Fight.refreshPhase) stations.forEach { if (it.kind != Station.Kind.LEVER) it.refreshStands() }
        // Gates that open by themselves 5 s after their section ended.
        for (s in 1..3) if (autoGateAt[s] >= 0 && n >= autoGateAt[s] && !gateDown[s]) blowGate(s, null)
        // Death ticks: the chat line lands at n = 60k-1 (72 of 98 on main), a tick early (60k-2) in 26 of 98.
        if (section <= 4) {
            if (n % 60 == 58) dtEarly = kotlin.random.Random.nextInt(98) < 26
            if ((n % 60 == 58 && dtEarly) || (n % 60 == 59 && !dtEarly)) deathTick()
        }
        goldor.tick(this)
        if (section in 1..4 && goldorSeg() == section) goldorReached[section] = true
        // His carving of the walkway is Blocks' (carveTick). The TNT cubes (one 27-block cube per 200-tick slot in about
        // half the runs), the granite blobs, the lantern burst and the S4 plate follow one recorded run (ARENA-01/02/04/08).
        arenaReplay?.tick(n)
        // The core: everyone in, then Goldor flies in and dies.
        if (section == 5) coreTick()
        Party.tickP3(this)
        GhostCapture.tick(this)
        if (handOff) { handOff = false; handDialogueOver(); Fight.begin(P4Necron(fromP3 = true)) }
    }

    /**
     * BREAKER-03: walking north out of the core through its (mined) door hole puts you back with the
     * enderman.teleport (far off, vol 8, pitch 0) and the chat line, every 20 ticks while you keep going
     * (14 recorded; back at about (54.5, 115, 58.3)).
     */
    private var lastIn: Vec3? = null
    private var innerAt = -100
    private fun innerChamber() {
        val p = Sim.player ?: return
        val was = lastIn; val now = p.position(); lastIn = now
        // From S4's start until Goldor takes off for the core (not at the opening), nothing gets you out of the inner chamber: any move from inside
        // CORE_BOX to outside it (a step, a leap, a teleport) snaps you back. Coming in from outside is free
        // (a leap onto a teammate at the door from S3 starts outside, so it never counts).
        if (section < 4 || everyoneInAt >= 0 || goldor.flying || was == null || !CORE_BOX.contains(was) || CORE_BOX.contains(now)) return
        Sim.tp(p, 54.5, 115.0, 58.3)
        lastIn = Vec3(54.5, 115.0, 58.3)
        if (n - innerAt < 20) return
        innerAt = n
        Sim.sound(SoundEvents.ENDERMAN_TELEPORT, 8f, 0f, Vec3(54.5, 115.0, 58.3))
        Sim.chat("§cA mystical force prevents you from leaving the inner chamber!")
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

    /** Goldor's voice plays at him (FLOW-07). */
    private fun gsay(line: String, stand: Boolean = true) = Sim.boss("Goldor", line, goldor.position, stand)

    private fun speak(line: String) {
        gsay(line)
        lastLine = n
        // "Necron, forgive me." 82 after "...." (FLIGHT: 120/120 runs; REACH: 82-88).
        if (line == "....") forgiveAt = n + 82
    }

    private fun dialogue() {
        if (lines.isNotEmpty() && n >= lastLine + 62) speak(lines.removeFirst())
        if (forgiveAt in 0..n) { forgiveAt = -1; gsay("Necron, forgive me."); Fight.later(12, "goldor rearm") { goldor.reArmour() } }
    }

    /** P4 starts: what Goldor still has to say runs on through Necron's intro, at the times it would have here. */
    private fun handDialogueOver() {
        var at = lastLine
        for (line in lines) {
            at = maxOf(at + 62, n)
            val dt = at - n
            Fight.later(dt, "goldor line") { gsay(line) }
            if (line == "....") forgiveAt = at + 82
        }
        lines.clear()
        if (forgiveAt >= 0) { val dt = forgiveAt - n; forgiveAt = -1; Fight.later(dt, "goldor forgive") { gsay("Necron, forgive me."); Fight.later(12, "goldor rearm") { goldor.reArmour() } } }
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
        if (by == Sim.me) { Stats.done(st, n); GhostCapture.event("done", st.id) }
        // Every progress line (devices too): pling vol 8 at your own position, pitch 4.05 as sent (the client clamps it to 2;
        // Odin's Terminal Sounds keys on the raw 4.047619) (chat-attacks.md §2).
        Sim.sound(SoundEvents.NOTE_BLOCK_PLING, 8f, 4.047619f, source = net.minecraft.sounds.SoundSource.BLOCKS)
        // Hypixel shows each completion as a subtitle too (Odin's Terminal Titles replaces it): 0/40/0, the
        // subtitle a legacy string without the §r's ("§bp3wr§a activated a terminal! (§c3§a/8)").
        Sim.title("", line.replace("§r", ""), 0, 40, 0)
        if (inProgress && count(section) >= Station.total(section)) sectionDone(section)
    }

    /** Rank colours (FLOW-27): you and the bots are MVP+ (the leap menu and party lines colour the bots §b). */
    private fun nameColour(name: String) = "§b"

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
            Sim.title("", "§aThe gate will open in 5 seconds!", 0, 40, 0)
            Sim.sound(SoundEvents.NOTE_BLOCK_PLING, 8f, 4.047619f, source = net.minecraft.sounds.SoundSource.BLOCKS)
            autoGateAt[s] = n + 100
            // The door's stairs and iron blocks (upper part, y118+) go 1 tick later; the barriers and portcullis wait for the gate (8 of 8 warnings, 47-48 blocks).
            Fight.later(1, "door top") {
                if (Fight.phase !== this || doorOpen[s]) return@later
                Blocks.anim("door$s")?.frames?.forEach { f ->
                    if (f.dt != 0 || f.pos.y < 118) return@forEach
                    val b = Blocks.get(f.pos)?.block
                    if (b == net.minecraft.world.level.block.Blocks.IRON_BLOCK || b is net.minecraft.world.level.block.StairBlock) Blocks.set(f.pos, f.state)
                }
            }
        }
    }

    private fun openDoor(s: Int) {
        if (doorOpen[s]) return
        doorOpen[s] = true
        // Blocks change 1 tick after the chat line (GATES-01: gates +1 in 102 of 108, doors +1, core +1 in 36 of 36).
        Blocks.play("door$s", delay = 1)
        if (s == 1) Blocks.play("ss_s1done", delay = 1)
        // His section line is queued with the door (the later of the last completion and the gate).
        say(SECTION_LINES.random())
        section = s + 1
        sectionStart[section] = n
        maybeTaunt()
        Stats.section(s, sectionEnd[s].coerceAtLeast(gateAt[s]) - sectionStart[s], n)
        // The section ends with its door (max(last completion, gate)): Goldor's catch-up cue.
        goldor.sectionEnded(s, n)
        // Stations of the new section that were done early already count.
        if (count(section) >= Station.total(section)) sectionDone(section)
    }

    /** Blows gate [s] (between S[s] and S[s+1]) if it can go now. [by]: who, null when it goes by itself. */
    fun blowGate(s: Int, by: String?): Boolean {
        if (s !in 1..3 || gateDown[s]) return false
        if (by != null && section < s) return false
        gateDown[s] = true
        gateAt[s] = n
        if (by == Sim.me) GhostCapture.event("gate", k = s)
        Sim.chat("§aThe gate has been destroyed!")
        // Hypixel: an empty title and the subtitle on the same tick (SUPERBOOM-04).
        Sim.title("", "§aThe gate has been destroyed!", 0, 40, 0)
        // The progress pling comes with this line too (164 of 164 with no progress line near; boss recorder).
        Sim.sound(SoundEvents.NOTE_BLOCK_PLING, 8f, 4.047619f)
        SimItems.gatePuffs(GATE_CENTRES[s], GATE_BOXES[s])
        Blocks.play("gate$s${s + 1}", delay = 1)
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
        if (p.isSpectator || p.isCreative || Masks.ghost) return   // the Creeper Veil does not stop death ticks (CLOAK-01)
        // The server's view of you, about one one-way latency late (PING-08).
        val seen = Fight.seenPos(p)
        if (inSafeSpot(seen)) return
        // Zones of sections not started yet are lethal; the one in progress too once Goldor has walked out of
        // its segment (Andrew's probe runs 2026-10-04: hits all round S1 while S1 was in progress, and inside S1
        // only after Goldor left it). Feet position, edges from the probes (DT_ZONES).
        val at = dtZone(seen)
        if (at < 0) return
        // A zone goes passive once its section has started (Andrew): only sections not started yet are lethal,
        // plus the one in progress while Goldor is out of its segment.
        if (at < section) return
        // The section in progress: lethal only once Goldor has reached its segment and walked on out of it. Behind it
        // (his start stretch on the S4 line, or still on the last section's line before his catch-up sprint) it is safe.
        if (at == section && !(goldorReached[section] && goldorSeg() != section)) return
        // Death Ticks: Off: nothing at all.
        if (P3Sim.deathTicks == 0) return
        deaths++
        Stats.deathTick(n)
        // FLOW-14: no line stand for death ticks (0/98). Quiet wither.ambient 1/1 HOSTILE at you, not the loud boss sound (DEATH-09);
        // after the death/proc chat and sounds (DEATH-10).
        val line = {
            Sim.chat("§4[BOSS] Goldor§r§c: What do you think you are doing there!")
            Sim.sound(SoundEvents.WITHER_AMBIENT, 1f, 1f, null, net.minecraft.sounds.SoundSource.HOSTILE)
        }
        when (P3Sim.deathTicks) {
            1 -> { line(); Sim.title("", "§cDeath tick §7(S$at during S$section)", 0, 25, 5) }
            else -> Masks.hit(p, null, line)   // the plain "You died and became a ghost." (MASKS-03)
        }
    }

    private fun inSafeSpot(v: Vec3) = CORE_BOX.contains(v) || STRIP.contains(v)

    // ------------------------------------------------------------------ the core

    private fun openCore() {
        section = 5
        coreAt = n
        Sim.chat("§aThe Core entrance is opening!")
        Sim.title("", "§aThe Core entrance is opening!", 0, 40, 0)
        Sim.sound(SoundEvents.NOTE_BLOCK_PLING, 8f, 4.047619f, source = net.minecraft.sounds.SoundSource.BLOCKS)
        if (from != 5) Stats.section(4, n - sectionStart[4], n)
        Blocks.play("core", delay = 1)
        Stats.p3(n)
    }

    /** True when everyone (you and the bots) is in the core. */
    private fun everyoneIn(): Boolean {
        val p = Sim.player ?: return false
        if (!CORE_BOX.contains(Fight.seenPos(p))) return false
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
        // Frenzy: every 10 ticks (on n % 10 == 7, main) while you're 2-14 blocks from him (goldor.md, damage).
        if (deadAt < 0 && n % 10 == 7) Sim.player?.let { p ->
            val d = p.position().distanceTo(goldor.position)
            if (Masks.ghost || p.isSpectator || p.isCreative || d !in 2.0..14.0) frenzyHits = 0
            else {
                // The kill's death line comes before that tick's Frenzy line, killer named Goldor (DEATH-07/08); a mask can save it.
                val hit = ++frenzyHits
                if (hit >= 3) { Masks.hit(p, "Goldor"); frenzyHits = 0 }
                // The damage ramps over hits in a row (main: 2,849 -> 44,323.9 -> 54,925.9; 11,562 -> 46,275 -> 53,974): a small first
                // hit, then 44k-60k. Hypixel's format: thousands commas, one decimal, none when it's whole.
                val r = kotlin.random.Random
                var dmg = when (hit) { 1 -> 2_000.0 + r.nextDouble(11_000.0); 2 -> 44_000.0 + r.nextDouble(4_000.0); else -> 52_000.0 + r.nextDouble(8_000.0) }
                dmg = if (r.nextInt(100) < 40) Math.floor(dmg) else Math.round(dmg * 10) / 10.0
                // Sounds as measured: explode v0.5 p0.49 + hurt (chat-attacks.md §1.2, §2).
                Sim.chat("§cGoldor's§r§7 Frenzy hit you for §r§c${StormFx.dmg(dmg)}§r§7 damage.")
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
        /**
         * Death-tick zones (feet, y 106 to 146): four plain rectangles, one per section, with block-wide gaps between
         * them at the gates (Andrew). From the 2026-10-04 probes: S1 x 90..114 z 26..121 (it takes the east strip and
         * the S4/S1 corner; z 121.5 never hit, x 89.3 and 114.3 safe), S2 x 20..114 z 122..146 (hit 122.30, safe
         * 146.3), S3 x -6..18 z 51..146 (the north strip west of gate 2/3 is S3; x 17.7 hit, 18.45 safe, -6.7 safe),
         * S4 x -6..90 z 26..50 (hit 49.70, safe 50.33; z 26.3 hit). y 145 hit, 146 never, 105.9 never.
         */
        val DT_ZONES: List<Pair<Int, AABB>> = listOf(
            1 to AABB(90.0, 106.0, 26.0, 114.0, 146.0, 121.0),
            2 to AABB(20.0, 106.0, 122.0, 114.0, 146.0, 146.0),
            3 to AABB(-6.0, 106.0, 51.0, 18.0, 146.0, 146.0),
            4 to AABB(-6.0, 106.0, 26.0, 90.0, 146.0, 50.0),
        )

        /** The section (1-4) whose zone [v] is in, or -1 outside them all (the gaps between them, the core, the middle). */
        fun dtZone(v: Vec3): Int = DT_ZONES.firstOrNull { it.second.contains(v) }?.first ?: -1

        /** Where the core counts as entered (DungeonSplits.everyoneInCore). */
        val CORE_BOX = AABB(39.0, 0.0, 54.0, 71.0, 155.5, 118.0)
        /** In front of the core door: outside every section, never hit by a death tick. */
        val STRIP = AABB(45.0, 100.0, 50.0, 65.0, 160.0, 54.5)
        /** Gate i/i+1, by i. */
        val GATE_BOXES = arrayOf(AABB.ofSize(Vec3.ZERO, 0.0, 0.0, 0.0), AABB(93.0, 113.0, 121.0, 108.0, 138.0, 125.0), AABB(16.0, 113.0, 125.0, 20.0, 138.0, 140.0), AABB(1.0, 113.0, 48.0, 16.0, 138.0, 52.0))
        /** Where each gate blows (centres), by i. */
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
        /** Still on the S4 line from his start, not yet round the S1 corner: counts as S1 for death ticks (probe runs). */
        var firstLap = true
        private var speed = WALK
        private var sprintTo = -1.0
        /** FLOW-03: the tick the pending catch-up sprint starts (-1: none) and the section whose door it follows. */
        private var sprintFrom = -1
        private var sprintSec = 0
        var flying = false
            private set
        /** Reached the core point alive. */
        var arrived = false
            private set
        private var dead = false
        private var flyAt = 0
        private var nextHurt = 0
        private var pos = Vec3.ZERO
        /** FLOW-17: armoured (health 1) on the track in 23 of 36 recorded runs, knocked there 1-7 ticks after spawning. */
        private val armouredRun = kotlin.random.Random.nextInt(36) < 23
        private val armourAt = 1 + kotlin.random.Random.nextInt(7)
        private var armourOffAt = Int.MAX_VALUE
        private var spawnedN = 0
        /** FLOW-16: the giants appear at n 11-19 and orbit. */
        private val giantsAt = 11 + kotlin.random.Random.nextInt(9)
        private var giantAngle = 0.0
        var killAt = Int.MAX_VALUE
            private set
        val position: Vec3 get() = pos

        fun spawn(n: Int, bar: Boolean = true) {
            firstLap = START_S + walkDist(n) < LOOP
            s = (START_S + walkDist(n)) % LOOP
            pos = trackPos(s)
            spawnedN = n
            boss = BossWither("Goldor", pos, inv = 0, armoured = false)
            if (bar) BossBar.show("§c§lGoldor", 1f)
        }

        /** FLOW-16: four invisible golden-sword giants 90 degrees apart, radius 3 round Goldor+(2.4,-8,-3.5), ~6 degrees a tick. */
        private fun spawnGiants() {
            for (i in 0 until 4) {
                val e = SimGiant(Sim.level)
                e.setNoAi(true); e.isSilent = true; e.isInvulnerable = true; e.setNoGravity(true); e.isInvisible = true
                // Vanilla clears a mob's invisible flag without the effect: the effect keeps it (no particles).
                e.addEffect(net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.INVISIBILITY, -1, 0, false, false))
                e.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.GOLDEN_SWORD))
                val g = giantPos(i)
                e.snapTo(g.x, g.y, g.z, 0f, 0f)
                giants += Sim.spawn(e)
            }
        }

        private fun giantPos(i: Int): Vec3 {
            val a = Math.toRadians(giantAngle + 90.0 * i)
            return Vec3(pos.x + 2.4 + 3.0 * Math.cos(a), pos.y - 8.0, pos.z - 3.5 + 3.0 * Math.sin(a))
        }

        private fun orbitGiants() {
            giantAngle += 6.0
            giants.forEachIndexed { i, e -> val g = giantPos(i); e.snapTo(g.x, g.y, g.z, (giantAngle + 90.0 * i).toFloat(), 0f) }
        }

        /** Back on after "Necron, forgive me." (FLOW-17). */
        fun reArmour() { if (armouredRun) boss?.armour(true) }

        fun remove() { boss?.remove(); boss = null; giants.forEach { it.discard() }; giants.clear() }

        /** Section [sec] ended (its door opened): if he is still in its segment, he sprints to the next one's start. */
        fun sectionEnded(sec: Int, doorN: Int) {
            if (flying || sec !in 1..3) return
            val inIt = if (sec == 1) s >= S1_ENTRY || s < BOUNDS[1] else segment(s) == sec - 1
            // FLOW-03: the sprint starts 48-59 ticks (median ~55) after the door, not at once.
            if (inIt && sprintFrom < 0) { sprintFrom = doorN + SPRINT_LAG_MIN + kotlin.random.Random.nextInt(SPRINT_LAG_SPREAD); sprintSec = sec }
        }

        fun fly(n: Int) {
            if (flying) return
            flying = true
            flyAt = n
            // Killed [P3Sim.goldorKill] after leaving (the setting; flight start to the "...." line, median 43, range 17-74, 31 runs; FLOW-05).
            killAt = n + P3Sim.goldorKill.toInt()
            // His armour goes 4-14 ticks before the "...." line (FLOW-17).
            armourOffAt = killAt - 4 - kotlin.random.Random.nextInt(11)
        }

        /** Killed: he stops where he is (no death animation) and stays until well into P4. */
        fun die() { dead = true }

        /**
         * The bar: 1.0 on the track and until he leaves, then down as the party hits him, to 0.0 at the
         * kill and after. Hypixel resends it about once a second, so it moves in 20-tick steps (e.g. 1.0,
         * 0.86, 0.29, 0.25, 0.21, 0.0; boss recorder, 60 fights).
         */
        fun barTick(n: Int) {
            // FLOW-13 (recorder-2, 31 runs): full until ~3 ticks before the "...." (-17..+12), 0 about 9 after it (0..17).
            if (!flying || killAt == Int.MAX_VALUE) return
            val dropAt = killAt - 3
            val zeroAt = killAt + 9
            if (n < dropAt) return
            if (n >= zeroAt) { if (!barZeroed) { barZeroed = true; BossBar.progress(0f) }; return }
            if ((n - dropAt) % 4 == 0) BossBar.progress(0.35f * (zeroAt - n) / (zeroAt - dropAt))
        }
        private var barZeroed = false

        fun tick(phase: GoldorPhase) {
            val boss = boss ?: return
            val n = phase.n
            if (dead) return
            if (armouredRun && n == spawnedN + armourAt) boss.armour(true)
            if (n >= armourOffAt) { armourOffAt = Int.MAX_VALUE; boss.armour(false) }
            if (n >= giantsAt && giants.isEmpty()) spawnGiants()
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
                if (sprintFrom in 0..n && sprintTo < 0) {
                    // Only if he is still in the segment (he can have walked on during the lag).
                    val sec = sprintSec
                    val inIt = if (sec == 1) s >= S1_ENTRY || s < BOUNDS[1] else segment(s) == sec - 1
                    if (inIt) { sprintTo = SPRINT_TO[sec - 1]; speed = SPRINT }
                    sprintFrom = -1
                }
                if (sprintTo >= 0) {
                    // Distance left, round the loop's seam (a sprint from the S4 line into S1).
                    val left = ((sprintTo - s) % LOOP + LOOP) % LOOP
                    if (left <= SPRINT) { s = sprintTo; sprintTo = -1.0; speed = WALK } else s += SPRINT
                } else s += walkStep(n - spawnedN)
                if (s >= LOOP) firstLap = false
                s %= LOOP
                pos = trackPos(s)
            }
            val yaw = when {
                arrived -> 0f
                flying -> Math.toDegrees(Math.atan2(-(CORE_POINT.x - pos.x), CORE_POINT.z - pos.z)).toFloat()
                else -> yaw()
            }
            boss.moveTo(pos, pos.add(-Math.sin(Math.toRadians(yaw.toDouble())), 0.0, Math.cos(Math.toRadians(yaw.toDouble()))))
            if (giants.isNotEmpty()) orbitGiants()
        }

        private fun yaw(): Float {
            // Walking direction along the loop (S1: +z, S2: -x, S3: -z, S4: +x).
            return when (segment(s)) { 0 -> 0f; 1 -> 90f; 2 -> 180f; else -> -90f }
        }

        companion object {
                const val WALK = 0.06
            /** FLOW-04: he walks 0.048 a tick for the first ~194 ticks (median of 14 runs, 0.034-0.061), then 0.06. */
            const val SLOW_WALK = 0.048
            const val SLOW_TICKS = 194
            fun walkStep(age: Int) = if (age < SLOW_TICKS) SLOW_WALK else WALK
            fun walkDist(age: Int) = if (age <= SLOW_TICKS) SLOW_WALK * age else SLOW_WALK * SLOW_TICKS + WALK * (age - SLOW_TICKS)
            /** FLOW-03: a catch-up sprint starts 48-59 ticks after its door opens (5 runs, back-solved). */
            const val SPRINT_LAG_MIN = 48
            const val SPRINT_LAG_SPREAD = 12
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
            /**
             * Where a catch-up sprint ends, at the corner (FLOW-03, recorder-2: last sprint-speed sample S2 s 90.1-91.4, S3
             * 181.8-182.0, one step of 0.6 more at most). S4 is unremeasured (one sample, 273.5; boss recorder 274.3-276.5).
             */
            val SPRINT_TO = doubleArrayOf(91.2, 182.3, 272.8)
            val CORE_POINT = Vec3(53.6, 117.0, 40.0)
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

    /** [left]: a left click credits the lever without moving it or a click sound (LEV-01). */
    fun pullLever(st: Station, by: String, left: Boolean = false) {
        val lever = st.lever ?: return
        if (st.done) { if (by == Sim.me) Sim.chat("§cSomeone has already activated this lever!"); return }
        // LEV-02: another section's lever is vanilla's toggle with the click sound; no chat (never recorded), no credit.
        if (st.section != section) { if (by == Sim.me && !left) SimItems.vanillaLeverToggle(lever); return }
        // LEV-03: vanilla's toggle: the state flips, and the pitch follows the new state.
        val nowOn = !(Blocks.get(lever)?.takeIf { it.hasProperty(LeverBlock.POWERED) }?.getValue(LeverBlock.POWERED) ?: false)
        if (!left) Blocks.get(lever)?.takeIf { it.hasProperty(LeverBlock.POWERED) }?.let { Blocks.set(lever, it.setValue(LeverBlock.POWERED, nowOn)) }
        if (!left) Sim.sound(SoundEvents.LEVER_CLICK, 0.3f, if (nowOn) 0.59f else 0.49f, Vec3.atCenterOf(lever))
        complete(st, by)
        // The lever's stand renames 1-3 ticks after the pull, not on the 20-tick grid (devices.md §5).
        if (st.done) Fight.later(1, "lever stand") { if (Fight.phase === this) st.refreshStands() }
    }

    /** A click on a terminal's stand. */
    fun useTerminal(st: Station) {
        val p = Sim.player ?: return
        // Red components, a tick after the click (TERM-17). No "already using" lock on Hypixel (TERM-06).
        if (st.done) { Fight.later(1, "term refusal") { Sim.chatStyled("§cThis Terminal has already been completed!") }; return }
        if (st.section != section) { Fight.later(1, "term refusal") { Sim.chatStyled("§cThis Terminal doesn't seem to be responsive at the moment.") }; return }
        // The window opens a tick after the click (99 of 114 on Hypixel), on top of the ping.
        Fight.later(1, "term open") { Terminals.open(p, st) }
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
