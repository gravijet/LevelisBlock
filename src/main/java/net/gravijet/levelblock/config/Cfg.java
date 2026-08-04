package net.gravijet.levelblock.config;

import net.gravijet.levelblock.Mode;
import net.gravijet.levelblock.Sharing;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/**
 * Typed snapshot of {@code config.yml}. Everything is read once per reload so the hot
 * paths (movement checks, barrier rendering) never touch the YAML tree.
 */
public final class Cfg {

    /** Bumped whenever an existing config.yml needs adjusting, see {@link #migrate}. */
    private static final int CONFIG_VERSION = 3;

    private static final String PREFIX_V2 =
            "<gradient:#00E5FF:#7C4DFF><bold>Challenge</bold></gradient> <dark_gray>|</dark_gray> ";
    private static final String RESET_KICK_V2 =
            "<gradient:#00E5FF:#7C4DFF><bold>Challenge</bold></gradient><newline>"
                    + "<gray>Die Welten werden neu generiert.</gray><newline>"
                    + "<white>Komm gleich wieder rein!</white>";

    private final JavaPlugin plugin;

    public Mode defaultMode = Mode.LEVEL_BLOCK;
    public Sharing defaultSharing = Sharing.INDIVIDUAL;

    // general
    public int autosaveSeconds = 60;
    public String prefix = "";
    public boolean creativeBypasses = true;
    public boolean spectatorBypasses = true;
    public boolean playerCollision = false;

    // start
    public int startAreaSize = 3;
    public int countdownSeconds = 5;
    public boolean freezeDuringCountdown = true;
    public boolean resetPlayers = true;
    public boolean forceSurvival = true;
    public boolean preparePlatform = true;
    public Material platformMaterial = Material.STONE;
    public int invulnerableSeconds = 6;
    public int spawnPointDistance = 512;

    // timer
    public boolean countDown = false;
    public long countdownFromSeconds = 3600L;
    public boolean resumeAfterRestart = false;

    // unlock - the price of a block, in XP levels
    public int unlockCost = 1;
    public int costIncreaseEvery = 0;
    public boolean sneakBlocks = true;
    public boolean broadcastUnlock = false;

    // barrier (the red ground line in LEVEL_BLOCK)
    public boolean barrierEnabled = true;
    public Color barrierColor = Color.fromRGB(0xFF1F1F);
    public float barrierParticleSize = 1.0F;
    public int barrierPointsPerBlock = 5;
    public int barrierRenderDistance = 20;
    public int barrierRefreshTicks = 8;
    public int barrierMaxPoints = 3000;
    public int barrierMaxHeight = 12;
    public boolean bumpFeedback = true;

    // border (the world border in LEVEL_BORDER)
    public double borderStartSize = 3.0D;
    public double borderPerLevel = 1.0D;
    public double borderMaxSize = 59_999_968.0D;
    public double borderGrowSeconds = 1.0D;
    public int borderWarningDistance = 0;

    // actionbar
    public boolean actionbarEnabled = true;
    public int actionbarRefreshTicks = 4;
    public List<TextColor> actionbarGradient = List.of(TextColor.color(0x00E5FF), TextColor.color(0x7C4DFF));
    public boolean actionbarAnimate = false;
    public double actionbarSpeed = 0.012D;
    public boolean actionbarBold = true;

    // effects
    public Color themeColor = Color.fromRGB(0x00E5FF);
    public boolean unlockAnimation = true;
    public boolean startAnimation = true;
    public boolean startFireworks = true;
    public float volume = 0.8F;

    // protection
    public boolean restrictBlockEdits = true;
    public boolean restrictExplosions = true;

    // reset
    public boolean resetShutdown = true;

    private FileConfiguration raw;

    public Cfg(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.reloadConfig();
        // Before copyDefaults, so a missing config-version really reads as missing.
        migrate(plugin.getConfig());
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();
        this.raw = plugin.getConfig();
        FileConfiguration c = this.raw;

        defaultMode = Mode.parse(c.getString("mode"), Mode.LEVEL_BLOCK);

        autosaveSeconds = Math.max(10, c.getInt("general.autosave-seconds", 60));
        prefix = c.getString("general.prefix", "");
        creativeBypasses = c.getBoolean("general.creative-bypasses", true);
        spectatorBypasses = c.getBoolean("general.spectator-bypasses", true);
        playerCollision = c.getBoolean("general.player-collision", false);

        startAreaSize = Math.max(1, c.getInt("start.area-size", 3)) | 1; // force odd
        countdownSeconds = Math.max(0, c.getInt("start.countdown-seconds", 5));
        freezeDuringCountdown = c.getBoolean("start.freeze-during-countdown", true);
        resetPlayers = c.getBoolean("start.reset-players", true);
        forceSurvival = c.getBoolean("start.force-survival", true);
        preparePlatform = c.getBoolean("start.prepare-platform", true);
        platformMaterial = material(c.getString("start.platform-material"), Material.STONE, "start.platform-material");
        invulnerableSeconds = (int) clamp(c.getInt("start.invulnerable-seconds", 6), 0, 300);
        spawnPointDistance = (int) clamp(c.getInt("start.spawn-point-distance", 512), 0, 100_000);

        countDown = "COUNT_DOWN".equalsIgnoreCase(c.getString("timer.mode", "COUNT_UP"));
        countdownFromSeconds = Math.max(1L, c.getLong("timer.countdown-from-seconds", 3600L));
        resumeAfterRestart = c.getBoolean("timer.resume-after-restart", false);

        defaultSharing = Sharing.parse(c.getString("progress.sharing"), Sharing.INDIVIDUAL);

        unlockCost = Math.max(0, c.getInt("unlock.cost", 1));
        costIncreaseEvery = Math.max(0, c.getInt("unlock.cost-increase-every", 0));
        sneakBlocks = c.getBoolean("unlock.sneak-blocks", true);
        broadcastUnlock = c.getBoolean("unlock.broadcast", false);

        barrierEnabled = c.getBoolean("barrier.enabled", true);
        barrierColor = color(c.getString("barrier.color"), 0xFF1F1F, "barrier.color");
        barrierParticleSize = (float) clamp(c.getDouble("barrier.particle-size", 1.0D), 0.1D, 4.0D);
        barrierPointsPerBlock = (int) clamp(c.getInt("barrier.points-per-block", 4), 1, 16);
        barrierRenderDistance = (int) clamp(c.getInt("barrier.render-distance", 48), 4, 128);
        barrierRefreshTicks = (int) clamp(c.getInt("barrier.refresh-ticks", 8), 1, 40);
        barrierMaxPoints = (int) clamp(c.getInt("barrier.max-points", 3000), 64, 20_000);
        barrierMaxHeight = (int) clamp(c.getInt("barrier.max-height", 12), 0, 64);
        bumpFeedback = c.getBoolean("barrier.bump-feedback", true);

        borderStartSize = Math.max(1.0D, c.getDouble("border.start-size", 3.0D));
        borderPerLevel = Math.max(0.0D, c.getDouble("border.blocks-per-level", 1.0D));
        borderMaxSize = clamp(c.getDouble("border.max-size", 59_999_968.0D), 1.0D, 59_999_968.0D);
        borderGrowSeconds = clamp(c.getDouble("border.grow-animation-seconds", 1.0D), 0.0D, 60.0D);
        borderWarningDistance = Math.max(0, c.getInt("border.warning-distance", 0));

        actionbarEnabled = c.getBoolean("actionbar.enabled", true);
        actionbarRefreshTicks = (int) clamp(c.getInt("actionbar.refresh-ticks", 4), 1, 40);
        actionbarGradient = palette(c.getStringList("actionbar.gradient"));
        actionbarAnimate = c.getBoolean("actionbar.animate", false);
        actionbarSpeed = clamp(c.getDouble("actionbar.animation-speed", 0.012D), 0.0D, 0.5D);
        actionbarBold = c.getBoolean("actionbar.bold", true);

        themeColor = color(c.getString("effects.color"), 0x00E5FF, "effects.color");
        unlockAnimation = c.getBoolean("effects.unlock-animation", true);
        startAnimation = c.getBoolean("effects.start-animation", true);
        startFireworks = c.getBoolean("effects.start-fireworks", true);
        volume = (float) clamp(c.getDouble("effects.volume", 0.8D), 0.0D, 1.0D);

        restrictBlockEdits = c.getBoolean("protection.restrict-block-edits", true);
        restrictExplosions = c.getBoolean("protection.restrict-explosions", true);

        resetShutdown = c.getBoolean("reset.shutdown-server", true);
    }

    /**
     * Brings a config.yml written by an older build in line with the current one.
     * <p>
     * {@code copyDefaults} only ever <em>adds</em> missing keys, so a value whose meaning
     * changed - the prefix, the timer gradient - would keep its old wording forever on a
     * server that has run the plugin before. Only those few keys are touched, and only
     * when they still hold the value the old build shipped, so a hand-picked prefix
     * survives untouched.
     */
    private void migrate(FileConfiguration c) {
        int version = c.getInt("config-version", 1);
        if (version >= CONFIG_VERSION) {
            return;
        }
        if (version < 2) {
            // The plugin introduces itself as "Challenge" now.
            replaceIfContains(c, "general.prefix", "LevelBlock", PREFIX_V2);
            replaceIfContains(c, "messages.reset-kick", "LevelBlock", RESET_KICK_V2);
            // The timer wears the prefix gradient instead of its own animated ramp.
            c.set("actionbar.gradient", List.of("#00E5FF", "#7C4DFF"));
            c.set("actionbar.animate", false);
            // The action bar carries the clock alone; the numbers moved to /blocks and
            // /border, and the spawn point is moved silently.
            c.set("actionbar.suffix-block", null);
            c.set("actionbar.suffix-border", null);
            c.set("messages.spawn-moved", null);
        }
        if (version < 3) {
            // Credits are gone: a block is paid for with XP levels directly.
            c.set("progress.credits-per-level", null);
            c.set("progress.starting-credits", null);
            c.set("unlock.take-levels", null);
            c.set("messages.not-enough-credits", null);
            c.set("messages.credits-own", null);
            c.set("messages.credits-changed", null);
            // Both overviews were rewritten and the old ones talk about credits.
            c.set("messages.blocks-info", null);
            c.set("messages.border-info", null);
            // The barrier now carries much further and climbs cliffs, which the old
            // render distance and point budget were far too small for.
            raiseAtLeast(c, "barrier.render-distance", 48);
            raiseAtLeast(c, "barrier.max-points", 3000);
        }
        c.set("config-version", CONFIG_VERSION);
        plugin.getLogger().info("config.yml auf Version " + CONFIG_VERSION + " aktualisiert.");
    }

    /** Lifts a value to the new floor but leaves anything already higher alone. */
    private static void raiseAtLeast(FileConfiguration c, String path, int minimum) {
        if (c.isSet(path) && c.getInt(path, minimum) < minimum) {
            c.set(path, minimum);
        }
    }

    private static void replaceIfContains(FileConfiguration c, String path, String marker, String replacement) {
        String current = c.getString(path);
        if (current != null && current.contains(marker)) {
            c.set(path, replacement);
        }
    }

    public String msg(String key) {
        String value = raw.getString("messages." + key);
        return value == null ? "<red>Fehlende Nachricht: " + key + "</red>" : value;
    }

    /** Grace period after the start during which nothing can hurt a player. */
    public long invulnerableMillis() {
        return invulnerableSeconds * 1000L;
    }

    // ------------------------------------------------------------------ parsing

    private List<TextColor> palette(List<String> hexes) {
        List<TextColor> colors = new ArrayList<>(hexes.size());
        for (String hex : hexes) {
            TextColor parsed = TextColor.fromHexString(normalizeHex(hex));
            if (parsed == null) {
                plugin.getLogger().warning("Ungueltige Farbe \"" + hex + "\" bei actionbar.gradient.");
                continue;
            }
            colors.add(parsed);
        }
        if (colors.isEmpty()) {
            return List.of(TextColor.color(0x00E5FF), TextColor.color(0x7C4DFF));
        }
        return List.copyOf(colors);
    }

    private static String normalizeHex(String hex) {
        String value = hex == null ? "" : hex.trim();
        return value.startsWith("#") ? value : "#" + value;
    }

    private Material material(String name, Material fallback, String path) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        Material found = Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
        if (found == null) {
            plugin.getLogger().log(Level.WARNING,
                    "Unbekanntes Material \"{0}\" bei {1} - benutze {2}.",
                    new Object[]{name, path, fallback.name()});
            return fallback;
        }
        return found;
    }

    private Color color(String hex, int fallback, String path) {
        if (hex == null || hex.isBlank()) {
            return Color.fromRGB(fallback);
        }
        try {
            return Color.fromRGB(Integer.parseInt(hex.trim().replace("#", ""), 16) & 0xFFFFFF);
        } catch (NumberFormatException ex) {
            plugin.getLogger().warning("Ungueltige Farbe \"" + hex + "\" bei " + path + ".");
            return Color.fromRGB(fallback);
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
