package com.devgineerclient.misc

import com.devgineerclient.DevgineerClient
import com.devgineerclient.DevgineerClient.mc
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.onSend
import com.odtheking.odin.utils.skyblock.LocationUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime

/**
 * Where a launch's time goes, to the millisecond: each moment from the JVM starting to standing on Skyblock,
 * timed from the process's start. Written once per launch, when Skyblock is reached (or a minute after the
 * first join without it), as one line to the game log and appended to logs/launch-timeline.log.
 *
 * Moments (the first of each per launch; the transfer to Skyblock gets its own second config/join):
 *  jvm-main      the JVM handed over to Java code (its own uptime: before this is process start + JVM init)
 *  dc-init       this client's initialiser, i.e. roughly when the mods' initialisers are running
 *  started       Minecraft's constructor done (window up, first resource load begun)
 *  loaded        the loading overlay gone: resources loaded
 *  title         the title screen first built
 *  connect       the connect screen (address resolve + TCP from here)
 *  login         the login phase (encryption, Mojang session join)
 *  config        the configuration phase (registries, tags, resource pack offers)
 *  join          in the play phase on the lobby
 *  world         the lobby drawn (no screen, a level)
 *  /skyblock     the command sent
 *  config2/join2 Hypixel's move to Skyblock
 *  skyblock      Odin sees the Skyblock scoreboard
 *  sb-world      Skyblock drawn
 */
object LaunchTimeline {
    private val startMs = ProcessHandle.current().info().startInstant().map { it.toEpochMilli() }.orElse(ManagementFactory.getRuntimeMXBean().startTime)
    private val marks = LinkedHashMap<String, Long>()
    private var written = false
    private var overlayWasUp = false
    private var joins = 0
    private var configs = 0
    private var firstJoinAt = 0L

    private fun now() = System.currentTimeMillis() - startMs

    fun mark(name: String) {
        if (written || name in marks) return
        marks[name] = now()
    }


    fun install() {
        // How long the JVM took before any Java ran: its uptime counts from JVM init, the process from exec.
        marks["jvm-main"] = ManagementFactory.getRuntimeMXBean().startTime - startMs
        mark("dc-init")
        ClientLifecycleEvents.CLIENT_STARTED.register { mark("started") }
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            when (screen) {
                is TitleScreen -> mark("title")
                is ConnectScreen -> mark("connect")
            }
        }
        ClientLoginConnectionEvents.INIT.register { _, _ -> mark("login") }
        ClientConfigurationConnectionEvents.START.register { _, _ -> configs++; mark(if (configs == 1) "config" else "config$configs") }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            joins++
            mark(if (joins == 1) "join" else "join$joins")
            if (joins == 1) firstJoinAt = System.currentTimeMillis()
        }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (written) return@register
            if (client.gui.overlay() != null) overlayWasUp = true
            else if (overlayWasUp) mark("loaded")
            val inWorld = client.level != null && client.gui.screen() == null
            if (inWorld && joins == 1) mark("world")
            if (LocationUtils.isInSkyblock) mark("skyblock")
            if (inWorld && "skyblock" in marks) { mark("sb-world"); write() }
            else if (firstJoinAt != 0L && System.currentTimeMillis() - firstJoinAt > 60_000) write()
        }
        onSend<ServerboundChatCommandPacket> { if (command == "skyblock") mark("/skyblock") }
        EventBus.subscribe(this)
    }

    private fun write() {
        written = true
        var prev = 0L
        var line = marks.entries.joinToString("  ") { (k, v) -> "$k ${v}ms (+${v - prev})".also { prev = v } }
        DevgineerClient.logger.info("[dc] launch timeline: {}", line)
        // In chat: the build this launch runs (so a restart onto a new jar is visible) and the time it took.
        if (FastLaunch.buildChat) runCatching {
            val build = FabricLoader.getInstance().getModContainer("engineerclient").map { it.metadata.version.friendlyString }.orElse("?")
            val total = marks["sb-world"]?.let { end -> end }
            val took = total?.let { " §8· §7%.1f s to Skyblock".format(it / 1000.0) } ?: ""
            DevgineerClient.chat("§8[dc] §7build §f$build$took")
        }
        if (FastLaunch.timeline) runCatching {
            val file = mc.gameDirectory.toPath().resolve("logs").resolve("launch-timeline.log")
            Files.writeString(file, "${LocalDateTime.now().withNano(0)}  $line\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }
}
