package net.rustclear.mod.engine;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

/**
 * A floating key or Superboom TNT (secrets.rs PickupEntityImpl): an invisible armour stand
 * wearing it, spinning in place, picked up by walking into it. After 10 seconds whoever is
 * closest gets it.
 */
public final class Pickup {
    public enum Kind {
        WITHER("§8Wither Key", "ewogICJ0aW1lc3RhbXAiIDogMTYwMzYxMDQ0MzU4MywKICAicHJvZmlsZUlkIiA6ICIzM2ViZDMyYmIzMzk0YWQ5YWM2NzBjOTZjNTQ5YmE3ZSIsCiAgInByb2ZpbGVOYW1lIiA6ICJEYW5ub0JhbmFubm9YRCIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9lNDllYzdkODJiMTQxNWFjYWUyMDU5Zjc4Y2QxZDE3NTRiOWRlOWIxOGNhNTlmNjA5MDI0YzRhZjg0M2Q0ZDI0IgogICAgfQogIH0KfQ=="),
        BLOOD("§cBlood Key", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNmU1Y2Y3ZjJlMGY2YjE2N2IwYjZmZDBjNGFjMTZjYTcwZTRjNWM4MTFiOGQ1YWQwZWVkMmUzYWE2ZGQyYjcifX19"),
        TNT("§9Superboom TNT", null),
        BLESSING_LIFE("§dBlessing of Life", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZTkzZTIwNjg2MTc4NzJjNTQyZWNkYTFkMjdkZjRlY2U5MWM2OTk5MDdiZjMyN2M0ZGRiODUzMDk0MTJkMzkzOSJ9fX0="),
        BLESSING_POWER("§dBlessing of Power", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZTkzZTIwNjg2MTc4NzJjNTQyZWNkYTFkMjdkZjRlY2U5MWM2OTk5MDdiZjMyN2M0ZGRiODUzMDk0MTJkMzkzOSJ9fX0="),
        BLESSING_STONE("§dBlessing of Stone", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZTkzZTIwNjg2MTc4NzJjNTQyZWNkYTFkMjdkZjRlY2U5MWM2OTk5MDdiZjMyN2M0ZGRiODUzMDk0MTJkMzkzOSJ9fX0="),
        BLESSING_WISDOM("§dBlessing of Wisdom", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZTkzZTIwNjg2MTc4NzJjNTQyZWNkYTFkMjdkZjRlY2U5MWM2OTk5MDdiZjMyN2M0ZGRiODUzMDk0MTJkMzkzOSJ9fX0=");

        static final Kind[] BLESSINGS = {BLESSING_LIFE, BLESSING_POWER, BLESSING_STONE, BLESSING_WISDOM};

        public boolean isBlessing() {
            return name().startsWith("BLESSING_");
        }

        public final String name;
        final String texture;

        Kind(String name, String texture) {
            this.name = name;
            this.texture = texture;
        }

        ItemStack item() {
            if (texture != null) {
                return EngineItems.head(texture, null);
            }
            ItemStack tnt = new ItemStack(Items.TNT);
            tnt.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            return tnt;
        }
    }

    private static final int COOLDOWN_TICKS = 5;
    private static final double RADIUS = 2.0;
    /**
     * Keys are picked up at about 5 blocks, horizontal (clear-rooms-doors.md §5: 4.5-5.3 from the
     * recorder's own position, low confidence). // OPEN: 4 (exact radius; 3D or horizontal)
     */
    private static final double KEY_RADIUS = 5.0;
    private static final int AUTO_PICKUP_TICKS = 200;
    /** A key's head turns 2 degrees and rises 0.01 blocks a tick (one key traced, §5). */
    private static final float KEY_SPIN = 2f;
    private static final double KEY_RISE = 0.01;
    /** A key appears 0.72 below the dying mob's feet. */
    public static final double KEY_DROP = 0.72;

    public final Kind kind;
    private final ArmorStand stand;
    /** A key's own name stand ("Wither Key" over the head), or null. */
    private final ArmorStand label;
    private int age;

    /** Ticks since it dropped. */
    int age() {
        return age;
    }

    private Pickup(Kind kind, ArmorStand stand, ArmorStand label) {
        this.kind = kind;
        this.stand = stand;
        this.label = label;
    }

    private boolean isKey() {
        return kind == Kind.WITHER || kind == Kind.BLOOD;
    }

    /**
     * Floats [kind] at (x, y, z). A key is two stands as on Hypixel: an invisible one wearing the
     * key's head, and a name stand "Wither Key" above it.
     */
    public static Pickup spawn(ServerLevel level, double x, double y, double z, Kind kind) {
        ArmorStand stand = new ArmorStand(EntityTypes.ARMOR_STAND, level);
        stand.snapTo(x, y, z, 0f, 0f);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        stand.setPermanentlyInvulnerable(true);
        stand.setSilent(true);
        stand.setItemSlot(EquipmentSlot.HEAD, kind.item());
        ArmorStand label = null;
        if (kind == Kind.WITHER || kind == Kind.BLOOD) {
            level.addFreshEntity(stand);
            label = new ArmorStand(EntityTypes.ARMOR_STAND, level);
            ((net.rustclear.mod.mixin.ArmorStandAccessor) label).rustclear$setMarker(true);
            label.snapTo(x, y + 2.3, z, 0f, 0f);
            label.setInvisible(true);
            label.setNoGravity(true);
            label.setPermanentlyInvulnerable(true);
            label.setCustomName(EngineItems.text(kind.name));
            label.setCustomNameVisible(true);
            level.addFreshEntity(label);
        } else {
            stand.setCustomName(EngineItems.text(kind.name));
            stand.setCustomNameVisible(true);
            level.addFreshEntity(stand);
        }
        return new Pickup(kind, stand, label);
    }

    /** Spins, and returns who picked it up (removing it), or null. While [blocked], nobody can. */
    public ServerPlayer tick(ServerLevel level, boolean blocked) {
        age++;
        if (isKey()) {
            stand.setYRot(stand.getYRot() + KEY_SPIN);
            stand.setPos(stand.getX(), stand.getY() + KEY_RISE, stand.getZ());
            label.setPos(label.getX(), label.getY() + KEY_RISE, label.getZ());
        } else {
            stand.setYRot(stand.getYRot() + 6f);
        }
        if (age < COOLDOWN_TICKS || blocked) {
            return null;
        }
        ServerPlayer taker = null;
        if (age >= AUTO_PICKUP_TICKS) {
            double best = Double.MAX_VALUE;
            for (ServerPlayer player : level.players()) {
                if (Ghosts.isGhost(player)) {
                    continue; // a ghost picks nothing up
                }
                double d = player.distanceToSqr(stand);
                if (d < best) {
                    best = d;
                    taker = player;
                }
            }
        } else if (isKey()) {
            for (ServerPlayer player : level.players()) {
                if (Ghosts.isGhost(player)) {
                    continue; // a ghost picks nothing up
                }
                double dx = player.getX() - stand.getX();
                double dz = player.getZ() - stand.getZ();
                if (dx * dx + dz * dz <= KEY_RADIUS * KEY_RADIUS && Math.abs(player.getY() - stand.getY()) <= 8) {
                    taker = player;
                    break;
                }
            }
        } else {
            double r = RADIUS;
            AABB area = new AABB(stand.getX() - r, stand.getY() - r, stand.getZ() - r,
                stand.getX() + r, stand.getY() + r, stand.getZ() + r);
            for (ServerPlayer player : level.players()) {
                if (Ghosts.isGhost(player)) {
                    continue; // a ghost picks nothing up
                }
                if (player.getBoundingBox().intersects(area)) {
                    taker = player;
                    break;
                }
            }
        }
        if (taker != null) {
            level.playSound(null, taker.getX(), taker.getY(), taker.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 1f, 1f);
            remove();
        }
        return taker;
    }

    public void remove() {
        stand.discard();
        if (label != null) {
            label.discard();
        }
    }

    public net.minecraft.world.phys.Vec3 position() {
        return stand.position();
    }
}
