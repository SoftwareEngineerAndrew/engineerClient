package com.engineerclient.misc

import com.engineerclient.EngineerClient
import com.engineerclient.adr.ADR
import com.google.gson.JsonObject
import com.odtheking.odin.clickgui.settings.RenderableSetting
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.HUDSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration

/**
 * Once per launch, the site (/adm/api/launch, shown on its admin page) is told who is playing, which
 * modules they have on with those modules' settings, and how long they have played with the mod.
 *
 * Settings go as on/off, numbers and choices only: text, keybinds, colours and hidden settings (the ADR
 * owner token) stay here. Playtime is counted on client ticks and saved once a minute, so the total sent
 * is everything up to the end of the last session.
 */
object LaunchStats {
    private const val URL = "https://${ADR.SITE}/adm/api/launch"
    /** A gap longer than this between two ticks (the game frozen or the machine asleep) isn't counted. */
    private const val MAX_GAP_MS = 5_000L
    private const val SAVE_EVERY_MS = 60_000L

    private val file get() = EngineerClient.mc.gameDirectory.toPath().resolve("config").resolve("engineerclient").resolve("playtime.txt")

    /** Milliseconds played with the mod, all sessions. */
    private var playtimeMs = 0L
    private var lastTick = 0L
    private var lastSave = 0L

    fun install() {
        playtimeMs = runCatching { Files.readString(file).trim().toLong() }.getOrDefault(0L)
        ClientTickEvents.END_CLIENT_TICK.register { runCatching { count() } }
        ClientLifecycleEvents.CLIENT_STOPPING.register { save() }
        // Started: Odin has read every module's settings by now.
        ClientLifecycleEvents.CLIENT_STARTED.register { runCatching { send() } }
    }

    private fun count() {
        val now = System.currentTimeMillis()
        if (lastTick != 0L) (now - lastTick).takeIf { it in 1..MAX_GAP_MS }?.let { playtimeMs += it }
        lastTick = now
        if (now - lastSave >= SAVE_EVERY_MS) { lastSave = now; save() }
    }

    private fun save() = runCatching {
        Files.createDirectories(file.parent)
        Files.writeString(file, playtimeMs.toString())
    }

    private fun report(): JsonObject {
        val mod = FabricLoader.getInstance().getModContainer("engineerclient")
        return JsonObject().apply {
            addProperty("name", EngineerClient.mc.user.name)
            addProperty("build", mod.map { it.metadata.id }.orElse("?"))
            addProperty("version", mod.map { it.metadata.version.friendlyString }.orElse("?"))
            addProperty("mc", FabricLoader.getInstance().getModContainer("minecraft").map { it.metadata.version.friendlyString }.orElse("?"))
            addProperty("playtimeMs", playtimeMs)
            add("modules", JsonObject().also { modules ->
                for (m in EngineerClient.MODULES) modules.add(m.name, JsonObject().apply {
                    addProperty("enabled", m.enabled)
                    if (m.enabled) add("settings", JsonObject().also { s ->
                        for ((key, setting) in m.settings) {
                            if ((setting as? RenderableSetting<*>)?.hidden == true) continue
                            when (setting) {
                                is BooleanSetting -> s.addProperty(key, setting.value)
                                is NumberSetting<*> -> s.addProperty(key, setting.value as Number)
                                is SelectorSetting<*> -> s.addProperty(key, setting.value.toString())
                                is HUDSetting -> s.addProperty(key, setting.value.enabled)
                                else -> {}
                            }
                        }
                    })
                })
            })
        }
    }

    private fun send() {
        val body = report().toString()
        Thread.ofVirtual().name("engineerclient-launch-stats").start {
            runCatching {
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build().send(
                    HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(20))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.discarding(),
                )
            } // a failed send is dropped: nothing is shown or logged
        }
    }
}
