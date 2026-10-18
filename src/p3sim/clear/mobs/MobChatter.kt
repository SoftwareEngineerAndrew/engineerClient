package com.engineerclient.p3sim.clear.mobs

import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.SimStand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import kotlin.random.Random

/**
 * The idle talk of the clear's mobs: two mobs a few blocks apart trade one of two fixed three-line exchanges in gold text over
 * their heads. As recorded (ADR, 150 runs, 703 lines): mob A says lines 1 and 3, a mob 2 to 7 blocks away says line 2, a line every
 * 30 ticks, each a marker stand (flags 18) 2.375 above the speaker's feet facing its way that is gone after 30 ticks. A median 4.6
 * lines a minute of clear, so a conversation starts about every 40 seconds, among any built bodies (zombies, skeletons, wither
 * skeletons, endermen; never a fake player). A speaker that dies cuts the rest of its lines.
 *
 * Server thread only; nothing here is saved (a savepoint simply ends the conversation).
 */
internal class MobChatter {
    private class Talk(val a: MobEntry, val b: MobEntry, val lines: List<String>, var step: Int, var nextAt: Int)

    private var talk: Talk? = null
    private val stands = ArrayList<Pair<Entity, Int>>()

    fun tick(t: Int, entries: Collection<MobEntry>, rng: Random) {
        stands.removeAll { (s, until) -> if (t >= until || s.isRemoved) { s.discard(); true } else false }
        val k = talk
        if (k != null) {
            if (t < k.nextAt) return
            val who = if (k.step == 1) k.b else k.a
            if (who.alive && who.live) say(who, k.lines[k.step], t)
            k.step++
            if (k.step >= k.lines.size) talk = null else k.nextAt = t + LINE_GAP
            return
        }
        if (rng.nextDouble() >= START_CHANCE) return
        val speakers = entries.filter { it.alive && it.live && !it.asleep && it.body?.let { b -> b !is AdsDisguise && !b.isInvisible } == true }
        if (speakers.size < 2) return
        val a = speakers[rng.nextInt(speakers.size)]
        val partners = speakers.filter { it !== a && Math.hypot(it.x - a.x, it.z - a.z).let { d -> d in 1.0..PARTNER_RANGE } }
        if (partners.isEmpty()) return
        val b = partners[rng.nextInt(partners.size)]
        val lines = CONVERSATIONS[rng.nextInt(CONVERSATIONS.size)]
        say(a, lines[0], t)
        talk = Talk(a, b, lines, 1, t + LINE_GAP)
    }

    private fun say(e: MobEntry, text: String, t: Int) {
        val body = e.body ?: return
        val s = SimStand(Sim.level)
        s.snapTo(body.x, body.y + HEIGHT, body.z, body.yRot, 0f)
        s.isInvisible = true
        s.isPermanentlyInvulnerable = true
        s.isSilent = true
        s.setNoGravity(true)
        runCatching { s.entityData.set(ArmorStand.DATA_CLIENT_FLAGS, FLAGS) }
        s.customName = Sim.legacy(text)
        s.isCustomNameVisible = true
        stands += Sim.spawn(s) to t + LIFE
    }

    /** Takes every line down (the run ended, a savepoint went back, the clear is over). */
    fun clear() {
        for ((s, _) in stands) s.discard()
        stands.clear()
        talk = null
    }

    companion object {
        val CONVERSATIONS = listOf(
            listOf("§6I almost died last week!", "§6Dude.. we're undead.", "§6Oh"),
            listOf("§6What was that?", "§6What?", "§6Thought I heard something..."),
        )
        const val LINE_GAP = 30
        const val LIFE = 30
        const val HEIGHT = 2.375
        const val PARTNER_RANGE = 7.0
        const val FLAGS: Byte = 18
        /** A conversation every 40 seconds or so (1.5 a minute: 4.6 lines a minute over three lines each). */
        const val START_CHANCE = 1.0 / 800.0
    }
}
