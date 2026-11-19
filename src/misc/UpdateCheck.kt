package com.engineerclient.misc

import com.engineerclient.EngineerClient
import com.google.gson.JsonParser
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import net.fabricmc.loader.api.metadata.ModOrigin
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration

/**
 * Tells you when a newer ADM is out. promote.sh puts update.json next to each release's jar
 * (releases/download/release-<mc>/update.json): the newest version, a short note, and the versions below which an update
 * is hard or immediate. Read at launch and every 15 minutes; a failed read says nothing.
 *  - soft: one chat line on joining a server, once per new version;
 *  - hard: a red line on joining a server, every launch until updated;
 *  - immediate: at once, mid-game too (a red line, a title and a ping), and again on every server join.
 * Off in dev builds (their version is a date and commit, and they update themselves). /adm update: the jar's link.
 */
object UpdateCheck {
    enum class Level { NONE, SOFT, HARD, NOW }

    class Update(val version: String, val note: String, val hard: String, val now: String, val sha256: String) {
        constructor(version: String, note: String, hard: String, now: String) : this(version, note, hard, now, "")
    }

    private const val REPO = "https://github.com/undonecoffee/adm"
    private val mc by lazy { FabricLoader.getInstance().getModContainer("minecraft").map { it.metadata.version.friendlyString }.orElse("") }
    private val mine by lazy { FabricLoader.getInstance().getModContainer("engineerclient").map { it.metadata.version.friendlyString }.orElse("") }
    private val jarUrl get() = "$REPO/releases/download/release-$mc/ADM-$mc.jar"
    private val seenFile by lazy { FabricLoader.getInstance().configDir.resolve("engineerclient").resolve("update-seen.txt") }

    @Volatile private var found: Update? = null
    /** This launch: whether a soft or hard line was given, and which version's immediate alert went up. */
    private var toldThisLaunch = false
    private var alerted: String? = null

    private val VERSION = Regex("""\d+(\.\d+)*""")

    /** -1, 0 or 1 as [a] is older than, the same as or newer than [b] (dotted numbers). */
    fun compare(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }; val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) { val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 }); if (d != 0) return d }
        return 0
    }

    /** How hard [u] presses an install at [version]. */
    fun level(version: String, u: Update): Level = when {
        compare(version, u.version) >= 0 -> Level.NONE
        u.now.isNotEmpty() && compare(version, u.now) < 0 -> Level.NOW
        u.hard.isNotEmpty() && compare(version, u.hard) < 0 -> Level.HARD
        else -> Level.SOFT
    }

    /** update.json's text, or null if it isn't one. */
    fun parse(text: String): Update? = runCatching {
        val o = JsonParser.parseString(text).asJsonObject
        fun s(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        val v = s("version"); val hard = s("hard"); val now = s("now")
        if (!VERSION.matches(v) || (hard.isNotEmpty() && !VERSION.matches(hard)) || (now.isNotEmpty() && !VERSION.matches(now))) return null
        Update(v, s("note").replace(Regex("§."), "").take(160), hard, now, s("sha256").lowercase().takeIf { SHA256.matches(it) }.orEmpty())
    }.getOrNull()

    private val SHA256 = Regex("[0-9a-f]{64}")

    fun install() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            for (name in EngineerClient.COMMANDS) dispatcher.register(literal(name).then(literal("update").executes { sendLink(); 1 }
                .then(literal("install").executes { download(); 1 })))
        }
        EngineerClient.HELP += "§f /adm update§7 — the link to the newest ADM jar"
        EngineerClient.HELP += "§f /adm update install§7 — downloads it and swaps it in when the game closes"
        if (!VERSION.matches(mine) || mc.isEmpty()) return
        ClientPlayConnectionEvents.JOIN.register { _, _, client -> client.execute { EngineerClient.safely("update check") { onJoin() } } }
        Thread.ofVirtual().name("adm-update").start {
            val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build()
            while (true) {
                runCatching {
                    val req = HttpRequest.newBuilder(URI.create("$REPO/releases/download/release-$mc/update.json")).timeout(Duration.ofSeconds(15)).GET().build()
                    val res = http.send(req, HttpResponse.BodyHandlers.ofString())
                    if (res.statusCode() == 200) parse(res.body())?.let { u ->
                        found = u
                        if (level(mine, u) == Level.NOW) EngineerClient.mc.execute { EngineerClient.safely("update alert") { if (EngineerClient.mc.player != null) alert(u) } }
                    }
                }
                Thread.sleep(15 * 60_000L)
            }
        }
    }

    private fun onJoin() {
        val u = found ?: return
        when (level(mine, u)) {
            Level.NONE -> {}
            Level.SOFT -> if (!toldThisLaunch && seen() != u.version) { toldThisLaunch = true; markSeen(u.version); tell(u, "§e") }
            Level.HARD -> if (!toldThisLaunch) { toldThisLaunch = true; tell(u, "§c") }
            Level.NOW -> if (alerted != u.version) alert(u) else tell(u, "§c§l")
        }
    }

    private fun alert(u: Update) {
        if (alerted == u.version) return
        alerted = u.version
        tell(u, "§c§l")
        com.adm.utils.alert("§c§lUpdate ADM")
    }

    private fun tell(u: Update, colour: String) {
        val line = Component.literal("$colour ADM ${u.version} is out §7(you have $mine)$colour" + if (u.note.isNotEmpty()) ": §f${u.note} " else " ")
        EngineerClient.msg(line.append(link()))
    }

    private fun link(): Component = Component.literal("§b§n[Download]").withStyle { s ->
        s.withClickEvent(ClickEvent.OpenUrl(URI.create(jarUrl))).withHoverEvent(HoverEvent.ShowText(Component.literal("§7$jarUrl")))
    }

    /** /adm update: the newest jar's link, and whether you have it. */
    private fun sendLink() {
        val u = found
        val state = when {
            !VERSION.matches(mine) -> "§7(a dev build: it updates itself)"
            u == null -> "§7(you have $mine)"
            level(mine, u) == Level.NONE -> "§a(you have the newest, $mine)"
            else -> "§e(${u.version} is out, you have $mine)"
        }
        EngineerClient.msg(Component.literal("§7ADM jar: ").append(link()).append(Component.literal(" $state")))
        EngineerClient.msg(Component.literal("§8 $jarUrl"))
    }

    @Volatile private var staged: Path? = null

    /** /adm update install: the newest jar, checked against update.json's SHA-256, put in place of this one on exit. */
    private fun download() {
        val u = found
        val current = FabricLoader.getInstance().getModContainer("engineerclient").map { it.origin }.orElse(null)
            ?.takeIf { it.kind == ModOrigin.Kind.PATH }?.paths?.singleOrNull()?.takeIf { it.toString().endsWith(".jar") }
        when {
            !VERSION.matches(mine) || current == null -> return EngineerClient.msg("§7A dev build updates itself.")
            u == null -> return EngineerClient.msg("§7No update info yet; try again in a moment.")
            level(mine, u) == Level.NONE -> return EngineerClient.msg("§aYou have the newest, $mine.")
            u.sha256.isEmpty() -> return EngineerClient.msg("§cThis release has no checksum; download it by hand: ").also { sendLink() }
            staged != null -> return EngineerClient.msg("§a${u.version} is ready and goes in when the game closes.")
        }
        val jar = current ?: return
        val up = u ?: return
        EngineerClient.msg("§7Downloading ADM ${up.version}...")
        Thread.ofVirtual().name("adm-update-download").start {
            val result = runCatching {
                val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build()
                val res = http.send(HttpRequest.newBuilder(URI.create(jarUrl)).timeout(Duration.ofMinutes(2)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
                check(res.statusCode() == 200) { "HTTP ${res.statusCode()}" }
                val bytes = res.body()
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                check(hash == up.sha256) { "checksum does not match" }
                val dir = FabricLoader.getInstance().configDir.resolve("engineerclient")
                Files.createDirectories(dir)
                val file = dir.resolve("update.jar")
                Files.write(file, bytes)
                file
            }
            EngineerClient.mc.execute {
                result.onSuccess { file ->
                    if (staged == null) Runtime.getRuntime().addShutdownHook(Thread({ swap(jar) }, "adm-update-swap"))
                    staged = file
                    EngineerClient.msg("§aADM ${up.version} downloaded; it goes in when the game closes.")
                }.onFailure { EngineerClient.msg("§cUpdate failed: ${it.message}") }
            }
        }
    }

    private fun swap(current: Path) {
        val file = staged ?: return
        try {
            Files.move(file, current, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            // Windows keeps the running jar locked until the game has exited: a small process waits for that and moves it.
            if (!System.getProperty("os.name").startsWith("Windows")) return
            val pid = ProcessHandle.current().pid()
            fun q(p: Path) = "'" + p.toString().replace("'", "''") + "'"
            val script = "Wait-Process -Id $pid -ErrorAction SilentlyContinue; Start-Sleep -Milliseconds 500; Move-Item -Force -LiteralPath ${q(file)} -Destination ${q(current)}"
            runCatching { ProcessBuilder("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", script).start() }
        }
    }

    private fun seen(): String? = runCatching { Files.readString(seenFile).trim() }.getOrNull()
    private fun markSeen(v: String) { runCatching { Files.createDirectories(seenFile.parent); Files.writeString(seenFile, v) } }
}
