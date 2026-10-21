package net.rustclear.mod.engine;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.component.TooltipDisplay;

/** Builds the items the engine hands out and shows in menus. Names and lore use § colour codes, as on the server. */
public final class EngineItems {
    private EngineItems() {
    }

    /** [legacy] (§ codes) as Hypixel sends text: styled parts ([styled]), not italic. */
    public static Component text(String legacy) {
        return styled(legacy).withStyle(style -> style.withItalic(false));
    }

    /**
     * [legacy] (§ codes) as styled text, the way Hypixel sends it: mods that read a component's
     * plain string see no § codes, and mods that rebuild codes from the styles get the same string.
     */
    public static MutableComponent styled(String legacy) {
        MutableComponent out = Component.empty();
        Style style = Style.EMPTY;
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < legacy.length(); i++) {
            char c = legacy.charAt(i);
            ChatFormatting format = c == '§' && i + 1 < legacy.length() ? ChatFormatting.getByCode(legacy.charAt(i + 1)) : null;
            if (format == null) {
                run.append(c);
                continue;
            }
            if (!run.isEmpty()) {
                out.append(Component.literal(run.toString()).setStyle(style));
                run.setLength(0);
            }
            style = format == ChatFormatting.RESET ? Style.EMPTY
                : format.ordinal() <= ChatFormatting.WHITE.ordinal() ? Style.EMPTY.withColor(format)
                : style.applyFormat(format);
            i++;
        }
        if (!run.isEmpty()) {
            out.append(Component.literal(run.toString()).setStyle(style));
        }
        return out;
    }

    public static ItemStack named(Item item, String name, String... lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, text(name));
        if (lore.length > 0) {
            stack.set(DataComponents.LORE, new ItemLore(Arrays.stream(lore).map(EngineItems::text).toList()));
        }
        return stack;
    }

    /** A skin as Mojang's base64 "textures" property value. */
    public static GameProfile profile(String name, String textures, String signature) {
        return profile(UUID.nameUUIDFromBytes(("rustclear:" + name + textures).getBytes(StandardCharsets.UTF_8)), name, textures, signature);
    }

    /** A skin under a fixed profile id (Hypixel's own, for heads mods recognise by owner). */
    public static GameProfile profile(UUID id, String name, String textures, String signature) {
        Property property = signature == null ? new Property("textures", textures) : new Property("textures", textures, signature);
        return new GameProfile(id, name, new PropertyMap(ImmutableMultimap.of("textures", property)));
    }

    public static ItemStack head(String textures, String name) {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile("head", textures, null)));
        if (name != null) {
            stack.set(DataComponents.CUSTOM_NAME, text(name));
        }
        return stack;
    }

    public static ItemStack playerHead(GameProfile profile, String name, String... lore) {
        ItemStack stack = named(Items.PLAYER_HEAD, name, lore);
        stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile));
        return stack;
    }

    /** The empty black pane menus are filled with: no name and no tooltip at all, as Hypixel's. */
    public static ItemStack filler() {
        ItemStack stack = named(Items.STAINED_GLASS_PANE.black(), "");
        stack.set(DataComponents.TOOLTIP_DISPLAY, new TooltipDisplay(true, new LinkedHashSet<>()));
        return stack;
    }

    public static List<Component> lines(String... legacy) {
        return Arrays.stream(legacy).map(EngineItems::text).toList();
    }
}
