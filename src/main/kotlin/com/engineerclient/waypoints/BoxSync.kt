package com.engineerclient.waypoints

import com.engineerclient.EngineerClient
import com.engineerclient.betterpf.BetterPF
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * BR Waypoints 2's boxes on the site (undonecoffee.com/brroles), where they can be looked at and
 * edited too. One shared document, { rooms, lastId, updatedAt }, read and written whole; the newer
 * copy wins. Writing needs Better PF's upload key; without it nothing is sent.
 */
object BoxSync {

    private const val URL = "https://${BetterPF.SITE}/betterpf/api/brboxes"
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    /** Fetches the site's copy; [done] gets its JSON, off the game thread, or nothing if it failed. */
    fun pull(done: (String) -> Unit) {
        Thread.ofVirtual().name("brboxes-pull").start {
            runCatching {
                val res = http.send(HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString())
                if (res.statusCode() == 200) done(res.body())
            }.onFailure { EngineerClient.logger.warn("[ec] brboxes pull failed: ${it.message}") }
        }
    }

    private const val ROLES_URL = "https://${BetterPF.SITE}/betterpf/api/brroles"

    /** Fetches the site's roles (who kills which boxes; see [BrRoles]); [done] gets its JSON, off the game thread. */
    fun pullRoles(done: (String) -> Unit) {
        Thread.ofVirtual().name("brroles-pull").start {
            runCatching {
                val res = http.send(HttpRequest.newBuilder(URI.create(ROLES_URL)).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString())
                if (res.statusCode() == 200) done(res.body())
            }.onFailure { EngineerClient.logger.warn("[ec] brroles pull failed: ${it.message}") }
        }
    }

    /** Sends [json] as the site's copy; [done] gets the site's new updatedAt. False if there is no key. */
    fun push(json: String, done: (Long) -> Unit): Boolean {
        val key = BetterPF.siteKey
        if (key.isEmpty()) return false
        Thread.ofVirtual().name("brboxes-push").start {
            runCatching {
                val req = HttpRequest.newBuilder(URI.create(URL)).header("X-Upload-Key", key).header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30)).PUT(HttpRequest.BodyPublishers.ofString(json)).build()
                val res = http.send(req, HttpResponse.BodyHandlers.ofString())
                if (res.statusCode() == 200) done(Regex(""""updatedAt":(\d+)""").find(res.body())?.groupValues?.get(1)?.toLong() ?: 0L)
                else EngineerClient.logger.warn("[ec] brboxes push refused (${res.statusCode()}: ${res.body().take(80)})")
            }.onFailure { EngineerClient.logger.warn("[ec] brboxes push failed: ${it.message}") }
        }
        return true
    }
}
