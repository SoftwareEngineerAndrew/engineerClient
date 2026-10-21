package net.rustclear.mod.engine;

import com.mojang.math.Transformation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Bonemerang's Swing: the bone spins out along the aim (a block stops it short), then flies back
 * to whoever threw it, hitting each foe once on the way out and once on the way back for an
 * arrow's damage. While it's out, the thrower's Bonemerang is a ghast tear and can't be thrown
 * again; it turns back into the bone when the bone gets home. Mobs holding a bone throw theirs at
 * players the same way, for their floor damage.
 */
final class Bonemerangs {
    static final double RANGE = 10;
    /** Blocks a tick, both ways. */
    private static final double SPEED = 0.9;
    /** A bone that can't get home in this long (thrower boxed in, gone...) just ends. */
    private static final int MAX_TICKS = 80;
    private static final float SPIN_DEGREES = 50f;
    private static final double HIT_RADIUS = 0.6;
    /** The throw id on a thrown player Bonemerang's custom data, to find that stack again on return. */
    private static final String THROWN = "rustclear_thrown";
    private static final Identifier TEAR = Identifier.withDefaultNamespace("ghast_tear");

    private static final class Bone {
        final Display.ItemDisplay display;
        final LivingEntity thrower;
        /** A mob thrower's archetype (for its damage); null for a player. */
        final MobSystem.Archetype archetype;
        final int id;
        /** A mob's bone, back in its hand when this one gets home. */
        final ItemStack mobHeld;
        final Set<Entity> hitOut = new HashSet<>();
        final Set<Entity> hitBack = new HashSet<>();
        Vec3 pos;
        final Vec3 dir;
        double travelled;
        boolean returning;
        int ticks;
        float spin;

        Bone(Display.ItemDisplay display, LivingEntity thrower, MobSystem.Archetype archetype, int id, ItemStack mobHeld,
             Vec3 pos, Vec3 dir) {
            this.display = display;
            this.thrower = thrower;
            this.archetype = archetype;
            this.id = id;
            this.mobHeld = mobHeld;
            this.pos = pos;
            this.dir = dir;
        }
    }

    private final DungeonRun run;
    private final ServerLevel level;
    private final List<Bone> bones = new ArrayList<>();
    private int nextId = 1;

    Bonemerangs(DungeonRun run) {
        this.run = run;
        this.level = run.level;
    }

    /** Whether [stack] is out being thrown (its ghast tear look). */
    static boolean thrown(ItemStack stack) {
        return TEAR.equals(stack.get(DataComponents.ITEM_MODEL)) && !stack.is(Items.GHAST_TEAR);
    }

    /** Whether a mob holding [stack] has a bone to throw. */
    static boolean mobCanThrow(ItemStack stack) {
        return stack.is(Items.BONE) && !thrown(stack);
    }

    /** [player] throws the Bonemerang in their hand. False when it is already out. */
    boolean throwFrom(ServerPlayer player) {
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty() || thrown(held)) {
            return false;
        }
        int id = nextId++;
        CustomData.update(DataComponents.CUSTOM_DATA, held, tag -> tag.putInt(THROWN, id));
        held.set(DataComponents.ITEM_MODEL, TEAR);
        Vec3 from = player.getEyePosition().add(0, -0.4, 0);
        launch(player, null, id, ItemStack.EMPTY, from, player.getLookAngle());
        return true;
    }

    /** [mob] throws the bone in its hand at [at] (the target's feet). */
    void throwFrom(LivingEntity mob, MobSystem.Archetype archetype, Vec3 at) {
        ItemStack held = mob.getMainHandItem();
        ItemStack tear = held.copy();
        tear.set(DataComponents.ITEM_MODEL, TEAR);
        mob.setItemSlot(EquipmentSlot.MAINHAND, tear);
        Vec3 from = mob.position().add(0, 1.4, 0);
        Vec3 aim = at.add(0, 1.0, 0).subtract(from);
        launch(mob, archetype, nextId++, held, from, aim.lengthSqr() < 1e-6 ? mob.getLookAngle() : aim.normalize());
    }

    private void launch(LivingEntity thrower, MobSystem.Archetype archetype, int id, ItemStack mobHeld, Vec3 from, Vec3 dir) {
        Display.ItemDisplay display = new Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, level);
        display.setItemStack(new ItemStack(Items.BONE));
        display.setItemTransform(ItemDisplayContext.FIXED);
        display.setPosRotInterpolationDuration(1);
        display.setTransformationInterpolationDuration(1);
        display.snapTo(from.x, from.y, from.z, 0f, 0f);
        display.setNoGravity(true);
        display.setPermanentlyInvulnerable(true);
        display.setTransformation(spun(0f));
        level.addFreshEntity(display);
        bones.add(new Bone(display, thrower, archetype, id, mobHeld, from, dir));
        level.playSound(null, from.x, from.y, from.z, SoundEvents.SKELETON_SHOOT,
            thrower instanceof ServerPlayer ? SoundSource.PLAYERS : SoundSource.HOSTILE, 1f, 1.4f);
    }

    /** The bone lying flat, turned [degrees] about the vertical: a boomerang's spin. */
    private static Transformation spun(float degrees) {
        Quaternionf rotation = new Quaternionf().rotateY((float) Math.toRadians(degrees)).rotateX((float) Math.toRadians(90));
        return new Transformation(new Vector3f(), rotation, new Vector3f(1f, 1f, 1f), new Quaternionf());
    }

    void tick() {
        for (Iterator<Bone> it = bones.iterator(); it.hasNext(); ) {
            Bone b = it.next();
            if (step(b)) {
                finish(b);
                it.remove();
            }
        }
    }

    /** One tick of [b]'s flight. True when it is home (or lost). */
    private boolean step(Bone b) {
        b.ticks++;
        if (b.display.isRemoved() || b.ticks > MAX_TICKS || b.thrower.isRemoved() || b.thrower.level() != level) {
            return true;
        }
        Vec3 next;
        if (!b.returning) {
            next = b.pos.add(b.dir.scale(SPEED));
            var clip = level.clip(new ClipContext(b.pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, b.display));
            b.travelled += SPEED;
            if (clip.getType() != HitResult.Type.MISS) {
                next = clip.getLocation().subtract(b.dir.scale(0.2));
                b.returning = true;
            } else if (b.travelled >= RANGE) {
                b.returning = true;
            }
            hits(b, b.pos, next, b.hitOut);
        } else {
            // Home through anything in the way, as Hypixel's does.
            Vec3 home = b.thrower.position().add(0, b.thrower.getBbHeight() * 0.6, 0);
            Vec3 to = home.subtract(b.pos);
            if (to.length() <= SPEED) {
                return true;
            }
            next = b.pos.add(to.normalize().scale(SPEED));
            hits(b, b.pos, next, b.hitBack);
        }
        b.pos = next;
        b.spin = (b.spin + SPIN_DEGREES) % 360f;
        b.display.setPos(next.x, next.y, next.z);
        b.display.setTransformation(spun(b.spin));
        b.display.setTransformationInterpolationDelay(0);
        return false;
    }

    /** Everything [b] passes on the segment that it hasn't hit this way yet. */
    private void hits(Bone b, Vec3 from, Vec3 to, Set<Entity> already) {
        if (b.thrower instanceof ServerPlayer player) {
            for (Entity mob : run.mobs().mobsAlong(from, to, HIT_RADIUS)) {
                if (already.add(mob)) {
                    run.mobs().arrow(player, mob, 1.0, false);
                }
            }
            return;
        }
        for (ServerPlayer player : level.players()) {
            if (Ghosts.absent(player) || already.contains(player)) {
                continue;
            }
            var box = player.getBoundingBox().inflate(HIT_RADIUS / 2);
            if (box.contains(to) || box.clip(from, to).isPresent()) {
                already.add(player);
                run.combat().mobHit(player, b.archetype);
            }
        }
    }

    /** [b] is home: the bone is gone and the thrower's Bonemerang is a bone again. */
    private void finish(Bone b) {
        b.display.discard();
        if (b.thrower instanceof ServerPlayer player) {
            var inv = player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                CustomData data = stack.get(DataComponents.CUSTOM_DATA);
                if (data != null && data.copyTag().getIntOr(THROWN, 0) == b.id) {
                    CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.remove(THROWN));
                    stack.set(DataComponents.ITEM_MODEL, stack.getItem().components().get(DataComponents.ITEM_MODEL));
                }
            }
            if (!player.isRemoved()) {
                level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.5f, 1.2f);
            }
        } else if (!b.thrower.isRemoved() && thrown(b.thrower.getMainHandItem())) {
            b.thrower.setItemSlot(EquipmentSlot.MAINHAND, b.mobHeld);
        }
    }

    void dispose() {
        bones.forEach(this::finish);
        bones.clear();
    }
}
