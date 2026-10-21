package net.rustclear.mod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** The player's last settings, kept in config/rustclear.json between sessions. */
public final class SettingsStore {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("rustclear.json");

    private SettingsStore() {}

    public static DungeonSettings load() {
        try {
            if (Files.exists(FILE)) {
                DungeonSettings settings = Protocol.GSON.fromJson(Files.readString(FILE), DungeonSettings.class);
                if (settings != null) {
                    return settings;
                }
            }
        } catch (IOException | RuntimeException e) {
            RustclearMod.LOGGER.warn("Couldn't read {}: {}", FILE, e.toString());
        }
        return new DungeonSettings();
    }

    public static void save(DungeonSettings settings) {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, Protocol.GSON.toJson(settings));
        } catch (IOException e) {
            RustclearMod.LOGGER.warn("Couldn't save {}: {}", FILE, e.toString());
        }
    }
}
