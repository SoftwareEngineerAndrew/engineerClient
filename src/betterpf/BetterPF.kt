package com.engineerclient.betterpf

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
 * Better PF, step 1: record everything about a dungeon run, from instance load to leaving, so it
 * can later be replayed/simulated in the browser (tools/betterpf-viewer).
 *
 * A session starts on every world load while this is on; [RunRecorder] keeps it only if the world
 * turns out to be a dungeon. Runs land in config/engineerclient/betterpf/runs/.
 */
object BetterPF : Module(
    name = "Better PF",
    category = Category.custom("Engineer Client", 860, 10),
    description = "Records everything about each dungeon run (players, mobs, blocks, chat, rooms) for replaying it in the browser.",
    key = null,
) {
    private val captureGeometry by BooleanSetting("Capture Geometry", true, desc = "Records each run's doorways (two blocks per door spot) - the rooms themselves come from the viewer's room library, which has them all.")
    private val uploadRuns by BooleanSetting("Upload Runs", true, desc = "Uploads each finished run to the Better PF viewer (undonecoffee.com/betterpf), where it can be replayed. Turn on Private Runs to keep them off the public list.")
    private val privateRuns by BooleanSetting("Private Runs", false, desc = "Uploaded runs aren't listed on the viewer's home page: only people you give the link to can open them. /betterpf gives you a link to all your runs, private ones included.")
    private val hidePrivateChats by BooleanSetting("Hide Private Chats", true, desc = "Leaves private messages, guild, officer and co-op chat, and friends coming online out of recordings, so they are never saved or uploaded. Party chat stays in.")
    // The chat lines each run brings. Errors (a failed upload or save) always show.
    val recordingMessage by BooleanSetting("Recording Message", true, desc = "Says \"recording this run\" in chat when a run starts being recorded.")
    val savedMessage by BooleanSetting("Saved Message", true, desc = "Says \"saved run\" in chat, with the file's size, when a run's recording is saved.")
    private val tidyRuns by BooleanSetting("Tidy Runs Folder", true, desc = "Once a game, runs here older than 30 days that the viewer has are deleted from this computer - the site keeps every run for good. Recordings a crash left unfinished are always saved as runs.")
    private val uploadedMessage by BooleanSetting("Uploaded Message", true, desc = "Says in chat, with the link, when a run has been uploaded. A failed upload is always said.")
    /**
     * A secret this install sends with every upload, so the site can list this player's runs -
     * private ones included - for whoever has it: the link /betterpf gives. Made on first use.
     */
    private var ownerToken by StringSetting("Owner Token", "", 64, desc = "Identifies your uploads for /betterpf's link.", placeholder = "").hide()

    private fun token(): String {
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

    private val uploadMissing by ActionSetting("Upload Missing Runs", desc = "Uploads every run saved on this computer that the viewer doesn't have yet - ones whose upload failed, or that were recorded with uploading off. One at a time, with progress in chat.") { uploadMissing() }

    const val SITE = "undonecoffee.com"
    private const val RUNS_URL = "https://$SITE/betterpf/api/runs"
    private val CONTROL_CODES = Regex("\u00a7.")
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    private var session: RunRecorder? = null
    private val runsDir get() = EngineerClient.mc.gameDirectory.toPath().resolve("config").resolve("engineerclient").resolve("betterpf").resolve("runs")

    init {
        on<LevelEvent.Load> {
            if (!tidied) { tidied = true; tidyRunsFolder() }
            EngineerClient.safely("betterpf start") {
                session?.finish()
                RoomKeys.reset()
                session = RunRecorder(runsDir, captureGeometry, ::upload)
            }
        }

        on<LevelEvent.Unload> {
            EngineerClient.safely("betterpf end") { session?.finish(); session = null }
        }

        on<TickEvent.End> {
            val s = session ?: return@on
            EngineerClient.safely("betterpf tick") { s.onTick(level) }
            (EngineerClient.mc.gui.screen() as? AbstractContainerScreen<*>)?.let { screen ->
                EngineerClient.safely("betterpf gui tick") { s.onContainerTick(screen.menu.slots.map { it.item }, screen.menu.carried) }
            }
            if (s.abandoned) session = null
        }

        // Every rendered frame: your own camera, so POV replays show exactly what you saw.
        on<RenderExtractEvent> {
            val s = session ?: return@on
            val player = EngineerClient.mc.player ?: return@on
            val pt = EngineerClient.mc.deltaTracker.getGameTimeDeltaPartialTick(true)
            EngineerClient.safely("betterpf frame") { s.onFrame(pt, player.yRot, player.xRot) }
            // The mouse over an open container, relative to its window (GUI pixels).
            (EngineerClient.mc.gui.screen() as? AbstractContainerScreen<*>)?.let { screen ->
                EngineerClient.safely("betterpf gui mouse") {
                    val mc = EngineerClient.mc
                    val box = screen as ContainerScreenAccessor
                    s.onContainerMouse(pt, (mc.mouseHandler.getScaledXPos(mc.window) - box.betterpfLeftPos()).toFloat(), (mc.mouseHandler.getScaledYPos(mc.window) - box.betterpfTopPos()).toFloat())
                }
            }
        }

        // Your Simon Says clicks, after Odin has had its say (so blocked ones are known).
        on<BlockInteractEvent>(priority = -1) {
            val s = session ?: return@on
            if (s.isSsButton(pos)) EngineerClient.safely("betterpf ss click") { s.onSsClick(pos, isCancelled) }
        }
        on<BlockUpdateEvent> { EngineerClient.safely("betterpf block") { session?.onBlockUpdate(pos, updated) } }
        // Server ticks (Odin's, from the server's per-tick ping): the server's own clock, which falls
        // behind the client's 20 a second when the server lags (what split timers and tick timers use).
        on<TickEvent.Server> { EngineerClient.safely("betterpf server tick") { session?.onServerTick() } }
        // Teleports the server puts you through (etherwarp, leaps, the Teleport Maze pads...).
        onReceive<ClientboundPlayerPositionPacket> {
            val player = EngineerClient.mc.player
            val abs = if (player != null) PositionMoveRotation.calculateAbsolute(PositionMoveRotation.of(player), change(), relatives()) else change()
            val rel = relatives().map { it.name }
            EngineerClient.mc.execute { EngineerClient.safely("betterpf tp") { session?.onTeleport(abs.position().x, abs.position().y, abs.position().z, abs.yRot(), abs.xRot(), if (player == null) rel else emptyList()) } }
        }
        // Bats hit or killed (secret bats: their squeak is quieter than any other bat's).
        onReceive<ClientboundSoundPacket> {
            val sound = getSound().value()
            // And every sound at the Simon Says device: the start button's presses don't show as a block change.
            if (getX() in 105.0..116.0 && getY() in 116.0..127.0 && getZ() in 87.0..99.0) {
                val id = sound.location().toString(); val x = getX(); val y = getY(); val z = getZ(); val v = getVolume(); val p = getPitch()
                EngineerClient.mc.execute { EngineerClient.safely("betterpf ss sound") { session?.onSsSound(id, x, y, z, v, p) } }
            }
            if (sound != SoundEvents.BAT_HURT && sound != SoundEvents.BAT_DEATH) return@onReceive
            val x = getX(); val y = getY(); val z = getZ(); val v = getVolume()
            EngineerClient.mc.execute { EngineerClient.safely("betterpf bat sound") { session?.onBatSound(x, y, z, v) } }
        }
        // Items picked up, and who picked them up.
        onReceive<ClientboundTakeItemEntityPacket> {
            val item = getItemId(); val by = getPlayerId()
            EngineerClient.mc.execute { EngineerClient.safely("betterpf pickup") { session?.onPickup(item, by) } }
        }
        // Chat straight off the network, before any mod can hide it (chat cleaners hide the terminal /
        // device / gate messages the report needs). Handed to the client thread, where ticks happen.
        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (overlay) return@onReceive
            val text = content.string.replace(CONTROL_CODES, "")
            val colored = legacyText(content)
            if (hidePrivateChats && PRIVATE_CHAT.containsMatchIn(text)) return@onReceive
            val n = session?.serverTickCount
            EngineerClient.mc.execute { EngineerClient.safely("betterpf chat") { session?.onChat(text, colored, n) } }
        }
        // Chests opening and closing (the lid's block event: how many players have it open), so the
        // viewer can open the chests people looted.
        onReceive<ClientboundBlockEventPacket> {
            if (b0 != 1 || (block != Blocks.CHEST && block != Blocks.TRAPPED_CHEST && block != Blocks.ENDER_CHEST)) return@onReceive
            val at = pos.immutable(); val open = b1
            EngineerClient.mc.execute { EngineerClient.safely("betterpf chest") { session?.onChestEvent(at, open) } }
        }
        on<RoomEnterEvent> { EngineerClient.safely("betterpf room") { session?.onRoomEnter(room?.name) } }

        // Container screens you open (terminal GUIs among them), for exact terminal times.
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is AbstractContainerScreen<*>) return@register
            EngineerClient.safely("betterpf gui") {
                val s = session ?: return@safely
                val menu = screen.menu
                val box = screen as ContainerScreenAccessor
                // Your own inventory's menu has no registered type.
                val type = if (menu is InventoryMenu) "inventory" else runCatching { BuiltInRegistries.MENU.getKey(menu.type)?.toString() }.getOrNull() ?: "unknown"
                s.onContainerOpen(screen.title.string, type, box.betterpfImageWidth(), box.betterpfImageHeight(), menu.slots.map { intArrayOf(it.x, it.y) })
                s.onContainerTick(menu.slots.map { it.item }, menu.carried)
            }
            ScreenMouseEvents.afterMouseClick(screen).register { _, click, _ ->
                EngineerClient.safely("betterpf gui click") {
                    val box = screen as ContainerScreenAccessor
                    session?.onContainerClick((click.x() - box.betterpfLeftPos()).toFloat(), (click.y() - box.betterpfTopPos()).toFloat(), click.button())
                }
                false
            }
            ScreenEvents.remove(screen).register { EngineerClient.safely("betterpf gui close") { session?.onGuiClose() } }
        }
    }

    /** A container slot click sent to the server (from GameModeRecordMixin, whatever sent it). */
    fun onSlotClick(slot: Int, button: Int, type: String) = EngineerClient.safely("betterpf slot click") { session?.onSlotClick(slot, button, type) }

    /** A block you right-clicked (from GameModeRecordMixin). */
    fun onBlockUse(pos: BlockPos) = EngineerClient.safely("betterpf use") { session?.onBlockUse(pos) }

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
        if (!uploadRuns) return
        Thread.ofPlatform().name("betterpf-upload").daemon(true).priority(Thread.MIN_PRIORITY).start {
            try {
                // Party members recording the same run take turns, a little apart, so the first one's
                // recording is on the site when the next ones look for it (see [send]).
                val scan = UploadPacker.scan(file, privateRuns)
                staggerForParty(scan.summary)
                val id = send(file, scan)
                uploadFailSaid = false
                if (uploadedMessage) EngineerClient.msg("§7Better PF: uploaded${if (privateRuns) " privately" else ""} - §f$SITE/betterpf/$id")
            } catch (t: Throwable) {
                if (t !is Refused) EngineerClient.logger.error("[ec] betterpf upload failed", t)
                else EngineerClient.logger.warn("[ec] betterpf upload refused: ${t.message}")
                // Said once, not after every run while the site can't be reached; said again after
                // an upload has gone through. Upload Missing Runs sends them later.
                if (!uploadFailSaid) {
                    uploadFailSaid = true
                    EngineerClient.msg("§cBetter PF: upload ${if (t is Refused) "refused (${t.message})" else "failed - the site can't be reached"}. Runs are still saved here; §fUpload Missing Runs§c sends them later.")
                }
            }
        }
    }

    private class Refused(message: String) : Exception(message)

    /**
     * Waits 20 s for each party member whose name sorts before yours: whoever sorts first uploads
     * straight away, and the others find their recording there to leave out what it already has.
     */
    private fun staggerForParty(summary: JsonObject) {
        val self = summary["self"]?.asString ?: return
        val names = summary["party"]?.asJsonArray?.mapNotNull { runCatching { it.asJsonArray[0].asString }.getOrNull() }?.sortedBy { it.lowercase() } ?: return
        val rank = names.indexOfFirst { it.equals(self, ignoreCase = true) }
        if (rank > 0) Thread.sleep(rank * 20_000L)
    }

    /**
     * Another party member's recording of this run already on the site (the earliest), to leave out
     * what it has: its bytes, or null.
     */
    private fun siblingOf(summary: JsonObject): ByteArray? {
        // (The site only offers public runs.)
        return runCatching {
            val res = http.send(HttpRequest.newBuilder(URI.create("$RUNS_URL/sibling")).header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.ofString(summary.toString())).build(), HttpResponse.BodyHandlers.ofString())
            val id = JsonParser.parseString(res.body()).asJsonObject["id"]?.takeIf { !it.isJsonNull }?.asString ?: return null
            val got = http.send(HttpRequest.newBuilder(URI.create("$RUNS_URL/$id")).timeout(Duration.ofMinutes(2)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
            got.body().takeIf { got.statusCode() == 200 }
        }.onFailure { EngineerClient.logger.warn("[ec] betterpf: sibling lookup failed", it) }.getOrNull()
    }

    /** Sends one run, on the calling thread. Its id on the site. */
    private fun send(file: Path, scan: UploadPacker.Scan = UploadPacker.scan(file, privateRuns)): String {
        val summary = scan.summary
        // Without the mobs a party member's recording already on the site has (UploadPacker), as xz.
        val (packed, left) = UploadPacker.pack(file, scan, siblingOf(summary))
        if (left > 0) EngineerClient.logger.info("[ec] betterpf: $left mobs left out, already uploaded by a party member")
        try {
            return sendPacked(packed, summary)
        } finally {
            Files.deleteIfExists(packed)
        }
    }

    private fun sendPacked(file: Path, summary: JsonObject): String {
        // The site checks it, and takes a limited number an hour (more for solo runs).
        val req = HttpRequest.newBuilder(URI.create(RUNS_URL))
            .header("X-Run-Summary", summary.toString())
            .header("X-Run-Owner", token())
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
     * far as they got), and with Tidy Runs Folder, runs older than [KEEP_DAYS] days that the site
     * has (asked by recorder and start time, as Upload Missing Runs does) are deleted here.
     */
    private fun tidyRunsFolder() {
        Thread.ofPlatform().name("betterpf-tidy").daemon(true).priority(Thread.MIN_PRIORITY).start {
            try {
                if (!Files.isDirectory(runsDir)) return@start
                val now = System.currentTimeMillis()
                // (one still being written is touched every few seconds)
                val parts = Files.list(runsDir).use { s -> s.filter { PART_NAME.matches(it.fileName.toString()) }.toList() }
                for (part in parts) if (now - Files.getLastModifiedTime(part).toMillis() > 30 * 60_000L) salvage(part)
                if (!tidyRuns) return@start
                val old = Files.list(runsDir).use { s ->
                    s.filter { it.fileName.toString().endsWith(".jsonl.gz") && now - Files.getLastModifiedTime(it).toMillis() > KEEP_DAYS * 86_400_000L }.toList()
                }
                if (old.isEmpty()) return@start
                val keys = old.associateWith { runKey(it) }
                val ask = JsonArray().also { arr -> keys.values.filterNotNull().forEach { arr.add(it) } }
                val have = http.send(HttpRequest.newBuilder(URI.create("$RUNS_URL/have")).header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.ofString(ask.toString())).build(), HttpResponse.BodyHandlers.ofString())
                if (have.statusCode() != 200) return@start
                val onSite = JsonParser.parseString(have.body()).asJsonArray.mapTo(HashSet()) { it.asString }
                val gone = old.filter { f -> keys[f]?.let { it in onSite } == true && Files.deleteIfExists(f) }
                if (gone.isNotEmpty()) EngineerClient.logger.info("[ec] betterpf: deleted ${gone.size} runs over $KEEP_DAYS days old that the viewer has")
            } catch (t: Throwable) {
                EngineerClient.logger.warn("[ec] betterpf: tidying the runs folder failed", t)
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
        EngineerClient.logger.info("[ec] betterpf: saved $lines lines of an unfinished recording as ${target.fileName}")
    }

    /**
     * Uploads every finished run in the runs folder the site doesn't have. A run is on the site if a
     * run there has the same recorder and start time — private ones included, asked for by name.
     * The one being recorded is still a .part file, so it is never picked up. Runs even with Upload
     * Runs off - pressing the button is the ask.
     */
    /**
     * /betterpf: the link to every run you have uploaded, private ones included. Runs uploaded
     * before uploads carried your token are claimed first - named by recorder and start time, which
     * only this computer knows - so the list has them too.
     */
    fun myRunsLink() {
        val token = token()
        Thread.ofVirtual().name("betterpf-mine").start {
            try {
                val local = Files.list(runsDir).use { s -> s.filter { it.fileName.toString().endsWith(".jsonl.gz") }.toList() }
                val runs = JsonArray().also { arr -> local.mapNotNull { runKey(it) }.forEach { arr.add(it) } }
                val body = JsonObject().apply { addProperty("owner", token); add("runs", runs) }
                http.send(HttpRequest.newBuilder(URI.create("$RUNS_URL/claim")).header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString())
            } catch (t: Throwable) {
                EngineerClient.logger.warn("[ec] betterpf: claiming older runs failed", t)
            }
            val url = "https://$SITE/betterpf/?mine=$token"
            EngineerClient.msg(Component.literal("§7Better PF: ").append(
                Component.literal("§b§nyour runs").withStyle { s ->
                    s.withClickEvent(net.minecraft.network.chat.ClickEvent.OpenUrl(URI.create(url)))
                        .withHoverEvent(net.minecraft.network.chat.HoverEvent.ShowText(Component.literal("§7$url")))
                }
            ).append(Component.literal(" §8(private ones too - keep this link to yourself)")))
        }
    }

    @Volatile private var uploadFailSaid = false

    private fun uploadMissing() {

        if (catchingUp) return EngineerClient.msg("§7Better PF: already uploading missing runs.")
        catchingUp = true
        Thread.ofVirtual().name("betterpf-catch-up").start {
            try {
                // Asks by name rather than reading the run list, which leaves private runs off.
                val local = Files.list(runsDir).use { s -> s.filter { it.fileName.toString().endsWith(".jsonl.gz") }.sorted().toList() }
                val keys = local.associateWith { runKey(it) }
                val ask = JsonArray().also { arr -> keys.values.filterNotNull().forEach { arr.add(it) } }
                val have = http.send(HttpRequest.newBuilder(URI.create("$RUNS_URL/have")).header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.ofString(ask.toString())).build(), HttpResponse.BodyHandlers.ofString())
                if (have.statusCode() != 200) throw Refused("run check ${have.statusCode()}")
                val onSite = JsonParser.parseString(have.body()).asJsonArray.mapTo(HashSet()) { it.asString }
                val missing = local.filter { f -> keys[f]?.let { it !in onSite } ?: false }
                if (missing.isEmpty()) return@start EngineerClient.msg("§7Better PF: all ${local.size} runs here are already on the viewer.")
                EngineerClient.msg("§7Better PF: ${missing.size} of ${local.size} runs aren't on the viewer - uploading them.")
                var done = 0
                for ((i, f) in missing.withIndex()) {
                    try {
                        send(f)
                        done++
                        EngineerClient.msg("§7Better PF: uploaded ${i + 1}/${missing.size} §8(${f.fileName})")
                    } catch (t: Throwable) {
                        // The site takes a limited number an hour: the rest another time.
                        if (t is Refused && t.message?.startsWith("429") == true) {
                            EngineerClient.msg("§eBetter PF: $done uploaded - that's the hourly limit. Use Upload Missing Runs again later for the other ${missing.size - done}.")
                            return@start
                        }
                        EngineerClient.msg("§cBetter PF: ${f.fileName} ${if (t is Refused) "refused (${t.message})" else "failed (${t.javaClass.simpleName})"}")
                    }
                }
                EngineerClient.msg("§aBetter PF: done - $done of ${missing.size} uploaded.")
            } catch (t: Throwable) {
                EngineerClient.logger.error("[ec] betterpf catch-up failed", t)
                EngineerClient.msg("§cBetter PF: couldn't check which runs are missing (${t.message ?: t.javaClass.simpleName}).")
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

    /**
     * Chat that is nobody else's business: private messages both ways, guild, officer and co-op
     * chat, and friends coming online. Hypixel writes them "From [RANK] name: ...", "To name: ...",
     * "Guild > ...", "Officer > ...", "Co-op > ...", "Friend > ...".
     */
    private val PRIVATE_CHAT = Regex("""^(?:(?:From|To) (?:\[[^\]]+] )?\w{1,16}: |(?:Guild|Officer|Co-op|Friend) > )""")

    override fun onDisable() {
        session?.finish()
        session = null
        super.onDisable()
    }
}
