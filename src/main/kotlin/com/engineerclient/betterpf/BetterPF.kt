package com.engineerclient.betterpf

import com.engineerclient.EngineerClient
import com.odtheking.odin.clickgui.settings.impl.ActionSetting
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.StringSetting
import com.odtheking.odin.events.BlockUpdateEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.RenderEvent
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
    category = Category.custom("Engineer Client"),
    description = "Records everything about each dungeon run (players, mobs, blocks, chat, rooms) for replaying it in the browser.",
) {
    private val captureGeometry by BooleanSetting("Capture Geometry", true, desc = "Captures each dungeon room once (every block) for the viewer's shared room library, plus the doors/walls between rooms each run. Rooms the library already has are skipped.")
    private val uploadRuns by BooleanSetting("Upload Runs", true, desc = "Uploads each finished run to the Better PF viewer (undonecoffee.com/betterpf). Needs the upload key.")
    private val privateRuns by BooleanSetting("Private Runs", false, desc = "Uploaded runs aren't listed on the viewer's home page: only people you give the link to can open them. /betterpf gives you a link to all your runs, private ones included.")
    private val hidePrivateChats by BooleanSetting("Hide Private Chats", true, desc = "Leaves private messages, guild, officer and co-op chat, and friends coming online out of recordings, so they are never saved or uploaded. Party chat stays in.")
    // The chat lines each run brings. Errors (a failed upload or save) always show.
    val recordingMessage by BooleanSetting("Recording Message", true, desc = "Says \"recording this run\" in chat when a run starts being recorded.")
    val savedMessage by BooleanSetting("Saved Message", true, desc = "Says \"saved run\" in chat, with the file's size, when a run's recording is saved.")
    private val uploadedMessage by BooleanSetting("Uploaded Message", true, desc = "Says in chat, with the link, when a run has been uploaded. A failed upload is always said.")
    /**
     * A secret this install sends with every upload, so the site can list this player's runs -
     * private ones included - for whoever has it: the link /betterpf gives. Made on first use.
     */
    private var ownerToken by StringSetting("Owner Token", "", 64, desc = "Identifies your uploads for /betterpf's link.").hide()

    private fun token(): String {
        if (ownerToken.length < 32) {
            val r = java.security.SecureRandom()
            ownerToken = (1..32).joinToString("") { "0123456789abcdef"[r.nextInt(16)].toString() }
            com.odtheking.odin.features.ModuleManager.saveConfigurations()
        }
        return ownerToken
    }

    private val uploadKey by StringSetting("Upload Key", "", 64, desc = "Key for uploading runs to the viewer. Ask undonecoffee for it.")
    private val uploadMissing by ActionSetting("Upload Missing Runs", desc = "Uploads every run saved on this computer that the viewer doesn't have yet - ones whose upload failed, or that were recorded with uploading off. One at a time, with progress in chat.") { uploadMissing() }

    /** The upload key, for other features that write to the site (BR Roles's boxes). */
    val siteKey: String get() = uploadKey.trim()

    const val SITE = "undonecoffee.com"
    private const val RUNS_URL = "https://$SITE/betterpf/api/runs"
    private const val ROOMS_URL = "https://$SITE/betterpf/api/rooms"

    /** Rooms the server's library already has ("Name|ROTATION"); null until the fetch lands. */
    @Volatile private var libraryKeys: Set<String>? = null
    private val CONTROL_CODES = Regex("\u00a7.")
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    private var session: RunRecorder? = null
    private val runsDir get() = EngineerClient.mc.gameDirectory.toPath().resolve("config").resolve("engineerclient").resolve("betterpf").resolve("runs")

    init {
        on<LevelEvent.Load> {
            EngineerClient.safely("betterpf start") {
                session?.finish()
                libraryKeys = null
                fetchLibraryKeys()
                RoomKeys.reset()
                session = RunRecorder(runsDir, captureGeometry, { libraryKeys }, ::upload)
            }
        }

        on<LevelEvent.Unload> {
            EngineerClient.safely("betterpf end") { session?.finish(); session = null }
        }

        on<TickEvent.End> {
            val s = session ?: return@on
            EngineerClient.safely("betterpf tick") { s.onTick(level) }
            (EngineerClient.mc.screen as? AbstractContainerScreen<*>)?.let { screen ->
                EngineerClient.safely("betterpf gui tick") { s.onContainerTick(screen.menu.slots.map { it.item }, screen.menu.carried) }
            }
            if (s.abandoned) session = null
        }

        // Every rendered frame: your own camera, so POV replays show exactly what you saw.
        on<RenderEvent.Last> {
            val s = session ?: return@on
            val player = EngineerClient.mc.player ?: return@on
            val pt = EngineerClient.mc.deltaTracker.getGameTimeDeltaPartialTick(true)
            EngineerClient.safely("betterpf frame") { s.onFrame(pt, player.yRot, player.xRot) }
            // The mouse over an open container, relative to its window (GUI pixels).
            (EngineerClient.mc.screen as? AbstractContainerScreen<*>)?.let { screen ->
                EngineerClient.safely("betterpf gui mouse") {
                    val mc = EngineerClient.mc
                    val box = screen as ContainerScreenAccessor
                    s.onContainerMouse(pt, (mc.mouseHandler.getScaledXPos(mc.window) - box.betterpfLeftPos()).toFloat(), (mc.mouseHandler.getScaledYPos(mc.window) - box.betterpfTopPos()).toFloat())
                }
            }
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
            EngineerClient.mc.execute { EngineerClient.safely("betterpf chat") { session?.onChat(text, colored) } }
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
        val legacy = ChatFormatting.entries.firstOrNull { it.isColor && it.color == color.value }
        return if (legacy != null) "§" + legacy.char else "§#" + String.format(java.util.Locale.ROOT, "%06x", color.value and 0xFFFFFF)
    }

    private fun fetchLibraryKeys() {
        val request = HttpRequest.newBuilder(URI.create(ROOMS_URL)).timeout(Duration.ofSeconds(15)).GET().build()
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete { res, err ->
            if (err != null || res.statusCode() != 200) return@whenComplete
            EngineerClient.safely("betterpf library keys") {
                val keys = JsonParser.parseString(res.body()).asJsonObject.getAsJsonArray("keys")
                libraryKeys = keys.map { it.asString }.toSet()
            }
        }
    }

    /**
     * Sends a finished run to the viewer, off the game thread: first the room captures the library
     * didn't have (one request each), then the run itself with its summary in a header - the site
     * stores files as-is and never unpacks them, so it needs to be told what the list shows.
     */
    private fun upload(file: Path) {
        val key = uploadKey.trim()
        if (!uploadRuns || key.isEmpty()) return
        Thread.ofVirtual().name("betterpf-upload").start {
            try {
                val id = send(file, key)
                if (uploadedMessage) EngineerClient.chat("§8[§6EC§8]§7 Better PF: uploaded${if (privateRuns) " privately" else ""} - §f$SITE/betterpf/$id")
            } catch (t: Throwable) {
                if (t !is Refused) EngineerClient.logger.error("[ec] betterpf upload failed", t)
                EngineerClient.chat("§8[§6EC§8]§c Better PF: upload ${if (t is Refused) "refused (${t.message})" else "failed (${t.javaClass.simpleName})"}. The run is still saved locally.")
            }
        }
    }

    private class Refused(message: String) : Exception(message)

    /** Sends one run, on the calling thread: its new room captures, then the run. Its id on the site. */
    private fun send(file: Path, key: String): String {
        val (summary, rooms) = readForUpload(file)
        for ((roomKey, line) in rooms) {
            val req = HttpRequest.newBuilder(URI.create(ROOMS_URL))
                .header("X-Upload-Key", key).header("X-Room-Key", roomKey).header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(2)).POST(HttpRequest.BodyPublishers.ofString(line)).build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() != 200) EngineerClient.logger.warn("[ec] betterpf: room $roomKey refused (${res.statusCode()})")
        }
        val req = HttpRequest.newBuilder(URI.create(RUNS_URL))
            .header("X-Upload-Key", key)
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
        val key = uploadKey.trim()
        Thread.ofVirtual().name("betterpf-mine").start {
            if (key.isNotEmpty()) try {
                val local = Files.list(runsDir).use { s -> s.filter { it.fileName.toString().endsWith(".jsonl.gz") }.toList() }
                val runs = JsonArray().also { arr -> local.mapNotNull { runKey(it) }.forEach { arr.add(it) } }
                val body = JsonObject().apply { addProperty("owner", token); add("runs", runs) }
                http.send(HttpRequest.newBuilder(URI.create("$RUNS_URL/claim")).header("X-Upload-Key", key).header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString())
            } catch (t: Throwable) {
                EngineerClient.logger.warn("[ec] betterpf: claiming older runs failed", t)
            }
            val url = "https://$SITE/betterpf/?mine=$token"
            EngineerClient.chat(Component.literal("§8[§6EC§8]§7 Better PF: ").append(
                Component.literal("§b§nyour runs").withStyle { s ->
                    s.withClickEvent(net.minecraft.network.chat.ClickEvent.OpenUrl(URI.create(url)))
                        .withHoverEvent(net.minecraft.network.chat.HoverEvent.ShowText(Component.literal("§7$url")))
                }
            ).append(Component.literal(" §8(private ones too - keep this link to yourself)")))
        }
    }

    private fun uploadMissing() {
        val key = uploadKey.trim()
        if (key.isEmpty()) return EngineerClient.chat("§8[§6EC§8]§c Better PF: set the Upload Key first.")
        if (catchingUp) return EngineerClient.chat("§8[§6EC§8]§7 Better PF: already uploading missing runs.")
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
                if (missing.isEmpty()) return@start EngineerClient.chat("§8[§6EC§8]§7 Better PF: all ${local.size} runs here are already on the viewer.")
                EngineerClient.chat("§8[§6EC§8]§7 Better PF: ${missing.size} of ${local.size} runs aren't on the viewer - uploading them.")
                var done = 0
                missing.forEachIndexed { i, f ->
                    try {
                        send(f, key)
                        done++
                        EngineerClient.chat("§8[§6EC§8]§7 Better PF: uploaded ${i + 1}/${missing.size} §8(${f.fileName})")
                    } catch (t: Throwable) {
                        EngineerClient.chat("§8[§6EC§8]§c Better PF: ${f.fileName} ${if (t is Refused) "refused (${t.message})" else "failed (${t.javaClass.simpleName})"}")
                    }
                }
                EngineerClient.chat("§8[§6EC§8]§a Better PF: done - $done of ${missing.size} uploaded.")
            } catch (t: Throwable) {
                EngineerClient.logger.error("[ec] betterpf catch-up failed", t)
                EngineerClient.chat("§8[§6EC§8]§c Better PF: couldn't check which runs are missing (${t.message ?: t.javaClass.simpleName}).")
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

    private const val RUN_START = "[NPC] Mort: Here, I found this map when I first entered the dungeon."
    private val RUN_END = Regex("""^\s*☠ Defeated """)
    private val KIND = Regex("""^\{"k":"([a-z]+)"""")
    private val TICK = Regex(""""t":(\d+)""")

    /** One pass over the run file: the list summary (self, startMs, floor, party, ticks) and its room captures. */
    private fun readForUpload(file: Path): Pair<JsonObject, List<Pair<String, String>>> {
        val summary = JsonObject()
        val rooms = ArrayList<Pair<String, String>>()
        var ticks = 0
        var startTick: Int? = null
        var endTick: Int? = null
        BufferedReader(InputStreamReader(GZIPInputStream(Files.newInputStream(file)), Charsets.UTF_8), 1 shl 16).useLines { lines ->
            for (line in lines) {
                val kind = KIND.find(line)?.groupValues?.get(1) ?: continue
                TICK.find(line.take(48))?.groupValues?.get(1)?.toIntOrNull()?.let { if (it > ticks) ticks = it }
                when (kind) {
                    "meta" -> JsonParser.parseString(line).asJsonObject.let { summary.add("self", it["self"]); summary.add("startMs", it["startMs"]) }
                    "floor" -> summary.add("floor", JsonParser.parseString(line).asJsonObject["floor"])
                    "party" -> summary.add("party", JsonParser.parseString(line).asJsonObject["m"])
                    "lib" -> JsonParser.parseString(line).asJsonObject["key"]?.asString?.let { rooms += it to line }
                    "chat" -> {
                        val l = JsonParser.parseString(line).asJsonObject
                        val m = l["m"]?.asString ?: continue
                        val t = l["t"]?.asInt ?: 0
                        if (startTick == null && m.startsWith(RUN_START)) startTick = t
                        else if (RUN_END.containsMatchIn(m)) endTick = t
                    }
                }
            }
        }
        if (!summary.has("floor")) summary.addProperty("floor", "")
        if (!summary.has("party")) summary.add("party", JsonArray())
        summary.addProperty("ticks", ticks)
        // Whether it is a whole run (Mort's map to "☠ Defeated") and how long it took — worked out
        // here so the server doesn't have to unpack the recording, which a big run can't afford on
        // its CPU budget (Cloudflare error 1102). The same rule the server used.
        val start = startTick; val end = endTick
        val cleared = start != null && end != null && end > start
        summary.addProperty("cleared", if (cleared) 1 else 0)
        if (privateRuns) summary.addProperty("private", 1)
        if (cleared) summary.addProperty("timeMs", (end!! - start!!) * 50L)
        return summary to rooms
    }

    override fun onDisable() {
        session?.finish()
        session = null
        super.onDisable()
    }
}
