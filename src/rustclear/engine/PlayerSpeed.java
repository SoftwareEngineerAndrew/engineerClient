package net.rustclear.mod.engine;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * The Speed stat, as the Rust server plays it (server.rs): the movement speed attribute's base is
 * speed / 1000, Hypixel SkyBlock's scale (100 speed is vanilla's 0.1), and the walking speed in the
 * player's abilities matches it so the field of view stays as it is at vanilla speed. Sprinting
 * adds vanilla's own 30% on top. Speed is each player's own, from their stats (PlayerStats: base
 * 100, gear, class, potion, Ragnarok; capped at 500), so it changes as they swap gear.
 */
final class PlayerSpeed {
    private PlayerSpeed() {
    }

    /** Sets [player]'s speed from their stats. */
    static void apply(ServerPlayer player) {
        // A ghost moves at Hypixel's ghost speed, 0.55 (player_abilities walkingSpeed; boss-p4-necron.md F9).
        float value = Ghosts.isGhost(player) ? Ghosts.SPEED : (float) (PlayerStats.of(player).get(Stat.SPEED) / 1000);
        AttributeInstance attribute = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attribute != null && attribute.getBaseValue() != value) {
            attribute.setBaseValue(value);
        }
        if (player.getAbilities().getWalkingSpeed() != value) {
            player.getAbilities().setWalkingSpeed(value);
            player.onUpdateAbilities();
        }
    }
}
