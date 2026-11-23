package com.engineerclient.misc

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.StringSetting
import com.adm.events.TickEvent
import com.adm.events.core.on
import com.adm.features.Category
import com.adm.features.Module
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.StringRange
import com.mojang.brigadier.suggestion.Suggestion
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
import java.util.concurrent.CompletableFuture

object CommandAliases : Module(
    name = "Command Aliases",
    category = Category.SKYBLOCK,
    description = "Your own short commands: /alias runs its command, with anything typed after it.",
    toggled = true,
    key = null,
) {
    private const val SLOTS = 20
    private const val MAX_DEPTH = 5

    private class Slot(val alias: StringSetting, val command: StringSetting) {
        var words: List<String> = emptyList()
        var seenAlias: String? = null
        var seenCommand: String? = null
        fun used() = alias.value.isNotBlank() || command.value.isNotBlank()
        fun live() = words.isNotEmpty() && command.value.isNotBlank()
    }

    private val slots = ArrayList<Slot>()
    // Aliases being expanded right now, so a chain that comes back to one of them stops there.
    private val active = ArrayList<Slot>()
    private var suggesting = false
    private var lastTyped = ""
    private var lastRunAt = 0L
    // Every live alias and each of its leading words, so nodes of a removed or renamed alias stop matching.
    private var livePrefixes = HashSet<String>()
    private val ownRoots = HashSet<String>()
    private var ownRootsIn: CommandDispatcher<FabricClientCommandSource>? = null
    private var quietTicks = 0

    init {
        for (i in 1..SLOTS) {
            val first = i == 1
            val previous = slots.lastOrNull()
            val shown = { previous == null || previous.used() }
            val alias = StringSetting("Alias $i", if (first) "d" else "", 32, desc = "What you type, without the slash; can be several words.", placeholder = "d")
            val command = StringSetting("Command $i", if (first) "warp dungeon_hub" else "", 200, desc = "What Alias $i runs.", placeholder = "warp dungeon_hub")
            registerSetting(alias.withDependency(shown))
            registerSetting(command.withDependency(shown))
            slots += Slot(alias, command)
        }

        ClientSendMessageEvents.ALLOW_COMMAND.register { typed -> !(enabled && run(typed)) }

        // Registered as client commands only for tab completion.
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ -> recompute(); register(dispatcher) }

        on<TickEvent.End> {
            var changed = false
            for (slot in slots) if (slot.alias.value !== slot.seenAlias || slot.command.value !== slot.seenCommand) changed = true
            if (changed) {
                recompute()
                quietTicks = 10
            } else if (quietTicks > 0 && --quietTicks == 0) reregister()
        }
    }

    private fun recompute() {
        val prefixes = HashSet<String>()
        for (slot in slots) {
            slot.seenAlias = slot.alias.value
            slot.seenCommand = slot.command.value
            slot.words = slot.alias.value.trim().removePrefix("/").split(' ').filter { it.isNotEmpty() }
            if (slot.live()) for (n in 1..slot.words.size) prefixes += slot.words.subList(0, n).joinToString(" ")
        }
        livePrefixes = prefixes
    }

    // Brigadier can't drop nodes, so stale ones stay behind their requirement and the tree is resent for completion.
    private fun reregister() {
        val dispatcher = ClientCommands.getActiveDispatcher() ?: return
        if (mc.connection == null) return
        register(dispatcher)
        runCatching { ClientCommands.refreshCommandCompletions() }
    }

    private fun register(dispatcher: CommandDispatcher<FabricClientCommandSource>) {
        if (ownRootsIn !== dispatcher) { ownRoots.clear(); ownRootsIn = dispatcher }
        for (slot in slots) {
            if (!slot.live()) continue
            val words = slot.words
            if (words[0] !in ownRoots && dispatcher.root.getChild(words[0]) != null) continue
            ownRoots += words[0]
            val full = words.joinToString(" ")
            var node: LiteralArgumentBuilder<FabricClientCommandSource> = literal(words.last())
                .requires { full in livePrefixes }
                .executes { if (enabled) run(full); 1 }
                .then(argument("args", StringArgumentType.greedyString())
                    .suggests { _, builder -> if (enabled) find(full)?.let { suggest(it, builder) } ?: builder.buildFuture() else builder.buildFuture() }
                    .executes { ctx -> if (enabled) run("$full ${StringArgumentType.getString(ctx, "args")}"); 1 })
            for (i in words.size - 2 downTo 0) {
                val prefix = words.subList(0, i + 1).joinToString(" ")
                node = literal(words[i]).requires { prefix in livePrefixes }.then(node)
            }
            dispatcher.register(node)
        }
    }

    private fun find(full: String) = slots.firstOrNull { it.live() && it.words.joinToString(" ") == full }

    /** Where the alias ends in [typed], or -1 when [typed] doesn't start with it. */
    private fun aliasEnd(slot: Slot, typed: String): Int {
        var at = 0
        for (word in slot.words) {
            while (at < typed.length && typed[at] == ' ') at++
            if (!typed.regionMatches(at, word, 0, word.length, ignoreCase = true)) return -1
            at += word.length
            if (at < typed.length && typed[at] != ' ') return -1
        }
        return at
    }

    private fun target(slot: Slot) = com.engineerclient.EngineerClient.admCommand(slot.command.value.trim().removePrefix("/"))

    // Completes the target command with what was typed after the alias, then moves the results back under the alias.
    private fun suggest(slot: Slot, builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
        val connection = mc.connection ?: return builder.buildFuture()
        if (suggesting) return builder.buildFuture()
        val target = target(slot)
        val start = target.length + 1
        val shift = builder.start - start
        val commands = connection.commands
        suggesting = true
        val future = try {
            commands.getCompletionSuggestions(commands.parse("$target ${builder.remaining}", connection.suggestionsProvider))
        } finally { suggesting = false }
        return future.thenApply { found ->
            val moved = found.list.filter { it.range.start >= start }
                .map { Suggestion(StringRange(it.range.start + shift, it.range.end + shift), it.text, it.tooltip) }
            if (moved.isEmpty()) Suggestions.empty().join()
            else Suggestions(StringRange(moved.minOf { it.range.start }, moved.maxOf { it.range.end }), moved)
        }
    }

    private fun run(typed: String): Boolean {
        var slot: Slot? = null
        var end = -1
        for (s in slots) {
            if (!s.live() || (slot != null && s.words.size <= slot.words.size)) continue
            val at = aliasEnd(s, typed)
            if (at >= 0) { slot = s; end = at }
        }
        if (slot == null) return false
        val rest = typed.substring(end).trim()
        if (slot in active || active.size >= MAX_DEPTH) return true
        if (active.isEmpty()) {
            // The send hook and the client command can both see one typed alias.
            val now = System.currentTimeMillis()
            if (typed == lastTyped && now - lastRunAt < 100) return true
            lastTyped = typed
            lastRunAt = now
        }
        val target = target(slot)
        active += slot
        try { mc.player?.connection?.sendCommand(if (rest.isBlank()) target else "$target $rest") } finally { active.removeLast() }
        return true
    }
}
