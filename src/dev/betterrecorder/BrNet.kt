package com.devgineerclient.betterrecorder

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.game.*
import net.minecraft.network.protocol.common.ClientboundPingPacket
import net.minecraft.world.entity.Relative
import java.util.Base64

/**
 * The packet side of Better Recorder. Odin's packet events may come on the network thread, so each handler only copies what it needs
 * (numbers, strings, immutable states) into an event that the game thread turns into a line at the end of its tick, where the room
 * a position is in can be looked up. Server ticks are counted here: Hypixel sends a ping every server tick.
 */
object BrNet {
    private fun ev(kind: String, build: (BrJson) -> Unit) = BetterRecorder.session?.queue(kind, build)

    /** A packet from the server. */
    fun inbound(p: Packet<*>) {
        val s = BetterRecorder.session ?: return
        if (runCatching { p.type().flow() }.getOrNull() != PacketFlow.CLIENTBOUND) return
        when (p) {
            is ClientboundPingPacket -> if (p.id != 0) s.serverTick()
            is ClientboundSystemChatPacket -> {
                val text = BrJson.legacy(p.content())
                if (BetterRecorder.privateText(text)) ev(if (p.overlay()) "bar" else "chat") { it.b("private", true) }
                else if (p.overlay()) s.bar(text)
                else ev("chat") { it.s("m", text) }
            }
            is ClientboundSetTitleTextPacket -> { val t = BrJson.legacy(p.text()); ev("title") { it.s("m", t) } }
            is ClientboundSetSubtitleTextPacket -> { val t = BrJson.legacy(p.text()); ev("subtitle") { it.s("m", t) } }
            is ClientboundTabListPacket -> { val h = BrJson.legacy(p.header()); val f = BrJson.legacy(p.footer()); s.tabHeader(h, f) }
            is ClientboundBlockUpdatePacket -> { val pos = p.pos; val st = p.blockState; ev("blk") { j -> j.bp("p", pos).s("s", BrJson.state(st)); BrRooms.tag(j, pos.x, pos.y, pos.z) } }
            is ClientboundSectionBlocksUpdatePacket -> {
                val list = ArrayList<Pair<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState>>()
                p.runUpdates { pos, st -> list += pos.immutable() to st }
                ev("blks") { j ->
                    val sb = StringBuilder(list.size * 40).append('[')
                    list.forEachIndexed { i, (pos, st) ->
                        if (i > 0) sb.append(',')
                        sb.append('[').append(pos.x).append(',').append(pos.y).append(',').append(pos.z).append(',')
                        BrJson.str(sb, BrJson.state(st)); sb.append(']')
                    }
                    j.raw("b", sb.append(']').toString())
                    val first = list.firstOrNull()?.first
                    if (first != null) BrRooms.tag(j, first.x, first.y, first.z)
                }
            }
            is ClientboundBlockEventPacket -> {
                val pos = p.pos; val block = BuiltInRegistries.BLOCK.getKey(p.block).path; val b0 = p.b0; val b1 = p.b1
                ev("bev") { j -> j.bp("p", pos).s("b", block).i("a", b0).i("v", b1); BrRooms.tag(j, pos.x, pos.y, pos.z) }
            }
            is ClientboundBlockDestructionPacket -> { val id = p.id; val pos = p.pos; val pr = p.progress; ev("dig") { j -> j.i("by", id).bp("p", pos).i("st", pr); BrRooms.tag(j, pos.x, pos.y, pos.z) } }
            is ClientboundLevelEventPacket -> {
                val type = p.type; val pos = p.pos; val data = p.data; val g = p.isGlobalEvent
                ev("lev") { j -> j.i("type", type).bp("p", pos).i("d", data).b("g", g); BrRooms.tag(j, pos.x, pos.y, pos.z) }
            }
            is ClientboundSoundPacket -> {
                val id = p.sound.unwrapKey().map { it.identifier().path }.orElseGet { runCatching { p.sound.value().location().path }.getOrDefault("?") }
                val src = p.source.name; val x = p.x; val y = p.y; val z = p.z; val v = p.volume; val pi = p.pitch
                ev("snd") { j -> j.s("id", id).s("src", src).xyz("p", x, y, z).f("v", v).f("pi", pi); BrRooms.tag(j, x, y, z) }
            }
            is ClientboundSoundEntityPacket -> {
                val id = p.sound.unwrapKey().map { it.identifier().path }.orElseGet { runCatching { p.sound.value().location().path }.getOrDefault("?") }
                val src = p.source.name; val e = p.id; val v = p.volume; val pi = p.pitch
                ev("snde") { j -> j.s("id", id).s("src", src).i("e", e).f("v", v).f("pi", pi) }
            }
            is ClientboundLevelParticlesPacket -> {
                val type = runCatching { BuiltInRegistries.PARTICLE_TYPE.getKey(p.particle().type)?.path }.getOrNull()
                val x = p.x(); val y = p.y(); val z = p.z(); val n = p.count(); val sp = p.xMaxSpeed()
                val dx = p.xDist(); val dy = p.yDist(); val dz = p.zDist(); val opts = runCatching { p.particle().toString() }.getOrNull()
                s.particle(type, x, y, z) ?: return
                ev("ptc") { j -> j.s("id", type).xyz("p", x, y, z).i("c", n).f("sp", sp).raw("dist", "[${BrJson.n3(dx.toDouble())},${BrJson.n3(dy.toDouble())},${BrJson.n3(dz.toDouble())}]"); if (opts != null && opts.length < 120 && opts != type) j.s("o", opts) }
            }
            is ClientboundExplodePacket -> { val c = p.center(); val r = p.radius(); val n = p.blockCount(); ev("boom") { j -> j.xyz("p", c.x, c.y, c.z).f("r", r).i("blocks", n); BrRooms.tag(j, c.x, c.y, c.z) } }
            is ClientboundEntityEventPacket -> {
                val id = runCatching { entityIdOf(p) }.getOrNull() ?: return
                val e = p.eventId.toInt()
                ev("eev") { j -> j.i("e", id).i("ev", e) }
            }
            is ClientboundAnimatePacket -> { val id = p.id; val a = p.action; ev("anim") { j -> j.i("e", id).i("a", a) } }
            is ClientboundHurtAnimationPacket -> { val id = p.id(); val yaw = p.yaw(); ev("hurt") { j -> j.i("e", id).a("yaw", yaw) } }
            is ClientboundDamageEventPacket -> {
                val id = p.entityId(); val type = p.sourceType().unwrapKey().map { it.identifier().path }.orElse("?")
                val cause = p.sourceCauseId(); val direct = p.sourceDirectId(); val pos = p.sourcePosition().orElse(null)
                ev("dmg") { j -> j.i("e", id).s("type", type).i("cause", cause - 1).i("direct", direct - 1); if (pos != null) j.xyz("p", pos.x, pos.y, pos.z) }
            }
            is ClientboundTakeItemEntityPacket -> { val item = p.itemId; val by = p.playerId; val n = p.amount; ev("take") { j -> j.i("item", item).i("by", by).i("n", n) } }
            is ClientboundRemoveEntitiesPacket -> { val ids = p.entityIds().toIntArray(); s.removed(ids) }
            is ClientboundSetPassengersPacket -> { val v = p.vehicle; val ps = p.passengers.clone(); ev("ride") { j -> j.i("v", v).ints("p", ps) } }
            is ClientboundPlayerInfoUpdatePacket -> {
                for (e in p.entries()) {
                    val prof = e.profile() ?: continue
                    val tex = prof.properties().get("textures").firstOrNull() ?: continue
                    s.skin(prof.name(), e.profileId().toString(), tex.value(), tex.signature())
                }
            }
            is ClientboundMapItemDataPacket -> {
                val id = p.mapId().id()
                p.colorPatch().ifPresent { patch -> s.mapPatch(id, patch.startX(), patch.startY(), patch.width(), patch.height(), patch.mapColors().clone()) }
                p.decorations().ifPresent { list ->
                    val sb = StringBuilder(list.size * 24).append('[')
                    list.forEachIndexed { i, d ->
                        if (i > 0) sb.append(',')
                        sb.append('[')
                        BrJson.str(sb, d.type().unwrapKey().map { it.identifier().path }.orElse("?"))
                        sb.append(',').append(d.x()).append(',').append(d.y()).append(',').append(d.rot()).append(',')
                        BrJson.str(sb, d.name().map { BrJson.legacy(it) }.orElse(null)); sb.append(']')
                    }
                    s.mapIcons(id, sb.append(']').toString())
                }
            }
            is ClientboundBossEventPacket -> s.bossPacket(p)
            is ClientboundOpenScreenPacket -> {
                val cid = p.containerId; val type = runCatching { BuiltInRegistries.MENU.getKey(p.type)?.path }.getOrNull(); val title = BrJson.legacy(p.title)
                ev("gui") { j -> j.i("id", cid).s("type", type).s("title", title) }
            }
            is ClientboundContainerSetContentPacket -> {
                val cid = p.containerId(); if (cid == 0) return
                val items = p.items().map { BrJson.item(it) }
                ev("guiItems") { j -> j.i("id", cid).raw("items", items.joinToString(",", "[", "]")) }
            }
            is ClientboundContainerSetSlotPacket -> {
                val cid = p.containerId; if (cid == 0) return
                val slot = p.slot; val item = BrJson.item(p.item)
                ev("guiSlot") { j -> j.i("id", cid).i("slot", slot).raw("item", item) }
            }
            is ClientboundContainerClosePacket -> { val cid = p.containerId; ev("guiClose") { j -> j.i("id", cid).b("server", true) } }
            is ClientboundSetHealthPacket -> { val h = p.health; val f = p.food; ev("hp") { j -> j.f("hp", h).i("food", f) } }
            is ClientboundSetExperiencePacket -> { val pr = p.experienceProgress; val lv = p.experienceLevel; ev("xp") { j -> j.f("bar", pr).i("lvl", lv) } }
            is ClientboundPlayerPositionPacket -> {
                val c = p.change(); val rel = p.relatives().map { it.name }
                val pos = c.position(); val yaw = c.yRot(); val pitch = c.xRot()
                ev("tp") { j -> j.xyz("p", pos.x, pos.y, pos.z).a("yaw", yaw).a("pitch", pitch); if (rel.isNotEmpty()) j.raw("rel", rel.joinToString(",", "[", "]") { BrJson.q(it) }); if (Relative.X !in p.relatives()) BrRooms.tag(j, pos.x, pos.y, pos.z) }
            }
            is ClientboundCooldownPacket -> { val g = p.cooldownGroup().path; val d = p.duration(); ev("cd") { j -> j.s("item", g).i("ticks", d) } }
            is ClientboundUpdateMobEffectPacket -> {
                val e = p.entityId; val eff = p.effect.unwrapKey().map { it.identifier().path }.orElse("?"); val amp = p.effectAmplifier; val dur = p.effectDurationTicks
                ev("fx") { j -> j.i("e", e).s("fx", eff).i("amp", amp).i("dur", dur) }
            }
            is ClientboundRemoveMobEffectPacket -> {
                val e = p.entityId; val eff = p.effect().unwrapKey().map { it.identifier().path }.orElse("?")
                ev("fxEnd") { j -> j.i("e", e).s("fx", eff) }
            }
            is ClientboundSetTimePacket -> s.time(p.gameTime())
            is ClientboundRespawnPacket, is ClientboundLoginPacket -> ev("world") { j -> j.s("p", if (p is ClientboundLoginPacket) "login" else "respawn") }
            else -> {}
        }
    }

    private fun entityIdOf(p: ClientboundEntityEventPacket): Int {
        // The packet keeps the id private; the game's own getter needs a level, so it is read off the packet's fields.
        val f = ClientboundEntityEventPacket::class.java.declaredFields.first { it.type == Int::class.javaPrimitiveType }
        f.isAccessible = true
        return f.getInt(p)
    }

    /** A packet we send. Commands are cut to their first word (no private messages or typed text in the file). */
    fun outbound(p: Packet<*>) {
        val s = BetterRecorder.session ?: return
        if (runCatching { p.type().flow() }.getOrNull() != PacketFlow.SERVERBOUND) return
        when (p) {
            is ServerboundUseItemPacket -> { val h = p.hand().name; val yaw = p.yRot(); val pitch = p.xRot(); ev("use") { j -> j.s("hand", h).a("yaw", yaw).a("pitch", pitch).raw("held", s.heldItem()) } }
            is ServerboundUseItemOnPacket -> {
                val hit = p.hitResult(); val pos = hit.blockPos; val face = hit.direction.name; val loc = hit.location; val h = p.hand().name
                ev("useOn") { j -> j.bp("p", pos).s("face", face).xyz("at", loc.x, loc.y, loc.z).s("hand", h).s("block", s.blockAt(pos)).raw("held", s.heldItem()); BrRooms.tag(j, pos.x, pos.y, pos.z) }
            }
            is ServerboundAttackPacket -> { val id = p.entityId(); ev("attack") { j -> j.i("e", id).raw("held", s.heldItem()).raw("target", s.entityBrief(id)) } }
            is ServerboundInteractPacket -> { val id = p.entityId(); val h = p.hand().name; val sneak = p.usingSecondaryAction(); ev("interact") { j -> j.i("e", id).s("hand", h).b("sneak", sneak).raw("target", s.entityBrief(id)) } }
            is ServerboundPunchPacket -> ev("swing") { it.raw("held", s.heldItem()) }
            is ServerboundPlayerActionPacket -> {
                val a = p.action.name; val pos = p.pos; val dir = p.direction.name
                ev("act") { j -> j.s("a", a).bp("p", pos).s("face", dir); if (a.contains("DESTROY")) { j.s("block", s.blockAt(pos)); BrRooms.tag(j, pos.x, pos.y, pos.z) } }
            }
            is ServerboundSetCarriedItemPacket -> { val slot = p.slot; ev("slot") { j -> j.i("slot", slot) } }
            is ServerboundContainerClickPacket -> {
                val cid = p.containerId(); val slot = p.slotNum().toInt(); val b = p.buttonNum().toInt(); val input = p.containerInput().name
                ev("click") { j -> j.i("id", cid).i("slot", slot).i("button", b).s("input", input) }
            }
            is ServerboundContainerClosePacket -> { val cid = p.containerId; ev("guiClose") { j -> j.i("id", cid) } }
            is ServerboundPlayerInputPacket -> { val i = p.input(); ev("input") { j -> j.b("f", i.forward()).b("b", i.backward()).b("l", i.left()).b("r", i.right()).b("jump", i.jump()).b("sneak", i.shift()).b("sprint", i.sprint()) } }
            is ServerboundChatCommandPacket -> { val root = p.command().substringBefore(' ').lowercase(); ev("cmd") { j -> j.s("root", root) } }
            is ServerboundChatCommandSignedPacket -> { val root = p.command().substringBefore(' ').lowercase(); ev("cmd") { j -> j.s("root", root) } }
            is ServerboundChatPacket -> ev("say") { }
            else -> {}
        }
    }

    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
}
