package com.engineerclient.skyblock

import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.NumberSetting
import com.adm.events.RenderExtractEvent
import com.adm.events.core.on
import com.adm.features.Category
import com.adm.features.Module
import com.adm.utils.modMessage
import com.engineerclient.EngineerClient
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

object PingMenus : Module(
    name = "50 Ping Menus",
    category = Category.SKYBLOCK,
    description = "Wardrobe, loadouts, pets and stats open as if you had 50 ping; clicks wait for the real menu.",
) {
    private val wardrobeOn by BooleanSetting("Wardrobe", true, desc = "/wardrobe, /armor and /equipment.")
    private val loadoutsOn by BooleanSetting("Loadouts", true, desc = "/loadouts and /loadout.")
    private val petsOn by BooleanSetting("Pets", true, desc = "/pets.")
    private val statsOn by BooleanSetting("Stats", true, desc = "/stats.")
    private val menuOn by BooleanSetting("SkyBlock Menu Buttons", true, desc = "The SkyBlock Menu's Pets, Loadouts and Stats buttons too.")
    private val highlight by BooleanSetting("Show Queued", true, desc = "Marks the slots of clicks still waiting.")
    private val timeout by NumberSetting("Timeout", 2500, 500..10000, 100, unit = "ms", desc = "Gives up on a menu that hasn't opened by then.")

    private const val PING = 50L
    const val FAKE_ID = 1_000_050

    private enum class Kind(val commands: Set<String>, val button: String?, val match: Regex) {
        WARDROBE(setOf("wardrobe", "armor"), null, Regex("""\(\d+/\d+\) Armor Sets""")),
        EQUIPMENT(setOf("equipment"), null, Regex("""\(\d+/\d+\) Equipment Sets""")),
        LOADOUTS(setOf("loadouts", "loadout"), "Loadouts", Regex("""\(\d+/\d+\) Loadouts""")),
        PETS(setOf("pets"), "Pets", Regex("""(\(\d+/\d+\) )?Pets""")),
        STATS(setOf("stats"), "Stats & Equipment", Regex(""".*(Stats|Equipment).*""")),
    }

    private val NAV = setOf("Next Page", "Previous Page", "Go Back")
    private val PAGED = Regex("""^\((\d+)/(\d+)\) (.*)$""")

    private class Snapshot(val title: Component, val type: MenuType<ChestMenu>, val rows: Int, val items: List<ItemStack>)
    private val cache = object : LinkedHashMap<String, Snapshot>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Snapshot>?) = size > 48
    }
    private val firstTitle = HashMap<Kind, String>()

    private class Click(val slot: Int, val button: Int, val input: ContainerInput, val name: String?, val nav: Boolean, val page: String)

    private class Pending(val kind: Kind, val coveredId: Int) {
        val clicks = ArrayDeque<Click>()
        val sentAt = System.currentTimeMillis()
        var fake: FakeScreen? = null
        var closed = false
        var frozen = false
        var awaitingOpen = true
        var firstOpen = true
        var realId = -1
        var since = sentAt
    }

    private var pending: Pending? = null
    private var replaying = false
    private var openerKind: Kind? = null
    private var openerId = -1
    private var curId = -1
    private var curTitle: Component? = null

    private fun on(kind: Kind) = enabled && when (kind) {
        Kind.WARDROBE, Kind.EQUIPMENT -> wardrobeOn
        Kind.LOADOUTS -> loadoutsOn
        Kind.PETS -> petsOn
        Kind.STATS -> statsOn
    }

    init {
        on<RenderExtractEvent> { guard("show") { tryShow() } }
    }

    @JvmStatic
    fun onCommand(command: String) = guard("command") {
        val c = command.trim()
        if (c.contains(' ')) return@guard
        val kind = Kind.entries.firstOrNull { c.lowercase() in it.commands } ?: return@guard
        begin(kind, -1)
    }

    @JvmStatic
    fun onClick(containerId: Int, slot: Int, button: Int, input: ContainerInput): Boolean {
        if (replaying) return false
        if (containerId == FAKE_ID) {
            guard("queue") { queue(slot, button, input) }
            return true
        }
        guard("opener") {
            openerKind = null
            if (!enabled || !menuOn || pending != null) return@guard
            val screen = mc.gui.screen() as? AbstractContainerScreen<*> ?: return@guard
            if (screen.title.string != "SkyBlock Menu" || input != ContainerInput.PICKUP) return@guard
            val name = screen.menu.slots.getOrNull(slot)?.item?.hoverName?.string ?: return@guard
            openerKind = Kind.entries.firstOrNull { it.button == name }
            openerId = containerId
        }
        return false
    }

    @JvmStatic
    fun afterClick() {
        val kind = openerKind ?: return
        openerKind = null
        guard("opener") { begin(kind, openerId) }
    }

    private fun begin(kind: Kind, coveredId: Int) {
        if (!on(kind) || pending != null) return
        pending = Pending(kind, coveredId)
    }

    private fun tryShow() {
        val p = pending ?: return
        if (p.fake != null || p.closed || !p.awaitingOpen || !p.firstOpen) return
        if (System.currentTimeMillis() - p.sentAt < PING) return
        val title = firstTitle[p.kind] ?: return
        val snap = cache[title] ?: return
        val screen = mc.gui.screen()
        val free = if (p.coveredId < 0) screen == null || screen is ChatScreen
            else screen is AbstractContainerScreen<*> && screen.menu.containerId == p.coveredId
        if (free) show(p, title, snap)
    }

    private fun show(p: Pending, title: String, snap: Snapshot) {
        val player = mc.player ?: return
        val menu = ChestMenu(snap.type, FAKE_ID, player.inventory, SimpleContainer(snap.rows * 9), snap.rows)
        for (i in 0 until snap.rows * 9) menu.slots[i].set(snap.items[i].copy())
        val screen = FakeScreen(menu, player.inventory, snap.title, title)
        p.fake = screen
        player.containerMenu = menu
        mc.gui.setScreen(screen)
    }

    private class FakeScreen(menu: ChestMenu, inventory: Inventory, title: Component, val key: String) : ContainerScreen(menu, inventory, title) {
        val rows = menu.rowCount

        override fun onClose() = guard("close") { closedByPlayer(this) }

        override fun extractSlot(g: GuiGraphicsExtractor, slot: Slot, mouseX: Int, mouseY: Int) {
            super.extractSlot(g, slot, mouseX, mouseY)
            val p = pending ?: return
            if (highlight && p.fake === this && p.clicks.any { it.slot == slot.index && it.page == key })
                g.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, 0x8055FF55.toInt())
        }
    }

    private fun queue(slot: Int, button: Int, input: ContainerInput) {
        val p = pending ?: return
        val fake = p.fake ?: return
        if (p.frozen) return
        val name = if (slot in 0 until fake.rows * 9) fake.menu.slots[slot].item.hoverName.string else null
        if (name == "Close" && input == ContainerInput.PICKUP) { closedByPlayer(fake); return }
        val nav = name in NAV
        p.clicks.addLast(Click(slot, button, input, name, nav, fake.key))
        if (!nav) return
        val target = turn(fake.key, name!!)
        val snap = target?.let(cache::get)
        if (snap != null) show(p, target, snap) else p.frozen = true
    }

    private fun turn(title: String, arrow: String): String? {
        val m = PAGED.matchEntire(title) ?: return null
        val page = m.groupValues[1].toInt()
        val pages = m.groupValues[2].toInt()
        val to = when (arrow) { "Next Page" -> page + 1; "Previous Page" -> page - 1; else -> return null }
        if (to !in 1..pages) return null
        return "($to/$pages) ${m.groupValues[3]}"
    }

    private fun closedByPlayer(screen: FakeScreen) {
        val p = pending
        val player = mc.player
        if (player != null && player.containerMenu === screen.menu) player.containerMenu = player.inventoryMenu
        if (mc.gui.screen() === screen) mc.gui.setScreen(null)
        if (p == null || p.fake !== screen) return
        p.fake = null
        p.closed = true
    }

    @JvmStatic
    fun onOpen(packet: ClientboundOpenScreenPacket): Boolean {
        var cancel = false
        guard("open") {
            snapshotCurrent()
            curId = packet.containerId
            curTitle = packet.title
            val p = pending ?: return@guard
            val title = packet.title.string
            if (!p.awaitingOpen || (p.firstOpen && !p.kind.match.matches(title))) { abort(p); return@guard }
            if (p.firstOpen) firstTitle[p.kind] = title
            p.firstOpen = false
            p.awaitingOpen = false
            p.realId = packet.containerId
            p.since = System.currentTimeMillis()
            if (p.fake == null && !p.closed) {
                if (p.clicks.isEmpty()) pending = null
                return@guard
            }
            if (p.closed) {
                val player = mc.player ?: return@guard
                // Opened without a screen: the clicks still go, then it's closed unseen.
                player.containerMenu = packet.type.create(packet.containerId, player.inventory)
                cancel = true
            } else {
                p.fake = null
            }
        }
        return cancel
    }

    @JvmStatic
    fun onContent(packet: ClientboundContainerSetContentPacket) = guard("content") {
        if (packet.containerId() == curId) snapshotCurrent()
        val p = pending ?: return@guard
        if (p.awaitingOpen || packet.containerId() != p.realId) return@guard
        if (mc.player?.containerMenu?.containerId != p.realId) return@guard
        replay(p)
    }

    @JvmStatic
    fun onServerClose(): Boolean {
        var cancel = false
        guard("server close") {
            snapshotCurrent()
            val p = pending ?: return@guard
            if (p.fake != null && mc.gui.screen() === p.fake) { cancel = true; return@guard }
            if (p.realId >= 0) pending = null
        }
        return cancel
    }

    @JvmStatic
    fun onLocalClose() = guard("local close") { snapshotCurrent() }

    private fun replay(p: Pending) {
        val player = mc.player ?: return
        val gm = mc.gameMode ?: return
        val menu = player.containerMenu
        while (p.clicks.isNotEmpty()) {
            val c = p.clicks.removeFirst()
            if (c.name != null) {
                val now = menu.slots.getOrNull(c.slot)?.item?.hoverName?.string
                if (now != c.name) {
                    modMessage("§cSlot ${c.slot} changed (${c.name} → ${now ?: "nothing"}), click dropped.")
                    if (c.nav) p.clicks.clear()
                    continue
                }
            }
            replaying = true
            try { gm.handleContainerInput(p.realId, c.slot, c.button, c.input, player) } finally { replaying = false }
            if (c.nav && p.clicks.isNotEmpty()) {
                p.awaitingOpen = true
                p.since = System.currentTimeMillis()
                return
            }
        }
        if (p.closed) closeHeadless(p.realId)
        pending = null
    }

    private fun closeHeadless(id: Int) {
        val player = mc.player ?: return
        if (id < 0) return
        mc.connection?.send(ServerboundContainerClosePacket(id))
        if (player.containerMenu.containerId == id) {
            snapshotCurrent()
            player.containerMenu = player.inventoryMenu
        }
        if (curId == id) curId = -1
    }

    private fun abort(p: Pending) {
        val player = mc.player
        val fake = p.fake
        if (fake != null) {
            if (player != null && player.containerMenu === fake.menu) player.containerMenu = player.inventoryMenu
            if (mc.gui.screen() === fake) mc.gui.setScreen(null)
        }
        if (pending === p) pending = null
    }

    private fun tick() {
        val p = pending ?: return
        if (System.currentTimeMillis() - p.since < timeout) return
        val fake = p.fake
        abort(p)
        if (p.realId < 0 && p.coveredId >= 0 && fake != null) mc.connection?.send(ServerboundContainerClosePacket(p.coveredId))
        if (p.closed && p.realId >= 0) closeHeadless(p.realId)
        if (fake != null || p.clicks.isNotEmpty()) modMessage("§c${p.kind.name.lowercase()} didn't open${if (p.clicks.isEmpty()) "." else ", ${p.clicks.size} click(s) dropped."}")
    }

    private fun snapshotCurrent() {
        val player = mc.player ?: return
        val menu = player.containerMenu as? ChestMenu ?: return
        val title = curTitle ?: return
        if (menu.containerId != curId || curId == FAKE_ID) return
        val key = title.string
        if (Kind.entries.none { it.match.matches(key) }) return
        @Suppress("UNCHECKED_CAST")
        val type = menu.type as MenuType<ChestMenu>
        val n = menu.rowCount * 9
        cache[key] = Snapshot(title, type, menu.rowCount, (0 until n).map { menu.slots[it].item.copy() })
    }

    private fun reset() {
        pending?.let(::abort)
        pending = null
        curId = -1
        curTitle = null
        openerKind = null
    }

    fun install() {
        ClientTickEvents.END_CLIENT_TICK.register { guard("tick") { tick() } }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> mc.execute { guard("reset") { reset(); cache.clear(); firstTitle.clear() } } }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> mc.execute { guard("reset") { reset() } } }
    }

    private inline fun guard(what: String, block: () -> Unit) = EngineerClient.safely("50 ping menus $what", block)
}
