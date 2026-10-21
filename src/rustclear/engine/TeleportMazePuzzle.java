package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;

/**
 * Teleport Maze (Smalldungeons dungeon/room/teleport_maze.rs): 30 end portal frame pads in nine
 * chambers, each linked to a pad in another chamber. Every teleport turns the player toward the
 * goal pad; reaching it reveals the blessing chest, and opening that solves the room. The pad in
 * the reward room sends the player back to the start.
 */
final class TeleportMazePuzzle extends Puzzle {
    static final int[][] PAD_POSITIONS = {
        {4, 69, 6}, {4, 69, 12}, {4, 69, 14}, {4, 69, 20}, {4, 69, 22}, {4, 69, 28},
        {10, 69, 6}, {10, 69, 12}, {10, 69, 14}, {10, 69, 20}, {10, 69, 22}, {10, 69, 28},
        {12, 69, 22}, {12, 69, 28},
        {15, 69, 12}, {15, 69, 14},
        {18, 69, 22}, {18, 69, 28},
        {20, 69, 6}, {20, 69, 12}, {20, 69, 14}, {20, 69, 20}, {20, 69, 22}, {20, 69, 28},
        {26, 69, 6}, {26, 69, 12}, {26, 69, 14}, {26, 69, 20}, {26, 69, 22}, {26, 69, 28},
    };
    private static final int ENTRANCE_INDEX = 14;
    private static final int EXIT_INDEX = 15;
    private static final int[] EXIT_DESTINATION = {15, 69, 9};
    private static final int[] REWARD_LANDING = {15, 68, 17};
    private static final int[] EXISTING_CHEST = {15, 70, 20};
    /** Which of the nine chambers each pad is in. */
    static final int[] SECTION_OF = {
        0, 0, 1, 1, 2, 2, 0, 0, 1, 1, 2, 2, 5, 5, 3, 4, 5, 5, 6, 6, 7, 7, 8, 8, 6, 6, 7, 7, 8, 8,
    };
    private static final int NUM_SECTIONS = 9;
    private static final int ENTRANCE_SECTION = 3;
    private static final long TELEPORT_GRACE_TICKS = 10;
    /** 1.8 stone slab (id 44); meta bit 8 is the top half. */
    private static final int LEGACY_STONE_SLAB = 44;
    private static final int BLESSING_LIFETIME_TICKS = Blessings.CHEST_DELAY;
    private static final float[] HARP = {0.7936508f, 0.8888889f, 1.0f, 1.0952381f, 1.1904762f};

    /** The chest's floating blessing (EssenceEntityImpl with a blessing). */
    private static final class Blessing {
        final ArmorStand stand;
        final Pickup.Kind kind;
        final ServerPlayer finder;
        int age;

        Blessing(ArmorStand stand, Pickup.Kind kind, ServerPlayer finder) {
            this.stand = stand;
            this.kind = kind;
            this.finder = finder;
        }
    }

    private final List<BlockPos> pads = new ArrayList<>();
    private int[] dest;
    private int lookAtIndex;
    private BlockPos exitDestination;
    private BlockPos rewardLanding;
    private BlockPos chest;
    private boolean chestRevealed;
    private boolean chestOpened;
    /** Feet landing spot per pad (find_corner_slab, worked out once). */
    private double[][] landings;
    private final Map<UUID, Integer> playerPad = new HashMap<>();
    private final Map<UUID, Long> lastTeleportTick = new HashMap<>();
    private final List<Blessing> blessings = new ArrayList<>();


    TeleportMazePuzzle(DungeonRun run, RoomState room) {
        super(run, room);
    }

    /** setup: the pads, a random goal pad and the pad links. */
    @Override
    void setup() {
        for (int[] p : PAD_POSITIONS) {
            pads.add(world(p[0], p[1], p[2]));
        }
        exitDestination = world(EXIT_DESTINATION[0], EXIT_DESTINATION[1], EXIT_DESTINATION[2]);
        rewardLanding = world(REWARD_LANDING[0], REWARD_LANDING[1], REWARD_LANDING[2]);
        chest = world(EXISTING_CHEST[0], EXISTING_CHEST[1], EXISTING_CHEST[2]);

        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < pads.size(); i++) {
            if (i != EXIT_INDEX && i != ENTRANCE_INDEX) {
                candidates.add(i);
            }
        }
        lookAtIndex = candidates.get(random.nextInt(candidates.size()));
        dest = buildMazeGraph(pads.size(), lookAtIndex, EXIT_INDEX);

        landings = new double[pads.size()][];
        for (int i = 0; i < pads.size(); i++) {
            landings[i] = cornerLanding(i);
        }
    }

    /**
     * build_maze_graph: a random pairing of all pads that never links two pads of one chamber,
     * built around a random path through every chamber (entrance first, the goal's last) so the
     * goal can always be reached.
     */
    private int[] buildMazeGraph(int count, int goalIndex, int excludeIndex) {
        int goalSection = SECTION_OF[goalIndex];
        int excludeSection = SECTION_OF[excludeIndex];
        List<List<Integer>> sectionPads = new ArrayList<>();
        for (int s = 0; s < NUM_SECTIONS; s++) {
            sectionPads.add(new ArrayList<>());
        }
        for (int i = 0; i < count; i++) {
            sectionPads.get(SECTION_OF[i]).add(i);
        }
        int[] links = new int[count];
        Arrays.fill(links, -1);
        boolean[] used = new boolean[count];

        List<Integer> middle = new ArrayList<>();
        for (int s = 0; s < NUM_SECTIONS; s++) {
            if (s != goalSection && s != ENTRANCE_SECTION && s != excludeSection) {
                middle.add(s);
            }
        }
        Collections.shuffle(middle, random);
        List<Integer> path = new ArrayList<>();
        path.add(ENTRANCE_SECTION);
        path.addAll(middle);
        path.add(goalSection);

        for (int i = 1; i < path.size(); i++) {
            int a = pickUnused(sectionPads.get(path.get(i)), used);
            int b = pickUnused(sectionPads.get(path.get(i - 1)), used);
            used[a] = true;
            used[b] = true;
            links[a] = b;
            links[b] = a;
        }

        List<Integer> leftover = new ArrayList<>();
        for (int p = 0; p < count; p++) {
            if (!used[p]) {
                leftover.add(p);
            }
        }
        for (int attempt = 0; attempt < 2000; attempt++) {
            List<Integer> pool = new ArrayList<>(leftover);
            Collections.shuffle(pool, random);
            int[] trial = new int[count];
            Arrays.fill(trial, -1);
            boolean ok = true;
            while (!pool.isEmpty()) {
                int a = pool.remove(pool.size() - 1);
                int match = -1;
                for (int j = 0; j < pool.size(); j++) {
                    if (SECTION_OF[pool.get(j)] != SECTION_OF[a]) {
                        match = j;
                        break;
                    }
                }
                if (match < 0) {
                    ok = false;
                    break;
                }
                int b = pool.remove(match);
                trial[a] = b;
                trial[b] = a;
            }
            if (ok) {
                for (int i = 0; i < count; i++) {
                    if (trial[i] >= 0) {
                        links[i] = trial[i];
                    }
                }
                return links;
            }
        }
        for (int i = 0; i + 1 < leftover.size(); i += 2) {
            links[leftover.get(i)] = leftover.get(i + 1);
            links[leftover.get(i + 1)] = leftover.get(i);
        }
        return links;
    }

    private int pickUnused(List<Integer> options, boolean[] used) {
        List<Integer> free = new ArrayList<>();
        for (int p : options) {
            if (!used[p]) {
                free.add(p);
            }
        }
        return free.get(random.nextInt(free.size()));
    }

    /**
     * find_corner_slab: a pad's landing spot is the stone slab on its diagonal (for the two
     * one-pad chambers, the one most toward the goal pad), else the top of the pad itself.
     * Returns world feet {x, y, z}.
     */
    private double[] cornerLanding(int index) {
        int[] pad = PAD_POSITIONS[index];
        int[] look = PAD_POSITIONS[lookAtIndex];
        int[] best = null;
        boolean bestTop = false;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int[] d : new int[][] {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
            int x = pad[0] + d[0];
            int z = pad[2] + d[1];
            int legacy = room.data().blockAt(x, pad[1], z);
            if (legacy >> 4 != LEGACY_STONE_SLAB) {
                continue;
            }
            double score = d[0] * (double) (look[0] - pad[0]) + d[1] * (double) (look[2] - pad[2]);
            if (best == null || score > bestScore) {
                best = new int[] {x, pad[1], z};
                bestTop = (legacy & 8) != 0;
                bestScore = score;
            }
        }
        if (best == null) {
            BlockPos p = pads.get(index);
            return new double[] {p.getX() + 0.5, p.getY() + 1.0, p.getZ() + 0.5};
        }
        BlockPos corner = world(best[0], best[1], best[2]);
        return new double[] {corner.getX() + 0.5, corner.getY() + (bestTop ? 1.0 : 0.5), corner.getZ() + 0.5};
    }

    /** tick: every player's pad steps, and the blessing's hover. */
    @Override
    void tick() {
        for (ServerPlayer player : List.copyOf(level.players())) {
            tickPlayer(player);
        }
        tickBlessings();
    }

    @Override
    boolean useBlock(ServerPlayer player, BlockPos pos) {
        if (!pos.equals(chest)) {
            return false;
        }
        if (chestRevealed && !chestOpened) {
            chestOpened = true;
            openChest(player);
        }
        return true;
    }

    @Override
    boolean isInteractable(BlockPos pos) {
        return chestRevealed && pos.equals(chest);
    }

    @Override
    void dispose() {
        blessings.clear();
    }

    // ---------------------------------------------------------------- pads

    /** tick_player: stepping onto a pad (edge-triggered) teleports the player. */
    private void tickPlayer(ServerPlayer player) {
        UUID id = player.getUUID();
        long now = level.getGameTime();
        Long last = lastTeleportTick.get(id);
        if (last != null && now - last < TELEPORT_GRACE_TICKS) {
            return;
        }
        BlockPos feet = new BlockPos((int) Math.floor(player.getX()), (int) Math.floor(player.getY() - 0.1), (int) Math.floor(player.getZ()));
        int index = pads.indexOf(feet);
        Integer current = index < 0 ? null : index;
        if (Objects.equals(current, playerPad.get(id))) {
            return;
        }
        if (current == null) {
            playerPad.remove(id);
            return;
        }
        playerPad.put(id, current);
        int padIndex = current;

        if (padIndex == EXIT_INDEX) {
            double x = exitDestination.getX() + 0.5;
            double y = exitDestination.getY();
            double z = exitDestination.getZ() + 0.5;
            lastTeleportTick.put(id, now);
            teleport(player, x, y, z, turnYaw(180f));
            teleportEffects(x, y, z);
            return;
        }

        int landingIndex;
        boolean win;
        if (padIndex == lookAtIndex) {
            landingIndex = padIndex;
            win = true;
        } else {
            landingIndex = dest[padIndex];
            win = landingIndex == lookAtIndex;
        }
        if (padIndex != landingIndex) {
            playerPad.put(id, landingIndex);
        }
        if (win) {
            teleportToRewardChest(player);
            return;
        }

        BlockPos landingPad = pads.get(landingIndex);
        BlockPos lookAt = pads.get(lookAtIndex);
        double[] feetAt = landings[landingIndex];
        float yaw = yawTowards(lookAt.getX() + 0.5 - feetAt[0], lookAt.getZ() + 0.5 - feetAt[2]);
        lastTeleportTick.put(id, now);
        // First on the pad at y + 0.5 (what OdinClient's solver watches for), then the real spot.
        teleport(player, landingPad.getX() + 0.5, landingPad.getY() + 0.5, landingPad.getZ() + 0.5, yaw);
        teleport(player, feetAt[0], feetAt[1], feetAt[2], yaw);
        teleportEffects(feetAt[0], feetAt[1], feetAt[2]);
    }

    /** teleport_to_reward_chest: onto the platform before the chest, facing it; the chest appears the first time. */
    private void teleportToRewardChest(ServerPlayer player) {
        boolean alreadyRevealed = chestRevealed;
        chestRevealed = true;
        double x = rewardLanding.getX() + 0.5;
        double y = rewardLanding.getY() + 1.0;
        double z = rewardLanding.getZ() + 0.5;
        teleport(player, x, y, z, yawTowards(chest.getX() + 0.5 - x, chest.getZ() + 0.5 - z));
        teleportEffects(x, y, z);
        if (!alreadyRevealed) {
            setBlock(chest, Blocks.AIR.defaultBlockState());
            setBlock(chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, turn(Direction.NORTH)));
            level.sendParticles(ParticleTypes.FLAME, chest.getX() + 0.5, chest.getY() + 1.0, chest.getZ() + 0.5, 1, 0, 0, 0, 0);
        }
    }

    /** server_teleport with a set look, pitch level. */
    private void teleport(ServerPlayer player, double x, double y, double z, float yaw) {
        player.teleportTo(level, x, y, z, Set.of(), yaw, 0f, true);
        player.resetFallDistance();
    }

    /** play_teleport_effects: mob.endermen.portal and a burst of portal particles. */
    private void teleportEffects(double x, double y, double z) {
        level.playSound(null, x, y, z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1f, 1f);
        level.sendParticles(ParticleTypes.PORTAL, x, y + 0.5, z, 20, 0.3, 0.5, 0.3, 0);
    }

    /** movement::yaw_towards. */
    private static float yawTowards(double dx, double dz) {
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        return yaw < 0 ? yaw + 360f : yaw;
    }

    // ---------------------------------------------------------------- reward chest

    /** The Chest interact handler for a puzzle_room_index blessing chest. */
    private void openChest(ServerPlayer player) {
        level.blockEvent(chest, level.getBlockState(chest).getBlock(), 1, 1);
        level.playSound(null, chest, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 1f, 0.975f);
        run.foundSecret(room);
        solvedBy(player, "Teleport Maze");

        for (int i = 0; i < HARP.length; i++) {
            float pitch = HARP[i];
            schedule(i * 5, () -> level.playSound(null, chest.getX() + 0.5, chest.getY() + 0.5, chest.getZ() + 0.5,
                SoundEvents.NOTE_BLOCK_HARP.value(), SoundSource.RECORDS, 1f, pitch));
        }
        Pickup.Kind kind = Blessings.puzzleKind(random);
        ArmorStand stand = new ArmorStand(EntityTypes.ARMOR_STAND, level);
        stand.snapTo(chest.getX() + 0.5, chest.getY() - 1.4, chest.getZ() + 0.5, 0f, 0f);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        stand.setPermanentlyInvulnerable(true);
        stand.setSilent(true);
        stand.setCustomName(EngineItems.text(kind.name));
        stand.setCustomNameVisible(true);
        stand.setItemSlot(EquipmentSlot.HEAD, EngineItems.head(kind.texture, null));
        blessings.add(new Blessing(track(stand), kind, player));
    }

    /** EssenceEntityImpl::tick: rises, spins, and grants the blessing 23 ticks after the lid (Blessings). */
    private void tickBlessings() {
        for (Iterator<Blessing> it = blessings.iterator(); it.hasNext(); ) {
            Blessing blessing = it.next();
            ArmorStand stand = blessing.stand;
            blessing.age++;
            if (blessing.age < 20) {
                stand.setPos(stand.getX(), stand.getY() + 0.04, stand.getZ());
            }
            stand.setYRot(stand.getYRot() + 15f);
            if (blessing.age % 5 == 0) {
                level.sendParticles(ParticleTypes.CLOUD, stand.getX(), stand.getY() + 1.5, stand.getZ(), 5, 0, 0, 0, 0.06);
            }
            if (blessing.age >= BLESSING_LIFETIME_TICKS) {
                Blessings.grant(run, blessing.kind, 5, blessing.finder, -1);
                for (int i = 0; i < 2; i++) {
                    level.playSound(null, stand.getX(), stand.getY(), stand.getZ(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1f, 1.5f);
                }
                stand.discard();
                it.remove();
            }
        }
    }
}
