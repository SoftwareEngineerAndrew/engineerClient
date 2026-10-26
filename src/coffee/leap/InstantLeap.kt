package com.coffeeclient.leap

import com.coffeeclient.CoffeeClient
import com.coffeeclient.mixin.InstantLeapUseInvoker
import com.mojang.blaze3d.platform.InputConstants
import com.odtheking.odin.features.impl.dungeon.LeapMenu
import com.odtheking.odin.utils.equalsOneOf
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.modMessage
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.phys.HitResult

/**
 * Leap Extras' Instant Open: the leap menu shows the moment you right-click a leap, not a server round trip later.
 *
 * The press itself (KeyMapping.click, InstantLeapKeyMixin) uses the item there and then (vanilla waits for the next tick,
 * up to 50 ms) and opens a stand-in "Spirit Leap" chest with nothing in it ([Predicted]). Odin draws its leap menu on it from
 * what it already knows of the party (tab and the map), so it can be clicked straight away. A leap picked on it is held
 * (Odin's leapTo, LeapMenuLeapToMixin) and clicked in the real menu the moment its heads arrive (InstantLeapPacketsMixin);
 * the real one replaces the stand-in when it opens. No real menu in time: the stand-in closes. Closed by you before the
 * real one came: the real one is closed when it does.
 */
object InstantLeap {
    private val mc get() = CoffeeClient.mc

    /** The stand-in: never the player's open container (nothing is sent for it), and clicks on its slots do nothing. */
    class Predicted(menu: ChestMenu) : ContainerScreen(menu, CoffeeClient.mc.player!!.inventory, Component.literal("Spirit Leap")) {
        override fun slotClicked(slot: Slot, slotId: Int, buttonNum: Int, containerInput: ContainerInput) {}
        override fun onClose() {
            dismissed()
            mc.gui.setScreen(null)
        }
    }

    /** When the stand-in went up (ms), 0 once the real menu replaced it or it closed. */
    private var shownAt = 0L
    /** A leap picked on the stand-in, for the real menu. */
    private var queued: String? = null
    private var queuedAt = 0L
    /** You closed the stand-in (ms): the real menu, if it still comes, is closed for you. */
    private var dismissedAt = 0L
    /** The last stand-in went up (ms): the real menu that follows it doesn't restart Click Delay. */
    private var lastShownAt = 0L

    init {
        ClientTickEvents.END_CLIENT_TICK.register { CoffeeClient.safely("instant leap") { tick() } }
    }

    /** Whether [screen] opening follows a stand-in (it's the real menu taking over). */
    @JvmStatic
    fun replacesPrediction(screen: Any?): Boolean = screen !is Predicted && System.currentTimeMillis() - lastShownAt < GIVE_UP_MAX_MS

    /** KeyMapping.click: true when this press opened the stand-in (the click is then used up here). */
    @JvmStatic
    fun onPress(key: InputConstants.Key): Boolean {
        if (!LeapExtras.instantOpenOn()) return false
        val player = mc.player ?: return false
        if (key.name != mc.options.keyUse.saveString()) return false
        if (mc.gui.screen() != null || mc.gui.overlay() != null || mc.gameMode == null) return false
        if (!LeapMenu.enabled || !DungeonUtils.inDungeons) return false
        if (DungeonUtils.leapTeammates.none { it.name != "Empty" }) return false
        if (!player.mainHandItem.itemId.equalsOneOf("SPIRIT_LEAP", "INFINITE_SPIRIT_LEAP")) return false
        // Clicking someone does something else with it.
        if (mc.hitResult?.type == HitResult.Type.ENTITY || player.isUsingItem || player.isSpectator) return false
        (mc as InstantLeapUseInvoker).`cc$startUseItem`()
        val now = System.currentTimeMillis()
        shownAt = now; lastShownAt = now; queued = null; dismissedAt = 0
        // A container id the server never uses for one: the stand-in is never mistaken for a real menu.
        mc.gui.setScreen(Predicted(ChestMenu.sixRows(-7, player.inventory)))
        return true
    }

    /** Odin's leapTo: true when it's the stand-in's (the leap is held for the real menu). */
    @JvmStatic
    fun onLeap(name: String, screen: Any?): Boolean {
        if (screen !is Predicted) return false
        queued = name; queuedAt = System.currentTimeMillis()
        // Already here (it came in between): clicked now.
        tryClick()
        return true
    }

    /** The server opened a menu. */
    @JvmStatic
    fun onOpened() {
        val screen = mc.gui.screen() as? AbstractContainerScreen<*> ?: return
        if (!screen.title.string.equalsOneOf("Spirit Leap", "Teleport to Player")) return
        if (dismissedAt != 0L && System.currentTimeMillis() - dismissedAt < GIVE_UP_MAX_MS) {
            dismissedAt = 0
            mc.player?.closeContainer()
            return
        }
        shownAt = 0
        tryClick()
    }

    /** A menu's items arrived (all of them, or one): a held leap is clicked once its player's head is there. */
    @JvmStatic
    fun onContents() { if (queued != null) tryClick() }

    private fun tryClick() {
        val name = queued ?: return
        val player = mc.player ?: return
        val screen = mc.gui.screen() as? AbstractContainerScreen<*> ?: return
        if (screen is Predicted || screen.menu !== player.containerMenu) return
        if (!screen.title.string.equalsOneOf("Spirit Leap", "Teleport to Player")) return
        val slots = screen.menu.slots
        if (slots.size < 16) return
        // As Odin's leapTo finds it.
        val slot = slots.subList(11, 16).firstOrNull { it.item.hoverName.string.substringAfter(' ').equals(name, true) } ?: return
        queued = null
        mc.gameMode?.handleContainerInput(screen.menu.containerId, slot.index, 0, ContainerInput.PICKUP, player)
        modMessage("Teleporting to $name.")
    }

    private fun dismissed() {
        if (shownAt != 0L) dismissedAt = System.currentTimeMillis()
        shownAt = 0; queued = null
    }

    /** How long the stand-in waits for the real menu: three of your pings and a bit, 0.8 to 3 s. */
    private fun giveUpMs(): Long {
        val ping = mc.player?.let { p -> mc.connection?.getPlayerInfo(p.uuid)?.latency } ?: 200
        return (ping * 3L + 300).coerceIn(GIVE_UP_MIN_MS, GIVE_UP_MAX_MS)
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        if (shownAt != 0L && mc.gui.screen() is Predicted && now - shownAt > giveUpMs()) {
            shownAt = 0; queued = null
            mc.gui.setScreen(null)
            modMessage("The leap menu didn't open.")
        }
        if (queued != null && now - queuedAt > GIVE_UP_MAX_MS) queued = null
        if (dismissedAt != 0L && now - dismissedAt > GIVE_UP_MAX_MS) dismissedAt = 0
    }

    private const val GIVE_UP_MIN_MS = 800L
    private const val GIVE_UP_MAX_MS = 3000L
}
