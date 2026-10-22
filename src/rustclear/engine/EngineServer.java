package net.rustclear.mod.engine;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.rustclear.mod.DungeonSettings;
import net.rustclear.mod.Floor;
import net.rustclear.mod.Protocol;
import net.rustclear.mod.RustclearMod;

/**
 * The engine's server side: Minecraft's own singleplayer server, while it runs the Rustclear
 * world, and only then. Every hook returns at once for any other server or world.
 */
public final class EngineServer {
    private static volatile MinecraftServer server;
    /** The party's dungeon, in the overworld. */
    private static Instance partyInstance;
    /** The 1v1 or 2v2 being raced, each team in its own dimension, or null. */
    private static Duels duels;
    /** Duel teams matchmaking asked for (player name to "A" or "B"), for premade duos. */
    private static final Map<String, String> preferredTeams = new HashMap<>();
    /** A matchmade duel the next world opens for, or null. */
    private static volatile Match pendingMatch;
    /** This world is a matchmade duel: it starts once everyone matched is in, without readying. */
    private static boolean autoStartDuel;
    /** The server tick the matchmade party became full, or -1. */
    private static int fullSince = -1;

    /**
     * A duel the lobby matched: its mode ("DUEL_1V1" or "DUEL_2V2"), the score it races to, the
     * other players, and teams.
     */
    public record Match(String mode, int target, List<String> guests, Map<String, String> teams) {}

    /** How many players the host's server lets in: the most a party can be set to (Party.refusal does the rest). */
    public static final int MAX_PLAYERS = Party.MAX_SIZE;
    /** Everyone in this world and who leads them; null while no engine world runs. */
    private static Party party;
    /** Whether the next world opens as a public party (anyone may join) rather than a private one. */
    private static volatile boolean openOnStart;
    /**
     * What the next dungeon must look like (the party leader's settings screen, through
     * EngineProtocol). Only the dungeon fields: speed is each player's own ([speeds]).
     */
    private static DungeonSettings settings = new DungeonSettings();
    /** Each player's speed setting (unset: the default), from their own settings; only the Rust server plays it. */
    private static final Map<UUID, Integer> speeds = new HashMap<>();
    /** Whether practice rooms spawn their mobs: the leader's choice, as practice rebuilds everyone's dungeon. */
    private static boolean practiceMobs;
    private static final Random RANDOM = new Random();
    /** The seed of the last dungeon newDungeon built, and the one the next must use (null: a fresh one). */
    static volatile long lastSeed;
    /** Most block hits a guest may send in one tick (a held click sends one per tick). */
    private static final int MAX_HITS_PER_TICK = 4;
    /** This tick's hits per guest: the tick, then the blocks hit (BlockPos longs). */
    private static final Map<UUID, LongArrayList> hitsThisTick = new HashMap<>();
    private static final Map<UUID, Integer> hitTick = new HashMap<>();

    private EngineServer() {
    }

    /** A left click on a block in the Rustclear world (server thread): puzzles, Superboom, then the Dungeonbreaker. */
    private static BlockPos lastHit;
    private static long lastHitTick = Long.MIN_VALUE;

    /**
     * A block hit on the client (EngineBlockHitMixin). Adventure mode gives up on a hit before
     * Fabric's AttackBlockCallback fires, so the Dungeonbreaker, Superboom and lever hits never
     * arrived; both sides run in this game, so the hit goes straight to the server thread instead.
     * The client calls this on the press and again every tick the button is held: each block is hit
     * once per press. True when it was the Rustclear world's (vanilla mining never starts there).
     */
    public static boolean clientHit(BlockPos pos) {
        if (!Engine.isEngineClient()) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return true;
        }
        BlockPos at = pos.immutable();
        long now = mc.level.getGameTime();
        boolean again = at.equals(lastHit) && now - lastHitTick <= 1;
        lastHit = at;
        lastHitTick = now;
        if (again) {
            return true;
        }
        MinecraftServer s = server;
        if (s == null) {
            // A guest: the host's server gets it as a rustclear:hit, checked there (onGuestHit).
            ClientPlayNetworking.send(Protocol.Hit.of(at.getX(), at.getY(), at.getZ()));
            return true;
        }
        UUID id = mc.player.getUUID();
        s.execute(() -> {
            ServerPlayer sp = s.getPlayerList().getPlayer(id);
            if (sp != null) {
                hitBlock(sp, at);
            }
        });
        return true;
    }

    /**
     * A guest's rustclear:hit (server thread). Taken only from a player in the engine world, within
     * their normal reach, once per block per tick and a few blocks per tick at most.
     */
    static void onGuestHit(ServerPlayer sp, BlockPos pos) {
        if (runOf(sp) == null || !sp.isWithinBlockInteractionRange(pos, 1.0)) {
            return;
        }
        int now = sp.level().getServer().getTickCount();
        UUID id = sp.getUUID();
        LongArrayList hits = hitsThisTick.computeIfAbsent(id, k -> new LongArrayList());
        if (!Integer.valueOf(now).equals(hitTick.put(id, now))) {
            hits.clear();
        }
        long key = pos.asLong();
        if (hits.size() >= MAX_HITS_PER_TICK || hits.contains(key)) {
            return;
        }
        hits.add(key);
        hitBlock(sp, pos);
    }

    /** A block hit, for the run the player is in: puzzles, Superboom, then the Dungeonbreaker. */
    static void hitBlock(ServerPlayer sp, BlockPos pos) {
        EngineRun run = runOf(sp);
        if (run != null) {
            run.onHitBlock(sp, pos);
        }
    }

    /**
     * A left click on the client (EngineLeftClickMixin), air included. Only clicks holding something
     * that shoots on left click are passed on: straight to the server thread for the host, as a
     * rustclear:action for a guest.
     */
    public static void clientLeftClick() {
        if (!Engine.isEngineClient()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !GearSystem.leftClickItem(mc.player.getMainHandItem())
            && !ClassAbilities.beamItem(mc.player.getMainHandItem())
            && mc.player.getInventory().getSelectedSlot() != Kit.MAP_SLOT) {
            return;
        }
        MinecraftServer s = server;
        if (s == null) {
            ClientPlayNetworking.send(Protocol.Action.leftClick());
            return;
        }
        UUID id = mc.player.getUUID();
        s.execute(() -> {
            ServerPlayer sp = s.getPlayerList().getPlayer(id);
            if (sp != null) {
                onLeftClick(sp);
            }
        });
    }

    /**
     * The drop key on this client (EngineDropMixin): in the Rustclear world it drops nothing and
     * is the class ability (Ctrl+Q, [fullStack]) or ultimate (Q). True: the drop is cancelled.
     */
    public static boolean clientDrop(boolean fullStack) {
        if (!Engine.isEngineClient()) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return false;
        }
        // Ctrl+drop ("drop a stack") is the regular ability. Vanilla's flag misses Ctrl on some
        // keyboards, so SDL's modifier state is asked too.
        boolean ctrl = ctrlHeld();
        boolean ultimate = !(fullStack || ctrl);
        RustclearMod.LOGGER.info("Rustclear drop key: stack {}, Ctrl {}: {}", fullStack, ctrl, ultimate ? "ultimate" : "class ability");
        MinecraftServer s = server;
        if (s == null) {
            ClientPlayNetworking.send(Protocol.Action.classAbility(ultimate));
            return true;
        }
        UUID id = mc.player.getUUID();
        s.execute(() -> {
            ServerPlayer sp = s.getPlayerList().getPlayer(id);
            if (sp != null) {
                onClassAbility(sp, ultimate);
            }
        });
        return true;
    }

    /** Whether either Ctrl key is down, by SDL's modifier state or its key state. */
    private static boolean ctrlHeld() {
        try {
            if ((org.lwjgl.sdl.SDLKeyboard.SDL_GetModState() & org.lwjgl.sdl.SDLKeycode.SDL_KMOD_CTRL) != 0) {
                return true;
            }
        } catch (Throwable e) {
            RustclearMod.LOGGER.debug("No SDL modifier state: {}", e.toString());
        }
        return com.mojang.blaze3d.platform.InputConstants.isKeyDown(com.mojang.blaze3d.platform.InputConstants.KEY_LCONTROL)
            || com.mojang.blaze3d.platform.InputConstants.isKeyDown(com.mojang.blaze3d.platform.InputConstants.KEY_RCONTROL);
    }

    /** The class ability or ultimate, in the dungeon the player is in (server thread); on the 9th slot, the SkyBlock Menu. */
    static void onClassAbility(ServerPlayer sp, boolean ultimate) {
        if (sp.getInventory().getSelectedSlot() == Kit.MAP_SLOT) {
            SkyblockMenu.open(sp);
            return;
        }
        if (runOf(sp) instanceof DungeonRun dungeon) {
            dungeon.abilities().use(sp, ultimate);
        }
    }

    /** A left click, for the run the player is in (server thread); on the 9th slot, the SkyBlock Menu. */
    static void onLeftClick(ServerPlayer sp) {
        if (sp.getInventory().getSelectedSlot() == Kit.MAP_SLOT) {
            SkyblockMenu.open(sp);
            return;
        }
        EngineRun run = runOf(sp);
        if (run != null) {
            run.onLeftClick(sp);
        }
    }

    /**
     * A click in any window (EngineMenuSlotMixin): in the Rustclear world one on the 9th hotbar slot,
     * or a number-key swap with it, or a throw of it, does nothing to it; on the server it opens the
     * SkyBlock Menu. True: the click is cancelled.
     */
    public static boolean lockedClick(AbstractContainerMenu menu, int slot, int button, ContainerInput input, Player who) {
        boolean engine = who instanceof ServerPlayer sp ? isEngineLevel(sp.level())
            : who.level().isClientSide() && Engine.isEngineClient();
        if (!engine) {
            return false;
        }
        boolean onSlot = slot >= 0 && slot < menu.slots.size() && menu.slots.get(slot).container == who.getInventory()
            && menu.slots.get(slot).getContainerSlot() == Kit.MAP_SLOT;
        boolean swap = input == ContainerInput.SWAP && button == Kit.MAP_SLOT;
        if (!onSlot && !swap) {
            return false;
        }
        if (who instanceof ServerPlayer sp) {
            menu.sendAllDataToRemote();
            sp.inventoryMenu.sendAllDataToRemote();
            if (onSlot) {
                sp.level().getServer().execute(() -> SkyblockMenu.open(sp));
            }
        }
        return true;
    }

    /** The offhand swap key (any thread): with the 9th slot held in the Rustclear world, nothing moves and the SkyBlock Menu opens. */
    public static boolean lockedSwap(ServerPlayer sp) {
        MinecraftServer s = server;
        if (s == null || sp.level().getServer() != s || sp.getInventory().getSelectedSlot() != Kit.MAP_SLOT) {
            return false;
        }
        s.execute(() -> {
            sp.inventoryMenu.sendAllDataToRemote();
            SkyblockMenu.open(sp);
        });
        return true;
    }

    /** A player's arrow reached [entity] (EngineArrowHitMixin). True when the run took the hit. */
    public static boolean arrowHit(AbstractArrow arrow, Entity entity) {
        if (!isEngineLevel(arrow.level())) {
            return false;
        }
        Instance instance = instanceAt(arrow.level());
        EngineRun run = instance == null ? null : instance.run();
        return run != null && run.onArrowHit(arrow, entity);
    }

    /** A drawn bow let go (EngineBowMixin). True when the run shot it. */
    public static boolean bowRelease(ItemStack bow, Level level, LivingEntity entity, int ticksDrawn) {
        if (!(entity instanceof ServerPlayer sp) || !isEngineLevel(level)) {
            return false;
        }
        EngineRun run = runOf(sp);
        return run != null && run.onBowRelease(sp, bow, ticksDrawn);
    }

    /** The endless arrow a dungeon bow draws in the Rustclear world (EngineProjectileMixin), or null. */
    public static ItemStack infiniteArrow(Player player, ItemStack weapon) {
        if (!(weapon.getItem() instanceof BowItem) || DataItems.of(weapon) == null) {
            return null;
        }
        Level level = player.level();
        boolean engine = level.isClientSide() ? Engine.isEngineClient() : isEngineLevel(level);
        return engine ? new ItemStack(Items.ARROW) : null;
    }

    // ---------------------------------------------------------------- instances

    /** The instance whose level is [level] (the party's or a duel team's), or null. */
    static Instance instanceAt(Level level) {
        if (!isEngineLevel(level)) {
            return null;
        }
        if (partyInstance != null && partyInstance.level == level) {
            return partyInstance;
        }
        return duels == null ? null : duels.instanceAt((ServerLevel) level);
    }

    /** The run in [player]'s level, or null. Every hook goes through this, so each instance answers only its own players. */
    static EngineRun runOf(ServerPlayer player) {
        Instance instance = instanceAt(player.level());
        return instance == null ? null : instance.run();
    }

    /** Where [player] belongs: their duel team's instance while a duel runs, else the party's. */
    private static Instance home(ServerPlayer player) {
        Instance team = duels == null ? null : duels.instanceOf(player);
        return team != null ? team : partyInstance;
    }

    /** Hands [player] their starting items again now (a new kit arrived), if they are at a run. */
    static void reequip(ServerPlayer player) {
        Instance home = home(player);
        if (home != null && home.run() != null) {
            home.run().equip(player);
        }
    }

    /** The dungeon map a run in [level] draws, for map id 1024 there (EngineMapMixin), or null. */
    public static MapItemSavedData dungeonMap(Level level, MapId id) {
        if (id.id() != 1024) {
            return null;
        }
        Instance instance = instanceAt(level);
        DungeonRun dungeon = instance == null ? null : instance.dungeon();
        return dungeon == null ? null : dungeon.mapData();
    }

    static Duels duels() {
        return duels;
    }

    /** The running engine server, or null. */
    public static MinecraftServer server() {
        return server;
    }

    /** Is [level] the engine's (server side)? */
    public static boolean isEngineLevel(Level level) {
        MinecraftServer s = server;
        return s != null && level instanceof ServerLevel serverLevel && serverLevel.getServer() == s;
    }

    private static boolean isEngine(MinecraftServer s) {
        return s instanceof IntegratedServer && EngineWorld.NAME.equals(s.getWorldData().getLevelName())
            && (EngineWorld.opening || EngineWorld.isMarked(s.getWorldPath(LevelResource.ROOT)));
    }

    /** The world's dungeon settings (no speed: that is each player's, [speed]). */
    public static DungeonSettings settings() {
        return settings;
    }

    /** The practice-room mobs toggle from the leader's Dungeon settings. */
    static boolean practiceMobs() {
        return practiceMobs;
    }

    static void setPracticeMobs(boolean mobs) {
        practiceMobs = mobs;
    }

    /** Sets the world's dungeon settings; anything in [next] that is each player's own (speed, profile) is dropped. */
    static void setSettings(DungeonSettings next) {
        DungeonSettings dungeon = next.copy();
        dungeon.speed = null;
        dungeon.practiceMobs = null;
        dungeon.hypixel = null;
        dungeon.simLog = null;
        settings = dungeon;
    }

    /** The speed setting [id] sent, for the Rust server only: in the Rustclear world speed comes from stats (PlayerStats). */
    static int speed(UUID id) {
        Integer value = speeds.get(id);
        return value == null ? DungeonSettings.DEFAULT_SPEED : DungeonSettings.clampSpeed(value);
    }

    /** Keeps [player]'s class and Hypixel profile from their settings. */
    static void setStats(ServerPlayer player, DungeonSettings from) {
        PlayerStats.receive(player, from);
    }

    /** Back to [player]'s run's start after dying, as a respawn does. */
    static void respawn(ServerPlayer player) {
        Instance home = home(player);
        if (home != null) {
            home.toSpawn(player);
            PlayerSpeed.apply(player);
        }
    }

    /** Sets [player]'s own speed (null: the default) and applies it to them alone. */
    static void setSpeed(ServerPlayer player, Integer value) {
        if (value == null) {
            speeds.remove(player.getUUID());
        } else {
            speeds.put(player.getUUID(), DungeonSettings.clampSpeed(value));
        }
        PlayerSpeed.apply(player);
    }

    /**
     * Does [player] lead this world's party? Only the leader changes the dungeon (settings,
     * New dungeon, practice); everyone else's settings change only their own speed.
     */
    static boolean isLeader(ServerPlayer player) {
        Party p = party;
        return p != null ? p.isLeader(player) : isHost(player);
    }

    /** Is [player] the one whose game runs this world? */
    static boolean isHost(ServerPlayer player) {
        MinecraftServer s = server;
        return s != null && s.isSingleplayerOwner(player.nameAndId());
    }

    /** The Rustclear menu's Public / Private choice, for the world about to open. */
    public static void openOnStart(boolean open) {
        openOnStart = open;
    }

    static Party party() {
        return party;
    }

    /** The party's run (at Mort, or in the dungeon), or null. */
    static EngineRun run() {
        return partyInstance == null ? null : partyInstance.run();
    }

    /** The host's own player id, or null. */
    private static UUID hostId(MinecraftServer s) {
        return s.getSingleplayerProfile() == null ? null : s.getSingleplayerProfile().id();
    }

    /** The players in the party's dungeon (not those racing in a duel). */
    static List<ServerPlayer> players() {
        return partyInstance == null ? List.of() : partyInstance.players();
    }

    /** The party dungeon's total secrets, or null before one is built. */
    static Integer totalSecrets() {
        DungeonPlan plan = partyInstance == null ? null : partyInstance.plan();
        return plan == null ? null : plan.rooms().stream().mapToInt(r -> r.data().secrets()).sum();
    }

    public static void register() {
        EngineProtocol.register();
        ServerLifecycleEvents.SERVER_STARTING.register(s -> {
            if (isEngine(s)) {
                server = s;
            }
            EngineWorld.opening = false;
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents.LOAD.register((s, level) -> {
            if (s == server && level == s.overworld()) {
                // The dungeon's chunks load with the world, alongside vanilla's spawn area, rather
                // than one by one in the first build (~180 ms there). DRAGON: no timeout, not saved.
                level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.DRAGON,
                    new net.minecraft.world.level.ChunkPos(DungeonBuilder.FOOTPRINT_CENTER_CHUNK, DungeonBuilder.FOOTPRINT_CENTER_CHUNK),
                    DungeonBuilder.FOOTPRINT_CHUNK_RADIUS);
            }
        });
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            if (s != server) {
                return;
            }
            EngineWorld.mark(s.getWorldPath(LevelResource.ROOT));
            SimLog.start(s, hostId(s));
            party = new Party(s, hostId(s));
            party.setOpen(openOnStart);
            partyInstance = new Instance(s.overworld(), null);
            Match match = pendingMatch;
            pendingMatch = null;
            preferredTeams.clear();
            autoStartDuel = match != null;
            fullSince = -1;
            if (match != null) {
                // Matched players only, in the mode and teams the lobby picked.
                Party.Mode mode = Party.Mode.parse(match.mode());
                party.setMode(mode == null || !mode.duel() ? Party.Mode.DUEL_1V1 : mode);
                party.setTarget(match.target());
                match.guests().forEach(party::invite);
                preferredTeams.putAll(match.teams());
            }
            rules(s);
            commands(s);
            try {
                newDungeon(s);
            } catch (Planner.PlanException e) {
                // Saved settings no dungeon meets: start with a random one instead.
                settings = new DungeonSettings();
                newDungeonOrLog(s);
            }
        });
        // The host leaving ends the party: guests are told so before the server lets them go, and
        // their games show it as the end of the party rather than a lost connection.
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            if (s == server && party != null) {
                party.close("The host ended the party.");
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
            if (s == server) {
                SimLog.stop();
                server = null;
                party = null;
                partyInstance = null;
                duels = null;
                preferredTeams.clear();
                speeds.clear();
                PlayerStats.clear();
                Equipment.clear();
                Pets.clear();
                practiceMobs = false;
                hitsThisTick.clear();
                hitTick.clear();
                EngineProtocol.reset();
                KitItems.clear();
                SkyblockData.clear();
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
            if (s == server) {
                UUID id = handler.player.getUUID();
                speeds.remove(id);
                PlayerStats.forget(id);
                Equipment.forget(id);
                Pets.forget(id);
                hitsThisTick.remove(id);
                hitTick.remove(id);
                HotbarLayout.forget(id);
                KitItems.forget(id);
                SkyblockData.forget(id);
                EngineProtocol.forget(id);
                if (party != null && id.equals(party.leader()) && !isHost(handler.player)) {
                    // A guest who led has gone: the host leads again.
                    party.leaderLeft(hostId(s));
                    party.broadcast("§e" + handler.player.getName().getString() + " §eleft; the host leads the party again.");
                }
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
            if (s == server) {
                ServerPlayer player = handler.player;
                if (!isLeader(player) && !ServerPlayNetworking.canSend(player, Protocol.Hello.TYPE)) {
                    // Without the mod a guest can't hit, practice or see the HUD right: say why instead.
                    player.connection.disconnect(Component.literal("This is a Rustclear party. Install the Rustclear mod "
                        + RustclearMod.version() + " to join."));
                    return;
                }
                if (party != null && party.leader() == null && isHost(player)) {
                    party.leaderLeft(player.getUUID());
                }
                String refusal = party == null ? null : party.refusal(player, isHost(player));
                if (refusal != null) {
                    player.connection.disconnect(Component.literal(refusal));
                    return;
                }
                if (party != null && !isHost(player)) {
                    party.broadcast("§e" + player.getName().getString() + " §ajoined the party.");
                }
                // Adventure: blocks can be used (doors, chests, levers) but not broken.
                player.setGameMode(GameType.ADVENTURE);
                PlayerSpeed.apply(player);
                Instance home = home(player);
                home.toSpawn(player);
                if (home.run() != null) {
                    home.run().equip(player);
                }
                EngineProtocol.sendHello(player);
            }
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (newPlayer.level().getServer() == server && partyInstance != null) {
                PlayerSpeed.apply(newPlayer);
                Instance home = home(newPlayer);
                home.toSpawn(newPlayer);
                if (home.run() != null) {
                    home.run().equip(newPlayer);
                }
            }
        });
        // The scripted test acts at the start of a tick, where a real player's packets land.
        ServerTickEvents.START_SERVER_TICK.register(s -> {
            if (s == server && partyInstance != null) {
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (s != server) {
                return;
            }
            ChestGui.tick();
            if (partyInstance == null) {
                return;
            }
            EngineRun run = partyInstance.run();
            if (run != null) {
                run.tick();
            }
            // Speed follows the stats (gear, held item, Ragnarok) as they change.
            for (ServerPlayer player : s.getPlayerList().getPlayers()) {
                PlayerSpeed.apply(player);
            }
            if (duels != null) {
                duels.tick();
            } else {
                checkDuelStart(s);
            }
            // What guests see of the party (members, ready, phase, duel progress) twice a second, when it changed.
            if (party != null && s.getTickCount() % 10 == 0) {
                party.sync(run(), duels, false);
            }
        });
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            EngineRun run = player instanceof ServerPlayer sp ? runOf(sp) : null;
            if (run == null || hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            return run.onUseEntity((ServerPlayer) player, entity) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (hand == InteractionHand.MAIN_HAND && player instanceof ServerPlayer sp && isEngineLevel(world)
                && Pets.useItem(sp)) {
                // A pet item (a head) goes in the pet menu rather than on the block.
                return InteractionResult.SUCCESS;
            }
            if (isEngineLevel(world)) {
                // Hoppers, dispensers and droppers are decoration: they never open (either hand), as on Hypixel.
                // Crafting tables and anvils too (the user, 2026-10-19).
                BlockState state = world.getBlockState(hit.getBlockPos());
                if (state.is(Blocks.HOPPER) || state.is(Blocks.DISPENSER) || state.is(Blocks.DROPPER)
                    || state.is(Blocks.CRAFTING_TABLE) || state.is(net.minecraft.tags.BlockTags.ANVIL)) {
                    return InteractionResult.FAIL;
                }
            }
            EngineRun run = player instanceof ServerPlayer sp ? runOf(sp) : null;
            if (run == null || hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            return run.onUseBlock((ServerPlayer) player, hit.getBlockPos(), hit.getDirection())
                ? InteractionResult.SUCCESS : InteractionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClientSide()) {
                // The Terminator, staffs and wands are only ours: no bow draw (which held the button
                // down as one long use, so holding right click shot once), just the use packet.
                return hand == InteractionHand.MAIN_HAND && Engine.isEngineClient()
                    && GearSystem.rightClickItem(player.getMainHandItem()) ? InteractionResult.SUCCESS : InteractionResult.PASS;
            }
            if (hand == InteractionHand.MAIN_HAND && player instanceof ServerPlayer sp && isEngineLevel(world)
                && SkyblockMenu.opensMenu(sp)) {
                // The 9th slot's item (the Magical Map, or the SkyBlock Menu star) opens the SkyBlock Menu.
                SkyblockMenu.open(sp);
                return InteractionResult.SUCCESS;
            }
            if (hand == InteractionHand.MAIN_HAND && player instanceof ServerPlayer sp && isEngineLevel(world)) {
                // A pet item goes in the pet menu; a rod cast is an Autopet trigger (the Pet Rod swap).
                if (Pets.useItem(sp)) {
                    return InteractionResult.SUCCESS;
                }
                if (sp.getMainHandItem().is(Items.FISHING_ROD)) {
                    Pets.trigger(sp, Pets.Trigger.FISHING_HOOK);
                }
            }
            EngineRun run = player instanceof ServerPlayer sp ? runOf(sp) : null;
            if (run == null || hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            return run.onUseItem((ServerPlayer) player) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        });
        // Vanilla damage never reaches players in the Rustclear world: a dungeon mob's arrow becomes
        // its floor damage through Combat instead (melee hits go there from MobAi), and falls,
        // fire and the rest do nothing, as on the server.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayer sp) || sp.level().getServer() != server) {
                return true;
            }
            Instance instance = instanceAt(sp.level());
            DungeonRun dungeon = instance == null ? null : instance.dungeon();
            if (dungeon != null && source.getEntity() != null) {
                MobSystem.Archetype archetype = dungeon.mobs().archetypeOf(source.getEntity());
                if (archetype != null) {
                    dungeon.combat().attackedBy(sp, source.getEntity());
                    dungeon.combat().mobHit(sp, archetype, dungeon.mobs().killerName(source.getEntity()));
                }
            }
            return false;
        });
        EntityTrackingEvents.START_TRACKING.register((entity, player) -> {
            Instance instance = instanceAt(entity.level());
            if (instance != null && instance.run() != null) {
                instance.run().startedTracking(entity, player);
            }
        });
        EntityTrackingEvents.STOP_TRACKING.register((entity, player) -> {
            Instance instance = instanceAt(entity.level());
            if (instance != null && instance.run() != null) {
                instance.run().stoppedTracking(entity, player);
            }
        });
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            EngineRun run = player instanceof ServerPlayer sp ? runOf(sp) : null;
            if (run == null) {
                return InteractionResult.PASS;
            }
            return run.onAttackEntity((ServerPlayer) player, entity) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        });
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            if (world.isClientSide()) {
                // Hits in the Rustclear world are clientHit's (EngineBlockHitMixin); FAIL sends no packet.
                return Engine.isEngineClient() ? InteractionResult.FAIL : InteractionResult.PASS;
            }
            if (!(player instanceof ServerPlayer sp) || runOf(sp) == null) {
                return InteractionResult.PASS;
            }
            // Hits are delivered by the client branch above; never let vanilla break blocks here.
            return InteractionResult.SUCCESS;
        });
    }

    /** Tears down the current dungeon (if any), builds a new random one and sends everyone to its start. */
    private static void newDungeonOrLog(MinecraftServer s) {
        try {
            newDungeon(s);
        } catch (Planner.PlanException e) {
            RustclearMod.LOGGER.error("Couldn't plan a Rustclear dungeon: {}", e.getMessage());
        }
    }

    /** Sends every run's whole sidebar and tab list again (after client mods reset for a new dungeon). */
    static void resendHud() {
        if (partyInstance != null && partyInstance.run() != null) {
            partyInstance.run().resendHud();
        }
        if (duels != null) {
            duels.resendHud();
        }
    }

    /** A new party dungeon that meets the current settings; leaves practice mode. Not during a duel. */
    public static void newDungeon(MinecraftServer s) throws Planner.PlanException {
        if (duels != null) {
            throw new Planner.PlanException("The party is in a duel. Go back to the party first.");
        }
        BuildTimer timer = new BuildTimer();
        RoomStore store = RoomStore.get();
        timer.lap("data");
        // Plan first, so settings no dungeon meets leave the current one standing.
        long seed = RANDOM.nextLong();
        lastSeed = seed;
        Random random = new Random(seed);
        DungeonPlan next = Planner.plan(store, settings, random);
        timer.lap("plan");
        partyInstance.practicePick = null;
        partyInstance.practiceRoute = null;
        partyInstance.install(next, store, null, -1, practiceMobs, random, timer, settings.floorOrDefault(), seed, null, null);
        holdForDuel();
    }

    /** In a duel mode the party's Mort waits for the duel to start everyone ([checkDuelStart]). */
    private static void holdForDuel() {
        EngineRun run = run();
        if (run != null && party != null) {
            run.holdStart(party.mode().duel());
        }
    }

    // ---------------------------------------------------------------- duels

    /**
     * In a duel mode, once the right number of players are all ready at Mort (or the leader
     * pressed Start), the duel begins: one dungeon, a copy per team, one countdown.
     */
    private static void checkDuelStart(MinecraftServer s) {
        EngineRun run = run();
        if (party == null || run == null || !party.mode().duel()) {
            return;
        }
        run.holdStart(true);
        List<ServerPlayer> members = party.members();
        if (autoStartDuel) {
            // A matchmade duel starts by itself five seconds after the last player arrives.
            if (members.size() != party.mode().size()) {
                fullSince = -1;
                return;
            }
            if (fullSince < 0) {
                fullSince = s.getTickCount();
                party.broadcast("§aEveryone is here. The duel starts in 5 seconds.");
            }
            if (s.getTickCount() - fullSince < 100) {
                return;
            }
        } else if (!run.allReady()) {
            return;
        }
        if (members.size() != party.mode().size()) {
            if (s.getTickCount() % 100 == 0) {
                party.broadcast("§cA " + party.mode().label() + " needs exactly "
                    + party.mode().size() + " players; the party has " + members.size() + ".");
            }
            return;
        }
        Duels next = Duels.begin(s, party.mode(), party.target(), members, preferredTeams, settings, RANDOM);
        if (next != null) {
            duels = next;
            autoStartDuel = false;
            // Nobody is in the party's dungeon while they race.
            partyInstance.dispose();
            party.sync(run(), duels, true);
        }
    }

    /** The leader's Rematch: a new dungeon, the same for every team, with the same teams. */
    static void rematch() {
        if (duels != null) {
            duels.rematch(settings, RANDOM);
            party.sync(run(), duels, true);
        }
    }

    /** The leader's Back to party: the duel ends, everyone returns to a new party dungeon. */
    static void endDuel(MinecraftServer s) {
        if (duels == null) {
            return;
        }
        Duels ended = duels;
        duels = null;
        ended.dispose();
        newDungeonOrLog(s);
        for (ServerPlayer player : s.getPlayerList().getPlayers()) {
            partyInstance.toSpawn(player);
            PlayerSpeed.apply(player);
            if (partyInstance.run() != null) {
                partyInstance.run().equip(player);
            }
            HypixelSignals.newDungeon(player, it.unimi.dsi.fastutil.longs.LongSets.EMPTY_SET);
        }
        party.sync(run(), null, true);
    }

    /** The next world opens for this matchmade duel (call before EngineWorld.open). */
    public static void prepareMatch(Match match) {
        pendingMatch = match;
    }

    /**
     * load_practice_room (/practice <room> [door] [secrets]): the named room alone, everyone just
     * inside [door] ("random" or null for any), and the route timer armed for [secrets] (null: all
     * of the room's). Returns the chat reply; throws IllegalArgumentException with the reason.
     */
    static String practice(MinecraftServer s, String roomName, String door, Integer secrets) {
        if (duels != null) {
            throw new IllegalArgumentException("The party is in a duel. Go back to the party first.");
        }
        RoomStore store = RoomStore.get();
        Practice.Pick pick = Practice.find(store, roomName);
        if (pick == null) {
            throw new IllegalArgumentException("Unknown or unsupported practice room: '" + roomName + "'");
        }
        String name = pick.data().name();
        int total = pick.data().secrets();
        if (secrets != null) {
            if (secrets < 1) {
                throw new IllegalArgumentException("Secret count must be at least 1.");
            }
            if (total > 0 && secrets > total) {
                throw new IllegalArgumentException(name + " only has " + total + " secret(s).");
            }
        }
        int target = secrets != null ? secrets : total;
        BuildTimer timer = new BuildTimer();
        Practice.Built built = Practice.build(pick.data());
        timer.lap("plan");
        Practice.DoorSpawn chosen = Practice.chooseDoor(built.spawns(), door, RANDOM);
        if (chosen == null) {
            throw new IllegalArgumentException(name + " has no " + door + " door. Valid doors: " + Practice.validDoors(built.spawns()));
        }
        String prefix = name + " " + Character.toUpperCase(chosen.label().charAt(0)) + target + "/" + total;
        partyInstance.practicePick = pick;
        partyInstance.practiceRoute = new Practice.Route(target, prefix, chosen);
        partyInstance.install(built.plan(), store, partyInstance.practiceRoute, pick.waterPattern(), practiceMobs, RANDOM, timer);
        return "§aPracticing " + name + " from the " + chosen.label() + " door"
            + (target > 0 ? " (" + target + "/" + total + " secrets)." : ".");
    }

    /** restart_practice_room (/rs in practice): the same room rebuilt from scratch, everyone back at the same door, the timer reset. */
    private static void restartPractice(MinecraftServer s) {
        Instance in = partyInstance;
        Practice.Route old = in.practiceRoute;
        BuildTimer timer = new BuildTimer();
        Practice.Built built = Practice.build(in.practicePick.data());
        timer.lap("plan");
        in.practiceRoute = new Practice.Route(old.target, old.prefix, old.spawn);
        in.install(built.plan(), RoomStore.get(), in.practiceRoute, in.practicePick.waterPattern(), practiceMobs, RANDOM, timer);
    }

    private static void commands(MinecraftServer s) {
        HypixelSignals.commands(s);
        ItemCommand.register(s);
        // /rs: a new dungeon, as on the Rustclear server; in practice mode, the practice room reset.
        s.getCommands().getDispatcher().register(Commands.literal("rs").executes(c -> {
            if (duels == null && partyInstance.practiceRoute != null) {
                restartPractice(s);
                return 1;
            }
            try {
                newDungeon(s);
            } catch (Planner.PlanException e) {
                c.getSource().sendSystemMessage(Component.literal("§c" + e.getMessage()));
                return 0;
            }
            c.getSource().sendSystemMessage(Component.literal("§aNew dungeon!"));
            return 1;
        }));

        // engineerClient's Save Hotbar / Default Hotbar (also on the Speed tab of Dungeon settings).
        // Hypixel's /sbmenu, as the 9th slot's item opens it.
        s.getCommands().getDispatcher().register(Commands.literal("sbmenu").executes(c -> {
            SkyblockMenu.open(c.getSource().getPlayerOrException());
            return 1;
        }));
        s.getCommands().getDispatcher().register(Commands.literal("stats").executes(c -> {
            StatsMenu.open(c.getSource().getPlayerOrException());
            return 1;
        }));
        // The SkyBlock Menu's "Also accessible via /..." commands.
        Map<String, java.util.function.Consumer<ServerPlayer>> menus = new java.util.LinkedHashMap<>();
        menus.put("skills", SkillMenus::openSkills);
        menus.put("storage", StorageMenu::open);
        menus.put("bags", BagMenus::openBags);
        menus.put("accessorybag", p -> BagMenus.AccessoryBag.open(p, 1));
        menus.put("pets", PetsMenu::open);
        menus.put("loadouts", p -> LoadoutsMenu.open(p, false));
        menus.put("bank", BagMenus::openBank);
        menus.forEach((name, open) -> s.getCommands().getDispatcher().register(Commands.literal(name).executes(c -> {
            open.accept(c.getSource().getPlayerOrException());
            return 1;
        })));
        s.getCommands().getDispatcher().register(Commands.literal("savehotbar").executes(c -> {
            c.getSource().sendSystemMessage(EngineItems.text(HotbarLayout.save(c.getSource().getPlayerOrException())));
            return 1;
        }));
        s.getCommands().getDispatcher().register(Commands.literal("defaulthotbar").executes(c -> {
            c.getSource().sendSystemMessage(EngineItems.text(HotbarLayout.reset(c.getSource().getPlayerOrException())));
            return 1;
        }));

        // /practice <room> [door] [secrets] (practice.rs; door and secrets optional here, defaulting
        // to a random door and all the room's secrets). /practice off goes back to normal dungeons.
        s.getCommands().getDispatcher().register(Commands.literal("practice")
            .then(Commands.literal("off").executes(c -> {
                try {
                    newDungeon(s);
                } catch (Planner.PlanException e) {
                    c.getSource().sendSystemMessage(Component.literal("§c" + e.getMessage()));
                    return 0;
                }
                c.getSource().sendSystemMessage(Component.literal("§aPractice off. New dungeon!"));
                return 1;
            }))
            .then(RequiredArgumentBuilder.<CommandSourceStack, String>argument("room", StringArgumentType.word())
                .suggests((c, b) -> suggest(Practice.roomNames(RoomStore.get()), b))
                .executes(c -> practiceCommand(s, c, null, null))
                .then(RequiredArgumentBuilder.<CommandSourceStack, String>argument("door", StringArgumentType.word())
                    .suggests((c, b) -> suggest(Practice.DOOR_CHOICES, b))
                    .executes(c -> practiceCommand(s, c, StringArgumentType.getString(c, "door"), null))
                    .then(RequiredArgumentBuilder.<CommandSourceStack, Integer>argument("secrets", IntegerArgumentType.integer(1, 255))
                        .executes(c -> practiceCommand(s, c, StringArgumentType.getString(c, "door"),
                            IntegerArgumentType.getInteger(c, "secrets")))))));
    }

    private static int practiceCommand(MinecraftServer s, CommandContext<CommandSourceStack> c, String door, Integer secrets) {
        try {
            String reply = practice(s, StringArgumentType.getString(c, "room"), door, secrets);
            c.getSource().sendSystemMessage(Component.literal(reply));
            return 1;
        } catch (IllegalArgumentException e) {
            c.getSource().sendSystemMessage(Component.literal("§c" + e.getMessage()));
            return 0;
        }
    }

    private static CompletableFuture<Suggestions> suggest(List<String> options, SuggestionsBuilder builder) {
        String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(typed)) {
                builder.suggest(option);
            }
        }
        return builder.buildFuture();
    }

    private static void rules(MinecraftServer s) {
        GameRules r = s.getGameRules();
        r.set(GameRules.ADVANCE_TIME, false, s);
        r.set(GameRules.ADVANCE_WEATHER, false, s);
        r.set(GameRules.SPAWN_MOBS, false, s);
        r.set(GameRules.SPAWN_MONSTERS, false, s);
        r.set(GameRules.SPAWN_PHANTOMS, false, s);
        r.set(GameRules.SPAWN_PATROLS, false, s);
        r.set(GameRules.SPAWN_WANDERING_TRADERS, false, s);
        r.set(GameRules.RANDOM_TICK_SPEED, 0, s);
        r.set(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0, s);
        r.set(GameRules.KEEP_INVENTORY, true, s);
        r.set(GameRules.IMMEDIATE_RESPAWN, true, s);
        r.set(GameRules.SHOW_DEATH_MESSAGES, false, s);
        r.set(GameRules.SHOW_ADVANCEMENT_MESSAGES, false, s);
        r.set(GameRules.MOB_GRIEFING, false, s);
        r.set(GameRules.BLOCK_DROPS, false, s);
        r.set(GameRules.ENTITY_DROPS, false, s);
        r.set(GameRules.MOB_DROPS, false, s);
        r.set(GameRules.SEND_COMMAND_FEEDBACK, false, s);
        r.set(GameRules.LOCATOR_BAR, false, s);
        r.set(GameRules.RESPAWN_RADIUS, 0, s);
    }
}
