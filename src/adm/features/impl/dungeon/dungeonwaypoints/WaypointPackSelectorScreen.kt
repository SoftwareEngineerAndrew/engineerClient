package com.adm.features.impl.dungeon.dungeonwaypoints

import androidx.compose.runtime.*
import com.adm.AdmMod
import com.adm.AdmMod.mc
import com.adm.clickgui.AdmScreen
import com.adm.clickgui.GuiTheme
import com.adm.clickgui.ui.Button
import com.adm.clickgui.ui.SWITCH_HEIGHT
import com.adm.clickgui.ui.Switch
import com.adm.config.DungeonWaypointConfig
import com.adm.config.WaypointPackFileUtils
import com.adm.utils.modMessage
import com.adm.utils.render.roundedRect
import com.adm.utils.render.roundedRectOutlined
import com.adm.utils.ui.compose.*
import kotlinx.coroutines.launch
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

private const val WIDTH = 340
private const val ROW_W = WIDTH - PANEL_PAD * 2
private const val ROW_H = 24
private const val ROW_GAP = 4
private const val TOOLBAR_Y = 24
private const val LIST_TOP = 52

class WaypointPackSelectorScreen(private val parent: Screen?) : AdmScreen(Component.literal("Waypoint Pack Manager")) {

    private class Ask(val title: String, val then: (String) -> Unit)

    private var epoch by mutableIntStateOf(0)
    private var names by mutableStateOf(WaypointPackFileUtils.packNames())
    private var busy by mutableStateOf(false)
    private var asking by mutableStateOf<Ask?>(null)

    private val countLabels = HashMap<String, String>()
    private var countsAt = -1

    override val ui = UiHost {
        Manager()
        asking?.let { ask ->
            key(ask) {
                NamePrompt(ask.title, { text -> asking = null; ask.then(text) }, { asking = null })
            }
        }
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        ui.render(graphics, width, height, mouseX, mouseY)
    }

    override fun removed() {
        super.removed()
        ui.dispose()
    }

    private fun listViewport() = (mc.window.guiScaledHeight - LIST_TOP - BUTTON_H - PANEL_PAD * 2 - 20).coerceAtLeast(48).coerceAtMost(224)

    private fun countLabel(pack: String): String {
        val at = WaypointStore.revision
        if (at != countsAt) {
            countsAt = at
            countLabels.clear()
        }
        return countLabels.getOrPut(pack) {
            val n = WaypointStore.waypointCount(pack)
            if (n >= 0) "§a$n §7wp" else "§7? wp"
        }
    }

    @Composable
    private fun Manager() {
        val doneY = { LIST_TOP + listViewport() + PANEL_PAD }
        ModalPanel(WIDTH, { doneY() + BUTTON_H + PANEL_PAD }) {
            Text({ "§lWaypoint Pack Manager" }).centerIn(WIDTH, PANEL_PAD)
            Button("Create Pack", 100, BUTTON_H) {
                asking = Ask("Create Pack") { name -> if (name.isNotBlank()) busyWhile { WaypointStore.createPack(name) } }
            }.at(76, TOOLBAR_Y)
            Button("Import", 80, BUTTON_H) {
                asking = Ask("Import as New Pack") { name ->
                    val clipboard = mc.keyboardHandler.clipboard
                    busyWhile {
                        val decoded = DungeonWaypointConfig.decodeWaypoints(null, clipboard)
                        if (decoded != null && WaypointStore.importPack(name, decoded)) modMessage("§aImported waypoints as pack '$name'!")
                        else modMessage("§cFailed to decode waypoints from clipboard.")
                    }
                }
            }.at(184, TOOLBAR_Y)
            if (busy) Text({ "§7Loading..." }).centerIn(WIDTH, LIST_TOP + listViewport() / 2 - 4) else Packs()
            Button("Done", 80, BUTTON_H) { if (parent != null) mc.setScreenAndShow(parent) else mc.gui.setScreen(null) }
                .offset({ (WIDTH - 80) / 2 }, doneY)
        }
    }

    @Composable
    private fun Packs() {
        val chosen = remember(epoch) { DungeonWaypoints.selectedPackIds.toList() }
        val editing = remember(epoch) { DungeonWaypoints.editPackId }
        val scroll = remember { ScrollState() }

        Box {
            Column(ROW_GAP) {
                for (name in names) key(name) { PackRow(name, name in chosen, name == editing, names.size > 1) }
            }.offset(y = { scroll.offset })
        }
            .width(ROW_W)
            .height {
                val viewport = listViewport()
                scroll.maxScroll = height - viewport
                viewport
            }
            .clip()
            .at(PANEL_PAD, LIST_TOP)
            .onScroll { amount -> scroll.by(amount); true }
    }

    @Composable
    private fun PackRow(name: String, on: Boolean, editing: Boolean, deletable: Boolean) {
        val hover = remember { InteractionSource() }

        Box {
            Switch(on) { toggle(name) }.at(GuiTheme.PADDING, (ROW_H - SWITCH_HEIGHT) / 2)
            Box {
                Text({ (if (editing) "§e★ " else if (hover.hovered) "§f" else "§7") + name }).at(0, (ROW_H - 8) / 2)
            }.size(150, ROW_H).clip().hoverable(hover).clickable(onClick = { makeEditPack(name) }).at(36, 0)
            Text({ countLabel(name) }).at(190, (ROW_H - 8) / 2)
            Button("§7✎", 26, 18) {
                asking = Ask("Rename Pack") { to -> if (to.isNotBlank() && to != name) busyWhile { WaypointStore.renamePack(name, to) } }
            }.at(260, (ROW_H - 18) / 2)
            Button(if (deletable) "§cX" else "§8X", 26, 18) {
                if (deletable) busyWhile { WaypointStore.deletePack(name) }
            }.at(292, (ROW_H - 18) / 2)
        }.size(ROW_W, ROW_H).drawBehind { g ->
            if (editing) g.roundedRectOutlined(x, y, right, bottom, GuiTheme.surface.rgba, GuiTheme.accent.rgba, 1f, GuiTheme.RADIUS)
            else g.roundedRect(x, y, right, bottom, GuiTheme.surface.rgba, GuiTheme.RADIUS)
        }
    }

    private fun toggle(name: String) {
        val chosen = DungeonWaypoints.selectedPackIds
        if (name in chosen) {
            if (chosen.size == 1) return
            if (name == DungeonWaypoints.editPackId) DungeonWaypoints.editPackId = chosen.first { it != name }
            chosen.remove(name)
        } else chosen.add(name)
        commitSelection()
    }

    private fun makeEditPack(name: String) {
        if (name !in DungeonWaypoints.selectedPackIds) DungeonWaypoints.selectedPackIds.add(name)
        DungeonWaypoints.editPackId = name
        commitSelection()
    }

    private fun commitSelection() {
        epoch++
        val chosen = DungeonWaypoints.selectedPackIds.toList()
        val edit = DungeonWaypoints.editPackId
        AdmMod.scope.launch { WaypointStore.selectPacks(chosen, edit) }
    }

    private fun busyWhile(work: suspend () -> Unit) {
        busy = true
        AdmMod.scope.launch {
            try {
                work()
            } finally {
                names = WaypointPackFileUtils.packNames()
                epoch++
                busy = false
            }
        }
    }
}
