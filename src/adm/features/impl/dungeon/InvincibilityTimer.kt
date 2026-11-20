package com.adm.features.impl.dungeon

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.clickgui.settings.impl.DropdownSetting
import com.adm.clickgui.settings.impl.SelectorSetting
import com.adm.events.GuiEvent
import com.adm.events.LevelEvent
import com.adm.events.MessageEvent
import com.adm.events.TickEvent
import com.adm.events.core.on
import com.adm.features.Module
import com.adm.utils.Color
import com.adm.utils.Colors
import com.adm.utils.alert
import com.adm.utils.createSkullStack
import com.adm.utils.itemId
import com.adm.utils.lore
import com.adm.utils.render.ItemStateRenderer.Companion.drawItemStack
import com.adm.utils.render.textDim
import com.adm.utils.sendCommand
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.engineerclient.misc.MasksUsed
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack

object InvincibilityTimer : Module(
    name = "Invincibility Timer",
    description = "Provides visual information about your invincibility items."
) {
    private val invincibilityAlert by BooleanSetting("Invincibility Alert", true, desc = "Plays a sound when you get invincibility.")
    private val invincibilityAnnounce by BooleanSetting("Announce Invincibility", true, desc = "Announces when you get invincibility.")
    private val readyAlert by BooleanSetting("Ready Alert", true, desc = "Shows a title when an item's cooldown ends.")
    private val onlyInDungeons by BooleanSetting("Only In Dungeons", true, "Only proc invincibility timers while in a dungeon")

    private val maskCat by DropdownSetting("Displayed Items", desc = "Which invincibility items are shown in the HUD.")
    private val showSpirit by BooleanSetting("Show Spirit Mask", true, desc = "Shows the Spirit Mask in the HUD.").withDependency { maskCat }
    private val showBonzo by BooleanSetting("Show Bonzo Mask", true, desc = "Shows the Bonzo Mask in the HUD.").withDependency { maskCat }
    private val showPhoenix by BooleanSetting("Show Phoenix Pet", true, desc = "Shows the Phoenix Pet in the HUD.").withDependency { maskCat }

    private val hud by HUD(name, "Shows the invincibility time in the HUD.") { example ->
        if (!example && ((onlyInDungeons && !DungeonUtils.inDungeons) || (showOnlyInBoss && !DungeonUtils.inBoss))) return@HUD 0 to 0
        val worn = wornMask()
        var row = 0
        var width = 0
        for (item in items) {
            if (!example && !listed(item)) continue
            drawItemStack(item.icon, 0, row * 14 - 1)
            val y = row * 14 + 3
            if (worn != null && worn === item) fill(14, y, 15, y + 9, equippedMaskColor.rgba)
            val w = textDim(item.label(), 16, y, item.labelColor()).first
            if (w > width) width = w
            row++
        }
        if (row == 0) 0 to 0 else width + 20 to row * 14
    }
    init { hud.leftAlign = true }

    private val showOnlyInBoss by BooleanSetting("Show In Boss", false, desc = "Only shows invincibility timers during dungeon boss fights.")
    private val showWhen by SelectorSetting("Show", ShowWhen.ALWAYS, "Controls when invincibility items are shown.")
    private val equippedMaskColor by ColorSetting("Equipped Mask", Colors.MINECRAFT_DARK_PURPLE, desc = "Color of the equipped mask in the HUD. (Bonzo/Spirit)")

    private val itemCat by DropdownSetting("Cooldown On Item", desc = "Options to show cooldown on the item slot itself.")
    private val showOnItem by BooleanSetting("Show on Item", false, "Renders the cooldown on the spirit mask and bonzo mask items").withDependency { itemCat }
    private val durability by BooleanSetting("Display As Durability", false, "True: durability, False: colored vertical slide").withDependency { itemCat }
    private val cdColor by ColorSetting("Cooldown Color", Colors.gray38, false, "Color of the cooldown").withDependency { itemCat }

    private class Saver(val label: String, val savedTicks: Int, val cooldownSeconds: Int, texture: String, vararg val maskIds: String) {
        val icon: ItemStack by lazy { createSkullStack(texture) }
        @Volatile var savedLeft = 0
        @Volatile var cooldownLeft = 0

        private var shownKey = Long.MIN_VALUE
        private var shown = ""

        fun label(): String {
            val key = (savedLeft.toLong() shl 32) or (cooldownLeft.toLong() and 0xFFFFFFFFL)
            if (key != shownKey) {
                shownKey = key
                shown = when {
                    savedLeft > 0 -> seconds(savedLeft)
                    cooldownLeft > 0 -> seconds(cooldownLeft)
                    else -> "✔"
                }
            }
            return shown
        }

        fun labelColor(): Color = when {
            savedLeft > 0 -> Colors.MINECRAFT_GOLD
            cooldownLeft > 0 -> Colors.MINECRAFT_RED
            else -> Colors.MINECRAFT_GREEN
        }
    }

    private val spirit = Saver("Spirit", 60, 30, "9bbe721d7ad8ab965f08cbec0b834f779b5197f79da4aea3d13d253ece9dec2", "SPIRIT_MASK", "STARRED_SPIRIT_MASK")
    private val bonzo = Saver("Bonzo", 60, 180, "12716ecbf5b8da00b05f316ec6af61e8bd02805b21eb8e440151468dc656549c", "BONZO_MASK", "STARRED_BONZO_MASK")
    // The pet's lore says 4 s, but a proc only covers the player for 3 s, like the masks.
    private val phoenix = Saver("Phoenix", 60, 60, "66b1b59bc890c9c97527787dde20600c8b86f6b9912d51a6bfcdb0e4c2aa3c97")
    private val items = arrayOf(spirit, bonzo, phoenix)

    private const val SPIRIT_LINE = "Second Wind Activated! Your Spirit Mask saved your life!"
    private const val PHOENIX_LINE = "Your Phoenix Pet saved you from certain death!"
    private const val BONZO_TAIL = "Bonzo's Mask saved your life!"
    private const val COOLDOWN_PREFIX = "Cooldown: "

    private fun seconds(ticks: Int) = "${ticks / 20}.${((ticks % 20) * 5).toString().padStart(2, '0')}s"

    private fun listed(item: Saver): Boolean {
        val index = items.indexOf(item)
        val on = MasksUsed.shows(index, when (index) { 0 -> showSpirit; 1 -> showBonzo; else -> showPhoenix })
        return on && when (showWhen) {
            ShowWhen.ALWAYS -> true
            ShowWhen.ANY -> item.savedLeft > 0 || item.cooldownLeft > 0
            ShowWhen.WHEN_ACTIVE -> item.savedLeft > 0
            ShowWhen.ON_COOLDOWN -> item.cooldownLeft > 0
        }
    }

    private fun wornMask(): Saver? {
        val id = mc.player?.getItemBySlot(EquipmentSlot.HEAD)?.itemId ?: return null
        return if (id in spirit.maskIds) spirit else if (id in bonzo.maskIds) bonzo else null
    }

    private fun savedBy(message: String): Saver? = when {
        message == SPIRIT_LINE -> spirit
        message == PHOENIX_LINE -> phoenix
        // "Your Bonzo's Mask", or with a one character star before it: "Your ⚚ Bonzo's Mask".
        message.startsWith("Your ") && message.endsWith(BONZO_TAIL) &&
            (message.length == 5 + BONZO_TAIL.length || (message.length == 7 + BONZO_TAIL.length && message[6] == ' ' && message[5] != '\n' && message[5] != '\r')) -> bonzo
        else -> null
    }

    private fun helmetCooldownSeconds(): Int? {
        val lines = mc.player?.getItemBySlot(EquipmentSlot.HEAD)?.lore ?: return null
        for (i in lines.indices.reversed()) {
            val line = lines[i].string
            if (line.length <= COOLDOWN_PREFIX.length + 1 || !line.startsWith(COOLDOWN_PREFIX) || !line.endsWith('s')) continue
            val digits = line.substring(COOLDOWN_PREFIX.length, line.length - 1)
            if (digits.all { it in '0'..'9' }) digits.toIntOrNull()?.let { return it }
        }
        return null
    }

    init {
        on<TickEvent.Server> {
            for (item in items) {
                if (item.cooldownLeft > 0 && --item.cooldownLeft == 0 && readyAlert) mc.execute { alert("§a${item.label} Ready!") }
                if (item.savedLeft > 0) item.savedLeft--
            }
        }

        on<MessageEvent.Chat> {
            if (onlyInDungeons && !DungeonUtils.inDungeons) return@on
            val item = savedBy(message) ?: return@on
            MasksUsed.markUsed(items.indexOf(item))
            val seconds = if (item === bonzo) helmetCooldownSeconds() else null
            item.savedLeft = item.savedTicks
            item.cooldownLeft = (seconds ?: item.cooldownSeconds) * 20
            if (invincibilityAnnounce) sendCommand("pc ${item.label} Procced! (${items.count { it.cooldownLeft > 0 }}/${items.size})")
            if (invincibilityAlert) alert(item.label)
        }

        on<LevelEvent.Load> { resetTimers(); MasksUsed.clearUsed() }

        on<GuiEvent.RenderSlot> {
            if (!showOnItem || (spirit.cooldownLeft <= 0 && bonzo.cooldownLeft <= 0)) return@on
            val stack = slot.item
            val id = stack.itemId
            val mask = if (id in spirit.maskIds) spirit else if (id in bonzo.maskIds) bonzo else return@on
            val left = 1 - mask.cooldownLeft.toDouble() / (mask.cooldownSeconds * 20)
            if (left >= 1) return@on
            guiGraphics.fakeItem(stack, slot.x, slot.y)
            if (durability) {
                guiGraphics.fill(slot.x + 2, slot.y + 13, slot.x + 14, slot.y + 15, Colors.BLACK.rgba)
                guiGraphics.fill(slot.x + 2, slot.y + 13, slot.x + 14 - (left * 12).toInt(), slot.y + 14, cdColor.rgba)
            } else guiGraphics.fill(slot.x, slot.y + (left * 16).toInt(), slot.x + 16, slot.y + 16, cdColor.rgba)
            cancel()
        }
    }

    fun resetTimers() {
        for (item in items) {
            item.savedLeft = 0
            item.cooldownLeft = 0
        }
    }

    private enum class ShowWhen { ALWAYS, ANY, WHEN_ACTIVE, ON_COOLDOWN }
}
