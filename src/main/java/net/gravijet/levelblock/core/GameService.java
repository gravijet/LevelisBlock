package net.gravijet.levelblock.core;

import net.gravijet.levelblock.Mode;
import net.gravijet.levelblock.Sharing;
import net.gravijet.levelblock.config.Cfg;
import net.gravijet.levelblock.fx.AnimationService;
import net.gravijet.levelblock.fx.Fx;
import net.gravijet.levelblock.hud.ActionBarService;
import net.gravijet.levelblock.store.Storage;
import net.gravijet.levelblock.util.Keys;
import net.gravijet.levelblock.util.Msg;
import net.gravijet.levelblock.util.Text;
import net.gravijet.levelblock.world.WorldService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/** Start, stop, fail and level payouts - the glue between all the other services. */
public final class GameService {

    private static final Title.Times COUNTDOWN_TIMES =
            Title.Times.times(Duration.ZERO, Duration.ofMillis(900L), Duration.ofMillis(250L));
    private static final Title.Times BANNER_TIMES =
            Title.Times.times(Duration.ofMillis(200L), Duration.ofSeconds(4L), Duration.ofSeconds(1L));

    private final JavaPlugin plugin;
    private final Cfg cfg;
    private final GameState state;
    private final RegionService regions;
    private final WorldService worlds;
    private final BorderService border;
    private final AnimationService animations;
    private final ActionBarService actionBar;
    private final Storage storage;
    private final Msg msg;

    private final Set<UUID> bypassing = new HashSet<>();
    /** Death spots of players who still have to respawn into spectator mode. */
    private final Map<UUID, Location> deathSpots = new HashMap<>();

    private BukkitTask countdownTask;
    private boolean frozen;
    /** Guards the XP mirroring in {@link Sharing#SHARED} against feeding back into itself. */
    private boolean syncing;

    public GameService(JavaPlugin plugin, Cfg cfg, GameState state, RegionService regions, WorldService worlds,
                       BorderService border, AnimationService animations, ActionBarService actionBar,
                       Storage storage, Msg msg) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.state = state;
        this.regions = regions;
        this.worlds = worlds;
        this.border = border;
        this.animations = animations;
        this.actionBar = actionBar;
        this.storage = storage;
        this.msg = msg;
    }

    // ------------------------------------------------------------------ state

    public boolean isFrozen() {
        return frozen;
    }

    public Set<UUID> bypassing() {
        return bypassing;
    }

    public boolean isExempt(Player player) {
        GameMode mode = player.getGameMode();
        if (cfg.spectatorBypasses && mode == GameMode.SPECTATOR) {
            return true;
        }
        if (cfg.creativeBypasses && mode == GameMode.CREATIVE) {
            return true;
        }
        return bypassing.contains(player.getUniqueId());
    }

    /** Grace window around the start where nothing - fireworks included - may hurt anyone. */
    public boolean isProtected() {
        if (state.phase() == GameState.Phase.COUNTDOWN) {
            return true;
        }
        return state.isRunning() && state.elapsedMillis() < cfg.invulnerableMillis();
    }

    // ------------------------------------------------------------------ start

    /** Fresh run anchored on the initiator: their spot becomes spawn and the centre. */
    public boolean start(Player initiator) {
        if (state.isActive()) {
            msg.send(initiator, "game-already-running");
            return false;
        }
        Location spawn = worlds.groundedAt(initiator.getLocation());
        World world = spawn.getWorld();

        resetProgressInternal();
        state.anchor(world.getName(), spawn.getBlockX(), spawn.getBlockY(), spawn.getBlockZ());

        Location movedSpawn = worlds.relocateWorldSpawn(world, spawn.getBlockX(), spawn.getBlockZ());
        if (movedSpawn != null) {
            plugin.getLogger().info("Weltspawn auf " + movedSpawn.getBlockX() + "/" + movedSpawn.getBlockZ()
                    + " verschoben, damit im Spielgebiet Mobs spawnen koennen und der "
                    + "spawn-protection-Radius dort nicht greift.");
            msg.send(initiator, "spawn-moved",
                    "x", movedSpawn.getBlockX(), "z", movedSpawn.getBlockZ());
        }
        worlds.preparePlatform(world, spawn.getBlockX(), spawn.getBlockZ(), cfg.startAreaSize);
        if (state.mode() == Mode.LEVEL_BLOCK) {
            regions.seedArea(world, spawn.getBlockX(), spawn.getBlockZ(), cfg.startAreaSize);
        }
        warnAboutSpawnBlockers(initiator, world);

        state.phase(GameState.Phase.COUNTDOWN);
        frozen = cfg.freezeDuringCountdown;
        preparePlayers(spawn);
        border.apply(false);
        runCountdown(world, spawn);
        return true;
    }

    /**
     * Points out server settings that stop mobs from spawning. The plugin deliberately does
     * not change any of them - they are the server owner's call - but a challenge in which
     * nothing ever spawns looks like a broken plugin, so it says so out loud instead.
     */
    private void warnAboutSpawnBlockers(Player initiator, World world) {
        List<String> problems = new ArrayList<>();
        if (world.getDifficulty() == Difficulty.PEACEFUL) {
            problems.add("Schwierigkeit steht auf PEACEFUL - es spawnen keine Monster");
        }
        if (!world.getAllowMonsters()) {
            problems.add("spawn-monsters=false in der server.properties");
        }
        if (Boolean.FALSE.equals(world.getGameRuleValue(GameRules.SPAWN_MOBS))) {
            problems.add("Gameregel spawnMobs ist aus");
        }
        if (Boolean.FALSE.equals(world.getGameRuleValue(GameRules.SPAWN_MONSTERS))) {
            problems.add("Gameregel spawnMonsters ist aus");
        }
        if (problems.isEmpty()) {
            return;
        }
        for (String problem : problems) {
            plugin.getLogger().warning("Achtung: " + problem + ".");
            initiator.sendMessage(Text.mm("<gold>Achtung:</gold> <gray>%problem%.</gray>",
                    "problem", problem));
        }
    }

    private void runCountdown(World world, Location spawn) {
        int total = cfg.countdownSeconds;
        if (total <= 0) {
            beginRun(world, spawn);
            return;
        }
        countdownTask = new BukkitRunnable() {
            private int remaining = total;

            @Override
            public void run() {
                if (state.phase() != GameState.Phase.COUNTDOWN) {
                    cancel();
                    return;
                }
                if (remaining <= 0) {
                    cancel();
                    countdownTask = null;
                    beginRun(world, spawn);
                    return;
                }
                Title title = Title.title(
                        msg.of("countdown-title", "n", remaining),
                        msg.of("countdown-subtitle"),
                        COUNTDOWN_TIMES);
                for (Player player : Bukkit.getOnlinePlayers()) {
                    player.showTitle(title);
                }
                animations.countdownPulse(world, spawn, remaining, total);
                remaining--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void beginRun(World world, Location spawn) {
        frozen = false;
        state.startTimer();
        border.apply(false);

        Title title = Title.title(
                msg.of("start-title", "mode", state.mode().display()),
                msg.of("start-subtitle"),
                BANNER_TIMES);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showTitle(title);
            Fx.play(player, Fx.LEVEL_UP, cfg.volume, 1.2F);
        }
        animations.start(world, spawn);
        msg.broadcast("game-started");
        storage.saveAll(true);
    }

    private void preparePlayers(Location spawn) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (cfg.resetPlayers) {
                player.getInventory().clear();
                player.setLevel(0);
                player.setExp(0.0F);
                player.setTotalExperience(0);
                player.setFoodLevel(20);
                player.setSaturation(5.0F);
                player.setFireTicks(0);
                player.setFallDistance(0.0F);
                for (PotionEffect effect : List.copyOf(player.getActivePotionEffects())) {
                    player.removePotionEffect(effect.getType());
                }
                AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
                player.setHealth(maxHealth != null ? maxHealth.getValue() : 20.0D);
            }
            if (cfg.forceSurvival && player.getGameMode() != GameMode.CREATIVE) {
                player.setGameMode(GameMode.SURVIVAL);
            }
            state.credits(player.getUniqueId(), cfg.startingCredits);
            player.teleport(spawn);
        }
    }

    // ------------------------------------------------------- stop/pause/resume

    public void stop(CommandSender initiator) {
        if (!state.isActive()) {
            msg.send(initiator, "game-not-running");
            return;
        }
        cancelCountdown();
        state.stopTimer(GameState.Phase.FINISHED);
        border.release();
        msg.broadcast("game-stopped");
        storage.saveAll(true);
    }

    public void pause(CommandSender initiator) {
        if (!state.isRunning()) {
            msg.send(initiator, "game-not-running");
            return;
        }
        state.pauseTimer();
        msg.broadcast("game-paused");
        storage.saveAll(true);
    }

    public void resume(CommandSender initiator) {
        if (state.phase() != GameState.Phase.PAUSED) {
            msg.send(initiator, "game-not-running");
            return;
        }
        state.resumeTimer();
        msg.broadcast("game-resumed");
        storage.saveAll(true);
    }

    private void cancelCountdown() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
        frozen = false;
    }

    // ------------------------------------------------------------------- fail

    /**
     * One death ends the run for everybody. The dead player keeps their inventory, gets
     * respawned on the spot and - like everyone else - is put into spectator mode.
     */
    public void fail(Player dead) {
        if (!state.isActive()) {
            return;
        }
        cancelCountdown();
        state.stopTimer(GameState.Phase.FAILED);
        deathSpots.put(dead.getUniqueId(), dead.getLocation().clone());

        Title title = Title.title(
                msg.of("failed-title"),
                msg.of("failed-subtitle", "player", dead.getName(), "time",
                        Text.formatTime(state.elapsedSeconds()), "blocks", regions.totalColumns()),
                BANNER_TIMES);

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showTitle(title);
            Fx.play(player, Fx.ANVIL_LAND, cfg.volume, 0.6F);
            if (!player.equals(dead)) {
                player.setGameMode(GameMode.SPECTATOR);
            }
        }
        msg.broadcast("failed-chat", "player", dead.getName(),
                "time", Text.formatTime(state.elapsedSeconds()),
                "blocks", regions.totalColumns());

        border.release();
        World world = worlds.anchorWorld();
        Location anchor = worlds.anchor();
        if (world != null && anchor != null) {
            animations.failed(world, anchor);
        }

        // Skip the death screen: respawn on the next tick, then hand out spectator mode.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (dead.isOnline() && dead.isDead()) {
                dead.spigot().respawn();
            }
        });
        storage.saveAll(true);
    }

    /** Death spot of a player who is respawning into a failed run, consumed on read. */
    public Location takeDeathSpot(UUID playerId) {
        return deathSpots.remove(playerId);
    }

    /** Countdown-timer expiry, checked once per second by the main loop. */
    public void checkTimerExpiry() {
        if (!cfg.countDown || !state.isRunning()) {
            return;
        }
        if (state.elapsedSeconds() < cfg.countdownFromSeconds) {
            return;
        }
        state.stopTimer(GameState.Phase.FINISHED);
        border.release();

        Title title = Title.title(
                msg.of("timer-expired-title"),
                msg.of("timer-expired-subtitle", "blocks", regions.totalColumns()),
                BANNER_TIMES);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showTitle(title);
        }
        World world = worlds.anchorWorld();
        Location anchor = worlds.anchor();
        if (world != null && anchor != null) {
            animations.finish(world, anchor);
        }
        storage.saveAll(true);
    }

    // ------------------------------------------------------------------ reset

    public void resetGame(CommandSender initiator) {
        cancelCountdown();
        resetProgressInternal();
        border.release();
        msg.send(initiator, "reset-game");
        storage.saveAll(true);
    }

    private void resetProgressInternal() {
        for (String worldName : Set.copyOf(regions.all().keySet())) {
            storage.deleteRegionFile(worldName);
        }
        regions.clear();
        deathSpots.clear();
        state.resetProgress();
    }

    // ------------------------------------------------------------ level payout

    /** Called when a player's XP level goes up. */
    public void grantLevels(Player player, int amount) {
        if (syncing || amount <= 0 || !state.isRunning()) {
            return;
        }
        state.addLevels(player.getUniqueId(), player.getName(), amount);

        if (state.mode() == Mode.LEVEL_BLOCK) {
            int gained = amount * cfg.creditsPerLevel;
            if (gained > 0) {
                state.addCredits(player.getUniqueId(), gained);
                Fx.play(player, Fx.ORB_PICKUP, cfg.volume, 1.5F);
            }
        } else {
            border.grow();
            msg.broadcast("border-grown",
                    "player", player.getName(),
                    "size", ActionBarService.formatSize(border.targetSize()));
        }
        if (state.sharing() == Sharing.SHARED) {
            mirrorExperience(player);
        }
    }

    /**
     * Copies one player's experience onto everybody else. Used by {@link Sharing#SHARED},
     * where the whole team is meant to always sit on exactly the same XP.
     */
    public void mirrorExperience(Player source) {
        if (syncing || state.sharing() != Sharing.SHARED) {
            return;
        }
        int level = source.getLevel();
        float progress = source.getExp();
        syncing = true;
        try {
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (other.equals(source) || !worlds.isGameWorld(other.getWorld())) {
                    continue;
                }
                if (other.getLevel() != level) {
                    other.setLevel(level);
                }
                if (Math.abs(other.getExp() - progress) > 0.0001F) {
                    other.setExp(progress);
                }
            }
        } finally {
            syncing = false;
        }
    }

    /** Brings a player who just joined a shared run in line with the rest of the team. */
    public void adoptSharedExperience(Player joining) {
        if (state.sharing() != Sharing.SHARED || !state.isActive()) {
            return;
        }
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(joining) || !worlds.isGameWorld(other.getWorld())) {
                continue;
            }
            syncing = true;
            try {
                joining.setLevel(other.getLevel());
                joining.setExp(other.getExp());
            } finally {
                syncing = false;
            }
            return;
        }
    }

    public boolean isSyncing() {
        return syncing;
    }

    // ------------------------------------------------------------- containment

    /**
     * Makes sure a world the player just entered has a starting area. This is what lets the
     * nether and the end join the challenge on first arrival.
     */
    public void ensureRegionFor(World world, Location around) {
        if (state.mode() != Mode.LEVEL_BLOCK || !worlds.isGameWorld(world)) {
            return;
        }
        ColumnSet columns = regions.peek(world);
        if (columns != null && !columns.isEmpty()) {
            return;
        }
        regions.seedArea(world, around.getBlockX(), around.getBlockZ(), cfg.startAreaSize);
        worlds.preparePlatform(world, around.getBlockX(), around.getBlockZ(), cfg.startAreaSize);
        state.markDirty();
    }

    /** Pulls a player back into the unlocked area, e.g. after a login outside it. */
    public void rescue(Player player) {
        if (state.mode() != Mode.LEVEL_BLOCK || !state.isActive() || isExempt(player)) {
            return;
        }
        World world = player.getWorld();
        ColumnSet columns = regions.peek(world);
        if (columns == null || columns.isEmpty()) {
            return;
        }
        Location at = player.getLocation();
        if (columns.contains(at.getBlockX(), at.getBlockZ())) {
            return;
        }
        OptionalLong nearest = columns.nearest(at.getBlockX(), at.getBlockZ());
        if (nearest.isEmpty()) {
            return;
        }
        int x = Keys.unpackX(nearest.getAsLong());
        int z = Keys.unpackZ(nearest.getAsLong());
        player.teleport(new Location(world, x + 0.5D, safeY(world, x, z, at.getBlockY()), z + 0.5D,
                at.getYaw(), at.getPitch()));
    }

    /**
     * Keeps the player at their own height when there is room for them there. Someone who
     * gets pushed out of a mine shaft should land back in the shaft, not on the surface.
     */
    private static int safeY(World world, int x, int z, int wantedY) {
        int y = Math.max(world.getMinHeight() + 1, Math.min(world.getMaxHeight() - 2, wantedY));
        if (world.getBlockAt(x, y, z).isPassable()
                && world.getBlockAt(x, y + 1, z).isPassable()
                && !world.getBlockAt(x, y - 1, z).getType().isAir()) {
            return y;
        }
        return world.getHighestBlockYAt(x, z) + 1;
    }

    /** Safety net for everything a move event cannot see: pearls, pistons, plugin pushes. */
    public void enforceAll() {
        if (!state.isActive()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isExempt(player)) {
                continue;
            }
            if (state.mode() == Mode.LEVEL_BLOCK) {
                rescue(player);
            } else {
                border.enforce(player);
            }
        }
    }

    // ----------------------------------------------------------------- config

    public void switchMode(Mode mode) {
        state.mode(mode);
        if (mode == Mode.LEVEL_BORDER) {
            border.apply(false);
            return;
        }
        border.release();
        // Switching into block mode mid-run would otherwise leave every world without a
        // region, and a region-less world is unrestricted - the challenge would do nothing.
        World world = worlds.anchorWorld();
        Location anchor = worlds.anchor();
        if (world != null && anchor != null && state.isActive()) {
            ensureRegionFor(world, anchor);
        }
    }

    public void switchSharing(Sharing sharing) {
        state.sharing(sharing);
    }

    public void shutdown() {
        cancelCountdown();
    }

    public Component describeStatus() {
        return Text.mm("<gray>Modus <white>%mode%</white> <dark_gray>|</dark_gray> XP <white>%xpmode%</white>"
                        + " <dark_gray>|</dark_gray> Status <white>%status%</white>"
                        + " <dark_gray>|</dark_gray> Zeit <white>%time%</white>"
                        + " <dark_gray>|</dark_gray> Bloecke <aqua>%blocks%</aqua>"
                        + " <dark_gray>|</dark_gray> Level <aqua>%levels%</aqua></gray>",
                "mode", state.mode().display(),
                "xpmode", state.sharing().display(),
                "status", state.phase().name(),
                "time", Text.formatTime(state.elapsedSeconds()),
                "blocks", regions.totalColumns(),
                "levels", state.totalLevels());
    }
}
