package com.devgineerclient.recorder

import com.devgineerclient.DevgineerClient
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.item.ItemStack

/**
 * The local player, every tick, at full precision: the `me` line (position, last position, every
 * rotation, motion, collisions, inputs, item use, swing, health, food, xp, abilities, camera, keys
 * held), `effects`, the inventory (`inv`, changed slots only) and item cooldowns (`cd`).
 *
 * The me line is written every tick and never deduplicated: an unchanged line is information too
 * (the player stood still that tick), and doubles and floats keep their exact value, so a mod can be built
 * against the very numbers the client had. The key map is the one exception: every key mapping's
 * state is a big, mostly constant object, so it rides along only when it changed (and in keyframes).
 *
 * Runs on the game thread from [DungeonRecorder]'s tick, after `Rec.tick++`, so items are frozen
 * into strings where they live. Gated by the module's "Client State" setting ([enabled]).
 */
object PlayerState {

    /** The module's "Client State" setting, pushed in by [DungeonRecorder]. */
    @Volatile var enabled = true

    private val slots = SlotTracker<ItemStack>(ItemStack::matches, ItemStack::copy)
    /** The session the trackers belong to; a new one starts them over. */
    private var forSession: Any? = null
    private var loggedFailure = false

    fun install() {
        // A keyframe holds the full inventory, effects, cooldowns and key map, so a reader starting
        // there needs nothing before it.
        Rec.onKeyframe("player") { _ -> if (enabled && Rec.active) DevgineerClient.mc.player?.let { full(it) } }
    }

    /** One tick (game thread). */
    fun tick() {
        if (!enabled || !Rec.active) return
        val p = DevgineerClient.mc.player ?: return
        val s = Rec.session
        if (s !== forSession) { forSession = s; slots.reset() }
        guard("me") { me(p) }
        guard("effects") { val e = effects(p); if (Rec.changed("pl.effects", e)) Rec.emit("effects", "\"d\":$e") }
        guard("inv") { inventory(p) }
        guard("cd") { val c = cooldowns(p); if (Rec.changed("pl.cd", c)) Rec.emit("cd", "\"s\":$c") }
    }

    private fun me(p: LocalPlayer) {
        val mc = DevgineerClient.mc
        val sb = StringBuilder(1024)
        sb.append("\"pos\":"); vec(sb, p.x, p.y, p.z)
        sb.append(",\"old\":"); vec(sb, p.xo, p.yo, p.zo)
        sb.append(",\"rot\":["); n(sb, p.yRot); sb.append(','); n(sb, p.xRot); sb.append(','); n(sb, p.yHeadRot); sb.append(','); n(sb, p.yBodyRot); sb.append(']')
        val v = p.deltaMovement
        sb.append(",\"vel\":"); vec(sb, v.x, v.y, v.z)
        sb.append(",\"ground\":").append(p.onGround())
        sb.append(",\"hcol\":").append(p.horizontalCollision)
        sb.append(",\"vcol\":").append(p.verticalCollision)
        sb.append(",\"vcolBelow\":").append(p.verticalCollisionBelow)
        sb.append(",\"fall\":"); n(sb, p.fallDistance)
        sb.append(",\"sprint\":").append(p.isSprinting)
        sb.append(",\"crouch\":").append(p.isCrouching)
        sb.append(",\"shift\":").append(p.isShiftKeyDown)
        sb.append(",\"pose\":").append(RecorderFiles.q(p.pose.name))
        val k = p.input.keyPresses
        sb.append(",\"in\":[").append(k.forward()).append(',').append(k.backward()).append(',').append(k.left()).append(',').append(k.right())
            .append(',').append(k.jump()).append(',').append(k.shift()).append(',').append(k.sprint()).append(']')
        val mv = p.input.moveVector
        sb.append(",\"mv\":["); n(sb, mv.x); sb.append(','); n(sb, mv.y); sb.append(']')
        sb.append(",\"use\":")
        if (p.isUsingItem) {
            sb.append("{\"item\":").append(RichJson.itemNow(p.useItem)).append(",\"left\":").append(p.useItemRemainingTicks)
                .append(",\"hand\":").append(RecorderFiles.q(p.usedItemHand.name)).append('}')
        } else sb.append("null")
        sb.append(",\"swing\":").append(p.swinging)
        sb.append(",\"swingArm\":").append(RecorderFiles.q(p.swingingArm?.name))
        sb.append(",\"swingTime\":").append(p.swingTime)
        sb.append(",\"atkAnim\":"); n(sb, p.attackAnim)
        sb.append(",\"hurt\":").append(p.hurtTime)
        sb.append(",\"hp\":"); n(sb, p.health)
        sb.append(",\"abs\":"); n(sb, p.absorptionAmount)
        sb.append(",\"maxHp\":"); n(sb, p.maxHealth)
        sb.append(",\"food\":").append(p.foodData.foodLevel)
        sb.append(",\"sat\":"); n(sb, p.foodData.saturationLevel)
        sb.append(",\"xp\":[").append(p.experienceLevel).append(','); n(sb, p.experienceProgress); sb.append(']')
        sb.append(",\"air\":").append(p.airSupply)
        sb.append(",\"armor\":").append(p.armorValue)
        sb.append(",\"water\":").append(p.isInWater)
        sb.append(",\"lava\":").append(p.isInLava)
        sb.append(",\"vehicle\":").append(p.vehicle?.id?.toString() ?: "null")
        val a = p.abilities
        sb.append(",\"abil\":{\"fly\":").append(a.flying).append(",\"mayfly\":").append(a.mayfly)
            .append(",\"walk\":"); n(sb, a.walkingSpeed); sb.append(",\"flySpd\":"); n(sb, a.flyingSpeed)
        sb.append(",\"instabuild\":").append(a.instabuild).append(",\"invulnerable\":").append(a.invulnerable).append(",\"mayBuild\":").append(a.mayBuild).append('}')
        sb.append(",\"cam\":").append(RecorderFiles.q(mc.options.cameraType.name))
        sb.append(",\"atk\":"); n(sb, p.getAttackStrengthScale(0f))
        sb.append(",\"slot\":").append(p.inventory.selectedSlot)
        val keys = keys()
        if (Rec.changed("pl.keys", keys)) sb.append(",\"keys\":").append(keys)
        // The block being broken and the destroy progress the client keeps.
        sb.append(InputCapture.mineMembers())
        Rec.emit("me", sb.toString())
    }

    /** Every key mapping's held state, by its name (`key.forward`, `key.attack`, ...; mods' too). */
    private fun keys(): String {
        val sb = StringBuilder(2048).append('{')
        var first = true
        for (m in DevgineerClient.mc.options.keyMappings) {
            if (!first) sb.append(','); first = false
            sb.append(RecorderFiles.q(m.name)).append(':').append(m.isDown)
        }
        return sb.append('}').toString()
    }

    /** [[id, amplifier, duration, ambient, visible, showIcon]] (duration -1 = infinite). */
    private fun effects(p: LocalPlayer): String {
        val sb = StringBuilder(128).append('[')
        var first = true
        for (e in p.activeEffects) {
            if (!first) sb.append(','); first = false
            sb.append('[').append(RecorderFiles.q(e.effect.registeredName)).append(',').append(e.amplifier).append(',').append(e.duration)
                .append(',').append(e.isAmbient).append(',').append(e.isVisible).append(',').append(e.showIcon()).append(']')
        }
        return sb.append(']').toString()
    }

    private fun stacks(p: LocalPlayer): List<ItemStack> {
        val inv = p.inventory
        return List(inv.containerSize) { inv.getItem(it) }
    }

    private fun inventory(p: LocalPlayer) {
        val changed = slots.diff(stacks(p))
        // The selected slot moving on its own is in `me`; here it goes along with slot changes.
        if (changed.isEmpty()) return
        val sel = p.inventory.selectedSlot
        val inv = p.inventory
        val sb = StringBuilder(256 * changed.size).append("\"sel\":").append(sel).append(",\"s\":[")
        changed.forEachIndexed { i, slot ->
            if (i > 0) sb.append(',')
            sb.append('[').append(slot).append(',').append(RichJson.itemNow(inv.getItem(slot))).append(']')
        }
        Rec.emit("inv", sb.append(']').toString())
    }

    /** [[slot, cooldown group, percent left]] for every stack on cooldown. */
    private fun cooldowns(p: LocalPlayer): String {
        val cds = p.cooldowns
        val inv = p.inventory
        val sb = StringBuilder(64).append('[')
        var first = true
        for (slot in 0 until inv.containerSize) {
            val st = inv.getItem(slot)
            if (st.isEmpty || !cds.isOnCooldown(st)) continue
            if (!first) sb.append(','); first = false
            sb.append('[').append(slot).append(',').append(RecorderFiles.q(cds.getCooldownGroup(st).toString())).append(',')
            n(sb, cds.getCooldownPercent(st, 0f)); sb.append(']')
        }
        return sb.append(']').toString()
    }

    /** The keyframe set: every slot, effects and cooldowns tagged with the keyframe id; the key map with the next me line. */
    private fun full(p: LocalPlayer) {
        val kf = Rec.keyframeId
        guard("inv.full") {
            val inv = p.inventory
            val all = stacks(p)
            slots.seed(all)
            val sb = StringBuilder(256 * all.size).append("\"kf\":").append(kf).append(",\"full\":true,\"sel\":").append(inv.selectedSlot).append(",\"s\":[")
            all.forEachIndexed { i, st -> if (i > 0) sb.append(','); sb.append('[').append(i).append(',').append(RichJson.itemNow(st)).append(']') }
            Rec.emit("inv", sb.append(']').toString())
        }
        guard("effects.full") { val e = effects(p); Rec.changed("pl.effects", e); Rec.emit("effects", "\"kf\":$kf,\"d\":$e") }
        guard("cd.full") { val c = cooldowns(p); Rec.changed("pl.cd", c); Rec.emit("cd", "\"kf\":$kf,\"s\":$c") }
        // The key map goes out with this tick's me line (keyframes run just before it).
        Rec.changed("pl.keys", "")
    }

    private inline fun guard(what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) {
            if (!loggedFailure) { loggedFailure = true; DevgineerClient.logger.error("[dc] recorder player $what failed", t) }
            Rec.emit("error", "\"p\":${RecorderFiles.q("player:$what")},\"err\":${RecorderFiles.q(t.toString())}")
        }
    }

    private fun vec(sb: StringBuilder, x: Double, y: Double, z: Double) {
        sb.append('['); n(sb, x); sb.append(','); n(sb, y); sb.append(','); n(sb, z); sb.append(']')
    }

    private fun n(sb: StringBuilder, d: Double) = PacketJson.num(sb, d)
    private fun n(sb: StringBuilder, f: Float) = PacketJson.num(sb, f)
}

/**
 * Which inventory slots changed since the last look. Each slot is compared against a copy taken
 * when it last changed ([same] is [ItemStack.matches] in the game: item, count and components),
 * not by object identity, since stacks are often changed in place (a count going down keeps the
 * same object) and a fresh object with the same contents is no change. Game thread only.
 */
class SlotTracker<T : Any>(private val same: (T, T) -> Boolean, private val copy: (T) -> T) {
    private var copies = ArrayList<T?>()

    fun reset() { copies = ArrayList() }

    /** Remembers [now] without reporting anything (a full set was just written). */
    fun seed(now: List<T>) {
        copies = now.mapTo(ArrayList(now.size)) { copy(it) }
    }

    /** The slots of [now] that differ from the last call (all of them the first time, or when the size changes). */
    fun diff(now: List<T>): List<Int> {
        if (now.size != copies.size) copies = ArrayList<T?>(now.size).also { l -> repeat(now.size) { l.add(null) } }
        val out = ArrayList<Int>(0)
        for (i in now.indices) {
            val st = now[i]
            val c = copies[i]
            if (c != null && same(c, st)) continue
            copies[i] = copy(st); out += i
        }
        return out
    }
}
