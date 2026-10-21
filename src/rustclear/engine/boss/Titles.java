package net.rustclear.mod.engine.boss;

import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.rustclear.mod.engine.EngineItems;

/**
 * Server titles the way Hypixel sends them (menus-ui.md "Titles"): times, title, then subtitle,
 * all three in one tick, and only where Hypixel sends one (P1-P3 progress, P2's countdown, ghost
 * and revive). Client mods draw the rest ("Kill Mobs", dragon spawns), so the server never does.
 */
public final class Titles {
    /** P1-P3 progress subtitles: 0/40/0. */
    public static final int[] PROGRESS = {0, 40, 0};
    /** P2's 4-3-2-1 countdown: 0/30/0. */
    public static final int[] COUNTDOWN = {0, 30, 0};
    /** "You became a ghost!": 0/100/5. */
    public static final int[] GHOST = {0, 100, 5};
    /** "BEING REVIVED": 0/30/0. */
    public static final int[] REVIVING = {0, 30, 0};

    private Titles() {
    }

    /** [title] and [subtitle] (§ codes; "" for none) on [player]'s screen with [times] {in, stay, out}. */
    public static void send(ServerPlayer player, String title, String subtitle, int[] times) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(times[0], times[1], times[2]));
        player.connection.send(new ClientboundSetTitleTextPacket(EngineItems.styled(title)));
        player.connection.send(new ClientboundSetSubtitleTextPacket(EngineItems.styled(subtitle)));
    }
}
