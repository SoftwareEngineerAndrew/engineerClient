package net.rustclear.mod.engine.boss.maxor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.rustclear.mod.engine.EngineItems;
import net.rustclear.mod.engine.SimLog;
import net.rustclear.mod.engine.blood.CampMob;
import net.rustclear.mod.engine.boss.Attacks;
import net.rustclear.mod.engine.boss.BossChat;
import net.rustclear.mod.engine.boss.BossContext;
import net.rustclear.mod.engine.boss.BossFight;
import net.rustclear.mod.engine.boss.BossPhase;
import net.rustclear.mod.engine.boss.Titles;
import net.rustclear.mod.engine.boss.storm.StormPhase;
import net.rustclear.mod.mixin.ArmorStandAccessor;
import net.rustclear.mod.mixin.LivingEntityAccessor;

/**
 * P1, Maxor (boss-p1-maxor.md). Clock 0 = M0, his first line; the party arrived at M0 - 2.
 * - Three intro lines 42 apart; Maxor (a no-AI wither) and the two top crystals at +4; the
 *   pylons open at +80, the beam's blocks clear at +81, he walks to the beam and the 106 adds
 *   appear at +83, the beacon goes in at +120.
 * - A crystal is picked up by right-clicking it and placed by right-clicking a pylon; the pair
 *   charges the laser 27-29 ticks later. The laser hits on the beacon's 10-tick grid when Maxor
 *   is within 3.4 blocks of the beam: his health flag (data 9) goes 1 -> 1000, a stun line, new
 *   top crystals at +39, the pylons reset at +41.
 * - He takes damage only while stunned. Stun 1 ends at 25% or after 240 ticks ("Maxor is
 *   enraged!", a Wither TNT, faster skulls); stun 2 kills him 5 ticks later. Storm speaks 62
 *   ticks after the kill.
 */
public final class MaxorPhase implements BossPhase {
    /** Where the party lands, 2 ticks before Maxor's first line. */
    public static Vec3 entry() {
        return MaxorData.ENTRY;
    }

    public static int teleportBefore() {
        return MaxorData.TELEPORT_BEFORE;
    }

    private BossFight fight;
    private BossContext ctx;
    private Random random;

    private WitherBoss maxor;
    private ArmorStand nameTag;
    private ArmorStand bossTag;
    private Vec3 pos = MaxorData.MAXOR;
    private Vec3 hover;
    private boolean moving;

    private final EndCrystal[] tops = new EndCrystal[2];
    private final ArmorStand[][] topLabels = new ArmorStand[2][];
    private final EndCrystal[] pylonCrystals = new EndCrystal[2];
    private final ArmorStand[] pylonStands = new ArmorStand[2];
    private final ArmorStand[] pylonLabels = new ArmorStand[2];
    private final boolean[] active = new boolean[2];
    private final Set<UUID> carrying = new HashSet<>();
    /** What a carrier had in hotbar slot 9 before the crystal took it. */
    private final java.util.Map<UUID, net.minecraft.world.item.ItemStack> slotBefore = new java.util.HashMap<>();
    private boolean pylonsOpen;
    private boolean beacon;
    private boolean charged;
    private long chargeScheduled = -1;

    private double maxHealth;
    private double health;
    private int hits;
    private long hitAt = -1;
    private boolean stunned;
    private boolean enraged;
    private boolean dead;
    private long killAt = -1;
    private boolean over;

    private final List<WitherSkull> skulls = new ArrayList<>();
    private final List<Vec3> skullLast = new ArrayList<>();
    private final List<Entity> adds = new ArrayList<>();
    private final List<ArmorStand> floats = new ArrayList<>();
    private final List<Long> floatUntil = new ArrayList<>();

    @Override
    public String name() {
        return "maxor";
    }

    @Override
    public void start(BossFight fight) {
        this.fight = fight;
        this.ctx = fight.ctx;
        this.random = ctx.random();
        maxHealth = ctx.profile().master ? MaxorData.HEALTH_M7 : MaxorData.HEALTH_F7;
        health = maxHealth;
        for (int i = 0; i < MaxorData.INTRO.size(); i++) {
            String line = MaxorData.INTRO.get(i);
            fight.clock.at((long) i * MaxorData.LINE_GAP, () -> say(line, MaxorData.LINE_GAP));
        }
        fight.clock.at(MaxorData.SPAWN, this::spawn);
        fight.clock.at(MaxorData.NAME_STANDS, () -> {
            nameTag = label(pos.add(0, 3.69, 0), " §cMaxor");
            bossTag = label(pos.add(0, 4.0, 0), "§c§lBOSS");
        });
        fight.clock.at(MaxorData.CRYSTAL_LABELS, () -> {
            for (int i = 0; i < 2; i++) {
                labelTop(i);
            }
        });
        fight.clock.at(MaxorData.PYLONS, this::openPylons);
        fight.clock.at(MaxorData.BEAM_CLEAR, () -> {
            ctx.blocks().set(at(MaxorData.BEAM_BLOCK), Blocks.AIR.defaultBlockState());
            ctx.blocks().set(at(MaxorData.BEACON_POS), Blocks.AIR.defaultBlockState());
        });
        fight.clock.at(MaxorData.MOVE, () -> {
            moving = true;
            spawnAdds();
        });
        if (ctx.profile().master) {
            // M7 only: debris thrown inward round the beam from M0+83 (~200 blocks by +200).
            fight.clock.every(MaxorData.MOVE, 1, () -> fight.clock.now() <= MaxorData.DEBRIS_UNTIL, this::debris);
        }
        fight.clock.at(MaxorData.BEACON, () -> {
            beacon = true;
            ctx.blocks().set(at(MaxorData.BEACON_POS), Blocks.BEACON.defaultBlockState());
        });
        if (random.nextDouble() < MaxorData.TAUNT_P) {
            fight.clock.at(MaxorData.TAUNT_A_AT, () -> taunt(MaxorData.TAUNT_A));
        }
        if (random.nextDouble() < MaxorData.TAUNT_P) {
            fight.clock.at(MaxorData.TAUNT_B_AT + random.nextInt(8), () -> taunt(MaxorData.TAUNT_B));
        }
        // Until the beacon goes in, the beam's two blocks are the glass above them.
        ctx.blocks().set(at(MaxorData.BEACON_POS), Blocks.STAINED_GLASS.black().defaultBlockState());
        ctx.blocks().set(at(MaxorData.BEAM_BLOCK), Blocks.STAINED_GLASS.black().defaultBlockState());
        ctx.blocks().set(at(MaxorData.GLASS_POS), Blocks.STAINED_GLASS.black().defaultBlockState());
    }

    private static BlockPos at(int[] p) {
        return new BlockPos(p[0], p[1], p[2]);
    }

    // ---------------------------------------------------------------- entities

    private void spawn() {
        ServerLevel level = ctx.level();
        maxor = EntityTypes.WITHER.create(level, EntitySpawnReason.TRIGGERED);
        maxor.setNoAi(true);
        maxor.setNoGravity(true);
        maxor.setPersistenceRequired();
        // The vanilla summon animation's ticks, as Hypixel sends them (and never counts down).
        maxor.setInvulnerableTicks(200);
        var max = maxor.getAttribute(Attributes.MAX_HEALTH);
        if (max != null) {
            max.setBaseValue(ctx.profile().bossMaxHealth);
        }
        maxor.snapTo(pos.x, pos.y, pos.z, 0f, 0f);
        level.addFreshEntity(maxor);
        ctx.entities().add(maxor);
        flag(1.0f);
        ctx.registerBoss(maxor, this::damaged);
        for (int i = 0; i < 2; i++) {
            spawnTop(i);
        }
        SimLog.event("maxor_spawn");
    }

    private void flag(float value) {
        if (maxor != null && !maxor.isRemoved()) {
            maxor.getEntityData().set(LivingEntityAccessor.rustclear$healthId(), value);
        }
    }

    private EndCrystal crystal(Vec3 at) {
        EndCrystal c = new EndCrystal(ctx.level(), at.x, at.y, at.z);
        c.setShowBottom(false);
        c.setPermanentlyInvulnerable(true);
        ctx.level().addFreshEntity(c);
        ctx.entities().add(c);
        return c;
    }

    private void spawnTop(int i) {
        if (tops[i] == null || tops[i].isRemoved()) {
            tops[i] = crystal(MaxorData.TOPS[i]);
        }
    }

    private void labelTop(int i) {
        if (topLabels[i] == null && tops[i] != null) {
            Vec3 c = MaxorData.TOPS[i];
            topLabels[i] = new ArmorStand[] {label(c.add(0, 0.125, 0), "§bEnergy Crystal"), label(c.add(0, -0.25, 0), "§e§lCLICK HERE")};
        }
    }

    private void unlabelTop(int i) {
        if (topLabels[i] != null) {
            for (ArmorStand s : topLabels[i]) {
                ctx.entities().discard(s);
            }
            topLabels[i] = null;
        }
    }

    /** A floating text stand (a marker: nothing to click). */
    private ArmorStand label(Vec3 at, String legacy) {
        ArmorStand s = new ArmorStand(EntityTypes.ARMOR_STAND, ctx.level());
        ((ArmorStandAccessor) s).rustclear$setMarker(true);
        s.setInvisible(true);
        s.setNoGravity(true);
        s.setPermanentlyInvulnerable(true);
        s.setCustomNameVisible(true);
        s.setCustomName(EngineItems.styled(legacy));
        s.snapTo(at.x, at.y, at.z, 0f, 0f);
        ctx.level().addFreshEntity(s);
        ctx.entities().add(s);
        return s;
    }

    private void openPylons() {
        pylonsOpen = true;
        for (int i = 0; i < 2; i++) {
            Vec3 p = MaxorData.PYLON_STANDS[i];
            // A full-size invisible stand players can right-click, and its second line below.
            ArmorStand s = new ArmorStand(EntityTypes.ARMOR_STAND, ctx.level());
            s.setInvisible(true);
            s.setNoGravity(true);
            s.setPermanentlyInvulnerable(true);
            s.setCustomNameVisible(true);
            s.setCustomName(EngineItems.styled("§cEnergy Crystal Missing"));
            s.snapTo(p.x, p.y, p.z, 0f, 0f);
            ctx.level().addFreshEntity(s);
            ctx.entities().add(s);
            pylonStands[i] = s;
            pylonLabels[i] = label(p.add(0, -0.375, 0), "§e§lCLICK HERE");
        }
    }

    private void spawnAdds() {
        List<double[]> spots = addSpots();
        double total = 0;
        for (double[] s : spots) {
            total += s[3];
        }
        for (int i = 0; i < MaxorData.ADDS; i++) {
            double[] spot;
            if (i < MaxorData.ADD_PERCHES.length) {
                double[] p = MaxorData.ADD_PERCHES[i];
                spot = new double[] {p[0], p[1], p[2], 1};
            } else {
                double r = random.nextDouble() * total;
                spot = spots.isEmpty() ? new double[] {73.5, 221, 40.5, 1} : spots.get(spots.size() - 1);
                for (double[] s : spots) {
                    if ((r -= s[3]) < 0) {
                        spot = s;
                        break;
                    }
                }
            }
            boolean miner = random.nextBoolean();
            boolean m7 = ctx.profile().master;
            // OPEN: the Miner / Guard split (both names seen; shares not counted).
            CampMob mob = new CampMob(miner ? CampMob.Type.WITHER_MINER : CampMob.Type.WITHER_GUARD,
                miner ? "§cWither Miner" : "§cWither Guard", miner ? "Wither Miner" : "Wither Guard",
                m7 ? 300_000_000 : miner ? 10_000_000 : 3_500_000, null, null,
                miner ? "minecraft:stone_pickaxe" : "minecraft:stone_sword", null, false, 1.0, 0);
            LivingEntity add = ctx.spawnBossMob(mob, new Vec3(spot[0], spot[1], spot[2]), random.nextFloat() * 360f, null);
            if (add != null) {
                adds.add(add);
            }
        }
        SimLog.event("maxor_adds", "count", adds.size());
    }

    private static List<double[]> cachedSpots;

    private static synchronized List<double[]> addSpots() {
        if (cachedSpots != null) {
            return cachedSpots;
        }
        List<double[]> out = new ArrayList<>();
        try (InputStream in = MaxorPhase.class.getResourceAsStream("/assets/rustclear/engine/maxor_adds.json")) {
            if (in != null) {
                JsonArray arr = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("spots");
                for (JsonElement e : arr) {
                    JsonArray a = e.getAsJsonArray();
                    out.add(new double[] {a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble(), a.get(3).getAsDouble()});
                }
            }
        } catch (Exception ignored) {
            // No spots: the adds stand on the floor in front of the entry.
        }
        cachedSpots = out;
        return out;
    }

    // ---------------------------------------------------------------- chat

    private void say(String line, int gap) {
        ctx.chat().say(BossChat.boss("Maxor", line), gap, () -> floatLine(line));
    }

    /** The line floats above him as "§4§l<line>" for about 20 ticks. */
    private void floatLine(String line) {
        if (maxor == null || maxor.isRemoved()) {
            return;
        }
        floats.add(label(pos.add(0, 4.5, 0), "§4§l" + line));
        floatUntil.add(fight.clock.now() + MaxorData.FLOAT_TICKS);
    }

    private void taunt(List<String> pool) {
        if (!dead && !stunned && maxor != null) {
            say(pool.get(random.nextInt(pool.size())), MaxorData.LINE_GAP);
        }
    }

    private void announce(String legacy) {
        ctx.broadcast(legacy);
        for (ServerPlayer p : ctx.players()) {
            Titles.send(p, "", legacy, Titles.PROGRESS);
        }
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean use(ServerPlayer player, Entity entity) {
        for (int i = 0; i < 2; i++) {
            if (entity == tops[i] && tops[i] != null) {
                pick(player, i);
                return true;
            }
            if (entity == pylonStands[i] || entity == pylonCrystals[i]) {
                place(player, i);
                return true;
            }
        }
        return false;
    }

    private void pick(ServerPlayer player, int i) {
        if (carrying.contains(player.getUUID()) || ctx.isGhost(player)) {
            return;
        }
        carrying.add(player.getUUID());
        EndCrystal c = tops[i];
        tops[i] = null;
        String name = ctx.nameOf(player);
        int slot = net.rustclear.mod.engine.Kit.MAP_SLOT;
        slotBefore.put(player.getUUID(), player.getInventory().getItem(slot).copy());
        // ead6cf n112504: the held-slot packet (slot 9) and the line together, the crystal in slot 9 after.
        fight.clock.after(1, () -> {
            player.getInventory().setSelectedSlot(slot);
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(slot));
            ctx.broadcast(name + "§a picked up an §bEnergy Crystal§a!");
        });
        fight.clock.after(2, () -> {
            if (carrying.contains(player.getUUID())) {
                player.getInventory().setItem(slot, crystalItem());
            }
        });
        fight.clock.after(MaxorData.PICK_REMOVE, () -> {
            ctx.entities().discard(c);
            unlabelTop(i);
        });
        SimLog.event("crystal_pick", "top", i);
    }

    /** Hypixel's crystal item: a nether star, red name, "SPECIAL DUNGEON ITEM" (ead6cf). */
    private static net.minecraft.world.item.ItemStack crystalItem() {
        net.minecraft.world.item.ItemStack stack = EngineItems.named(net.minecraft.world.item.Items.NETHER_STAR,
            "§cEnergy Crystal", "§c§lSPECIAL DUNGEON ITEM");
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putString("id", "MAXOR_ENERGY_CRYSTAL");
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
        return stack;
    }

    private void giveBack(ServerPlayer player) {
        net.minecraft.world.item.ItemStack before = slotBefore.remove(player.getUUID());
        player.getInventory().setItem(net.rustclear.mod.engine.Kit.MAP_SLOT,
            before == null ? net.minecraft.world.item.ItemStack.EMPTY : before);
    }

    private void place(ServerPlayer player, int i) {
        if (!pylonsOpen || active[i] || dead) {
            return;
        }
        if (!carrying.remove(player.getUUID())) {
            player.sendSystemMessage(EngineItems.styled("§cYou do not current have an Energy Crystal! Find one and bring it here!"));
            return;
        }
        giveBack(player);
        active[i] = true;
        pylonCrystals[i] = crystal(MaxorData.PYLON_CRYSTALS[i]);
        int n = (active[0] ? 1 : 0) + (active[1] ? 1 : 0);
        announce((n == 2 ? "§a" : "§c") + n + "§a/2 Energy Crystals are now active!");
        ArmorStand stand = pylonStands[i];
        fight.clock.after(2, () -> stand.setCustomName(EngineItems.styled("§aCrystal Active")));
        SimLog.event("crystal_place", "pylon", i, "active", n);
        if (n == 2 && chargeScheduled < 0) {
            int delay = MaxorData.CHARGE_MIN + random.nextInt(MaxorData.CHARGE_SPREAD);
            chargeScheduled = fight.clock.now() + delay;
            fight.clock.after(delay, () -> {
                chargeScheduled = -1;
                if (dead || !active[0] || !active[1]) {
                    return;
                }
                charged = true;
                announce("§aThe Energy Laser is charging up!");
                ctx.blocks().set(at(MaxorData.GLASS_POS), Blocks.STAINED_GLASS.yellow().defaultBlockState());
                SimLog.event("laser_charge");
            });
        }
    }

    // ---------------------------------------------------------------- ticking

    @Override
    public void tick() {
        long now = fight.clock.now();
        if (maxor == null || maxor.isRemoved()) {
            return;
        }
        move(now);
        if (!dead && beacon && charged && !stunned && now >= MaxorData.BEACON && (now - MaxorData.BEACON) % MaxorData.HIT_GRID == 0
            && nearBeam()) {
            hit(now);
        }
        if (stunned && hits == 1 && now - hitAt >= MaxorData.STUN_MAX) {
            enrage(false);
        }
        if (!dead && !stunned && now >= MaxorData.FIRST_SKULL
            && random.nextDouble() < (enraged ? MaxorData.SKULLS_ENRAGED : MaxorData.SKULLS_CALM)) {
            shoot();
        }
        skulls(now);
        for (int i = floats.size() - 1; i >= 0; i--) {
            if (now >= floatUntil.get(i)) {
                ctx.entities().discard(floats.remove(i));
                floatUntil.remove(i);
            } else {
                floats.get(i).snapTo(pos.x, pos.y + 4.5, pos.z, 0f, 0f);
            }
        }
    }

    private boolean nearBeam() {
        double dx = pos.x - MaxorData.BEAM_X;
        double dz = pos.z - MaxorData.BEAM_Z;
        return dx * dx + dz * dz <= MaxorData.HIT_RANGE * MaxorData.HIT_RANGE && pos.y >= MaxorData.HIT_MIN_Y;
    }

    /** He walks to the beam and hovers there, within about 1.3 blocks (main-post median after a hit). */
    private void move(long now) {
        if (moving && !stunned && !dead) {
            if (hover == null || now % 40 == 0) {
                double a = random.nextDouble() * Math.PI * 2;
                double r = random.nextDouble() * 1.3;
                hover = new Vec3(MaxorData.BEAM_X + Math.cos(a) * r, 225.8, MaxorData.BEAM_Z + Math.sin(a) * r);
            }
            Vec3 d = hover.subtract(pos);
            double len = d.length();
            double speed = enraged ? MaxorData.WALK * MaxorData.ENRAGE_SPEED / 0.6 : MaxorData.WALK;
            pos = len <= speed ? hover : pos.add(d.scale(speed / len));
        }
        ServerPlayer near = nearest(pos, 64);
        float yaw = near == null ? maxor.getYRot()
            : (float) (Math.toDegrees(Math.atan2(near.getZ() - pos.z, near.getX() - pos.x)) - 90.0);
        maxor.snapTo(pos.x, pos.y, pos.z, yaw, 0f);
        maxor.setYHeadRot(yaw);
        if (nameTag != null) {
            nameTag.snapTo(pos.x, pos.y + 3.69, pos.z, 0f, 0f);
            bossTag.snapTo(pos.x, pos.y + 4.0, pos.z, 0f, 0f);
        }
    }

    private void hit(long now) {
        hits++;
        hitAt = now;
        stunned = true;
        charged = false;
        flag(1000f);
        say(MaxorData.STUN_LINES.get(random.nextInt(MaxorData.STUN_LINES.size())), MaxorData.LINE_GAP);
        ctx.blocks().set(at(MaxorData.GLASS_POS), Blocks.STAINED_GLASS.red().defaultBlockState());
        ctx.level().playSound(null, pos.x, pos.y, pos.z, SoundEvents.WITHER_HURT, SoundSource.HOSTILE, 4f, 1f);
        SimLog.event("maxor_hit", "hit", hits);
        int which = hits;
        fight.clock.after(MaxorData.GLASS_BLACK, () -> {
            if (!charged) {
                ctx.blocks().set(at(MaxorData.GLASS_POS), Blocks.STAINED_GLASS.black().defaultBlockState());
            }
        });
        fight.clock.after(MaxorData.TOPS_BACK, () -> {
            for (int i = 0; i < 2; i++) {
                spawnTop(i);
                labelTop(i);
            }
        });
        fight.clock.after(MaxorData.PYLONS_RESET, this::resetPylons);
        if (which == 1) {
            health = Math.max(health - maxHealth * MaxorData.HIT_SHARE, maxHealth * MaxorData.STUN_FLOOR);
            ctx.bar().progress((float) (health / maxHealth));
        } else if (which == 2) {
            fight.clock.after(MaxorData.KILL_AFTER_HIT2, this::kill);
        }
    }

    private void resetPylons() {
        for (int i = 0; i < 2; i++) {
            active[i] = false;
            if (pylonCrystals[i] != null) {
                ctx.entities().discard(pylonCrystals[i]);
                pylonCrystals[i] = null;
            }
            if (pylonStands[i] != null && !dead) {
                pylonStands[i].setCustomName(EngineItems.styled("§cEnergy Crystal Missing"));
            }
        }
    }

    /** A player's hit (any weapon or ability): it lands only while he is stunned. */
    private void damaged(long amount) {
        if (!stunned || dead) {
            return;
        }
        if (hits == 1) {
            health -= amount;
            if (health <= maxHealth * MaxorData.STUN_FLOOR) {
                health = maxHealth * MaxorData.STUN_FLOOR;
                ctx.bar().progress((float) MaxorData.STUN_FLOOR);
                enrage(true);
                return;
            }
        } else {
            health = Math.max(0, health - amount);
        }
        ctx.bar().progress((float) (health / maxHealth));
    }

    /** Stun 1 is over: at 25% ([floor]) or after 240 ticks (the bar stays where it was). */
    private void enrage(boolean floor) {
        stunned = false;
        enraged = true;
        announce("§c⚠ Maxor is enraged! ⚠");
        fight.clock.after(1, () -> flag(1.0f));
        var speed = maxor.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(MaxorData.ENRAGE_SPEED);
        }
        fight.clock.after(1, this::tnt);
        if (random.nextDouble() < MaxorData.TAUNT_P) {
            fight.clock.after(MaxorData.TAUNT_A_AFTER_ENRAGE, () -> taunt(MaxorData.TAUNT_A));
        }
        SimLog.event("maxor_enrage", "floor", floor, "stun_ticks", fight.clock.now() - hitAt);
    }

    /** The Wither TNT at his feet: it goes off after its fuse on players near it. */
    private void tnt() {
        ServerLevel level = ctx.level();
        PrimedTnt tnt = new PrimedTnt(level, pos.x, pos.y, pos.z, null);
        tnt.setFuse(MaxorData.TNT_FUSE + 10);
        tnt.setDeltaMovement(0, 0.2, 0);
        level.addFreshEntity(tnt);
        ctx.entities().add(tnt);
        fight.clock.after(MaxorData.TNT_FUSE, () -> {
            Vec3 at = tnt.position();
            ctx.entities().discard(tnt);
            level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y, at.z, 1, 0, 0, 0, 0);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 4f, 1f);
            double dmg = MaxorData.TNT_DAMAGE * ctx.profile().scale("Maxor's Wither TNT");
            for (ServerPlayer p : ctx.players()) {
                if (p.position().distanceTo(at) <= MaxorData.TNT_RADIUS) {
                    // "Maxor's Wither TNT hit you for 7200.0 damage." (as recorded, with the ".0").
                    ctx.campHit(p, "§cMaxor's§7 Wither TNT hit you for §c" + dmg + "§7 damage.", dmg, false, "Maxor");
                }
            }
        });
    }

    private void kill() {
        dead = true;
        stunned = false;
        killAt = fight.clock.now();
        health = 0;
        ctx.bar().progress(0f);
        ctx.blocks().set(at(MaxorData.BEACON_POS), Blocks.BEDROCK.defaultBlockState());
        resetPylons();
        for (ArmorStand s : pylonStands) {
            if (s != null) {
                ctx.entities().discard(s);
            }
        }
        for (ArmorStand s : pylonLabels) {
            if (s != null) {
                ctx.entities().discard(s);
            }
        }
        SimLog.event("maxor_kill");
        fight.clock.after(MaxorData.KILL_EXPLODE, () -> {
            ctx.level().sendParticles(ParticleTypes.EXPLOSION_EMITTER, pos.x, pos.y, pos.z, 1, 0, 0, 0, 0);
            ctx.level().playSound(null, pos.x, pos.y, pos.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 4f, 1f);
        });
        fight.clock.after(MaxorData.CRUMBLE, () -> {
            flag(0f);
            // OPEN: the crumbling pylon platforms (about 38 falling blocks) aren't modelled.
            for (Entity add : adds) {
                if (!add.isRemoved()) {
                    ctx.killMob(add);
                }
            }
            adds.clear();
        });
        fight.clock.after(MaxorData.STORM_LINE, () -> over = true);
    }

    private void shoot() {
        ServerPlayer target = nearest(pos, 40);
        if (target == null) {
            return;
        }
        // From one of his three heads: the middle about +2.8, the sides ±1.3 at about +2.0.
        int head = random.nextInt(3);
        Vec3 from = pos.add(head == 0 ? 0 : head == 1 ? -1.3 : 1.3, head == 0 ? 2.8 : 2.0, 0);
        Vec3 dir = target.getBoundingBox().getCenter().subtract(from).normalize();
        WitherSkull skull = new WitherSkull(ctx.level(), maxor, dir.scale(MaxorData.SKULL_POWER));
        skull.snapTo(from.x, from.y, from.z, 0f, 0f);
        ctx.level().addFreshEntity(skull);
        ctx.level().playSound(null, from.x, from.y, from.z, SoundEvents.WITHER_SHOOT, SoundSource.HOSTILE, 1f, 1f);
        skulls.add(skull);
        skullLast.add(from);
    }

    /** A skull that hit something blew up where it was: Hypixel's line on every player close by. */
    private void skulls(long now) {
        for (int i = skulls.size() - 1; i >= 0; i--) {
            WitherSkull s = skulls.get(i);
            if (!s.isRemoved()) {
                skullLast.set(i, s.position());
                if (s.tickCount > 200) {
                    s.discard();
                }
                continue;
            }
            Vec3 at = skullLast.get(i);
            skulls.remove(i);
            skullLast.remove(i);
            for (ServerPlayer p : ctx.players()) {
                if (p.position().add(0, 1, 0).distanceTo(at) <= MaxorData.SKULL_RADIUS) {
                    ctx.cryptSkull(p);
                }
            }
        }
        for (ServerPlayer p : ctx.players()) {
            // A vanilla skull's direct hit also withers; Hypixel's don't.
            p.removeEffect(MobEffects.WITHER);
        }
    }

    private ServerPlayer nearest(Vec3 at, double within) {
        ServerPlayer best = null;
        double bestD = within * within;
        for (ServerPlayer p : ctx.players()) {
            if (ctx.isGhost(p) || p.isSpectator()) {
                continue;
            }
            double d = p.position().distanceToSqr(at);
            if (d <= bestD) {
                best = p;
                bestD = d;
            }
        }
        return best;
    }

    @Override
    public BossPhase next() {
        return over ? new StormPhase() : null;
    }

    @Override
    public void dispose() {
        if (maxor != null) {
            ctx.unregisterBoss(maxor);
        }
        for (ServerPlayer p : ctx.players()) {
            if (carrying.remove(p.getUUID())) {
                giveBack(p);
            }
        }
        for (Iterator<Entity> it = adds.iterator(); it.hasNext(); ) {
            Entity add = it.next();
            if (!add.isRemoved()) {
                add.discard();
            }
            it.remove();
        }
        for (WitherSkull s : skulls) {
            s.discard();
        }
        skulls.clear();
    }

    public boolean dead() {
        return dead;
    }

    /** For the LAN check: top crystal [i] (null once taken), pylon [i]'s stand, and its state. */
    public Entity top(int i) {
        return tops[i];
    }

    public Entity pylonStand(int i) {
        return pylonStands[i];
    }

    public boolean pylonsOpen() {
        return pylonsOpen;
    }

    public boolean active(int i) {
        return active[i];
    }

    public boolean carrying(ServerPlayer p) {
        return carrying.contains(p.getUUID());
    }

    long killedAt() {
        return killAt;
    }

    /** For Attacks' name in the profile (keeps the import used when nothing else does). */
    static String tntKey() {
        return Attacks.MAXOR_TNT;
    }

    /**
     * M7's debris (boss-p1-maxor.md, alpha): falling obsidian, purple glass and blue terracotta
     * launched from y 220.3 on a ring round the beam, moving inward at 0.6 a tick and 0.22 up.
     * Cosmetic (OPEN: whether they hurt); each goes after 30 ticks so none lands as a block.
     */
    private void debris() {
        int n = (int) MaxorData.DEBRIS_PER_TICK + (random.nextDouble() < MaxorData.DEBRIS_PER_TICK % 1 ? 1 : 0);
        for (int i = 0; i < n; i++) {
            debrisBlock();
        }
    }

    private void debrisBlock() {
        double a = random.nextDouble() * Math.PI * 2;
        double cx = MaxorData.BEACON_POS[0] + 0.5;
        double cz = MaxorData.BEACON_POS[2] + 0.5;
        double r = MaxorData.DEBRIS_RADIUS;
        net.minecraft.world.level.block.state.BlockState[] kinds = {Blocks.OBSIDIAN.defaultBlockState(),
            Blocks.STAINED_GLASS.purple().defaultBlockState(), Blocks.DYED_TERRACOTTA.blue().defaultBlockState()};
        net.minecraft.world.entity.item.FallingBlockEntity f = net.minecraft.world.entity.item.FallingBlockEntity.fall(
            ctx.level(), net.minecraft.core.BlockPos.containing(cx + Math.cos(a) * r, 220.3, cz + Math.sin(a) * r),
            kinds[random.nextInt(kinds.length)]);
        f.disableDrop();
        f.setDeltaMovement(-Math.cos(a) * 0.6, 0.22, -Math.sin(a) * 0.6);
        ctx.entities().add(f);
        fight.clock.after(30, () -> ctx.entities().discard(f));
    }

}
