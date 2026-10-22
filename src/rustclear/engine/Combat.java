package net.rustclear.mod.engine;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;

/**
 * Players' SkyBlock health in one run, from their stats (PlayerStats): max Health, Defense,
 * Vitality, Mending and Health Regen all come from gear, class and levels. A mob's hit takes its
 * floor damage (MobSystem.Archetype) less Defense, Hypixel's way: damage x 100 / (Defense + 100)
 * (CombatMath). Absorption (a Tank's Protective Barrier, a Healer's Wish) soaks hits first, and a
 * Tank within 30 blocks takes 80% of a teammate's hits (Diversion). Healing received is scaled by
 * Vitality, healing given (and, since Jul 2026, self-healing) by Mending.
 *
 * The health shows on the action bar as Hypixel's does ("2400/3000❤     900❈ Defense
 * 1600/1600✎ Mana"), which client mods read, and as the vanilla hearts. At zero the player dies as
 * on Hypixel: the worn Spirit or Bonzo's Mask, then a Phoenix pet, saves them if off cooldown; otherwise
 * "☠ ... and became a ghost", a death on the score, and a ghost until revived ([Ghosts]). Vanilla damage stays off
 * (EngineServer's ALLOW_DAMAGE).
 *
 * Mana (100 + Intelligence) and Vitality (the Vitality stat, base 100) are pools too: item abilities
 * spend them ([spend]: Mana Cost, Vitality Cost, Health Cost) and can't be used without enough.
 * Mana refills 2.2% of its max a second, Vitality about 5% every 10 ticks (abilities-items.md §10.2).
 */
final class Combat {
    /** Ticks after a hit before regen starts again. */
    private static final int COMBAT_TICKS = 60;
    /**
     * A save's damage immunity: the next death tick 60 later always kills or procs the next item, so it ends within
     * 58-59 ticks (abilities-items.md §3; the lore's 3 s would be 60).
     */
    private static final int SAVE_IMMUNITY_TICKS = 58;
    /** Save cooldowns: Spirit Mask 30 s, Bonzo's Mask 180 s, Phoenix 60 s (lore; the shortest measured gaps agree). */
    private static final int SPIRIT_COOLDOWN = 600;
    private static final int BONZO_COOLDOWN = 3600;
    private static final int PHOENIX_COOLDOWN = 1200;
    /** Diversion: a Tank this close takes this share of a teammate's hits. */
    private static final double DIVERSION_RANGE = 30;
    private static final double DIVERSION_SHARE = 0.8;
    private static final double AURA_RANGE = 8;

    private final DungeonRun run;
    private final Map<UUID, Double> health = new HashMap<>();
    private final Map<UUID, Double> absorption = new HashMap<>();
    private final Map<UUID, Double> mana = new HashMap<>();
    private final Map<UUID, Double> vitality = new HashMap<>();
    private final Map<UUID, Long> lastHit = new HashMap<>();
    /** When an enemy (a mob or boss attack) last damaged each player: abilities cost full price for 100 ticks after. */
    private final Map<UUID, Long> enemyHit = new HashMap<>();
    /** Players whose Vitality doesn't regenerate (the Wither Cloak's veil is up). */
    private final java.util.Set<UUID> vitalityFrozen = new java.util.HashSet<>();
    /** When each Tank's Protective Barrier shield is ready again. */
    private final Map<UUID, Long> barrierReady = new HashMap<>();
    private final Ghosts ghosts;
    /** When each player's mask or pet save is ready again, by "uuid:item". */
    private final Map<String, Long> saveReady = new HashMap<>();
    /** Damage taken is multiplied by the factor until the tick (Wither Shield, Creeper Veil, Castle of Stone). */
    private final Map<UUID, double[]> protection = new HashMap<>();
    private long ticks;

    Combat(DungeonRun run) {
        this.run = run;
        this.ghosts = new Ghosts(run);
    }

    Ghosts ghosts() {
        return ghosts;
    }

    private static StatSheet stats(ServerPlayer player) {
        return PlayerStats.of(player);
    }

    double max(ServerPlayer player) {
        return Math.max(1, stats(player).get(Stat.HEALTH));
    }

    double health(ServerPlayer player) {
        return health.computeIfAbsent(player.getUUID(), id -> max(player));
    }

    double absorption(ServerPlayer player) {
        return absorption.getOrDefault(player.getUUID(), 0.0);
    }

    boolean isGhost(ServerPlayer player) {
        return ghosts.contains(player);
    }

    private final java.util.Map<java.util.UUID, net.minecraft.world.entity.Entity> attackers = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, Integer> attackedAt = new java.util.HashMap<>();

    void attackedBy(ServerPlayer player, net.minecraft.world.entity.Entity mob) {
        attackers.put(player.getUUID(), mob);
        attackedAt.put(player.getUUID(), player.tickCount);
    }

    /** The living mob that hit [player] in the last [ticks] ticks, or null. */
    net.minecraft.world.entity.Entity attacker(ServerPlayer player, int ticks) {
        var mob = attackers.get(player.getUUID());
        Integer at = attackedAt.get(player.getUUID());
        return mob == null || at == null || player.tickCount - at > ticks || !mob.isAlive() || mob.isRemoved() ? null : mob;
    }

    /** A dungeon mob of [archetype] hit [player] (melee or arrow). A Tank close by takes most of it. */
    void mobHit(ServerPlayer player, MobSystem.Archetype archetype) {
        mobHit(player, archetype, null);
    }

    /** The same, with the name a death line gives the mob ("Golden Flamer"; null: its archetype's). */
    void mobHit(ServerPlayer player, MobSystem.Archetype archetype, String killer) {
        if (archetype == null || isGhost(player) || player.isSpectator()) {
            return;
        }
        double raw = archetype.stats(run.floor).damage();
        if (raw <= 0) {
            return;
        }
        ServerPlayer tank = diverter(player);
        if (tank != null) {
            take(tank, raw * DIVERSION_SHARE, killer != null ? killer : archetype.fullName, false);
            raw *= 1 - DIVERSION_SHARE;
        }
        take(player, raw, killer != null ? killer : archetype.fullName, false);
    }

    /**
     * A boss's named attack hits [player] for [raw]: Hypixel's line ("Storm's Static Field hit you
     * for 10,800 damage.", the attack's own number before Defense), then the hit itself, less
     * Defense, or True Defense for true damage.
     */
    void bossHit(ServerPlayer player, net.rustclear.mod.engine.boss.Attacks.Attack attack, double raw) {
        if (isGhost(player) || player.isSpectator()) {
            return;
        }
        player.sendSystemMessage(EngineItems.styled(attack.line(raw)));
        take(player, raw, attack.owner(), attack.trueDamage());
    }

    /** "A Crypt Wither Skull exploded, hitting you for 9,000 damage." (P1's skulls; 120,000 on M7; boss-p1-maxor.md §4). */
    void cryptSkull(ServerPlayer player) {
        double raw = run.floor.master() ? 120_000 : 9_000;
        campHit(player, "§7A Crypt Wither Skull exploded, hitting you for §c" + net.rustclear.mod.engine.boss.Attacks.number(raw)
            + "§7 damage.", raw, false, "Crypt Wither Skull");
    }

    /** A Watcher camp attack ([line] in chat, then [raw] less Defense; a death names [killer]). */
    void campHit(ServerPlayer player, String line, double raw, boolean trueDamage, String killer) {
        if (isGhost(player) || player.isSpectator()) {
            return;
        }
        player.sendSystemMessage(EngineItems.styled(line));
        take(player, raw, killer, trueDamage);
    }

    /** The Tank who'd take [player]'s hits (Diversion), or null. */
    private ServerPlayer diverter(ServerPlayer player) {
        if (PlayerStats.classOf(player) == DungeonClass.TANK) {
            return null;
        }
        for (ServerPlayer other : run.level.players()) {
            if (other != player && !isGhost(other) && !other.isSpectator() && PlayerStats.classOf(other) == DungeonClass.TANK
                && other.distanceTo(player) <= DIVERSION_RANGE) {
                return other;
            }
        }
        return null;
    }

    /** An Archer's Bone Plating takes a whole hit this often (gaps between lines: mode 300 ticks, 344 lines). */
    static final long BONE_PLATING_TICKS = 300;
    private final Map<UUID, Long> platedAt = new HashMap<>();

    /** [raw] damage on [player], less their Defense and any protection, absorption first. */
    private void take(ServerPlayer player, double raw, String killer, boolean trueDamage) {
        double[] shield = protection.get(player.getUUID());
        double factor = shield != null && ticks < shield[0] ? shield[1] : 1;
        if (factor <= 0) {
            return;
        }
        StatSheet s = stats(player);
        double taken = CombatMath.damageTaken(s, raw, trueDamage) * factor * s.takenFrom(killer);
        if (s.bigHitTaken != 1 && taken >= 0.5 * s.get(Stat.HEALTH)) {
            taken *= s.bigHitTaken;
        }
        enemyHit.put(player.getUUID(), ticks);
        if (veiled(player)) {
            taken = throughVeil(player, taken);
            if (taken <= 0) {
                run.level.getChunkSource().sendToTrackingPlayersAndSelf(player, new ClientboundHurtAnimationPacket(player));
                return;
            }
        }
        if (s.dungeonClass == DungeonClass.ARCHER && ticks - platedAt.getOrDefault(player.getUUID(), -BONE_PLATING_TICKS)
            >= BONE_PLATING_TICKS) {
            // Bone Plating: the whole hit, once every 300 ticks (abilities-items.md "Bone Plating"). OPEN: a cap.
            platedAt.put(player.getUUID(), ticks);
            player.sendSystemMessage(EngineItems.text("§eYour bone plating reduced the damage you took by §c"
                + combatNumber(taken) + "§e!"));
            run.level.getChunkSource().sendToTrackingPlayersAndSelf(player, new ClientboundHurtAnimationPacket(player));
            return;
        }
        double soak = Math.min(absorption(player), taken);
        if (soak > 0) {
            absorption.put(player.getUUID(), absorption(player) - soak);
            taken -= soak;
        }
        run.milestones().taken(player, taken);
        double left = health(player) - taken;
        // Every hit a player takes: what, how much after Defense, the HP left of the max (comparing a scripted host's
        // survival with its bots').
        SimLog.event("hurt", "who", player.getName().getString(), "by", killer, "raw", Math.round(raw), "taken", Math.round(taken),
            "hp", Math.round(left), "max", Math.round(max(player)), "def", Math.round(s.get(Stat.DEFENSE)));
        health.put(player.getUUID(), left);
        lastHit.put(player.getUUID(), ticks);
        enemyHit.put(player.getUUID(), ticks);
        run.level.getChunkSource().sendToTrackingPlayersAndSelf(player, new ClientboundHurtAnimationPacket(player));
        run.level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 1f, 1f);
        if (left <= 0) {
            die(player, killer);
            return;
        }
        barrier(player, s, left);
        sync(player);
    }

    /** A Tank's Protective Barrier: an absorption shield when their health drops low, on a cooldown. */
    private void barrier(ServerPlayer player, StatSheet s, double now) {
        DungeonClass.Bonus b = s.classBonus;
        if (s.dungeonClass != DungeonClass.TANK || b.shieldPercent() <= 0) {
            return;
        }
        double max = s.get(Stat.HEALTH);
        if (now / max >= b.shieldBelow() || ticks < barrierReady.getOrDefault(player.getUUID(), 0L)) {
            return;
        }
        barrierReady.put(player.getUUID(), ticks + b.shieldCooldownTicks());
        shield(player, max * b.shieldPercent());
        player.sendSystemMessage(EngineItems.text("§aYour §6Protective Barrier §agranted you §e"
            + Stat.number(max * b.shieldPercent()) + " §aabsorption!"));
    }

    /** Each player's own deaths (the end block's "Deaths: n"). */
    final java.util.Map<java.util.UUID, Integer> deathsOf = new java.util.HashMap<>();

    void kill(ServerPlayer player, String killer) {
        if (!isGhost(player)) {
            health.put(player.getUUID(), 0.0);
            die(player, killer);
        }
    }

    private void die(ServerPlayer player, String killer) {
        if (save(player)) {
            return;
        }
        absorption.remove(player.getUUID());
        run.score.deaths++;
        deathsOf.merge(player.getUUID(), 1, Integer::sum);
        ghosts.kill(player, killer);
    }

    /**
     * The worn mask, else a Phoenix pet, saves [player] from dying when off cooldown (the worn helmet always procs
     * first: 0 of 93 procs broke that order). Lines, sounds and particles as recorded (findings-masks.md MASKS-04..09).
     */
    private boolean save(ServerPlayer player) {
        ItemDefs.Instance helmet = DataItems.of(player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD));
        String helmetId = helmet == null ? "" : helmet.def().id;
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        if (helmetId.endsWith("SPIRIT_MASK") && saveReady(player, "spirit", SPIRIT_COOLDOWN)) {
            sound(player, SoundEvents.GENERIC_EAT.value(), SoundSource.PLAYERS, 0.9f, 0.59f);
            sound(player, SoundEvents.ZOMBIE_VILLAGER_CURE, SoundSource.HOSTILE, 1f, 2f);
            player.sendSystemMessage(EngineItems.text("§6Second Wind Activated§a! Your Spirit Mask saved your life!"));
            // "Instead of dying, gain +50 Speed and damage immunity for 3 seconds. Also heals you for 10% of your
            // Health over 5 seconds."
            health.put(player.getUUID(), 1.0);
            run.abilities().buffSpeed(player, 50, 60);
            for (int t = 20; t <= 100; t += 20) {
                run.schedule(t, () -> heal(player, max(player) * 0.02));
            }
        } else if (helmetId.endsWith("BONZO_MASK") && saveReady(player, "bonzo", BONZO_COOLDOWN)) {
            sound(player, SoundEvents.ZOMBIE_VILLAGER_CURE, SoundSource.HOSTILE, 1f, 2f);
            player.sendSystemMessage(EngineItems.text("§aYour §9Bonzo's Mask §asaved your life!"));
            // "immunity and +40 Strength for 3s and fully replenish your health"
            health.put(player.getUUID(), max(player));
            run.abilities().buffStrength(player, 40, 60);
            sound(player, SoundEvents.GENERIC_EAT.value(), SoundSource.PLAYERS, 0.9f, 0.59f);
        } else if (phoenix(player) && saveReady(player, "phoenix", PHOENIX_COOLDOWN)) {
            player.sendSystemMessage(EngineItems.text("§eYour §cPhoenix Pet §esaved you from certain death!"));
            sound(player, SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS, 1f, 1.49f);
            sound(player, SoundEvents.ZOMBIE_INFECT, SoundSource.HOSTILE, 1f, 1.19f);
            sound(player, SoundEvents.ZOMBIE_INFECT, SoundSource.HOSTILE, 1f, 1.19f);
            sound(player, SoundEvents.GHAST_SCREAM, SoundSource.MASTER, 1f, 1.41f + (float) Math.random() * 0.15f);
            run.level.sendParticles(net.minecraft.core.particles.ParticleTypes.LAVA, x, y, z, 18, 0.1, 0.1, 0.1, 0.08);
            // How much the Phoenix heals isn't recorded (findings-masks.md: set_health is always 40); full here.
            health.put(player.getUUID(), max(player));
        } else {
            return false;
        }
        run.level.sendParticles(net.minecraft.core.particles.ParticleTypes.EXPLOSION, x, y, z, 3, 1, 1, 1, 0);
        sound(player, SoundEvents.WITHER_AMBIENT, SoundSource.HOSTILE, 1f, 1f);
        protect(player, SAVE_IMMUNITY_TICKS, 0);
        sync(player);
        return true;
    }

    private boolean saveReady(ServerPlayer player, String item, int cooldown) {
        String key = player.getUUID() + ":" + item;
        if (ticks < saveReady.getOrDefault(key, 0L)) {
            return false;
        }
        saveReady.put(key, ticks + cooldown);
        return true;
    }

    private static boolean phoenix(ServerPlayer player) {
        Pets.Pet pet = Pets.active(player);
        return pet != null && pet.type() == PetDefs.Type.PHOENIX;
    }

    private void sound(ServerPlayer player, net.minecraft.sounds.SoundEvent sound, SoundSource source, float volume, float pitch) {
        run.level.playSound(null, player.getX(), player.getY(), player.getZ(), sound, source, volume, pitch);
    }

    /** [player] heals themself by [amount] (an item, Bloodlust): x Vitality and Mending. */
    void heal(ServerPlayer player, double amount) {
        if (isGhost(player)) {
            return;
        }
        StatSheet s = stats(player);
        add(player, CombatMath.healReceived(s, CombatMath.healGiven(s, amount)));
    }

    /** [healer] heals [target] by [amount]: x the healer's Mending (and Renew) and the target's Vitality. */
    void heal(ServerPlayer target, double amount, ServerPlayer healer) {
        if (isGhost(target)) {
            return;
        }
        StatSheet h = stats(healer);
        double given = CombatMath.healGiven(h, amount) * (1 + h.classBonus.healingBonus());
        add(target, CombatMath.healReceived(stats(target), given));
    }

    private void add(ServerPlayer player, double amount) {
        double now = health(player);
        double after = Math.min(max(player), now + amount);
        healingDone += Math.max(0, after - now);
        health.put(player.getUUID(), after);
        sync(player);
    }

    /** Health restored by heals this run (the tab's Team Healing Done; OPEN: what Hypixel counts there; regen isn't in it). */
    double healingDone;

    /** Absorption on top of health (Protective Barrier, Wish, Overheal). */
    void shield(ServerPlayer player, double amount) {
        if (!isGhost(player) && amount > 0) {
            absorption.merge(player.getUUID(), amount, Double::sum);
            sync(player);
        }
    }

    /** Full health for [player] (Wish). */
    void healFull(ServerPlayer player) {
        if (!isGhost(player)) {
            health.put(player.getUUID(), max(player));
            sync(player);
        }
    }

    /** For [ticks], hits on [player] do [factor] of their damage (0: none). */
    void protect(ServerPlayer player, int ticks, double factor) {
        protection.put(player.getUUID(), new double[] {this.ticks + ticks, factor});
    }

    /** [player] was just revived ([Ghosts]): full health, fresh pools. */
    void revived(ServerPlayer player) {
        health.put(player.getUUID(), max(player));
        mana.remove(player.getUUID());
        vitality.remove(player.getUUID());
        sync(player);
    }

    /**
     * Hyperion's Wither Shield (the third scroll): an absorption shield of 150% of [player]'s Crit Damage as
     * health for 5 seconds, then 50% of the shield turns into healing (the scroll's lore). Measured: the yellow
     * hearts come with the shield sound and go 92-125 ticks later (median ~100; c48518). OPEN: whether the heal is
     * half of the shield given or of what is left of it (the vanilla hearts are capped, so the size can't be
     * read): half of the shield given here.
     */
    void witherShield(ServerPlayer player) {
        if (isGhost(player)) {
            return;
        }
        double amount = 1.5 * stats(player).get(Stat.CRIT_DAMAGE);
        shieldFor(player, amount, 100);
        run.schedule(100, () -> heal(player, amount * 0.5));
    }

    /** [amount] absorption on [player] that wears off after [duration] ticks (a Tank ghost's Absorption Potion). */
    void shieldFor(ServerPlayer player, double amount, int duration) {
        shield(player, amount);
        run.schedule(duration, () -> {
            double left = absorption(player);
            if (left > 0) {
                absorption.put(player.getUUID(), Math.max(0, left - amount));
                sync(player);
            }
        });
    }

    void tick() {
        ticks++;
        ghosts.tick();
        boolean second = ticks % 20 == 0;
        for (ServerPlayer player : run.level.players()) {
            // The hunger bar never moves on Hypixel (food 20, saturation 20 in every recorded "me" line); vanilla's
            // drained it with sprinting and jumping until a long session couldn't sprint any more.
            player.getFoodData().setFoodLevel(20);
            player.getFoodData().setSaturation(20f);
            if (isGhost(player)) {
                continue;
            }
            StatSheet s = stats(player);
            double max = Math.max(1, s.get(Stat.HEALTH));
            double now = Math.min(health(player), max);
            Long hit = lastHit.get(player.getUUID());
            if (second && now < max && (hit == null || ticks - hit > COMBAT_TICKS)) {
                now = Math.min(max, now + CombatMath.regenPerSecond(s));
            }
            health.put(player.getUUID(), now);
            if (second) {
                double maxMana = CombatMath.maxMana(s);
                mana.put(player.getUUID(), Math.min(maxMana, mana(player) + CombatMath.manaRegenPerSecond(s)));
            }
            if (ticks % VITALITY_TICKS == 0 && !vitalityFrozen.contains(player.getUUID())) {
                // About 5% of max every 10 ticks (abilities-items.md §10.2: +6 a bar at max 123).
                double maxVitality = maxVitality(s);
                vitality.put(player.getUUID(), Math.min(maxVitality, vitality(player) + maxVitality * 0.05));
            }
            double[] veil = veils.get(player.getUUID());
            if (veil != null && ticks >= veil[0]) {
                endVeil(player, VeilEnd.EXPIRED);
            }
            if (second && s.dungeonClass == DungeonClass.HEALER) {
                aura(player, s);
            }
            sync(player);
            if (ticks % ACTION_BAR_TICKS == 0) {
                actionBar(player);
            }
        }
    }

    private static double maxVitality(StatSheet s) {
        return Math.max(0, s.get(Stat.VITALITY));
    }

    /** [player]'s Mana now (full until they first spend some). */
    double mana(ServerPlayer player) {
        return Math.min(mana.getOrDefault(player.getUUID(), Double.MAX_VALUE), CombatMath.maxMana(stats(player)));
    }

    /** [player]'s Vitality now (full until they first spend some). */
    double vitality(ServerPlayer player) {
        return Math.min(vitality.getOrDefault(player.getUUID(), Double.MAX_VALUE), maxVitality(stats(player)));
    }

    /**
     * Pays for [ability]: [manaCost] Mana, [vitalityCost] Vitality and [healthCost] Health (null or 0:
     * none). With too little of any, nothing is spent, the action bar says so as Hypixel's does, and
     * it's false. A Health cost can't take the last of your health.
     */
    boolean spend(ServerPlayer player, String ability, Double manaCost, Double vitalityCost, Double healthCost) {
        if (!affords(player, manaCost, vitalityCost, healthCost)) {
            return false;
        }
        double m = manaCost == null ? 0 : manaCost;
        double v = vitalityCost == null ? 0 : vitalityCost;
        double h = healthCost == null ? 0 : healthCost;
        if (m > 0) {
            mana.put(player.getUUID(), mana(player) - m);
        }
        if (v > 0) {
            vitality.put(player.getUUID(), vitality(player) - v);
        }
        if (h > 0) {
            health.put(player.getUUID(), health(player) - h);
        }
        if (m > 0 || v > 0 || h > 0) {
            // Hypixel's bar shows no cost line for a cast (only Rapid-fire's, ItemSystem.rapidFire).
            sync(player);
        }
        return true;
    }

    /**
     * An ability's Mana cost on Hypixel (abilities-items.md §10.1): [base] x (1 - 10% a level of
     * Ultimate Wise on [held]) x 0.9 unless an enemy damaged [player] in the last 100 ticks, rounded
     * half up. OPEN: what gives the 10% off outside combat (no lore shows it).
     */
    double manaCost(ServerPlayer player, double base, ItemStack held) {
        CustomData data = held == null ? null : held.get(DataComponents.CUSTOM_DATA);
        double wise = data == null ? 1 : DataItems.mods(data.copyTag()).manaMultiplier();
        return cost(base, wise, fullPrice(player));
    }

    /** [base] x [wise] x 0.9 unless [full], rounded half up. */
    static double cost(double base, double wise, boolean full) {
        return Math.floor(base * wise * (full ? 1 : 0.9) + 0.5);
    }

    /** Whether an enemy hit [player] in the last 100 ticks (abilities cost full price). */
    boolean fullPrice(ServerPlayer player) {
        Long hit = enemyHit.get(player.getUUID());
        return hit != null && ticks - hit < FULL_PRICE_TICKS;
    }

    /** Each veiled player's {end tick, damage a layer blocks}: the Wither Cloak's Creeper Veil. */
    private final Map<UUID, double[]> veils = new HashMap<>();
    private static final int VEIL_TICKS = 200;
    private static final double VEIL_LAYER_VITALITY = 30;

    boolean veiled(ServerPlayer player) {
        return veils.containsKey(player.getUUID());
    }

    /**
     * The Creeper Veil goes up (abilities-items.md §10.2): for 10 s each hit takes layers, 30
     * Vitality each and up to [block] damage a layer, and Vitality doesn't regenerate. Activation is
     * free and silent; the creeper hurt sound plays 20 ticks in.
     */
    void startVeil(ServerPlayer player, double block) {
        veils.put(player.getUUID(), new double[] {ticks + VEIL_TICKS, block});
        freezeVitality(player, true);
        player.sendSystemMessage(EngineItems.text("§dCreeper Veil §aActivated!"));
        run.schedule(20, () -> {
            if (veiled(player)) {
                run.level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.CREEPER_HURT, SoundSource.HOSTILE, 4f, 1.19f);
            }
        });
    }

    /** Why a veil ends. */
    enum VeilEnd { MANUAL, EXPIRED, VITALITY }

    /** The veil comes down, with Hypixel's line for [why] and the skeleton hurt sound twice. */
    void endVeil(ServerPlayer player, VeilEnd why) {
        if (veils.remove(player.getUUID()) == null) {
            return;
        }
        freezeVitality(player, false);
        player.sendSystemMessage(EngineItems.text(switch (why) {
            case MANUAL -> "§dCreeper Veil §cDe-activated!";
            case EXPIRED -> "§dCreeper Veil §cDe-activated! §8(Expired)";
            case VITALITY -> "§cNot enough vitality! §dCreeper Veil §cDe-activated!";
        }));
        Runnable sound = () -> run.level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.SKELETON_HURT,
            SoundSource.HOSTILE, 4f, 1.19f);
        run.schedule(1, sound);
        run.schedule(7, sound);
    }

    /** [taken] damage through the veil's layers: what's left over after the layers it could pay for. */
    private double throughVeil(ServerPlayer player, double taken) {
        double[] veil = veils.get(player.getUUID());
        while (taken > 0) {
            if (vitality(player) < VEIL_LAYER_VITALITY) {
                // Out of Vitality: all of it goes, a slight knockback, and the veil ends.
                drainVitality(player, vitality(player));
                Vec3 look = player.getLookAngle();
                ItemSystem.push(player, new Vec3(-look.x * 0.4, 0.3, -look.z * 0.4));
                endVeil(player, VeilEnd.VITALITY);
                return taken;
            }
            drainVitality(player, VEIL_LAYER_VITALITY);
            taken -= veil[1];
        }
        return 0;
    }

    /** Freezes (or lets go) [player]'s Vitality regen: the Creeper Veil stops it. */
    void freezeVitality(ServerPlayer player, boolean frozen) {
        if (frozen) {
            vitalityFrozen.add(player.getUUID());
        } else {
            vitalityFrozen.remove(player.getUUID());
        }
    }

    /** Takes up to [amount] Vitality from [player]; what was taken. */
    double drainVitality(ServerPlayer player, double amount) {
        double has = vitality(player);
        double taken = Math.min(has, amount);
        vitality.put(player.getUUID(), has - taken);
        return taken;
    }

    /** The action bar's middle shows [text] for a second (Hypixel does this only for Rapid-fire). */
    void castLine(ServerPlayer player, String text) {
        lastCast.put(player.getUUID(), new Object[] {ticks, text});
    }

    /** Whether [player] has the Mana, Vitality and Health a cast costs; if not, the action bar says which. */
    boolean affords(ServerPlayer player, Double manaCost, Double vitalityCost, Double healthCost) {
        double m = manaCost == null ? 0 : manaCost;
        double v = vitalityCost == null ? 0 : vitalityCost;
        double h = healthCost == null ? 0 : healthCost;
        if (m > 0 && mana(player) < m) {
            player.sendOverlayMessage(EngineItems.text("§c§lNOT ENOUGH MANA"));
            return false;
        }
        if (v > 0 && vitality(player) < v) {
            player.sendOverlayMessage(EngineItems.text("§c§lNOT ENOUGH VITALITY"));
            return false;
        }
        if (h > 0 && health(player) <= h) {
            player.sendOverlayMessage(EngineItems.text("§c§lNOT ENOUGH HEALTH"));
            return false;
        }
        return true;
    }

    /** The last cost paid, shown on the action bar for a second in place of Defense, as Hypixel does: {tick, text}. */
    private final Map<UUID, Object[]> lastCast = new HashMap<>();
    /**
     * Combat XP per kill on the action bar, in Defense's place for three frames (abilities-items.md
     * "Combat XP"): medians of 1,051 clear and 989 boss gains on maxed profiles. OPEN: the gain per mob
     * type and Combat Wisdom, who besides the killer gets it, and the format below level 60.
     */
    static final double CLEAR_COMBAT_XP = 520.7;
    static final double BOSS_COMBAT_XP = 771.9;
    /** Combat 60's total XP, where a maxed profile's count starts. */
    static final double COMBAT_60_XP = 111_672_425;
    private static final int COMBAT_XP_TICKS = 30;
    /** {tick, text} of the last kill's XP, and each player's running total (kept across runs, like a profile's). */
    private final Map<UUID, Object[]> lastCombatXp = new HashMap<>();
    private static final Map<UUID, Double> combatXp = new java.util.concurrent.ConcurrentHashMap<>();

    /** [player] killed a mob: "+520.7 Combat (111,673,000/0)" for three action-bar frames. */
    void combatXp(ServerPlayer player, boolean boss) {
        if (PlayerStats.profile(player).skill("combat", 60) < 60) {
            return;
        }
        double gain = boss ? BOSS_COMBAT_XP : CLEAR_COMBAT_XP;
        combatXp.putIfAbsent(player.getUUID(), COMBAT_60_XP);
        double total = combatXp.merge(player.getUUID(), gain, Double::sum);
        lastCombatXp.put(player.getUUID(), new Object[] {ticks, "§3+" + combatNumber(gain) + " Combat ("
            + String.format(Locale.ROOT, "%,d", (long) Math.floor(total)) + "/0)"});
    }

    /** "520.7", "7,536.9", "162": commas, one decimal, ".0" dropped. */
    static String combatNumber(double value) {
        String s = String.format(Locale.ROOT, "%,.1f", value);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /** {until tick, text} per player: text that takes the Mana and Vitality slots (the Ragnarock's "CASTING IN 3s"). */
    private final Map<UUID, Object[]> manaSlot = new HashMap<>();

    /** The action bar shows [text] where Mana and Vitality go, for [duration] ticks. */
    void manaSlot(ServerPlayer player, String text, int duration) {
        manaSlot.put(player.getUUID(), new Object[] {ticks + duration, text});
    }

    /** Whether anything hurt [player] in the last [within] ticks. */
    boolean hurtWithin(ServerPlayer player, int within) {
        Long hit = lastHit.get(player.getUUID());
        return hit != null && ticks - hit < within;
    }

    /** Healing Aura: teammates within 8 blocks heal a share of their max Health each second. */
    private void aura(ServerPlayer healer, StatSheet s) {
        for (ServerPlayer other : run.level.players()) {
            if (other != healer && !isGhost(other) && other.distanceTo(healer) <= AURA_RANGE) {
                double amount = stats(other).get(Stat.HEALTH) * s.classBonus.auraPercent();
                health.put(other.getUUID(), Math.min(max(other), health(other) + CombatMath.healReceived(stats(other),
                    CombatMath.healGiven(s, amount))));
            }
        }
    }

    /** The action bar goes out every 10 ticks, never in between (menus-ui.md: gaps 10/9/11). */
    private static final int ACTION_BAR_TICKS = 10;
    private static final double HEARTS = 40;
    private static final int VITALITY_TICKS = 10;
    /** Abilities cost full price for this long after an enemy's hit, 10% less otherwise (abilities-items.md §10.1). */
    private static final int FULL_PRICE_TICKS = 100;

    /**
     * The vanilla hearts follow the SkyBlock health, as a share of a fixed 40 (Hypixel's max_health
     * attribute is always 40, never the max HP: stats.md §5).
     */
    private void sync(ServerPlayer player) {
        StatSheet s = stats(player);
        double max = Math.max(1, s.get(Stat.HEALTH));
        double now = Math.max(0, Math.min(health(player), max));
        var attribute = player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        if (attribute != null && attribute.getBaseValue() != HEARTS) {
            attribute.setBaseValue(HEARTS);
        }
        float hearts = (float) Math.max(1, Math.ceil(HEARTS * now / max));
        if (player.getHealth() != hearts) {
            player.setHealth(hearts);
        }
    }

    /**
     * The action bar as Hypixel's (stats.md §3): health (gold while a shield lifts it over max), defense
     * (or the last cast's cost for a second), mana, the Vitality pool, and the room's "x/y Secrets"
     * while in a room with secrets (not over practice's timer). Max values are the stats floored. The
     * stat symbols are the classic ones: Hypixel's are resource-pack glyphs (// OPEN: 69).
     * // OPEN: Overflow Mana (Hypixel's §3600 segment) isn't modelled, so it isn't shown.
     */
    private void actionBar(ServerPlayer player) {
        StatSheet s = stats(player);
        double max = Math.max(1, s.get(Stat.HEALTH));
        double now = Math.max(0, Math.min(health(player), max));
        if (!run.ownsActionBar()) {
            double shield = absorption(player);
            String hp = String.format(Locale.ROOT, "%s%,d/%,d❤", shield > 0 ? "§6" : "§c",
                (long) Math.floor(now + shield), (long) Math.floor(max));
            long maxMana = (long) Math.floor(CombatMath.maxMana(s));
            Object[] cast = lastCast.get(player.getUUID());
            Object[] xp = lastCombatXp.get(player.getUUID());
            boolean castShown = cast != null && ticks - (long) cast[0] < 20;
            boolean xpShown = xp != null && ticks - (long) xp[0] < COMBAT_XP_TICKS;
            // The newer of the two takes Defense's place.
            String middle = castShown && (!xpShown || (long) cast[0] >= (long) xp[0]) ? (String) cast[1]
                : xpShown ? (String) xp[1]
                : String.format(Locale.ROOT, "§a%,d❈", (long) Math.floor(s.get(Stat.DEFENSE)));
            String vitality = String.format(Locale.ROOT, "     §4%,d/%,d♨", (long) Math.floor(vitality(player)),
                (long) Math.floor(maxVitality(s)));
            String secrets = "";
            int room = run.roomAt(player.getX(), player.getZ());
            if (room >= 0) {
                RoomState r = run.rooms.get(room);
                if (r.data().secrets() > 0) {
                    secrets = "          §7" + r.foundSecrets + "/" + r.data().secrets() + " Secrets";
                }
            }
            Object[] slot = manaSlot.get(player.getUUID());
            if (slot != null && ticks < (long) slot[0]) {
                // Hypixel drops Mana and Vitality for the cast text, which keeps the colour before it.
                player.sendOverlayMessage(EngineItems.text(hp + "     " + middle + "     " + slot[1]
                    + (secrets.isEmpty() ? "" : "     " + secrets.strip())));
                return;
            }
            player.sendOverlayMessage(EngineItems.text(String.format(Locale.ROOT,
                "%s     %s     §b%,d/%,d✎%s%s", hp, middle, (long) Math.floor(mana(player)), maxMana, vitality, secrets)));
        }
    }
}
