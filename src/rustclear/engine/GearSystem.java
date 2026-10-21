package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * What the dungeon items ([ItemDefs]) do in a run: shortbows shoot on both clicks with one shared
 * cooldown (the Terminator three arrows at once, and Salvation on left click after three hits),
 * bows shoot when let go, and right-click abilities hit the run's mobs. Every hit is the player's
 * hit (from their stats, PlayerStats) times a share the ability's lore gives (Reaving Strike 125%,
 * a Runaan's side arrow 40%...), through MobSystem.hit, so the floor's mob health is what counts,
 * as for melee.
 *
 * Abilities cost their Mana, Vitality and Health (Combat.spend) and have their cooldowns.
 */
final class GearSystem {
    /** Fewest ticks between two shortbow shots, whatever the item says (the client's own right-click rate). */
    private static final int MIN_SHOT_TICKS = 4;
    private static final net.minecraft.resources.Identifier BOW_GROUP = net.minecraft.resources.Identifier.withDefaultNamespace("bow");
    private static final int SALVATION_HITS = 3;
    private static final int SALVATION_TARGETS = 5;
    private static final double SALVATION_RANGE = 40;
    private static final float ARROW_SPEED = 3.0f;
    private static final int ARROW_LIFE_TICKS = 100;
    /** Two clicks this close (a block use followed by an item use) are one cast. */
    private static final int MIN_CAST_TICKS = 2;

    /** What one of our arrows does when it lands. */
    private record Shot(UUID shooter, String item, double multiplier, double heal) {}

    private final DungeonRun run;
    private final ItemSystem items;
    private final ServerLevel level;
    private final Map<AbstractArrow, Shot> arrows = new HashMap<>();
    /** "player:key" to the tick it can be used again. */
    private final Map<String, Long> readyAt = new HashMap<>();
    private final Map<UUID, Integer> salvationHits = new HashMap<>();
    /** Throw's streak: the multiplier for the next throw, and the tick the streak lapses. */
    private final Map<UUID, double[]> throwStreak = new HashMap<>();
    /** Players mid-Leap, with the tick they jumped. */
    private final Map<UUID, Long> leaping = new HashMap<>();
    /** Shadow Fury's remaining targets per player. */
    private final Map<UUID, List<Entity>> fury = new HashMap<>();
    /** Thrown Bonemerangs, the players' and the mobs'. */
    private final Bonemerangs bones;

    GearSystem(DungeonRun run, ItemSystem items) {
        this.run = run;
        this.items = items;
        this.level = run.level;
        this.bones = new Bonemerangs(run);
    }

    Bonemerangs bones() {
        return bones;
    }

    // ---------------------------------------------------------------- clicks

    /** A right click holding a dungeon item. True when it did something (nothing vanilla happens). */
    boolean rightClick(ServerPlayer player, ItemDefs.Instance item) {
        ItemDefs.Def def = item.def();
        switch (def.right) {
            case "shoot" -> {
                shoot(player, def, false);
                return true;
            }
            case "draw", "none" -> {
                // Bows draw the vanilla way (GearSystem.release); armour equips and the rest do nothing.
                return false;
            }
            case "class" -> {
                if (item.witherImpact()) {
                    if (ready(player, "wither_impact", MIN_CAST_TICKS / 2) && run.combat().spend(player, "Wither Impact",
                        run.combat().manaCost(player, 300, player.getMainHandItem()), null, null)) {
                        items.wither(player);
                    }
                } else {
                    player.sendSystemMessage(EngineItems.text(
                        "§cApply Implosion, Shadow Warp and Wither Shield to use Wither Impact. §7(/rcitem give " + def.id + ")"));
                }
                return true;
            }
            default -> {
                ItemDefs.Ability ability = def.ability("RIGHT CLICK");
                if (ability != null) {
                    cast(player, def, ability);
                }
                return true;
            }
        }
    }

    /** A left click (air, block or mob) holding anything: shortbows shoot, the Terminator casts Salvation. */
    void leftClick(ServerPlayer player) {
        ItemStack held = player.getMainHandItem();
        ItemDefs.Def def;
        if (EngineItem.of(held) == EngineItem.TERMINATOR) {
            def = ItemDefs.get().def("TERMINATOR");
        } else {
            ItemDefs.Instance item = DataItems.of(held);
            def = item == null ? null : item.def();
        }
        if (def != null && def.isShortbow()) {
            shoot(player, def, true);
        }
    }

    /** The Terminator or any other shortbow. */
    static boolean isShortbow(ItemStack stack) {
        if (EngineItem.of(stack) == EngineItem.TERMINATOR) {
            return true;
        }
        ItemDefs.Instance item = DataItems.of(stack);
        return item != null && item.def().isShortbow();
    }

    /** Whether a left click with [stack] does something here (so the client bothers to say so). */
    static boolean leftClickItem(ItemStack stack) {
        if (EngineItem.of(stack) == EngineItem.TERMINATOR) {
            return true;
        }
        ItemDefs.Instance item = DataItems.of(stack);
        return item != null && item.def().isShortbow();
    }

    /** Whether a right click with [stack] is ours alone (no vanilla bow draw, rod cast...). */
    static boolean rightClickItem(ItemStack stack) {
        EngineItem engine = EngineItem.of(stack);
        if (engine != null) {
            return engine == EngineItem.TERMINATOR;
        }
        ItemDefs.Instance item = DataItems.of(stack);
        if (item == null) {
            return false;
        }
        return switch (item.def().right) {
            case "shoot", "ability", "class" -> true;
            default -> false;
        };
    }

    // ---------------------------------------------------------------- bows

    /**
     * A shortbow shot: on either click, sharing one cooldown (Shot Cooldown, shortened by the item's
     * Bonus Attack Speed). The Terminator fires three arrows 5.5 degrees apart; its left click is
     * Salvation instead once three of its arrows have landed.
     */
    void shoot(ServerPlayer player, ItemDefs.Def def, boolean left) {
        if (def == null) {
            return;
        }
        boolean terminator = def.id.equals("TERMINATOR");
        if (left && terminator && salvationHits.getOrDefault(player.getUUID(), 0) >= SALVATION_HITS) {
            salvation(player);
            return;
        }
        if (!ready(player, "shot", shotTicks(player, def))) {
            return;
        }
        ItemDefs.Ability bite = left ? def.ability("LEFT CLICK") : null;
        double heal = bite != null && bite.name.equals("Nasty Bite") ? 189 : 0;
        Vec3 eye = player.getEyePosition();
        // The look the arrows leave along (the server's), for checking a scripted player's aim against its client's.
        SimLog.event("shot", "who", player.getName().getString(), "item", def.id, "yaw", player.getYRot(), "pitch", player.getXRot());
        // Hypixel's shot sound (abilities-items.md §7): arrow.shoot 0.5 / 0.698.
        level.playSound(null, eye.x, eye.y, eye.z, SoundEvents.ARROW_SHOOT, SoundSource.MASTER, 0.5f, 0.698f);
        // And the only cooldown packet it sends: the minecraft:bow group for the shot's cooldown (5
        // ticks on a Terminator), then 0 when it ends (vanilla's server cooldowns send that too).
        player.getCooldowns().addCooldown(BOW_GROUP, shotTicks(player, def));
        // Side arrows exactly 5.5 degrees off the server's look (notes/maxor-work/aimnoise).
        float[] spread = terminator ? new float[] {0f, -5.5f, 5.5f} : new float[] {0f};
        for (float offset : spread) {
            fire(player, def.id, player.getYRot() + offset, player.getXRot(), ARROW_SPEED, false, 1.0, heal);
        }
    }

    /**
     * Ticks between [player]'s shots with [def]: its Shot Cooldown shortened by the player's whole
     * Attack Speed (gear, pet, Ragnarok), up to the 100 cap. The Terminator's 0.5 s is 5 ticks at
     * 100, the rate the server Smalldungeons copies measured.
     */
    private static int shotTicks(ServerPlayer player, ItemDefs.Def def) {
        double seconds = def.stats.getOrDefault("Shot Cooldown", 0.5);
        return Math.max(MIN_SHOT_TICKS, CombatMath.shotTicks(PlayerStats.of(player), (int) Math.round(seconds * 20)));
    }

    /**
     * A drawn bow let go (vanilla's BowItem.releaseUsing, which we stand in for): arrows as fast as
     * the draw, hitting for the draw's share. Runaan's Bow adds two side arrows at 40%.
     */
    boolean release(ServerPlayer player, ItemDefs.Instance item, int ticksDrawn) {
        ItemDefs.Def def = item.def();
        if (!"draw".equals(def.right)) {
            return false;
        }
        float power = BowItem.getPowerForTime(ticksDrawn);
        if (power < 0.1f) {
            return true;
        }
        Vec3 eye = player.getEyePosition();
        level.playSound(null, eye.x, eye.y, eye.z, SoundEvents.ARROW_SHOOT, SoundSource.PLAYERS, 1f,
            1f / (level.getRandom().nextFloat() * 0.4f + 1.2f) + power * 0.5f);
        fire(player, def.id, player.getYRot(), player.getXRot(), power * ARROW_SPEED, power >= 1f, power, 0);
        if (def.id.equals("RUNAANS_BOW")) {
            fire(player, def.id, player.getYRot() - 10f, player.getXRot(), power * ARROW_SPEED, false, power * 0.4, 0);
            fire(player, def.id, player.getYRot() + 10f, player.getXRot(), power * ARROW_SPEED, false, power * 0.4, 0);
        }
        return true;
    }

    private void fire(ServerPlayer player, String item, float yawDeg, float pitchDeg, float speed, boolean crit,
                      double multiplier, double heal) {
        Vec3 eye = player.getEyePosition();
        Vec3 dir = ItemSystem.look(yawDeg, pitchDeg);
        Arrow arrow = new Arrow(level, player, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
        arrow.setPos(eye.x, eye.y - 0.1, eye.z);
        arrow.shoot(dir.x, dir.y, dir.z, speed, 0f);
        arrow.setCritArrow(crit);
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        level.addFreshEntity(arrow);
        arrows.put(arrow, new Shot(player.getUUID(), item, multiplier, heal));
        run.schedule(ARROW_LIFE_TICKS, arrow::discard);
    }

    /** One of [shooter]'s arrows reached a run mob: the hit, then the arrow is gone. */
    void arrowHit(ServerPlayer shooter, AbstractArrow arrow, Entity entity) {
        Shot shot = arrows.remove(arrow);
        arrow.discard();
        SimLog.event("shot_hit", "who", shooter.getName().getString(), "mob", entity.getName().getString());
        // An arrow we didn't fire (a plain vanilla one) still hits for the player's damage.
        run.mobs().arrow(shooter, entity, shot == null ? 1.0 : shot.multiplier(), shot != null && shot.item().equals("TERMINATOR"));
        level.playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(), SoundEvents.ARROW_HIT_PLAYER, SoundSource.PLAYERS, 0.4f, 1f);
        if (shot == null) {
            return;
        }
        if (shot.heal() > 0) {
            run.combat().heal(shooter, shot.heal());
        }
        if (shot.item().equals("TERMINATOR")) {
            salvationHits.merge(shooter.getUUID(), 1, Integer::sum);
        }
    }

    /** Salvation: a beam that goes through up to five mobs, until a block stops it. Uses up the three hits. */
    private void salvation(ServerPlayer player) {
        if (!ready(player, "shot", shotTicks(player, ItemDefs.get().def("TERMINATOR")))) {
            return;
        }
        salvationHits.put(player.getUUID(), 0);
        Vec3 from = player.getEyePosition();
        Vec3 to = blockStop(player, from, from.add(player.getLookAngle().scale(SALVATION_RANGE)));
        line(from, to, ParticleTypes.END_ROD, 0.5);
        level.playSound(null, from.x, from.y, from.z, SoundEvents.GUARDIAN_ATTACK, SoundSource.PLAYERS, 1f, 1.6f);
        hitAlong(player, from, to, SALVATION_TARGETS, 1.0);
    }

    // ---------------------------------------------------------------- abilities

    /** A right-click ability, by name; cooldowns from its lore. */
    private void cast(ServerPlayer player, ItemDefs.Def def, ItemDefs.Ability a) {
        // A Mage's Efficient Spells shortens every item ability's cooldown.
        double cdr = PlayerStats.of(player).classBonus.cooldownReduction();
        int cooldown = a.cooldown == null ? MIN_CAST_TICKS : (int) Math.round(a.cooldown * 20 * (1 - cdr));
        // The Creeper Veil comes down on a right click while it's up, and the cooldown is halved.
        if (a.name.equals("Creeper Veil") && run.combat().veiled(player)) {
            run.combat().endVeil(player, Combat.VeilEnd.MANUAL);
            readyAt.put(player.getUUID() + ":ability:" + a.name, level.getGameTime() + Math.max(MIN_CAST_TICKS, cooldown / 2));
            return;
        }
        // Mana at Hypixel's price (Ultimate Wise, 10% off outside combat; Rapid-fire's escalating shots).
        Double mana = a.mana == null ? null : a.name.equals("Rapid-fire") ? items.rapidFireCost(player)
            : run.combat().manaCost(player, a.mana, player.getMainHandItem());
        // Without the Mana, Vitality or Health it costs, the ability doesn't go (nor start its cooldown).
        if (!run.combat().affords(player, mana, a.vitalityCost, a.healthCost)) {
            return;
        }
        if (!ready(player, "ability:" + a.name, Math.max(MIN_CAST_TICKS, cooldown))) {
            if (cooldown > MIN_CAST_TICKS) {
                player.sendSystemMessage(EngineItems.text("§cThis ability is on cooldown for " + secondsLeft(player, "ability:" + a.name) + "s."));
                if (a.name.equals("Spirit Leap")) {
                    level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 8f, 0f);
                }
            }
            return;
        }

        boolean used = switch (a.name) {
            case "Showtime" -> run(() -> items.bonzo(player));
            case "Rapid-fire" -> run(() -> items.jerry(player));
            case "Guided Bat" -> run(() -> items.spiritSceptre(player));
            case "Dragon Rage" -> cone(player, 8, 70, 1.0, ParticleTypes.FLAME, SoundEvents.ENDER_DRAGON_GROWL);
            case "Reaving Strike" -> cone(player, 6, 120, 1.25, ParticleTypes.SWEEP_ATTACK, SoundEvents.PLAYER_ATTACK_SWEEP);
            case "Flay" -> cone(player, 8, 90, 1.0, ParticleTypes.CRIT, SoundEvents.PLAYER_ATTACK_SWEEP);
            case "Ice Spray" -> cone(player, 8, 60, 1.0, ParticleTypes.SNOWFLAKE, SoundEvents.ENDER_DRAGON_GROWL);
            case "Molten Wave" -> cone(player, 10, 40, 1.0, ParticleTypes.LAVA, SoundEvents.BLAZE_SHOOT);
            case "Throw" -> throwWeapon(player);
            case "Swing" -> bones.throwFrom(player);
            case "Ice Bolt" -> beam(player, 20, 1, 1.0, ParticleTypes.SNOWFLAKE, SoundEvents.SNOW_GOLEM_SHOOT);
            case "Dreadlord" -> beam(player, 20, 1, 1.0, ParticleTypes.SMOKE, SoundEvents.WITHER_SHOOT);
            case "Witherlord" -> witherlord(player);
            case "Sinrecall Transmission" -> sinrecall(player);
            case "Petal Barrage" -> ricochet(player, 5, 1.0, false);
            case "Heat-Seeking Rose" -> ricochet(player, 3, 1.0, true);
            case "Giant's Slam" -> slam(player, ahead(player, 3), 6, 1.0, SoundEvents.ANVIL_LAND);
            case "Terrain Toss" -> slam(player, aimed(player, 12), 4, 1.0, SoundEvents.GENERIC_EXPLODE.value());
            case "Burning Souls" -> slam(player, player.position(), 6, 1.0, SoundEvents.BLAZE_AMBIENT);
            case "Implosion" -> slam(player, player.position(), 10, 1.0, SoundEvents.GENERIC_EXPLODE.value());
            case "Shadow Warp" -> slam(player, aimed(player, 10), 7, 1.0, SoundEvents.ENDERMAN_TELEPORT);
            case "Wither Shield" -> shield(player, 100, 0.9, "§aWither Shield §7reduces damage taken by §a10%§7.");
            case "Creeper Veil" -> veil(player, def);
            case "Ragnarock" -> ragnarock(player);
            case "Instant Heal" -> heal(player, 500, 100);
            case "Speed Boost" -> run(() -> player.addEffect(new MobEffectInstance(MobEffects.SPEED, 600, 0, false, false, true)));
            case "Leap" -> leap(player);
            case "Shadowstep" -> shadowstep(player);
            case "Shadow Fury" -> shadowFury(player);
            case "Spirit Leap" -> spiritLeap(player);
            case "Revive" -> revive(player);
            case "Aspiring Leap" -> say(player, "§cYou can only use this on your private island or garden!");
            case "Raise Souls" -> say(player, "§cYou don't have any souls to raise!");
            case "Rapid Fire" -> say(player, "§cRapid Fire isn't in the Rustclear world yet.");
            default -> say(player, "§c" + a.name + " isn't in the Rustclear world yet.");
        };
        if (used) {
            run.combat().spend(player, a.name, mana, a.vitalityCost, a.healthCost);
        }
    }

    /**
     * The Ragnarock Axe (abilities-items.md §2, §10.3): a 3 s channel, then 1.5x the axe's Strength
     * for 10 s, unless anything hurts you first ("Ragnarock was cancelled due to taking damage!").
     * The bar's Mana slot reads "CASTING IN 3s" (two bars each of 3s, 2s, 1s), "CASTING" from +60
     * for 20 ticks (34 of 37 at 59-61), or "CANCELLED". The lever click comes with the cast.
     */
    private static final int RAGNAROCK_CHANNEL = 60;
    private static final int RAGNAROCK_TICKS = 200;

    private boolean ragnarock(ServerPlayer player) {
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.55f, 2f);
        // The axe's own Strength (base, stars, reforge, books), read now: the buff is the cast's.
        double strength = PlayerStats.of(player).flat(StatSheet.Source.HELD, Stat.STRENGTH) * 1.5;
        channel(player, 0, strength);
        return true;
    }

    private void channel(ServerPlayer player, int age, double strength) {
        if (player.isRemoved() || run.combat().isGhost(player)) {
            return;
        }
        if (age > 0 && run.combat().hurtWithin(player, age)) {
            run.combat().manaSlot(player, "§c§lCANCELLED", 20);
            player.sendSystemMessage(EngineItems.text("§cRagnarock was cancelled due to taking damage!"));
            return;
        }
        if (age == RAGNAROCK_CHANNEL) {
            run.combat().manaSlot(player, "§lCASTING", 20);
            run.abilities().buffStrength(player, strength, RAGNAROCK_TICKS);
            return;
        }
        if (age % 20 == 0) {
            run.combat().manaSlot(player, "§lCASTING IN " + (3 - age / 20) + "s", 20);
        }
        run.schedule(1, () -> channel(player, age + 1, strength));
    }

    private static boolean run(Runnable action) {
        action.run();
        return true;
    }

    private static boolean say(ServerPlayer player, String message) {
        player.sendSystemMessage(EngineItems.text(message));
        return false;
    }

    /** Every mob within [range] whose middle is within [degrees] of the player's aim. */
    private boolean cone(ServerPlayer player, double range, double degrees, double multiplier, ParticleOptions particle, SoundEvent sound) {
        Vec3 eye = player.getEyePosition();
        Vec3 aim = player.getLookAngle();
        double cos = Math.cos(Math.toRadians(degrees / 2));
        for (Entity mob : run.mobs().mobsIn(player.getBoundingBox().inflate(range))) {
            Vec3 to = mob.getBoundingBox().getCenter().subtract(eye);
            if (to.length() <= range + 1 && to.normalize().dot(aim) >= cos) {
                run.mobs().hit(player, mob, multiplier);
            }
        }
        for (int i = 1; i <= range; i++) {
            Vec3 at = eye.add(aim.scale(i));
            level.sendParticles(particle, at.x, at.y - 0.3, at.z, 3, i * 0.15, 0.2, i * 0.15, 0.01);
        }
        sound(player, sound);
        return true;
    }

    /** A straight line along the aim, stopped by blocks, hitting up to [max] mobs on it. */
    private boolean beam(ServerPlayer player, double range, int max, double multiplier, ParticleOptions particle, SoundEvent sound) {
        Vec3 from = player.getEyePosition();
        Vec3 to = blockStop(player, from, from.add(player.getLookAngle().scale(range)));
        List<Entity> hit = hitAlong(player, from, to, max, multiplier);
        // A single-target shot stops at what it hit.
        Vec3 end = max == 1 && !hit.isEmpty() ? hit.get(0).getBoundingBox().getCenter() : to;
        line(from, end, particle, 0.6);
        sound(player, sound);
        return true;
    }

    /** Throw: each throw within 3 seconds of the last does twice the damage (from 10% of a hit, up to 16 times). */
    private boolean throwWeapon(ServerPlayer player) {
        long now = level.getGameTime();
        double[] streak = throwStreak.get(player.getUUID());
        double next = streak == null || now > streak[1] ? 1 : Math.min(16, streak[0] * 2);
        throwStreak.put(player.getUUID(), new double[] {next, now + 60});
        return beam(player, 12, Integer.MAX_VALUE, 0.1 * next, ParticleTypes.CRIT, SoundEvents.TRIDENT_THROW.value());
    }

    private boolean witherlord(ServerPlayer player) {
        Vec3 from = player.getEyePosition();
        for (float offset : new float[] {-6f, 0f, 6f}) {
            Vec3 dir = ItemSystem.look(player.getYRot() + offset, player.getXRot());
            Vec3 to = blockStop(player, from, from.add(dir.scale(20)));
            List<Entity> hit = hitAlong(player, from, to, 1, 1.0);
            line(from, hit.isEmpty() ? to : hit.get(0).getBoundingBox().getCenter(), ParticleTypes.SMOKE, 0.6);
        }
        sound(player, SoundEvents.WITHER_SHOOT);
        return true;
    }

    /** Sinseeker's Sinrecall: zap 4 blocks forward, hitting what's in the way. */
    private boolean sinrecall(ServerPlayer player) {
        Vec3 from = player.getEyePosition();
        Vec3 to = blockStop(player, from, from.add(player.getLookAngle().scale(4)));
        hitAlong(player, from, to, Integer.MAX_VALUE, 1.0);
        line(from, to, ParticleTypes.ELECTRIC_SPARK, 0.4);
        var dest = items.transmission(player, 4.0);
        if (dest != null && !items.noItemTeleport(player)) {
            ItemSystem.teleport(player, dest.x, dest.y, dest.z);
        }
        sound(player, SoundEvents.ENDERMAN_TELEPORT);
        return true;
    }

    /** A rose (or barrage) that hits the mob you aim at, then bounces to the nearest ones; Heat-Seeking grows each bounce. */
    private boolean ricochet(ServerPlayer player, int targets, double multiplier, boolean growing) {
        Entity first = aimedMob(player, 20);
        if (first == null) {
            Vec3 from = player.getEyePosition();
            line(from, blockStop(player, from, from.add(player.getLookAngle().scale(20))), ParticleTypes.CHERRY_LEAVES, 0.6);
            sound(player, SoundEvents.AZALEA_LEAVES_BREAK);
            return true;
        }
        List<Entity> chain = new ArrayList<>();
        chain.add(first);
        Entity at = first;
        while (chain.size() < targets) {
            Entity next = null;
            for (Entity mob : run.mobs().mobsIn(at.getBoundingBox().inflate(8))) {
                if (!chain.contains(mob) && (next == null || mob.distanceToSqr(at) < next.distanceToSqr(at))) {
                    next = mob;
                }
            }
            if (next == null) {
                break;
            }
            chain.add(next);
            at = next;
        }
        Vec3 from = player.getEyePosition();
        for (int i = 0; i < chain.size(); i++) {
            Entity mob = chain.get(i);
            Vec3 to = mob.getBoundingBox().getCenter();
            line(from, to, ParticleTypes.CHERRY_LEAVES, 0.5);
            from = to;
            if (!mob.isRemoved()) {
                run.mobs().hit(player, mob, growing ? multiplier * (i + 1) : multiplier);
            }
        }
        sound(player, SoundEvents.AZALEA_LEAVES_BREAK);
        return true;
    }

    /** Every mob within [radius] of [at]. */
    private boolean slam(ServerPlayer player, Vec3 at, double radius, double multiplier, SoundEvent sound) {
        for (Entity mob : run.mobs().mobsIn(new AABB(at, at).inflate(radius))) {
            if (mob.position().distanceTo(at) <= radius + 0.5) {
                run.mobs().hit(player, mob, multiplier);
            }
        }
        level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.5, at.z, 4, radius / 3, 0.3, radius / 3, 0);
        level.playSound(null, at.x, at.y, at.z, sound, SoundSource.PLAYERS, 1f, 1f);
        return true;
    }

    private boolean shield(ServerPlayer player, int ticks, double factor, String message) {
        run.combat().protect(player, ticks, factor);
        player.sendSystemMessage(EngineItems.text(message), true);
        sound(player, SoundEvents.SHIELD_BLOCK.value());
        return true;
    }

    /**
     * The Wither Cloak's Creeper Veil: each layer blocks 900 damage scaled like the item's stats in
     * the Catacombs (the lore's "scales with your Dungeon Stat Boost"; 5,850 recorded on one cloak).
     * OPEN: the 900 base and its exact scaling; the six-creeper ring isn't drawn.
     */
    private boolean veil(ServerPlayer player, ItemDefs.Def def) {
        ItemDefs.Instance held = PlayerStats.instance(player.getMainHandItem());
        int cata = PlayerStats.profile(player).cataLevel();
        double block = held == null ? 900 : ItemStats.inDungeon("Defense", 900, cata, held.stars(), held.masterStars(),
            EngineServer.settings().floorOrDefault().master(), 0);
        run.combat().startVeil(player, block);
        return true;
    }

    /** Instant Heal: [self] health for you and [others] for every player within 8 blocks. */
    private boolean heal(ServerPlayer player, double self, double others) {
        run.combat().heal(player, self);
        for (ServerPlayer other : level.players()) {
            if (other != player && other.distanceTo(player) <= 8) {
                run.combat().heal(other, others);
            }
        }
        level.sendParticles(ParticleTypes.HEART, player.getX(), player.getY() + 1, player.getZ(), 6, 0.5, 0.5, 0.5, 0);
        sound(player, SoundEvents.PLAYER_LEVELUP);
        return true;
    }

    /** Leap: up and forward; the landing hits everything within 4 blocks (GearSystem.tick). */
    private boolean leap(ServerPlayer player) {
        Vec3 aim = player.getLookAngle();
        ItemSystem.push(player, new Vec3(aim.x * 1.2, 0.9, aim.z * 1.2));
        leaping.put(player.getUUID(), level.getGameTime());
        sound(player, SoundEvents.SLIME_JUMP);
        return true;
    }

    /** Shadowstep: behind the mob you look at (up to 20 blocks) and hit it. */
    private boolean shadowstep(ServerPlayer player) {
        Entity mob = aimedMob(player, 20);
        if (mob == null) {
            return say(player, "§cNo enemy in sight!");
        }
        behind(player, mob);
        run.mobs().hit(player, mob, 1.0);
        return true;
    }

    /** Shadow Fury: up to five mobs within 12 blocks, one every 5 ticks, each hit as you arrive. */
    private boolean shadowFury(ServerPlayer player) {
        List<Entity> near = run.mobs().mobsIn(player.getBoundingBox().inflate(12));
        near.sort((a, b) -> Double.compare(a.distanceToSqr(player), b.distanceToSqr(player)));
        if (near.isEmpty()) {
            return say(player, "§cNo enemies nearby!");
        }
        fury.put(player.getUUID(), new ArrayList<>(near.subList(0, Math.min(5, near.size()))));
        return true;
    }

    private void behind(ServerPlayer player, Entity mob) {
        Vec3 facing = ItemSystem.look(mob.getYRot(), 0);
        Vec3 to = mob.position().subtract(facing.scale(1.2));
        if (!level.noCollision(player, player.getBoundingBox().move(to.subtract(player.position())))) {
            to = mob.position();
        }
        float yaw = mob.getYRot();
        player.teleportTo(level, to.x, to.y, to.z, java.util.Set.<Relative>of(), yaw, player.getXRot(), false);
        player.resetFallDistance();
        level.playSound(null, to.x, to.y, to.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1f, 1f);
    }

    /** Spirit Leap: the leap menu (LeapMenu); a plain Spirit Leap is used up by a leap, the Infinileap isn't. */
    private boolean spiritLeap(ServerPlayer player) {
        ItemStack held = player.getMainHandItem();
        ItemDefs.Instance item = DataItems.of(held);
        boolean consumable = item != null && item.def().id.equals("SPIRIT_LEAP");
        LeapMenu.open(player, () -> {
            // "Grants 1 second of immunity after teleporting" (the item's lore).
            run.combat().protect(player, 20, 0);
            if (consumable && DataItems.of(player.getMainHandItem()) != null
                && DataItems.of(player.getMainHandItem()).def().id.equals("SPIRIT_LEAP")) {
                player.getMainHandItem().shrink(1);
            }
        });
        return true;
    }

    /**
     * A Revive Stone used by hand. Ghosts are revived by a teammate anyway (Ghosts), so with a ghost about it's
     * Hypixel's "someone else" line; with none, its "no players" line (boss-p4-necron.md F9).
     */
    private boolean revive(ServerPlayer player) {
        if (!run.combat().ghosts().any()) {
            return say(player, "§cThere are no players available to revive right now!");
        }
        return say(player, "§cSomeone else is currently reviving that player!");
    }

    // ---------------------------------------------------------------- helpers

    /** Hits up to [max] mobs on the segment, nearest first; returns them. */
    private List<Entity> hitAlong(ServerPlayer player, Vec3 from, Vec3 to, int max, double multiplier) {
        List<Entity> hit = run.mobs().mobsAlong(from, to, 0.3);
        if (hit.size() > max) {
            hit = new ArrayList<>(hit.subList(0, max));
        }
        for (Entity mob : hit) {
            run.mobs().hit(player, mob, multiplier);
        }
        return hit;
    }

    /** Where the segment first meets a block, or [to]. */
    private Vec3 blockStop(ServerPlayer player, Vec3 from, Vec3 to) {
        var clip = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return clip.getType() == HitResult.Type.MISS ? to : clip.getLocation();
    }

    /** The mob nearest along the player's aim within [range] (blocks in the way stop it), or null. */
    private Entity aimedMob(ServerPlayer player, double range) {
        Vec3 from = player.getEyePosition();
        Vec3 to = blockStop(player, from, from.add(player.getLookAngle().scale(range)));
        List<Entity> hit = run.mobs().mobsAlong(from, to, 0.6);
        return hit.isEmpty() ? null : hit.get(0);
    }

    /** The block point the player aims at within [range], or the point that far along the aim. */
    private Vec3 aimed(ServerPlayer player, double range) {
        Vec3 from = player.getEyePosition();
        return blockStop(player, from, from.add(player.getLookAngle().scale(range)));
    }

    private static Vec3 ahead(ServerPlayer player, double blocks) {
        Vec3 flat = ItemSystem.look(player.getYRot(), 0);
        return player.position().add(flat.scale(blocks));
    }

    private void line(Vec3 from, Vec3 to, ParticleOptions particle, double step) {
        Vec3 d = to.subtract(from);
        int n = Math.max(1, (int) (d.length() / step));
        for (int i = 0; i <= n; i++) {
            Vec3 at = from.add(d.scale((double) i / n));
            level.sendParticles(particle, at.x, at.y, at.z, 1, 0, 0, 0, 0);
        }
    }

    private void sound(ServerPlayer player, SoundEvent sound) {
        level.playSound(null, player.getX(), player.getY(), player.getZ(), sound, SoundSource.PLAYERS, 1f, 1f);
    }

    /** True (and starts [ticks] of cooldown) when [player]'s [key] is ready. */
    private boolean ready(ServerPlayer player, String key, int ticks) {
        long now = level.getGameTime();
        String k = player.getUUID() + ":" + key;
        Long at = readyAt.get(k);
        if (at != null && now < at) {
            return false;
        }
        readyAt.put(k, now + ticks);
        return true;
    }

    private String secondsLeft(ServerPlayer player, String key) {
        Long at = readyAt.get(player.getUUID() + ":" + key);
        long left = at == null ? 0 : Math.max(0, at - level.getGameTime());
        // A whole number of seconds, rounded up (abilities-items.md §10.3: "15s", never "14.5s").
        return Long.toString((long) Math.ceil(left / 20.0));
    }

    // ---------------------------------------------------------------- ticking

    void tick() {
        bones.tick();
        arrows.keySet().removeIf(Entity::isRemoved);
        long now = level.getGameTime();
        for (Iterator<Map.Entry<UUID, Long>> it = leaping.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Long> e = it.next();
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(e.getKey());
            if (player == null || player.level() != level || now - e.getValue() > 60) {
                it.remove();
            } else if (now - e.getValue() > 3 && player.onGround()) {
                it.remove();
                slam(player, player.position(), 4, 1.0, SoundEvents.GENERIC_EXPLODE.value());
            }
        }
        if (now % 5 == 0) {
            for (Iterator<Map.Entry<UUID, List<Entity>>> it = fury.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<UUID, List<Entity>> e = it.next();
                ServerPlayer player = level.getServer().getPlayerList().getPlayer(e.getKey());
                List<Entity> left = e.getValue();
                left.removeIf(Entity::isRemoved);
                if (player == null || player.level() != level || left.isEmpty()) {
                    it.remove();
                    continue;
                }
                Entity mob = left.remove(0);
                behind(player, mob);
                run.mobs().hit(player, mob, 1.0);
            }
        }
    }

    void dispose() {
        arrows.keySet().forEach(Entity::discard);
        arrows.clear();
        leaping.clear();
        fury.clear();
        bones.dispose();
    }
}
