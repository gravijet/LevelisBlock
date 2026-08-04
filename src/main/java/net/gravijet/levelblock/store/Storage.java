package net.gravijet.levelblock.store;

import net.gravijet.levelblock.Mode;
import net.gravijet.levelblock.Sharing;
import net.gravijet.levelblock.config.Cfg;
import net.gravijet.levelblock.core.ColumnSet;
import net.gravijet.levelblock.core.GameState;
import net.gravijet.levelblock.core.RegionService;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Persists the game state and the unlocked columns.
 * <p>
 * Regions go into a gzipped binary file instead of YAML: a mature run can hold tens of
 * thousands of columns, which YAML handles badly in both size and parse time. Payloads are
 * built on the main thread and flushed off it, and every write goes through a temp file so
 * a crash mid-save cannot leave a truncated region file behind.
 */
public final class Storage {

    private static final int MAGIC = 0x4C56424B; // "LVBK"
    private static final int FORMAT_VERSION = 1;

    private final JavaPlugin plugin;
    private final Cfg cfg;
    private final GameState state;
    private final RegionService regions;
    private final File dataDir;
    private final File regionDir;

    /** Set by {@code /reset}: the data on disk is about to be thrown away, stop writing. */
    private boolean suspended;

    public Storage(JavaPlugin plugin, Cfg cfg, GameState state, RegionService regions) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.state = state;
        this.regions = regions;
        this.dataDir = new File(plugin.getDataFolder(), "data");
        this.regionDir = new File(dataDir, "regions");
    }

    public void suspend() {
        this.suspended = true;
    }

    /** Throws away everything the plugin stored, used by {@code /reset}. */
    public void wipe() {
        suspend();
        deleteTree(dataDir);
    }

    // ------------------------------------------------------------------- load

    public void load() {
        if (!regionDir.isDirectory() && !regionDir.mkdirs()) {
            plugin.getLogger().warning("Datenordner konnte nicht angelegt werden: " + regionDir);
        }
        loadState();
        loadRegions();
    }

    private void loadState() {
        File file = new File(dataDir, "state.yml");
        if (!file.isFile()) {
            state.mode(cfg.defaultMode);
            state.sharing(cfg.defaultSharing);
            state.clearDirty();
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        state.mode(Mode.parse(yaml.getString("mode"), cfg.defaultMode));
        state.sharing(Sharing.parse(yaml.getString("sharing"), cfg.defaultSharing));
        state.totalLevels(yaml.getLong("total-levels", 0L));
        state.sharedCredits(yaml.getInt("shared-credits", 0));
        state.elapsedSeconds(yaml.getLong("elapsed-seconds", 0L));

        String anchorWorld = yaml.getString("anchor.world");
        if (anchorWorld != null && !anchorWorld.isBlank()) {
            state.anchor(anchorWorld,
                    yaml.getInt("anchor.x", 0),
                    yaml.getInt("anchor.y", 64),
                    yaml.getInt("anchor.z", 0));
        }

        GameState.Phase saved = parsePhase(yaml.getString("phase"));
        // A countdown cannot survive a restart, and an unattended timer should not keep
        // ticking unless the admin asked for it.
        if (saved == GameState.Phase.RUNNING && !cfg.resumeAfterRestart) {
            saved = GameState.Phase.PAUSED;
        } else if (saved == GameState.Phase.COUNTDOWN) {
            saved = GameState.Phase.IDLE;
        }
        state.phase(saved);
        if (saved == GameState.Phase.RUNNING) {
            state.resumeTimer();
        }

        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players != null) {
            for (String rawId : players.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(rawId);
                    state.levelsByPlayer().put(id, players.getLong(rawId + ".levels", 0L));
                    state.creditsByPlayer().put(id, players.getInt(rawId + ".credits", 0));
                    state.putName(id, players.getString(rawId + ".name", "?"));
                } catch (IllegalArgumentException ignored) {
                    // Skip malformed entries rather than failing the whole load.
                }
            }
        }
        state.clearDirty();
    }

    private static GameState.Phase parsePhase(String raw) {
        if (raw == null) {
            return GameState.Phase.IDLE;
        }
        try {
            return GameState.Phase.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return GameState.Phase.IDLE;
        }
    }

    private void loadRegions() {
        File[] files = regionDir.listFiles((dir, name) -> name.endsWith(".bin"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            String worldName = file.getName().substring(0, file.getName().length() - 4);
            try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(file.toPath())));
                 DataInputStream data = new DataInputStream(in)) {

                if (data.readInt() != MAGIC) {
                    plugin.getLogger().warning("Ueberspringe fremde Regionsdatei: " + file.getName());
                    continue;
                }
                int version = data.readInt();
                if (version != FORMAT_VERSION) {
                    plugin.getLogger().warning("Regionsdatei " + file.getName()
                            + " hat Version " + version + ", erwartet " + FORMAT_VERSION + " - wird ignoriert.");
                    continue;
                }
                String storedWorld = data.readUTF();
                int count = data.readInt();
                ColumnSet columns = regions.forName(storedWorld.isEmpty() ? worldName : storedWorld);
                for (int i = 0; i < count; i++) {
                    columns.addRaw(data.readLong());
                }
                columns.rebuildBoundary();
            } catch (IOException ex) {
                plugin.getLogger().log(Level.WARNING,
                        "Regionsdatei " + file.getName() + " konnte nicht gelesen werden.", ex);
            }
        }
    }

    // ------------------------------------------------------------------- save

    public void saveAll(boolean async) {
        if (suspended) {
            return;
        }
        if (!regionDir.isDirectory() && !regionDir.mkdirs()) {
            plugin.getLogger().warning("Datenordner konnte nicht angelegt werden: " + regionDir);
            return;
        }

        String stateYaml = buildStateYaml();
        Map<String, long[]> snapshots = new HashMap<>();
        for (Map.Entry<String, ColumnSet> entry : regions.all().entrySet()) {
            snapshots.put(entry.getKey(), entry.getValue().toArray());
        }

        Runnable write = () -> {
            if (suspended) {
                return;
            }
            writeText(new File(dataDir, "state.yml"), stateYaml);
            List<String> keep = new ArrayList<>();
            for (Map.Entry<String, long[]> entry : snapshots.entrySet()) {
                String fileName = sanitize(entry.getKey()) + ".bin";
                keep.add(fileName);
                writeRegion(new File(regionDir, fileName), entry.getKey(), entry.getValue());
            }
            File[] existing = regionDir.listFiles((dir, name) -> name.endsWith(".bin"));
            if (existing != null) {
                for (File file : existing) {
                    if (!keep.contains(file.getName()) && !file.delete()) {
                        plugin.getLogger().warning("Veraltete Regionsdatei blieb bestehen: " + file.getName());
                    }
                }
            }
        };

        if (async) {
            Bukkit.getAsyncScheduler().runNow(plugin, task -> write.run());
        } else {
            write.run();
        }
        state.clearDirty();
    }

    private String buildStateYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("mode", state.mode().name());
        yaml.set("sharing", state.sharing().name());
        yaml.set("phase", state.phase().name());
        yaml.set("elapsed-seconds", state.elapsedSeconds());
        yaml.set("total-levels", state.totalLevels());
        yaml.set("shared-credits", state.sharedCredits());
        if (state.hasAnchor()) {
            yaml.set("anchor.world", state.anchorWorld());
            yaml.set("anchor.x", state.anchorX());
            yaml.set("anchor.y", state.anchorY());
            yaml.set("anchor.z", state.anchorZ());
        }
        for (Map.Entry<UUID, Long> entry : state.levelsByPlayer().entrySet()) {
            yaml.set("players." + entry.getKey() + ".levels", entry.getValue());
        }
        for (Map.Entry<UUID, Integer> entry : state.creditsByPlayer().entrySet()) {
            yaml.set("players." + entry.getKey() + ".credits", entry.getValue());
        }
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players != null) {
            for (String rawId : players.getKeys(false)) {
                try {
                    yaml.set("players." + rawId + ".name", state.nameOf(UUID.fromString(rawId)));
                } catch (IllegalArgumentException ignored) {
                    // Cannot happen - the keys were written from UUIDs above.
                }
            }
        }
        return yaml.saveToString();
    }

    private void writeText(File target, String content) {
        try {
            Path temp = target.toPath().resolveSibling(target.getName() + ".tmp");
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Konnte " + target.getName() + " nicht speichern.", ex);
        }
    }

    private void writeRegion(File target, String worldName, long[] columns) {
        Path temp = target.toPath().resolveSibling(target.getName() + ".tmp");
        try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(temp));
             GZIPOutputStream gzip = new GZIPOutputStream(raw);
             DataOutputStream out = new DataOutputStream(gzip)) {

            out.writeInt(MAGIC);
            out.writeInt(FORMAT_VERSION);
            out.writeUTF(worldName);
            out.writeInt(columns.length);
            for (long key : columns) {
                out.writeLong(key);
            }
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Konnte Region " + worldName + " nicht speichern.", ex);
            return;
        }
        try {
            Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Konnte Region " + worldName + " nicht ersetzen.", ex);
        }
    }

    public void deleteRegionFile(String worldName) {
        File file = new File(regionDir, sanitize(worldName) + ".bin");
        if (file.isFile() && !file.delete()) {
            plugin.getLogger().warning("Regionsdatei " + file.getName() + " konnte nicht geloescht werden.");
        }
    }

    private void deleteTree(File root) {
        File[] children = root.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        if (root.exists() && !root.delete()) {
            plugin.getLogger().warning("Konnte " + root.getName() + " nicht loeschen.");
        }
    }

    private static String sanitize(String worldName) {
        return worldName.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }
}
