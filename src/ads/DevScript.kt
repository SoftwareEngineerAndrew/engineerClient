package com.engineerclient.ads

import com.engineerclient.EngineerClient
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import net.rustclear.mod.Floor
import java.nio.file.Files

/**
 * Development runs only: drives the game from a script so a change can be looked at without anyone at the keyboard.
 * Reads run/ads-dev-script.txt once the title screen is up, one step per line, and renames it to .done when it ends:
 *
 *   wait N                 N ticks
 *   waitworld [N]          until in a world with no screen open (at most N ticks, default 1200)
 *   open title|ads|practice|settings|p3menu|pause|close
 *   sim                    the P3 Sim world
 *   leave                  out of the world, as Save and Quit does
 *   practice clear|rush|camp|p1|p2|p4|p5 [F7|M7]   or   practice room NAME [door] [F7|M7]
 *   cmd TEXT               a command, without its slash
 *   simaction KIND         a P3 Sim menu action (start, stop, spectateRoute...), as its buttons send them
 *   perspective N          the camera: 0 first person, 1 third person behind, 2 third person in front
 *   click X Y [right]      a click on the open screen, in GUI coordinates
 *   key CODE               a key press (InputConstants code) on the open screen, or in game with none open
 *   shot NAME              a screenshot, run/screenshots/NAME.png
 *   tp X Y Z               moves the player there (the integrated server's player)
 *   column X Y1 Y2 Z       logs the blocks from Y1 to Y2 at (X, Z) on the server
 *   floormap Y X1 X2 Z1 Z2 logs a map of the blocks at height Y ('#' solid, '~' fluid, '.' air)
 *   status                 logs the player's position, health and game mode
 *   log TEXT / quit
 *
 * Each step is logged as "[devscript]"; a step that fails is logged and the script goes on.
 */
object DevScript {
    private var steps: ArrayDeque<String>? = null
    private var waitTicks = 0
    private var waitWorld = 0

    fun init() {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment) return
        ClientTickEvents.END_CLIENT_TICK.register { mc -> EngineerClient.safely("devscript") { tick(mc) } }
    }

    private fun tick(mc: Minecraft) {
        val file = mc.gameDirectory.toPath().resolve("ads-dev-script.txt")
        if (steps == null) {
            if (mc.gui.screen() !is TitleScreen || !Files.exists(file)) return
            steps = ArrayDeque(Files.readAllLines(file).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") })
            // Nobody is at the window, so it never has focus: don't pause for that.
            mc.options.pauseOnLostFocus = false
            Files.move(file, file.resolveSibling("ads-dev-script.txt.done"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            log("start, ${steps!!.size} steps")
        }
        val queue = steps ?: return
        if (waitTicks > 0) { waitTicks--; return }
        if (waitWorld > 0) {
            if (mc.player != null && mc.gui.screen() is PauseScreen) mc.gui.setScreen(null)
            val ready = mc.player != null && mc.level != null && mc.gui.screen() == null
            if (!ready && --waitWorld > 0) return
            log(if (ready) "in world" else "waitworld timed out")
            waitWorld = 0
        }
        val step = queue.removeFirstOrNull() ?: return
        log(step)
        try {
            run(mc, step)
        } catch (t: Throwable) {
            log("step failed: $t")
        }
    }

    private fun run(mc: Minecraft, step: String) {
        val args = step.split(Regex("\\s+"))
        when (args[0]) {
            "wait" -> waitTicks = args.getOrNull(1)?.toInt() ?: 20
            "waitworld" -> waitWorld = args.getOrNull(1)?.toInt() ?: 1200
            "open" -> {
                val title = TitleScreen()
                mc.gui.setScreen(when (args.getOrNull(1)) {
                    "title" -> title
                    "ads" -> net.rustclear.mod.RustclearMenuScreen(title)
                    "practice" -> PracticeScreen(net.rustclear.mod.RustclearMenuScreen(title))
                    "settings" -> { com.engineerclient.ads.settings.AdsSettingsRegistry.open(); return }
                    "p3menu" -> com.engineerclient.p3sim.SimRestartScreen()
                    "pause" -> PauseScreen(true)
                    else -> null
                })
            }
            "sim" -> com.engineerclient.p3sim.SimWorld.open()
            "leave" -> mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("Leaving"))
            "practice" -> {
                val floor = Floor.of(args.lastOrNull { it.equals("F7", true) || it.equals("M7", true) } ?: "M7")
                EnginePractice.start(when (args.getOrNull(1)) {
                    "clear" -> PracticeRequest.FullClear(floor, args.getOrNull(2)?.toLongOrNull())
                    "rush" -> PracticeRequest.BloodRush(floor, args.getOrNull(2)?.toLongOrNull())
                    "camp" -> PracticeRequest.BloodCamp(floor)
                    "room" -> PracticeRequest.Room(floor, args[2], args.getOrNull(3)?.takeIf { it.lowercase() in DOORS } ?: "random")
                    "p1" -> PracticeRequest.Boss(floor, PracticeRequest.Phase.P1)
                    "p2" -> PracticeRequest.Boss(floor, PracticeRequest.Phase.P2)
                    "p4" -> PracticeRequest.Boss(floor, PracticeRequest.Phase.P4)
                    "p5" -> PracticeRequest.Boss(Floor.M7, PracticeRequest.Phase.P5)
                    else -> error("unknown practice ${args.getOrNull(1)}")
                })
            }
            "simaction" -> com.engineerclient.p3sim.SimActions.send(args[1])
            "perspective" -> mc.options.setCameraType(net.minecraft.client.CameraType.entries[args[1].toInt()])
            "cmd" -> mc.player?.connection?.sendCommand(step.substringAfter(' ')) ?: log("no player for cmd")
            "click" -> {
                val screen = mc.gui.screen() ?: error("no screen to click")
                val button = if (args.getOrNull(3) == "right") InputConstants.MOUSE_BUTTON_RIGHT else InputConstants.MOUSE_BUTTON_LEFT
                val event = MouseButtonEvent(args[1].toDouble(), args[2].toDouble(), MouseButtonInfo(button, 0))
                screen.mouseClicked(event, false)
                screen.mouseReleased(event)
            }
            "key" -> {
                val code = args[1].toInt()
                val screen = mc.gui.screen()
                if (screen != null) screen.keyPressed(net.minecraft.client.input.KeyEvent(code, 0, 0))
                else log("key outside a screen isn't supported")
            }
            "shot" -> {
                val name = (args.getOrNull(1) ?: "shot") + ".png"
                Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1) { msg -> log("saved ${msg.string}") }
            }
            "status" -> {
                val p = mc.player ?: return log("status: no player")
                log("status: pos %.2f %.2f %.2f, health %.1f/%.1f, mode %s, alive %s, lava %s, fire %s".format(
                    p.x, p.y, p.z, p.health, p.maxHealth, mc.gameMode?.playerMode, p.isAlive, p.isInLava, p.isOnFire))
            }
            "tp" -> {
                val id = mc.player?.uuid ?: return log("tp: no player")
                val (x, y, z) = args.drop(1).take(3).map { it.toDouble() }
                val server = mc.singleplayerServer ?: return log("tp: no integrated server")
                server.execute {
                    val p = server.playerList.getPlayer(id) ?: return@execute
                    p.teleportTo(p.level(), x, y, z, emptySet(), p.yRot, p.xRot, true)
                    p.resetFallDistance()
                }
            }
            "column" -> {
                val (x, y1, y2, z) = args.drop(1).take(4).map { it.toInt() }
                val server = mc.singleplayerServer ?: return log("column: no integrated server")
                val id = mc.player?.uuid ?: return log("column: no player")
                server.execute {
                    val level = server.playerList.getPlayer(id)?.level() ?: return@execute
                    val runs = ArrayList<String>()
                    var last = ""; var from = y1
                    for (y in y1..y2 + 1) {
                        val name = if (y > y2) "" else level.getBlockState(net.minecraft.core.BlockPos(x, y, z)).toString()
                            .removePrefix("Block{minecraft:").replace("}", "")
                        if (name != last) { if (y > y1) runs += "$from-${y - 1} $last"; last = name; from = y }
                    }
                    log("column $x $z: ${runs.joinToString(", ")}")
                }
            }
            "floormap" -> {
                // floormap Y X1 X2 Z1 Z2: one line per z, '#' where a player could stand on (x, Y), '~' over fluid.
                val (y, x1, x2, z1, z2) = args.drop(1).take(5).map { it.toInt() }
                val server = mc.singleplayerServer ?: return log("floormap: no integrated server")
                val id = mc.player?.uuid ?: return log("floormap: no player")
                server.execute {
                    val level = server.playerList.getPlayer(id)?.level() ?: return@execute
                    for (z in z1..z2) {
                        val row = (x1..x2).joinToString("") { x ->
                            val s = level.getBlockState(net.minecraft.core.BlockPos(x, y, z))
                            when { s.isAir -> "."; !s.fluidState.isEmpty -> "~"; else -> "#" }
                        }
                        log("floormap y$y z$z $row")
                    }
                }
            }
            "log" -> {}
            "quit" -> mc.stop()
            else -> log("unknown step")
        }
    }

    private val DOORS = setOf("north", "east", "south", "west", "random")

    private fun log(s: String) = EngineerClient.logger.info("[devscript] $s")
}
