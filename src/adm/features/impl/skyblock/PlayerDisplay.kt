package com.adm.features.impl.skyblock

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.clickgui.settings.impl.DropdownSetting
import com.adm.events.MessageEvent
import com.adm.events.core.on
import com.adm.features.Module
import com.adm.utils.Color
import com.adm.utils.Colors
import com.adm.utils.render.text
import com.adm.utils.skyblock.ActionBarListener
import com.adm.utils.skyblock.LocationUtils
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import kotlin.math.abs

enum class OverlayType {
    ARMOR,
    HEARTS,
    FOOD,
    XP,
    HOTBAR;
}

object PlayerDisplay : Module(
    name = "Player Display",
    description = "Allows to customize the player stat displays (health, defense and more)."
) {
    private val EXTRA_STATS = ActionBarListener.Stat.entries.filter { it >= ActionBarListener.Stat.SECRETS }

    private val hideElements by DropdownSetting("Hide Elements", desc = "Shows toggles for hiding vanilla HUD elements.")
    private val hideArmor by BooleanSetting("Hide Armor", false, desc = "Hides the armor bar.").withDependency { hideElements }
    private val hideFood by BooleanSetting("Hide Food", false, desc = "Hides the food bar.").withDependency { hideElements }
    private val hideHearts by BooleanSetting("Hide Hearts", false, desc = "Hides the hearts.").withDependency { hideElements }
    private val hideXP by BooleanSetting("Hide XP Level", false, desc = "Hides the XP level.").withDependency { hideElements }
    private val hideHotbar by BooleanSetting("Hide Hotbar", false, desc = "Hides the hotbar.").withDependency { hideElements }
    private val hideActionBar by DropdownSetting("Hide Action Bar Elements", desc = "Shows toggles for hiding action bar elements.")
    private val hideHealth by BooleanSetting("Hide Health", true, desc = "Hides the health bar.").withDependency { hideActionBar }
    private val hideMana by BooleanSetting("Hide Mana", true, desc = "Hides the mana bar.").withDependency { hideActionBar }
    private val hideOverflow by BooleanSetting("Hide Overflow Mana", true, desc = "Hides the overflow mana bar.").withDependency { hideActionBar }
    private val hideDefense by BooleanSetting("Hide Defense", true, desc = "Hides the defense bar.").withDependency { hideActionBar }
    private val hideVitality by BooleanSetting("Hide Vitality", true, desc = "Hides the vitality bar.").withDependency { hideActionBar }
    private val extraHides = EXTRA_STATS.map {
        registerSetting(BooleanSetting("Hide ${it.title}", false, desc = "Hides the ${it.title.lowercase()} from the action bar.").withDependency { hideActionBar })
    }
    private val overflow by DropdownSetting("Overflow Mana", desc = "Shows settings for the overflow mana bar.")
    private val separateOverflow by BooleanSetting("Separate Overflow Mana", true, desc = "Separates the overflow mana from the mana bar.").withDependency { overflow }
    private val hideZeroSF by BooleanSetting("Hide 0 Overflow", true, desc = "Hides the overflow mana when it's 0.").withDependency { overflow && separateOverflow }

    private val showIcons by BooleanSetting("Show Icons", true, desc = "Shows icons indicating what the number means.")

    private val healthHud by HUD("Health HUD", "Displays the player's health.") { example ->
        val bar = ActionBarListener
        when {
            example -> health.ratio(this, 3000, 4000, HEALTH, healthColor)
            !LocationUtils.isInSkyblock || bar.currentHealth == 0 || bar.maxHealth == 0 -> NONE
            else -> health.ratio(this, bar.currentHealth, bar.maxHealth, HEALTH, healthColor)
        }
    }
    private val healthColor by ColorSetting("Health Color", Colors.MINECRAFT_RED, true, "The color of the health text.")

    private val manaHud by HUD("Mana HUD", "Displays the player's mana.") { example ->
        val bar = ActionBarListener
        when {
            example -> mana.mana(this, 2000, 20000, !separateOverflow)
            !LocationUtils.isInSkyblock || bar.maxMana == 0 -> NONE
            else -> mana.mana(this, bar.currentMana, bar.maxMana, !separateOverflow && overflowManaHud.enabled)
        }
    }
    private val manaColor by ColorSetting("Mana Color", Colors.MINECRAFT_AQUA, true, "The color of the mana text.")

    private val overflowManaHud by HUD("Overflow Mana HUD", "Displays the player's overflow mana.") { example ->
        when {
            example -> overflowMana.amount(this, 333, OVERFLOW, hideZeroSF, overflowManaColor)
            !LocationUtils.isInSkyblock || !separateOverflow -> NONE
            else -> overflowMana.amount(this, ActionBarListener.overflowMana, OVERFLOW, hideZeroSF, overflowManaColor)
        }
    }
    private val overflowManaColor by ColorSetting("Overflow Mana Color", Colors.MINECRAFT_DARK_AQUA, true, desc = "The color of the overflow mana text.")

    private val defenseHud by HUD("Defense HUD", "Displays the player's defense.") { example ->
        val v = if (example) 1000 else if (LocationUtils.isInSkyblock) ActionBarListener.currentDefense else 0
        if (v == 0) NONE else defense.amount(this, v, DEFENSE, true, defenseColor)
    }
    private val defenseColor by ColorSetting("Defense Color", Colors.MINECRAFT_GREEN, true, desc = "The color of the defense text.")

    private val vitalityHud by HUD("Vitality HUD", "Displays the player's vitality.") { example ->
        val bar = ActionBarListener
        when {
            example -> vitality.ratio(this, 100, 100, VITALITY, vitalityColor)
            !LocationUtils.isInSkyblock || !bar.isVitalityShown || bar.maxVitality == 0 -> NONE
            else -> vitality.ratio(this, bar.currentVitality, bar.maxVitality, VITALITY, vitalityColor)
        }
    }
    private val vitalityColor by ColorSetting("Vitality Color", Colors.MINECRAFT_DARK_RED, true, "The color of the vitality text.")

    private val ehpHud by HUD("EHP HUD", "Displays the player's effective health (EHP).") { example ->
        val v = if (example) 1000000 else if (LocationUtils.isInSkyblock) ActionBarListener.effectiveHP else 0
        if (v == 0) NONE else ehp.amount(this, v, "", true, ehpColor)
    }
    private val ehpColor by ColorSetting("EHP color", Colors.MINECRAFT_DARK_GREEN, true, "The color of the effective health text.")

    private val speedHud by HUD("Speed HUD", "Displays the player's speed buff.") { example ->
        val v = if (example) 100 else if (LocationUtils.isInSkyblock) ActionBarListener.currentSpeed else 0
        if (v == 0) NONE else speed.amount(this, v, SPEED, true, speedColor)
    }
    private val speedColor by ColorSetting("Speed color", Colors.WHITE, true, "The color of the speed text.")

    private val extraHuds = EXTRA_STATS.mapIndexed { i, stat ->
        val readout = BarText()
        registerSetting(HUD("${stat.title} HUD", "Displays the ${stat.title.lowercase()} from the action bar.", true, 10, 10 + i * 12) { example ->
            val text = if (example) example(stat) else if (LocationUtils.isInSkyblock && ActionBarListener.shown(stat)) ActionBarListener.text(stat) else null
            if (text == null) NONE else readout.draw(this, text)
        })
    }

    private fun example(stat: ActionBarListener.Stat): String = when (stat) {
        ActionBarListener.Stat.SECRETS -> "§72/5 Secrets"
        ActionBarListener.Stat.TRUE_DEFENSE -> "§f69"
        ActionBarListener.Stat.ARMOR_STACKS -> "§610⁑"
        ActionBarListener.Stat.TERMINATOR -> "§a§lT3!"
        ActionBarListener.Stat.SKILL_XP -> "§3+163.7 Combat (1,560,940/2,000,000)"
        ActionBarListener.Stat.MANA_USE -> "§b-42 Mana (§6Rapid-fire§b)"
        ActionBarListener.Stat.ESSENCE -> "§d+1 Undead Essence"
        ActionBarListener.Stat.ABILITY -> "§lCASTING IN 3s"
        ActionBarListener.Stat.DRILL_FUEL -> "§22,609/3k Drill Fuel"
        ActionBarListener.Stat.RIFT_TIME -> "§a10m59sф Left"
        ActionBarListener.Stat.WATER -> "§b2,400§7/3k Water"
        ActionBarListener.Stat.GECKO_COMBO -> "§a[⬛⬛⬛⬛⬛ §e§lx7 §a⬛⬛⬛⬜⬜]"
        ActionBarListener.Stat.AURORA_RUNE -> "§5§lVirtuoso"
        ActionBarListener.Stat.INVULNERABLE -> "§9§lINVULNERABLE"
        ActionBarListener.Stat.COOKIE_BITS -> "§b+250 Bits from Cookie Buff!"
        ActionBarListener.Stat.ZONE -> "§7 §bVillage"
        else -> stat.title
    }

    private val NONE = 0 to 0

    private const val HEALTH = "❤"
    private const val MANA = "✎"
    private const val OVERFLOW = "ʬ"
    private const val DEFENSE = "❈"
    private const val VITALITY = "♨"
    private const val SPEED = "✦"

    // Each HUD keeps its last line and size; they are rebuilt only when a number or a setting it shows changes.
    private class Readout {
        private var a = 0
        private var b = 0
        private var c = 0
        private var flags = -1
        private var text = ""
        private var size = 0 to 0

        fun ratio(g: GuiGraphicsExtractor, current: Int, max: Int, icon: String, color: Color): Pair<Int, Int> {
            val f = if (showIcons) 1 else 0
            if (flags != f || a != current || b != max) remember(current, max, 0, f, StringBuilder().appendRatio(current, max, icon).toString())
            return draw(g, color)
        }

        fun amount(g: GuiGraphicsExtractor, value: Int, icon: String, hideZero: Boolean, color: Color): Pair<Int, Int> {
            val f = (if (showIcons) 1 else 0) or (if (hideZero) 2 else 0)
            if (flags != f || a != value) remember(value, 0, 0, f, StringBuilder().appendAmount(value, icon, hideZero).toString())
            return draw(g, color)
        }

        fun mana(g: GuiGraphicsExtractor, current: Int, max: Int, withOverflow: Boolean): Pair<Int, Int> {
            val overflow = ActionBarListener.overflowMana
            val f = (if (showIcons) 1 else 0) or (if (hideZeroSF) 2 else 0) or (if (withOverflow) 4 else 0)
            if (flags != f || a != current || b != max || c != overflow) {
                val sb = StringBuilder().appendRatio(current, max, MANA)
                if (withOverflow) sb.append(' ').appendAmount(overflow, OVERFLOW, hideZeroSF)
                remember(current, max, overflow, f, sb.toString())
            }
            return draw(g, manaColor)
        }

        private fun remember(a: Int, b: Int, c: Int, flags: Int, text: String) {
            this.a = a
            this.b = b
            this.c = c
            this.flags = flags
            if (text != this.text || size.second == 0) {
                this.text = text
                size = mc.font.width(text) to mc.font.lineHeight
            }
        }

        private fun draw(g: GuiGraphicsExtractor, color: Color): Pair<Int, Int> {
            g.text(text, 0, 0, color, true)
            return size
        }
    }

    // The bar's own coloured text; measured again only when the bar hands over a new string.
    private class BarText {
        private var text: String? = null
        private var size = 0 to 0

        fun draw(g: GuiGraphicsExtractor, text: String): Pair<Int, Int> {
            if (text !== this.text) {
                this.text = text
                size = mc.font.width(text) to mc.font.lineHeight
            }
            g.text(text, 0, 0, Colors.WHITE, true)
            return size
        }
    }

    private val health = Readout()
    private val mana = Readout()
    private val overflowMana = Readout()
    private val defense = Readout()
    private val vitality = Readout()
    private val ehp = Readout()
    private val speed = Readout()

    private fun StringBuilder.appendRatio(current: Int, max: Int, icon: String): StringBuilder {
        appendGrouped(current).append('/').appendGrouped(max)
        if (showIcons) append(icon)
        return this
    }

    private fun StringBuilder.appendAmount(value: Int, icon: String, hideZero: Boolean): StringBuilder {
        if (hideZero && value == 0) return this
        appendGrouped(value)
        if (showIcons) append(icon)
        return this
    }

    private fun StringBuilder.appendGrouped(n: Int): StringBuilder {
        if (n < 0) append('-')
        val digits = abs(n.toLong()).toString()
        val lead = digits.length % 3
        for (i in digits.indices) {
            if (i > 0 && (i - lead) % 3 == 0) append(',')
            append(digits[i])
        }
        return this
    }

    private val HIDDEN_PARTS = arrayOf(
        Regex("[\\d|,]+/[\\d|,]+\\uE010"),
        Regex("[\\d|,]+/[\\d|,]+\\uE003( Mana)?"),
        Regex("§?[\\d|,]+\\uE017"),
        Regex("[\\d|,]+(§.)?\\uE008( Defense)?"),
        Regex("[\\d.,]+/[\\d.,]+(§.)?\\uE028"),
    )
    private val MARKERS = charArrayOf('', '', '', '', '')

    private fun hides(part: Int): Boolean = when (part) {
        0 -> hideHealth
        1 -> hideMana
        2 -> hideOverflow
        3 -> hideDefense
        else -> hideVitality
    }

    private var lastIn: String? = null
    private var lastMask = -1
    private var lastOut: Component? = null

    init {
        on<MessageEvent.ModifyOverlay> {
            val original = component.string
            var extra = 0
            for (i in EXTRA_STATS.indices) if (extraHides[i].value) extra = extra or (1 shl EXTRA_STATS[i].ordinal)
            var mask = extra
            for (i in MARKERS.indices) if (hides(i)) mask = mask or (1 shl (31 - i))
            if (original != lastIn || mask != lastMask) {
                var text = ActionBarListener.without(original, extra)
                for (i in MARKERS.indices) {
                    if (hides(i) && text.indexOf(MARKERS[i]) >= 0) text = HIDDEN_PARTS[i].replace(text, "")
                }
                lastIn = original
                lastMask = mask
                lastOut = if (text != original) Component.literal(text.trim()) else null
            }
            lastOut?.let { component = it }
        }
    }

    @JvmStatic
    fun shouldCancelOverlay(type: OverlayType): Boolean =
        enabled && LocationUtils.isInSkyblock && when (type) {
            OverlayType.ARMOR -> hideArmor
            OverlayType.HEARTS -> hideHearts
            OverlayType.FOOD -> hideFood
            OverlayType.XP -> hideXP
            OverlayType.HOTBAR -> hideHotbar
        }
}