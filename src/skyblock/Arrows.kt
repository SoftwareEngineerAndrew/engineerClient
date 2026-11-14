package com.engineerclient.skyblock

import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.DropdownSetting
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.SetSlotEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.createSoundSettings
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.lore
import com.odtheking.odin.utils.playSoundSettings
import com.odtheking.odin.utils.render.textDim
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents
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
        val text = when {
            example -> buildText(Component.literal("Flint Arrow"), "100")
            arrows != null -> arrows?.let { buildText(it.first, it.second) }
            else -> null
        } ?: return@HUD 0 to 0
        return@HUD textDim(text, 0, 0)
    }.withDependency { quiverDropdown && quiverDisplay }
    private val swapHud by HUD("Arrow Swap Title", "Shows your new arrow type when you swap arrows.", true, 400, 110, 2f) { example ->
        val text = if (example) Component.literal("§aToxic Arrow Poison") else if (ticks > 0) arrow else return@HUD 0 to 0
        text(mc.font, text, 0, 0, -1)
        mc.font.width(text) to 9
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
    private var ticks = 0
    private var arrows: Pair<Component, String>? = null

    private fun buildText(name: Component, count: String) =
        (if (showName) name.copy().append("§8x") else Component.literal(""))
            .append(Component.literal(count).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(countColor.rgba and 0xFFFFFF))))
            .visualOrderText

    init {
        on<MessageEvent.Chat> {
            if (!swapped.matches(message)) return@on
            arrow = component.siblings.getOrNull(1) ?: Component.literal(swapped.find(message)!!.groupValues[1])
            ticks = 20
        }

        on<TickEvent.End> {
            if (ticks > 0) ticks--
        }

        on<SetSlotEvent> {
            if (slotIndex != (if (DungeonUtils.inClear) 9 else 44) || (itemStack.item != Items.FEATHER && itemStack.item != Items.ARROW)) return@on

            arrows = itemStack.hoverName to (itemStack.lore.find { arrowRegex.matches(it.string) }?.let {
                arrowRegex.find(it.string)?.groupValues?.get(1)
            } ?: return@on)
        }
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
