package com.engineerclient.recorder

import com.engineerclient.EngineerClient
import com.odtheking.odin.events.CheckmarkUpdateEvent
import com.odtheking.odin.events.EntityEvent
import com.odtheking.odin.events.FloorEnterEvent
import com.odtheking.odin.events.LocationChangeEvent
import com.odtheking.odin.events.MapUpdateEvent
import com.odtheking.odin.events.MessageSentEvent
import com.odtheking.odin.events.PartyEvent
import com.odtheking.odin.events.RoomEnterEvent
import com.odtheking.odin.events.ScoreUpdateEvent
import com.odtheking.odin.events.ScreenCloseEvent
import com.odtheking.odin.events.ScreenEvent
import com.odtheking.odin.events.SecretPickupEvent
import com.odtheking.odin.events.SecretsUpdateEvent
import com.odtheking.odin.events.SetSlotEvent
import com.odtheking.odin.events.TerminalEvent
import com.odtheking.odin.events.UseItemOnPostEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.impl.dungeon.map.WorldScan
import com.odtheking.odin.features.impl.dungeon.map.tile.DungeonRoom
import com.odtheking.odin.utils.skyblock.LocationUtils
import com.odtheking.odin.utils.skyblock.dungeon.terminals.terminalhandler.TerminalHandler
import net.minecraft.core.registries.BuiltInRegistries

/**
 * Odin's own event stream as `ev` lines (recorder-2 U11): `{"k":"ev","e":Name,"thr":"main|net",
 * "cancelled"?,...}`. These are the moments Odin's features act on - a room entered, a secret
 * picked up, a terminal opened or clicked, the score changing - so a recording shows exactly when
 * Odin saw each one, not just the packets it came from.
 *
 * Odin's bus dispatches by the event's exact class, so every concrete class is registered on its
 * own. Every listener runs last (Int.MIN_VALUE) and also sees cancelled events, so "cancelled" is
 * the final verdict of every mod on the bus. Values are copied into the line on the thread that
 * posted the event; items through [RichJson.itemNow].
 */
object OdinEvents {

    fun install() {
        on<RoomEnterEvent>(priority = Int.MIN_VALUE) { ev("RoomEnterEvent") { room(this, room) } }
        on<CheckmarkUpdateEvent>(priority = Int.MIN_VALUE) { ev("CheckmarkUpdateEvent") { room(this, room); s("check", checkmark.name) } }
        on<SecretsUpdateEvent>(priority = Int.MIN_VALUE) { ev("SecretsUpdateEvent") { room(this, room); n("found", foundSecrets) } }
        on<SecretPickupEvent.Item>(priority = Int.MIN_VALUE) {
            ev("SecretPickupEvent.Item") {
                val e = entity
                n("eid", e.id)
                safe("item") { it.append(RichJson.itemNow(e.item)) }
                safe("pos") { OdinJs.nums(it, e.x, e.y, e.z) }
            }
        }
        on<SecretPickupEvent.Interact>(priority = Int.MIN_VALUE) {
            ev("SecretPickupEvent.Interact") {
                val p = blockPos
                safe("pos") { OdinJs.nums(it, p.x, p.y, p.z) }
                s("block", blockState.toString())
                safe("rel") { OdinState.relative(it, WorldScan.currentRoom, p) }
            }
        }
        on<SecretPickupEvent.Bat>(priority = Int.MIN_VALUE) {
            ev("SecretPickupEvent.Bat") {
                val p = packet
                safe("sound") { OdinJs.str(it, p.sound.registeredName) }
                s("source", p.source.name)
                safe("pos") { OdinJs.nums(it, p.x, p.y, p.z) }
                n("volume", p.volume).n("pitch", p.pitch).n("seed", p.seed)
            }
        }
        on<ScoreUpdateEvent>(priority = Int.MIN_VALUE) { ev("ScoreUpdateEvent") { n("score", score) } }
        on<FloorEnterEvent>(priority = Int.MIN_VALUE) { ev("FloorEnterEvent") { s("floor", floor.name) } }
        on<LocationChangeEvent>(priority = Int.MIN_VALUE) {
            ev("LocationChangeEvent") { safe("area") { OdinJs.str(it, LocationUtils.currentArea.name) }; safe("lobby") { OdinJs.str(it, LocationUtils.lobbyId) } }
        }
        on<MapUpdateEvent>(priority = Int.MIN_VALUE) { ev("MapUpdateEvent") {} }
        on<PartyEvent.Leave>(priority = Int.MIN_VALUE) { ev("PartyEvent.Leave") { arr("members", members.toList()) { out, s -> OdinJs.str(out, s) } } }
        on<TerminalEvent.Open>(priority = Int.MIN_VALUE) { ev("TerminalEvent.Open") { term(this, terminal) } }
        on<TerminalEvent.Close>(priority = Int.MIN_VALUE) { ev("TerminalEvent.Close") { term(this, terminal) } }
        on<TerminalEvent.Solve>(priority = Int.MIN_VALUE) { ev("TerminalEvent.Solve") { term(this, terminal) } }
        on<TerminalEvent.Click>(priority = Int.MIN_VALUE) {
            ev("TerminalEvent.Click") {
                safe("type") { OdinJs.str(it, terminal.type.name) }
                n("slot", slotIndex).n("button", button)
                arr("solution", solution.toList()) { out, x -> OdinJs.num(out, x) }
            }
        }
        on<UseItemOnPostEvent>(priority = Int.MIN_VALUE) {
            ev("UseItemOnPostEvent") {
                val h = hitResult
                s("hand", hand.name)
                safe("pos") { OdinJs.nums(it, h.blockPos.x, h.blockPos.y, h.blockPos.z) }
                s("face", h.direction.name)
                safe("hit") { val l = h.location; OdinJs.nums(it, l.x, l.y, l.z) }
                b("inside", h.isInside)
                s("result", interactionResult.toString())
            }
        }
        on<ScreenEvent.Open>(priority = Int.MIN_VALUE) {
            ev("ScreenEvent.Open", isCancelled) {
                val sc = screen
                s("class", sc.javaClass.name)
                safe("title") { RichJson.component(it, sc.title) }
            }
        }
        on<ScreenCloseEvent>(priority = Int.MIN_VALUE) { ev("ScreenCloseEvent") {} }
        on<EntityEvent.Event>(priority = Int.MIN_VALUE) {
            ev("EntityEvent.Event") {
                val e = entity
                n("eid", e.id)
                safe("type") { OdinJs.str(it, BuiltInRegistries.ENTITY_TYPE.getKey(e.type).toString()) }
                n("status", id)
            }
        }
        on<SetSlotEvent>(priority = Int.MIN_VALUE) {
            ev("SetSlotEvent") {
                n("slot", slotIndex)
                safe("item") { it.append(RichJson.itemNow(itemStack)) }
                safe("menu") { OdinJs.str(it, menu.javaClass.name) }
                safe("containerId") { it.append(menu.containerId) }
            }
        }
        on<MessageSentEvent>(priority = Int.MIN_VALUE) { ev("MessageSentEvent", isCancelled) { sent(this, message) } }

        EventBus.subscribe(this)
    }

    /** One `ev` line, built here on the posting thread; a payload that throws keeps the line with an @error. */
    private inline fun ev(name: String, cancelled: Boolean? = null, fill: OdinJs.() -> Unit) {
        if (!Rec.active) return
        EngineerClient.safely("recorder odin event $name") {
            val j = OdinJs(256).s("e", name).s("thr", OdinState.thr())
            if (cancelled != null) j.b("cancelled", cancelled)
            val mark = j.sb.length
            try { j.fill() } catch (t: Throwable) {
                j.sb.setLength(mark)
                j.sb.append(",\"@error\":"); PacketJson.str(j.sb, t.toString())
            }
            Rec.emit("ev", j.toString())
        }
    }

    private fun room(j: OdinJs, r: DungeonRoom?) {
        j.n("room", r?.let { System.identityHashCode(it) })
        j.safe("name") { OdinJs.str(it, r?.name) }
    }

    private fun term(j: OdinJs, t: TerminalHandler) {
        j.safe("type") { OdinJs.str(it, t.type.name) }
        j.n("ticksOpened", t.ticksOpened)
        j.arr("clicked", t.clickedSlots.toList()) { out, (slot, button) -> OdinJs.nums(out, slot, button) }
        j.arr("solution", t.solution.toList()) { out, x -> OdinJs.num(out, x) }
    }

    /** What you sent: the text only with Typed Chat on; otherwise a command's root word, or a chat line's length. */
    internal fun sent(j: OdinJs, text: String) {
        when {
            Rec.typedAllowed(text, text.startsWith("/")) -> j.s("text", text)
            text.startsWith("/") -> j.s("command", text.drop(1).substringBefore(' ')).s("args", "<redacted>")
            else -> j.b("redacted", true).n("len", text.length)
        }
    }
}
