package com.engineerclient.visual

import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.SelectorSetting
import com.adm.events.MessageEvent
import com.adm.events.TickEvent
import com.adm.events.core.on
import com.adm.events.core.onReceive
import com.adm.events.core.onSend
import com.adm.features.Category
import com.adm.features.Module
import com.adm.utils.itemId
import com.adm.utils.loreString
import com.adm.utils.skyblock.LocationUtils
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.item.ItemStack

object BlazeDaggerHUD : Module(
    name = "Blaze Dagger HUD",
    category = Category.NETHER,
    description = "Shows the attunement of your Blaze Slayer daggers while holding one.",
    key = null,
) {
    private val hideWarning by BooleanSetting("Hide Attunement Warning", true, desc = "Hides the wrong attunement and Hellion Shield chat messages.")
    private val topDagger by SelectorSetting("Top Line Dagger", Dagger.TWILIGHT, desc = "Which dagger's attunement is shown first.")

    private const val EXAMPLE_TOP = "§7[§eAuric§7] §bCrystal"
    private const val EXAMPLE_BOTTOM = "§8Ashen §fSpirit"
    private val NONE = 0 to 0

    private val topHud by HUD("Dagger Attunement Top", "Active attunements of both daggers.", false, 400, 260, 1f) { example ->
        if (example) {
            text(mc.font, EXAMPLE_TOP, 0, 0, -1)
            return@HUD mc.font.width(EXAMPLE_TOP) to 9
        }
        if (topLine.isEmpty()) return@HUD NONE
        text(mc.font, topLine, 0, 0, -1)
        topSize
    }

    private val bottomHud by HUD("Dagger Attunement Bottom", "Next attunements of both daggers.", false, 400, 272, 1f) { example ->
        if (example) {
            text(mc.font, EXAMPLE_BOTTOM, 0, 0, -1)
            return@HUD mc.font.width(EXAMPLE_BOTTOM) to 9
        }
        if (bottomLine.isEmpty()) return@HUD NONE
        text(mc.font, bottomLine, 0, 0, -1)
        bottomSize
    }

    private enum class Attunement(val label: String, val colour: String) {
        AURIC("Auric", "§e"),
        ASHEN("Ashen", "§8"),
        SPIRIT("Spirit", "§f"),
        CRYSTAL("Crystal", "§b");

        val plain = colour + label
        val bracketed = "§7[$colour$label§7]"
    }

    private enum class Dagger(private val shown: String, val ids: Set<String>, val first: Attunement, val second: Attunement) {
        TWILIGHT("Twilight Dagger", setOf("HEARTMAW_DAGGER", "BURSTMAW_DAGGER", "MAWDUST_DAGGER"), Attunement.SPIRIT, Attunement.CRYSTAL),
        FIREDUST("Firedust Dagger", setOf("HEARTFIRE_DAGGER", "BURSTFIRE_DAGGER", "FIREDUST_DAGGER"), Attunement.ASHEN, Attunement.AURIC);

        override fun toString() = shown
    }

    private val DAGGERS = Dagger.entries.toTypedArray()
    private val ATTUNEMENTS = Attunement.entries.toTypedArray()
    private val ATTUNEMENT_WORD = Regex("""\b(auric|ashen|spirit|crystal)\b""", RegexOption.IGNORE_CASE)

    // Per dagger, bit 0 = first attunement on, bit 1 = second; only a title or the item lore makes it known.
    private val attuned = IntArray(2)
    private val known = BooleanArray(2)

    private var topLine = ""
    private var bottomLine = ""
    private var topSize = NONE
    private var bottomSize = NONE
    private var linesFor = -1
    private var lastLoreCheck = 0L

    private fun daggerOf(stack: ItemStack?): Dagger? {
        if (stack == null || stack.isEmpty) return null
        val id = stack.itemId
        for (d in DAGGERS) if (id in d.ids) return d
        return null
    }

    private fun current(d: Dagger): Attunement = if (attuned[d.ordinal] == 2) d.second else d.first

    private fun sibling(d: Dagger, a: Attunement): Attunement = if (a == d.first) d.second else d.first

    private fun fromTitle(text: String): Boolean {
        val word = ATTUNEMENT_WORD.find(text)?.value ?: return false
        val heard = ATTUNEMENTS.firstOrNull { it.name.equals(word, ignoreCase = true) } ?: return false
        for (d in DAGGERS) {
            val bit = if (heard == d.first) 1 else if (heard == d.second) 2 else continue
            attuned[d.ordinal] = bit
            known[d.ordinal] = true
        }
        return true
    }

    private fun readLore() {
        val now = System.currentTimeMillis()
        if (now - lastLoreCheck < 1000) return
        lastLoreCheck = now
        for (d in DAGGERS) {
            val i = d.ordinal
            if (known[i] || attuned[i] != 0) continue
            when (attunedInInventory(d)) {
                d.first -> { attuned[i] = 1; known[i] = true }
                d.second -> { attuned[i] = 2; known[i] = true }
                else -> attuned[i] = 1
            }
        }
    }

    private fun attunedInInventory(d: Dagger): Attunement? {
        val items = mc.player?.inventory?.nonEquipmentItems ?: return null
        for (stack in items) {
            if (stack.isEmpty || stack.itemId !in d.ids) continue
            for (line in stack.loreString) {
                if (!line.contains("Attuned: ")) continue
                if (line.contains(d.first.label)) return d.first
                if (line.contains(d.second.label)) return d.second
            }
        }
        return null
    }

    private fun part(d: Dagger, active: Boolean, held: Dagger): String {
        val a = if (active) current(d) else sibling(d, current(d))
        return if (active && d == held) a.bracketed else a.plain
    }

    private fun refreshLines(held: Dagger) {
        val top = topDagger
        val key = held.ordinal or (top.ordinal shl 1) or (attuned[0] shl 2) or (attuned[1] shl 4)
        if (key == linesFor) return
        linesFor = key
        val other = if (top == Dagger.TWILIGHT) Dagger.FIREDUST else Dagger.TWILIGHT
        topLine = part(other, true, held) + " " + part(top, true, held)
        bottomLine = part(other, false, held) + " " + part(top, false, held)
        topSize = mc.font.width(topLine) to 9
        bottomSize = mc.font.width(bottomLine) to 9
    }

    init {
        on<TickEvent.End> {
            val held = if (LocationUtils.isInSkyblock) daggerOf(mc.player?.mainHandItem) else null
            if (held == null) {
                topLine = ""
                bottomLine = ""
                linesFor = -1
                return@on
            }
            readLore()
            refreshLines(held)
        }

        onSend<ServerboundUseItemPacket> {
            val d = daggerOf(mc.player?.mainHandItem) ?: return@onSend
            attuned[d.ordinal] = attuned[d.ordinal] xor 3
        }

        onReceive<ClientboundSetSubtitleTextPacket> {
            if (!LocationUtils.isInSkyblock || daggerOf(mc.player?.mainHandItem) == null) return@onReceive
            if (fromTitle(text().string)) it.cancel()
        }

        on<MessageEvent.Chat> {
            if (hideWarning && (isAttunementWarning(message) || message == "Your hit was reduced by Hellion Shield!")) cancel()
        }
    }

    private val attunementWarning = Regex("Strike using the .+ attunement on your dagger!")

    private fun isAttunementWarning(m: String): Boolean = m.startsWith("Strike using the ") && attunementWarning.matches(m)
}
