package com.devgineerclient.misc

import com.devgineerclient.DevgineerClient
import com.devgineerclient.DevgineerClient.mc
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

/**
 * The f7 launcher's handle on a running game, and the parked start.
 *
 * Every launch listens on $XDG_RUNTIME_DIR/f7-ctl/<pid>.sock, one line in and one line out:
 *  state   starting | parked | running
 *  go      a parked launch carries on (one still starting won't stop at the park point at all)
 *  quit    a running game closes the normal way (as Quit Game would); a parked or starting one exits at once
 *
 * Launched with -Ddc.park=1, the start stops just before the window and graphics come up (RenderSystem's backend
 * init: every mod initialised, options read) and waits for `go`: no window, no CPU, no server connection. Everything
 * before that point (~10 s of a 16 s launch) is then already done when you press play.
 */
object Park {
    @Volatile private var state = "starting"
    @Volatile private var quit = false
    /** Only a launch f7 can reach parks (no socket, nothing could ever say go). */
    @Volatile private var listening = false
    private val go = CountDownLatch(1)

    fun install() {
        val runtime = System.getenv("XDG_RUNTIME_DIR") ?: return
        val dir = Path.of(runtime, "f7-ctl")
        Files.createDirectories(dir)
        val path = dir.resolve("${ProcessHandle.current().pid()}.sock")
        Files.deleteIfExists(path)
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(path))
        path.toFile().deleteOnExit()
        listening = true
        Thread.ofPlatform().daemon().name("dc-park-control").start {
            while (true) runCatching { server.accept().use { ch ->
                val reply = when (Channels.newInputStream(ch).bufferedReader().readLine()?.trim()) {
                    "state" -> state
                    "go" -> { go.countDown(); "ok" }
                    "quit" -> quit()
                    else -> "?"
                }
                Channels.newOutputStream(ch).write("$reply\n".toByteArray())
            } }
        }
    }

    private fun quit(): String {
        if (state == "running") { mc.execute { mc.stop() }; return "ok" }
        quit = true
        if (state == "parked") Runtime.getRuntime().halt(0)
        go.countDown()   // still starting: exits at the park point
        return "ok"
    }

    /** RenderSystem.initBackendSystem HEAD (ParkMixin). */
    @JvmStatic
    fun atParkPoint() {
        if (listening && System.getProperty("dc.park") == "1" && go.count > 0 && !quit) {
            state = "parked"
            DevgineerClient.logger.info("[dc] parked before the window, waiting for f7")
            go.await()
            LaunchTimeline.unparked()
        }
        if (quit) Runtime.getRuntime().halt(0)
        state = "running"
    }
}

/**
 * dtmap's map image watcher (a non-daemon "DTM-ImageLoader" thread) never ends on its own, so after Quit Game the
 * process lingered until Minecraft's shutdown watchdog killed it (~16 s, and a "Client shutdown from post-main"
 * crash report every time). Interrupting it ends its loop.
 */
object FastExit {
    fun install() {
        ClientLifecycleEvents.CLIENT_STOPPING.register {
            Thread.getAllStackTraces().keys.filter { it.name == "DTM-ImageLoader" }.forEach { it.interrupt() }
        }
    }
}
