package com.engineerclient.p3sim.clear.mobs

import com.engineerclient.p3sim.pack.Packs
import com.engineerclient.p3sim.pack.mobs.MobArchetype
import com.engineerclient.p3sim.pack.mobs.MobTag
import com.engineerclient.p3sim.run.AdsClientContext
import com.engineerclient.p3sim.run.AdsCommand
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player

/**
 * `/ads dev disguise`: the spike for fake players. The server part puts one Crypt Dreadlord (a real fake player on the wire, a
 * light mob on the server, a starred name tag over it) four blocks ahead of you; the client part waits about a second for it to
 * arrive and then asks the code of Odin's star highlight and of Blade Addons' look whether they accept it, and prints one line
 * each: the entity class (it must be a `RemotePlayer`, not a mannequin), its UUID version (2), the tag at body id + 1 and whether
 * its text matches the star regex, Odin's `isValidEntity`, and Blade's name-based kind. `/ads dev disguise clear` removes it.
 * Both checks reach private members by reflection, so a line that says "cannot ask" means that member moved, not that ESP failed.
 */
internal object DisguiseSpike {
    private var wait = -1
    private var reply: ((String) -> Unit)? = null

    /** Registers the client tick that runs the probe. Called from the module's `onInit`. */
    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register {
            if (wait > 0 && --wait == 0) { val r = reply; reply = null; wait = -1; if (r != null) probe(r) }
        }
    }

    fun command(module: MobsModule): AdsCommand = AdsCommand(
        "disguise", "/ads dev disguise [clear]",
        "Puts a Crypt Dreadlord in front of you as a real fake player and checks that Odin's and Blade Addons' star ESP accept it.",
        client = { ctx: AdsClientContext ->
            if (ctx.arg(0) != "clear") {
                reply = ctx.reply
                wait = 30
            }
        },
        server = { ctx ->
            if (ctx.arg(0) == "clear") {
                ctx.reply(module.spikeClear())
            } else {
                val pack = Packs.current?.mobs
                val arch = pack?.archetypeById("crypt_dreadlord") ?: builtin()
                ctx.reply(module.spikeSpawn(ctx.player, arch, 4.0))
                if (pack == null) ctx.reply("No mob tables were loaded, so this one has a plain skin and a built-in name tag.")
            }
        },
    )

    /** A Crypt Dreadlord without a pack: the grammar of the real tag, an invented head. */
    private fun builtin() = MobArchetype(
        0, "crypt_dreadlord", "Crypt Dreadlord", "clear", "minecraft:player", true, null, null, null, mapOf("F7" to 840_000L), true,
        MobTag(1, 2.09, "§2◆§r§6 ✯ §r", "§c", false), "static", null, null, emptyMap(),
    )

    private fun probe(say: (String) -> Unit) {
        val mc = Minecraft.getInstance()
        val level = mc.level; val me = mc.player
        if (level == null || me == null) { say("No client world to look at."); return }
        val fake = level.entitiesForRendering().filterIsInstance<Player>().filter { it !== me && it.uuid.version() == 2 && it.distanceTo(me) < 16f }.minByOrNull { it.distanceTo(me) }
        if (fake == null) { say("§cNo version-2 player appeared within 16 blocks. The client ignored the add (no player-info entry first?) or the entity never reached it."); return }
        say("Body: ${fake.javaClass.simpleName} (a Player: ${fake is Player}), id ${fake.id}, uuid version ${fake.uuid.version()}, ${"%.1f".format(java.util.Locale.ROOT, fake.distanceTo(me))} blocks away.")
        val tag = level.getEntity(fake.id + 1)
        val tagName = (tag as? ArmorStand)?.customName?.string
        if (tag !is ArmorStand || tagName == null) say("§cNo name-tag stand at body id + 1 (found ${tag?.javaClass?.simpleName}).")
        else say("Tag at id ${tag.id}: \"$tagName\" matches the star regex: ${MobTags.STARRED_REGEX.matches(tagName)}.")
        say("Odin star highlight (isValidEntity): " + odinAccepts(fake))
        say("Blade Addons look (kind from the tag): " + bladeKind(tagName ?: ""))
    }

    private fun odinAccepts(e: Entity): String = try {
        val hl = com.odtheking.odin.features.impl.dungeon.Highlight
        val m = hl.javaClass.getDeclaredMethod("isValidEntity", Entity::class.java).apply { isAccessible = true }
        val ok = m.invoke(hl, e) as Boolean
        var listed = "unknown"
        try {
            val f = hl.javaClass.getDeclaredField("entities").apply { isAccessible = true }
            listed = ((f.get(null) as? Set<*>)?.contains(e)).toString()
        } catch (_: Throwable) {}
        "accepts it: $ok (in Odin's current list: $listed; the list only fills while Odin thinks you are in a dungeon)"
    } catch (t: Throwable) { "cannot ask (${t.javaClass.simpleName}: ${t.message})" }

    private fun bladeKind(tag: String): String = try {
        val o = com.engineerclient.misc.OdinHighlightLook
        val m = o.javaClass.getDeclaredMethod("kindOf", String::class.java).apply { isAccessible = true }
        "kind ${m.invoke(o, tag)}"
    } catch (t: Throwable) { "cannot ask (${t.javaClass.simpleName}: ${t.message})" }
}
