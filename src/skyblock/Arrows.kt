package com.engineerclient.skyblock

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.clickgui.settings.impl.DropdownSetting
import com.adm.clickgui.settings.impl.NumberSetting
import com.adm.events.LevelEvent
import com.adm.events.MessageEvent
import com.adm.events.SetSlotEvent
import com.adm.events.TickEvent
import com.adm.events.core.on
import com.adm.events.core.onReceive
import com.adm.utils.alert
import com.adm.features.Category
import com.adm.features.Module
import com.adm.utils.Colors
import com.adm.utils.createSoundSettings
import com.adm.utils.itemId
import com.adm.utils.lore
import com.adm.utils.playSoundSettings
import com.adm.utils.render.text
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.FormattedCharSequence
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

object Arrows : Module(
    name = "Arrows",
    category = Category.SKYBLOCK,
    description = "Arrow tweaks: shortbows, quiver, swap title, hit sound and hiding arrows.",
    key = null,
    toggled = true,
) {
    private val noPullback by BooleanSetting("No Shortbow Pullback", false, desc = "No bow pullback on shortbows.")

    private val quiverDropdown by DropdownSetting("Quiver", desc = "Quiver Display and Arrow Swap Title.")
    private val quiverDisplay by BooleanSetting("Quiver Display", false, desc = "Displays the number of arrows in your quiver.").withDependency { quiverDropdown }
    private val showName by BooleanSetting("Show Name", true, desc = "Shows the arrow type's name before the count.").withDependency { quiverDropdown && quiverDisplay }
    private val countColor by ColorSetting("Count Color", Colors.MINECRAFT_GREEN, true, desc = "The color of the arrow count.").withDependency { quiverDropdown && quiverDisplay }
    private val quiverHud by HUD("Quiver HUD", "Displays the number of arrows in your quiver.", false) { example ->
        if (!quiverDisplay) return@HUD 0 to 0
        val name = if (example) exampleName else quiverName ?: return@HUD 0 to 0
        val text = quiverLine(name, if (example) "100" else quiverCount)
        text(text, 0, 0)
        quiverSize
    }.withDependency { quiverDropdown && quiverDisplay }
    private val lowArrows by NumberSetting("Low Arrow Alert", 0, 0..2880, 32, desc = "A title when the quiver drops below this; 0 is off.").withDependency { quiverDropdown }
    private val swapHud by HUD("Arrow Swap Title", "Shows your new arrow type when you swap arrows.", true, 400, 110, 2f) { example ->
        if (example) {
            text(mc.font, exampleSwap, 0, 0, -1)
            return@HUD mc.font.width(exampleSwap) to 9
        }
        if (ticks <= 0) return@HUD 0 to 0
        text(mc.font, arrow, 0, 0, -1)
        arrowSize
    }.also { it.value.enabled = true }.withDependency { quiverDropdown }

    private val hitSoundDropdown by DropdownSetting("Hit Sound", desc = "Arrow Hit Sound.")
    private val hitSound by BooleanSetting("Arrow Hit Sound", false, desc = "Replaces the arrow hit sound with a sound of your choice.").withDependency { hitSoundDropdown }
    private val sound = createSoundSettings("Hit Sound", "block.note_block.harp") { hitSoundDropdown && hitSound }

    private val hideDropdown by DropdownSetting("Hide Arrows", desc = "Arrows that are not drawn.")
    private val hideGroundedArrows by BooleanSetting("Hide Grounded Arrows", false, desc = "Arrows stuck in blocks are not drawn.").withDependency { hideDropdown }
    private val hideStuckArrows by BooleanSetting("Hide Stuck Arrows", false, desc = "No arrows stuck in players.").withDependency { hideDropdown }
    private val hideOwnArrows by BooleanSetting("Hide Own Arrows Nearby", false, desc = "Arrows you shoot are not drawn while they fly within 5 blocks of you.").withDependency { hideDropdown }

    private val known = HashMap<String, Boolean>()
    private val swapped = Regex("^You set your selected arrow type to (.+)!$")
    private val arrowRegex = Regex("^Arrows Remaining: ([\\d,]+)$")
    private var arrow: Component = Component.empty()
    private var arrowSize = 0 to 9
    private var ticks = 0
    private var quiverName: Component? = null
    private var quiverCount = ""
    private var lowAlerted = false

    private val exampleName = Component.literal("Flint Arrow")
    private val exampleSwap = Component.literal("§aToxic Arrow Poison")

    private var lineName: Component? = null
    private var lineCount = ""
    private var lineShowName = false
    private var lineColor = 0
    private var line: FormattedCharSequence = FormattedCharSequence.EMPTY
    private var quiverSize = 0 to 9

    private fun quiverLine(name: Component, count: String): FormattedCharSequence {
        val named = showName
        val color = countColor.rgba and 0xFFFFFF
        if (name === lineName && count == lineCount && named == lineShowName && color == lineColor) return line
        lineName = name
        lineCount = count
        lineShowName = named
        lineColor = color
        line = (if (named) name.copy().append("§8x") else Component.literal(""))
            .append(Component.literal(count).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(color))))
            .visualOrderText
        quiverSize = mc.font.width(line) to mc.font.lineHeight
        return line
    }

    init {
        on<MessageEvent.Chat> {
            if (!swapped.matches(message)) return@on
            arrow = component.siblings.getOrNull(1) ?: Component.literal(swapped.find(message)!!.groupValues[1])
            arrowSize = mc.font.width(arrow) to 9
            ticks = 20
        }

        on<TickEvent.End> {
            if (ticks > 0) ticks--
        }

        on<SetSlotEvent> {
            if (slotIndex == quiverSlot()) readQuiver(itemStack)
        }

        // A warp or join sends the whole inventory at once, not slot by slot.
        onReceive<ClientboundContainerSetContentPacket> {
            if (containerId() != 0 || (!quiverDisplay && lowArrows <= 0)) return@onReceive
            val stack = items().getOrNull(quiverSlot()) ?: return@onReceive
            mc.execute { readQuiver(stack) }
        }

        on<LevelEvent.Load> {
            quiverName = null
            quiverCount = ""
        }
    }

    private fun quiverSlot() = if (DungeonUtils.inClear) 9 else 44

    private fun readQuiver(stack: ItemStack) {
        if (stack.item != Items.FEATHER && stack.item != Items.ARROW) return
        for (lore in stack.lore) {
            val count = arrowRegex.matchEntire(lore.string)?.groupValues?.get(1) ?: continue
            quiverName = stack.hoverName
            if (count != quiverCount) {
                quiverCount = count
                checkLow(count.replace(",", "").toIntOrNull() ?: return)
            }
            return
        }
    }

    private fun checkLow(count: Int) {
        val threshold = lowArrows
        if (threshold <= 0 || count >= threshold) { lowAlerted = false; return }
        if (lowAlerted) return
        lowAlerted = true
        alert("§cLow Arrows! §f$count left")
    }

    fun clears(item: ItemStack?): Boolean {
        if (!enabled || !noPullback || item == null || item.isEmpty || !item.`is`(Items.BOW)) return false
        val id = item.itemId
        known[id]?.let { return it }
        return item.lore.any { "Shortbow: Instantly shoots!" in it.string }.also { known[id] = it }
    }

    @JvmStatic
    fun onSoundPlay(instance: SoundInstance): Boolean {
        if (!enabled || !hitSound || instance.identifier != SoundEvents.ARROW_HIT_PLAYER.location) return false
        val settings = sound()
        if (Identifier.tryParse(settings.first) == instance.identifier) return false
        playSoundSettings(settings)
        return true
    }

    fun hidesGroundedArrows() = enabled && hideGroundedArrows

    fun hidesStuckArrows() = enabled && hideStuckArrows

    /** [arrow] is one the local player shot, still in flight ([inGround] false), within 5 blocks of them. */
    fun hidesOwnArrow(arrow: AbstractArrow, inGround: Boolean): Boolean {
        if (!enabled || !hideOwnArrows || inGround) return false
        val me = mc.player ?: return false
        return arrow.owner === me && arrow.distanceToSqr(me) < 25.0
    }
}
