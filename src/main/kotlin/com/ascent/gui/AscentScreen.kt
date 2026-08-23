package com.ascent.gui

import com.ascent.AscentConfig
import com.ascent.AscentMod
import com.ascent.ClassDetect
import com.ascent.RushProfiles
import com.odtheking.odin.config.WaypointPackFileUtils
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.layouts.FrameLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

/**
 * Profile GUI: class (auto-detected, overridable), players-on-rush (2..5),
 * dedicated door, and which custom Odin waypoint packs ride along with the
 * active profile pack. Waypoints themselves are edited with Odin's editor —
 * the active profile pack is always Odin's edit pack.
 */
class AscentScreen : Screen(Component.literal("Ascent")) {

    private lateinit var layout: LinearLayout

    override fun init() {
        super.init()
        val d = AscentConfig.data

        layout = LinearLayout.vertical().spacing(5)
        layout.defaultCellSetting().alignHorizontallyCenter()

        layout.addChild(StringWidget(Component.literal("§6§lAscent §7— Blood Rush Waypoint Profiles"), font))

        val live = ClassDetect.detected?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "none"
        layout.addChild(
            StringWidget(
                Component.literal("§7live tab class: §f$live§7 · stash: §f${d.lastKnownClass ?: "none"}§7 · pack: §a${RushProfiles.activePackName() ?: "none"}"),
                font
            )
        )

        // Class row: Auto + the five classes; selection shown green.
        val classRow = layout.addChild(LinearLayout.horizontal().spacing(4))
        classRow.addChild(pick("Auto", d.classOverride == null, 44) {
            d.classOverride = null
        })
        for (clazz in RushProfiles.CLASSES) {
            classRow.addChild(pick(clazz, d.classOverride == clazz, 62) {
                d.classOverride = clazz
            })
        }

        // Players-on-rush row.
        val playersRow = layout.addChild(LinearLayout.horizontal().spacing(4))
        playersRow.addChild(StringWidget(Component.literal("§7players on rush:"), font))
        for (n in 2..5) {
            playersRow.addChild(pick("${n}p", d.playersOnRush == n, 36) {
                d.playersOnRush = n
            })
        }

        // Toggles row.
        val toggleRow = layout.addChild(LinearLayout.horizontal().spacing(4))
        toggleRow.addChild(pick("Dedicated Door: ${if (d.dedicatedDoor) "§aON" else "§cOFF"}", false, 130) {
            d.dedicatedDoor = !d.dedicatedDoor
        })
        toggleRow.addChild(pick("Auto Profiles: ${if (d.enabled) "§aON" else "§cOFF"}", false, 120) {
            d.enabled = !d.enabled
        })

        // Custom pack toggles: every non-profile Odin pack, kept selected alongside the profile.
        val customPacks = listCustomPacks()
        if (customPacks.isNotEmpty()) {
            layout.addChild(StringWidget(Component.literal("§7custom waypoint sets (ride along with the profile):"), font))
            customPacks.chunked(4).forEach { rowPacks ->
                val row = layout.addChild(LinearLayout.horizontal().spacing(4))
                rowPacks.forEach { pack ->
                    row.addChild(pick(pack, pack in d.customPacks, 90) {
                        if (!d.customPacks.remove(pack)) d.customPacks.add(pack)
                    })
                }
            }
        }

        layout.addChild(Button.builder(CommonComponents.GUI_DONE) { onClose() }.width(100).build())

        layout.visitWidgets(this::addRenderableWidget)
        repositionElements()
    }

    /** A selectable button: green when active; clicking mutates config, applies, and rebuilds the screen. */
    private fun pick(label: String, selected: Boolean, w: Int, mutate: () -> Unit): Button =
        Button.builder(Component.literal(if (selected) "§a§l$label" else label)) {
            mutate()
            AscentConfig.save()
            RushProfiles.applySelection("gui")
            AscentMod.mc.setScreen(AscentScreen())
        }.width(w).build()

    private fun listCustomPacks(): List<String> = try {
        WaypointPackFileUtils.packsFolder.listFiles { f -> f.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.filterNot { RushProfiles.isProfilePack(it) }
            ?.sorted() ?: emptyList()
    } catch (t: Throwable) {
        AscentMod.logger.warn("[ascent] failed to list packs for GUI", t)
        emptyList()
    }

    override fun repositionElements() {
        layout.arrangeElements()
        FrameLayout.centerInRectangle(layout, rectangle)
    }

    override fun isPauseScreen(): Boolean = false
}
