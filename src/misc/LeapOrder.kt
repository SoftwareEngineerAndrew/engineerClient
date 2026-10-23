package com.engineerclient.misc

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.adr.PartyTracker
import com.mojang.brigadier.arguments.StringArgumentType
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.features.ModuleManager
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
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.util.Util
import net.minecraft.world.entity.player.PlayerSkin

/**
 * Odin's custom leap order (`/od leaporder a b c d`, Leap Menu sorting Custom), kept across restarts: Odin holds
 * it in memory only, so it is saved to config/engineerclient/leaporder.txt whenever it changes (however it was
 * set) and put back at startup.
 *
 * `/od leaporder` (or `/lo`) with no names opens [LeapOrderScreen]: Odin's leap menu as it would look, the
 * order's players in their spots, to drag between them. `/lo a b c d` sets it as Odin's command does.
 *
 * Changing the order (any way) switches Odin's Leap Menu sorting to Custom. It goes back to what it was (Odin
 * Sorting if it was already Custom) only once the party is clearly a different one ([dropReason]): a player or
 * two swapping out doesn't. Only ADR's party tracker counts, and only when it has no doubt, so a party that
 * isn't known never switches it. Kept across restarts in config/engineerclient/leaporder-sorting.txt: the
 * sorting to go back to, the party it was set for, since when you've been out of a party ("-" when in one).
 */
object LeapOrder {
    private val file by lazy { FabricLoader.getInstance().configDir.resolve("engineerclient").resolve("leaporder.txt") }
    private var saved: List<String> = emptyList()
    private val autoFile by lazy { file.resolveSibling("leaporder-sorting.txt") }
    /** The sorting to put back when the party changes, while this switched it to Custom. */
    private var revertTo: LeapMenu.Sorting? = null
    /** Who (besides you) was in the party when the order was set; null until the party is known. */
    private var orderParty: Set<String>? = null
    /** Since when you've surely been out of a party, or null while in one (or it isn't known). */
    private var aloneSince: Long? = null

    /** Out of a party this long: the order is for a party that's gone. */
    private const val ALONE_MS = 10 * 60_000L
    /** More of the order's party than this gone: it's another party. */
    private const val MAX_SWAPPED = 3

    fun install() {
        runCatching {
            if (Files.exists(file)) {
                val names = Files.readAllLines(file).map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.take(4)
                if (names.isNotEmpty() && DungeonUtils.customLeapOrder.isEmpty()) DungeonUtils.customLeapOrder = names
                saved = names
            }
        }.onFailure { EngineerClient.logger.warn("[ec] leap order: couldn't read $file", it) }
        runCatching {
            if (Files.exists(autoFile)) {
                val lines = Files.readAllLines(autoFile)
                revertTo = LeapMenu.Sorting.entries.firstOrNull { it.name == lines.getOrNull(0)?.trim() }
                orderParty = lines.getOrNull(1)?.takeIf { it.trim() != "?" }?.let { l -> l.split(' ').filter { it.isNotBlank() }.toSet() }
                aloneSince = lines.getOrNull(2)?.trim()?.toLongOrNull()
            }
        }.onFailure { EngineerClient.logger.warn("[ec] leap order: couldn't read $autoFile", it) }

        // Saved on any change, also from Odin's own command.
        ClientTickEvents.END_CLIENT_TICK.register {
            val now = DungeonUtils.customLeapOrder
            if (now != saved) EngineerClient.safely("leap order save") { save(now); if (now.isNotEmpty()) useCustom() }
            EngineerClient.safely("leap order party") { checkParty() }
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

    /**
     * [name]'s skin: from the tab list when they're in your lobby, else looked up by name (Mojang's profile, then
     * the skin, in the background, kept for the session). Steve's while that loads or when the name has none.
     */
    fun skin(name: String): PlayerSkin {
        mc.connection?.getPlayerInfoIgnoreCase(name)?.let { return it.skin }
        if (name.equals(mc.player?.name?.string, ignoreCase = true)) mc.player?.let { return it.skin }
        val key = name.lowercase(Locale.ROOT)
        skins[key]?.let { return it }
        if (looking.add(key)) {
            CompletableFuture.supplyAsync({ mc.services().profileResolver().fetchByName(name) }, Util.nonCriticalIoPool())
                .thenCompose { p -> p.map { mc.skinManager.get(it) }.orElse(CompletableFuture.completedFuture(Optional.empty())) }
                .whenComplete { s, e ->
                    if (e != null) EngineerClient.logger.warn("[ec] leap order: no skin for {} ({})", name, e.toString())
                    s?.ifPresent { skins[key] = it }
                }
        }
        return DefaultPlayerSkin.getDefaultSkin()
    }
    private val skins = ConcurrentHashMap<String, PlayerSkin>()
    private val looking = ConcurrentHashMap.newKeySet<String>()

    /** Brigadier's node command (no setter: Odin's node is given the bare run in place). */
    private val COMMAND by lazy { com.mojang.brigadier.tree.CommandNode::class.java.getDeclaredField("command").apply { isAccessible = true } }

    private fun open() = mc.schedule { mc.gui.setScreen(LeapOrderScreen()) }

    /** Odin's order, Odin-style (lower case, ranks 0-3: top left, top right, bottom left, bottom right). */
    fun set(names: List<String>) {
        DungeonUtils.customLeapOrder = names.filter { it.isNotBlank() }.map { it.lowercase(Locale.ROOT) }
    }

    private val sorting get() = LeapMenu.settings["Sorting"] as? SelectorSetting<LeapMenu.Sorting>

    /** Whether Odin's Leap Menu sorts by the order (sorting Custom). */
    val custom get() = sorting?.value == LeapMenu.Sorting.CUSTOM

    /** The Leap Order screen's checkbox: on is as setting the order, off puts back the sorting from before. */
    fun setCustom(on: Boolean) {
        val s = sorting ?: return
        if (on == custom) return
        if (on) { useCustom(); return }
        // checkParty sees it's no longer Custom and forgets the rest.
        s.value = revertTo ?: LeapMenu.Sorting.ODIN
        ModuleManager.saveConfigurations()
    }

    /** The order was just set: sort the leap menu by it, for this party. */
    private fun useCustom() {
        val s = sorting ?: return
        if (s.value != LeapMenu.Sorting.CUSTOM) {
            revertTo = s.value
            s.value = LeapMenu.Sorting.CUSTOM
            ModuleManager.saveConfigurations()
            EngineerClient.msg("§7Leap Menu sorting: §fCustom§7 (back to ${label(revertTo!!)} once you're with a different party)")
        } else if (revertTo == null) revertTo = LeapMenu.Sorting.ODIN
        orderParty = party()
        aloneSince = null
        saveAuto()
    }

    /** The party is clearly another one since the order was set: back to the sorting from before. */
    private fun checkParty() {
        val back = revertTo ?: return
        val s = sorting ?: return
        // Set back to something else by hand: nothing to put back.
        if (s.value != LeapMenu.Sorting.CUSTOM) { revertTo = null; orderParty = null; aloneSince = null; saveAuto(); return }
        val why = dropReason(System.currentTimeMillis()) ?: return
        s.value = back
        revertTo = null
        orderParty = null
        aloneSince = null
        ModuleManager.saveConfigurations()
        saveAuto()
        EngineerClient.msg("§7Leap Menu sorting back to §f${label(back)}§7: $why")
    }

    /**
     * Why the order no longer fits, or null while it might. Any one of:
     *  - out of a party for [ALONE_MS] straight;
     *  - more than [MAX_SWAPPED] of the party it was set for gone;
     *  - none of the order's players left in the party.
     * Kept quiet while the party isn't surely known.
     */
    private fun dropReason(now: Long): String? {
        val me = mc.user.name.lowercase(Locale.ROOT)
        val order = DungeonUtils.customLeapOrder.map { it.lowercase(Locale.ROOT) }.filter { it != me }.toSet()

        val party = party()
        if (party != null && party.isEmpty()) {
            val since = aloneSince ?: now.also { aloneSince = it; saveAuto() }
            if (now - since > ALONE_MS) return "out of a party for ${ALONE_MS / 60_000} minutes"
        } else if (party != null) {
            if (aloneSince != null) { aloneSince = null; saveAuto() }
            val was = orderParty ?: party.also { orderParty = it; saveAuto() }
            val gone = was.count { it !in party }
            if (gone > MAX_SWAPPED) return "$gone of the party it was set for are gone"
            if (order.isNotEmpty() && order.none { it in party }) return "none of the leap order's players are in the party"
        }
        return null
    }

    /**
     * Who's in your party besides you (lower case; empty: not in one), or null while that isn't surely known.
     * Only ADR's tracker with no doubt: Odin's list can be partial (after a Party Finder join it only has whoever
     * joined since), and a partial list would read as people having left.
     */
    private fun party(): Set<String>? {
        val me = mc.user.name.lowercase(Locale.ROOT)
        val names = synchronized(PartyTracker.state) {
            PartyTracker.state.members.keys.toList().takeIf { PartyTracker.state.known && PartyTracker.state.doubt == null }
        } ?: return null
        return names.map { it.lowercase(Locale.ROOT) }.filter { it != me }.toSet()
    }

    /** Its name as Odin's setting shows it ("Odin Sorting"). */
    private fun label(s: LeapMenu.Sorting) = s.toString()

    private fun saveAuto() = runCatching {
        val back = revertTo
        if (back == null) Files.deleteIfExists(autoFile)
        else {
            Files.createDirectories(autoFile.parent)
            Files.write(autoFile, listOf(back.name, orderParty?.sorted()?.joinToString(" ") ?: "?", aloneSince?.toString() ?: "-"))
        }
    }.onFailure { EngineerClient.logger.warn("[ec] leap order: couldn't write $autoFile", it) }

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

    /** The party as last shown ([PartyTracker]'s version), to redo the spots when it changes. */
    private var partyVersion = -1

    init {
        fill()
        EngineerClient.safely("leap order party") { PartyTracker.askIfUnknown() }
    }

    /**
     * Who goes where: your party (or dungeon teammates) in the order's ranks, those not in the order after them.
     * With the party known, names in the order that aren't in it are left out (a past party's order would hide
     * the people you're with now); with it unknown, the order is shown as it is.
     */
    private fun fill() {
        partyVersion = PartyTracker.state.version
        val me = mc.player?.name?.string
        val party = party().filter { !it.equals(me, ignoreCase = true) }
        val order = DungeonUtils.customLeapOrder.take(4)
        val shown = if (party.isEmpty()) order
            else order.mapNotNull { n -> party.firstOrNull { it.equals(n, ignoreCase = true) } } +
                party.filter { p -> order.none { it.equals(p, ignoreCase = true) } }
        for (i in 0 until 4) names[i] = shown.getOrElse(i) { "" }
    }

    /** The people you're with: the dungeon's teammates in one, else the party (ADR's tracker, else Odin's). */
    private fun party(): List<String> {
        val team = DungeonUtils.dungeonTeammates.map { it.name }
        if (DungeonUtils.inDungeons && team.isNotEmpty()) return team
        val tracked = synchronized(PartyTracker.state) {
            PartyTracker.state.members.values.map { it.name }.takeIf { PartyTracker.state.known }
        }
        return (tracked ?: PartyUtils.members).distinct()
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
        // The party changed (or a /p list answered) while open: redo the spots, unless one is being dragged.
        if (dragging < 0 && PartyTracker.state.version != partyVersion) fill()
        // A release the screen never heard about (focus lost, another button): dropped where the mouse is.
        // Only once SDL has been seen holding it: if its state can't be read, the release event alone drops.
        if (dragging >= 0) when (leftHeld()) {
            true -> sawHeld = true
            false -> if (sawHeld) drop(mouseX, mouseY)
            null -> {}
        }

        // Whether the leap menu uses this order: Odin's sorting is Custom.
        val cb = checkbox()
        val on = LeapOrder.custom
        val hot = dragging < 0 && mouseX >= cb[0] && mouseX < cb[2] && mouseY >= cb[1] && mouseY < cb[3]
        gfx.fill(cb[0], cb[1], cb[0] + CHECK, cb[1] + CHECK, (if (hot) CHECK_HOT else OUTLINE).rgba)
        gfx.fill(cb[0] + 1, cb[1] + 1, cb[0] + CHECK - 1, cb[1] + CHECK - 1, BOX.rgba)
        if (on) gfx.fill(cb[0] + 3, cb[1] + 3, cb[0] + CHECK - 3, cb[1] + CHECK - 3, CHECKED.rgba)
        gfx.text(LABEL, cb[0] + CHECK + 4, cb[1] + 1, WHITE, shadow = true)

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
            PlayerFaceExtractor.extractRenderState(gfx, LeapOrder.skin(name), 9, 9, face)
            gfx.text(name, 15 + face, (h / 2.5).toInt(), c?.color ?: WHITE, shadow = true)
            gfx.text(c?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "§7In party", 15 + face, (h / 1.7).toInt(), WHITE, shadow = true)
        }
        gfx.pose().popMatrix()
    }

    /** The Custom checkbox, centred above the top boxes (its label included): x0, y0, x1, y1. */
    private fun checkbox(): IntArray {
        val w = CHECK + 4 + mc.font.width(LABEL)
        val x0 = (width - w) / 2
        val y0 = box(0)[1] - CHECK - 10
        return intArrayOf(x0, y0, x0 + w, y0 + CHECK)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val cb = checkbox()
        if (isLeft(event) && event.x() >= cb[0] && event.x() < cb[2] && event.y() >= cb[1] && event.y() < cb[3]) {
            LeapOrder.setCustom(!LeapOrder.custom)
            mc.player?.playSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.4f, 1.2f)
            return true
        }
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
            // Odin's order has no gaps (its menu fills the spots in rank order), so a player dropped on an
            // empty spot moves up to the first free one here too: the screen shows the order as it applies.
            val order = names.filter { it.isNotEmpty() }
            for (i in 0 until 4) names[i] = order.getOrElse(i) { "" }
            LeapOrder.set(order)
            // Moving anyone flicks Custom on, even back to the order it was.
            LeapOrder.setCustom(true)
            mc.player?.playSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.4f, 1.2f)
        }
        dragging = -1
    }

    /** A left click: 26.3 numbers the buttons as SDL does (left 1, middle 2, right 3), not GLFW's (left 0). */
    private fun isLeft(event: MouseButtonEvent): Boolean = event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT

    /** The left button is still held (SDL), or null when that can't be told. */
    private fun leftHeld(): Boolean? = runCatching {
        (org.lwjgl.sdl.SDLMouse.SDL_GetMouseState(null as java.nio.FloatBuffer?, null as java.nio.FloatBuffer?) and 1) != 0
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
        const val CHECK = 10
        const val LABEL = "Custom Sorting"
        val CHECKED = Color(85, 255, 85, 1f)
        val CHECK_HOT = Color(200, 200, 200, 1f)
    }
}
