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
 * Before Minecraft opens its window: if e4mc (Rustclear's relay, an optional mod) isn't installed,
 * ask whether to install it. Install fetches it from Modrinth into the mods folder and then closes
 * the game, since mods only load at start-up ("press Play again"); Not now carries on; Don't ask
 * again carries on and remembers it in config/rustclear-e4mc.txt.
 *
 * The question is a window of its own process (E4mcPrompt), as Fabric Loader does for its own
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
    private static final String DONT_ASK = "rustclear-e4mc.txt";
    private static final String DONT_ASK_SWITCH = "rustclear-e4all.txt";

    @Override
    public void onPreLaunch() {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        if (kryptonLoaded() && e4mcLoaded() && !e4allLoaded()) {
            Path e4mc = e4mcJar();
            if (e4mc == null) {
                LOGGER.warn("e4mc and Krypton together stop worlds from loading; remove one, or use e4all instead of e4mc");
            } else if (!dontAsk(DONT_ASK_SWITCH)) {
                prompt(e4mc, DONT_ASK_SWITCH);
            }
            return;
        }
        if (!e4mcLoaded() && !dontAsk(DONT_ASK)) {
            prompt(null, DONT_ASK);
        }
    }

    /** Asks about installing [relayProject] (replacing e4mc's jar [replace], if given) and acts on the answer. */
    private static void prompt(Path replace, String dontAskFile) {
        String project = relayProject();
        int choice;
        try {
            choice = ask(project, replace);
        } catch (Exception e) {
            LOGGER.warn("Couldn't ask about installing {}: {}", project, e.toString());
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
            case E4mcPrompt.DONT_ASK -> {
                try {
                    Files.writeString(configDir().resolve(dontAskFile), "ADS won't offer to install " + project + " at start-up. Delete this file to be asked again.\n");
                } catch (Exception e) {
                    LOGGER.warn("Couldn't remember the {} choice: {}", project, e.toString());
                }
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

    private static Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    private static boolean dontAsk(String file) {
        return Files.exists(configDir().resolve(file));
    }

    private static String java() {
        return ProcessHandle.current().info().command().orElse("java");
    }

    /** Runs E4mcPrompt as its own process with this game's Java and waits for the player's choice. */
    private static int ask(String project, Path replace) throws Exception {
        String rustclear = FabricLoader.getInstance().getModContainer("rustclear")
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

    /** Rustclear's own classes and Gson's: all the prompt (and E4mcSwap) needs. */
    private static String classpath() {
        Set<String> paths = new LinkedHashSet<>();
        ModContainer mod = FabricLoader.getInstance().getModContainer("rustclear").orElseThrow();
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
