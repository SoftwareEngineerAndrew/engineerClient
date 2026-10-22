package net.rustclear.mod.engine.boss.witherking;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.rustclear.mod.engine.EngineItems;
import net.rustclear.mod.engine.SimLog;
import net.rustclear.mod.engine.boss.BossChat;
import net.rustclear.mod.engine.boss.BossContext;
import net.rustclear.mod.engine.boss.BossFight;
import net.rustclear.mod.engine.boss.BossPhase;
import net.rustclear.mod.mixin.ArmorStandAccessor;
import net.rustclear.mod.mixin.LivingEntityAccessor;
import net.rustclear.mod.mixin.MannequinAccessor;

/**
 * P5, the Wither King (M7 only; boss-p5-wither-king.md, alpha-measured). t = 0 is Necron's removal:
 * the bar becomes "§kWither King" at 0 and the five relics stand by their statues. Right-click a
 * relic to pick it up ("<name> picked the Corrupted <Colour> Relic!"), use it on its cauldron to
 * place it; 60 ticks after the last placement "You... again?" (A) and the bar fills to 1.0: it
 * counts the dragons left. Dragon 1 is announced at A and spawns 100 later (100 ticks of particles
 * over its statue), dragon 2 at A + 60; later ones follow the last death. A kill near the dragon's
 * own statue takes 0.2 off the bar and the statue falls 202 ticks later; elsewhere the colour comes
 * back. The fifth counted kill wins the run. ~120 Wither Husks stand on the floor.
 */
public final class WitherKingPhase implements BossPhase {
    private BossFight fight;
    private BossContext ctx;
    private Random random;

    private final Map<Entity, WitherKingData.Colour> relicStands = new HashMap<>();
    private final Map<UUID, WitherKingData.Colour> carrying = new HashMap<>();
    private final Map<UUID, ItemStack> slotBefore = new HashMap<>();
    private int placed;
    private long againAt = -1;
    private long a = -1;

    /** Colours still to come, the next first. */
    private final ArrayDeque<WitherKingData.Colour> queue = new ArrayDeque<>();
    private final List<Dragon> dragons = new ArrayList<>();
    private int counted;
    private float bar;
    private boolean won;
    private long nextLine = -1;
    private int lineIndex;

    private WitherBoss king;
    private final List<LivingEntity> husks = new ArrayList<>();
    private final Map<Entity, double[]> huskHp = new HashMap<>();

    private final class Dragon {
        final WitherKingData.Colour colour;
        EnderDragon entity;
        ArmorStand tag;
        double hp = WitherKingData.DRAGON_HP;
        long spawnedAt = -1;
        /** Where it is wandering to (after its route); its heading in degrees (vanilla dragon yaw); Purple's next teleport. */
        Vec3 target;
        float yaw;
        boolean steering;
        long teleportAt = -1;
        long diedAt = -1;

        Dragon(WitherKingData.Colour colour) {
            this.colour = colour;
        }
    }

    /** Where the party stands as this phase starts (it puts them there itself), for boss practice's respawns. */
    public static Vec3 practiceEntry() {
        return WitherKingData.FLOOR_SPOT;
    }

    @Override
    public String name() {
        return "wither_king";
    }

    private long now() {
        return fight.clock.now();
    }

    @Override
    public void start(BossFight fight) {
        this.fight = fight;
        this.ctx = fight.ctx;
        this.random = ctx.random();
        ctx.chat().reset();
        ctx.bar().boss("§kWither King", 0f);
        for (ServerPlayer p : ctx.players()) {
            Vec3 f = WitherKingData.FLOOR_SPOT;
            p.teleportTo(f.x, f.y, f.z);
            p.resetFallDistance();
        }
        for (WitherKingData.Colour c : WitherKingData.COLOURS) {
            relicStands.put(stand(c.relic(), relicItem(c), null), c);
        }
        fight.clock.after(5, () -> relicStands.forEach((e, c) -> {
            if (e.isRemoved()) {
                net.rustclear.mod.RustclearMod.LOGGER.warn("P5: the {} relic stand is gone ({})", c.name(), e.getRemovalReason());
            }
        }));
        spawnKing();
        for (int i = 0; i < WitherKingData.HUSKS; i++) {
            fight.clock.at((long) i * WitherKingData.HUSK_SPAN / WitherKingData.HUSKS, this::husk);
        }
        List<WitherKingData.Colour> order = new ArrayList<>(WitherKingData.COLOURS);
        java.util.Collections.shuffle(order, random);
        queue.addAll(order);
        SimLog.event("wk_start");
    }

    private static ItemStack relicItem(WitherKingData.Colour c) {
        return EngineItems.named(net.minecraft.world.item.Items.PLAYER_HEAD, c.code() + "Corrupted " + c.name() + " Relic");
    }

    private ArmorStand stand(Vec3 at, ItemStack head, String name) {
        ArmorStand s = new ArmorStand(EntityTypes.ARMOR_STAND, ctx.level());
        s.setInvisible(true);
        s.setNoGravity(true);
        s.setPermanentlyInvulnerable(true);
        if (head != null) {
            s.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, head);
        }
        if (name != null) {
            ((ArmorStandAccessor) s).rustclear$setMarker(true);
            s.setCustomName(EngineItems.styled(name));
            s.setCustomNameVisible(true);
        }
        s.snapTo(at.x, at.y, at.z, 0f, 0f);
        ctx.level().addFreshEntity(s);
        ctx.entities().add(s);
        return s;
    }

    private void say(String line) {
        ctx.chat().say(BossChat.boss("Wither King", line), 1);
    }

    // ---------------------------------------------------------------- relics

    @Override
    public boolean use(ServerPlayer player, Entity entity) {
        WitherKingData.Colour c = relicStands.get(entity);
        if (c != null && !ctx.isGhost(player) && !carrying.containsKey(player.getUUID())) {
            relicStands.remove(entity);
            ctx.entities().discard(entity);
            carrying.put(player.getUUID(), c);
            int slot = net.rustclear.mod.engine.Kit.MAP_SLOT;
            slotBefore.put(player.getUUID(), player.getInventory().getItem(slot).copy());
            // 429ba6 n111957-59: the line and the held-slot packet (slot 9) a tick after the click,
            // the relic in inventory slot 44 a tick after that.
            String name = ctx.nameOf(player);
            fight.clock.after(1, () -> {
                player.getInventory().setSelectedSlot(slot);
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(slot));
                // 429ba6: the relic's name is red whatever its colour.
                ctx.broadcast(name + "§a picked the §cCorrupted " + c.name() + " Relic§a!");
            });
            fight.clock.after(2, () -> {
                if (carrying.get(player.getUUID()) == c) {
                    player.getInventory().setItem(slot, relicItem(c));
                }
            });
            SimLog.event("wk_relic_pick", "colour", c.name());
            return true;
        }
        return ctx.entities().contains(entity);
    }

    @Override
    public boolean useBlock(ServerPlayer player, BlockPos pos) {
        WitherKingData.Colour c = carrying.get(player.getUUID());
        if (c == null) {
            return false;
        }
        WitherKingData.Colour at = null;
        for (WitherKingData.Colour k : WitherKingData.COLOURS) {
            if (k.cauldron().distManhattan(pos) <= 1) {
                at = k;
            }
        }
        if (at != c) {
            player.sendSystemMessage(EngineItems.styled(WitherKingData.WRONG_PLACE));
            return true;
        }
        carrying.remove(player.getUUID());
        player.getInventory().setItem(net.rustclear.mod.engine.Kit.MAP_SLOT,
            slotBefore.getOrDefault(player.getUUID(), ItemStack.EMPTY));
        stand(c.placed(), relicItem(c), null);
        placed++;
        SimLog.event("wk_relic_place", "colour", c.name());
        if (placed == WitherKingData.COLOURS.size()) {
            againAt = now() + WitherKingData.AGAIN_AFTER_PLACE;
        }
        return true;
    }

    // ---------------------------------------------------------------- the King, husks

    private void spawnKing() {
        king = EntityTypes.WITHER.create(ctx.level(), EntitySpawnReason.TRIGGERED);
        king.setNoAi(true);
        king.setNoGravity(true);
        king.setPersistenceRequired();
        Vec3 t = WitherKingData.KING_TOP;
        king.snapTo(t.x, t.y, t.z, 0f, 0f);
        ctx.level().addFreshEntity(king);
        ctx.entities().add(king);
        king.getEntityData().set(LivingEntityAccessor.rustclear$healthId(), 300f);
    }

    /** A Wither Husk on the floor: a player NPC with 500M HP and its tag (OPEN: their attacks and AI). */
    private void husk() {
        ServerLevel level = ctx.level();
        double x = 20 + random.nextDouble() * 70;
        double z = 40 + random.nextDouble() * 70;
        int y = 12;
        while (y > 0 && level.getBlockState(BlockPos.containing(x, y - 1, z)).isAir()) {
            y--;
        }
        Mannequin npc = new Mannequin(EntityTypes.MANNEQUIN, level);
        ((MannequinAccessor) npc).rustclear$setImmovable(true);
        float yaw = random.nextFloat() * 360f;
        npc.snapTo(x, y, z, yaw, 0f);
        npc.setYHeadRot(yaw);
        npc.setYBodyRot(yaw);
        level.addFreshEntity(npc);
        ctx.entities().add(npc);
        ArmorStand tag = stand(new Vec3(x, y + 2.0, z), null, " §cWither Husk §a500M§c❤");
        huskHp.put(npc, new double[] {WitherKingData.HUSK_HP});
        husks.add(npc);
        ctx.registerBoss(npc, amount -> {
            double[] hp = huskHp.get(npc);
            if (hp == null) {
                return;
            }
            hp[0] -= amount;
            if (hp[0] <= 0) {
                huskHp.remove(npc);
                ctx.unregisterBoss(npc);
                ctx.level().broadcastEntityEvent(npc, (byte) 3);
                fight.clock.after(2, () -> {
                    ctx.entities().discard(npc);
                    ctx.entities().discard(tag);
                });
            } else {
                tag.setCustomName(EngineItems.styled(" §cWither Husk §a" + short1(hp[0]) + "§c❤"));
            }
        });
    }

    // ---------------------------------------------------------------- dragons

    /** A dragon announced now: 100 ticks of particles over its statue, then it spawns. */
    private void announce() {
        WitherKingData.Colour c = queue.poll();
        if (c == null) {
            return;
        }
        Dragon d = new Dragon(c);
        dragons.add(d);
        SimLog.event("wk_announce", "colour", c.name());
        for (int t = 0; t < WitherKingData.WARN; t += 10) {
            fight.clock.after(t, () -> warn(c));
        }
        fight.clock.after(WitherKingData.WARN, () -> spawnDragon(d));
    }

    private void warn(WitherKingData.Colour c) {
        ServerLevel l = ctx.level();
        double x = c.spawn().x;
        double z = c.spawn().z;
        double y = WitherKingData.WARN_Y;
        l.sendParticles(ParticleTypes.ENCHANT, true, true, x, y, z, 40, 2, 3, 2, 0);
        l.sendParticles(ParticleTypes.LARGE_SMOKE, true, true, x, y, z, 40, 2, 3, 2, 0);
        l.sendParticles(ParticleTypes.WITCH, true, true, x, y, z, 40, 2, 3, 2, 0);
        l.sendParticles(ParticleTypes.FLAME, true, true, x, y, z, 20, 2, 3, 2, 0);
    }

    private void spawnDragon(Dragon d) {
        if (won) {
            return;
        }
        EnderDragon e = EntityTypes.ENDER_DRAGON.create(ctx.level(), EntitySpawnReason.TRIGGERED);
        if (e == null) {
            return;
        }
        // Not NoAI (EngineDragonMixin keeps vanilla's AI off): a NoAI dragon never turns or flaps on clients.
        e.setPersistenceRequired();
        Vec3 s = d.colour.spawn();
        e.snapTo(s.x, s.y, s.z, 0f, 0f);
        ctx.level().addFreshEntity(e);
        ctx.entities().add(e);
        e.getEntityData().set(LivingEntityAccessor.rustclear$healthId(), (float) WitherKingData.DRAGON_HP);
        d.entity = e;
        d.spawnedAt = now();
        double[][] route = WitherKingData.ROUTES.get(d.colour.name());
        d.yaw = route != null && route.length > 1 && (route[1][1] != route[0][1] || route[1][3] != route[0][3])
            ? headingOf(route[1][1] - route[0][1], route[1][3] - route[0][3]) : 0f;
        e.snapTo(s.x, s.y, s.z, d.yaw, 0f);
        d.tag = stand(s.add(0, 4, 0), null, tagName(d.hp));
        ctx.registerBoss(e, amount -> hit(d, amount));
        SimLog.event("wk_dragon", "colour", d.colour.name());
    }

    private static String tagName(double hp) {
        return " §cWithered Dragon §a" + short1(hp) + "§f/§a1B§c❤";
    }

    /** "1B", "44.8M", "500M". */
    static String short1(double v) {
        String[] units = {"", "k", "M", "B"};
        int u = 0;
        while (v >= 1000 && u < 3) {
            v /= 1000;
            u++;
        }
        String s = v >= 100 || Math.abs(v - Math.rint(v)) < 0.05 ? String.valueOf(Math.round(v))
            : String.format(java.util.Locale.ROOT, "%.1f", v);
        return s + units[u];
    }

    private void hit(Dragon d, long amount) {
        if (d.diedAt >= 0 || d.entity == null) {
            return;
        }
        d.hp -= amount;
        if (d.hp > 0) {
            d.tag.setCustomName(EngineItems.styled(tagName(d.hp)));
            return;
        }
        die(d);
    }

    private void die(Dragon d) {
        d.diedAt = now();
        ctx.unregisterBoss(d.entity);
        ctx.level().broadcastEntityEvent(d.entity, (byte) 3);
        ctx.entities().discard(d.tag);
        Vec3 at = d.entity.position();
        Vec3 home = d.colour.spawn();
        double dx = at.x - home.x;
        double dz = at.z - home.z;
        boolean counts = Math.sqrt(dx * dx + dz * dz) <= WitherKingData.COUNT_RADIUS;
        SimLog.event("wk_dragon_dead", "colour", d.colour.name(), "counted", counts);
        EnderDragon body = d.entity;
        fight.clock.after(WitherKingData.BODY, () -> ctx.entities().discard(body));
        if (counts) {
            counted++;
            // The bar drops at its next 20-tick update.
            float to = 1f - 0.2f * counted;
            bar = Math.max(0f, to);
            ctx.bar().progress(bar);
            fight.clock.after(WitherKingData.COLLAPSE_AFTER, () -> collapse(d.colour));
            if (counted >= WitherKingData.COLOURS.size()) {
                won = true;
                fight.clock.after(WitherKingData.DEFEATED_AFTER, this::defeated);
                return;
            }
        } else {
            // The colour comes back (OPEN: when; here next in line).
            queue.addFirst(d.colour);
        }
        // The next one, on the 10-tick burst grid after the death (median 8).
        long wait = WitherKingData.NEXT_AFTER_DEATH;
        if (now() - a >= WitherKingData.SECOND_AT) {
            fight.clock.after(wait, this::announceIfNone);
        }
    }

    /** One dragon at a time after the second: announce the next if none is alive or coming. */
    private void announceIfNone() {
        for (Dragon d : dragons) {
            if (d.diedAt < 0) {
                return;
            }
        }
        announce();
    }

    /** Its statue falls: a third of the blocks fly as falling blocks (debris capped), the rest just go. */
    private void collapse(WitherKingData.Colour c) {
        Vec3 s = c.spawn();
        int r = WitherKingData.STATUE_RADIUS;
        int k = 0;
        for (int x = (int) s.x - r; x <= (int) s.x + r; x++) {
            for (int z = (int) s.z - r; z <= (int) s.z + r; z++) {
                if ((x - s.x) * (x - s.x) + (z - s.z) * (z - s.z) > r * r) {
                    continue;
                }
                for (int y = WitherKingData.STATUE_Y0; y <= WitherKingData.STATUE_Y1; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState b = ctx.blocks().get(p);
                    if (b.isAir()) {
                        continue;
                    }
                    ctx.blocks().set(p, Blocks.AIR.defaultBlockState());
                    if (k++ % 3 == 0) {
                        FallingBlockEntity f = FallingBlockEntity.fall(ctx.level(), p, b);
                        f.dropItem = false;
                        f.disableDrop();
                        ctx.entities().add(f);
                    }
                }
            }
        }
        SimLog.event("wk_statue", "colour", c.name());
    }

    private void defeated() {
        SimLog.event("wk_defeated");
        ctx.defeated();
        fight.clock.after(WitherKingData.INCREDIBLE_AFTER, () -> say(WitherKingData.INCREDIBLE));
        fight.clock.after(WitherKingData.INCREDIBLE_AFTER + WitherKingData.THANK_AFTER, () -> say(WitherKingData.THANK));
        fight.clock.after(WitherKingData.INCREDIBLE_AFTER + WitherKingData.THANK_AFTER + 2, () -> over = true);
    }

    private boolean over;

    // ---------------------------------------------------------------- ticking

    @Override
    public void tick() {
        long n = now();
        if (againAt >= 0 && n >= againAt && a < 0) {
            a = n;
            bar = 1f;
            ctx.bar().boss("§kWither King", 1f);
            nextLine = n;
            announce();
            fight.clock.after(WitherKingData.SECOND_AT, this::announce);
            SimLog.event("wk_again");
        }
        if (a >= 0 && !won && n >= nextLine) {
            say(line(lineIndex++));
            nextLine = n + WitherKingData.GAP;
        }
        // The King comes down to the floor by A.
        if (king != null && king.getY() > WitherKingData.KING_FLOOR.y) {
            king.snapTo(king.getX(), Math.max(WitherKingData.KING_FLOOR.y, king.getY() - 0.5), king.getZ(), 0f, 0f);
        }
        for (Dragon d : dragons) {
            if (d.entity == null || d.entity.isRemoved()) {
                continue;
            }
            if (d.diedAt >= 0) {
                // The body rises as vanilla's death animation does, until it is removed.
                d.entity.snapTo(d.entity.getX(), d.entity.getY() + 0.1, d.entity.getZ(), d.entity.getYRot(), 0f);
                continue;
            }
            fly(d, n);
        }
    }

    /**
     * One tick of [d]'s flight: its measured route, then wandering (WitherKingData.ROUTES, WANDER_*).
     * Flyers steer: the heading turns at most TURN degrees a tick toward the next point and the
     * dragon flies forward, so turns come out as curves. Purple doesn't fly: it teleports.
     */
    private void fly(Dragon d, long n) {
        Vec3 from = d.entity.position();
        long t = n - d.spawnedAt;
        Vec3 to = routePoint(d.colour.name(), t);
        float yaw = d.yaw;
        if (to != null) {
            double dx = to.x - from.x;
            double dz = to.z - from.z;
            if (dx * dx + dz * dz > 1e-6) {
                yaw = turn(d.yaw, headingOf(dx, dz), WitherKingData.TURN * 2);
            }
        } else if (d.colour.name().equals("Purple")) {
            if (d.teleportAt < 0 || n >= d.teleportAt) {
                to = wanderPoint();
                d.teleportAt = n + WitherKingData.TELEPORT_MIN
                    + random.nextInt(WitherKingData.TELEPORT_MAX - WitherKingData.TELEPORT_MIN + 1);
                if (d.teleportAt > n + 1 && t > 0) {
                    SimLog.event("wk_dragon_teleport", "colour", d.colour.name());
                }
            } else {
                to = from;
            }
        } else {
            if (!d.steering) {
                // Off the route's end, keep its last heading.
                d.steering = true;
            }
            if (d.target == null || horizontal(from, d.target) < WitherKingData.WANDER_REACHED) {
                d.target = wanderPoint();
            }
            yaw = turn(d.yaw, headingOf(d.target.x - from.x, d.target.z - from.z), WitherKingData.TURN);
            double r = Math.toRadians(yaw);
            double dy = Math.max(-WitherKingData.CLIMB, Math.min(WitherKingData.CLIMB, d.target.y - from.y));
            to = new Vec3(from.x + Math.sin(r) * WitherKingData.WANDER_SPEED, from.y + dy,
                from.z - Math.cos(r) * WitherKingData.WANDER_SPEED);
        }
        yaw = net.minecraft.util.Mth.wrapDegrees(yaw);
        d.yaw = yaw;
        Vec3 delta = to.subtract(from);
        d.entity.snapTo(to.x, to.y, to.z, yaw, 0f);
        d.entity.setYHeadRot(yaw);
        // The hit boxes follow the body on the server (vanilla places them in the aiStep it no longer runs).
        for (net.minecraft.world.entity.boss.enderdragon.EnderDragonPart part : d.entity.getSubEntities()) {
            part.setPos(part.getX() + delta.x, part.getY() + delta.y, part.getZ() + delta.z);
        }
        d.tag.snapTo(to.x, to.y + 4, to.z, 0f, 0f);
        if (t % 10 == 0) {
            SimLog.event("wk_dragon_at", "colour", d.colour.name(), "t", t,
                "x", Math.round(to.x * 10) / 10.0, "y", Math.round(to.y * 10) / 10.0, "z", Math.round(to.z * 10) / 10.0,
                "yaw", Math.round(yaw));
        }
    }

    private Vec3 wanderPoint() {
        return new Vec3(
            WitherKingData.WANDER_X0 + random.nextDouble() * (WitherKingData.WANDER_X1 - WitherKingData.WANDER_X0),
            WitherKingData.WANDER_Y0 + random.nextDouble() * (WitherKingData.WANDER_Y1 - WitherKingData.WANDER_Y0),
            WitherKingData.WANDER_Z0 + random.nextDouble() * (WitherKingData.WANDER_Z1 - WitherKingData.WANDER_Z0));
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    /** A vanilla dragon's yaw for flying along (dx, dz): it flies toward (sin yaw, -cos yaw). */
    static float headingOf(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(dx, -dz));
    }

    /** [from] turned toward [want] by at most [max] degrees. */
    static float turn(float from, float want, float max) {
        float diff = net.minecraft.util.Mth.wrapDegrees(want - from);
        return from + Math.max(-max, Math.min(max, diff));
    }

    /** Where [colour]'s route puts a dragon [t] ticks after its spawn, or null once the route is over. */
    static Vec3 routePoint(String colour, long t) {
        double[][] r = WitherKingData.ROUTES.get(colour);
        if (r == null || t > r[r.length - 1][0]) {
            return null;
        }
        for (int i = 1; i < r.length; i++) {
            if (t <= r[i][0]) {
                double f = (t - r[i - 1][0]) / (r[i][0] - r[i - 1][0]);
                return new Vec3(r[i - 1][1] + (r[i][1] - r[i - 1][1]) * f, r[i - 1][2] + (r[i][2] - r[i - 1][2]) * f,
                    r[i - 1][3] + (r[i][3] - r[i - 1][3]) * f);
            }
        }
        return new Vec3(r[0][1], r[0][2], r[0][3]);
    }

    /** The King's lines from A, one every 62 (§8). */
    private String line(int i) {
        if (i < WitherKingData.FIXED.size()) {
            return WitherKingData.FIXED.get(i);
        }
        return switch (i) {
            case 3, 4 -> WitherKingData.FILL.get(random.nextInt(WitherKingData.FILL.size()));
            case 5 -> WitherKingData.ALLIES;
            case 6 -> WitherKingData.AT_392.get(random.nextInt(WitherKingData.AT_392.size()));
            case 7 -> WitherKingData.LORE.get(random.nextInt(WitherKingData.LORE.size()));
            default -> WitherKingData.LATE.get(random.nextInt(WitherKingData.LATE.size()));
        };
    }

    @Override
    public BossPhase next() {
        return null;
    }

    @Override
    public boolean ended() {
        return over;
    }

    @Override
    public void dispose() {
        for (Dragon d : dragons) {
            if (d.entity != null) {
                ctx.unregisterBoss(d.entity);
            }
        }
        for (LivingEntity h : husks) {
            ctx.unregisterBoss(h);
        }
        for (ServerPlayer p : ctx.players()) {
            ItemStack before = slotBefore.get(p.getUUID());
            if (before != null && carrying.containsKey(p.getUUID())) {
                p.getInventory().setItem(net.rustclear.mod.engine.Kit.MAP_SLOT, before);
            }
        }
    }

    /** For the LAN check: a relic still on its stand (or null), the carried relic's cauldron, placements. */
    public Entity anyRelic() {
        return relicStands.isEmpty() ? null : relicStands.keySet().iterator().next();
    }

    public BlockPos cauldronFor(ServerPlayer p) {
        WitherKingData.Colour c = carrying.get(p.getUUID());
        return c == null ? null : c.cauldron();
    }

    public int placed() {
        return placed;
    }

    public static boolean master(BossContext ctx) {
        return ctx.profile().master;
    }
}
