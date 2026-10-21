package net.rustclear.mod.online;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Downloads e4mc from Modrinth, its own distribution: the newest Fabric release for a Minecraft
 * version from Modrinth's API, fetched from Modrinth's CDN only and checked against the SHA-512
 * Modrinth publishes, then moved into the mods folder whole. Plain Java (no Minecraft or Fabric
 * classes), so the start-up prompt (E4mcPrompt, a process of its own) can run it too.
 *
 * Bundling e4mc inside Rustclear's jar would also work, but e4mc refuses copies re-hosted anywhere
 * but Modrinth and CurseForge (its PoisonPill); fetching it from Modrinth keeps to its author's wish.
 *
 * The same goes for e4all, the e4mc fork used alongside Krypton (E4mcPreLaunch.relayProject).
 */
public final class E4mcDownload {
    private static final String API = "https://api.modrinth.com/v2/project/%s/version";
    private static final String CDN = "https://cdn.modrinth.com/";
    /** A downloaded jar waiting for the game to close before it replaces a locked one (E4mcSwap). */
    public static final String PENDING = ".pending";

    private E4mcDownload() {
    }

    /**
     * Installs [project] ("e4mc" or "e4all") for Minecraft [minecraft] into [mods]; returns the file
     * name. Throws IllegalStateException with a message for the player when it can't.
     * [rustclearVersion] goes in the User-Agent Modrinth asks every client for.
     *
     * With [replace] (a jar the new one stands in for, as e4all does for e4mc), that jar is deleted
     * first, since both in the mods folder would stop Minecraft starting. Where the running game holds
     * it locked (Windows), the new jar is left as "name.pending" for E4mcSwap to finish after exit.
     */
    public static String install(String project, String minecraft, Path mods, String rustclearVersion, Path replace) {
        HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        String agent = "rustclear/" + rustclearVersion + " (Rustclear Minecraft mod)";
        try {
            String query = "?loaders=" + encode("[\"fabric\"]") + "&game_versions=" + encode("[\"" + minecraft + "\"]");
            HttpResponse<String> list = http.send(request(API.formatted(project) + query, agent), HttpResponse.BodyHandlers.ofString());
            if (list.statusCode() != 200) {
                throw new IllegalStateException("Modrinth didn't answer (" + list.statusCode() + ").");
            }
            JsonObject file = pick(JsonParser.parseString(list.body()).getAsJsonArray(), project, minecraft);
            String url = file.get("url").getAsString();
            String name = file.get("filename").getAsString();
            String sha512 = file.getAsJsonObject("hashes").get("sha512").getAsString();
            if (!url.startsWith(CDN) || !name.endsWith(".jar") || name.contains("/") || name.contains("\\")) {
                throw new IllegalStateException("Modrinth offered an unexpected file.");
            }
            Files.createDirectories(mods);
            Path partial = mods.resolve(name + ".download");
            HttpResponse<InputStream> body = http.send(request(url, agent), HttpResponse.BodyHandlers.ofInputStream());
            if (body.statusCode() != 200) {
                throw new IllegalStateException("The download failed (" + body.statusCode() + ").");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            try (InputStream in = body.body(); var out = Files.newOutputStream(partial)) {
                byte[] buffer = new byte[64 * 1024];
                for (int n; (n = in.read(buffer)) > 0; ) {
                    digest.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                }
            }
            if (!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(sha512)) {
                Files.deleteIfExists(partial);
                throw new IllegalStateException("The download didn't match Modrinth's checksum.");
            }
            if (replace != null) {
                try {
                    Files.deleteIfExists(replace);
                } catch (IOException locked) {
                    Files.move(partial, mods.resolve(name + PENDING), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    return name;
                }
            }
            Files.move(partial, mods.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return name;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Couldn't reach Modrinth. Check your connection.", e);
        }
    }

    /** The newest release (else the newest of any kind) and its primary jar; e4all's Android builds are skipped. */
    private static JsonObject pick(JsonArray versions, String project, String minecraft) {
        JsonObject chosen = null;
        for (JsonElement e : versions) {
            JsonObject v = e.getAsJsonObject();
            if (v.get("version_number").getAsString().contains("android")) {
                continue;
            }
            if ("release".equals(v.get("version_type").getAsString())) {
                chosen = v;
                break;
            }
            if (chosen == null) {
                chosen = v;
            }
        }
        if (chosen == null) {
            throw new IllegalStateException(project + " has no Fabric build for Minecraft " + minecraft + " yet.");
        }
        JsonObject primary = null;
        for (JsonElement f : chosen.getAsJsonArray("files")) {
            JsonObject file = f.getAsJsonObject();
            if (primary == null || file.get("primary").getAsBoolean()) {
                primary = file;
            }
        }
        if (primary == null) {
            throw new IllegalStateException("Modrinth listed " + project + " without a file.");
        }
        return primary;
    }

    private static HttpRequest request(String url, String agent) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", agent).build();
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
