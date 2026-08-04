package net.gravijet.levelblock.world;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Wipes every world of the server and hands the next start a fresh random seed.
 * <p>
 * The deletion runs from a JVM shutdown hook, not from {@code onDisable}: at disable time
 * the server still holds open region files and has not written {@code level.dat} yet, so
 * anything deleted there comes straight back. By the time the shutdown hook runs the world
 * storage is closed and the folders can actually go. {@code server.properties} is rewritten
 * in the same hook so the server cannot overwrite the new seed on its way out.
 */
public final class WorldReset {

    private static final String MARKER_NAME = "pending-reset.txt";

    private final JavaPlugin plugin;
    private boolean armed;

    public WorldReset(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Reports on a reset that was prepared during the previous run, then clears the marker.
     * Called on startup so a failed deletion does not silently look like a successful reset.
     */
    public void reportPreviousReset() {
        File marker = new File(plugin.getDataFolder(), MARKER_NAME);
        if (!marker.isFile()) {
            return;
        }
        List<String> leftovers = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(marker.toPath(), StandardCharsets.UTF_8)) {
                String path = line.trim();
                if (!path.isEmpty() && new File(path).isDirectory()) {
                    leftovers.add(path);
                }
            }
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Reset-Marker konnte nicht gelesen werden.", ex);
        }
        if (leftovers.isEmpty()) {
            plugin.getLogger().info("Weltreset abgeschlossen - die Welten wurden neu generiert.");
        } else {
            plugin.getLogger().severe("Weltreset unvollstaendig! Diese Ordner konnten nicht geloescht "
                    + "werden und muessen von Hand entfernt werden: " + String.join(", ", leftovers));
        }
        if (!marker.delete()) {
            plugin.getLogger().warning("Reset-Marker " + marker.getName() + " konnte nicht geloescht werden.");
        }
    }

    /**
     * Arms the reset. Everything happens after the server has shut down.
     *
     * @return the folders that are going to be deleted, empty when nothing was found
     */
    public List<File> arm() {
        List<File> folders = collectWorldFolders();
        if (folders.isEmpty() || armed) {
            return folders;
        }
        armed = true;

        long seed = new SecureRandom().nextLong();
        File properties = findServerProperties();
        writeMarker(folders);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // The plugin logger is gone at this point, so this goes straight to stdout.
            System.out.println("[Challenge] Loesche Welten fuer den Reset...");
            for (File folder : folders) {
                deleteWithRetries(folder.toPath());
            }
            applySeed(properties, seed);
            System.out.println("[Challenge] Reset vorbereitet - beim naechsten Start werden die "
                    + "Welten mit Seed " + seed + " neu generiert.");
        }, "LevelBlock-WorldReset"));

        return folders;
    }

    // ----------------------------------------------------------------- folders

    /**
     * Every world folder to delete, guarded so nothing outside the world container is
     * touched.
     * <p>
     * Since 1.26 the dimensions of a world live inside it
     * ({@code world/dimensions/minecraft/overworld}) instead of next to it, so
     * {@link World#getWorldFolder()} no longer points at the world root. Comparing that
     * path against the container therefore always failed and every world got skipped.
     * The folder is resolved back to its root here, which also collapses the three
     * dimensions of a 1.26 world into the single folder they actually share.
     */
    private List<File> collectWorldFolders() {
        Path container = canonical(Bukkit.getWorldContainer());
        if (container == null) {
            plugin.getLogger().severe("Weltordner des Servers konnte nicht bestimmt werden.");
            return List.of();
        }
        Map<Path, File> roots = new LinkedHashMap<>();

        for (World world : Bukkit.getWorlds()) {
            Path folder = canonical(world.getWorldFolder());
            Path root = worldRoot(folder, container);
            if (root == null) {
                plugin.getLogger().warning("Welt \"" + world.getName() + "\" liegt ausserhalb des "
                        + "Weltordners und wird beim Reset uebersprungen: " + folder);
                continue;
            }
            if (roots.containsKey(root)) {
                continue;
            }
            File dir = root.toFile();
            if (!looksLikeWorld(dir)) {
                plugin.getLogger().warning("Welt \"" + world.getName() + "\" sieht nicht wie ein "
                        + "Weltordner aus und wird beim Reset uebersprungen: " + root);
                continue;
            }
            roots.put(root, dir);
        }
        return List.copyOf(roots.values());
    }

    /**
     * The direct child of {@code container} that this folder sits in, or {@code null} when
     * the folder is not below the container at all.
     */
    private static Path worldRoot(Path folder, Path container) {
        if (folder == null || !folder.startsWith(container)) {
            return null;
        }
        Path relative = container.relativize(folder);
        if (relative.getNameCount() == 0) {
            // The container itself - deleting that would take the whole server with it.
            return null;
        }
        return container.resolve(relative.getName(0));
    }

    /** A world root holds level.dat; 1.26 keeps the dimension data in a sub-folder. */
    private static boolean looksLikeWorld(File dir) {
        if (!dir.isDirectory()) {
            return false;
        }
        return new File(dir, "level.dat").isFile()
                || new File(dir, "dimensions").isDirectory()
                || new File(dir, "region").isDirectory();
    }

    private static Path canonical(File file) {
        if (file == null) {
            return null;
        }
        try {
            return file.getCanonicalFile().toPath();
        } catch (IOException ex) {
            return file.getAbsoluteFile().toPath().normalize();
        }
    }

    private void writeMarker(List<File> folders) {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.isDirectory() && !dataFolder.mkdirs()) {
            return;
        }
        StringBuilder content = new StringBuilder();
        for (File folder : folders) {
            content.append(folder.getAbsolutePath()).append(System.lineSeparator());
        }
        try {
            Files.writeString(new File(dataFolder, MARKER_NAME).toPath(), content.toString(),
                    StandardCharsets.UTF_8);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Reset-Marker konnte nicht geschrieben werden.", ex);
        }
    }

    /** Region files stay locked for a moment after shutdown, especially on Windows. */
    private static void deleteWithRetries(Path path) {
        for (int attempt = 1; attempt <= 10; attempt++) {
            try {
                deleteRecursively(path);
            } catch (IOException ignored) {
                // Retry below; the final state is checked with Files.exists.
            }
            if (!Files.exists(path)) {
                return;
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        System.err.println("[Challenge] Konnte " + path + " nicht loeschen.");
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // -------------------------------------------------------------- properties

    private File findServerProperties() {
        File inWorkingDir = new File("server.properties").getAbsoluteFile();
        if (inWorkingDir.isFile()) {
            return inWorkingDir;
        }
        File inContainer = new File(Bukkit.getWorldContainer(), "server.properties").getAbsoluteFile();
        if (inContainer.isFile()) {
            return inContainer;
        }
        plugin.getLogger().warning("server.properties nicht gefunden - der neue Seed kann nicht "
                + "gesetzt werden. Die Welten werden trotzdem geloescht.");
        return null;
    }

    /** Rewrites {@code level-seed} in place and leaves every other line untouched. */
    private static void applySeed(File properties, long seed) {
        if (properties == null || !properties.isFile()) {
            return;
        }
        try {
            List<String> lines = new ArrayList<>(
                    Files.readAllLines(properties.toPath(), StandardCharsets.ISO_8859_1));
            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith("level-seed=")) {
                    lines.set(i, "level-seed=" + seed);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                lines.add("level-seed=" + seed);
            }
            Files.write(properties.toPath(), lines, StandardCharsets.ISO_8859_1);
        } catch (IOException ex) {
            System.err.println("[Challenge] server.properties konnte nicht geschrieben werden: "
                    + ex.getMessage());
        }
    }
}
