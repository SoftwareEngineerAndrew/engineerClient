package com.engineerclient.visual

import com.odtheking.odin.utils.itemId
import net.minecraft.world.item.ItemStack

enum class HellionShield(val cleanName: String, val chatColor: String, var active: Boolean = false) {
    AURIC("Auric", "§e"),
    ASHEN("Ashen", "§8"),
    SPIRIT("Spirit", "§f"),
    CRYSTAL("Crystal", "§b");

    fun other(): HellionShield = Dagger.entries.first { this in it.shields }.shields.first { it != this }

    companion object {
        fun fromText(text: String): HellionShield? = entries.find { text.contains(it.name, true) }
    }
}

enum class Dagger(private val displayName: String, val skyblockIds: Set<String>, val shields: List<HellionShield>, var updated: Boolean = false) {
    TWILIGHT("Twilight Dagger", setOf("HEARTMAW_DAGGER", "BURSTMAW_DAGGER", "MAWDUST_DAGGER"), listOf(HellionShield.SPIRIT, HellionShield.CRYSTAL)),
    FIREDUST("Firedust Dagger", setOf("HEARTFIRE_DAGGER", "BURSTFIRE_DAGGER", "FIREDUST_DAGGER"), listOf(HellionShield.ASHEN, HellionShield.AURIC));

    fun other(): Dagger = if (this == TWILIGHT) FIREDUST else TWILIGHT
    fun activeShield(): HellionShield = shields.firstOrNull { it.active } ?: shields[0]
    override fun toString() = displayName

    companion object {
        fun fromStack(stack: ItemStack?): Dagger? = stack?.itemId?.let { id -> entries.find { id in it.skyblockIds } }

        fun updateActiveFromTitle(titleText: String): HellionShield? {
            val shield = HellionShield.fromText(titleText) ?: return null
            for (dagger in entries.filter { shield in it.shields }) {
                dagger.shields.forEach { it.active = false }
                dagger.updated = true
            }
            shield.active = true
            return shield
        }
    }
}
