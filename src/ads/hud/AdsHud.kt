// Adapted from Odin (https://github.com/odtheking/OdinFabric), Copyright (c) 2025, odtheking. BSD 3-Clause License: see licenses/Odin-LICENSE.txt.
package com.engineerclient.ads.hud

import com.engineerclient.EngineerClient
import com.engineerclient.ads.settings.BooleanSetting
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * One of ADS's HUDs, as Odin's HudElement and HUDSetting make one: where it sits ([x], [y], top left, in GUI pixels)
 * and how big ([scale]), whether it shows ([enabled], an ADS setting, so its on/off sits in that part's `/ads` panel),
 * and [draw], which draws it at (0, 0) and returns its size unscaled. With example = true it draws what the HUD
 * editor shows: content that's always there, so it can be placed outside a run. Off duty it draws nothing and
 * returns 0 to 0.
 *
 * Its place and size are saved in config/engineerclient/huds.json by [id]; the defaults are what reset goes back to.
 */
class AdsHud(
    val id: String,
    val name: String,
    val description: String,
    val defaultX: Int,
    val defaultY: Int,
    val defaultScale: Float,
    val enabled: BooleanSetting,
    val draw: GuiGraphicsExtractor.(example: Boolean) -> Pair<Int, Int>,
) {
    var x = defaultX
    var y = defaultY
    var scale = defaultScale

    /** The size the last draw returned (unscaled), for the editor's box and hit test. */
    var width = 0
        private set
    var height = 0
        private set

    val scaledWidth: Int get() = (width * scale).toInt()
    val scaledHeight: Int get() = (height * scale).toInt()

    fun reset() {
        x = defaultX
        y = defaultY
        scale = defaultScale
    }

    fun clampToScreen() {
        val w = Minecraft.getInstance().window
        x = x.coerceIn(0, (w.guiScaledWidth - scaledWidth).coerceAtLeast(0))
        y = y.coerceIn(0, (w.guiScaledHeight - scaledHeight).coerceAtLeast(0))
    }

    /** Draws it at its place and size (Odin's drawAtHud), and keeps the size it came out at. */
    fun render(g: GuiGraphicsExtractor, example: Boolean) {
        g.pose().pushMatrix()
        g.pose().translate(x.toFloat(), y.toFloat())
        g.pose().scale(scale)
        val (w, h) = try {
            g.draw(example)
        } catch (t: Throwable) {
            if (failed.add(id)) EngineerClient.logger.error("[ads] HUD $id failed to draw (logged once)", t)
            0 to 0
        }
        g.pose().popMatrix()
        width = w
        height = h
    }

    companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 5f
        private val failed = HashSet<String>()
    }
}

/**
 * Every ADS HUD ([register]), drawn over the game in one Fabric HUD layer, the way Odin's HudLayer draws its HUD
 * settings, and saved together: config/engineerclient/huds.json holds each one's x, y and scale by id.
 */
object AdsHuds {
    val all = mutableListOf<AdsHud>()

    private val path: Path get() = Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("engineerclient").resolve("huds.json")

    /** The saved file, read once, so a HUD registered later still gets its place. */
    private val saved: JsonObject by lazy {
        try {
            if (Files.exists(path)) JsonParser.parseString(Files.readString(path)).asJsonObject else JsonObject()
        } catch (t: Throwable) {
            EngineerClient.logger.warn("[ads] failed to load huds.json, keeping the default places", t)
            JsonObject()
        }
    }

    private var layered = false

    /** Adds [hud]: at its saved place if there is one, and drawn from now on while it's [AdsHud.enabled]. */
    fun register(hud: AdsHud): AdsHud {
        all += hud
        (saved[hud.id] as? JsonObject)?.let { o ->
            runCatching {
                o["x"]?.let { hud.x = it.asInt }
                o["y"]?.let { hud.y = it.asInt }
                o["scale"]?.let { hud.scale = it.asFloat.coerceIn(AdsHud.MIN_SCALE, AdsHud.MAX_SCALE) }
            }
        }
        if (!layered) {
            layered = true
            HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("engineerclient", "ads_huds")) { g, _ ->
                EngineerClient.safely("ads huds") { drawAll(g) }
            }
        }
        return hud
    }

    private fun drawAll(g: GuiGraphicsExtractor) {
        val mc = Minecraft.getInstance()
        // F1 hides them as it hides the game's own; the editor draws its own examples.
        if (mc.gui.hud.isHidden || mc.gui.screen() is AdsHudEditor) return
        for (h in all) if (h.enabled.value) h.render(g, example = false)
    }

    fun save() {
        try {
            Files.createDirectories(path.parent)
            val json = JsonObject()
            for (h in all) json.add(h.id, JsonObject().apply {
                addProperty("x", h.x)
                addProperty("y", h.y)
                addProperty("scale", h.scale)
            })
            val tmp = path.resolveSibling("huds.json.tmp")
            Files.writeString(tmp, GSON.toJson(json))
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (t: Throwable) {
            EngineerClient.logger.warn("[ads] failed to save huds.json", t)
        }
    }

    private val GSON = GsonBuilder().setPrettyPrinting().create()
}
