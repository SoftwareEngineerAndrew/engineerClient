package com.engineerclient.p3sim.clear.state

import com.engineerclient.index
import com.engineerclient.p3sim.P3Sim
import com.engineerclient.p3sim.Party
import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.SimItems
import com.engineerclient.p3sim.Terminals
import com.engineerclient.p3sim.clear.bots.BotsModule
import com.engineerclient.p3sim.run.Run
import com.engineerclient.p3sim.run.RunStage
import com.engineerclient.p3sim.run.Runs
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.SimpleContainer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ResolvableProfile
import java.util.Base64

/**
 * Mort's "Ready Up" window, the one a click on him opens before the run starts (ADR, 92 recorded openings): a 9x6 of black panes with
 * your head at 4, the ready pane at 13 (red "Not Ready" / lime "Ready"), the five classes at 29-33 (Healer splash potion, Mage blaze rod,
 * Berserk iron sword, Archer bow, Tank leather chestplate, "[Lvl N] Class", the one you play glinting), under each a dye with how many
 * of the party play it (38-42: "0 Players" grey, "N Player(s)" lime, glinting under yours), the Dungeon Orb at 45, Close at 49 and
 * Toggle Auto Ready Up at 53.
 *
 * Picking a class makes it yours at once ("<name> selected the <Class> Class!" for the party, as recorded) and the bots are planned
 * again, the one that had that class taking another. Mort stays clickable for the whole dungeon (COUNTDOWN, CLEAR, BLOOD): after the
 * start a pick takes the run back to its "Start" savepoint with the new class, since the bots' whole plan hangs on who plays what.
 * Server thread.
 */
object ReadyUpMenu {
    /** The five as the window lists them, with their icons. */
    private val CLASSES = listOf("Healer", "Mage", "Berserk", "Archer", "Tank")

    /** "[Lvl N]" per class: the recording owner's levels (ADR, the latest Ready Up window of SoloF7). UNMEASURED for anyone else. */
    private val LEVELS = mapOf("Healer" to 50, "Mage" to 48, "Berserk" to 49, "Archer" to 49, "Tank" to 47)

    /** The Dungeon Orb head's texture (as recorded). */
    private const val ORB_TEX = "ba49f9de12c813329f38d0af2da0f32f32a6dcd52c3221d19090c256376c418c"

    /** Whether you have readied in this run (the ready pane); the run's own scripted countdown goes on either way. */
    private val readied = HashSet<java.util.UUID>()

    fun reset() { readied.clear() }

    /** Mort was clicked by [p]: the window, while the run is in the dungeon. */
    fun open(p: ServerPlayer) {
        val run = Runs.current ?: return
        if (!run.stage.inDungeon) return
        p.openMenu(SimpleMenuProvider({ id, inv, _ -> Window(id, inv, p) }, Component.literal("Ready Up")))
    }

    private fun icon(cls: String) = when (cls) {
        "Healer" -> Items.SPLASH_POTION; "Mage" -> Items.BLAZE_ROD; "Berserk" -> Items.IRON_SWORD; "Archer" -> Items.BOW; else -> Items.LEATHER_CHESTPLATE
    }

    private fun texHead(hash: String, name: String): ItemStack {
        val json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/$hash\"}}}"
        return SimItems.head(Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8)), name)
    }

    private fun colourOf(run: Run, p: ServerPlayer): String = (run.state.players[p.uuid]?.extras?.get("nameColour") as? String) ?: "§b"

    /** [p] takes [cls] ("Mage"): the setting the boss uses, the run's record, the line, and the bots planned again. */
    private fun choose(p: ServerPlayer, cls: String) {
        val run = Runs.current ?: return
        if (!run.stage.inDungeon) return
        if (run.state.players[p.uuid]?.dungeonClass.equals(cls, ignoreCase = true)) return
        if (run.stage != RunStage.COUNTDOWN) {
            val start = run.savepoints.list().firstOrNull() ?: return
            p.closeContainer()
            if (!run.savepoints.restore(run, start.id)) return
        }
        val rec = run.state.players[p.uuid] ?: return
        rec.dungeonClass = cls.uppercase()
        if (Sim.isHost(p)) {
            P3Sim.classS.index = Party.CLASSES.indexOfFirst { it.name.equals(cls, ignoreCase = true) }.coerceAtLeast(0)
            com.engineerclient.EngineerClient.mc.execute { com.odtheking.odin.features.ModuleManager.saveConfigurations() }
        }
        Sim.chat(StateText.selectedClass(p.gameProfile.name, colourOf(run, p), cls))
        BotsModule.reclass(run)
    }

    private fun toggleReady(p: ServerPlayer) {
        val run = Runs.current ?: return
        val now = readied.add(p.uuid)
        if (!now) readied.remove(p.uuid)
        Sim.chat(if (now) StateText.ready(p.gameProfile.name, colourOf(run, p)) else StateText.notReady(p.gameProfile.name, colourOf(run, p)))
    }

    class Window(id: Int, inv: Inventory, private val sp: ServerPlayer) : ChestMenu(MenuType.GENERIC_9x6, id, inv, SimpleContainer(54), 6) {
        init { draw() }

        private fun draw() {
            val c = container
            val run = Runs.current
            for (i in 0 until 54) c.setItem(i, Terminals.FILLER)
            c.setItem(4, ItemStack(Items.PLAYER_HEAD).also {
                it.set(DataComponents.PROFILE, ResolvableProfile.createResolved(sp.gameProfile))
                it.set(DataComponents.CUSTOM_NAME, Component.literal("§b${sp.gameProfile.name}").withStyle { s -> s.withItalic(false) })
            })
            val ready = sp.uuid in readied
            c.setItem(13, if (ready) Terminals.named(Items.STAINED_GLASS_PANE.lime(), "§aReady") else Terminals.named(Items.STAINED_GLASS_PANE.red(), "§cNot Ready"))
            val mine = run?.state?.players?.get(sp.uuid)?.dungeonClass
            for ((i, cls) in CLASSES.withIndex()) {
                val yours = cls.equals(mine, ignoreCase = true)
                c.setItem(29 + i, Terminals.named(icon(cls), "§a[Lvl ${LEVELS[cls]}] $cls", glint = yours).also {
                    it.set(DataComponents.LORE, net.minecraft.world.item.component.ItemLore((listOf<net.minecraft.network.chat.MutableComponent>(Component.literal(if (yours) "§aCurrently selected!" else "§eClick to select!")) +
                        (if (!yours && run != null && run.stage != RunStage.COUNTDOWN) listOf(Component.literal("§7Restarts the run from the start.")) else emptyList())
                        ).map { l -> l.withStyle { s -> s.withItalic(false) } }))
                    it.set(DataComponents.ATTRIBUTE_MODIFIERS, net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY)
                })
                val n = run?.state?.players?.values?.count { it.dungeonClass.equals(cls, ignoreCase = true) } ?: 0
                c.setItem(38 + i, if (n == 0) Terminals.named(Items.DYE.gray(), "§70 Players") else Terminals.named(Items.DYE.lime(), "§a$n Player(s)", glint = yours))
            }
            c.setItem(45, texHead(ORB_TEX, "§aDungeon Orb"))
            c.setItem(49, Terminals.named(Items.BARRIER, "§cClose"))
            c.setItem(53, Terminals.named(Items.STAINED_GLASS.lime(), "§aToggle Auto Ready Up"))
        }

        override fun clicked(slot: Int, button: Int, input: ContainerInput, p: Player) {
            when (slot) {
                49 -> { sp.closeContainer(); return }
                13 -> toggleReady(sp)
                in 29..33 -> choose(sp, CLASSES[slot - 29])
                else -> return
            }
            Sim.sound(SoundEvents.UI_BUTTON_CLICK.value(), 1f, 1f)
            draw()
            broadcastChanges()
        }

        override fun stillValid(p: Player) = true
        override fun quickMoveStack(p: Player, slot: Int): ItemStack = ItemStack.EMPTY
    }
}
