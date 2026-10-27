package com.engineerclient.adr

import com.engineerclient.EngineerClient
import com.odtheking.odin.clickgui.settings.impl.ActionSetting
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.StringSetting
import com.odtheking.odin.events.BlockInteractEvent
import com.odtheking.odin.events.BlockUpdateEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.RoomEnterEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import com.engineerclient.mixin.ContainerScreenAccessor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket
import net.minecraft.network.protocol.game.ClientboundBossEventPacket
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.sounds.SoundEvents
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import java.util.Optional
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.zip.GZIPInputStream

/**
 * ADR: records everything about a dungeon run, from instance load to leaving, so it can be
 * replayed in the browser viewer (undonecoffee.com/adr).
 *
 * A session starts on every world load while this is on; [RunRecorder] keeps it only if the world
 * turns out to be a dungeon. Runs land in config/engineerclient/adr/runs/.
 */
object ADR : Module(
    name = "ADR",
    category = Category.custom("Engineer Client", 860, 10),
    description = "Records everything about each dungeon run (players, mobs, blocks, chat, rooms) and uploads it to the ADR viewer (undonecoffee.com/adr), where it can be replayed in the browser. Runs here older than 30 days that the viewer has are deleted from this computer.",
    key = null,
) {
    private val privateRuns by BooleanSetting("Private Runs", true, desc = "Uploaded runs aren't listed on the viewer's home page: only people you give the link to can open them, unless someone in your party uploads the same run publicly - then yours shows as one of its POVs. /adr gives you a link to all your runs, private ones included.")
    private val hidePrivateChats by BooleanSetting("Hide Private Chats", true, desc = "Leaves private messages, guild, officer and co-op chat, friend requests and friends coming online out of recordings, so they are never saved (they are never uploaded either way).")
    private val recordAds by BooleanSetting("Record ADS Runs", true, desc = "Records your ADS full runs (the clear and the boss in the ADS world) into config/engineerclient/adr/sim, on this computer only: they are never uploaded. Used to check ADS against real runs.")
    private val hidePartyChat by BooleanSetting("Hide Party Chat", false, desc = "Leaves party chat out of recordings and uploads. Off, the viewer shows what your party said during the run.")
    // The chat lines each run brings. Errors (a failed upload or save) always show.
    val savedMessage by BooleanSetting("Saved Message", true, desc = "Says \"saved run\" in chat, with the file's size, when a run's recording is saved.")
    private val uploadedMessage by BooleanSetting("Uploaded Message", true, desc = "Says in chat, with the link, when a run has been uploaded. A failed upload is always said.")
    /**
     * A secret this install sends with every upload, so the site can list this player's runs -
     * private ones included - for whoever has it: the link /adr gives. Made on first use.
     */
    private var ownerToken by StringSetting("Owner Token", "", 64, desc = "Identifies your uploads for /adr's link.", placeholder = "").hide()

    internal fun token(): String {
        if (ownerToken.length < 32) {
            val r = java.security.SecureRandom()
            ownerToken = (1..32).joinToString("") { "0123456789abcdef"[r.nextInt(16)].toString() }
            com.odtheking.odin.features.ModuleManager.saveConfigurations()
        }
        return ownerToken
    }

    private val cameraFpsSetting by NumberSetting("Camera FPS", 30, 20..160, 10, desc = "How many times a second your view is saved (at most - never more than the game draws). Higher makes your POV in the viewer smoother on a high refresh rate screen; each 60 more adds about 3% to a run.")
    /** For the recorder: Camera FPS. */
    val cameraFps: Int get() = cameraFpsSetting.toInt()

    private val openOnline by ActionSetting("Open Online Page", desc = "Opens ADR Online (undonecoffee.com/adr/online) in your browser, signed in as you: everyone online with ADR and the party they are in, and your own looking-to-play settings. Each time signs the last browser out.") { OnlineLink.openPage() }
    private val uploadMissing by ActionSetting("Upload Missing Runs", desc = "Uploads every run saved on this computer that the viewer doesn't have yet - ones whose upload failed. One at a time, with progress in chat.") { uploadMissing() }

    const val SITE = "undonecoffee.com"
    private const val RUNS_URL = "https://$SITE/adr/api/runs"
    private val CONTROL_CODES = Regex("\u00a7.")
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    private var session: RunRecorder? = null
    /** This world's recording once it is known to be a Hypixel dungeon run (for layers that ride on it, like Better Recorder). */
    internal val recording: RunRecorder? get() = session?.takeIf { it.isConfirmed && !it.abandoned && !EngineerClient.mc.isLocalServer }
    private val simDir get() = EngineerClient.mc.gameDirectory.toPath().resolve("config").resolve("engineerclient").resolve("adr").resolve("sim")
    private val runsDir get() = EngineerClient.mc.gameDirectory.toPath().resolve("config").resolve("engineerclient").resolve("adr").resolve("runs")
    private val catDir get() = EngineerClient.mc.gameDirectory.toPath().resolve("config").resolve("engineerclient").resolve("adr").resolve("catalogue")

    init {
        on<LevelEvent.Load> {
            if (!tidied) { tidied = true; tidyRunsFolder() }
            EngineerClient.safely("adr start") {
                session?.finish()
                RoomKeys.reset()
                // An ADS full run is recorded too, into its own folder and never uploaded: it is what the sim is checked against the real runs with.
                val adsWorld = com.engineerclient.p3sim.SimServer.server != null && EngineerClient.mc.hasSingleplayerServer()
                session = if (adsWorld) (if (recordAds) RunRecorder(simDir, true, sim = true) else null) else RunRecorder(runsDir, true, ::upload)
            }
        }

        // ADR Online: your party, tracked from chat, and the link to the site.
        on<TickEvent.End> { EngineerClient.safely("adr online") { PartyTracker.tick(); OnlineLink.tick() } }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> EngineerClient.safely("adr party join") { PartyTracker.onJoin() } }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> EngineerClient.safely("adr party leave") { PartyTracker.onDisconnect() } }

        on<LevelEvent.Unload> {
            EngineerClient.safely("adr end") { session?.finish(); session = null }
        }

        on<TickEvent.End> {
            val s = session ?: return@on
            EngineerClient.safely("adr tick") { s.onTick(level) }
            (EngineerClient.mc.gui.screen() as? AbstractContainerScreen<*>)?.let { screen ->
                EngineerClient.safely("adr gui tick") { s.onContainerTick(screen.menu.slots.map { it.item }, screen.menu.carried) }
            }
            if (s.abandoned) session = null
        }

        // Every rendered frame: your own camera, so POV replays show exactly what you saw.
        on<RenderExtractEvent> {
            val s = session ?: return@on
            val player = EngineerClient.mc.player ?: return@on
            val pt = EngineerClient.mc.deltaTracker.getGameTimeDeltaPartialTick(true)
            EngineerClient.safely("adr frame") { s.onFrame(pt, player.yRot, player.xRot) }
            // The mouse over an open container, relative to its window (GUI pixels).
            (EngineerClient.mc.gui.screen() as? AbstractContainerScreen<*>)?.let { screen ->
                EngineerClient.safely("adr gui mouse") {
                    val mc = EngineerClient.mc
                    val box = screen as ContainerScreenAccessor
                    s.onContainerMouse(pt, (mc.mouseHandler.getScaledXPos(mc.window) - box.adrLeftPos()).toFloat(), (mc.mouseHandler.getScaledYPos(mc.window) - box.adrTopPos()).toFloat())
                }
            }
        }

        // Your Simon Says clicks, after Odin has had its say (so blocked ones are known).
        on<BlockInteractEvent>(priority = -1) {
            val s = session ?: return@on
            if (s.isSsButton(pos)) EngineerClient.safely("adr ss click") { s.onSsClick(pos, isCancelled) }
        }
        on<BlockUpdateEvent> { EngineerClient.safely("adr block") { session?.onBlockUpdate(pos, updated) } }
        // Server ticks (Odin's, from the server's per-tick ping): the server's own clock, which falls
        // behind the client's 20 a second when the server lags (what split timers and tick timers use).
        on<TickEvent.Server> { EngineerClient.safely("adr server tick") { session?.onServerTick() } }
        // Teleports the server puts you through (etherwarp, leaps, the Teleport Maze pads...).
        onReceive<ClientboundPlayerPositionPacket> {
            val player = EngineerClient.mc.player
            val abs = if (player != null) PositionMoveRotation.calculateAbsolute(PositionMoveRotation.of(player), change(), relatives()) else change()
            val rel = relatives().map { it.name }
            EngineerClient.mc.execute { EngineerClient.safely("adr tp") { session?.onTeleport(abs.position().x, abs.position().y, abs.position().z, abs.yRot(), abs.xRot(), if (player == null) rel else emptyList()) } }
        }
        // Bats hit or killed (secret bats: their squeak is quieter than any other bat's).
        onReceive<ClientboundSoundPacket> {
            val sound = getSound().value()
            // And every sound at the Simon Says device: the start button's presses don't show as a block change.
            if (getX() in 105.0..116.0 && getY() in 116.0..127.0 && getZ() in 87.0..99.0) {
                val id = sound.location().toString(); val x = getX(); val y = getY(); val z = getZ(); val v = getVolume(); val p = getPitch()
                EngineerClient.mc.execute { EngineerClient.safely("adr ss sound") { session?.onSsSound(id, x, y, z, v, p) } }
            }
            if (sound != SoundEvents.BAT_HURT && sound != SoundEvents.BAT_DEATH) return@onReceive
            val x = getX(); val y = getY(); val z = getZ(); val v = getVolume()
            EngineerClient.mc.execute { EngineerClient.safely("adr bat sound") { session?.onBatSound(x, y, z, v) } }
        }
        // Items picked up, and who picked them up.
        onReceive<ClientboundTakeItemEntityPacket> {
            val item = getItemId(); val by = getPlayerId()
            EngineerClient.mc.execute { EngineerClient.safely("adr pickup") { session?.onPickup(item, by) } }
        }
        // Chat straight off the network, before any mod can hide it (chat cleaners hide the terminal /
        // device / gate messages the report needs). Handed to the client thread, where ticks happen.
        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (overlay) { val c = content; EngineerClient.mc.execute { EngineerClient.safely("adr action bar") { session?.let { it.hud.actionBar(it.currentTick, c) } } }; return@onReceive }
            val text = content.string.replace(CONTROL_CODES, "")
            val colored = legacyText(content)
            // The answer to a /p list ADR sent itself: read, and kept out of chat.
            var hide = false
            EngineerClient.safely("adr party") { hide = PartyTracker.onChat(text, colored) }
            if (hide) it.cancel()
            if (hidePrivateChats && UploadPacker.privateChat(text)) return@onReceive
            if (hidePartyChat && UploadPacker.partyChat(text)) return@onReceive
            val n = session?.serverTickCount
            EngineerClient.mc.execute { EngineerClient.safely("adr chat") { session?.onChat(text, colored, n) } }
        }
        // Chests opening and closing (the lid's block event: how many players have it open), so the
        // viewer can open the chests people looted.
        onReceive<ClientboundBlockEventPacket> {
            if (b0 != 1 || (block != Blocks.CHEST && block != Blocks.TRAPPED_CHEST && block != Blocks.ENDER_CHEST)) return@onReceive
            val at = pos.immutable(); val open = b1
            EngineerClient.mc.execute { EngineerClient.safely("adr chest") { session?.onChestEvent(at, open) } }
        }
        // The HUD as the server sends it (HudCapture): the action bar (also sent as overlay chat, above), titles and boss bars.
        onReceive<ClientboundSetActionBarTextPacket> { val c = text(); EngineerClient.mc.execute { EngineerClient.safely("adr action bar") { session?.let { it.hud.actionBar(it.currentTick, c) } } } }
        onReceive<ClientboundSetTitleTextPacket> { val c = text(); EngineerClient.mc.execute { EngineerClient.safely("adr title") { session?.let { it.hud.title(it.currentTick, false, c) } } } }
        onReceive<ClientboundSetSubtitleTextPacket> { val c = text(); EngineerClient.mc.execute { EngineerClient.safely("adr title") { session?.let { it.hud.title(it.currentTick, true, c) } } } }
        onReceive<ClientboundClearTitlesPacket> { EngineerClient.mc.execute { EngineerClient.safely("adr title") { session?.let { it.hud.title(it.currentTick, false, null) } } } }
        onReceive<ClientboundBossEventPacket> {
            val packet = this
            EngineerClient.mc.execute { EngineerClient.safely("adr boss bar") { session?.let { s -> packet.dispatch(BossBars(s)) } } }
        }
        on<RoomEnterEvent> { EngineerClient.safely("adr room") { session?.onRoomEnter(room?.name) } }

        // Container screens you open (terminal GUIs among them), for exact terminal times.
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is AbstractContainerScreen<*>) return@register
            EngineerClient.safely("adr gui") {
                val s = session ?: return@safely
                val menu = screen.menu
                val box = screen as ContainerScreenAccessor
                // Your own inventory's menu has no registered type.
                val type = if (menu is InventoryMenu) "inventory" else runCatching { BuiltInRegistries.MENU.getKey(menu.type)?.toString() }.getOrNull() ?: "unknown"
                s.onContainerOpen(screen.title.string, type, box.adrImageWidth(), box.adrImageHeight(), menu.slots.map { intArrayOf(it.x, it.y) })
                s.onContainerTick(menu.slots.map { it.item }, menu.carried)
            }
            ScreenMouseEvents.afterMouseClick(screen).register { _, click, _ ->
                EngineerClient.safely("adr gui click") {
                    val box = screen as ContainerScreenAccessor
                    session?.onContainerClick((click.x() - box.adrLeftPos()).toFloat(), (click.y() - box.adrTopPos()).toFloat(), click.button())
                }
                false
            }
            ScreenEvents.remove(screen).register { EngineerClient.safely("adr gui close") { session?.onGuiClose() } }
        }
    }

    /** A container slot click sent to the server (from GameModeRecordMixin, whatever sent it). */
    fun onSlotClick(slot: Int, button: Int, type: String) = EngineerClient.safely("adr slot click") { session?.onSlotClick(slot, button, type) }

    /** A block you right-clicked (from GameModeRecordMixin). */
    fun onBlockUse(pos: BlockPos) = EngineerClient.safely("adr use") { session?.onBlockUse(pos) }

    /**
     * A chat line with its formatting as § codes: colour (the nearest of the 16 chat colours, or
     * §#rrggbb for any other), then bold/italic/underline/strikethrough/obfuscated, written again
     * wherever the style changes. § codes already inside the text are kept as they are.
     */
    internal fun legacyText(message: Component): String {
        val sb = StringBuilder()
        var last = ""
        message.visit(FormattedText.StyledContentConsumer<Unit> { style, text ->
            if (text.isNotEmpty()) {
                val codes = styleCodes(style)
                if (codes != last) { if (last.isNotEmpty()) sb.append("§r"); sb.append(codes); last = codes }
                sb.append(text)
            }
            Optional.empty()
        }, Style.EMPTY)
        return sb.toString()
    }

    private fun styleCodes(style: Style): String {
        val sb = StringBuilder()
        style.color?.let { sb.append(colorCode(it)) }
        if (style.isObfuscated) sb.append("§k")
        if (style.isBold) sb.append("§l")
        if (style.isStrikethrough) sb.append("§m")
        if (style.isUnderlined) sb.append("§n")
        if (style.isItalic) sb.append("§o")
        return sb.toString()
    }

    private fun colorCode(color: TextColor): String {
        val legacy = ChatFormatting.entries.firstOrNull { TextColor.fromLegacyFormat(it)?.value == color.value }
        return if (legacy != null) legacy.toString() else "§#" + String.format(java.util.Locale.ROOT, "%06x", color.value and 0xFFFFFF)
    }

    /**
     * Sends a finished run to the viewer, off the game thread: the run with its summary in a header -
     * the site stores files as-is and never unpacks them, so it needs to be told what the list shows.
     * On a low-priority platform thread: packing it (xz) is a few seconds of CPU, and that shouldn't
     * compete with the game loading the next world.
     */
    private fun upload(file: Path) {
        Thread.ofPlatform().name("engineerclient-adr-upload").daemon(true).priority(Thread.MIN_PRIORITY).start {
            try {
                // Party members recording the same run take turns, so the first one's recording is on
                // the site when the next ones look for it ([waitForSibling]).
                val scan = UploadPacker.scan(file, privateRuns)
                val sibling = waitForSibling(scan.summary)
                // A connection dropped on the way (Cloudflare resetting the stream, a moment
                // offline) is tried again twice, after 10 s and a minute. If the first try did get
                // there after all, the site says this run is already uploaded: that's done too.
                var id: String? = null
                for ((i, wait) in listOf(0L, 10_000L, 60_000L).withIndex()) {
                    if (wait > 0) Thread.sleep(wait)
                    try {
                        id = send(file, scan, sibling); break
                    } catch (e: java.io.IOException) {
                        if (i == 2) throw e
                        EngineerClient.logger.warn("[ec] adr upload try ${i + 1} failed, trying again: $e")
                    } catch (e: Refused) {
                        if (i == 0 || !e.message.orEmpty().startsWith("409")) throw e
                        id = ""; break
                    }
                }
                uploadFailSaid = false
                if (uploadedMessage) EngineerClient.msg("§7ADR: uploaded${if (privateRuns) " privately" else ""}${if (id.isNullOrEmpty()) "" else " - §f$SITE/adr/$id"}")
            } catch (t: Throwable) {
                if (t !is Refused) EngineerClient.logger.error("[ec] adr upload failed", t)
                else EngineerClient.logger.warn("[ec] adr upload refused: ${t.message}")
                // Said once, not after every run while the site can't be reached; said again after
                // an upload has gone through. Upload Missing Runs sends them later.
                if (!uploadFailSaid) {
                    uploadFailSaid = true
                    EngineerClient.msg("§cADR: upload ${if (t is Refused) "refused (${t.message})" else "failed - the site can't be reached"}. Runs are still saved here; §fUpload Missing Runs§c sends them later.")
                }
            }
        }
    }

    private class Refused(message: String) : Exception(message)

    /**
     * Another party member's recording of this run on the site to leave out what it has (its id), or
     * null. Whoever sorts first of the party members who upload (the site says who has lately) goes
     * straight away; the others wait up to [STAGGER_MS] for each one before them, asking every
     * [POLL_MS], and go as soon as one is there.
     */
    private fun waitForSibling(summary: JsonObject): String? {
        val self = summary["self"]?.asString?.lowercase() ?: return null
        var until = 0L
        while (true) {
            val (id, uploaders) = askSibling(summary) ?: return null
            if (id != null) return id
            if (until == 0L) {
                // (A site that doesn't say who uploads: everyone in the party, as before.)
                val names = uploaders ?: summary["party"]?.asJsonArray?.mapNotNull { runCatching { it.asJsonArray[0].asString }.getOrNull() } ?: return null
                val ahead = names.map { it.lowercase() }.distinct().count { it < self }
                if (ahead == 0) return null
                until = System.currentTimeMillis() + ahead * STAGGER_MS
            }
            if (System.currentTimeMillis() >= until) return null
            Thread.sleep(POLL_MS)
        }
    }

    private const val STAGGER_MS = 20_000L
    private const val POLL_MS = 2_500L

    /** The site's answer for this run: an earlier party member's recording (id or null), and who in the party uploads (null: not said). Null if it can't be asked. */
    private fun askSibling(summary: JsonObject): Pair<String?, List<String>?>? = runCatching {
        val res = http.send(HttpRequest.newBuilder(URI.create("$RUNS_URL/sibling")).header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.ofString(summary.toString())).build(), HttpResponse.BodyHandlers.ofInputStream())
        val body = res.body().use { s -> s.readNBytes(64 * 1024).toString(Charsets.UTF_8) }
        if (res.statusCode() != 200) return null
        val o = JsonParser.parseString(body).asJsonObject
        val id = o["id"]?.takeIf { !it.isJsonNull }?.asString?.takeIf { RUN_ID.matches(it) }
        val uploaders = o["uploaders"]?.takeIf { it.isJsonArray }?.asJsonArray?.take(16)?.mapNotNull { runCatching { it.asString }.getOrNull() }
        id to uploaders
    }.onFailure { EngineerClient.logger.warn("[ec] adr: sibling lookup failed", it) }.getOrNull()

    /** A party member's recording on the site ([id]): its bytes, or null. */
    private fun siblingBytes(id: String): ByteArray? {
        // (Anyone's upload, so it is only read up to a size, and UploadPacker trusts nothing in it.)
        return runCatching {
            val got = http.send(HttpRequest.newBuilder(URI.create("$RUNS_URL/$id")).timeout(Duration.ofMinutes(2)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
            got.body().use { s ->
                if (got.statusCode() != 200) return null
                // (the request's timeout is only until the headers: one sent a byte a minute isn't waited out)
                val bytes = java.util.concurrent.CompletableFuture.supplyAsync({ s.readNBytes(SIBLING_MAX + 1) }, { Thread.ofVirtual().start(it) })
                    .get(2, java.util.concurrent.TimeUnit.MINUTES)
                if (bytes.size > SIBLING_MAX) { EngineerClient.logger.warn("[ec] adr: a party member's upload is over ${SIBLING_MAX shr 20} MB, nothing left out"); return null }
                bytes
            }
        }.onFailure { EngineerClient.logger.warn("[ec] adr: sibling download failed", it) }.getOrNull()
    }

    /** One of the site's catalogue files (Catalogue), or null. */
    private fun catalogueBytes(name: String): ByteArray? = runCatching {
        val got = http.send(HttpRequest.newBuilder(URI.create("https://$SITE/adr/cat/$name")).timeout(Duration.ofMinutes(2)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        got.body().use { s -> if (got.statusCode() != 200) null else s.readNBytes(16 shl 20) }
    }.onFailure { EngineerClient.logger.warn("[ec] adr: catalogue download failed", it) }.getOrNull()

    /** A party member's upload bigger than this (a real one is a few MB) is left alone. */
    private const val SIBLING_MAX = 16 shl 20
    private val RUN_ID = Regex("""[A-Za-z0-9_-]{1,64}""")

    /** Sends one run, on the calling thread. Its id on the site. */
    private fun send(file: Path, scan: UploadPacker.Scan = UploadPacker.scan(file, privateRuns), siblingId: String? = askSibling(scan.summary)?.first): String {
        val summary = scan.summary
        // Without the mobs a party member's recording already on the site has (UploadPacker), as xz;
        // what the site's catalogues have as their numbers (Catalogue).
        val cat = runCatching { Catalogue.load(catDir, ::catalogueBytes) }.onFailure { EngineerClient.logger.warn("[ec] adr: no catalogue", it) }.getOrNull()
        val (packed, left) = UploadPacker.pack(file, scan, siblingId?.let(::siblingBytes), hideParty = hidePartyChat, cat = cat)
        if (left > 0) EngineerClient.logger.info("[ec] adr: $left mobs left out, already uploaded by a party member")
        try {
            return sendPacked(packed, summary)
        } finally {
            Files.deleteIfExists(packed)
        }
    }

    private fun sendPacked(file: Path, summary: JsonObject): String {
        // The site checks it, and takes a limited number an hour (more for solo runs). Signed in as
        // its recorder ([ADRAuth]) when that can be done, marking it verified; without, if not.
        val auth = summary["self"]?.asString?.let(ADRAuth::headerFor)
        val req = HttpRequest.newBuilder(URI.create(RUNS_URL))
            .header("X-Run-Summary", summary.toString())
            .header("X-Run-Owner", token())
            .apply { if (auth != null) header("X-ADR-Auth", auth) }
            .header("Content-Type", "application/octet-stream")
            .timeout(Duration.ofMinutes(5))
            .POST(HttpRequest.BodyPublishers.ofFile(file))
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() != 200) throw Refused("${res.statusCode()}: ${res.body().trim().take(80)}")
        return runCatching { JsonParser.parseString(res.body()).asJsonObject["id"].asString }.getOrDefault("")
    }

    @Volatile private var catchingUp = false

    // ------------------------------------------------------------------ the runs folder

    private var tidied = false
    private const val KEEP_DAYS = 30L
    private val PART_NAME = Regex("""^recording-(.+)\.jsonl\.gz\.part$""")

    /**
     * Once a game, in the background: recordings a crash left as .part files are saved as runs (as
     * far as they got), and runs older than [KEEP_DAYS] days that the site
     * has (asked by recorder and start time, as Upload Missing Runs does) are deleted here.
     */
    private fun tidyRunsFolder() {
        Thread.ofPlatform().name("engineerclient-adr-tidy").daemon(true).priority(Thread.MIN_PRIORITY).start {
            try {
                if (!Files.isDirectory(runsDir)) return@start
                val now = System.currentTimeMillis()
                // (one still being written is touched every few seconds)
                val parts = Files.list(runsDir).use { s -> s.filter { PART_NAME.matches(it.fileName.toString()) }.toList() }
                for (part in parts) if (now - Files.getLastModifiedTime(part).toMillis() > 30 * 60_000L) salvage(part)
                val old = Files.list(runsDir).use { s ->
                    s.filter { it.fileName.toString().endsWith(".jsonl.gz") && now - Files.getLastModifiedTime(it).toMillis() > KEEP_DAYS * 86_400_000L }.toList()
                }
                if (old.isEmpty()) return@start
                val keys = old.associateWith { runKey(it) }
                val ask = JsonArray().also { arr -> keys.values.filterNotNull().forEach { arr.add(it) } }
                val have = http.send(HttpRequest.newBuilder(URI.create("$RUNS_URL/have")).header("Content-Type", "application/json").header("X-Run-Owner", token())
                    .timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.ofString(ask.toString())).build(), HttpResponse.BodyHandlers.ofString())
                if (have.statusCode() != 200) return@start
                val onSite = JsonParser.parseString(have.body()).asJsonArray.mapTo(HashSet()) { it.asString }
                val gone = old.filter { f -> keys[f]?.let { it in onSite } == true && Files.deleteIfExists(f) }
                if (gone.isNotEmpty()) EngineerClient.logger.info("[ec] adr: deleted ${gone.size} runs over $KEEP_DAYS days old that the viewer has")
            } catch (t: Throwable) {
                EngineerClient.logger.warn("[ec] adr: tidying the runs folder failed", t)
            }
        }
    }

    /** A recording a crash cut off: its whole lines into a run file named as a finished one would be, then the .part goes. */
    private fun salvage(part: Path) {
        val stamp = PART_NAME.find(part.fileName.toString())?.groupValues?.get(1) ?: return
        val temp = part.resolveSibling("salvage-$stamp.tmp")
        var lines = 0
        var floor = "unknown"
        var meta = false
        java.util.zip.GZIPOutputStream(Files.newOutputStream(temp)).bufferedWriter(Charsets.UTF_8).use { w ->
            try {
                BufferedReader(InputStreamReader(GZIPInputStream(Files.newInputStream(part)), Charsets.UTF_8)).use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        if (lines == 0) meta = line.startsWith("{\"k\":\"meta\"")
                        if (line.startsWith("{\"k\":\"floor\"")) runCatching { floor = JsonParser.parseString(line).asJsonObject["floor"].asString }
                        w.write(line); w.newLine(); lines++
                    }
                }
            } catch (_: java.io.IOException) {
                // (where the crash cut it off)
            }
        }
        if (!meta || lines < 2) { Files.deleteIfExists(temp); Files.deleteIfExists(part); return }
        val target = part.resolveSibling("${stamp}_${floor.replace(Regex("[^A-Za-z0-9]+"), "")}.jsonl.gz")
        if (Files.exists(target)) { Files.deleteIfExists(temp); return }
        Files.move(temp, target)
        Files.deleteIfExists(part)
        EngineerClient.logger.info("[ec] adr: saved $lines lines of an unfinished recording as ${target.fileName}")
    }

    /** /adr: the link to every run this install has uploaded (with its owner token), private ones included. */
    fun myRunsLink() {
        val token = token()
        Thread.ofVirtual().name("adr-mine").start {
            val url = "https://$SITE/adr/?mine=$token"
            EngineerClient.msg(Component.literal("§7ADR: ").append(
                Component.literal("§b§nyour runs").withStyle { s ->
                    s.withClickEvent(net.minecraft.network.chat.ClickEvent.OpenUrl(URI.create(url)))
                        .withHoverEvent(net.minecraft.network.chat.HoverEvent.ShowText(Component.literal("§7$url")))
                }
            ).append(Component.literal(" §8(private ones too - keep this link to yourself)")))
        }
    }

    @Volatile private var uploadFailSaid = false

    /**
     * Uploads every finished run in the runs folder the site doesn't have. A run is on the site if a
     * run there has the same recorder and start time - private ones included: the site says so only
     * to their owner, so the owner token goes with the question.
     * The one being recorded is still a .part file, so it is never picked up. Runs even with Upload
     * Runs off - pressing the button is the ask.
     */
    private fun uploadMissing() {

        if (catchingUp) return EngineerClient.msg("§7ADR: already uploading missing runs.")
        catchingUp = true
        Thread.ofVirtual().name("adr-catch-up").start {
            try {
                // Asks by name rather than reading the run list, which leaves private runs off; with the
                // owner token, without which the site doesn't say whether it has a private run.
                val local = Files.list(runsDir).use { s -> s.filter { it.fileName.toString().endsWith(".jsonl.gz") }.sorted().toList() }
                val keys = local.associateWith { runKey(it) }
                val ask = JsonArray().also { arr -> keys.values.filterNotNull().forEach { arr.add(it) } }
                val have = http.send(HttpRequest.newBuilder(URI.create("$RUNS_URL/have")).header("Content-Type", "application/json").header("X-Run-Owner", token())
                    .timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.ofString(ask.toString())).build(), HttpResponse.BodyHandlers.ofString())
                if (have.statusCode() != 200) throw Refused("run check ${have.statusCode()}")
                val onSite = JsonParser.parseString(have.body()).asJsonArray.mapTo(HashSet()) { it.asString }
                val missing = local.filter { f -> keys[f]?.let { it !in onSite } ?: false }
                if (missing.isEmpty()) return@start EngineerClient.msg("§7ADR: all ${local.size} runs here are already on the viewer.")
                EngineerClient.msg("§7ADR: ${missing.size} of ${local.size} runs aren't on the viewer - uploading them.")
                var done = 0
                for ((i, f) in missing.withIndex()) {
                    try {
                        send(f)
                        done++
                        EngineerClient.msg("§7ADR: uploaded ${i + 1}/${missing.size} §8(${f.fileName})")
                    } catch (t: Throwable) {
                        // The site takes a limited number an hour: the rest another time.
                        if (t is Refused && t.message?.startsWith("429") == true) {
                            EngineerClient.msg("§eADR: $done uploaded - that's the hourly limit. Use Upload Missing Runs again later for the other ${missing.size - done}.")
                            return@start
                        }
                        EngineerClient.msg("§cADR: ${f.fileName} ${if (t is Refused) "refused (${t.message})" else "failed (${t.javaClass.simpleName})"}")
                    }
                }
                EngineerClient.msg("§aADR: done - $done of ${missing.size} uploaded.")
            } catch (t: Throwable) {
                EngineerClient.logger.error("[ec] adr catch-up failed", t)
                EngineerClient.msg("§cADR: couldn't check which runs are missing (${t.message ?: t.javaClass.simpleName}).")
            } finally {
                catchingUp = false
            }
        }
    }

    /** A run's recorder and start time, from its first line; null for an empty or unreadable file. */
    private fun runKey(file: Path): String? = runCatching {
        BufferedReader(InputStreamReader(GZIPInputStream(Files.newInputStream(file)), Charsets.UTF_8)).use { r ->
            val meta = JsonParser.parseString(r.readLine() ?: return null).asJsonObject
            if (meta["k"]?.asString != "meta") return null
            meta["self"].asString + "|" + meta["startMs"].asLong
        }
    }.getOrNull()

    override fun onDisable() {
        session?.finish()
        session = null
        OnlineLink.close()
        super.onDisable()
    }
}

/** A boss bar packet into the recording (HudCapture.bossBar). */
private class BossBars(private val s: RunRecorder) : ClientboundBossEventPacket.Handler {
    override fun add(id: java.util.UUID, name: Component, progress: Float, color: net.minecraft.world.BossEvent.BossBarColor, overlay: net.minecraft.world.BossEvent.BossBarOverlay, darkenScreen: Boolean, playMusic: Boolean, createWorldFog: Boolean) =
        s.hud.bossBar(s.currentTick, id, name, progress, false)
    override fun remove(id: java.util.UUID) = s.hud.bossBar(s.currentTick, id, null, null, true)
    override fun updateProgress(id: java.util.UUID, progress: Float) = s.hud.bossBar(s.currentTick, id, null, progress, false)
    override fun updateName(id: java.util.UUID, name: Component) = s.hud.bossBar(s.currentTick, id, name, null, false)
}
