package com.devgineerclient.misc

import com.devgineerclient.DevgineerClient.mc
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path

/**
 * The f7 launcher's handle on a running game: $XDG_RUNTIME_DIR/f7-ctl/<pid>.sock, one line in and one line out.
 *  state   running
 *  quit    the game closes the normal way (as Quit Game would)
 */
object F7Control {
    fun install() {
        val runtime = System.getenv("XDG_RUNTIME_DIR") ?: return
        val dir = Path.of(runtime, "f7-ctl")
        Files.createDirectories(dir)
        val path = dir.resolve("${ProcessHandle.current().pid()}.sock")
        Files.deleteIfExists(path)
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(path))
        path.toFile().deleteOnExit()
        Thread.ofPlatform().daemon().name("dc-f7-control").start {
            while (true) runCatching { server.accept().use { ch ->
                val reply = when (Channels.newInputStream(ch).bufferedReader().readLine()?.trim()) {
                    "state" -> "running"
                    "quit" -> { mc.execute { mc.stop() }; "ok" }
                    else -> "?"
                }
                Channels.newOutputStream(ch).write("$reply\n".toByteArray())
            } }
        }
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
            if (FastLaunch.fastExit) Thread.getAllStackTraces().keys.filter { it.name == "DTM-ImageLoader" }.forEach { it.interrupt() }
        }
    }
}
