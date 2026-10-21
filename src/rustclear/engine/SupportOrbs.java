package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import net.rustclear.mod.mixin.ArmorStandAccessor;

/**
 * The Healer's Orbies passive: Support Orbs (abilities-items.md "Support Orbs", measured from 581
 * orbs in 94 recordings, all with a Healer in the party).
 *
 * With a Healer in the party, a dead mob drops an orb on its block 2.4% of the time in the clear
 * (182 of 7,460 deaths) and 0.7% in the boss (111 of 15,581), in the tick of its death. Red =
 * Damage, green = Defense, blue = Ability Damage, a third each. The orb is an invisible marker
 * stand wearing the orb's head, bobbing y0 - cos(2 pi t / 80) and spinning 18 degrees a tick, with
 * a label stand above it counting down "DAMAGE 60s" each second; it's gone after 1,200 ticks. Any
 * other living player who walks into it takes it: +20% of its stat for 10 seconds, a line for the
 * picker and one for the killer, no sound.
 */
final class SupportOrbs {
    static final double CLEAR_CHANCE = 0.024;
    static final double BOSS_CHANCE = 0.007;
    static final int LIFETIME = 1200;
    private static final int BOB_PERIOD = 80;
    private static final float SPIN = 18f;
    /** The label rides this far above the orb stand (1.89 in the traced orbs). */
    private static final double LABEL_Y = 1.9;
    /** OPEN: pickup reach; recorded removals came a median 1.3-1.6 blocks from the picker. */
    static final double PICKUP_RADIUS = 2.0;
    static final double BUFF = 0.20;
    static final int BUFF_TICKS = 200;

    enum Type {
        DAMAGE("Damage", "§c§lDAMAGE ",
            "ewogICJ0aW1lc3RhbXAiIDogMTYwNDY4NDIxNTAyMCwKICAicHJvZmlsZUlkIiA6ICI3NzI3ZDM1NjY5Zjk0MTUxODAyM2Q2MmM2ODE3NTkxOCIsCiAgInByb2ZpbGVOYW1lIiA6ICJsaWJyYXJ5ZnJlYWsiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYWI4NmRhMmUyNDNjMDVkYzA4OThiMGNjNWQzZTY0ODc3MTczMTc3ZTBhMjM5NDQyNWNlYzEwMDI1OWNiNDUyNiIKICAgIH0KICB9Cn0="),
        DEFENSE("Defense", "§a§lDEFENSE ",
            "ewogICJ0aW1lc3RhbXAiIDogMTYwNTM1NjUyNzQzOSwKICAicHJvZmlsZUlkIiA6ICJhYTZhNDA5NjU4YTk0MDIwYmU3OGQwN2JkMzVlNTg5MyIsCiAgInByb2ZpbGVOYW1lIiA6ICJiejE0IiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzE1NzhiNGFmM2ZkZDkxNTFiODUwYjEzYzY3YzQ1ODAyMjRjN2Y2MDA1MjcxM2YyZDE1MWY3YzE1ZGMwZDdiMzQiCiAgICB9CiAgfQp9"),
        ABILITY_DAMAGE("Ability Damage", "§c§lABILITY DAMAGE ",
            "ewogICJ0aW1lc3RhbXAiIDogMTYzODUyNDAzODE5OCwKICAicHJvZmlsZUlkIiA6ICIzOWEzOTMzZWE4MjU0OGU3ODQwNzQ1YzBjNGY3MjU2ZCIsCiAgInByb2ZpbGVOYW1lIiA6ICJkZW1pbmVjcmFmdGVybG9sIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzVlZTRiYjQ4MjFkMGY1ZWQ4NjVjMjEwOTBhODBiNWVlN2Q1MjI2ODQ3NmVlMjVkMzg5NzEwZjdjYzlmMTEwZDYiCiAgICB9CiAgfQp9");

        final String title;
        final String label;
        final String texture;

        Type(String title, String label, String texture) {
            this.title = title;
            this.label = label;
            this.texture = texture;
        }
    }

    private static final class Orb {
        final Type type;
        final UUID owner;
        final String ownerName;
        final Vec3 base;
        final ArmorStand stand;
        final ArmorStand label;
        int age;
        float yaw;

        Orb(Type type, ServerPlayer owner, Vec3 base, ArmorStand stand, ArmorStand label) {
            this.type = type;
            this.owner = owner.getUUID();
            this.ownerName = DungeonRun.nameColour(owner);
            this.base = base;
            this.stand = stand;
            this.label = label;
        }
    }

    private final DungeonRun run;
    private final ServerLevel level;
    private final Random random;
    private final List<Orb> orbs = new ArrayList<>();

    SupportOrbs(DungeonRun run, ServerLevel level, Random random) {
        this.run = run;
        this.level = level;
        this.random = random;
    }

    /** A mob died at [at], killed by [killer] (null: the nearest player). Maybe drops an orb. */
    void mobDied(Vec3 at, ServerPlayer killer, boolean boss) {
        if (!healerInParty()) {
            return;
        }
        if (killer == null) {
            killer = nearest(at);
            if (killer == null) {
                return;
            }
        }
        if (random.nextDouble() >= (boss ? BOSS_CHANCE : CLEAR_CHANCE)) {
            return;
        }
        spawn(Type.values()[random.nextInt(Type.values().length)], killer,
            new Vec3(Math.floor(at.x) + 0.5, Math.floor(at.y), Math.floor(at.z) + 0.5));
    }

    void spawn(Type type, ServerPlayer owner, Vec3 base) {
        ArmorStand stand = new ArmorStand(EntityTypes.ARMOR_STAND, level);
        ((ArmorStandAccessor) stand).rustclear$setMarker(true);
        ((ArmorStandAccessor) stand).rustclear$setNoBasePlate(true);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        stand.setPermanentlyInvulnerable(true);
        stand.setSilent(true);
        stand.snapTo(base.x, base.y, base.z, 0f, 0f);
        stand.setItemSlot(EquipmentSlot.HEAD, EngineItems.head(type.texture, "§aSupport Orb"));
        level.addFreshEntity(stand);
        // The label comes a tick later on Hypixel (190 of 213); spawned together here.
        ArmorStand label = new ArmorStand(EntityTypes.ARMOR_STAND, level);
        ((ArmorStandAccessor) label).rustclear$setMarker(true);
        label.setInvisible(true);
        label.setNoGravity(true);
        label.setPermanentlyInvulnerable(true);
        label.setSilent(true);
        label.setCustomNameVisible(true);
        label.setCustomName(EngineItems.text(labelText(type, 0)));
        label.snapTo(base.x, base.y - 1 + LABEL_Y, base.z, 0f, 0f);
        level.addFreshEntity(label);
        orbs.add(new Orb(type, owner, base, stand, label));
        SimLog.event("orb_drop", "type", type.name(), "x", base.x, "y", base.y, "z", base.z);
    }

    static String labelText(Type type, int age) {
        return type.label + "§e" + (LIFETIME - age + 19) / 20 + "s";
    }

    /** The orb's height above its block at [age]: y0 - cos(2 pi t / 80). */
    static double bob(int age) {
        return -Math.cos(2 * Math.PI * age / BOB_PERIOD);
    }

    void tick() {
        for (Iterator<Orb> it = orbs.iterator(); it.hasNext(); ) {
            Orb orb = it.next();
            orb.age++;
            if (orb.age >= LIFETIME || orb.stand.isRemoved()) {
                remove(orb);
                it.remove();
                continue;
            }
            double y = orb.base.y + bob(orb.age);
            orb.yaw = (orb.yaw + SPIN) % 360f;
            orb.stand.snapTo(orb.base.x, y, orb.base.z, orb.yaw, 0f);
            orb.label.snapTo(orb.base.x, y + LABEL_Y, orb.base.z, 0f, 0f);
            if (orb.age % 20 == 0) {
                orb.label.setCustomName(EngineItems.text(labelText(orb.type, orb.age)));
            }
            ServerPlayer picker = pickerOf(orb);
            if (picker != null) {
                pickUp(orb, picker);
                remove(orb);
                it.remove();
            }
        }
    }

    private ServerPlayer pickerOf(Orb orb) {
        for (ServerPlayer player : level.players()) {
            if (player.getUUID().equals(orb.owner) || player.isSpectator() || run.combat().isGhost(player)) {
                continue;
            }
            double dx = player.getX() - orb.base.x;
            double dz = player.getZ() - orb.base.z;
            double dy = player.getY() - orb.base.y;
            if (dx * dx + dz * dz <= PICKUP_RADIUS * PICKUP_RADIUS && dy > -2.5 && dy < 2.5) {
                return player;
            }
        }
        return null;
    }

    private void pickUp(Orb orb, ServerPlayer picker) {
        run.abilities().buffOrb(picker, orb.type, BUFF_TICKS);
        SimLog.event("orb_pickup", "type", orb.type.name(), "age", orb.age);
        // OPEN: the heal. All 31 recorded pickups were at full health ("for 0❤"); no heal is modelled.
        picker.sendSystemMessage(EngineItems.text("§c◕ §eYou picked up a " + orb.type.title + " Orb from "
            + orb.ownerName + " §ehealing you for §c0❤ §eand granting you +§a20% §e" + orb.type.title
            + " for §b10 §eseconds."));
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(orb.owner);
        if (owner != null) {
            owner.sendSystemMessage(EngineItems.text("§c◕ " + DungeonRun.nameColour(picker) + " §epicked up your "
                + orb.type.title + " Orb!"));
        }
    }

    private static void remove(Orb orb) {
        orb.stand.discard();
        orb.label.discard();
    }

    private boolean healerInParty() {
        for (ServerPlayer player : level.players()) {
            if (PlayerStats.classOf(player) == DungeonClass.HEALER) {
                return true;
            }
        }
        return false;
    }

    private ServerPlayer nearest(Vec3 at) {
        ServerPlayer best = null;
        for (ServerPlayer player : level.players()) {
            if (best == null || player.distanceToSqr(at) < best.distanceToSqr(at)) {
                best = player;
            }
        }
        return best;
    }

    int count() {
        return orbs.size();
    }

    void dispose() {
        orbs.forEach(SupportOrbs::remove);
        orbs.clear();
    }
}
