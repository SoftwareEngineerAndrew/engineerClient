package com.coffeeclient.misc

import com.coffeeclient.CoffeeClient
import com.google.common.collect.ImmutableMultimap
import com.google.gson.JsonParser
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.ResolvableProfile
import java.util.UUID

/**
 * No Hypixel SkyBlock resource pack (was the PackDisabler mod, our no-pack fork; CC0): the pack is never downloaded or
 * loaded (NoPackPushMixin tells the server it loaded), and each SkyBlock item is drawn as it was before the pack, from
 * skyblock-items.json (its old vanilla model, and the head texture for those that were heads). Read in the background
 * at launch; until then items draw as their own vanilla item.
 */
object NoPack {
    @Volatile private var models: Map<String, Identifier> = emptyMap()
    @Volatile private var skulls: Map<String, ResolvableProfile> = emptyMap()
    private val ARROW by lazy(LazyThreadSafetyMode.NONE) { model(Items.ARROW) }
    private val GOLD_SWORD by lazy(LazyThreadSafetyMode.NONE) { model(Items.GOLDEN_SWORD) }
    private val DIAMOND_SWORD by lazy(LazyThreadSafetyMode.NONE) { model(Items.DIAMOND_SWORD) }
    private val KATANAS = setOf("VOIDEDGE_KATANA", "VORPAL_KATANA", "ATOMSPLIT_KATANA")
    private val FUNGI_CUTTERS = setOf("FUNGI_CUTTER", "FUNGI_CUTTER_2", "FUNGI_CUTTER_3")
    /** Attuned daggers: the mode they show their other look in, and that look. */
    private val DAGGERS = mapOf("FIREDUST_DAGGER" to 1, "BURSTFIRE_DAGGER" to 1, "HEARTFIRE_DAGGER" to 1, "MAWDUST_DAGGER" to 3, "BURSTMAW_DAGGER" to 3, "HEARTMAW_DAGGER" to 3)

    fun register() {
        Thread.ofVirtual().name("coffee-nopack").start {
            runCatching { load() }.onFailure { CoffeeClient.logger.warn("[cc] No Pack: skyblock-items.json didn't load", it) }
        }
    }

    private fun load() {
        val text = NoPack::class.java.getResourceAsStream("/coffeeclient/skyblock-items.json")!!.reader().use { it.readText() }
        val m = HashMap<String, Identifier>(); val s = HashMap<String, ResolvableProfile>()
        for ((id, el) in JsonParser.parseString(text).asJsonObject.entrySet()) {
            val o = el.asJsonObject
            m[id] = Identifier.parse(o.get("model")?.asString ?: continue)
            o.get("texture")?.asString?.takeIf { it.isNotEmpty() }?.let { s[id] = profile(id, it) }
        }
        models = m; skulls = s
        CoffeeClient.logger.info("[cc] No Pack: {} items", m.size)
    }

    private fun profile(id: String, texture: String): ResolvableProfile {
        val props = PropertyMap(ImmutableMultimap.of("textures", Property("textures", texture)))
        return ResolvableProfile.createResolved(GameProfile(UUID.nameUUIDFromBytes("coffeeclient:$id".toByteArray()), "NoPack", props))
    }

    private fun model(item: net.minecraft.world.item.Item): Identifier = item.components().get(DataComponents.ITEM_MODEL)!!
    private fun tag(stack: ItemStack): CompoundTag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
    private fun idOf(tag: CompoundTag, key: String): String? = tag.getString(key).orElse(null)?.replace(':', '-')

    /** The model to draw [stack] with, for the [current] one the server gave it (a Hypixel pack model has none here). */
    @JvmStatic fun model(stack: ItemStack, current: Identifier?): Identifier? {
        if (current == null || current.namespace != "hypixel_skyblock") return current
        val vanilla = stack.item.components().get(DataComponents.ITEM_MODEL) ?: current
        val tag = tag(stack)
        val id = idOf(tag, "id")
        val old = when {
            id != null -> models[id]
            tag.contains("quiver_arrow") -> ARROW
            else -> null
        } ?: return vanilla
        return when (id) {
            "CARNIVAL_SHOVEL" -> when (current.path.substringAfterLast('/')) {
                "carnival_shovel_iron" -> model(Items.IRON_SHOVEL); "carnival_shovel_gold" -> model(Items.GOLDEN_SHOVEL)
                "carnival_shovel_diamond" -> model(Items.DIAMOND_SHOVEL); else -> old
            }
            in DAGGERS -> if (tag.getInt("td_attune_mode").orElse(-1) == DAGGERS[id]) (if (DAGGERS[id] == 1) GOLD_SWORD else DIAMOND_SWORD) else old
            in KATANAS -> if (Minecraft.getInstance().player?.cooldowns?.isOnCooldown(stack) == true) GOLD_SWORD else old
            in FUNGI_CUTTERS -> when (tag.getString("fungi_cutter_mode").orElse(null)) {
                "RED" -> model(Items.RED_MUSHROOM); "BROWN" -> model(Items.BROWN_MUSHROOM); else -> old
            }
            else -> old
        }
    }

    /** The head texture to draw [stack] with when it was a head before the pack (its skin's, else its own). */
    @JvmStatic fun profile(stack: ItemStack, current: ResolvableProfile?): ResolvableProfile? {
        if (stack.isEmpty || stack.`is`(Items.PLAYER_HEAD)) return current
        val tag = tag(stack)
        val id = idOf(tag, "id") ?: return current
        return idOf(tag, "skin")?.let(skulls::get) ?: skulls[id] ?: current
    }
}
