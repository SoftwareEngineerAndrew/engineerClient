package net.rustclear.mod.engine;

import java.util.Random;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;

/**
 * Hypixel's damage numbers: every hit on a mob shows its damage on an invisible marker armor stand by the mob
 * (abilities-items.md §9). Measured from 872 tags in 6 main F7 recordings (2026-10-19): the stand never moves,
 * goes after 19-20 ticks (median; a few after 4-5), and stands ~0.8 blocks to the side of the mob (p25 0.6, p75 1.4)
 * at ~1.3 above its feet (p25 0.9, p75 1.6). A plain hit is gray; a crit is ✧N✧ with each character in the
 * white, white, yellow, gold, red, red cycle. Shown numbers are capped at 2,147,483,647, as on Hypixel.
 */
final class DamageTags {
    private DamageTags() {
    }

    static final int LIFETIME = 20;
    private static final ChatFormatting[] CRIT = {ChatFormatting.WHITE, ChatFormatting.WHITE, ChatFormatting.YELLOW,
        ChatFormatting.GOLD, ChatFormatting.RED, ChatFormatting.RED};
    private static final Random RANDOM = new Random();

    /** [amount] on [mob], a crit or not; the tag goes [LIFETIME] ticks later. */
    static void show(DungeonRun run, Entity mob, long amount, boolean crit) {
        ServerLevel level = run.level;
        double angle = RANDOM.nextDouble() * Math.PI * 2;
        double out = 0.4 + RANDOM.nextDouble() * 1.0;
        double up = 0.3 + mob.getBbHeight() * 0.5 + (RANDOM.nextDouble() - 0.5) * 0.6;
        ArmorStand stand = new ArmorStand(EntityTypes.ARMOR_STAND, level);
        stand.snapTo(mob.getX() + Math.cos(angle) * out, mob.getY() + up, mob.getZ() + Math.sin(angle) * out, 0f, 0f);
        stand.setInvisible(true);
        ((net.rustclear.mod.mixin.ArmorStandAccessor) stand).rustclear$setMarker(true);
        stand.setNoGravity(true);
        stand.setSilent(true);
        stand.setPermanentlyInvulnerable(true);
        stand.setCustomName(text(amount, crit));
        stand.setCustomNameVisible(true);
        level.addFreshEntity(stand);
        run.schedule(LIFETIME, stand::discard);
    }

    static Component text(long amount, boolean crit) {
        String n = String.format(java.util.Locale.ROOT, "%,d", Math.min(Integer.MAX_VALUE, Math.max(0, amount)));
        if (!crit) {
            return Component.literal(n).withStyle(ChatFormatting.GRAY);
        }
        String s = "✧" + n + "✧";
        MutableComponent out = Component.empty().withStyle(Style.EMPTY.withItalic(false));
        for (int i = 0; i < s.length(); i++) {
            out.append(Component.literal(String.valueOf(s.charAt(i))).withStyle(CRIT[i % CRIT.length]));
        }
        return out;
    }
}
