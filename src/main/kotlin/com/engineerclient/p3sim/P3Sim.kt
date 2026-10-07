package com.engineerclient.p3sim

import com.engineerclient.index
import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.KeybindSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.skyblock.Island
import com.odtheking.odin.utils.skyblock.LocationUtils
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonListener
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer
import com.odtheking.odin.utils.skyblock.dungeon.Floor
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/**
 * P3 Sim: F7's boss fight in a singleplayer world of its own ("p3sim"), to practice it alone.
 *
 * The world is the real arena at Hypixel's coordinates; the fight runs on the world's own
 * (integrated) server ([SimServer], [Fight]): Goldor, terminals, levers, devices, gates, death
 * ticks, and Maxor/Storm/Necron around it. The menu ([SimScreen]: the keybind, `/p3sim`, or the
 * SkyBlock Menu star in the hotbar) starts any phase or section and teleports anywhere.
 *
 * Nothing of it exists anywhere else: every piece checks [inSim] (the client) or
 * [SimServer.isSim] (the server), both of which are only true in that one singleplayer world.
 */
object P3Sim : Module(
    name = "P3 Sim",
    category = Category.custom("Engineer Client", 860, 10),
    description = "F7's boss in a singleplayer world of its own: /p3sim (or the title screen button) opens it. Only ever active in that world.",
    key = null,
) {
    val menuKey by KeybindSetting("Menu Keybind", GLFW.GLFW_KEY_UNKNOWN, "Opens the P3 Sim menu in the sim world (so do /p3sim and the SkyBlock Menu star in your hotbar). Outside it, opens the sim.").onPress { openMenuOrSim() }
    val restartKey by KeybindSetting("Restart Keybind", GLFW.GLFW_KEY_UNKNOWN, "In the sim: starts whatever you last started again (P3, S2, P2...), from scratch.").onPress {
        if (inSim) SimServer.run("restart") { Fight.start(Fight.lastStart) }
    }
    enum class ClassOption { HEALER, BERSERK, ARCHER, TANK, MAGE }
    enum class DeathTickOption { OFF, WARN, MASKS }
    enum class TerminalOption { RANDOM, ORDER, PANES, RUBIX, STARTS_WITH, SELECT, MELODY }
    enum class MaskOption { SPIRIT, BONZO }

    // Kept as objects (not delegates) so the sim's own menu can change them.
    val classS = +SelectorSetting("Your Class", ClassOption.BERSERK, desc = "Your dungeon class (Odin's party list and leap menu). The four bots are the other classes. What you do in P3 is the menu's Plan tab.")
    val speedS = +NumberSetting("Speed", 450, 100..750, 10, desc = "Your Skyblock speed without Black Cat. Black Cat adds 100 (and 100 to the speed cap), so 450 is 550 with it out, as in recorded P3 starts (1.55 blocks a tick sprinting); Phoenix out has no Black Cat bonus.")
    val botsS = +BooleanSetting("Party Bots", true, desc = "Four bots do the rest of the party's terminals, levers, devices and gates at the pace of fast Better PF runs. Off: you do everything.")
    val deathTicksS = +SelectorSetting("Death Ticks", DeathTickOption.MASKS, desc = "Goldor's death tick (every 60 ticks, hits anyone in a section ahead): Warn only says so; Masks uses your Spirit Mask, Bonzo's Mask and Phoenix as Hypixel does, and with none left you die (back to the section's start).")
    val terminalS = +SelectorSetting("Terminals", TerminalOption.RANDOM, desc = "Every terminal as this type, or random as on Hypixel.")
    val pingS = +NumberSetting("Simulated Ping", 0, 0..300, 10, unit = "ms", desc = "Delays the server's answer to your clicks and items by this much, like playing on Hypixel with that ping.")
    val jitterS = +BooleanSetting("Ping Jitter", true, desc = "Simulated Ping varies like a real connection: usually a few ms either way, now and then 30+ ms more, rarely a lag spike (spread recorded on Hypixel). Off: a fixed delay.")
    val goldorKillS = +NumberSetting("Goldor Kill Time", 43, 10..120, 1, unit = " ticks", desc = "How long after Goldor starts flying to the core his \"....\" line comes, i.e. he dies (median of 31 recorded runs: 43, range 17-74).")
    val shortbowCooldownS = +NumberSetting("Shortbow Cooldown", 5, 1..20, 1, unit = " ticks", desc = "Ticks between shots of the Terminator, Spirit Shortbow and Mosquito Shortbow: 5 at 100% attack speed, on every bow and whatever Terror armor you wear (recordings: Terminator 5, Mosquito 5). A click inside it fires when it ends. Nasty Bite has its own 10.")
    val hydraStartS = +NumberSetting("Hydra Stacks At Start", 9, 0..10, 1, desc = "Hydra Strike stacks every start from the menu (P1, P3, a section...) begins with. Going on from one phase to the next keeps what you have.")
    val clickLimitS = +BooleanSetting("Terminal Click Limit", true, desc = "As on Hypixel: a terminal takes at most 5 clicks in any 10 ticks; the rest are dropped without an answer (measured from 82 recorded windows).")
    val noMelodiesS = +BooleanSetting("No Melodies", false, desc = "Random terminals are never melodies.")
    val recordS = +BooleanSetting("Record Runs", true, desc = "Writes each run, tick by tick (you, the bots, what's left, chat), to config/engineerclient/p3sim-runs (last 20 kept), to look at what went wrong.")
    val debugBotsS = +BooleanSetting("Debug Bots", false, desc = "Chat lines for everything the P3 bots do: where they head and why, jobs, leaps, early enters (on the spot, who they wait for, why they move on).")
    val breakerRefillS = +NumberSetting("Dungeonbreaker Refill", 6, 1..10, 1, unit = "/s", desc = "Charges back each second (20 max), in irregular +2 steps. Main server: ~6 a second; alpha ~2.")
    val breakerRegenS = +NumberSetting("Dungeonbreaker Regen", 11.05, 1.0..30.0, 0.5, unit = "s", desc = "How long a broken block stays broken (recordings: ~11 s; the 21st break brings back the oldest 41 ticks later).")
    val realMasksS = +BooleanSetting("Real Masks", true, desc = "Masks are real helmets: only the one you wear can save you, swap them in /stats (cooldowns stay with each mask). Off: whichever is ready saves you.")
    val wornMaskS = +SelectorSetting("Starting Mask", MaskOption.SPIRIT, desc = "Real Masks: the mask you wear (/stats swaps it).")
    val phoenixS = +BooleanSetting("Phoenix Pet", true, desc = "Your pet: Phoenix (saves you from a death, no Black Cat speed bonus) or Black Cat (+100 speed). The Pet Rod swaps them.")
    val lavaS = +BooleanSetting("Lava Bounce", true, desc = "Lava bounces you up as on Hypixel. Off: plain vanilla lava (no damage).")
    val p3OnlyS = +BooleanSetting("Stop After P3", true, desc = "End at Goldor's death instead of going on to Necron.")
    val autoStartS = +BooleanSetting("Start On Join", false, desc = "Start P3 as soon as you join the sim world.")
    val showTimesS = +BooleanSetting("Section Times", true, desc = "Each section's time in chat as it ends, and a summary at the core.")

    val autoStart: Boolean get() = autoStartS.value
    val showTimes: Boolean get() = showTimesS.value
    val myClass: DungeonClass get() = Party.CLASSES[classS.index.coerceIn(0, 4)]
    val speed: Int get() = speedS.value.toInt()
    val bots: Boolean get() = botsS.value
    val deathTicks: Int get() = deathTicksS.index
    val ping: Int get() = pingS.value.toInt()
    val jitter: Boolean get() = jitterS.value
    val goldorKill: Int get() = goldorKillS.value.toInt()
    val p3Only: Boolean get() = p3OnlyS.value
    val shortbowCooldown: Int get() = shortbowCooldownS.value.toInt()
    /** Terror armor pieces worn right now (TERROR_* ids in the four armour slots, helmet included): 0 to 4. Hydra Strike follows it. */
    val terrorPieces: Int get() = Sim.player?.let { p ->
        listOf(net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
            net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET)
            .count { SimItems.idOf(p.getItemBySlot(it))?.startsWith("TERROR_") == true }
    } ?: 0
    val hydraStart: Int get() = hydraStartS.value.toInt()
    val lava: Boolean get() = lavaS.value
    val noMelodies: Boolean get() = noMelodiesS.value
    val clickLimit: Boolean get() = clickLimitS.value
    val debugBots: Boolean get() = debugBotsS.value
    val record: Boolean get() = recordS.value

    /** Hide Players is Odin's own (its module and its Hide All / Distance settings); in the sim its rule hides the bots too. */
    val hidePlayers: Boolean get() = com.odtheking.odin.features.impl.render.HidePlayers.enabled
    fun toggleHidePlayers() = com.odtheking.odin.features.impl.render.HidePlayers.toggle()

    /** A bot (a mannequin in the sim) Odin's Hide Players would hide if it were a player. Client thread. */
    @JvmStatic
    fun hideBot(e: net.minecraft.world.entity.Entity): Boolean {
        if (!hidePlayers || !inSim || e !is net.minecraft.world.entity.decoration.Mannequin) return false
        val hp = com.odtheking.odin.features.impl.render.HidePlayers
        if ((hp.settings["Hide all"] as? BooleanSetting)?.value == true) return true
        val d = (hp.settings["Distance"] as? NumberSetting<*>)?.value?.toDouble() ?: 3.0
        val me = mc.player ?: return false
        return e.distanceToSqr(me) <= d * d
    }
    val breakerRefill: Int get() = breakerRefillS.value.toInt()
    val breakerRegen: Double get() = breakerRegenS.value.toDouble()
    val realMasks: Boolean get() = realMasksS.value
    val phoenix: Boolean get() = phoenixS.value
    val forcedTerminal: Terminals.Type? get() = terminalS.index.let { if (it == 0) null else Terminals.Type.entries[it - 1] }

    /** True only in the p3sim singleplayer world (client side). */
    @JvmStatic
    val inSim: Boolean
        get() {
            val s = SimServer.server ?: return false
            return mc.hasSingleplayerServer() && mc.singleplayerServer === s && mc.level != null
        }

    fun init() {
        SimServer.register()
        P3Plan.load()
        // /p3sim: the menu in the sim (or opens the sim); /p3sim <start> starts it; /p3sim rebuild remakes the world.
        // /stats: Hypixel's equipment window, here to swap masks. On the sim's own server only (a
        // server command, so Hypixel's /stats is never touched).
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(net.minecraft.commands.Commands.literal("stats")
                .requires { it.server === SimServer.server }
                .executes { ctx -> ctx.source.player?.let { p -> Masks.openStats(p) }; 1 })
            // /loadouts: Hypixel's Loadouts window (recorded: Andrew types /loadouts), same sim-server-only rule.
            dispatcher.register(net.minecraft.commands.Commands.literal("loadouts")
                .requires { it.server === SimServer.server }
                .executes { ctx -> ctx.source.player?.let { p -> Loadouts.open(p) }; 1 })
        }
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            val cmd = ClientCommands.literal("p3sim").executes { openMenuOrSim(); 1 }
            for (s in Fight.Start.entries) cmd.then(ClientCommands.literal(s.name.lowercase()).executes {
                if (inSim) SimServer.run("cmd start") { Fight.start(s) } else SimWorld.open(); 1
            })
            cmd.then(ClientCommands.literal("stop").executes { SimServer.run("cmd stop") { Fight.end() }; 1 })
            cmd.then(ClientCommands.literal("rebuild").executes { SimWorld.rebuild(); 1 })
            dispatcher.register(cmd)
        }
        ClientTickEvents.START_CLIENT_TICK.register { EngineerClient.safely("p3sim bridge") { bridge(); SimItems.clientTick() } }
        ScreenEvents.AFTER_INIT.register { _, screen, w, _ ->
            // The title screen's Minecraft Realms button becomes two: P3 Sim on the left half, Join
            // Hypixel on the right. No Realms button (another mod's menu): a small P3 Sim button in the corner.
            if (screen is TitleScreen) EngineerClient.safely("p3sim title button") {
                val widgets = Screens.getWidgets(screen)
                val realms = widgets.filterIsInstance<Button>()
                    .firstOrNull { (it.message.contents as? net.minecraft.network.chat.contents.TranslatableContents)?.key == "menu.online" }
                if (realms == null) {
                    widgets.add(Button.builder(Component.literal("P3 Sim")) { SimWorld.open() }.bounds(w - 64, 4, 60, 16).build())
                    return@safely
                }
                val half = (realms.width - 4) / 2
                widgets.remove(realms)
                widgets.add(Button.builder(Component.literal("P3 Sim")) { SimWorld.open() }
                    .bounds(realms.x, realms.y, half, realms.height).build())
                widgets.add(Button.builder(Component.literal("Join Hypixel")) { com.engineerclient.misc.RandomStuff.joinHypixel(screen) }
                    .bounds(realms.x + realms.width - half, realms.y, half, realms.height).build())
            }
            // In the sim, Esc has the menu too: right under Save and Quit (the bottom button if that isn't found).
            if (screen is net.minecraft.client.gui.screens.PauseScreen && inSim) EngineerClient.safely("p3sim pause button") {
                val widgets = Screens.getWidgets(screen)
                val buttons = widgets.filterIsInstance<Button>()
                val quit = buttons.firstOrNull { (it.message.contents as? net.minecraft.network.chat.contents.TranslatableContents)?.key in QUIT_KEYS }
                    ?: buttons.maxByOrNull { it.y }
                val b = if (quit != null) Button.builder(Component.literal("§6P3 Sim Menu")) { mc.gui.setScreen(SimScreen()) }.bounds(quit.x, quit.y + quit.height + 4, quit.width, 20)
                    else Button.builder(Component.literal("§6P3 Sim Menu")) { mc.gui.setScreen(SimScreen()) }.bounds(4, 4, 90, 20)
                widgets.add(b.build())
            }
        }
    }

    /** The Esc menu's Save and Quit button (Disconnect if it's shown that way). */
    private val QUIT_KEYS = setOf("menu.returnToMenu", "menu.disconnect")

    fun openMenuOrSim() {
        if (inSim) mc.execute { mc.gui.setScreen(SimScreen()) } else SimWorld.open()
    }

    // ------------------------------------------------------------------ Odin

    private var bridged = false

    /**
     * Tells Odin it is in F7's boss with a party of five, every tick while in the sim (Odin clears
     * it all on each world load). Odin's dungeon features (terminal solver, leap menu, Simon Says,
     * splits...) then work in the sim as they do on Hypixel.
     */
    private fun bridge() {
        if (!inSim) {
            if (bridged) { bridged = false; unbridge() }
            return
        }
        bridged = true
        val me = mc.player?.name?.string ?: return
        setArea(Island.Dungeon)
        DungeonListener.floor = Floor.F7
        DungeonListener.inBoss = true
        if (DungeonListener.dungeonTeammates.size != 5 || DungeonListener.dungeonTeammates.none { it.name == me } || teamClass != classS.index || roster != Party.bots().joinToString { it.name }) {
            teamClass = classS.index
            roster = Party.bots().joinToString { it.name }
            val mine = myClass
            val team = arrayListOf(DungeonPlayer(me, mine, 50, mc.player?.skin))
            Party.bots().forEach { team += DungeonPlayer(it.name, it.clazz, 50, null) }
            val others = team.filter { it.name != me }
            DungeonListener.dungeonTeammates = team
            DungeonListener.dungeonTeammatesNoSelf = others
            // Odin's leap menu quadrants in the plan's leap slot order (slot 1 = top left ... 4 = bottom right).
            DungeonListener.leapTeammates = others
        }
    }

    private var teamClass = -1
    private var roster = ""

    private fun unbridge() {
        teamClass = -1
        DungeonListener.floor = null
        DungeonListener.inBoss = false
        DungeonListener.dungeonTeammates = arrayListOf()
        DungeonListener.dungeonTeammatesNoSelf = emptyList()
        DungeonListener.leapTeammates = emptyList()
        setArea(Island.Unknown)
    }

    // Resolved once: if Odin renames them, the bridge stays off instead of failing every tick.
    private val areaField = runCatching { LocationUtils::class.java.getDeclaredField("currentArea").apply { isAccessible = true } }.getOrNull()
    private val skyblockField = runCatching { LocationUtils::class.java.getDeclaredField("isInSkyblock").apply { isAccessible = true } }.getOrNull()

    private fun setArea(area: Island) {
        val areaField = areaField ?: return
        val skyblockField = skyblockField ?: return
        if (LocationUtils.currentArea != area) areaField.set(null, area)
        if (skyblockField.getBoolean(null) != (area == Island.Dungeon)) skyblockField.setBoolean(null, area == Island.Dungeon)
    }
}
