package com.engineerclient.visual

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.textures.FilterMode
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.ActionSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.clickgui.settings.impl.StringSetting
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.modMessage
import com.odtheking.odin.utils.skyblock.Island
import com.odtheking.odin.utils.skyblock.LocationUtils
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.renderer.state.MapRenderState
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import net.minecraft.util.Util
import net.minecraft.world.item.MapItem
import net.minecraft.world.level.saveddata.maps.MapId
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.math.roundToInt

object HubMap : Module(
    name = "Hub Map",
    category = Category.SKYBLOCK,
    description = "Replaces the SkyBlock Hub wall map with a custom image.",
    key = null,
) {
    private enum class Source(private val label: String) {
        DEFAULT("Default"), URL("URL");
        override fun toString() = label
    }

    private enum class Fit(private val label: String) {
        CROP("Crop"), FIT("Fit");
        override fun toString() = label
    }

    private const val COLUMNS = 13
    private const val ROWS = 7
    private const val TILE_COUNT = COLUMNS * ROWS
    private const val TILE_SIZE = MapItem.IMAGE_WIDTH

    private val image by SelectorSetting("Image", Source.DEFAULT, desc = "The built-in image or one from a link.")
    private val fitMode by SelectorSetting("Image Fit Mode", Fit.CROP, desc = "Crop the image to the wall or fit it inside.")
    private val imageUrl by StringSetting("Image URL", "", 256, desc = "Direct link to an image, best at ${COLUMNS * TILE_SIZE}x${ROWS * TILE_SIZE}.", placeholder = "").withDependency { image == Source.URL }
    private val reloadImage by ActionSetting("Reload Image", desc = "Loads the image again.") { reload(force = true) }

    private val cacheDir by lazy { FabricLoader.getInstance().configDir.resolve("engineerclient").resolve("hub-map") }
    private val locations = Array(TILE_COUNT) { Identifier.fromNamespaceAndPath("engineerclient", "hub_map/$it") }
    private val http by lazy { HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build() }

    // Hub map ids never change; in wall order, row by row.
    private val mapIds = intArrayOf(
        30662, 30655, 30648, 30641, 30634, 30627, 30620, 30613, 30606, 30599, 30592, 30585, 30578,
        30661, 30654, 30647, 30640, 30633, 30626, 30619, 30612, 30605, 30598, 30591, 30584, 30577,
        30660, 30653, 30646, 30639, 30632, 30625, 30618, 30611, 30604, 30597, 30590, 30583, 30576,
        30659, 30652, 30645, 30638, 30631, 30624, 30617, 30610, 30603, 30596, 30589, 30582, 30575,
        30658, 30651, 30644, 30637, 30630, 30623, 30616, 30609, 30602, 30595, 30588, 30581, 30574,
        30657, 30650, 30643, 30636, 30629, 30622, 30615, 30608, 30601, 30594, 30587, 30580, 30573,
        30656, 30649, 30642, 30635, 30628, 30621, 30614, 30607, 30600, 30593, 30586, 30579, 30572,
    )

    @Volatile private var texturesLoaded = false
    private var requested = false
    private var generation = 0

    init {
        on<TickEvent.End> { if (!requested) reload() }
    }

    @JvmStatic
    fun applyRenderState(mapId: MapId, state: MapRenderState): Boolean {
        if (!enabled || !texturesLoaded || LocationUtils.currentArea != Island.Hub) return false
        val index = mapIds.indexOf(mapId.id).takeIf { it != -1 } ?: return false
        state.texture = locations[index]
        state.decorations.clear()
        return true
    }

    private fun reload(force: Boolean = false) {
        requested = true
        val gen = ++generation
        val source = image
        val url = imageUrl
        CompletableFuture.supplyAsync({
            if (source == Source.DEFAULT) HubMap::class.java.getResourceAsStream("/assets/engineerclient/hub-map.png")?.use { it.readBytes() }
            else download(url, force)
        }, Util.nonCriticalIoPool()).whenComplete { bytes, error ->
            if (error != null) mc.execute { modMessage("§cHub Map: couldn't load the image (${error.cause?.message ?: error.message})") }
            if (bytes == null) return@whenComplete
            val wall = runCatching { toWall(bytes) }.getOrElse {
                mc.execute { modMessage("§cHub Map: couldn't read the image.") }
                return@whenComplete
            }
            mc.execute {
                wall.use {
                    if (gen != generation) return@execute
                    locations.forEach(mc.textureManager::release)
                    repeat(TILE_COUNT) { i ->
                        val tile = NativeImage(TILE_SIZE, TILE_SIZE, false)
                        wall.copyRect(tile, i % COLUMNS * TILE_SIZE, i / COLUMNS * TILE_SIZE, 0, 0, TILE_SIZE, TILE_SIZE, false, false)
                        mc.textureManager.register(locations[i], object : DynamicTexture({ "Hub map tile $i" }, tile) {
                            init { sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR) }
                        })
                    }
                    texturesLoaded = true
                }
            }
        }
    }

    private fun download(url: String, force: Boolean): ByteArray? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return null

        Files.createDirectories(cacheDir)
        val cacheFile = cacheDir.resolve(url.trim().hashCode().toString())
        if (!force && cacheFile.isRegularFile()) return cacheFile.readBytes()

        val response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() !in 200..299) error("HTTP ${response.statusCode()}")
        val type = response.headers().firstValue("Content-Type").orElse(null)
        if (type != null && !type.startsWith("image/") && type != "application/octet-stream") error("the link isn't an image ($type), use the direct file link")
        return response.body().also { Files.write(cacheFile, it) }
    }

    private fun toWall(bytes: ByteArray): NativeImage {
        val decoded = ImageIO.read(bytes.inputStream()) ?: error("unsupported image")
        val png = ByteArrayOutputStream().also { ImageIO.write(decoded, "png", it) }.toByteArray()
        return NativeImage.read(png).use(::fit)
    }

    private fun fit(source: NativeImage): NativeImage {
        val canvasWidth = COLUMNS * TILE_SIZE
        val canvasHeight = ROWS * TILE_SIZE
        val targetRatio = canvasWidth.toDouble() / canvasHeight

        if (fitMode == Fit.CROP) {
            val cropWidth = minOf(source.width, (source.height * targetRatio).roundToInt())
            val cropHeight = minOf(source.height, (source.width / targetRatio).roundToInt())
            return NativeImage(canvasWidth, canvasHeight, false).also { wall ->
                source.resizeSubRectTo((source.width - cropWidth) / 2, (source.height - cropHeight) / 2, cropWidth, cropHeight, wall)
            }
        }

        val scale = minOf(canvasWidth.toDouble() / source.width, canvasHeight.toDouble() / source.height)
        val scaledWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
        val scaledHeight = (source.height * scale).roundToInt().coerceAtLeast(1)
        return NativeImage(scaledWidth, scaledHeight, false).use { scaled ->
            source.resizeSubRectTo(0, 0, source.width, source.height, scaled)
            NativeImage(canvasWidth, canvasHeight, false).also { wall ->
                wall.fillRect(0, 0, canvasWidth, canvasHeight, 0)
                scaled.copyRect(wall, 0, 0, (canvasWidth - scaledWidth) / 2, (canvasHeight - scaledHeight) / 2, scaledWidth, scaledHeight, false, false)
            }
        }
    }
}
