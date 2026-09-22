package com.engineerclient.price

import com.odtheking.odin.OdinMod
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.network.WebUtils
import kotlinx.coroutines.launch
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The auction house's lowest BIN for whatever item the cursor is on, as one extra tooltip line.
 *
 * Priced from Coflnet, one request per item id:
 * `sky.coflnet.com/api/item/price/<ID>/current` → `{"buy":491990000,"available":1,"isAh":true}`,
 * where `buy` is that item's lowest BIN (checked against the dedicated `/bin` endpoint — they
 * agree to the coin) and `isAh` says whether the item trades on the auction house at all.
 *
 * `isAh` is why this asks Coflnet rather than reading lore: it is the server's own answer to
 * "is this auctionable", so bazaar goods (`isAh: false`, real prices) and things with no auction
 * at all (`available: -1`, prices `0`) are both excluded without this mod having to recognise
 * soulbound wording, museum flags or any other lore text that Hypixel is free to reword.
 *
 * Why not a bulk price dump: the tooltip is rebuilt every frame while you hover, so whatever runs
 * here has to be a map lookup. It is — the network only ever happens once per item id, on Odin's
 * coroutine scope, and the line appears when the answer lands. Hypixel's own auctions endpoint
 * would be 45 pages of ~2.4 MB with the item ids buried in gzipped NBT, which is tens of
 * thousands of NBT decodes per refresh; that cost lands on the client as GC churn whatever thread
 * it runs on, so it is not worth it for one line of text.
 */
object LowestBin : Module(
    name = "Lowest BIN",
    category = Category.custom("Blood Rush"),
    description = "Shows an item's lowest auction-house BIN in its tooltip. Silent for anything not auctionable.",
    toggled = true, // existing installs have no saved state for a new module; on by default
) {
    private val showStack by BooleanSetting("Stack Total", false, desc = "On a stack, also show the whole stack's worth at that price.")

    private const val API = "https://sky.coflnet.com/api/item/price/"

    /** Prices move, so a hover a few minutes later is worth asking about again. */
    private const val TTL_MS = 5 * 60 * 1000L
    private const val RETRY_FAILED_MS = 60_000L

    /**
     * Sweeping a cursor across a chest would otherwise fire a request per slot it crosses. Only
     * an item the cursor actually settles on is worth asking about.
     */
    private const val HOVER_SETTLE_MS = 150L
    private const val MAX_IN_FLIGHT = 4

    /** Ids are bounded in practice; this only stops a very long session growing without end. */
    private const val MAX_CACHED = 4000

    private class Price(val lowest: Double, val onAuction: Boolean, val at: Long)

    private sealed interface Entry
    private object Loading : Entry
    private class Failed(val at: Long) : Entry
    private class Ready(val price: Price) : Entry

    /** Coflnet's `/current` shape; only these two fields are used. */
    private class Current(val buy: Double = 0.0, val isAh: Boolean = false)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val refreshing = ConcurrentHashMap.newKeySet<String>()
    private val inFlight = AtomicInteger(0)

    // Render-thread only: which id the cursor is on and when it arrived there.
    private var settledId = ""
    private var settledAt = 0L

    /** Returns [lines] untouched (same instance) for anything without a price to show. */
    fun decorate(stack: ItemStack, lines: List<Component>): List<Component> {
        if (!enabled) return lines
        val id = stack.itemId
        if (id.isEmpty()) return lines // a vanilla item, not a SkyBlock one
        val line = priceLine(id, stack.count) ?: return lines
        return lines + Component.literal(line)
    }

    private fun priceLine(id: String, count: Int): String? {
        val now = System.currentTimeMillis()
        if (id != settledId) {
            settledId = id
            settledAt = now
        }
        return when (val entry = cache[id]) {
            null -> {
                if (now - settledAt >= HOVER_SETTLE_MS) request(id)
                null
            }
            is Loading -> null // nothing yet; a placeholder that flickers on every item reads worse
            is Failed -> {
                if (now - entry.at > RETRY_FAILED_MS) request(id)
                null
            }
            is Ready -> {
                if (now - entry.price.at > TTL_MS && refreshing.add(id)) fetch(id)
                render(entry.price, count)
            }
        }
    }

    private fun render(price: Price, count: Int): String? {
        // Not auctionable, or auctionable with nothing listed: say nothing rather than "0".
        if (!price.onAuction || price.lowest <= 0) return null
        val head = "§7Lowest BIN: §6${coins(price.lowest)}"
        if (!showStack || count <= 1) return head
        return "$head §8· §7x$count §6${coins(price.lowest * count)}"
    }

    private fun request(id: String) {
        if (inFlight.get() >= MAX_IN_FLIGHT) return
        cache[id] = Loading
        fetch(id)
    }

    private fun fetch(id: String) {
        inFlight.incrementAndGet()
        OdinMod.scope.launch {
            val url = API + URLEncoder.encode(id, StandardCharsets.UTF_8) + "/current"
            val data = runCatching { WebUtils.fetchJson<Current>(url).getOrNull() }.getOrNull()
            when {
                data != null -> cache[id] = Ready(Price(data.buy, data.isAh, System.currentTimeMillis()))
                // A failed refresh keeps the price we had rather than dropping the line.
                cache[id] !is Ready -> cache[id] = Failed(System.currentTimeMillis())
            }
            refreshing -= id
            inFlight.decrementAndGet()
            if (cache.size > MAX_CACHED) evict()
        }
    }

    /** Drop everything past its TTL, and if that was not enough, start over. */
    private fun evict() {
        val now = System.currentTimeMillis()
        cache.entries.removeIf { (_, v) -> v !is Ready || now - v.price.at > TTL_MS }
        if (cache.size > MAX_CACHED) cache.clear()
    }

    private fun coins(v: Double): String = when {
        v >= 1_000_000_000 -> String.format(Locale.ROOT, "%.2fb", v / 1_000_000_000)
        v >= 1_000_000 -> String.format(Locale.ROOT, "%.2fm", v / 1_000_000)
        v >= 1_000 -> String.format(Locale.ROOT, "%.1fk", v / 1_000)
        else -> String.format(Locale.ROOT, "%.0f", v)
    }

    /** For the debug HUD / a command: how many item prices are known. */
    fun cached(): Int = cache.count { it.value is Ready }
}
