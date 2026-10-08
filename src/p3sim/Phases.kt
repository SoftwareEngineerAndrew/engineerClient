package com.engineerclient.p3sim

import net.minecraft.world.entity.EntityTypes
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.world.BossEvent
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import net.minecraft.world.phys.Vec3
import kotlin.math.min
import kotlin.random.Random
import net.minecraft.world.level.block.Blocks as B

/**
 * A boss as Hypixel shows it: a plain wither (no name of its own; [inv] invulnerable ticks, 200 for
 * Maxor's smaller pale look), its blue armour driven by fake health (1 = on, 1000 = off), and its
 * name on a separate marker stand 3.7 above it (3.6875 client side: Hypixel's 1/32 positions), as
 * `§e﴾ §r§8<U+E085>§r§5<U+E073> §r§c§l<Name>§r §e﴿` in every recording (the two are Hypixel's
 * resource-pack icons). The name stand can come [tagDelay] ticks after the wither (Maxor's: ~17).
 * Each `[BOSS]` line also shows a `§4§l<line>` stand 4.1 above it for ~41 ticks ([speak]). Moved by
 * the phase; no AI.
 */
class BossWither(val name: String, at: Vec3, inv: Int = 1, armoured: Boolean = true, tagDelay: Int = 0) {
    val e: WitherBoss = SimWither(Sim.level).also { w ->
        w.setNoAi(true); w.isSilent = true; w.isInvulnerable = true; w.setNoGravity(true)
        w.invulnerableTicks = inv
        w.health = if (armoured) 1f else w.maxHealth
        w.snapTo(at.x, at.y, at.z, 0f, 0f)
        BossBar.hideOwn(w)
        Sim.spawn(w)
    }
    private var tag: ArmorStand? = null
    private var speech: ArmorStand? = null
    private var speechText = ""
    private var speechUntil = -1
    private var removed = false
    var pos: Vec3 = at
        private set

    init {
        live[name] = this
        if (tagDelay <= 0) showTag() else Fight.later(tagDelay, "$name name") { if (!removed) showTag() }
    }

    private fun showTag() {
        tag = marker("§e\uFD3E §r§8\uE085§r§5\uE073 §r§c§l$name§r §e\uFD3F", TAG_Y)
        if (Fight.serverTick < speechUntil) showSpeech()
    }

    private fun marker(text: String, dy: Double) = ArmorStand(EntityTypes.ARMOR_STAND, Sim.level).also { s ->
        s.isInvisible = true; s.setNoGravity(true); s.isInvulnerable = true; s.isSilent = true
        Station.setMarker(s)
        s.setCustomName(Sim.legacy(text))
        s.isCustomNameVisible = true
        s.snapTo(pos.x, pos.y + dy, pos.z, 0f, 0f)
        Sim.spawn(s)
    }

    /**
     * A `[BOSS]` line's stand over the wither, from the tick of the line for [SPEECH_TICKS] (recordings:
     * same tick, gone 41-42 later when watched throughout). Said before the name stand is
     * up (Maxor's first line), it comes with the name stand, and goes at the same time.
     */
    fun speak(line: String) {
        if (removed) return
        val text = "§4§l$line"
        speechText = text
        speechUntil = Fight.serverTick + if (name == "Goldor") GOLDOR_SPEECH_TICKS else SPEECH_TICKS
        speech?.discard(); speech = null
        // STANDS-05: Hypixel's speech stand spawns in the same tick as the chat line (+0 in 66 of 72 Goldor, 119 of 123 Necron).
        if (tag != null) showSpeech()
    }

    private fun showSpeech() {
        speech?.discard()
        val s = marker(speechText, SPEECH_Y)
        speech = s
        Fight.later(speechUntil - Fight.serverTick, "$name speech end") { if (speech === s) { s.discard(); speech = null } }
    }

    /** Blue armour on (health 1) or off (health above half). */
    fun armour(on: Boolean) { if (e.health > 0f) e.health = if (on) 1f else e.maxHealth }
    val armoured get() = e.health > 0f && e.health <= e.maxHealth / 2

    /** The vanilla death animation (health 0); remove it ~20 ticks later. */
    fun dieAnim() { e.health = 0f; tag?.discard(); speech?.discard() }

    fun moveTo(p: Vec3, faceTo: Vec3? = null) {
        pos = p
        val f = faceTo ?: p
        val yaw = if (faceTo != null) Math.toDegrees(Math.atan2(-(f.x - p.x), f.z - p.z)).toFloat() else e.yRot
        e.snapTo(p.x, p.y, p.z, yaw, 0f)
        e.yHeadRot = yaw; e.yBodyRot = yaw
        tag?.snapTo(p.x, p.y + TAG_Y, p.z, 0f, 0f)
        speech?.snapTo(p.x, p.y + SPEECH_Y, p.z, 0f, 0f)
    }

    /** One step toward [target] of at most [speed]; true when there. */
    fun step(target: Vec3, speed: Double, face: Vec3? = target): Boolean {
        val d = target.subtract(pos)
        val len = d.length()
        if (len <= speed) { moveTo(target, face); return true }
        moveTo(pos.add(d.scale(speed / len)), face)
        return false
    }

    fun remove() {
        removed = true
        e.discard(); tag?.discard(); speech?.discard()
        if (live[name] === this) live.remove(name)
    }

    companion object {
        const val TAG_Y = 3.6875
        const val SPEECH_Y = 4.09375
        const val SPEECH_TICKS = 41
        /** Goldor's line stands last longer: mode 61 recorded (FLOW-14). */
        const val GOLDOR_SPEECH_TICKS = 61
        private val live = HashMap<String, BossWither>()

        /** From [Sim.boss]: the line's stand over that boss, while it is up. */
        fun speak(name: String, line: String) { live[name]?.takeIf { !it.removed && !it.e.isRemoved }?.speak(line) }
    }
}

/**
 * A boss's wither. The sim world is peaceful (no mobs of its own), and vanilla deletes every wither in a peaceful
 * world each tick; this one stays. With no AI it never charges up, shoots or breaks blocks.
 */
class SimWither(level: net.minecraft.world.level.Level) : WitherBoss(EntityTypes.WITHER, level) {
    override fun checkDespawn() {}
}

/**
 * The fight's one boss bar (Hypixel's: `§c§lMaxor`... with its own progress). The withers' own
 * bars are hidden.
 */
object BossBar {
    private var bar: ServerBossEvent? = null

    fun show(name: String, progress: Float = 1f) {
        val b = bar ?: ServerBossEvent(java.util.UUID.randomUUID(), Sim.legacy(name),
            BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS).also { bar = it }
        b.name = Sim.legacy(name)
        b.progress = progress.coerceIn(0f, 1f)
        val p = Sim.player
        if (p != null && p !in b.players) { b.removeAllPlayers(); b.addPlayer(p) }
    }

    fun progress(f: Float) { bar?.progress = f.coerceIn(0f, 1f) }

    /** Sends the bar's name, style and progress again (as main does once a second), unchanged. */
    fun resend() {
        val b = bar ?: return
        for (p in b.players) {
            p.connection.send(net.minecraft.network.protocol.game.ClientboundBossEventPacket.createUpdateNamePacket(b))
            p.connection.send(net.minecraft.network.protocol.game.ClientboundBossEventPacket.createUpdateStylePacket(b))
            p.connection.send(net.minecraft.network.protocol.game.ClientboundBossEventPacket.createUpdateProgressPacket(b))
        }
    }
    val progress: Float get() = bar?.progress ?: 0f

    fun hide() { bar?.removeAllPlayers(); bar = null }

    private val ownField by lazy {
        WitherBoss::class.java.declaredFields.firstOrNull { ServerBossEvent::class.java.isAssignableFrom(it.type) }?.apply { isAccessible = true }
    }

    /** A wither's own boss bar, never shown (the fight's bar is [show]'s). */
    fun hideOwn(w: WitherBoss) { runCatching { (ownField?.get(w) as? ServerBossEvent)?.isVisible = false } }
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
 * P1, Maxor (docs/mechanics/maxor.md, tools/p3sim/research/p1-maxor.md). The intro on 62-tick lines;
 * crystals on the top platforms from +5 (their "Energy Crystal" / "CLICK HERE" stands ~23 later):
 * click one (or a stand) to pick it up, which puts it in your SkyBlock Menu slot and selects it.
 * Everything else runs on the 10-tick checks (t 6 mod 10):
 *  - the pylons open on the +166 check (their stands appear: "Energy Crystal Missing" / "CLICK
 *    HERE"); a placement registers one tick after a check, and says "X/2" with X = 1 + this cycle's
 *    earlier placements already charged (28 ticks) (§4.2). The stands say "Crystal Active" ~20 later;
 *    the pylon's power line (19 sea lanterns in the floor, then its half of the ceiling T, then for
 *    the cycle's first crystal the centre and the column toward Maxor) lights ~1 block a tick;
 *  - "The Energy Laser is charging up!" 28 after the second placement; the beacon goes in on the
 *    +206 check whatever the crystals do; the column (73, 223-224, 73, and 222 from beacon + 120)
 *    shows yellow / red / black;
 *  - a hit: a check, the laser charged, the beacon in, 200 ticks since the last hit, him within
 *    3.5 of (73.5, 73.5) with his feet at y 225+ (§4.4). The laser resets: the lines go dark from
 *    hit + 40, top crystals back at + 41, placed ones gone at + 42, pylons open again at + 70.
 * A stun takes his armour off and freezes him facing south until "⚠ Maxor is enraged! ⚠" (the
 * party's damage: mostly 6-12 ticks with a party, 240 at most), and he moves 1-5 ticks after it.
 * Taunt A comes 161 after the beacon (never stunned) or 161-164 after an enrage; a hit inside it is
 * silent, its stun line 62 after the taunt (§5.3). He dies 0-13 after the second stun line (at once
 * after a silent hit), "I'M TOO YOUNG TO DIE AGAIN!" at stun 2 + 82 if he's still there, despawn at
 * kill + 80, Storm at kill + 102. The bar is his health, resent once a second: 0.95 at a hit, falling
 * while stunned, 0.25 once he breaks free, ~0.2 at the second hit, 0 at the kill.
 *
 * With bots, two carry (pick up, wait at their pylon, go back up for the respawn: the recordings'
 * flow) and one lures him from (73.6, 225, 77.2); you can take either crystal yourself.
 */
class P1Maxor : Fight.Phase("P1") {
    override val restart get() = Fight.Start.P1
    private lateinit var maxor: BossWither

    /** A crystal on a top platform with its two stands (the first pair's come later, [labels]). */
    private inner class Top(val side: Int, stands: Boolean) {
        val e = spawnCrystal(TOPS[side])
        var name: ArmorStand? = null
        var click: ArmorStand? = null
        init { if (stands) labels() }
        fun labels() {
            if (name != null || e.isRemoved) return
            name = label(TOPS[side].add(0.0, 0.125, 0.0), "§bEnergy Crystal")
            click = label(TOPS[side].add(0.0, -0.25, 0.0), "§e§lCLICK HERE")
        }
        fun owns(x: Entity) = x === e || x === name || x === click
        fun remove() { e.discard(); name?.discard(); click?.discard() }
    }

    /** A pylon: its two stands from the laser start, the crystal on it once placed. */
    private inner class Pylon(val side: Int) {
        var crystal: EndCrystal? = null
        var top: ArmorStand? = null
        var bottom: ArmorStand? = null
        val placed get() = crystal != null
        fun open() {
            if (top != null) return
            top = label(PYLONS[side].add(0.0, -0.875, 0.0), "§cEnergy Crystal Missing")
            bottom = label(PYLONS[side].add(0.0, -1.25, 0.0), "§e§lCLICK HERE")
        }
        fun active() { rename(top, "§aCrystal Active"); rename(bottom, "") }
        fun missing() { rename(top, "§cEnergy Crystal Missing"); rename(bottom, "§e§lCLICK HERE") }
        fun owns(x: Entity) = x === crystal || x === top || x === bottom
        fun clear() { crystal?.discard(); crystal = null }
        fun remove() { clear(); top?.discard(); bottom?.discard(); top = null; bottom = null }
    }

    /** A bot carrying for one side: fetches the crystal, waits at a pylon, goes back up after a hit. */
    private inner class Carrier(val bot: Party.Bot, val side: Int) {
        var holding = false
        var leaveAt = 5 + Random.nextInt(20)
        var pylon = side
        /** Which top crystal it goes for (its side's, unless covering for you). */
        var fetch = side
    }

    /** When the pylons last opened (the laser start, or a reset at hit + 70). */
    private fun lastOpen() = if (lastHit < 0) 166 else maxOf(166, lastHit + 70)

    private val tops = arrayOfNulls<Top>(2)
    private val pylons = arrayOf(Pylon(0), Pylon(1))
    private val carriers = ArrayList<Carrier>()
    private var lure: Party.Bot? = null
    private var carrying = 0
    /** Your hotbar slot the crystal is in (the SkyBlock Menu's). */
    private var crystalSlot = -1
    /** Placement ticks this laser cycle (the "X/2" counter). */
    private val placeTimes = ArrayList<Int>()
    /** Placements clicked, taken on the next check (pylon to who). */
    private val pending = HashMap<Int, String>()
    private var open = false
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
    /** His health as the bar shows it, and where it stops falling in this stun. */
    private var health = 1f
    private var healthFloor = 1f
    private var healthRate = 0f
    /** Power-line block changes by tick (true: sea lantern, false: coal). */
    private val lights = HashMap<Int, MutableList<Pair<BlockPos, Boolean>>>()
    private var centreLit = false
    private val TAUNTS_A = listOf(
        "YOUR WEAPONS CAN'T PIERCE THROUGH MY SHIELD!", "YOUR MOBILITY TRICKS DON'T WORK IN MY DOMAIN!",
        "I HOPE YOU LIKE EXPLOSIONS TOO!", "MY MINIONS WILL HAVE TO WIPE THE FLOOR AFTER I'M DONE WITH YOU ALL!",
    )
    /** The skull-volley taunts: ~11 % of runs, one of them at t 534-572 (chat-attacks.md §1.1). */
    private val TAUNTS_SKULL = listOf("How about you taste some rapid fire Wither Skulls!", "Time for me to blast you away for good!", "Eat Wither Skulls, scum!")
    private val skullTauntAt = if (Random.nextInt(100) < 11) 534 + Random.nextInt(39) else -1

    override fun start() {
        val p = Sim.player ?: return
        val s = Spots.P1
        Sim.tp(p, s.x, s.y, s.z, s.yaw, s.pitch)
        SimItems.giveHotbar(p, p3 = false)
        BossBar.show("§c§lMaxor", 1f)
        Sim.boss("Maxor", "WELL! WELL! WELL! LOOK WHO'S HERE!")
        stripStart()
        Blocks.play("p1strip", exclude = COLUMN)
        Party.standAt(listOf(Vec3(71.5, 221.0, 16.5), Vec3(75.5, 221.0, 16.5), Vec3(69.5, 221.0, 18.5), Vec3(77.5, 221.0, 18.5)))
        // Roles by class (party.md §6): Berserk west crystal, Mage east, Tank lures under the laser,
        // Healer goes down to Storm, Archer waits at the start and drops at ~+15 s. Yours is left to you.
        val bots = if (P3Sim.bots) Party.bots().filter { it.entity != null } else emptyList()
        fun of(c: DungeonClass) = bots.firstOrNull { it.clazz == c }
        of(DungeonClass.BERSERK)?.let { carriers += Carrier(it, 0) }
        of(DungeonClass.MAGE)?.let { carriers += Carrier(it, 1) }
        lure = of(DungeonClass.TANK)
        healer = of(DungeonClass.HEALER)
        archer = of(DungeonClass.ARCHER)
    }

    private var healer: Party.Bot? = null
    private var archer: Party.Bot? = null

    override fun stop() {
        tops.forEach { it?.remove() }
        pylons.forEach { it.remove() }
        if (::maxor.isInitialized) maxor.remove()
        Blocks.stop("p1strip")
        if (carrying > 0) { carrying = 0; giveBackMenu() }
    }

    private fun check() = t % 10 == 6

    override fun tick() {
        when (t) {
            5 -> {
                maxor = BossWither("Maxor", Vec3(73.0, 226.0, 53.0), inv = 200, tagDelay = 17)
                // His first line's stand comes up with his name stand, gone ~48 after the line.
                maxor.speak("WELL! WELL! WELL! LOOK WHO'S HERE!")
                for (i in 0..1) tops[i] = Top(i, stands = false)
            }
            // The first pair's stands: ~23 after the crystals (17-40 in the recordings).
            28 -> tops.forEach { it?.labels() }
            62 -> Sim.boss("Maxor", "I'VE BEEN TOLD I COULD HAVE A BIT OF FUN WITH YOU.")
            124 -> Sim.boss("Maxor", "DON'T DISAPPOINT ME, I HAVEN'T HAD A GOOD FIGHT IN A WHILE.")
            166 -> {
                open = true
                Blocks.set(BlockPos(73, 221, 73), B.AIR.defaultBlockState()); Blocks.set(BlockPos(73, 222, 73), B.AIR.defaultBlockState())
                pylons.forEach { it.open() }
                Sim.note("The pylons take crystals now: pick one up on top, right-click a pylon (or let the bots).")
            }
            206 -> { beacon = true; Blocks.set(BlockPos(73, 221, 73), B.BEACON.defaultBlockState()) }
        }
        lights.remove(t)?.forEach { (p, on) -> Blocks.set(p, if (on) B.SEA_LANTERN.defaultBlockState() else B.COAL_BLOCK.defaultBlockState()) }
        if (!::maxor.isInitialized) return
        if ((t + 3) % 20 == 0) BossBar.progress(health)
        if (killAt >= 0) { killed(); return }
        bots()
        // Placements: one tick after a check.
        if (open && t % 10 == 7) {
            pending.forEach { (i, by) -> place(i, by) }
            pending.clear()
        }
        if (chargeAt >= 0 && t == chargeAt) Sim.chat("§aThe Energy Laser is charging up!")
        if (check()) column()
        // Taunt A (an ability: a hit inside it is silent).
        if (t == tauntAt) Sim.boss("Maxor", TAUNTS_A.random())
        if (t == skullTauntAt && !stunned && !inAbility()) Sim.boss("Maxor", TAUNTS_SKULL.random())
        if (stunned && t == enrageAt) {
            Sim.chat("§c⚠ Maxor is enraged! ⚠")
            stunned = false; moveAt = t + 1 + Random.nextInt(5); tauntAt = t + 161 + Random.nextInt(4)
            health = 0.25f
            maxor.armour(true)
        }
        if (t == 205 && tauntAt < 0) tauntAt = 206 + 161
        // Stunned: armour off (health 1000; now and then a 1-tick flicker back to 1), his health falling.
        if (stunned) {
            maxor.armour(Random.nextInt(25) == 0)
            health = maxOf(healthFloor, health - healthRate)
        }
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
        val c = EndCrystal(EntityTypes.END_CRYSTAL, Sim.level)
        c.setShowBottom(false)
        c.isInvulnerable = true
        c.snapTo(at.x, at.y, at.z, 0f, 0f)
        return Sim.spawn(c)
    }

    /** A crystal's name stand: invisible, not a marker (you click them), as Hypixel's (flags 2). */
    private fun label(at: Vec3, text: String): ArmorStand {
        val s = ArmorStand(EntityTypes.ARMOR_STAND, Sim.level)
        s.isInvisible = true; s.setNoGravity(true); s.isInvulnerable = true; s.isSilent = true
        rename(s, text)
        s.snapTo(at.x, at.y, at.z, 0f, 0f)
        return Sim.spawn(s)
    }

    private fun rename(s: ArmorStand?, text: String) {
        s ?: return
        s.setCustomName(Sim.legacy(text))
        s.isCustomNameVisible = text.isNotEmpty()
    }

    // ------------------------------------------------------------------ crystals

    /** A click on a crystal or its stands, or a pylon's: picks up, places, or Hypixel's refusal. */
    fun useCrystal(c: Entity): Boolean {
        val top = tops.firstOrNull { it != null && it.owns(c) }
        if (top != null) {
            if (killAt >= 0) return true
            take(top)
            carrying++
            Sim.chat("§b${Sim.me}§r§a picked up an §r§bEnergy Crystal§r§a!")
            holdCrystal()
            return true
        }
        val pylon = pylons.firstOrNull { it.owns(c) } ?: return false
        if (pylon.placed || pylon.side in pending || killAt >= 0) return true
        if (carrying <= 0) Sim.chat("§cYou do not current have an §r§bEnergy Crystal§r§c! Find one and bring it here!")
        else usePylon(PYLONS[pylon.side])
        return true
    }

    private fun take(top: Top) {
        top.remove()
        tops[top.side] = null
    }

    /** A right click near a pylon while carrying: placed on the next check. */
    fun usePylon(at: Vec3): Boolean {
        if (carrying <= 0 || !open || killAt >= 0) return false
        val i = PYLONS.indexOfFirst { Math.hypot(it.x - at.x, it.z - at.z) < 3.5 && Math.abs(it.y - at.y) < 4 }
        if (i < 0 || pylons[i].placed || i in pending) return false
        carrying--
        pending[i] = Sim.me
        return true
    }

    /**
     * Hypixel puts the crystal in your SkyBlock Menu slot and selects that slot (51 of 54 own pickups:
     * slot 9 selected 0-2 ticks after the line); the menu comes back once placed.
     */
    private fun holdCrystal() {
        val p = Sim.player ?: return
        val inv = p.inventory
        if (crystalSlot < 0) crystalSlot = (0..8).firstOrNull { SimItems.idOf(inv.getItem(it)) == "SKYBLOCK_MENU" } ?: 8
        inv.setItem(crystalSlot, crystalItem())
        inv.selectedSlot = crystalSlot
        p.connection.send(ClientboundSetHeldSlotPacket(crystalSlot))
        p.inventoryMenu.broadcastChanges()
    }

    private fun giveBackMenu() {
        val p = Sim.player ?: return
        val slot = crystalSlot.takeIf { it >= 0 } ?: return
        crystalSlot = -1
        if (SimItems.idOf(p.inventory.getItem(slot)) == "MAXOR_ENERGY_CRYSTAL") p.inventory.setItem(slot, SimItems.MENU)
        p.inventoryMenu.broadcastChanges()
    }

    /** The carried crystal: a nether star with SkyBlock id MAXOR_ENERGY_CRYSTAL (its name isn't recorded). */
    private fun crystalItem(): ItemStack {
        val s = ItemStack(Items.NETHER_STAR)
        s.set(DataComponents.CUSTOM_NAME, Component.literal("§bEnergy Crystal").withStyle { it.withItalic(false) })
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().also { it.putString("id", "MAXOR_ENERGY_CRYSTAL"); it.putBoolean("p3sim", true) }))
        return s
    }

    private fun place(i: Int, by: String) {
        val pylon = pylons[i]
        if (pylon.placed) return
        pylon.crystal = spawnCrystal(PYLONS[i])
        if (by == Sim.me && carrying == 0) giveBackMenu()
        val x = 1 + placeTimes.count { t >= it + 28 }
        placeTimes += t
        // Green from 2 on ("§a2/2", "§a3/2"; chat-attacks.md §1.2).
        Sim.chat(if (x >= 2) "§a$x/2 Energy Crystals are now active!" else "§c$x§r§a/2 Energy Crystals are now active!")
        // The stands' names change on Hypixel's next refresh (3-22 ticks after the line).
        val c = pylon.crystal
        Fight.later(20, "maxor pylon active") { if (Fight.phase === this && pylon.crystal === c && c != null) pylon.active() }
        light(t, i, on = true, centre = !centreLit)
        centreLit = true
        if (pylons.all { it.placed } && chargeAt < 0) chargeAt = t + 28
    }

    // ------------------------------------------------------------------ the power lines

    /**
     * One pylon's power line from [at]: its 19 floor lanterns from the pylon, ~1 a tick, then its half
     * of the ceiling T from the outer end, then (the cycle's first crystal, or going dark) the centre
     * and the column toward Maxor (8 recordings, every placement and every hit + 40).
     */
    private fun light(at: Int, side: Int, on: Boolean, centre: Boolean) {
        FLOORS[side].forEachIndexed { i, p -> queue(at + i, p, on) }
        CEILINGS[side].forEachIndexed { j, p -> queue(at + 19 + j, p, on) }
        if (centre) CENTRE.forEachIndexed { k, p -> queue(at + 24 + k, p, on) }
    }

    private fun queue(at: Int, p: BlockPos, on: Boolean) { lights.getOrPut(at) { ArrayList() } += p to on }

    /** The beam column on the checks: yellow (one charged), red (armed), black; 222 too from beacon + 120. */
    private fun column() {
        val n = placeTimes.count { t >= it + 28 }
        val state = when {
            charged() && beacon -> B.STAINED_GLASS.red()
            n >= 1 -> B.STAINED_GLASS.yellow()
            else -> B.STAINED_GLASS.black()
        }.defaultBlockState()
        for (y in (if (t >= 206 + 120) 222 else 223)..224) if (Blocks.get(BlockPos(73, y, 73)) != state) Blocks.set(BlockPos(73, y, 73), state)
    }

    // ------------------------------------------------------------------ the conveyor strip

    /** The strip as it is just before its first shift (the recording starts at that shift). */
    private fun stripStart() {
        val blocks = Blocks.extra("anims-p124.json", "p1stripStart")?.getAsJsonArray("blocks") ?: return
        for (e in blocks) {
            val a = e.asJsonArray
            val pos = BlockPos(a[0].asInt, a[1].asInt, a[2].asInt)
            if (pos in COLUMN) continue
            Blocks.set(pos, Arena.parse(a[3].asString))
        }
    }

    // ------------------------------------------------------------------ bots

    /**
     * The bots' P1 (recordings: the carriers take the crystals at ~50-90 and wait on their pylon; the
     * lure stands 3-4 south of the beam; after a hit the carriers go back up for the respawn at + 41).
     */
    private fun bots() {
        lure?.let { if (t >= 5) walk(it, LURE, 0.7) }
        healer?.let { if (t >= 20) walk(it, STORM_HEALER, 0.9) }
        archer?.let { if (t >= 300) walk(it, STORM_ARCHER, 0.9) }
        for (c in carriers) {
            if (c.bot.entity == null) continue
            if (!c.holding) {
                // Done for this cycle, but a crystal nobody carries (yours to do) still up after 3 s
                // of open pylons: fetch that one too, so the fight never stalls.
                if (c.leaveAt == Int.MAX_VALUE && carrying == 0 && open) {
                    val orphan = (0..1).firstOrNull { s -> tops[s] != null && carriers.none { it.side == s } && t >= lastOpen() + 60 }
                    if (orphan != null) { c.fetch = orphan; c.leaveAt = t }
                }
                if (t < c.leaveAt) continue
                val top = tops[c.fetch]
                if (walk(c.bot, TOP_STANDS[c.fetch], 0.7) && top != null) {
                    take(top)
                    c.holding = true
                    Sim.chat("§a${c.bot.name}§r§a picked up an §r§bEnergy Crystal§r§a!")
                }
            } else {
                // Its own pylon, or the other if you took that one.
                if (pylons[c.pylon].placed || (c.pylon in pending && pending[c.pylon] != c.bot.name)) c.pylon = 1 - c.pylon
                if (walk(c.bot, PYLON_STANDS[c.pylon], 0.7) && open && !pylons[c.pylon].placed && c.pylon !in pending) {
                    pending[c.pylon] = c.bot.name
                    c.holding = false
                    c.leaveAt = Int.MAX_VALUE
                }
            }
        }
    }

    /** One step of [b] toward [to] at [speed] a tick (straight, like the P3 bots); true once there. */
    private fun walk(b: Party.Bot, to: Vec3, speed: Double): Boolean {
        val d = to.subtract(b.pos)
        val len = d.length()
        if (len < 0.05) return true
        if (Math.abs(d.x) + Math.abs(d.z) > 0.01) b.yaw = Math.toDegrees(Math.atan2(-d.x, d.z)).toFloat()
        b.pos = if (len <= speed) to else b.pos.add(d.scale(speed / len))
        b.entity?.let { e -> e.snapTo(b.pos.x, b.pos.y, b.pos.z, b.yaw, 0f); e.yHeadRot = b.yaw; e.yBodyRot = b.yaw }
        return len <= speed
    }

    // ------------------------------------------------------------------ hits

    private fun hit() {
        hits++
        lastHit = t
        chargeAt = -1
        val at = t
        // The bar: 0.95 on the first resend after a hit; the second hit leaves ~0.12-0.20.
        if (hits == 1) { health = 0.95f; healthFloor = 0.30f + Random.nextFloat() * 0.35f; healthRate = 0.004f + Random.nextFloat() * 0.006f }
        else { health = 0.12f + Random.nextFloat() * 0.08f; healthFloor = 0.01f; healthRate = 0.01f }
        carriers.forEach { if (!it.holding) { it.fetch = it.side; it.leaveAt = at + 15 + Random.nextInt(10) } }
        Fight.later(40, "maxor lines dark") { if (Fight.phase === this) { light(t, 0, false, true); light(t, 1, false, false); centreLit = false } }
        Fight.later(41, "maxor crystals") { if (Fight.phase === this) for (i in 0..1) if (tops[i] == null) tops[i] = Top(i, stands = true) }
        Fight.later(42, "maxor pylons") { if (Fight.phase === this && killAt < 0) pylons.forEach { it.clear(); it.missing() } }
        Fight.later(70, "maxor reset") { if (Fight.phase === this) placeTimes.removeAll { it <= at + 42 } }
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
            health = 0f
            maxor.armour(false)
            Blocks.set(BlockPos(73, 221, 73), B.BEDROCK.defaultBlockState())
            // The placed crystals and the pylons' stands go with him; the strip stops.
            pylons.forEach { it.remove() }
            Blocks.stop("p1strip")
            if (carrying > 0) { carrying = 0; giveBackMenu() }
        }
        if (since == 59) maxor.dieAnim()
        if (since == 78) Blocks.play("p1end", skip = -24)
        if (since == 80) maxor.remove()
        if (since == 102) Fight.begin(P2Storm())
    }

    fun status() = when {
        killAt >= 0 -> "Maxor dead"
        hits > 0 -> "Hits $hits/2" + if (stunned) " (stunned)" else ""
        else -> "Crystals ${pylons.count { it.placed }}/2" + if (carrying > 0) " (carrying)" else ""
    }

    private companion object {
        val TOPS = listOf(Vec3(64.5, 238.375, 50.5), Vec3(82.5, 238.375, 50.5))
        val PYLONS = listOf(Vec3(52.5, 224.375, 41.5), Vec3(94.5, 224.375, 41.5))
        val BEAM = Vec3(73.5, 226.0, 73.5)
        /** Where carriers and the lure stand (recordings' medians). */
        val TOP_STANDS = listOf(Vec3(63.5, 238.0, 49.5), Vec3(83.5, 238.0, 49.5))
        val PYLON_STANDS = listOf(Vec3(51.8, 224.0, 40.6), Vec3(95.9, 224.0, 40.4))
        val LURE = Vec3(73.6, 225.0, 77.2)
        /** Where the Healer (from the start) and the Archer (+15 s) wait on Storm's floor (party.md §6). */
        val STORM_HEALER = Vec3(96.5, 165.0, 41.5)
        val STORM_ARCHER = Vec3(36.5, 170.0, 90.5)
        /** The beacon and its glass: driven here, not by the recorded strip. */
        val COLUMN = (221..224).map { BlockPos(73, it, 73) }.toSet()
        /** The floor power lines (coal / sea lantern, y221), pylon end first: 19 each. */
        val FLOORS = listOf(
            (56..64).map { BlockPos(it, 221, 41) } + (42..51).map { BlockPos(64, 221, it) },
            (45..48).map { BlockPos(94, 221, it) } + (93 downTo 82).map { BlockPos(it, 221, 48) } + (49..51).map { BlockPos(82, 221, it) },
        )
        /** The ceiling T's halves (y236 z64), outer end first, then the centre and the column toward Maxor. */
        val CEILINGS = listOf((68..72).map { BlockPos(it, 236, 64) }, (78 downTo 74).map { BlockPos(it, 236, 64) })
        val CENTRE = listOf(BlockPos(73, 236, 64)) + (65..69).map { BlockPos(73, 236, it) }
    }
}
