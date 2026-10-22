package net.rustclear.mod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.rustclear.mod.engine.KitItems;
import net.rustclear.mod.online.HypixelProfile;
import net.rustclear.mod.ui.NoticeScreen;
import net.rustclear.mod.ui.Theme;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Answers the Rustclear server's hello (the server kicks clients that don't) and adds a
 * "Dungeon settings" and a "Leaderboard" button to the pause menu while connected to one.
 */
public final class RustclearMod implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("rustclear");

    /** The server's catalog, or null when not on a Rustclear server. */
    public static volatile Protocol.Catalog catalog;
    /** The last status the server sent, or null. */
    public static Protocol.StatusMessage lastStatus;
    /** The last leaderboard the server sent, or null. */
    public static Protocol.BoardMessage board;
    /** The player's settings (saved to config/rustclear.json). */
    public static DungeonSettings settings;
    /** The party in the Rustclear world this client is in, as its host last sent it, or null. */
    public static volatile Protocol.PartyState party;
    /** The relay address this client's world is shared at, while it hosts one, or null. Never an IP. */
    public static volatile String shareAddress;

    @Override
    public void onInitializeClient() {
        settings = SettingsStore.load();
        net.rustclear.mod.engine.SimLog.setEnabled(settings.simLog());
        DuelHud.register();
        Online.register();

        // Every channel is registered here, before the engine's entrypoint adds its receivers.
        PayloadTypeRegistry.clientboundPlay().register(Protocol.Hello.TYPE, Protocol.Hello.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Protocol.Status.TYPE, Protocol.Status.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Protocol.Board.TYPE, Protocol.Board.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Protocol.Signals.TYPE, Protocol.Signals.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Protocol.Party.TYPE, Protocol.Party.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Protocol.Settings.TYPE, Protocol.Settings.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Protocol.Board.TYPE, Protocol.Board.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Protocol.Hit.TYPE, Protocol.Hit.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Protocol.Action.TYPE, Protocol.Action.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Protocol.Party.TYPE, Protocol.Party.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Protocol.Kit.TYPE, Protocol.Kit.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Protocol.Skyblock.TYPE, Protocol.Skyblock.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(Protocol.Hello.TYPE, (payload, context) -> {
            Protocol.Catalog hello;
            try {
                hello = Protocol.GSON.fromJson(payload.json(), Protocol.Catalog.class);
            } catch (RuntimeException e) {
                LOGGER.warn("Bad Rustclear hello: {}", e.toString());
                return;
            }
            if (hello.engine && (hello.version != Protocol.VERSION || !version().equals(hello.modVersion))) {
                // Another player's Rustclear world on another build: say so now, before anything breaks.
                String theirs = hello.modVersion == null ? "an older version" : hello.modVersion;
                var connection = context.client().getConnection();
                if (connection != null) {
                    connection.getConnection().disconnect(Component.literal("This Rustclear party runs Rustclear "
                        + theirs + " (protocol " + hello.version + ").\nYou have " + version() + " (protocol "
                        + Protocol.VERSION + "). Install the same version as the host to join."));
                }
                return;
            }
            catalog = hello;
            net.rustclear.mod.engine.Engine.backToRustclear = true;
            if (catalog.version != Protocol.VERSION && !catalog.engine) {
                LOGGER.info("Rustclear server speaks protocol {}, answering in it", catalog.version);
            }
            ClientPlayNetworking.send(Protocol.Settings.of("join", settings));
            if (catalog.engine) {
                // The Kit editor's kit lives on this computer: the host needs it to hand it out.
                KitItems.send();
                HypixelProfile.joined();
                HypixelProfile.onJoin(catalog.current != null && catalog.current.levelsFromHypixel());
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(Protocol.Status.TYPE, (payload, context) -> {
            try {
                lastStatus = Protocol.GSON.fromJson(payload.json(), Protocol.StatusMessage.class);
                if (lastStatus != null && lastStatus.settings != null && lastStatus.settings.levelsFromHypixel()) {
                    // The leader switched the party to Hypixel stats: the host needs this player's.
                    HypixelProfile.onJoin(true);
                }
            } catch (RuntimeException e) {
                LOGGER.warn("Bad Rustclear status: {}", e.toString());
                return;
            }
            if (Minecraft.getInstance().gui.screen() instanceof SettingsScreen screen) {
                screen.onStatus(lastStatus);
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(Protocol.Board.TYPE, (payload, context) -> {
            Protocol.BoardMessage page;
            try {
                page = Protocol.GSON.fromJson(payload.json(), Protocol.BoardMessage.class);
            } catch (RuntimeException e) {
                LOGGER.warn("Bad Rustclear leaderboard: {}", e.toString());
                return;
            }
            if (page.entries == null) {
                page.entries = List.of();
            }
            board = page;
        });

        ClientPlayNetworking.registerGlobalReceiver(Protocol.Party.TYPE, (payload, context) -> {
            Protocol.PartyState state;
            try {
                state = Protocol.GSON.fromJson(payload.json(), Protocol.PartyState.class);
            } catch (RuntimeException e) {
                LOGGER.warn("Bad Rustclear party state: {}", e.toString());
                return;
            }
            if (state.closed != null) {
                endParty(context.client(), state.closed);
                return;
            }
            if (state.members == null) {
                state.members = List.of();
            }
            if (state.teams == null) {
                state.teams = List.of();
            }
            Protocol.PartyState previous = party;
            party = state;
            if (context.client().gui.screen() instanceof PartyScreen screen) {
                screen.onParty();
            } else if (context.client().gui.screen() instanceof SettingsScreen screen) {
                screen.onParty();
            }
            // A duel just ended: show the results once, over whatever is open.
            if (state.winner != null && (previous == null || previous.winner == null)
                && !(context.client().gui.screen() instanceof DuelResultsScreen)) {
                context.client().gui.setScreen(new DuelResultsScreen(context.client().gui.screen()));
            }
        });

        // The party lobby covers the world while the party waits to start, and gets out of the way
        // once the countdown begins. Anything else open (chat, a menu) is left alone until it closes.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            Protocol.Catalog c = catalog;
            Protocol.PartyState p = party;
            if (c == null || !c.engine || p == null || client.player == null) {
                return;
            }
            boolean waiting = "lobby".equals(p.phase);
            Screen open = client.gui.screen();
            if (waiting && open == null) {
                client.gui.setScreen(SettingsScreen.lobby());
            } else if (!waiting && open instanceof SettingsScreen screen && screen.isLobby()) {
                screen.closeForRun();
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            catalog = null;
            lastStatus = null;
            board = null;
            party = null;
        });

        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof PauseScreen && catalog != null) {
                Screens.getWidgets(screen).add(Button.builder(Component.literal("Dungeon settings"),
                        b -> client.gui.setScreen(new SettingsScreen(screen)))
                    .bounds(4, 4, 110, 20)
                    .build());
                Screens.getWidgets(screen).add(Button.builder(Component.literal("Leaderboard"),
                        b -> client.gui.setScreen(new SettingsScreen(screen, true)))
                    .bounds(4, 28, 110, 20)
                    .build());
                // Parties only exist in Rustclear worlds (the host's or a guest's), not on the Rust server.
                if (catalog.engine) {
                    Screens.getWidgets(screen).add(Button.builder(Component.literal("Party"),
                            b -> client.gui.setScreen(new PartyScreen(screen)))
                        .bounds(4, 52, 110, 20)
                        .build());
                }
            }
        });
    }

    /**
     * The host ended the party or removed this player: leave the world and say so in Rustclear's
     * own notice, which reads as the party ending rather than a lost connection.
     */
    private static void endParty(Minecraft mc, String why) {
        party = null;
        mc.disconnect(new NoticeScreen("Party ended", why, Theme.GOLD, "Back", () -> new RustclearMenuScreen(new TitleScreen())), false);
    }

    /** A party request to the Rustclear world's host. */
    public static void sendParty(String action, String target) {
        if (catalog != null && catalog.engine) {
            ClientPlayNetworking.send(Protocol.Party.request(action, target));
        }
    }

    public static void sendParty(String action, String target, String mode) {
        if (catalog != null && catalog.engine) {
            ClientPlayNetworking.send(Protocol.Party.request(action, target, mode));
        }
    }

    /** A leader's party setting with a number: "size" (the player limit) or "target" (the duel's score). */
    public static void sendParty(String action, int value) {
        if (catalog != null && catalog.engine) {
            ClientPlayNetworking.send(Protocol.Party.request(action, value));
        }
    }

    /** Picks [dungeonClass] for this player on the host, which readies them (EngineProtocol). */
    public static void pickClass(String dungeonClass) {
        if (catalog != null && catalog.engine) {
            ClientPlayNetworking.send(Protocol.Party.pickClass(dungeonClass));
        }
    }

    public static String version() {
        // Rustclear is part of ADS now: the version is ADS's own (mod id engineerclient).
        return FabricLoader.getInstance().getModContainer("engineerclient")
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("dev");
    }

    /**
     * May this player change the dungeon? Always on a Rustclear server (everyone sets their own);
     * in an engine world only the party leader may, which the host says in each status.
     */
    public static boolean canEditDungeon() {
        Protocol.StatusMessage status = lastStatus;
        return status == null || status.leader == null || status.leader;
    }

    public static void send(String action) {
        SettingsStore.save(settings);
        net.rustclear.mod.engine.SimLog.setEnabled(settings.simLog());
        if (catalog != null) {
            ClientPlayNetworking.send(Protocol.Settings.of(action, settings));
        }
    }

    /** Asks for a category's board, or the player's own party size when {@code category} is null. */
    public static void requestBoard(String category) {
        if (catalog != null) {
            ClientPlayNetworking.send(Protocol.Board.request(category));
        }
    }
}
