package net.rustclear.mod.online;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Before Minecraft opens its window: ADS needs e4mc (its party relay), so if it isn't in the game it is
 * installed at once, without asking: fetched from Modrinth into the instance's mods folder, then the game
 * closes, since Fabric picks its mods before any mod code runs and a jar added now only loads on the next
 * start ("press Play again"). Only if the download fails does the game start without it. Not in a
 * development run (the dev mods folder isn't a player's).
 *
 * The install shows in a window of its own process (E4mcPrompt), as Fabric Loader does for its own
 * error window: opening Swing inside Minecraft's process clashes with its window on macOS. Only
 * Minecraft's own classes are kept out of here, as nothing of the game is loaded yet.
 *
 * e4mc and Krypton together stop every world from loading, singleplayer included: the player is
 * disconnected while joining. e4all, a fork of e4mc, works with Krypton, so with Krypton installed
 * the question is about e4all instead ([relayProject]), and a game that already has e4mc and Krypton
 * is offered the switch, which replaces e4mc's jar (the two can't be installed together).
 */
public final class E4mcPreLaunch implements PreLaunchEntrypoint {
    private static final Logger LOGGER = LoggerFactory.getLogger("rustclear");

    @Override
    public void onPreLaunch() {
        if (GraphicsEnvironment.isHeadless() || FabricLoader.getInstance().isDevelopmentEnvironment()) {
            return;
        }
        if (kryptonLoaded() && e4mcLoaded() && !e4allLoaded()) {
            Path e4mc = e4mcJar();
            if (e4mc == null) {
                LOGGER.warn("e4mc and Krypton together stop worlds from loading; remove one, or use e4all instead of e4mc");
            } else {
                install(e4mc);
            }
            return;
        }
        if (!e4mcLoaded()) {
            install(null);
        }
    }

    /** Installs [relayProject] (replacing e4mc's jar [replace], if given), then closes the game so it loads next start. */
    private static void install(Path replace) {
        String project = relayProject();
        int choice;
        try {
            choice = ask(project, replace);
        } catch (Exception e) {
            LOGGER.warn("Couldn't install {}: {}", project, e.toString());
            return;
        }
        switch (choice) {
            case E4mcPrompt.INSTALLED -> {
                if (replace != null) {
                    finishSwapAfterExit(replace);
                }
                LOGGER.info("{} installed; closing so it loads on the next start", project);
                System.exit(0);
            }
            default -> {
            }
        }
    }

    /** Whether e4mc, or e4all (which stands in for it), is in this game. */
    public static boolean e4mcLoaded() {
        FabricLoader loader = FabricLoader.getInstance();
        return loader.isModLoaded("e4mc") || loader.isModLoaded("e4mc_minecraft");
    }

    public static boolean e4allLoaded() {
        return FabricLoader.getInstance().isModLoaded("e4all");
    }

    private static boolean kryptonLoaded() {
        return FabricLoader.getInstance().isModLoaded("krypton");
    }

    /** The relay mod to install from Modrinth: e4all when Krypton is installed (e4mc breaks with it), else e4mc. */
    public static String relayProject() {
        return kryptonLoaded() ? "e4all" : "e4mc";
    }

    /** e4mc's jar in the mods folder, or null when it came from anywhere else (then it isn't ours to remove). */
    private static Path e4mcJar() {
        Path mods = FabricLoader.getInstance().getGameDir().resolve("mods").toAbsolutePath().normalize();
        return FabricLoader.getInstance().getModContainer("e4mc")
            .map(c -> c.getOrigin().getPaths())
            .filter(paths -> paths.size() == 1)
            .map(paths -> paths.getFirst().toAbsolutePath().normalize())
            .filter(p -> mods.equals(p.getParent()) && p.getFileName().toString().endsWith(".jar") && Files.isRegularFile(p))
            .orElse(null);
    }

    /**
     * When e4mc's jar was locked and e4all was left pending (E4mcDownload), starts E4mcSwap to put
     * e4all in place once this game has closed.
     */
    private static void finishSwapAfterExit(Path replace) {
        if (!Files.exists(replace)) {
            return;
        }
        try (var files = Files.list(replace.getParent())) {
            Path pending = files.filter(p -> p.getFileName().toString().startsWith("e4all") && p.getFileName().toString().endsWith(".jar" + E4mcDownload.PENDING))
                .findFirst().orElse(null);
            if (pending != null) {
                new ProcessBuilder(java(), "-cp", classpath(), E4mcSwap.class.getName(),
                    String.valueOf(ProcessHandle.current().pid()), replace.toString(), pending.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            }
        } catch (Exception e) {
            LOGGER.warn("Couldn't finish replacing e4mc; delete {} from the mods folder: {}", replace.getFileName(), e.toString());
        }
    }

    public static String minecraftVersion() {
        return FabricLoader.getInstance().getModContainer("minecraft")
            .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("26.3");
    }

    private static String java() {
        return ProcessHandle.current().info().command().orElse("java");
    }

    /** Runs E4mcPrompt as its own process with this game's Java and waits for the install. */
    private static int ask(String project, Path replace) throws Exception {
        String rustclear = FabricLoader.getInstance().getModContainer("engineerclient")
            .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
        List<String> command = new ArrayList<>(List.of(java(), "-cp", classpath(), E4mcPrompt.class.getName(),
            minecraftVersion(), FabricLoader.getInstance().getGameDir().resolve("mods").toString(), rustclear, project));
        if (replace != null) {
            command.add(replace.toString());
        }
        Process process = new ProcessBuilder(command).inheritIO().start();
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.destroy();
            return E4mcPrompt.NOT_NOW;
        }
        return process.exitValue();
    }

    /** ADS's own classes and Gson's: all the window (and E4mcSwap) needs. */
    private static String classpath() {
        Set<String> paths = new LinkedHashSet<>();
        ModContainer mod = FabricLoader.getInstance().getModContainer("engineerclient").orElseThrow();
        for (Path p : mod.getOrigin().getPaths()) {
            paths.add(p.toAbsolutePath().toString());
        }
        for (Class<?> c : new Class<?>[] {E4mcPrompt.class, com.google.gson.Gson.class}) {
            try {
                paths.add(Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().toString());
            } catch (Exception e) {
                LOGGER.debug("No code source for {}", c.getName());
            }
        }
        return String.join(File.pathSeparator, paths);
    }
}
