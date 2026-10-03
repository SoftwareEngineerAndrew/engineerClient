package com.engineerclient.p3sim

import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents

/**
 * The three invincibility items, as Hypixel procs them on a hit that would kill you: the first off
 * cooldown of Spirit Mask (30 s), Bonzo's Mask (180 s) and Phoenix (60 s), with the game's chat
 * lines (Odin's invincibility timer and Masks Used read them). With none left you die: back to the
 * start of the section in progress.
 */
object Masks {
    private class Item(val name: String, val cooldown: Int, val line: String) { var readyAt = 0 }

    private val items = listOf(
        Item("Spirit Mask", 600, "§r§6Second Wind Activated!§r§a Your Spirit Mask saved your life!"),
        Item("Bonzo's Mask", 3600, "§r§aYour §r§9⚚ Bonzo's Mask§r§a saved your life!"),
        Item("Phoenix", 1200, "§r§aYour Phoenix Pet saved you from certain death!"),
    )

    /** Invincible until (after a proc: 3 s). */
    private var safeUntil = 0

    fun reset() { items.forEach { it.readyAt = 0 }; safeUntil = 0 }

    /** Ready items, for the menu. */
    fun status(): String = items.joinToString(" ") { val left = it.readyAt - Fight.serverTick; if (left <= 0) "§a${it.name}" else "§c${it.name} ${(left + 19) / 20}s" }

    fun hit(p: ServerPlayer, by: String) {
        val now = Fight.serverTick
        if (now < safeUntil) return
        val item = items.firstOrNull { it.readyAt <= now }
        if (item != null) {
            item.readyAt = now + item.cooldown
            safeUntil = now + if (item.name == "Phoenix") 80 else 60
            Sim.chat(item.line)
            Sim.sound(SoundEvents.TOTEM_USE, 0.4f, 1.4f)
            return
        }
        Sim.chat("§r§c ☠ §r§7You were killed by $by§r§7 and became a ghost§r§7.")
        Sim.title("§cYou died", "§7No masks left", 0, 40, 10)
        val phase = Fight.phase as? GoldorPhase
        val spot = phase?.let { Spots.p3Start(it.section.coerceIn(1, 5)) } ?: Spots.LOBBY
        Sim.tp(p, spot.x, spot.y, spot.z, spot.yaw, spot.pitch)
        safeUntil = now + 60
    }
}
