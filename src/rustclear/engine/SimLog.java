package net.rustclear.mod.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.item.ItemStack;
import net.rustclear.mod.RustclearMod;

/**
 * The simulator's own run as JSON lines, in the schema of the Dungeon Recorder timelines the F7
 * knowledge base was measured from (docs/f7-sim, catalog.py): one object per line with a kind
 * "k", the server tick "n", and the event's fields. tools/f7check/compare.py reads it and checks
 * every scripted event against the measured timings.
 *
 * It logs what the recorded player (the host) receives, as the recorder does: chat ("c"), the
 * action bar ("ab"), titles ("hud"), the boss bar ("bar"), windows and their slots ("scr", "slot",
 * "content", "close"), sounds ("snd") and server teleports ("tp"), straight off the packets
 * (SimLogMixin), plus the sidebar ("sb", from Sidebar), the host's own clicks ("click") and the
 * simulator's events ("ev": doors, phases, entity spawns) that the recordings show as world changes.
 *
 * Off unless the host turns on "SimLog" in the settings. Files go to the game folder's
 * rustclear-simlog/, one per world session. Server thread only.
 */
public final class SimLog {
    private static volatile boolean enabled;
    private static BufferedWriter out;
    private static MinecraftServer server;
    private static UUID recorded;

    private SimLog() {
    }

    /** The host's setting (RustclearMod.settings.simLog); takes effect on the next event. */
    public static void setEnabled(boolean on) {
        enabled = on;
        if (!on) {
            RustclearMod.LOGGER.debug("SimLog off");
        }
    }

    public static boolean enabled() {
        return enabled && server != null;
    }

    /** The engine world opened: a new file on the first event. */
    static void start(MinecraftServer s, UUID host) {
        close();
        server = s;
        recorded = host;
    }

    /** The world closed. */
    static void stop() {
        close();
        server = null;
        recorded = null;
    }

    private static void close() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException e) {
                RustclearMod.LOGGER.warn("SimLog close failed: {}", e.toString());
            }
            out = null;
        }
    }

    private static long tick() {
        return server == null ? 0 : server.getTickCount();
    }

    private static synchronized void write(JsonObject line) {
        if (!enabled()) {
            return;
        }
        try {
            if (out == null) {
                Path dir = FabricLoader.getInstance().getGameDir().resolve("rustclear-simlog");
                Files.createDirectories(dir);
                String name = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".jsonl";
                out = Files.newBufferedWriter(dir.resolve(name), StandardCharsets.UTF_8);
                RustclearMod.LOGGER.info("SimLog writing to {}", dir.resolve(name));
            }
            out.write(line.toString());
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            RustclearMod.LOGGER.warn("SimLog write failed, turning it off: {}", e.toString());
            enabled = false;
            close();
        }
    }

    private static JsonObject line(String kind) {
        JsonObject o = new JsonObject();
        o.addProperty("k", kind);
        o.addProperty("n", tick());
        return o;
    }

    // ---------------------------------------------------------------- what the simulator logs itself

    /** A simulator event the recordings show as a world change ("door_barrier", "phase", ...). */
    public static void event(String name, Object... keyValues) {
        if (!enabled()) {
            return;
        }
        JsonObject o = line("ev");
        o.addProperty("e", name);
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object v = keyValues[i + 1];
            String key = String.valueOf(keyValues[i]);
            if (v instanceof Number num) {
                o.addProperty(key, num);
            } else if (v instanceof Boolean b) {
                o.addProperty(key, b);
            } else {
                o.addProperty(key, String.valueOf(v));
            }
        }
        write(o);
    }

    /** The sidebar a level's players now see, when it changed (colour codes stripped, as "sb" lines are). */
    static void sidebar(net.minecraft.server.level.ServerLevel level, UUID viewer, List<String> lines) {
        if (!enabled() || !hostIn(level) || viewer != null && !viewer.equals(recorded)) {
            return;
        }
        JsonObject o = line("sb");
        JsonArray a = new JsonArray();
        // Line 0 is the objective's title, which the recorder's "sb" lines don't carry.
        for (int i = 1; i < lines.size(); i++) {
            a.add(plain(lines.get(i)));
        }
        o.add("lines", a);
        write(o);
    }

    /** Changed tab rows (index, text) for the host's level. */
    static void tab(net.minecraft.server.level.ServerLevel level, Map<Integer, String> rows) {
        if (!enabled() || rows.isEmpty() || !hostIn(level)) {
            return;
        }
        JsonObject o = line("tab");
        JsonArray a = new JsonArray();
        rows.forEach((i, t) -> {
            JsonArray row = new JsonArray();
            row.add(i);
            row.add(plain(t));
            a.add(row);
        });
        o.add("rows", a);
        write(o);
    }

    private static boolean hostIn(net.minecraft.server.level.ServerLevel level) {
        ServerPlayer host = recorded == null || server == null ? null : server.getPlayerList().getPlayer(recorded);
        return host != null && host.level() == level;
    }

    /**
     * The host's own movement each client tick, as the recorder's "me" lines (position, rotation,
     * ground, keys, sprint, sneak), so tools/f7check/movescore.py measures a player and the
     * scripted clear the same way.
     */


    public static void me(long clientTick, double[] pos, float yaw, float pitch, boolean ground, boolean sprint,
                          boolean shift, boolean forward, boolean back, boolean left, boolean right, boolean jump,
                          boolean sneakKey, boolean hcol) {
        if (!enabled()) {
            return;
        }
        JsonObject o = line("me");

        // The client tick as the recorder's "t" (server ticks repeat or skip against client ticks).
        o.addProperty("t", clientTick);
        o.addProperty("hcol", hcol);
        JsonArray p = new JsonArray();
        for (double v : pos) {
            p.add(v);
        }
        o.add("pos", p);
        JsonArray r = new JsonArray();
        r.add(yaw);
        r.add(pitch);
        o.add("rot", r);
        o.addProperty("ground", ground);
        o.addProperty("sprint", sprint);
        o.addProperty("shift", shift);
        JsonObject keys = new JsonObject();
        keys.addProperty("key.forward", forward);
        keys.addProperty("key.back", back);
        keys.addProperty("key.left", left);
        keys.addProperty("key.right", right);
        keys.addProperty("key.jump", jump);
        keys.addProperty("key.sneak", sneakKey);
        o.add("keys", keys);
        write(o);
    }

    // ---------------------------------------------------------------- packets (SimLogMixin)

    /** A packet on its way to [player]. */
    public static void sent(ServerPlayer player, Packet<?> packet) {
        if (!enabled() || player == null || !player.getUUID().equals(recorded)) {
            return;
        }
        if (packet instanceof BundlePacket<?> bundle) {
            for (Packet<?> p : bundle.subPackets()) {
                sent(player, p);
            }
            return;
        }
        switch (packet) {
            case ClientboundSystemChatPacket chat -> {
                JsonObject o = line(chat.overlay() ? "ab" : "c");
                o.addProperty("t", chat.content().getString().replaceAll("§.", ""));
                o.addProperty("r", legacy(chat.content()));
                write(o);
            }
            case ClientboundSetTitlesAnimationPacket times -> {
                JsonObject o = line("hud");
                o.addProperty("what", "times");
                o.addProperty("t", times.getFadeIn() + "/" + times.getStay() + "/" + times.getFadeOut());
                write(o);
            }
            case ClientboundSetTitleTextPacket title -> hud("title", title.text());
            case ClientboundSetSubtitleTextPacket sub -> hud("subtitle", sub.text());
            case ClientboundBossEventPacket bar -> bar.dispatch(new BarLogger());
            case ClientboundOpenScreenPacket open -> {
                JsonObject o = line("scr");
                o.addProperty("open", true);
                o.addProperty("cid", open.getContainerId());
                o.addProperty("menu", String.valueOf(BuiltInRegistries.MENU.getKey(open.getType())));
                o.addProperty("title", plain(open.getTitle().getString()));
                write(o);
            }
            case ClientboundContainerSetSlotPacket slot -> {
                JsonObject o = line("slot");
                o.addProperty("cid", slot.getContainerId());
                o.addProperty("state", slot.getStateId());
                o.addProperty("slot", slot.getSlot());
                o.add("item", item(slot.getItem()));
                write(o);
            }
            case ClientboundContainerSetContentPacket content -> {
                JsonObject o = line("content");
                o.addProperty("cid", content.containerId());
                o.addProperty("state", content.stateId());
                JsonArray items = new JsonArray();
                content.items().forEach(i -> items.add(item(i)));
                o.add("items", items);
                write(o);
            }
            case ClientboundContainerClosePacket close -> {
                JsonObject o = line("close");
                o.addProperty("cid", close.getContainerId());
                o.addProperty("by", "server");
                write(o);
            }
            case ClientboundSoundPacket sound -> {
                JsonObject o = line("snd");
                o.addProperty("id", sound.getSound().unwrapKey().map(k -> k.identifier().toString())
                    .orElseGet(() -> sound.getSound().value().location().toString()));
                o.addProperty("src", sound.getSource().getName());
                o.addProperty("vol", sound.getVolume());
                o.addProperty("pitch", sound.getPitch());
                write(o);
            }
            case ClientboundPlayerPositionPacket tp -> {
                JsonObject o = line("tp");
                o.addProperty("x", tp.change().position().x);
                o.addProperty("y", tp.change().position().y);
                o.addProperty("z", tp.change().position().z);
                o.addProperty("yaw", tp.change().yRot());
                o.addProperty("pitch", tp.change().xRot());
                o.addProperty("rel", tp.relatives().toString());
                write(o);
            }
            default -> {
            }
        }
    }

    /** A packet from [player] (container clicks and closes, for the container-protocol check). */
    public static void received(ServerPlayer player, Packet<?> packet) {
        if (!enabled() || player == null || !player.getUUID().equals(recorded)) {
            return;
        }
        if (packet instanceof ServerboundContainerClickPacket click) {
            JsonObject o = line("click");
            o.addProperty("cid", click.containerId());
            o.addProperty("slot", click.slotNum());
            o.addProperty("button", click.buttonNum());
            o.addProperty("input", click.containerInput().name());
            write(o);
        } else if (packet instanceof ServerboundContainerClosePacket close) {
            JsonObject o = line("close");
            o.addProperty("cid", close.getContainerId());
            o.addProperty("by", "client");
            write(o);
        }
    }

    private static void hud(String what, Component text) {
        JsonObject o = line("hud");
        o.addProperty("what", what);
        o.addProperty("t", plain(text.getString()));
        o.addProperty("r", legacy(text));
        write(o);
    }

    private static JsonObject item(ItemStack stack) {
        JsonObject o = new JsonObject();
        if (stack.isEmpty()) {
            o.addProperty("id", "air");
            return o;
        }
        o.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath());
        o.addProperty("count", stack.getCount());
        o.addProperty("name", legacy(stack.getHoverName()));
        return o;
    }

    private static final class BarLogger implements ClientboundBossEventPacket.Handler {
        private void bar(UUID id, String op, Component name, Float progress, BossEvent.BossBarColor color) {
            JsonObject o = line("bar");
            o.addProperty("id", id.toString());
            o.addProperty("op", op);
            if (name != null) {
                o.addProperty("name", legacy(name));
            }
            if (progress != null) {
                o.addProperty("progress", progress);
            }
            if (color != null) {
                o.addProperty("color", color.getName());
            }
            write(o);
        }

        @Override
        public void add(UUID id, Component name, float progress, BossEvent.BossBarColor color, BossEvent.BossBarOverlay overlay,
                        boolean darken, boolean music, boolean fog) {
            bar(id, "add", name, progress, color);
        }

        @Override
        public void remove(UUID id) {
            bar(id, "remove", null, null, null);
        }

        @Override
        public void updateProgress(UUID id, float progress) {
            bar(id, "progress", null, progress, null);
        }

        @Override
        public void updateName(UUID id, Component name) {
            bar(id, "name", name, null, null);
        }

        @Override
        public void updateStyle(UUID id, BossEvent.BossBarColor color, BossEvent.BossBarOverlay overlay) {
            bar(id, "style", null, null, color);
        }
    }

    // ---------------------------------------------------------------- text

    /** [legacy] without its § codes. */
    static String plain(String legacy) {
        return legacy == null ? "" : legacy.replaceAll("§.", "");
    }

    /** [text] as § codes: each styled part's colour and formats, then its text (literal § codes kept). */
    public static String legacy(Component text) {
        StringBuilder sb = new StringBuilder();
        text.visit((style, part) -> {
            if (!part.isEmpty()) {
                sb.append(codes(style)).append(part);
            }
            return java.util.Optional.empty();
        }, Style.EMPTY);
        return sb.toString();
    }

    private static String codes(Style style) {
        StringBuilder sb = new StringBuilder();
        TextColor color = style.getColor();
        if (color != null) {
            String named = null;
            for (ChatFormatting f : ChatFormatting.values()) {
                if (color.equals(TextColor.fromLegacyFormat(f))) {
                    // ChatFormatting's string is its code, "§a".
                    named = f.toString();
                    break;
                }
            }
            sb.append(named != null ? named : "§#" + color.serialize().replace("#", ""));
        }
        if (style.isBold()) sb.append("§l");
        if (style.isItalic()) sb.append("§o");
        if (style.isUnderlined()) sb.append("§n");
        if (style.isStrikethrough()) sb.append("§m");
        if (style.isObfuscated()) sb.append("§k");
        return sb.toString();
    }
}
