package net.rustclear.mod.online;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.util.Signer;
import net.minecraft.world.entity.player.ProfileKeyPair;
import net.minecraft.world.entity.player.ProfilePublicKey;
import net.rustclear.mod.Protocol;
import net.rustclear.mod.RustclearMod;

/**
 * Talks to the Rustclear lobby (../lobby): signs in with Mojang's session flow, lists open
 * parties, and keeps one WebSocket for listings and the 1v1 / 2v2 queues. The lobby's URL is in
 * config/rustclear-online.json; with none set, or the lobby down, everything online reports
 * itself unavailable and private parties, LAN and joining by address carry on as normal.
 *
 * Sign-in: the lobby hands out a server id, this game signs it with the chat-signing key Mojang
 * issued the account, and the lobby checks Mojang's certificate on that key itself (Mojang's APIs
 * refuse Cloudflare Workers, so it can't ask). Only the player's name, id and public key are
 * known to the lobby; no token, email or password is ever sent to it, and no IP is.
 */
public final class LobbyClient {
    private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("rustclear-online.json");
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    /** The config file: where the lobby is, and whether to use it at all. */
    static final class Config {
        @SerializedName("lobby_url")
        String lobbyUrl = "";
        boolean enabled = true;
    }

    public enum Health { UNKNOWN, CHECKING, UP, DOWN }

    /** One open party as the lobby lists it. */
    public static final class Listing {
        public String id;
        public String leader;
        public List<String> members = List.of();
        public int count;
        public int max;
        public String mode;
        public String version;
        public String address;
    }

    private static final class Parties {
        List<Listing> parties = List.of();
    }

    private static final class SignIn {
        String token;
        long expires;
        String error;
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private static Config config;
    private static volatile Health health = Health.UNKNOWN;
    private static volatile String token;
    private static volatile long tokenExpires;
    private static volatile WebSocket socket;
    private static volatile Consumer<JsonObject> handler = m -> { };

    private LobbyClient() {
    }

    private static synchronized Config config() {
        if (config == null) {
            try {
                config = Files.exists(CONFIG) ? Protocol.GSON.fromJson(Files.readString(CONFIG), Config.class) : null;
            } catch (IOException | RuntimeException e) {
                RustclearMod.LOGGER.warn("Couldn't read {}: {}", CONFIG, e.toString());
            }
            if (config == null) {
                config = new Config();
                try {
                    // Written once so the setting is easy to find.
                    Files.createDirectories(CONFIG.getParent());
                    Files.writeString(CONFIG, Protocol.GSON.toJson(config));
                } catch (IOException e) {
                    RustclearMod.LOGGER.warn("Couldn't write {}: {}", CONFIG, e.toString());
                }
            }
        }
        return config;
    }

    /** The lobby's base URL without a trailing slash, or null when online features are off. */
    private static String base() {
        Config c = config();
        if (!c.enabled || c.lobbyUrl == null || !c.lobbyUrl.startsWith("https://")) {
            return null;
        }
        return c.lobbyUrl.replaceAll("/+$", "");
    }

    public static boolean configured() {
        return base() != null;
    }

    public static Health health() {
        return health;
    }

    /** Checks whether the lobby answers; [health] follows. */
    public static void checkHealth() {
        String base = base();
        if (base == null) {
            health = Health.DOWN;
            return;
        }
        if (health == Health.CHECKING) {
            return;
        }
        health = Health.CHECKING;
        HTTP.sendAsync(get(base + "/v1/health").build(), HttpResponse.BodyHandlers.ofString())
            .whenComplete((r, e) -> health = e == null && r.statusCode() == 200 ? Health.UP : Health.DOWN);
    }

    private static HttpRequest.Builder get(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).header("Accept", "application/json");
    }

    // ---------------------------------------------------------------- sign-in

    /** A lobby token for this player, signing in through Mojang if there is none (off the client thread). */
    private static CompletableFuture<String> signIn() {
        String base = base();
        if (base == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Online features unavailable."));
        }
        if (token != null && tokenExpires - 60 > System.currentTimeMillis() / 1000) {
            return CompletableFuture.completedFuture(token);
        }
        return HTTP.sendAsync(get(base + "/v1/auth/challenge").POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString())
            .thenApplyAsync(r -> {
                String serverId = JsonParser.parseString(r.body()).getAsJsonObject().get("serverId").getAsString();
                Minecraft mc = Minecraft.getInstance();
                User user = mc.getUser();
                // The chat-signing key Mojang issues this account (the one vanilla signs chat with):
                // Mojang's certificate on it proves the account to the lobby, which can't reach Mojang.
                ProfileKeyPair keys = mc.getProfileKeyPairManager().prepareKeyPair().join()
                    .orElseThrow(() -> new IllegalStateException("Online play needs a signed-in Microsoft account."));
                ProfilePublicKey.Data cert = keys.publicKey().data();
                Base64.Encoder b64 = Base64.getEncoder();
                JsonObject body = new JsonObject();
                body.addProperty("name", user.getName());
                body.addProperty("uuid", user.getProfileId().toString().replace("-", ""));
                body.addProperty("serverId", serverId);
                body.addProperty("publicKey", b64.encodeToString(cert.key().getEncoded()));
                body.addProperty("expiresAt", cert.expiresAt().toEpochMilli());
                body.addProperty("keySignature", b64.encodeToString(cert.keySignature()));
                body.addProperty("signature", b64.encodeToString(Signer.from(keys.privateKey(), "SHA256withRSA")
                    .sign(("rustclear-lobby:" + serverId).getBytes(StandardCharsets.UTF_8))));
                return body.toString();
            })
            .thenCompose(body -> HTTP.sendAsync(get(base + "/v1/auth/verify")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString()))
            .thenApply(r -> {
                SignIn result = Protocol.GSON.fromJson(r.body(), SignIn.class);
                if (r.statusCode() != 200 || result == null || result.token == null) {
                    throw new IllegalStateException(result != null && result.error != null ? result.error : "Couldn't sign in to the lobby.");
                }
                token = result.token;
                tokenExpires = result.expires;
                return token;
            });
    }

    // ---------------------------------------------------------------- party browser

    /** The open parties on this mod version. */
    public static CompletableFuture<List<Listing>> parties() {
        String version = RustclearMod.version();
        return signIn().thenCompose(t -> HTTP.sendAsync(get(base() + "/v1/parties?version=" + encode(version))
                .header("Authorization", "Bearer " + t).build(), HttpResponse.BodyHandlers.ofString()))
            .thenApply(r -> {
                if (r.statusCode() != 200) {
                    throw new IllegalStateException("The lobby didn't answer.");
                }
                Parties p = Protocol.GSON.fromJson(r.body(), Parties.class);
                // The lobby filters by version already; this is in case an older one doesn't.
                return p == null || p.parties == null ? List.<Listing>of()
                    : p.parties.stream().filter(l -> version.equals(l.version) && Relays.cleanAddress(l.address) != null).toList();
            });
    }

    /**
     * How many players are searching in each 1v1 / 2v2 queue on this mod version, keyed
     * "DUEL_1V1:270" (mode and target score).
     */
    public static CompletableFuture<Map<String, Integer>> queues() {
        String version = RustclearMod.version();
        return signIn().thenCompose(t -> HTTP.sendAsync(get(base() + "/v1/queues?version=" + encode(version))
                .header("Authorization", "Bearer " + t).build(), HttpResponse.BodyHandlers.ofString()))
            .thenApply(r -> {
                if (r.statusCode() != 200) {
                    throw new IllegalStateException("The lobby didn't answer.");
                }
                Map<String, Integer> out = new HashMap<>();
                JsonObject counts = JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("counts");
                if (counts != null) {
                    counts.entrySet().forEach(e -> out.put(e.getKey(), e.getValue().getAsInt()));
                }
                return Map.copyOf(out);
            });
    }

    /**
     * This player's own Hypixel SkyBlock profile, as the lobby reads it with its API key (signed
     * in, so it can only ever be your own): the selected profile's Catacombs and class levels,
     * class, skills, slayers, Accessory Power and SkyBlock level (SkyblockProfile's fields), and
     * its pets and inventory (engine.SkyblockImport reads those).
     */
    public static CompletableFuture<JsonObject> hypixelProfile() {
        if (base() == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                "Set lobby_url in config/rustclear-online.json to read your Hypixel profile."));
        }
        return signIn().thenCompose(t -> HTTP.sendAsync(get(base() + "/v1/hypixel/me")
                .header("Authorization", "Bearer " + t).build(), HttpResponse.BodyHandlers.ofString()))
            .thenApply(r -> {
                JsonObject body = JsonParser.parseString(r.body()).getAsJsonObject();
                if (r.statusCode() != 200) {
                    throw new IllegalStateException(body.has("error") ? body.get("error").getAsString() : "The lobby didn't answer.");
                }
                return body;
            });
    }

    private static String encode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- the socket

    /** Where lobby messages go (client thread). */
    public static void onMessage(Consumer<JsonObject> next) {
        handler = next;
    }

    public static boolean connected() {
        WebSocket ws = socket;
        return ws != null && !ws.isOutputClosed() && !ws.isInputClosed();
    }

    /** Opens the lobby socket if it isn't open. */
    public static CompletableFuture<Void> connect() {
        if (connected()) {
            return CompletableFuture.completedFuture(null);
        }
        String base = base();
        return signIn().thenCompose(t -> HTTP.newWebSocketBuilder()
                .header("Authorization", "Bearer " + t)
                .connectTimeout(TIMEOUT)
                .buildAsync(URI.create(base.replaceFirst("^https", "wss") + "/v1/ws"), new Listener()))
            .thenAccept(ws -> socket = ws);
    }

    /** Sends [message] once the socket is open. */
    public static CompletableFuture<Void> send(JsonObject message) {
        return connect().thenCompose(v -> socket.sendText(message.toString(), true)).thenAccept(ws -> { });
    }

    public static void close() {
        WebSocket ws = socket;
        socket = null;
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        }
    }

    private static final class Listener implements WebSocket.Listener {
        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String text = partial.toString();
                partial.setLength(0);
                try {
                    JsonObject message = JsonParser.parseString(text).getAsJsonObject();
                    Minecraft.getInstance().execute(() -> handler.accept(message));
                } catch (RuntimeException e) {
                    RustclearMod.LOGGER.debug("Bad lobby message: {}", e.toString());
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
            if (socket == ws) {
                socket = null;
            }
            JsonObject closed = new JsonObject();
            closed.addProperty("type", "closed");
            Minecraft.getInstance().execute(() -> handler.accept(closed));
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            onClose(ws, WebSocket.NORMAL_CLOSURE, "");
        }
    }
}
