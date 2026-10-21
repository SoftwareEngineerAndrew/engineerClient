package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.block.Blocks;
import net.rustclear.mod.mixin.MannequinAccessor;

/**
 * Three Weirdos (Smalldungeons dungeon/room/three_weirdos.rs): three NPCs each make one statement
 * about which chest holds the reward; exactly one is true, and its speaker stands by the right chest.
 */
final class ThreeWeirdosPuzzle extends Puzzle {
    /** CHEST_POSITIONS: CHESTS[i] belongs to the weirdo at WEIRDOS[i]. */
    private static final int[][] CHESTS = {{18, 69, 24}, {16, 69, 25}, {14, 69, 24}};
    private static final int[][] WEIRDOS = {{17, 69, 24}, {15, 69, 25}, {13, 69, 24}};
    private static final double FACE_PLAYER_RANGE = 8.0;

    private static final String[] NAMES = {
        "§cArdis", "§cBaxter", "§cBenson", "§cCarver", "§cElmo", "§cEveleth", "§cHope", "§cHugo",
        "§cLino", "§cLuverne", "§cMadelia", "§cMarshall", "§cMelrose", "§cMontgomery", "§cMorris",
        "§cRamsey", "§cRose", "§cVictoria", "§cVirginia", "§cWillmar", "§cWinona",
    };

    /** DIALOGUE: per set, roles "1", "2", "3"; role 0's line is always the true one. */
    private static final String[][] DIALOGUE = {
        {
            "§e[NPC] §c1§r: The reward is not in my chest!",
            "§e[NPC] §c2§r: One of us is telling the truth!",
            "§e[NPC] §c3§r: They are both telling the truth. The reward isn't in §c1's§r chest.",
        },
        {
            "§e[NPC] §c1§r: The reward isn't in any of our chests.",
            "§e[NPC] §c2§r: The reward is not in my chest. They are both lying.",
            "§e[NPC] §c3§r: The reward is in my chest!",
        },
        {
            "§e[NPC] §c1§r: My chest doesn't have the reward. We are all telling the truth.",
            "§e[NPC] §c2§r: My chest doesn't have the reward. At least one of the others is telling the truth!",
            "§e[NPC] §c3§r: One of the others is lying!",
        },
        {
            "§e[NPC] §c1§r: My chest has the reward and I'm telling the truth!",
            "§e[NPC] §c2§r: They are both lying, the reward is in my chest!",
            "§e[NPC] §c3§r: They are both telling the truth, the reward is in §c2's§r chest!",
        },
        {
            "§e[NPC] §c1§r: At least one of them is lying, and the reward is not in §c3's§r chest!",
            "§e[NPC] §c2§r: We are all telling the truth!",
            "§e[NPC] §c3§r: §c2§r is telling the truth and the reward is in his chest.",
        },
        {
            "§e[NPC] §c1§r: Both of them are telling the truth. Also, §c2§r has the reward in their chest!",
            "§e[NPC] §c2§r: §c3§r is telling the truth.",
            "§e[NPC] §c3§r: My chest has the reward!",
        },
    };

    /** SKINS: {textures, signature} per physical slot. */
    private static final String[][] SKINS = {
        {
            "eyJ0aW1lc3RhbXAiOjE1ODIxNDYwNjAxMDYsInByb2ZpbGVJZCI6ImEyZjgzNDU5NWM4OTRhMjdhZGQzMDQ5NzE2Y2E5MTBjIiwicHJvZmlsZU5hbWUiOiJiUHVuY2giLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzdiNGM2ZjVkZjMxMzRhZGY0YTdlYWUxMmZlMjJlYjZhYTEwMmI2NzM1MjIxZTdmNTQ3NWM3YmJlYzQyMzdiYjgifX19",
            "rDW4GM5nUP2hvfh9it3pfXgGeaDoa+JEHoOefy5Rwruz2clabGqda1lXt527QWTAWieS4lFcNWnqwJUtzLow83i/kbFZ72MkUTo3c0LC3nFDTtABGijY8KfcIVRp0XHzWdQwG7PXWYt5RvX+RgEdOmd+yhDoq16Cf4d3MhWhuFrSpKJohzvQ3ad/FFXdpSiWmklnsQ2n7ZP1ZRzuWWg4kRdtYEEjE2oodVkQoN8xqtddK+eT/3kz9n/aqPfokAHjWMJDbkqPBLweLVK2+WYkI9c6unHcG/uWKwhw8lwG7oEXLNhtDnipoWqA+TNcP//m8DAF9kA2MeBjO72U2v+UkNIGXZPamy5wSqhoNhyTAmG0MsammQprwfzL/K3PVW5QZxIldAIDMFNn/T6tYH2PtT345A+0gC0xtZUXHjscjlok/dcvYyleHyxK15fPyYtxcmGE59AUjj0Xllv90aEECRHrzC3t+2/gj+nWcDrLPvxX/qbjlTXKyxT/V0vJlMrzfoj8apPHgdj3S3mu3XDog18kfj7iPmoN0X1xllGzgR4SmOqnlCSWKFieYx7wrbN9J1y23itVteto9DiMWKbgc314m6nxGSaiSVSZriMX4lciNv1js7ADkyQ5LX3FuWUe7KRJuHYv/aRSzj70IEHq+/G6I5EHd3WbRJKmM6AMg4Q=",
        },
        {
            "eyJ0aW1lc3RhbXAiOjE1ODIxNDU3Njc3MDEsInByb2ZpbGVJZCI6ImEyZjgzNDU5NWM4OTRhMjdhZGQzMDQ5NzE2Y2E5MTBjIiwicHJvZmlsZU5hbWUiOiJiUHVuY2giLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzE0Nzk0Yjc3N2Y5YmIzNDhjNTBiMTlhNTAwZDk2ODkwNGM1NzAwNTRlZGMxNzhhNTIwYzRlMWEyZGEwZDY1ODcifX19",
            "k+o7OResg2zOWnG/s2kdEWgEytoj4sVcaFHU0oiLkhwZfxp9NpEeGdTC3A8PdiGiLGAMGJnmBjgXPY0iyJJbfpe+f8FLb/F0FpFPT8P6t7Et9t/jep8ZtAKRQF8kKkgTvDljycSSQQod/DXHOuX/9LCb0UytSc8FFeaTKStTqbGpAOA9Wgb3cF9Mg5DmKC3RJwOeMw3G1nnWrJuG8W+6Uf7oR8f4+M6DBxay3GDNRNBVRQfFxovd/3T0f9VBCenn5ednB+T4t5h8pEssUVmgGiHLwJONxfQolzQkgb7sVC8I9oi74JDwAg3k4rKFb64YTbVKsvbmqb+sqfrcbZUQhPLR9BgNKlWr/A3SokplfjraK7+m90B/vM4jZiFnexxpW1TwNGZkrDrkozNckYRtTK2j9PmOwUgscrRMl4pMNMe7bPDpb1w8PeAzXMSTYzcvVQDK4rFuGCviwWq5JhQnbI+sFTHCJtxixH1AQSWTnLIqUqcKNtQsVYgoN3AGpaj3v5cqoGHh2WPjo1vOMNQN1VjCpNGMiNEJhf9xxfigh4bdC4thH4NMBKavXeMedJ5M9azmBo30b9u0YT3nYbqrx82D4HxagmKeb2+j2O3StdGWk1VUUfxUpwQ4mR9nMKfN1k2JYewog3uxGcJ9FGAcOflvQqBwSIVrMnZgtHFpuQ0=",
        },
        {
            "eyJ0aW1lc3RhbXAiOjE1ODIwNTY1MjY1ODksInByb2ZpbGVJZCI6ImEyZjgzNDU5NWM4OTRhMjdhZGQzMDQ5NzE2Y2E5MTBjIiwicHJvZmlsZU5hbWUiOiJiUHVuY2giLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzYxOTBhN2IyYmIxM2FlNTgyM2Y2YTE4NDZmODQyYzM0ODllMjYyMGEzNjY1NTc0YTBmYmE5NzVjMzk0MTA5MjIifX19",
            "D/AAFPkwp3dqEx8OktDtqX0PSwJfu6PS+u67e8mq+0FMz+yqvDhD4FmzlvJlz6dwVa+UWGBCX6CMPXbPja9eeR90GFEYU+AYInam8IvyrmDzw7q0Fx3jzP9aRmHSn4229Y8GXhkOJ37k3pWf5zrcIJmT9npIq4lwEc3B0OxEZtQadanWX0/qIr/bpbrB+en2zIWzzwQWIAXPJUwQgiVj7mRwfMCyajoOqGs0AApzTi5IPresYF2BZZ9pLWyLv96YFhm96ncMHVJlSl3h8mt0R1pGi2BwOROYIfFq6HDONpSfD3R7aaty0fyPeV9kcrswndCS5/ubZxvv1bLp2wqhR0A5NzWr2GM3GK7o2EQgM5o9gsKS65SGPaWF0h3dUzsrpCOSMKzxzj29eAP4TLsLxWNAyaR/Q4NQt8cluiisLSk2yKUDovzUiSqrdLToD+5DPFqNYxDRraCc2gQQlsHpp3aXHNpqoBYcczTXwUHgjqh71HodzXGo4pxOcJNo5kOjV+uyfgvR9zCIuoN3j7UK6E2F3LQrLDTTRb8W4KGkMDnEESv8jGrXwQs4WzqUdP4HHXOFHbp2cx0Pi7xc93MQW1ZmjNtDeLr+xvsqDbL2syI8D2mf2++vjLhPSzxqtx4hOcyWQFCMC9Sdzb/bDg8JAuXgQSAMgmp1E1Oz/msZJ1w=",
        },
    };

    /** FINISHED_DIALOGUE: what a weirdo says once the puzzle is over; "name" is his own name. */
    private static final String[] FINISHED_DIALOGUE = {
        "§e[NPC] §cname§f: You're free to leave.§7",
        "§e[NPC] §cname§f: You can leave now! Bye!§7",
        "§e[NPC] §cname§f: Thanks for playing! Now get out!§7",
        "§e[NPC] §cname§f: Scram!§7",
    };

    private final BlockPos[] chests = new BlockPos[3];
    private final String[] names = new String[3];
    private final String[] dialogues = new String[3];
    private final Mannequin[] weirdos = new Mannequin[3];
    /** Each weirdo's CLICK label, by slot (clicking it talks to him too). */
    private final ArmorStand[] labels = new ArmorStand[3];
    private int correctIndex;
    private boolean spawned;
    /** Some chest was opened: solved or failed, the puzzle is over. */
    private boolean resolved;
    /** clicked_weirdos: per player, which weirdos (by slot) they have talked to. */
    private final Map<UUID, boolean[]> clicked = new HashMap<>();
    /** One right click reaches us as two use events; the tick of each player's last talk. */
    private final Map<UUID, Long> lastTalk = new HashMap<>();
    private long ticks;

    ThreeWeirdosPuzzle(DungeonRun run, RoomState room) {
        super(run, room);
        for (int i = 0; i < 3; i++) {
            chests[i] = world(CHESTS[i][0], CHESTS[i][1], CHESTS[i][2]);
        }
    }

    /** setup: picks the names and statements and spawns the three weirdos. */
    @Override
    void entered() {
        if (spawned) {
            return;
        }
        spawned = true;
        List<String> pool = new ArrayList<>(Arrays.asList(NAMES));
        Collections.shuffle(pool, random);
        String[] chosen = {pool.get(0), pool.get(1), pool.get(2)};
        String[] lines = DIALOGUE[random.nextInt(DIALOGUE.length)];
        List<Integer> roles = new ArrayList<>(List.of(0, 1, 2));
        Collections.shuffle(roles, random);
        for (int slot = 0; slot < 3; slot++) {
            int role = roles.get(slot);
            names[slot] = chosen[role];
            dialogues[slot] = lines[role].replace("1", chosen[0]).replace("2", chosen[1]).replace("3", chosen[2]);
            if (role == 0) {
                correctIndex = slot;
            }
        }

        float yaw = turnYaw(180f);
        for (int i = 0; i < 3; i++) {
            BlockPos pos = world(WEIRDOS[i][0], WEIRDOS[i][1], WEIRDOS[i][2]);
            String plain = names[i].replaceAll("§.", "");
            Mannequin npc = new Mannequin(EntityTypes.MANNEQUIN, level);
            ((MannequinAccessor) npc).rustclear$setImmovable(true);
            npc.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, yaw, 0f);
            npc.setYHeadRot(yaw);
            npc.setYBodyRot(yaw);
            npc.setComponent(DataComponents.PROFILE, ResolvableProfile.createResolved(
                EngineItems.profile(plain.length() > 16 ? plain.substring(0, 16) : plain, SKINS[i][0], SKINS[i][1])));
            npc.setCustomName(EngineItems.text(names[i]));
            npc.setCustomNameVisible(true);
            npc.setPermanentlyInvulnerable(true);
            npc.setNoGravity(true);
            npc.setSilent(true);
            weirdos[i] = track(npc);

            ArmorStand label = new ArmorStand(EntityTypes.ARMOR_STAND, level);
            label.snapTo(pos.getX() + 0.5, pos.getY() + 0.3, pos.getZ() + 0.5, 0f, 0f);
            label.setInvisible(true);
            label.setNoGravity(true);
            label.setPermanentlyInvulnerable(true);
            label.setCustomName(EngineItems.text("§e§lCLICK"));
            label.setCustomNameVisible(true);
            labels[i] = track(label);
        }
    }

    /** WeirdoImpl::tick: each weirdo looks at the nearest player within 8 blocks. */
    @Override
    void tick() {
        ticks++;
        if (!spawned) {
            return;
        }
        for (Mannequin npc : weirdos) {
            ServerPlayer nearest = null;
            double best = FACE_PLAYER_RANGE * FACE_PLAYER_RANGE;
            for (ServerPlayer player : level.players()) {
                double d = player.distanceToSqr(npc);
                if (d <= best) {
                    best = d;
                    nearest = player;
                }
            }
            if (nearest != null) {
                float yaw = (float) (Math.toDegrees(Math.atan2(nearest.getZ() - npc.getZ(), nearest.getX() - npc.getX())) - 90.0);
                npc.setYRot(yaw);
                npc.setYHeadRot(yaw);
                npc.setYBodyRot(yaw);
            }
        }
    }

    @Override
    boolean useEntity(ServerPlayer player, Entity entity) {
        return talk(player, entity);
    }

    @Override
    boolean attackEntity(ServerPlayer player, Entity entity) {
        return talk(player, entity);
    }

    /** WeirdoImpl::interact: a right or left click on a weirdo (or his label) talks to him. */
    private boolean talk(ServerPlayer player, Entity entity) {
        int index = -1;
        for (int i = 0; i < 3; i++) {
            if (entity == weirdos[i] || entity == labels[i]) {
                index = i;
            }
        }
        if (index < 0) {
            return false;
        }
        Long last = lastTalk.put(player.getUUID(), ticks);
        if (last != null && last == ticks) {
            return true;
        }
        clicked.computeIfAbsent(player.getUUID(), id -> new boolean[3])[index] = true;
        if (!resolved) {
            player.sendSystemMessage(EngineItems.text(dialogues[index]));
        } else {
            String line = FINISHED_DIALOGUE[random.nextInt(FINISHED_DIALOGUE.length)];
            player.sendSystemMessage(EngineItems.text(line.replace("name", names[index])));
        }
        Mannequin npc = weirdos[index];
        level.playSound(null, npc.getX(), npc.getY(), npc.getZ(), SoundEvents.DONKEY_HURT, SoundSource.NEUTRAL, 1f, 0.5f);
        // The tell: talking to the one with the reward puffs a flame above his chest.
        if (index == correctIndex) {
            BlockPos chest = chests[index];
            level.sendParticles(ParticleTypes.FLAME, chest.getX() + 0.5, chest.getY() + 1.0, chest.getZ() + 0.5, 1, 0, 0, 0, 0);
        }
        return true;
    }

    /** interact_chest. */
    @Override
    boolean useBlock(ServerPlayer player, BlockPos pos) {
        int index = chestIndex(pos);
        if (index < 0) {
            return false;
        }
        if (!spawned || resolved) {
            return true;
        }
        boolean[] talked = clicked.get(player.getUUID());
        if (talked == null || !talked[0] || !talked[1] || !talked[2]) {
            player.sendSystemMessage(EngineItems.text("§ctalk to us"));
            return true;
        }
        resolved = true;
        level.blockEvent(pos, level.getBlockState(pos).getBlock(), 1, 1);
        if (index == correctIndex) {
            broadcast("§a§lPUZZLE SOLVED! " + DungeonRun.nameColour(player) + " §ewasn't fooled by §c" + names[index] + "§e! " + GOOD_JOB);
            // The right chest holds a Blessing V, granted 24 ticks later (Blessings).
            Pickup.Kind kind = Blessings.puzzleKind(random);
            schedule(Blessings.CHEST_DELAY + 1, () -> Blessings.grant(run, kind, 5, player, -1));
            finish(false);
            return true;
        }
        player.sendSystemMessage(EngineItems.text("§e[NPC] §c" + names[index] + "§f: You fool!"));
        broadcast("§c§lPUZZLE FAIL! " + DungeonRun.nameColour(player) + " §ewas fooled by §c" + names[index] + "§e! " + YIKES);
        level.playSound(null, pos.getX(), pos.getY(), pos.getZ(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 1f, 1f);
        // All three chests go on a fail, not just the wrong one.
        for (BlockPos chest : chests) {
            setBlock(chest, Blocks.AIR.defaultBlockState());
        }
        room.failedBy = player.getName().getString();
        finish(true);
        return true;
    }



    @Override
    boolean isInteractable(BlockPos pos) {
        return chestIndex(pos) >= 0 && !(resolved && room.puzzleFailed);
    }

    private int chestIndex(BlockPos pos) {
        for (int i = 0; i < 3; i++) {
            if (chests[i].equals(pos)) {
                return i;
            }
        }
        return -1;
    }
}
