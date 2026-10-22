package net.rustclear.mod.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.rustclear.mod.Online;
import net.rustclear.mod.Protocol;
import net.rustclear.mod.RustclearMenuScreen;
import net.rustclear.mod.RustclearMod;
import net.rustclear.mod.ui.RustclearTitleButton;

/**
 * Rustclear without a server: a "Rustclear" button on the title screen opens the Rustclear menu
 * (RustclearMenuScreen), whose "Make a party" opens a singleplayer world the mod builds dungeons
 * into ([EngineWorld], [EngineServer]). Other players can join that
 * world as guests; everything a guest's game needs from the host arrives over the mod's channels.
 */
public final class Engine implements ClientModInitializer {
    /**
     * Is this client playing in a Rustclear world right now: its own (the host), or another
     * player's that said so in its hello (a guest)? Cleared when the hello is (on disconnect).
     */
    public static boolean isEngineClient() {
        return isEngineHost() || isEngineGuest();
    }

    /** Is this client running the Rustclear world it is in? */
    public static boolean isEngineHost() {
        Minecraft mc = Minecraft.getInstance();
        return mc.getSingleplayerServer() != null && mc.getSingleplayerServer() == EngineServer.server();
    }

    /** Is this client a guest in another player's Rustclear world? */
    public static boolean isEngineGuest() {
        Protocol.Catalog catalog = RustclearMod.catalog;
        return catalog != null && catalog.engine && EngineServer.server() == null;
    }

    /**
     * Set while in a Rustclear world or on a Rustclear server: leaving it (a duel ending, Disconnect,
     * the host closing the world) lands on the Rustclear menu instead of Minecraft's title screen.
     */
    public static volatile boolean backToRustclear;

    @Override
    public void onInitializeClient() {
        EngineServer.register();
        ClientPlayNetworking.registerGlobalReceiver(Protocol.Signals.TYPE, (payload, context) -> {
            // Only from a Rustclear world: no other server gets to make this client think it is on Hypixel.
            if (!isEngineGuest()) {
                return;
            }
            try {
                Protocol.SignalMessage message = Protocol.GSON.fromJson(payload.json(), Protocol.SignalMessage.class);
                HypixelSignals.onSignal(message.kind());
            } catch (RuntimeException e) {
                RustclearMod.LOGGER.warn("Bad Rustclear signal: {}", e.toString());
            }
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (isEngineHost()) {
                backToRustclear = true;
                Online.onHostJoined();
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> Online.onLeftWorld());
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (client.level == null && screen instanceof net.rustclear.mod.ui.RustclearScreen) {
                // Already on a Rustclear screen (a "Party ended" notice...): its Back goes where it says.
                backToRustclear = false;
            }
            if (backToRustclear && client.level == null && !EngineWorld.opening
                && (screen instanceof TitleScreen || screen instanceof JoinMultiplayerScreen)) {
                // Just left a Rustclear world or server: back to Rustclear's own menu.
                backToRustclear = false;
                client.execute(() -> client.gui.setScreen(new RustclearMenuScreen(new TitleScreen())));
                return;
            }
            if (screen instanceof TitleScreen) {
                // Above Singleplayer and the same size; top right if a mod moved Singleplayer away.
                AbstractWidget single = Screens.getWidgets(screen).stream()
                    .filter(w -> w.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals("menu.singleplayer"))
                    .findFirst().orElse(null);
                Runnable open = () -> client.gui.setScreen(new RustclearMenuScreen(screen));
                Screens.getWidgets(screen).add(single != null
                    ? new RustclearTitleButton(single.getX(), single.getY() - single.getHeight() - 4, single.getWidth(), single.getHeight(), open)
                    : new RustclearTitleButton(width - 204, 4, 200, 20, open));
            }
        });
    }

    /** Practice names already checked by [canPractice], so the settings screen can ask every frame. */
    private static final Map<String, Boolean> PRACTICABLE = new ConcurrentHashMap<>();

    /**
     * Can the settings screen offer to practice [roomName]? Only in a Rustclear world, only for the
     * party leader (practice rebuilds everyone's dungeon), and only for rooms practice mode builds.
     */
    public static boolean canPractice(String roomName) {
        if (!isEngineClient() || !RustclearMod.canEditDungeon()) {
            return false;
        }
        return PRACTICABLE.computeIfAbsent(roomName,
            n -> n.equals("Blaze") || Practice.find(RoomStore.get(), n.replace(' ', '_')) != null);
    }

    /** Rooms the single-room practice builds, as /practice names them (underscores for spaces). */
    public static java.util.List<String> practiceRooms() {
        return Practice.roomNames(RoomStore.get());
    }

    /** /practice [roomName] from the settings screen: the room alone, a random door, all its secrets. */
    public static void practice(String roomName, boolean mobs) {
        if (isEngineClient()) {
            ClientPlayNetworking.send(Protocol.Action.practice(roomName, mobs));
        }
    }

    /** Save Hotbar / Default Hotbar from Dungeon settings, for this player only. */
    public static void hotbar(boolean save) {
        if (isEngineClient()) {
            ClientPlayNetworking.send(Protocol.Action.hotbar(save));
        }
    }
}
