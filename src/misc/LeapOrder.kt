package com.engineerclient.misc

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.mojang.brigadier.arguments.StringArgumentType
import com.odtheking.odin.features.impl.dungeon.LeapMenu
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.render.roundedRect
import com.odtheking.odin.utils.render.roundedRectOutlined
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.skyblock.PartyUtils
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.nio.file.Files
import java.util.Locale

/**
 * Odin's custom leap order (`/od leaporder a b c d`, Leap Menu sorting Custom), kept across restarts: Odin holds
 * it in memory only, so it is saved to config/engineerclient/leaporder.txt whenever it changes (however it was
 * set) and put back at startup.
 *
 * `/od leaporder` (or `/lo`) with no names opens [LeapOrderScreen]: Odin's leap menu as it would look, the
 * order's players in their spots, to drag between them. `/lo a b c d` sets it as Odin's command does.
 */
object LeapOrder {
    private val file by lazy { FabricLoader.getInstance().configDir.resolve("engineerclient").resolve("leaporder.txt") }
    private var saved: List<String> = emptyList()

    fun install() {
        runCatching {
            if (Files.exists(file)) {
                val names = Files.readAllLines(file).map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.take(4)
                if (names.isNotEmpty() && DungeonUtils.customLeapOrder.isEmpty()) DungeonUtils.customLeapOrder = names
                saved = names
            }
        }.onFailure { EngineerClient.logger.warn("[ec] leap order: couldn't read $file", it) }

        // Saved on any change, also from Odin's own command.
        ClientTickEvents.END_CLIENT_TICK.register {
            val now = DungeonUtils.customLeapOrder
            if (now != saved) EngineerClient.safely("leap order save") { save(now) }
        }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            // Brigadier merges this into Odin's /od leaporder: the bare literal opens the screen, its 4 names still set it.
            for (root in listOf("od", "odin")) dispatcher.register(literal(root).then(literal("leaporder").executes { open(); 1 }))
            dispatcher.register(literal("lo").executes { open(); 1 }
                .then(argument("member1", StringArgumentType.word())
                    .then(argument("member2", StringArgumentType.word())
                        .then(argument("member3", StringArgumentType.word())
                            .then(argument("member4", StringArgumentType.word()).executes { ctx ->
                                val names = (1..4).map { StringArgumentType.getString(ctx, "member$it") }
                                set(names.map { it.lowercase(Locale.ROOT) })
                                EngineerClient.msg("§aLeap order set: §f${names.joinToString(" §7> §f")}")
                                1
                            })))))
        }
    }

    private fun open() = mc.schedule { mc.gui.setScreen(LeapOrderScreen()) }

    /** Odin's order, Odin-style (lower case, ranks 0-3: top left, top right, bottom left, bottom right). */
    fun set(names: List<String>) {
        DungeonUtils.customLeapOrder = names.filter { it.isNotBlank() }.map { it.lowercase(Locale.ROOT) }
    }

    private fun save(names: List<String>) {
        saved = names
        Files.createDirectories(file.parent)
        Files.write(file, names)
    }
}

/**
 * Odin's leap menu, simulated: the 4 boxes where its Leap Menu puts them (24px off the centre, its Render Scale),
 * the custom order's players in rank order, empty spots filled with your party. Drag a player onto another spot
 * to swap them; the order is set (and saved) on the drop.
 */
class LeapOrderScreen : Screen(Component.literal("Leap Order")) {
    /** The names in each spot, as shown ("" for none). */
    private val names = Array(4) { "" }
    private var dragging = -1
    private var sawHeld = false
    private var grabX = 0.0
    private var grabY = 0.0
    private var mouseX = 0.0
    private var mouseY = 0.0

    init {
        val me = mc.player?.name?.string
        val known = (DungeonUtils.dungeonTeammates.map { it.name } + PartyUtils.members).filter { it != me }.distinct()
        fun proper(n: String) = known.firstOrNull { it.equals(n, ignoreCase = true) } ?: n
        DungeonUtils.customLeapOrder.take(4).forEachIndexed { i, n -> names[i] = proper(n) }
        // Spots the order leaves empty: your teammates or party not in it yet.
        val rest = known.filter { k -> names.none { it.equals(k, ignoreCase = true) } }.iterator()
        for (i in 0 until 4) if (names[i].isEmpty() && rest.hasNext()) names[i] = rest.next()
    }

    override fun isPauseScreen() = false

    private val scale get() = com.engineerclient.rotation.LeapHighlight.odinScale()

    /** Spot [i]'s box on screen: x0, y0, x1, y1. */
    private fun box(i: Int): IntArray {
        val s = scale
        val w = (LeapMenu.BOX_WIDTH * s).toInt()
        val h = (LeapMenu.BOX_HEIGHT * s).toInt()
        val x0 = if (i % 2 == 0) width / 2 - 24 - w else width / 2 + 24
        val y0 = if (i / 2 == 0) height / 2 - 24 - h else height / 2 + 24
        return intArrayOf(x0, y0, x0 + w, y0 + h)
    }

    private fun spotAt(x: Double, y: Double): Int = (0 until 4).firstOrNull { val b = box(it); x >= b[0] && x < b[2] && y >= b[1] && y < b[3] } ?: -1

    private fun clazz(name: String): DungeonClass? = DungeonUtils.dungeonTeammates.firstOrNull { it.name.equals(name, ignoreCase = true) }?.clazz

    override fun extractRenderState(gfx: GuiGraphicsExtractor, mx: Int, my: Int, delta: Float) {
        super.extractRenderState(gfx, mx, my, delta)
        mouseX = mx.toDouble(); mouseY = my.toDouble()
        // A release the screen never heard about (focus lost, another button): dropped where the mouse is.
        // Only once SDL has been seen holding it: if its state can't be read, the release event alone drops.
        if (dragging >= 0) when (leftHeld()) {
            true -> sawHeld = true
            false -> if (sawHeld) drop(mouseX, mouseY)
            null -> {}
        }
        val font = mc.font
        fun centred(text: String, y: Int) = gfx.text(text, (width - font.width(text)) / 2, y, WHITE, shadow = true)
        centred("§6§lLeap Order", 12)
        centred("§7Drag a player onto another spot to swap them. Saved as you drop.", 26)
        if (LeapMenu.type != LeapMenu.Sorting.CUSTOM) centred("§cOdin's Leap Menu sorting isn't Custom, so it won't use this order.", 38)
        centred("§8Top left is first, then top right, bottom left, bottom right.", height - 20)

        for (i in 0 until 4) {
            val b = box(i)
            if (i == dragging) {
                // The spot it was lifted from.
                gfx.roundedRectOutlined(b[0], b[1], b[2], b[3], EMPTY.rgba, OUTLINE.rgba, 1.5f, 9f * scale)
                continue
            }
            val hovered = dragging >= 0 && spotAt(mouseX, mouseY) == i
            drawBox(gfx, b[0], b[1], names[i], if (hovered) HOVER else BOX)
        }
        if (dragging >= 0) {
            val b = box(dragging)
            drawBox(gfx, (mouseX - grabX).toInt().coerceIn(0, width - (b[2] - b[0])), (mouseY - grabY).toInt().coerceIn(0, height - (b[3] - b[1])), names[dragging], DRAGGED)
        }
    }

    /** A leap menu box at [x], [y] for [name], drawn at Odin's size and scaled as its Render Scale does. */
    private fun drawBox(gfx: GuiGraphicsExtractor, x: Int, y: Int, name: String, fill: Color) {
        val w = LeapMenu.BOX_WIDTH
        val h = LeapMenu.BOX_HEIGHT
        val s = scale
        gfx.pose().pushMatrix()
        gfx.pose().translate(x.toFloat(), y.toFloat())
        gfx.pose().scale(s, s)
        val c = clazz(name)
        gfx.roundedRectOutlined(0, 0, w, h, fill.rgba, (c?.color ?: OUTLINE).rgba, 2f, 9f)
        if (name.isEmpty()) {
            gfx.text("§8Empty", 15, h / 2 - 4, WHITE, shadow = false)
        } else {
            val face = (h * 0.76).toInt()
            val skin = mc.connection?.getPlayerInfoIgnoreCase(name)?.skin ?: mc.player?.skin
            if (skin != null) PlayerFaceExtractor.extractRenderState(gfx, skin, 9, 9, face)
            gfx.text(name, 15 + face, (h / 2.5).toInt(), c?.color ?: WHITE, shadow = true)
            gfx.text(c?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "§7In party", 15 + face, (h / 1.7).toInt(), WHITE, shadow = true)
        }
        gfx.pose().popMatrix()
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val i = spotAt(event.x(), event.y())
        if (isLeft(event) && i >= 0 && names[i].isNotEmpty()) {
            dragging = i
            sawHeld = false
            val b = box(i)
            grabX = event.x() - b[0]; grabY = event.y() - b[1]
            return true
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (dragging < 0) return super.mouseReleased(event)
        drop(event.x(), event.y())
        return true
    }

    /** Lets go of the dragged player at [x], [y]: swapped with the spot there, if any. */
    private fun drop(x: Double, y: Double) {
        val to = spotAt(x, y)
        if (to >= 0 && to != dragging) {
            val t = names[to]; names[to] = names[dragging]; names[dragging] = t
            LeapOrder.set(names.toList())
            mc.player?.playSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.4f, 1.2f)
        }
        dragging = -1
    }

    /**
     * A left click. Under SDL (26.3) the event's button number isn't reliable (a left click can come through as
     * 1), so anything but SDL saying the right button alone is down counts.
     */
    private fun isLeft(event: MouseButtonEvent): Boolean = runCatching {
        val state = org.lwjgl.sdl.SDLMouse.SDL_GetMouseState(null as java.nio.FloatBuffer?, null as java.nio.FloatBuffer?)
        !((state and 4) != 0 && (state and 1) == 0)
    }.getOrElse { event.button() == 0 }

    /** The left button is still held (SDL), or null when that can't be told. */
    private fun leftHeld(): Boolean? = runCatching {
        (org.lwjgl.sdl.SDLMouse.SDL_GetMouseState(null as java.nio.FloatBuffer?, null as java.nio.FloatBuffer?) and 1) != 0
    }.getOrNull()

    private companion object {
        val WHITE = Color(255, 255, 255, 1f)
        val BOX = Color(30, 30, 30, 0.85f)
        val HOVER = Color(60, 60, 60, 0.9f)
        val DRAGGED = Color(45, 45, 45, 0.7f)
        val EMPTY = Color(0, 0, 0, 0.25f)
        val OUTLINE = Color(120, 120, 120, 1f)
    }
}
