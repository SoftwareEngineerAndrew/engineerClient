package com.engineerclient.waypoints

import com.engineerclient.EngineerClient
import com.engineerclient.betterpf.BetterPF
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * BR Roles's boxes on the site (undonecoffee.com/brroles), where they are edited (by the site's
 * owner). One shared document, { rooms, lastId, updatedAt }, read whole; the mod only reads it.
 */
object BoxSync {

    private const val URL = "https://${BetterPF.SITE}/betterpf/api/brboxes"
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    /** Fetches the site's copy; [done] gets its JSON, off the game thread, or nothing if it failed. */
    fun pull(done: (String) -> Unit) {
        Thread.ofPlatform().daemon().name("brboxes-pull").start {
            runCatching {
                val res = http.send(HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString())
                EngineerClient.logger.info("[ec] brboxes pull: HTTP ${res.statusCode()}, ${res.body().length} chars")
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
}
