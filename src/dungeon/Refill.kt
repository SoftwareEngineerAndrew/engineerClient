package com.engineerclient.dungeon

import com.engineerclient.EngineerClient
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.NumberSetting
import com.adm.features.Category
import com.adm.features.Module
import com.adm.utils.itemId
import com.adm.utils.sendCommand
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.minecraft.ChatFormatting
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents

object Refill : Module(
    name = "Refill",
    category = Category.DUNGEON,
    description = "/adm refill tops up the items picked here from your sacks; /adm ep, sl, sb, ij, dd, tap, twap one at a time.",
    key = null,
) {
    private class Item(val command: String, val name: String, val id: String, val sack: String, val max: Int, on: Boolean) {
        val enabled = BooleanSetting("Refill $name", on, desc = "/adm refill tops up your $name.")
        val amount = NumberSetting("$name Amount", max, 1..max, 1, desc = "How many $name to have.").withDependency { enabled.value }
    }

    private val items = listOf(
        Item("ep", "Ender Pearls", "ENDER_PEARL", "ender_pearl", 16, true),
        Item("sl", "Spirit Leaps", "SPIRIT_LEAP", "spirit_leap", 16, true),
        Item("sb", "Superboom TNT", "SUPERBOOM_TNT", "superboom_tnt", 64, true),
        Item("ij", "Inflatable Jerrys", "INFLATABLE_JERRY", "inflatable_jerry", 64, false),
        Item("dd", "Decoys", "DUNGEON_DECOY", "dungeon_decoy", 64, false),
        Item("tap", "Toxic Arrow Poison", "TOXIC_ARROW_POISON", "toxic_arrow_poison", 64, false),
        Item("twap", "Twilight Arrow Poison", "TWILIGHT_ARROW_POISON", "twilight_arrow_poison", 64, false),
    )

    /** Hypixel drops a /gfs sent too soon after another: one at a time, the next once the last is answered. */
    private const val GAP = 10
    private const val TIMEOUT = 40
    private class Pending(val item: Item, val amount: Int, var tries: Int = 0)
    private val queue = ArrayDeque<Pending>()
    private var sent: Pending? = null
    private var wait = 0
    private val movedRegex = Regex("Moved \\d+.+ from your Sacks to your inventory")

    init {
        for (i in items) { registerSetting(i.enabled); registerSetting(i.amount) }

        ClientTickEvents.END_CLIENT_TICK.register { tick() }
        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            if (!overlay && sent != null && movedRegex.containsMatchIn(ChatFormatting.stripFormatting(message.string) ?: "")) { sent = null; wait = GAP }
        }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            for (name in EngineerClient.COMMANDS) {
                var root = literal(name).then(literal("refill").executes { EngineerClient.mc.schedule { refillAll() }; 1 })
                for (i in items) root = root.then(literal(i.command)
                    .executes { EngineerClient.mc.schedule { refill(i, i.amount.value, true) }; 1 }
                    .then(argument("amount", IntegerArgumentType.integer(1, i.max)).executes { ctx ->
                        val n = IntegerArgumentType.getInteger(ctx, "amount")
                        EngineerClient.mc.schedule { refill(i, n, true) }; 1
                    }))
                dispatcher.register(root)
            }
        }
        EngineerClient.HELP += "§f /adm refill§7 — top up your Refill items; §f/adm ep§7, §fsl§7, §fsb§7, §fij§7, §fdd§7, §ftap§7, §ftwap§7 [amount]"
    }

    private fun refillAll() {
        val picked = items.filter { it.enabled.value }
        if (picked.isEmpty()) return EngineerClient.msg("§7Nothing picked in Refill.")
        if (picked.map { refill(it, it.amount.value, false) }.none { it }) EngineerClient.msg("§7Everything is already full.")
    }

    private fun tick() {
        if (wait > 0) { wait--; return }
        // no answer: sent again (what's missing is counted afresh), once
        sent?.let { if (it.tries < 2) queue.addFirst(it); sent = null }
        while (true) {
            val p = queue.removeFirstOrNull() ?: return
            val missing = p.amount - have(p.item)
            if (missing <= 0) continue
            sendCommand("gfs ${p.item.sack} $missing")
            p.tries++; sent = p; wait = TIMEOUT
            return
        }
    }

    private fun have(item: Item): Int {
        val inv = EngineerClient.mc.player?.inventory ?: return Int.MAX_VALUE
        var have = 0
        for (i in 0 until inv.containerSize) inv.getItem(i).takeIf { it.itemId == item.id }?.let { have += it.count }
        return have
    }

    /** Queues a top-up for what's missing; false if nothing is. */
    private fun refill(item: Item, amount: Int, say: Boolean): Boolean {
        val have = have(item)
        if (have >= amount) { if (say) EngineerClient.msg("§7Already have $have ${item.name}."); return false }
        queue.removeAll { it.item === item }
        if (sent?.item !== item) queue += Pending(item, amount)
        return true
    }
}
