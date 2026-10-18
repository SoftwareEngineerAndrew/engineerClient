package com.engineerclient.p3sim.clear.blood

import com.engineerclient.EngineerClient
import com.engineerclient.p3sim.pack.rooms.SkullTexture
import com.google.common.collect.ImmutableMultimap
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ResolvableProfile

/** Player-head items for the blood room: the wall heads' stands and the Watcher's own head. Server thread only. */
object HeadItems {
    /**
     * The player head [key] names (see [HeadTextures.resolve]): a plain head for [HeadTextures.PLAIN] or a key that is neither a
     * texture id nor a known role, [ItemStack.EMPTY] for [HeadTextures.NONE].
     */
    fun stack(key: String, textures: List<SkullTexture>): ItemStack {
        if (key == HeadTextures.NONE) return ItemStack.EMPTY
        val stack = ItemStack(Items.PLAYER_HEAD)
        val tex = HeadTextures.resolve(key, textures) ?: return stack
        EngineerClient.safely("blood head") {
            val gp = GameProfile(tex.profileId, "head", PropertyMap(ImmutableMultimap.of("textures", Property("textures", tex.value))))
            stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(gp))
        }
        return stack
    }
}
