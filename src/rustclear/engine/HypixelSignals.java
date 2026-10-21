package net.rustclear.mod.engine;

import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.commands.Commands;
import net.minecraft.network.PacketListener;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.rustclear.mod.Protocol;
import net.rustclear.mod.RustclearMod;

/**
 * What Hypixel tells a client when it joins a dungeon, so SkyBlock client mods (Skyblocker,
 * Firmament, SkyHanni, anything on the Hypixel Mod API) switch their dungeon features on in the
 * Rustclear world, as Smalldungeons server.rs does on join:
 *
 * - the server brand "Hypixel BungeeCord ..." (Skyblocker's isOnHypixel check),
 * - the Hypixel Mod API hello (hypixel:hello) and location event (hyevent:location: server type
 *   SKYBLOCK, mode "dungeon", map "Dungeon"), which is how those mods decide they are in SkyBlock and
 *   in the Catacombs.
 *
 * Each message is written out in Hypixel's wire format and read back through
 * ClientboundCustomPayloadPacket's own codec, which every installed mod (Fabric API's payload
 * registry, Hypixel's FabricModAPI, hm-api's codec wrapper) has already hooked: the result is
 * whatever those mods expect, and without any of them installed it is an ignored unknown payload.
 * Rustclear registers none of these channels itself, so nothing clashes with the mods that do.
 *
 * The packet is built and handed straight to the client's packet listener rather than sent down the
 * connection: the Hypixel Mod API's payloads can only be read, never written, so encoding one for
 * the connection throws and drops the player. The host's own client shares this JVM and gets them
 * directly; a guest gets a rustclear:signals naming the signal, and its own Rustclear mod builds
 * and delivers the same packets on its side ([onSignal]).
 */
final class HypixelSignals {
    /** Skyblocker looks for "Hypixel BungeeCord" in the brand. */
    private static final String BRAND = "Hypixel BungeeCord (Rustclear)";
    /** A made-up server id in Hypixel's form, so it never matches a real server mods share data about. */
    private static final String SERVER_NAME = "mini0RC";
    /** The SkyBlock profile name the tab list and chat report. */
    static final String PROFILE_NAME = "Rustclear";
    /** ClientboundLocationPacket's version and Hello's environment (0 = PRODUCTION). */
    private static final int LOCATION_VERSION = 1;
    private static final int PRODUCTION = 0;
    /** The answer to /profileid, so mods asking for it get one instead of "Unknown command". */
    private static final UUID PROFILE_ID = UUID.nameUUIDFromBytes("rustclear:profile".getBytes(StandardCharsets.UTF_8));

    private static StreamCodec<Object, Object> codec;
    private static boolean codecLoaded;
    private static boolean warned;

    private HypixelSignals() {
    }

    /** Sent to each player as they join a dungeon (each new dungeon counts as a new "server", as on Hypixel). */
    static void join(ServerPlayer player) {
        if (isHost(player)) {
            onHostClient(player, HypixelSignals::clientJoin);
        } else if (ServerPlayNetworking.canSend(player, Protocol.Signals.TYPE)) {
            ServerPlayNetworking.send(player, Protocol.Signals.of(JOIN));
        }
        // What Hypixel says on every server join; Skyblocker, SkyHanni and SkyblockAPI (SkyOcean)
        // only treat the profile as loaded once they see it. It reaches the client after the
        // location above, which goes straight to the client thread.
        player.sendSystemMessage(Component.literal("§aYou are playing on profile: §e" + PROFILE_NAME));
    }

    /**
     * A rebuilt dungeon is, to client mods, a new server: on Hypixel every dungeon is its own
     * server, and mods only reset their dungeon state (Odin's map and room scan, for one) when
     * they join one. So the client's join event fires again, then the dungeon's chunks are sent
     * again so mods that scan chunks as they load (Odin's WorldScan) see the new rooms. Called
     * on the server thread after the blocks are placed and before the run's entities spawn.
     * [chunks] (ChunkPos longs) are the ones the build changed; DungeonBuilder doesn't send
     * its blocks any other way. Returns true for a guest, whose HUD the caller must resend (the
     * host's client asks for that itself once its mods have reset).
     */
    static boolean newDungeon(ServerPlayer player, LongSet chunks) {
        boolean guest = !isHost(player);
        if (!guest) {
            MinecraftServer server = player.level().getServer();
            onHostClient(player, mc -> {
                clientNewDungeon(mc);
                // Then the sidebar and tab again, so the mods re-learn the area and floor after resetting.
                server.execute(EngineServer::resendHud);
            });
        } else if (ServerPlayNetworking.canSend(player, Protocol.Signals.TYPE)) {
            // Ahead of the chunks and HUD below on the same connection, so the guest resets first.
            ServerPlayNetworking.send(player, Protocol.Signals.of(NEW_DUNGEON));
        }
        ServerLevel level = player.level();
        for (LongIterator it = chunks.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk == null) {
                continue;
            }
            player.connection.send(new ClientboundForgetLevelChunkPacket(chunk.getPos()));
            player.connection.send(new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null));
        }
        return guest;
    }

    /** Is [player] this game's own player, the one whose client shares the JVM with the server? */
    private static boolean isHost(ServerPlayer player) {
        return player.level().getServer().isSingleplayerOwner(player.nameAndId());
    }

    /** Runs [action] on the client thread if [player] is the one this client plays. */
    private static void onHostClient(ServerPlayer player, Consumer<Minecraft> action) {
        Minecraft mc = Minecraft.getInstance();
        UUID id = player.getUUID();
        mc.execute(() -> {
            if (mc.getConnection() != null && mc.player != null && mc.player.getUUID().equals(id)) {
                action.accept(mc);
            }
        });
    }

    // ---------------------------------------------------------------- on the client (host or guest)

    static final String JOIN = "join";
    static final String NEW_DUNGEON = "newDungeon";

    /** A guest's rustclear:signals from its host (client thread): the same as the host's own client does. */
    static void onSignal(String kind) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            return;
        }
        switch (kind == null ? "" : kind) {
            case JOIN -> clientJoin(mc);
            case NEW_DUNGEON -> clientNewDungeon(mc);
            default -> RustclearMod.LOGGER.warn("Unknown Rustclear signal {}", kind);
        }
    }

    /** The brand and the Mod API hello and location, through this client's own packet listener. */
    private static void clientJoin(Minecraft mc) {
        deliver(mc, "minecraft:brand", buf -> buf.writeUtf(BRAND));
        deliver(mc, "hypixel:hello", buf -> {
            buf.writeBoolean(true);
            buf.writeVarInt(PRODUCTION);
        });
        deliver(mc, "hyevent:location", buf -> {
            buf.writeBoolean(true);
            buf.writeVarInt(LOCATION_VERSION);
            buf.writeUtf(SERVER_NAME);
            optional(buf, "SKYBLOCK"); // serverType
            optional(buf, null); // lobbyName: only set in lobbies
            optional(buf, "dungeon"); // mode
            optional(buf, "Dungeon"); // map
        });
    }

    /** Hypixel moves you to a new server (a new level) for every dungeon. Mods reset on a join (Odin) or on a level change (Devonian, NoammAddons), so fire both. */
    private static void clientNewDungeon(Minecraft mc) {
        ClientPacketListener listener = mc.getConnection();
        try {
            ClientPlayConnectionEvents.JOIN.invoker().onPlayReady(listener, ClientPlayNetworking.getSender(), mc);
        } catch (Throwable t) {
            RustclearMod.LOGGER.warn("A client mod failed to reset for the new dungeon", t);
        }
        try {
            if (mc.level != null) {
                ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.invoker().afterLevelChange(mc, mc.level);
            }
        } catch (Throwable t) {
            RustclearMod.LOGGER.warn("A client mod failed to reset for the new dungeon", t);
        }
    }

    /** Hypixel's /profileid, which Skyblocker and Firmament send by themselves once they think they are in SkyBlock. */
    static void commands(MinecraftServer server) {
        server.getCommands().getDispatcher().register(Commands.literal("profileid").executes(c -> {
            c.getSource().sendSystemMessage(Component.literal("Profile ID: " + PROFILE_ID));
            return 1;
        }));
        // Hypixel's /locraw, which older mods send to learn where they are.
        server.getCommands().getDispatcher().register(Commands.literal("locraw").executes(c -> {
            c.getSource().sendSystemMessage(Component.literal(
                "{\"server\":\"" + SERVER_NAME + "\",\"gametype\":\"SKYBLOCK\",\"mode\":\"dungeon\",\"map\":\"Dungeon\"}"));
            return 1;
        }));
    }

    private static void optional(RegistryFriendlyByteBuf buf, String value) {
        buf.writeBoolean(value != null);
        if (value != null) {
            buf.writeUtf(value);
        }
    }

    /**
     * Writes [body] in Hypixel's wire format, reads it back through ClientboundCustomPayloadPacket's
     * codec, and runs the packet through this client's listener as if it had arrived (client thread).
     */
    @SuppressWarnings("unchecked")
    private static void deliver(Minecraft mc, String channel, Consumer<RegistryFriendlyByteBuf> body) {
        StreamCodec<Object, Object> c = codec();
        ClientPacketListener listener = mc.getConnection();
        if (c == null || listener == null) {
            return;
        }
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), listener.registryAccess());
        try {
            buf.writeUtf(channel);
            body.accept(buf);
            if (c.decode(buf) instanceof Packet<?> packet) {
                ((Packet<PacketListener>) packet).handle(listener);
            }
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                RustclearMod.LOGGER.warn("Couldn't send {} to client mods", channel, t);
            }
        } finally {
            buf.release();
        }
    }

    /** ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC, looked up by reflection. */
    @SuppressWarnings("unchecked")
    private static synchronized StreamCodec<Object, Object> codec() {
        if (!codecLoaded) {
            codecLoaded = true;
            try {
                Class<?> packetClass = Class.forName("net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket");
                Field found = null;
                for (Field f : packetClass.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) && StreamCodec.class.isAssignableFrom(f.getType())
                        && (found == null || f.getName().equals("GAMEPLAY_STREAM_CODEC"))) {
                        found = f;
                    }
                }
                if (found == null) {
                    throw new NoSuchFieldException("GAMEPLAY_STREAM_CODEC");
                }
                found.setAccessible(true);
                codec = (StreamCodec<Object, Object>) found.get(null);
            } catch (Throwable t) {
                RustclearMod.LOGGER.warn("Hypixel signals for client mods unavailable", t);
                codec = null;
            }
        }
        return codec;
    }
}
