package com.engineerclient.visual

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.vertex.VertexConsumer
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.KeybindSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.ScreenEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.clickSlot
import com.odtheking.odin.utils.loreString
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.joml.Matrix3x2f
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

object PetMenu : Module(
    name = "Pet Menu",
    category = Category.SKYBLOCK,
    description = "Replaces the Pets menu with a pet wheel.",
    key = null,
) {
    private const val PETS_PER_WHEEL = 9

    private val menuScale by NumberSetting("Wheel Scale", 100, 70..135, 5, desc = "Size of the wheel.", unit = "%")
    private val showKeyLabels by BooleanSetting("Show Key Labels", true, desc = "Shows each slot's key on the wheel.")
    private val favouritePetsOnly by BooleanSetting("Favourite Pets Only", false, desc = "Only shows pets favourited in the Pets menu.")
    private val segmentColor by ColorSetting("Segment Color", Color(15, 15, 15, 200 / 255f), true, desc = "Colour of the wheel.")
    private val hoverColor by ColorSetting("Hover Color", Color(255, 255, 255, 30 / 255f), true, desc = "Colour over the hovered pet.")
    private val activeColor by ColorSetting("Active Color", Color(85, 170, 255, 120 / 255f), true, desc = "Colour behind your active pet.")
    private val separatorColor by ColorSetting("Separator Color", Color(255, 255, 255, 40 / 255f), true, desc = "Colour of the lines between pets.")
    private val useHotbarBinds by BooleanSetting("Use Hotbar Binds", false, desc = "Uses your hotbar keys instead of the keys below.")
    private val keybinds = (1..PETS_PER_WHEEL).map { slot ->
        +KeybindSetting("Pet Slot $slot", InputConstants.KEY_1 + slot - 1, desc = "Summons the pet in slot $slot.").withDependency { !useHotbarBinds }
    }

    private val petMenuRegex = Regex("^(?:\\(\\d+/\\d+\\) )?Pets(?: \\(\\d+/\\d+\\))?$")
    private val petSlots = (10..43).filter { it % 9 in 1..7 }

    private var petScreen: AbstractContainerScreen<*>? = null
    private var lastTitle = ""
    private var wheelPage = 0
    private var lastClickAt = 0L
    private var tempDisabled = false
    private var clicked = false

    init {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is AbstractContainerScreen<*>) return@register
            val previousTitle = lastTitle
            ScreenEvents.remove(screen).register { lastTitle = it.title.string }
            if (!screen.title.string.matches(petMenuRegex) || previousTitle.startsWith("Loadout") || previousTitle.endsWith("Loadouts")) return@register
            if (petScreen?.menu?.containerId != screen.menu.containerId) wheelPage = 0
            petScreen = screen
            clicked = false

            ScreenMouseEvents.allowMouseClick(screen).register { s, click -> !(isActive(s) && onClick(s as AbstractContainerScreen<*>, click.x(), click.y(), click.button())) }
            ScreenMouseEvents.allowMouseScroll(screen).register { s, _, _, _, vertical ->
                if (!isActive(s)) return@register true
                val pages = pageCount(pets(s as AbstractContainerScreen<*>).size)
                if (vertical != 0.0 && pages > 1) wheelPage = Math.floorMod(wheelPage + if (vertical < 0) 1 else -1, pages)
                false
            }
            ScreenKeyboardEvents.allowKeyPress(screen).register { s, key ->
                if (!isActive(s)) return@register true
                if (clicked) {
                    clicked = false
                    mc.player?.closeContainer()
                    return@register false
                }
                !handleKeybind(s as AbstractContainerScreen<*>, InputConstants.Type.KEYBOARD, key.key())
            }
        }

        on<ScreenEvent.Render> {
            val screen = screen as? AbstractContainerScreen<*> ?: return@on
            if (!isActive(screen)) return@on
            cancel()
            renderWheel(guiGraphics, screen, mouseX, mouseY)
        }

        on<TickEvent.End> {
            if (mc.gui.screen() !is AbstractContainerScreen<*>) {
                petScreen = null
                tempDisabled = false
                clicked = false
            }
        }
    }

    private fun isActive(screen: Any): Boolean = enabled && !tempDisabled && screen === petScreen

    private fun onClick(screen: AbstractContainerScreen<*>, rawX: Double, rawY: Double, button: Int): Boolean {
        val mouseX = rawX / scale(screen)
        val mouseY = rawY / scale(screen)
        val (bx, by, bw, bh) = vanillaButton(screen)
        if (mouseX >= bx && mouseX <= bx + bw && mouseY >= by && mouseY <= by + bh) {
            tempDisabled = true
            return true
        }

        val visible = petsOnPage(pets(screen))
        val layout = layout(screen, visible.size)
        val pet = hoveredIndex(mouseX, mouseY, layout)?.let(visible::getOrNull)

        if (button == InputConstants.MOUSE_BUTTON_RIGHT && InputConstants.isKeyDown(InputConstants.KEY_LSHIFT) && pet != null) {
            val now = System.currentTimeMillis()
            if (now - lastClickAt >= 300) {
                lastClickAt = now
                mc.player?.clickSlot(pet.index, 0, ContainerInput.QUICK_MOVE)
            }
            return true
        }
        if (handleKeybind(screen, InputConstants.Type.MOUSE, button)) return true
        if (button == InputConstants.MOUSE_BUTTON_LEFT && pet != null) click(screen, pet.index)
        return true
    }

    private fun handleKeybind(screen: AbstractContainerScreen<*>, type: InputConstants.Type, code: Int): Boolean {
        val index = if (useHotbarBinds) mc.options.keyHotbarSlots.take(PETS_PER_WHEEL).indexOfFirst { it.matches(type.getOrCreate(code)) }
        else keybinds.indexOfFirst { it.value.type == type && it.value.value == code }
        if (index < 0) return false
        pets(screen).getOrNull(wheelPage * PETS_PER_WHEEL + index)?.let { click(screen, it.index) }
        return true
    }

    private fun click(screen: AbstractContainerScreen<*>, slotIndex: Int) {
        val player = mc.player ?: return
        if (player.containerMenu !== screen.menu) return
        val slot = screen.menu.slots.getOrNull(slotIndex) ?: return
        if (slot.item.isEmpty) return
        val now = System.currentTimeMillis()
        if (now - lastClickAt < 300) return
        lastClickAt = now
        player.clickSlot(slotIndex, 0, ContainerInput.PICKUP)
        clicked = true
    }

    private fun renderWheel(gfx: GuiGraphicsExtractor, screen: AbstractContainerScreen<*>, rawX: Int, rawY: Int) {
        val scale = scale(screen)
        val mouseX = rawX / scale
        val mouseY = rawY / scale
        gfx.pose().pushMatrix()
        gfx.pose().scale(scale, scale)
        val pets = pets(screen)
        val pages = pageCount(pets.size)
        wheelPage = wheelPage.coerceIn(0, pages - 1)
        val visible = petsOnPage(pets)
        val layout = layout(screen, visible.size)

        val hovered = hoveredIndex(mouseX.toDouble(), mouseY.toDouble(), layout)
        val activePet = visible.firstOrNull { slot -> slot.item.loreString.any { it.contains("Click to despawn!", true) } }
        val selected = hovered?.let(visible::get) ?: activePet

        gfx.fill(0, 0, ceil(width(screen)).toInt(), ceil(height(screen)).toInt(), 0x96000000.toInt())
        visible.forEachIndexed { i, pet ->
            ringSegment(gfx, layout, i, segmentColor.rgba)
            if (pet === activePet) ringSegment(gfx, layout, i, activeColor.rgba)
            if (i == hovered) ringSegment(gfx, layout, i, hoverColor.rgba)
        }
        separators(gfx, layout)
        visible.forEachIndexed { i, pet -> petInSegment(gfx, pet, i, layout, i == hovered) }
        if (selected != null) center(gfx, selected, selected === activePet, layout)

        centeredText(gfx, "Pets ${wheelPage + 1}/$pages", layout.centerX, layout.centerY - layout.outerRadius - 19f, -1, 0.85f)

        val (bx, by, bw, bh) = vanillaButton(screen)
        val buttonHovered = mouseX >= bx && mouseX <= bx + bw && mouseY >= by && mouseY <= by + bh
        gfx.fill(bx.toInt(), by.toInt(), (bx + bw).toInt(), (by + bh).toInt(), if (buttonHovered) hoverColor.rgba else segmentColor.rgba)
        centeredText(gfx, "Vanilla Menu", bx + bw / 2f, by + bh / 2f - 3.5f, -1, 1f)
        gfx.pose().popMatrix()
    }

    private fun vanillaButton(screen: AbstractContainerScreen<*>): FloatArray {
        val w = mc.font.width("Vanilla Menu") + 16f
        val h = 18f
        return floatArrayOf(width(screen) - w - 5f, height(screen) - h - 5f, w, h)
    }

    private fun ringSegment(gfx: GuiGraphicsExtractor, layout: WheelLayout, index: Int, color: Int) {
        val centerAngle = -PI / 2.0 + index * layout.segmentAngle
        annularSegment(gfx, layout.centerX, layout.centerY, layout.innerRadius, layout.outerRadius, centerAngle - layout.segmentAngle / 2.0, centerAngle + layout.segmentAngle / 2.0, color)
    }

    private fun separators(gfx: GuiGraphicsExtractor, layout: WheelLayout) {
        if (layout.segmentCount <= 1) return
        repeat(layout.segmentCount) { i ->
            val angle = -PI / 2.0 - layout.segmentAngle / 2.0 + i * layout.segmentAngle
            val half = 1.0 / layout.outerRadius
            annularSegment(gfx, layout.centerX, layout.centerY, layout.innerRadius - 1f, layout.outerRadius + 1f, angle - half, angle + half, separatorColor.rgba)
        }
    }

    private fun petInSegment(gfx: GuiGraphicsExtractor, slot: Slot, index: Int, layout: WheelLayout, hovered: Boolean) {
        val angle = -PI / 2.0 + index * layout.segmentAngle
        val cosA = cos(angle).toFloat()
        val sinA = sin(angle).toFloat()
        val itemRadius = (layout.innerRadius + layout.outerRadius) / 2f
        item(gfx, slot.item, layout.centerX + cosA * itemRadius, layout.centerY + sinA * itemRadius, 2.25f * if (hovered) 1.12f else 1f)

        if (!showKeyLabels) return
        val keyName = if (useHotbarBinds) mc.options.keyHotbarSlots.getOrNull(index)?.translatedKeyMessage?.string?.uppercase()?.replace("BUTTON ", "M")
        else keybinds.getOrNull(index)?.value?.displayName?.string?.uppercase()
        if (keyName.isNullOrEmpty()) return

        val keyRadius = layout.outerRadius - 12f
        val keyX = layout.centerX + cosA * keyRadius
        val keyY = layout.centerY + sinA * keyRadius
        val keyWidth = mc.font.width(keyName)
        val textScale = min(0.68f, 24f / keyWidth.coerceAtLeast(1))
        val badge = (keyWidth * textScale + 8f).coerceAtLeast(12f)
        gfx.fill((keyX - badge / 2f).roundToInt(), (keyY - 6).roundToInt(), (keyX + badge / 2f).roundToInt(), (keyY + 6).roundToInt(), 0xC80F0F0F.toInt())
        if (hovered) gfx.fill((keyX - badge / 2f).roundToInt(), (keyY - 6).roundToInt(), (keyX + badge / 2f).roundToInt(), (keyY + 6).roundToInt(), hoverColor.rgba)
        centeredText(gfx, keyName, keyX, keyY - 3.5f * textScale, -1, textScale)
    }

    private fun center(gfx: GuiGraphicsExtractor, selected: Slot, active: Boolean, layout: WheelLayout) {
        val contentScale = (layout.innerRadius / 54f).coerceIn(0.78f, 1.12f)
        val textWidth = (layout.innerRadius * 2f - 14f).coerceAtLeast(36f)
        item(gfx, selected.item, layout.centerX, layout.centerY - 14f * contentScale, min(2.475f, layout.innerRadius * 0.62f / 16f))

        fun line(text: String, y: Float, color: Int, maxScale: Float) =
            centeredText(gfx, text, layout.centerX, y, color, min(maxScale * contentScale, textWidth / mc.font.width(text).coerceAtLeast(1)))

        val nameY = layout.centerY + 3f * contentScale
        line(selected.item.hoverName.string, nameY, -1, 0.78f)
        val heldItem = selected.item.loreString.firstNotNullOfOrNull { it.substringAfter("Held Item:", "").trim().takeIf(String::isNotEmpty) } ?: "None"
        val heldY = nameY + 9.5f * contentScale
        line("Pet Item: $heldItem", heldY, -1, 0.62f)
        if (active) line("ACTIVE", heldY + 10f * contentScale, 0xFF55FF55.toInt(), 0.6f)
    }

    private fun centeredText(gfx: GuiGraphicsExtractor, text: String, x: Float, y: Float, color: Int, scale: Float) {
        gfx.pose().pushMatrix()
        gfx.pose().translate(x, y)
        gfx.pose().scale(scale, scale)
        gfx.text(mc.font, text, -mc.font.width(text) / 2, 0, color)
        gfx.pose().popMatrix()
    }

    private fun item(gfx: GuiGraphicsExtractor, stack: ItemStack, x: Float, y: Float, scale: Float) {
        gfx.pose().pushMatrix()
        gfx.pose().translate(x, y)
        gfx.pose().scale(scale, scale)
        gfx.item(stack, -8, -8)
        gfx.pose().popMatrix()
    }

    private fun annularSegment(gfx: GuiGraphicsExtractor, cx: Float, cy: Float, inner: Float, outer: Float, start: Double, end: Double, color: Int) {
        val pose = Matrix3x2f(gfx.pose())
        val size = ceil(outer * 2f).toInt() + 2
        gfx.guiRenderState.addGuiElement(object : GuiElementRenderState {
            override fun buildVertices(buffer: VertexConsumer) {
                val steps = ceil((end - start) * outer / 2.0).toInt().coerceAtLeast(1)
                val step = (end - start) / steps
                repeat(steps) { i ->
                    val a1 = start + i * step
                    val a2 = a1 + step
                    val c1 = cos(a1).toFloat(); val s1 = sin(a1).toFloat()
                    val c2 = cos(a2).toFloat(); val s2 = sin(a2).toFloat()
                    buffer.addVertexWith2DPose(pose, cx + c1 * outer, cy + s1 * outer).setColor(color)
                    buffer.addVertexWith2DPose(pose, cx + c1 * inner, cy + s1 * inner).setColor(color)
                    buffer.addVertexWith2DPose(pose, cx + c2 * inner, cy + s2 * inner).setColor(color)
                    buffer.addVertexWith2DPose(pose, cx + c2 * outer, cy + s2 * outer).setColor(color)
                }
            }
            override fun pipeline() = RenderPipelines.GUI
            override fun textureSetup(): TextureSetup = TextureSetup.noTexture()
            override fun scissorArea(): ScreenRectangle? = null
            override fun bounds(): ScreenRectangle = ScreenRectangle(floor(cx - outer).toInt() - 1, floor(cy - outer).toInt() - 1, size, size).transformMaxBounds(pose)
        })
    }

    private fun scale(screen: AbstractContainerScreen<*>) = min(screen.width / 960f, screen.height / 540f)
    private fun width(screen: AbstractContainerScreen<*>) = screen.width / scale(screen)
    private fun height(screen: AbstractContainerScreen<*>) = screen.height / scale(screen)

    private fun layout(screen: AbstractContainerScreen<*>, segmentCount: Int): WheelLayout {
        val desired = 138f * (menuScale / 100f)
        val max = min((height(screen) - 96f) / 2f, (width(screen) - 220f) / 2f).coerceAtLeast(82f)
        val outer = min(desired, max)
        return WheelLayout(width(screen) / 2f, height(screen) / 2f - 8f, outer * 0.55f, outer, segmentCount)
    }

    private fun hoveredIndex(mouseX: Double, mouseY: Double, layout: WheelLayout): Int? {
        if (layout.segmentCount == 0) return null
        val x = mouseX - layout.centerX
        val y = mouseY - layout.centerY
        if (hypot(x, y) <= layout.innerRadius) return null
        return Math.floorMod(floor((atan2(y, x) + PI / 2.0 + layout.segmentAngle / 2.0) / layout.segmentAngle).toInt(), layout.segmentCount)
    }

    private fun pets(screen: AbstractContainerScreen<*>): List<Slot> = petSlots.mapNotNull { index ->
        screen.menu.slots.getOrNull(index)?.takeIf {
            !it.item.isEmpty && it.item.`is`(Items.PLAYER_HEAD) && (!favouritePetsOnly || it.item.hoverName.string.startsWith("⭐ "))
        }
    }

    private fun petsOnPage(pets: List<Slot>) = pets.drop(wheelPage * PETS_PER_WHEEL).take(PETS_PER_WHEEL)
    private fun pageCount(petCount: Int) = Math.ceilDiv(petCount, PETS_PER_WHEEL).coerceAtLeast(1)

    private class WheelLayout(val centerX: Float, val centerY: Float, val innerRadius: Float, val outerRadius: Float, val segmentCount: Int) {
        val segmentAngle = if (segmentCount == 0) 0.0 else PI * 2.0 / segmentCount
    }
}
