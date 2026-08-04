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
import net.gravijet.levelblock.util.Xp;
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
    private final Storage storage;
    private final Msg msg;

    private final Set<UUID> bypassing = new HashSet<>();
    /** Death spots of players who still have to respawn into spectator mode. */
    private final Map<UUID, Location> deathSpots = new HashMap<>();

    private BukkitTask countdownTask;
    private boolean frozen;
    /** Wall clock until which nothing may hurt anyone, e.g. right after a start or resume. */
    private long graceUntilMillis;
    /** Guards the XP mirroring in {@link Sharing#SHARED} against feeding back into itself. */
    private boolean syncing;

    public GameService(JavaPlugin plugin, Cfg cfg, GameState state, RegionService regions, WorldService worlds,
                       BorderService border, AnimationService animations, Storage storage, Msg msg) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.state = state;
        this.regions = regions;
        this.worlds = worlds;
        this.border = border;
        this.animations = animations;
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

    /** Grace window around a start or resume where nothing - fireworks included - may hurt. */
    public boolean isProtected() {
        if (state.phase() == GameState.Phase.COUNTDOWN) {
            return true;
        }
        return state.isRunning() && System.currentTimeMillis() < graceUntilMillis;
    }

    private void startGrace() {
        graceUntilMillis = System.currentTimeMillis() + cfg.invulnerableMillis();
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

        // Silently, on purpose: the spawn point only moves so mobs can spawn and so
        // spawn-protection does not lock the play area, which is nothing a player has to
        // read about. See WorldService#relocateWorldSpawn for why it has to happen at all.
        worlds.relocateWorldSpawn(world, spawn.getBlockX(), spawn.getBlockZ());
        worlds.preparePlatform(world, spawn.getBlockX(), spawn.getBlockZ(), cfg.startAreaSize);
        if (state.mode() == Mode.LEVEL_BLOCK) {
            regions.seedArea(world, spawn.getBlockX(), spawn.getBlockZ(), cfg.startAreaSize);
        }
        logSpawnBlockers(world);

        state.phase(GameState.Phase.COUNTDOWN);
        frozen = cfg.freezeDuringCountdown;
        border.reset();
        preparePlayers(spawn);
        border.apply(false);
        runCountdown(world, spawn);
        return true;
    }

    /**
     * Points out server settings that stop mobs from spawning. Console only - the server
     * owner can act on these, a player cannot, so it stays out of the chat. The plugin
     * deliberately changes none of them; they are the owner's call.
     */
    private void logSpawnBlockers(World world) {
        List<String> problems = new java.util.ArrayList<>();
        if (world.getDifficulty() == Difficulty.PEACEFUL) {
            problems.add("Schwierigkeit steht auf PEACEFUL");
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
        if (!problems.isEmpty()) {
            plugin.getLogger().warning("Es spawnen keine Monster: " + String.join(", ", problems) + ".");
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
        startGrace();
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
            player.teleport(spawn);
        }
        if (state.sharing() == Sharing.SHARED) {
            // Seed the pool before the first orb drops, so nobody starts out of step.
            state.teamExperience(cfg.resetPlayers ? 0L : highestExperience());
            pushSharedExperience();
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

    /**
     * Picks the run back up - from a pause as well as from a finished or failed one.
     * <p>
     * Everybody goes back to survival and gets teleported to the start point. After a
     * death that is the whole point: the team is sitting in spectator mode spread across
     * the map, and {@code /timer resume} is what puts them back into the game together.
     */
    public void resume(CommandSender initiator) {
        if (!state.hasAnchor()) {
            msg.send(initiator, "game-not-started");
            return;
        }
        if (state.isRunning()) {
            msg.send(initiator, "game-already-running");
            return;
        }
        cancelCountdown();
        deathSpots.clear();
        state.resumeTimer();
        startGrace();
        border.apply(false);

        Location spot = worlds.safeAnchor();
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setGameMode(GameMode.SURVIVAL);
            player.setFireTicks(0);
            player.setFallDistance(0.0F);
            if (spot != null) {
                // Keep the view direction - being spun around north on every resume is
                // disorienting for no reason.
                Location target = spot.clone();
                target.setYaw(player.getLocation().getYaw());
                target.setPitch(player.getLocation().getPitch());
                player.teleport(target);
            }
            border.attach(player);
        }
        // Everybody moved worlds and game modes just now - hand out the pool again so the
        // team comes back in step.
        pushSharedExperience();
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

    /**
     * What a gained level is worth. In block mode the level <em>is</em> the currency, so
     * there is nothing to pay out - it simply stays on the player until they spend it on a
     * block. In border mode it widens the ring.
     */
    private void awardLevels(Player player, int amount) {
        if (amount <= 0 || !state.isRunning()) {
            return;
        }
        state.addLevels(player.getUniqueId(), player.getName(), amount);

        if (state.mode() == Mode.LEVEL_BORDER) {
            border.grow(amount);
            msg.broadcast("border-grown",
                    "player", player.getName(),
                    "size", ActionBarService.formatSize(border.targetSize()));
        }
    }

    /**
     * A player's own level went up. Only used by {@link Sharing#INDIVIDUAL} - the shared
     * pool counts its levels off the pool total instead, in
     * {@link #addSharedExperience}.
     */
    public void grantLevels(Player player, int amount) {
        if (syncing || state.sharing() == Sharing.SHARED) {
            return;
        }
        awardLevels(player, amount);
    }

    /** Levels this player can spend right now - the pool's when experience is shared. */
    public int availableLevels(Player player) {
        return state.sharing() == Sharing.SHARED
                ? Xp.levelOf(state.teamExperience())
                : player.getLevel();
    }

    /**
     * Pays for something in XP levels.
     * <p>
     * The bar keeps its fill, exactly like an enchanting table: only whole levels are
     * taken, the progress towards the next one stays where it was. With
     * {@link Sharing#SHARED} the pool pays, because the pool is what everybody is looking
     * at.
     *
     * @return {@code false} when there were not enough levels; nothing is taken then
     */
    public boolean spendLevels(Player player, int levels) {
        if (levels <= 0) {
            return true;
        }
        if (state.sharing() == Sharing.SHARED) {
            long total = state.teamExperience();
            int level = Xp.levelOf(total);
            if (level < levels) {
                return false;
            }
            state.teamExperience(Xp.total(level - levels, Xp.progressOf(total)));
            pushSharedExperience();
            return true;
        }
        if (player.getLevel() < levels) {
            return false;
        }
        // getExp() is untouched on purpose - that is the "keeps the bar" part.
        // The level change fires PlayerLevelChangeEvent; the guard keeps that from being
        // read back as earned progress.
        syncing = true;
        try {
            player.setLevel(player.getLevel() - levels);
        } finally {
            syncing = false;
        }
        return true;
    }

    // -------------------------------------------------------- shared experience

    /**
     * Adds picked-up experience to the team pool and hands the new total to everybody.
     * <p>
     * The points go into the pool instead of into the player who walked over the orb, so
     * two people collecting in the same tick cannot overwrite each other's gain. Levels
     * are counted off the pool total as well, which is why this does the payout itself
     * rather than waiting for a level event that only one player would fire.
     */
    public void addSharedExperience(Player source, int points) {
        if (syncing || points <= 0 || state.sharing() != Sharing.SHARED || !state.isRunning()) {
            return;
        }
        long before = state.teamExperience();
        long after = before + points;
        state.teamExperience(after);
        pushSharedExperience();

        int gained = Xp.levelOf(after) - Xp.levelOf(before);
        if (gained > 0) {
            awardLevels(source, gained);
        }
    }

    /** Writes the pool total onto every player inside the challenge worlds. */
    public void pushSharedExperience() {
        if (state.sharing() != Sharing.SHARED) {
            return;
        }
        long total = state.teamExperience();
        syncing = true;
        try {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (worlds.isGameWorld(player.getWorld())) {
                    Xp.apply(player, total);
                }
            }
        } finally {
            syncing = false;
        }
    }

    /**
     * Rebuilds the pool from one player and pushes it back out.
     * <p>
     * Enchanting tables, anvils and {@code /xp} change a level behind the pool's back.
     * Whoever caused it holds the truth afterwards, so their total becomes the new pool
     * total. No level payout here on purpose: this corrects the pool, it does not earn
     * anything.
     */
    public void syncSharedFrom(Player source) {
        if (syncing || state.sharing() != Sharing.SHARED || !worlds.isGameWorld(source.getWorld())) {
            return;
        }
        state.teamExperience(Xp.totalOf(source));
        pushSharedExperience();
    }

    /** Brings a player who just joined or crossed into a shared run in line with the team. */
    public void adoptSharedExperience(Player joining) {
        if (state.sharing() != Sharing.SHARED || !worlds.isGameWorld(joining.getWorld())) {
            return;
        }
        syncing = true;
        try {
            Xp.apply(joining, state.teamExperience());
        } finally {
            syncing = false;
        }
    }

    /** Highest total anybody in the run is carrying, used when seeding the pool. */
    private long highestExperience() {
        long best = 0L;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (worlds.isGameWorld(player.getWorld())) {
                best = Math.max(best, Xp.totalOf(player));
            }
        }
        return best;
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

    /**
     * Nearest legal spot for a position that ended up outside the unlocked area, or
     * {@code null} when this world is not restricted. Used to redirect ender pearls and
     * portal exits instead of swallowing them.
     */
    public Location nearestInside(World world, Location outside) {
        ColumnSet columns = regions.peek(world);
        if (columns == null || columns.isEmpty()) {
            return null;
        }
        OptionalLong nearest = columns.nearest(outside.getBlockX(), outside.getBlockZ());
        if (nearest.isEmpty()) {
            return null;
        }
        int x = Keys.unpackX(nearest.getAsLong());
        int z = Keys.unpackZ(nearest.getAsLong());
        int y = WorldService.standableY(world, x, z, outside.getBlockY());
        return new Location(world, x + 0.5D, y, z + 0.5D, outside.getYaw(), outside.getPitch());
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
        Location target = nearestInside(world, at);
        if (target != null) {
            smoothTeleport(player, target);
        }
    }

    /**
     * Teleport that tries not to feel like one.
     * <p>
     * The view direction is carried over so the camera does not snap, the fall counter is
     * cleared so a rescue cannot turn into fall damage, and leftover momentum is dropped so
     * the player does not immediately get flung back out of the area they were just put
     * into. {@code teleportAsync} loads the target chunk off the main thread, which is what
     * removes the freeze frame a plain teleport can cause.
     */
    public void smoothTeleport(Player player, Location target) {
        Location to = target.clone();
        to.setYaw(player.getLocation().getYaw());
        to.setPitch(player.getLocation().getPitch());
        player.setFallDistance(0.0F);
        player.setVelocity(new org.bukkit.util.Vector(0.0D, 0.0D, 0.0D));
        player.teleportAsync(to);
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
            if (!state.hasBorderSize()) {
                border.reset();
            }
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
        Sharing previous = state.sharing();
        state.sharing(sharing);
        if (sharing != Sharing.SHARED || previous == Sharing.SHARED) {
            return;
        }
        // Switching over must not cost anybody their experience, so the pool starts at
        // whatever the furthest player had and everyone is levelled up to it.
        state.teamExperience(Math.max(state.teamExperience(), highestExperience()));
        pushSharedExperience();
    }

    public void shutdown() {
        cancelCountdown();
    }

    public Component describeStatus() {
        String progress = state.mode() == Mode.LEVEL_BORDER
                ? "<gray>Border <aqua>" + ActionBarService.formatSize(border.targetSize()) + "</aqua></gray>"
                : "<gray>Bloecke <aqua>" + regions.totalColumns() + "</aqua></gray>";
        return Text.mm("<gray>Modus <white>%mode%</white> <dark_gray>|</dark_gray> XP <white>%xpmode%</white>"
                        + " <dark_gray>|</dark_gray> Status <white>%status%</white>"
                        + " <dark_gray>|</dark_gray> Zeit <white>%time%</white>"
                        + " <dark_gray>|</dark_gray> " + progress
                        + " <dark_gray>|</dark_gray> Level <aqua>%levels%</aqua></gray>",
                "mode", state.mode().display(),
                "xpmode", state.sharing().display(),
                "status", statusText(),
                "time", Text.formatTime(state.elapsedSeconds()),
                "levels", state.totalLevels());
    }

    public String statusText() {
        return switch (state.phase()) {
            case RUNNING -> "Laeuft";
            case PAUSED -> "Pausiert";
            case COUNTDOWN -> "Countdown";
            case FINISHED -> "Beendet";
            case FAILED -> "Gescheitert";
            case IDLE -> "Bereit";
        };
    }
}
