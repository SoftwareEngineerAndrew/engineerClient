package com.engineerclient.adr

import com.engineerclient.EngineerClient
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration

/**
 * Signing in to ADR as this Minecraft account, so the site marks uploads as really this player's
 * (verified). The same proof a server gets: the site hands out a challenge, the game tells Mojang it
 * is joining "server" SHA-1(challenge) (as it does joining any online-mode server), and the site asks
 * Mojang whether this player did. It answers with a token good for 30 days, kept in
 * config/engineerclient/adr/auth.json and sent with uploads (X-ADR-Auth).
 *
 * Never in the way of an upload: no token (an offline account, Mojang or the site down) just means
 * the upload goes without one.
 */
object ADRAuth {
    private const val AUTH_URL = "https://${ADR.SITE}/adr/api/auth"
    /** A token this close to running out is replaced. */
    private const val RENEW_MS = 2 * 86_400_000L
    /** After a failed sign-in, uploads go without one for this long before it is tried again. */
    private const val RETRY_MS = 10 * 60_000L
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    private val file get() = EngineerClient.mc.gameDirectory.toPath().resolve("config").resolve("engineerclient").resolve("adr").resolve("auth.json")

    private class Token(val name: String, val token: String, val exp: Long)
    private var cached: Token? = null
    private var loaded = false
    private var failedAt = 0L

    /**
     * The token to send with an upload recorded by [self], or null: only when the game is signed in
     * as [self] (Upload Missing Runs can send runs another account recorded). Off the game thread only:
     * it can sign in, which talks to Mojang and the site.
     */
    @Synchronized
    fun headerFor(self: String): String? = try {
        val name = EngineerClient.mc.user.name
        if (!name.equals(self, ignoreCase = true)) null else current(name)
    } catch (t: Throwable) {
        EngineerClient.logger.warn("[ec] adr: sign-in failed: ${t.javaClass.simpleName}")
        failedAt = System.currentTimeMillis()
        null
    }

    private fun current(name: String): String? {
        if (!loaded) { loaded = true; cached = load() }
        val now = System.currentTimeMillis()
        cached?.let { if (it.name.equals(name, ignoreCase = true) && it.exp - RENEW_MS > now) return it.token }
        if (now - failedAt < RETRY_MS) return cached?.takeIf { it.name.equals(name, ignoreCase = true) && it.exp > now }?.token
        val fresh = signIn(name) ?: run { failedAt = now; return null }
        cached = fresh
        save(fresh)
        return fresh.token
    }

    private fun signIn(name: String): Token? {
        val user = EngineerClient.mc.user
        // (Offline and dev accounts have no real access token: Mojang turns them away below anyway.)
        val access = user.accessToken
        if (access.isNullOrBlank() || access.length < 20) return null
        val c = get("$AUTH_URL/challenge")?.get("c")?.asString ?: return null
        val serverId = MessageDigest.getInstance("SHA-1").digest(c.toByteArray()).joinToString("") { "%02x".format(it) }
        EngineerClient.mc.services().sessionService().joinServer(user.profileId, access, serverId)
        val body = JsonObject().apply { addProperty("name", user.name); addProperty("c", c) }
        val res = http.send(HttpRequest.newBuilder(URI.create(AUTH_URL)).header("Content-Type", "application/json").timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() != 200) {
            EngineerClient.logger.warn("[ec] adr: sign-in refused: ${res.statusCode()} ${res.body().trim().take(80)}")
            return null
        }
        val o = JsonParser.parseString(res.body()).asJsonObject
        return Token(o["name"].asString, o["token"].asString, o["exp"].asLong)
    }

    private fun get(url: String): JsonObject? {
        val res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString())
        return if (res.statusCode() == 200) JsonParser.parseString(res.body()).asJsonObject else null
    }

    private fun load(): Token? = runCatching {
        if (!Files.exists(file)) return null
        val o = JsonParser.parseString(Files.readString(file)).asJsonObject
        Token(o["name"].asString, o["token"].asString, o["exp"].asLong)
    }.getOrNull()

    private fun save(t: Token) {
        runCatching {
            val o = JsonObject().apply { addProperty("name", t.name); addProperty("token", t.token); addProperty("exp", t.exp) }
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling("auth.json.tmp")
            Files.writeString(tmp, o.toString())
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { EngineerClient.logger.warn("[ec] adr: couldn't save auth.json: ${it.javaClass.simpleName}") }
    }
}
