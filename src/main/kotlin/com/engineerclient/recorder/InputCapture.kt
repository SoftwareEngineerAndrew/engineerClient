package com.engineerclient.recorder

import com.engineerclient.EngineerClient
import com.engineerclient.mixin.GameModeDestroyAccessor
import com.mojang.blaze3d.platform.InputConstants
import com.odtheking.odin.events.BlockInteractEvent
import com.odtheking.odin.events.EntityInteractEvent
import com.odtheking.odin.events.GuiEvent
import com.odtheking.odin.events.InputEvent
import com.odtheking.odin.events.MessageSentEvent
import com.odtheking.odin.events.ScreenEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.UseItemOnPostEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.fabricmc.fabric.api.event.client.player.ClientHotbarScrollEvents
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.MultiLineEditBox
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen
import net.minecraft.client.gui.screens.inventory.BookEditScreen
import net.minecraft.client.gui.screens.inventory.BookSignScreen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec3

/**
 * Dungeon Recorder: what you did with your hands, and what came of it.
 *
 * Packets only show what reached the server. This keeps the step before: every key, mouse button,
 * scroll, cursor move and look turn (with its time inside the tick, since GLFW is polled once a
 * frame and a tick has several frames), the actions the game started (attack, use, pick block)
 * and whether they ran to the end or something cancelled them first, the clicks and key presses
 * Odin's bus saw (and whether a module cancelled them), what the crosshair is on, what each
 * interaction returned, chat and commands that never became a packet, and block-breaking progress.
 *
 * Every hook runs on the game thread (GLFW callbacks are re-posted through Minecraft.execute) and
 * is observe-only. Discrete events are written at once, so their seq sits exactly between the
 * packets around them; cursor and look samples are batched per tick (each sample with its own ns)
 * and the batch is flushed before any discrete event, so the order still reads true.
 *
 * Privacy: while a chat, sign or book screen is open, or a text field has focus, keys are written
 * only as `{"redacted":true}` unless Typed Chat is on; typed characters are only written with
 * Typed Chat on.
 */
object InputCapture {

    private val mc get() = EngineerClient.mc

    /** The recorder is writing and the Input setting is on (the cheap early return of every hook). */
    private fun on(): Boolean = Rec.active && DungeonRecorder.inputOn

    private val cur = InputJson.SampleBuffer()
    private val look = InputJson.SampleBuffer()

    private var installed = false

    /** Registers the Fabric listeners and subscribes the Odin ones below; called once from DungeonRecorder. */
    fun install() {
        if (installed) return
        installed = true
        EventBus.subscribe(this)

        ClientHotbarScrollEvents.AFTER.register { _, from, to, dx, dy ->
            if (on()) EngineerClient.safely("recorder hotbar") {
                event("attempt", "\"what\":\"hotbar_scroll\",\"from\":$from,\"to\":$to,\"dx\":${n(dx)},\"dy\":${n(dy)},\"cancelled\":false")
            }
        }
        ClientSendMessageEvents.CHAT.register { m -> typed("chat", m, null) }
        ClientSendMessageEvents.COMMAND.register { m -> typed("command", m, null) }
        ClientSendMessageEvents.CHAT_CANCELED.register { m -> typed("chat", m, true) }
        ClientSendMessageEvents.COMMAND_CANCELED.register { m -> typed("command", m, true) }
        ClientPlayerBlockBreakEvents.AFTER.register { _, _, pos, state ->
            if (on()) EngineerClient.safely("recorder broke") {
                event("broke", "\"pos\":${bp(pos)},\"state\":${q(BlockStateParser.serialize(state))}")
            }
        }

        // Inside screens: Fabric's per-screen events, re-registered on every init (Fabric gives a
        // screen fresh event objects each time it is initialised). The mouse ones return a boolean
        // that feeds the next listener: always hand back the flag we were given.
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            ScreenKeyboardEvents.afterKeyPress(screen).register { s, k -> screenKey("screen.afterKeyPress", s, k) }
            ScreenKeyboardEvents.afterKeyRelease(screen).register { s, k -> screenKey("screen.afterKeyRelease", s, k) }
            ScreenMouseEvents.afterMouseClick(screen).register { s, e, consumed -> screenMouse("screen.afterMouseClick", s, e, consumed, null); consumed }
            ScreenMouseEvents.afterMouseRelease(screen).register { s, e, consumed -> screenMouse("screen.afterMouseRelease", s, e, consumed, null); consumed }
            ScreenMouseEvents.afterMouseDrag(screen).register { s, e, dx, dy, consumed ->
                screenMouse("screen.afterMouseDrag", s, e, consumed, ",\"dx\":${n(dx)},\"dy\":${n(dy)}"); consumed
            }
            ScreenMouseEvents.afterMouseScroll(screen).register { s, x, y, dx, dy, consumed ->
                if (on()) EngineerClient.safely("recorder screen scroll") {
                    event("attempt", "\"what\":\"screen.afterMouseScroll\",\"x\":${n(x)},\"y\":${n(y)},\"dx\":${n(dx)},\"dy\":${n(dy)},\"consumed\":$consumed,\"screen\":${screenName(s)}")
                }
                consumed
            }
        }

        // The aim at keyframes, so a reader starting there knows what the crosshair is on.
        Rec.onKeyframe("input") { aim(force = true) }
    }

    init {
        // Odin's bus, last in line (Int.MIN_VALUE) and with cancelled events delivered, so the
        // cancelled flag is the final word of every module before us.
        on<InputEvent>(priority = Int.MIN_VALUE) {
            if (!on()) return@on
            EngineerClient.safely("recorder bind") {
                val body = if (redactKeys(mc.screen)) "\"redacted\":true" else "\"key\":${q(key.name)}"
                event("bind", "$body,\"cancelled\":$isCancelled")
            }
        }
        on<GuiEvent.SlotClick>(priority = Int.MIN_VALUE) {
            if (!on()) return@on
            EngineerClient.safely("recorder slot click") {
                event("attempt", "\"what\":\"slot_click\",\"slot\":$slotIndex,\"button\":$button,\"cancelled\":$isCancelled,\"screen\":${screenName(screen)}")
            }
        }
        on<BlockInteractEvent>(priority = Int.MIN_VALUE) {
            if (!on()) return@on
            EngineerClient.safely("recorder block interact") {
                event("attempt", "\"what\":\"block_interact\",\"pos\":${bp(pos)},\"cancelled\":$isCancelled")
            }
        }
        on<EntityInteractEvent>(priority = Int.MIN_VALUE) {
            if (!on()) return@on
            EngineerClient.safely("recorder entity interact") {
                event("attempt", "\"what\":\"entity_interact\",\"eid\":${entity.id},\"etype\":${q(RichJson.registryId(entity.type))},\"pos\":${vec(pos)},\"cancelled\":$isCancelled")
            }
        }
        on<ScreenEvent.MouseClick>(priority = Int.MIN_VALUE) {
            if (!on()) return@on
            EngineerClient.safely("recorder screen click") { screenClick("screen_click", screen, click, isCancelled) }
        }
        on<ScreenEvent.MouseRelease>(priority = Int.MIN_VALUE) {
            if (!on()) return@on
            EngineerClient.safely("recorder screen release") { screenClick("screen_release", screen, click, isCancelled) }
        }
        on<ScreenEvent.KeyPress>(priority = Int.MIN_VALUE) {
            if (!on()) return@on
            EngineerClient.safely("recorder screen key") {
                val body = if (redactKeys(screen)) "\"redacted\":true" else keyMembers(input)
                event("attempt", "\"what\":\"screen_key\",$body,\"cancelled\":$isCancelled,\"screen\":${screenName(screen)}")
            }
        }
        on<UseItemOnPostEvent>(priority = Int.MIN_VALUE) {
            if (!on()) return@on
            EngineerClient.safely("recorder use on") {
                event("useon", "\"hand\":${q(hand.name)},${blockHit(hitResult)},\"result\":${result(interactionResult)}")
            }
        }
        on<MessageSentEvent>(priority = Int.MIN_VALUE) { typed("odin", message, isCancelled) }
        on<TickEvent.End> { if (Rec.active) EngineerClient.safely("recorder input tick") { onTick() } }
    }

    // ------------------------------------------------------------------ keys and mouse (mixins)

    /** KeyboardHandler.keyPress, HEAD. [action] 0 release, 1 press, 2 repeat. */
    @JvmStatic fun key(action: Int, ev: KeyEvent) {
        if (!on()) return
        val screen = mc.screen
        val body = if (redactKeys(screen)) "\"redacted\":true,\"screen\":${screenName(screen)}"
            else "${keyMembers(ev)},\"act\":$action,\"screen\":${screenName(screen)},\"maps\":${maps { it.matches(ev) }}"
        event("key", body)
    }

    /** KeyboardHandler.charTyped, HEAD: only with Typed Chat on. */
    @JvmStatic fun charTyped(ev: CharacterEvent) {
        if (!on() || !Rec.typedChat) return
        // A private message being written (Hide Private Chats) keeps its characters out even so.
        if (redactKeys(mc.screen)) { event("char", "\"redacted\":true,\"screen\":${screenName(mc.screen)}"); return }
        event("char", "\"cp\":${ev.codepoint()},\"s\":${q(ev.codepointAsString())},\"screen\":${screenName(mc.screen)}")
    }

    /** MouseHandler.onButton, HEAD. [action] 0 release, 1 press. gx/gy are the cursor in window pixels. */
    @JvmStatic fun button(info: MouseButtonInfo, action: Int, gx: Double, gy: Double) {
        if (!on()) return
        val ev = MouseButtonEvent(gx, gy, info)
        event("btn", "\"b\":${info.button()},\"act\":$action,\"mods\":${info.modifiers()},\"gx\":${n(gx)},\"gy\":${n(gy)}," +
            "\"screen\":${screenName(mc.screen)},\"maps\":${maps { it.matchesMouse(ev) }}")
    }

    /** MouseHandler.onScroll, HEAD (ahead of anything that cancels it, like the wand scroll). */
    @JvmStatic fun scroll(dx: Double, dy: Double) {
        if (!on()) return
        event("scroll", "\"dx\":${n(dx)},\"dy\":${n(dy)},\"screen\":${screenName(mc.screen)}")
    }

    /** MouseHandler.onMove, HEAD: one [ns,x,y,grabbed] sample, written with the tick's batch. */
    @JvmStatic fun move(x: Double, y: Double, grabbed: Boolean) {
        if (!on() || !DungeonRecorder.cursorMovesOn) return
        val s = Rec.session ?: return
        cur.add(s) { it.append(Rec.nowNs()).append(','); PacketJson.num(it, x); it.append(','); PacketJson.num(it, y); it.append(',').append(grabbed) }
    }

    /**
     * Entity.turn, HEAD, for the local player only: the look delta the mouse asked for, after
     * sensitivity, smoothing and invert (the game turns by these times 0.15 degrees).
     */
    @JvmStatic fun turn(dYaw: Double, dPitch: Double) {
        if (!on()) return
        val s = Rec.session ?: return
        look.add(s) { it.append(Rec.nowNs()).append(','); PacketJson.num(it, dYaw); it.append(','); PacketJson.num(it, dPitch) }
    }

    // ------------------------------------------------------------------ attempts (Minecraft mixin)

    private class Pending(val seq: Long, val env: String, val ms: Long, val body: String)
    private val pending = HashMap<String, Pending>()

    /**
     * An action the game started (startAttack, startUseItem, continueAttack, pickBlockOrEntity),
     * HEAD. The line is finished at RETURN with done=true; one that never reaches its RETURN
     * (cancelled by some mod's HEAD injection) is written with done=false at the next start of the
     * same action or at the end of the tick. Its seq is taken now, so it sorts before the packets
     * the action sends.
     */
    @JvmStatic fun actHead(what: String, arg: String?) {
        if (!on()) return
        pending.remove(what)?.let { finish(it, false, null) }
        val seq = Rec.nextSeq()
        val body = "\"what\":${q(what)}" + (arg?.let { ",\"arg\":$it" } ?: "") + ",\"aim\":${aimJson()}"
        pending[what] = Pending(seq, Rec.envelope("act", seq), System.currentTimeMillis(), body)
    }

    /** RETURN of the same action; [ret] is its JSON return value (startAttack's boolean), if any. */
    @JvmStatic fun actReturn(what: String, ret: String?) {
        val p = pending.remove(what) ?: return
        finish(p, true, ret)
    }

    /**
     * continueAttack runs every tick; it only does something while attack is held, so it is
     * recorded while held and once on the tick it is let go.
     */
    private var attackHeld = false

    @JvmStatic fun continueAttackHead(down: Boolean) {
        if (!on()) return
        val wanted = down || attackHeld
        attackHeld = down
        if (wanted) actHead("continueAttack", down.toString())
    }

    private fun finish(p: Pending, done: Boolean, ret: String?) {
        flushMotion()
        val line = "${p.env},${p.body},\"done\":$done" + (ret?.let { ",\"r\":$it" } ?: "") + "}"
        Rec.emitLine(p.seq, line.length, "act", p.ms) { line }
    }

    // ------------------------------------------------------------------ outcomes (game mode mixin)

    /** MultiPlayerGameMode.useItem, RETURN. */
    @JvmStatic fun used(hand: InteractionHand, r: InteractionResult?) {
        if (!on()) return
        event("use", "\"hand\":${q(hand.name)},\"result\":${result(r)}")
    }

    /** MultiPlayerGameMode.interact, RETURN. */
    @JvmStatic fun interacted(target: Entity, hit: EntityHitResult, hand: InteractionHand, r: InteractionResult?) {
        if (!on()) return
        event("interact", "\"id\":${target.id},\"etype\":${q(RichJson.registryId(target.type))},\"hand\":${q(hand.name)}," +
            "\"hit\":${vec(hit.location)},\"result\":${result(r)}")
    }

    /** MultiPlayerGameMode.attack, HEAD. */
    @JvmStatic fun attacked(target: Entity) {
        if (!on()) return
        event("attack", "\"id\":${target.id},\"etype\":${q(RichJson.registryId(target.type))}")
    }

    /** ClientLevel.destroyBlockProgress, HEAD: anyone's breaking stage on a block (-1 or 10+ clears it). */
    @JvmStatic fun breakProgress(breaker: Int, pos: BlockPos, stage: Int) {
        if (!on()) return
        event("break", "\"by\":$breaker,\"self\":${breaker == mc.player?.id},\"pos\":${bp(pos)},\"stage\":$stage")
    }

    /**
     * `,"mine":{pos,stage,progress}` while you are breaking a block, else "" (for the `me` line).
     * Game thread.
     */
    fun mineMembers(): String {
        if (!DungeonRecorder.inputOn) return ""
        val gm = mc.gameMode ?: return ""
        if (!gm.isDestroying) return ""
        val acc = gm as GameModeDestroyAccessor
        return ",\"mine\":{\"pos\":${bp(acc.`ec$destroyBlockPos`())},\"stage\":${gm.destroyStage},\"progress\":${n(acc.`ec$destroyProgress`())}}"
    }

    // ------------------------------------------------------------------ per tick

    private fun onTick() {
        if (pending.isNotEmpty()) {
            val left = pending.values.toList(); pending.clear()
            left.forEach { finish(it, false, null) }
        }
        flushMotion()
        if (on()) aim(force = false)
    }

    /** The crosshair target, written when it changes (and at keyframes). */
    private fun aim(force: Boolean) {
        if (!on()) return
        val a = aimJson()
        if (Rec.changed("aim", a) || force) event("aim", RecorderFiles.members(a) + if (force) ",\"kf\":${Rec.keyframeId}" else "")
    }

    /** mc.hitResult and crosshairPickEntity as one object (null with no world). */
    private fun aimJson(): String {
        val hr = mc.hitResult ?: return "{\"type\":null}"
        val sb = StringBuilder(160)
        sb.append("{\"type\":").append(q(hr.type.name))
        when (hr) {
            is BlockHitResult -> sb.append(',').append(blockHit(hr))
            is EntityHitResult -> sb.append(",\"id\":").append(hr.entity.id).append(",\"etype\":").append(q(RichJson.registryId(hr.entity.type)))
                .append(",\"hit\":").append(vec(hr.location))
            else -> sb.append(",\"hit\":").append(vec(hr.location))
        }
        mc.crosshairPickEntity?.let { sb.append(",\"pick\":").append(it.id) }
        return sb.append('}').toString()
    }

    private fun blockHit(h: BlockHitResult) =
        "\"pos\":${bp(h.blockPos)},\"face\":${q(h.direction.name)},\"hit\":${vec(h.location)},\"inside\":${h.isInside},\"border\":${h.isWorldBorderHit}"

    // ------------------------------------------------------------------ helpers

    /** A discrete event: the cursor/look samples before it go first, then its own line. */
    private fun event(kind: String, body: String) {
        flushMotion()
        Rec.emit(kind, body)
    }

    private fun flushMotion() {
        val s = Rec.session
        cur.take(s)?.let { Rec.emit("cur", it) }
        look.take(s)?.let { Rec.emit("look", it) }
    }

    private fun typed(kind: String, text: String, cancelled: Boolean?) {
        if (!on()) return
        val command = kind == "command" || (kind == "odin" && text.startsWith("/"))
        EngineerClient.safely("recorder typed") { event("typed", InputJson.typedBody(kind, text, Rec.typedAllowed(text, command), cancelled)) }
    }

    private fun screenKey(what: String, s: Screen, k: KeyEvent) {
        if (!on()) return
        EngineerClient.safely("recorder $what") {
            val body = if (redactKeys(s)) "\"redacted\":true" else keyMembers(k)
            event("attempt", "\"what\":${q(what)},$body,\"screen\":${screenName(s)}")
        }
    }

    private fun screenMouse(what: String, s: Screen, e: MouseButtonEvent, consumed: Boolean, extra: String?) {
        if (!on()) return
        EngineerClient.safely("recorder $what") {
            event("attempt", "\"what\":${q(what)},\"button\":${e.button()},\"mods\":${e.buttonInfo().modifiers()},\"x\":${n(e.x())},\"y\":${n(e.y())}" +
                (extra ?: "") + ",\"consumed\":$consumed,\"screen\":${screenName(s)}")
        }
    }

    private fun screenClick(what: String, s: Screen, e: MouseButtonEvent, cancelled: Boolean) {
        event("attempt", "\"what\":${q(what)},\"button\":${e.button()},\"mods\":${e.buttonInfo().modifiers()},\"x\":${n(e.x())},\"y\":${n(e.y())}," +
            "\"cancelled\":$cancelled,\"screen\":${screenName(s)}")
    }

    private fun keyMembers(ev: KeyEvent) =
        "\"key\":${q(InputConstants.getKey(ev).name)},\"code\":${ev.key()},\"scan\":${ev.scancode()},\"mods\":${ev.modifiers()}"

    /** Names of the key mappings [match] picks (what this key or button is bound to). */
    private inline fun maps(match: (net.minecraft.client.KeyMapping) -> Boolean): String =
        mc.options.keyMappings.filter { runCatching { match(it) }.getOrDefault(false) }.joinToString(",", "[", "]") { q(it.name) }

    /** Keys that would spell out what you type: a chat, sign or book screen, or any focused text field. */
    /** A screen you type text into (chat, sign, book, a focused text field), when Typed Chat is off. Also used by [ThumbCapture]. */
    internal fun redactKeys(screen: Screen?): Boolean {
        if (screen == null) return false
        val typing = screen is ChatScreen || screen is AbstractSignEditScreen || screen is BookEditScreen || screen is BookSignScreen ||
            screen.focused.let { it is EditBox || it is MultiLineEditBox }
        if (!typing) return false
        if (!Rec.typedChat) return true
        // Typed Chat on: still hidden while the chat box holds a private message (/msg, /gc, a guild channel...).
        if (screen !is ChatScreen) return false
        val draft = (screen.focused as? EditBox)?.value ?: return false
        return Rec.privateOutbound(draft, draft.startsWith("/"))
    }

    private fun screenName(s: Screen?): String = if (s == null) "null" else q(PacketJson.simpleName(s.javaClass))

    private fun result(r: InteractionResult?): String = if (r == null) "null" else
        runCatching { PacketJson.writeNow(r) }.getOrElse { q(r.toString()) }

    private fun bp(p: BlockPos) = "[${p.x},${p.y},${p.z}]"
    private fun vec(v: Vec3) = "[${n(v.x)},${n(v.y)},${n(v.z)}]"
    private fun n(d: Double) = StringBuilder(24).also { PacketJson.num(it, d) }.toString()
    private fun n(f: Float) = StringBuilder(16).also { PacketJson.num(it, f) }.toString()
    private fun q(s: String?) = RecorderFiles.q(s)
}

/** The pure parts of [InputCapture], kept free of game classes so they can be tested. */
internal object InputJson {

    /**
     * Samples for one batched line (`"d":[[..],[..]]`), appended on the game thread and taken at the
     * tick's end. Samples belong to the session they were taken in ([owner]); a batch left over when
     * that session ends is dropped with it rather than landing in the next one, whose clock differs.
     */
    class SampleBuffer {
        private val sb = StringBuilder()
        private var owner: Any? = null
        var count = 0
            private set

        @Synchronized fun add(owner: Any, fill: (StringBuilder) -> Unit) {
            if (this.owner !== owner) { sb.setLength(0); count = 0; this.owner = owner }
            if (count > 0) sb.append(',')
            sb.append('[')
            fill(sb)
            sb.append(']')
            count++
        }

        /** `"d":[...]` and empties the buffer, or null when there is nothing for [current]. */
        @Synchronized fun take(current: Any?): String? {
            if (count == 0) return null
            val out = if (owner === current && current != null) "\"d\":[$sb]" else null
            sb.setLength(0); count = 0
            return out
        }
    }

    /** The first word of a command, without its slash ("msg" for "/msg Bob hi"). */
    fun commandRoot(cmd: String): String = cmd.trim().removePrefix("/").substringBefore(' ')

    /**
     * The body of a `typed` line. With Typed Chat on, the text itself; off, only that something was
     * sent, its length and, for commands, the command's name. [kind] is chat, command or odin (Odin's
     * MessageSentEvent, whose message is a command when it starts with a slash).
     */
    fun typedBody(kind: String, text: String, typedChat: Boolean, cancelled: Boolean?): String {
        val sb = StringBuilder("\"kind\":").append(RecorderFiles.q(kind))
        if (typedChat) sb.append(",\"text\":").append(RecorderFiles.q(text))
        else {
            sb.append(",\"redacted\":true,\"len\":").append(text.length)
            if (kind == "command" || (kind == "odin" && text.startsWith("/"))) sb.append(",\"root\":").append(RecorderFiles.q(commandRoot(text)))
        }
        if (cancelled != null) sb.append(",\"cancelled\":").append(cancelled)
        return sb.toString()
    }
}
