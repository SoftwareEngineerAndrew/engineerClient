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
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.event.Event
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
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

        // After Odin's commands, without adding or merging any node into /odin or /od (so nothing else under /od can
        // be affected): Odin's own "leaporder" node just gets a run for the
        // bare form, opening the screen. With names it is untouched, Odin's command as always. /od points at a copy
        // of /odin's node whose children are the same node objects, so both see the one change.
        val afterOdin = Identifier.fromNamespaceAndPath("engineerclient", "after_odin")
        ClientCommandRegistrationCallback.EVENT.addPhaseOrdering(Event.DEFAULT_PHASE, afterOdin)
        ClientCommandRegistrationCallback.EVENT.register(afterOdin) { dispatcher, _ ->
            EngineerClient.safely("leap order /od") {
                val nodes = listOfNotNull(
                    dispatcher.root.getChild("odin")?.getChild("leaporder"),
                    dispatcher.root.getChild("od")?.let { it.redirect ?: it }?.getChild("leaporder"),
                ).distinct()
                for (node in nodes) COMMAND.set(node, com.mojang.brigadier.Command<FabricClientCommandSource> { open(); 1 })
                if (nodes.isEmpty()) EngineerClient.logger.info("[ec] leap order: Odin has no /od leaporder here, /lo only")
            }
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

    /** Brigadier's node command (no setter: Odin's node is given the bare run in place). */
    private val COMMAND by lazy { com.mojang.brigadier.tree.CommandNode::class.java.getDeclaredField("command").apply { isAccessible = true } }

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
    /** A box sliding into its spot after a drop: where it started (x, y), and when. */
    private val slideFrom = arrayOfNulls<IntArray>(4)
    private val slideAt = LongArray(4)

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

    private val scale get() = com.engineerclient.misc.OdinLeapScale.get()

    /** Spot [i]'s box on screen: x0, y0, x1, y1. */
    private fun box(i: Int): IntArray {
        val s = scale
        val w = (LeapMenu.BOX_WIDTH * s).toInt()
        val h = (LeapMenu.BOX_HEIGHT * s).toInt()
        val x0 = if (i % 2 == 0) width / 2 - 24 - w else width / 2 + 24
        val y0 = if (i / 2 == 0) height / 2 - 24 - h else height / 2 + 24
        return intArrayOf(x0, y0, x0 + w, y0 + h)
    }

    /** Where spot [i]'s box is drawn: its place, or on its way there for [SLIDE_MS] after a drop (easing out). */
    private fun drawnAt(i: Int): IntArray {
        val b = box(i)
        val from = slideFrom[i] ?: return b
        val t = (System.currentTimeMillis() - slideAt[i]) / SLIDE_MS.toFloat()
        if (t >= 1f) { slideFrom[i] = null; return b }
        val e = 1f - (1f - t) * (1f - t) * (1f - t)
        val x = from[0] + ((b[0] - from[0]) * e).toInt()
        val y = from[1] + ((b[1] - from[1]) * e).toInt()
        return intArrayOf(x, y, x + b[2] - b[0], y + b[3] - b[1])
    }

    private fun slide(i: Int, x: Int, y: Int) { slideFrom[i] = intArrayOf(x, y); slideAt[i] = System.currentTimeMillis() }

    /** Where the dragged box is drawn for the mouse at [x], [y]: held where it was grabbed, kept on screen. */
    private fun heldAt(x: Double, y: Double): IntArray {
        val b = box(dragging)
        return intArrayOf((x - grabX).toInt().coerceIn(0, width - (b[2] - b[0])), (y - grabY).toInt().coerceIn(0, height - (b[3] - b[1])))
    }

    /** The spot for a point anywhere on screen: its quarter (the leap menu's boxes sit one per quarter). */
    private fun spotAt(x: Double, y: Double): Int = (if (x < width / 2.0) 0 else 1) + (if (y < height / 2.0) 0 else 2)

    private fun onBox(i: Int, x: Double, y: Double): Boolean { val b = box(i); return x >= b[0] && x < b[2] && y >= b[1] && y < b[3] }

    private fun clazz(name: String): DungeonClass? = DungeonUtils.dungeonTeammates.firstOrNull { it.name.equals(name, ignoreCase = true) }?.clazz

    override fun extractRenderState(gfx: GuiGraphicsExtractor, mx: Int, my: Int, delta: Float) {
        super.extractRenderState(gfx, mx, my, delta)
        mouseX = mx.toDouble(); mouseY = my.toDouble()
        // A release the screen never heard about (focus lost, another button): dropped where the mouse is.
        // Only once GLFW has been seen holding it: if its state can't be read, the release event alone drops.
        if (dragging >= 0) when (leftHeld()) {
            true -> sawHeld = true
            false -> if (sawHeld) drop(mouseX, mouseY)
            null -> {}
        }
        val font = mc.font
        fun centred(text: String, y: Int) = gfx.text(text, (width - font.width(text)) / 2, y, WHITE, shadow = true)
        centred("§6§lLeap Order", 12)
        centred("§7Drag a player into another quarter of the screen to swap them. Saved as you drop.", 26)
        if (LeapMenu.type != LeapMenu.Sorting.CUSTOM) centred("§cOdin's Leap Menu sorting isn't Custom, so it won't use this order.", 38)
        centred("§8Top left is first, then top right, bottom left, bottom right.", height - 20)

        // The quarter a drop would go to.
        if (dragging >= 0) {
            val q = spotAt(mouseX, mouseY)
            val qx = if (q % 2 == 0) 0 else width / 2
            val qy = if (q / 2 == 0) 0 else height / 2
            gfx.fill(qx, qy, qx + (width + 1) / 2, qy + (height + 1) / 2, QUARTER.rgba)
        }
        for (i in 0 until 4) {
            val b = box(i)
            if (i == dragging) {
                // The spot it was lifted from.
                gfx.roundedRectOutlined(b[0], b[1], b[2], b[3], EMPTY.rgba, OUTLINE.rgba, 1.5f, 9f * scale)
                continue
            }
            val hovered = dragging >= 0 && spotAt(mouseX, mouseY) == i
            val at = drawnAt(i)
            drawBox(gfx, at[0], at[1], names[i], if (hovered) HOVER else BOX)
        }
        if (dragging >= 0) {
            val at = heldAt(mouseX, mouseY)
            drawBox(gfx, at[0], at[1], names[dragging], DRAGGED)
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
            // Held where it was grabbed; grabbed elsewhere in its quarter, held by its middle.
            if (onBox(i, event.x(), event.y())) { grabX = event.x() - b[0]; grabY = event.y() - b[1] }
            else { grabX = (b[2] - b[0]) / 2.0; grabY = (b[3] - b[1]) / 2.0 }
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
        // Both boxes slide the rest of the way: the dropped one from the mouse, the one it swapped with from its old spot.
        val held = heldAt(x, y)
        val other = drawnAt(to)
        slide(to, held[0], held[1])
        if (to != dragging) slide(dragging, other[0], other[1])
        if (to != dragging) {
            val t = names[to]; names[to] = names[dragging]; names[dragging] = t
            LeapOrder.set(names.toList())
            mc.player?.playSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.4f, 1.2f)
        }
        dragging = -1
    }

    /** A left click. */
    private fun isLeft(event: MouseButtonEvent): Boolean = event.button() == 0

    /** The left button is still held (GLFW), or null when that can't be told. */
    private fun leftHeld(): Boolean? = runCatching {
        org.lwjgl.glfw.GLFW.glfwGetMouseButton(mc.window.handle(), org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS
    }.getOrNull()

    private companion object {
        /** How long a dropped box takes to slide into its spot. */
        const val SLIDE_MS = 90L
        val WHITE = Color(255, 255, 255, 1f)
        val BOX = Color(30, 30, 30, 0.85f)
        val HOVER = Color(60, 60, 60, 0.9f)
        val DRAGGED = Color(45, 45, 45, 0.7f)
        val EMPTY = Color(0, 0, 0, 0.25f)
        val OUTLINE = Color(120, 120, 120, 1f)
        val QUARTER = Color(255, 255, 255, 0.06f)
    }
}
