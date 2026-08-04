package net.gravijet.levelblock.listener;

import net.gravijet.levelblock.Mode;
import net.gravijet.levelblock.Sharing;
import net.gravijet.levelblock.core.BorderService;
import net.gravijet.levelblock.core.CollisionService;
import net.gravijet.levelblock.core.ColumnSet;
import net.gravijet.levelblock.core.GameService;
import net.gravijet.levelblock.core.GameState;
import net.gravijet.levelblock.core.RegionService;
import net.gravijet.levelblock.core.UnlockService;
import net.gravijet.levelblock.world.WorldService;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Session wiring: joins, deaths, respawns and dimension travel. */
public final class PlayerListener implements Listener {

    private final JavaPlugin plugin;
    private final GameState state;
    private final RegionService regions;
    private final GameService game;
    private final UnlockService unlocks;
    private final BorderService border;
    private final WorldService worlds;
    private final CollisionService collisions;
    private final ContainmentListener containment;

    public PlayerListener(JavaPlugin plugin, GameState state, RegionService regions, GameService game,
                          UnlockService unlocks, BorderService border, WorldService worlds,
                          CollisionService collisions, ContainmentListener containment) {
        this.plugin = plugin;
        this.state = state;
        this.regions = regions;
        this.game = game;
        this.unlocks = unlocks;
        this.border = border;
        this.worlds = worlds;
        this.collisions = collisions;
        this.containment = containment;
    }

    // --------------------------------------------------------------- session

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        state.putName(player.getUniqueId(), player.getName());
        border.attach(player);
        collisions.add(player);
        if (state.isActive()) {
            game.adoptSharedExperience(player);
            game.rescue(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        unlocks.forget(player.getUniqueId());
        containment.forget(player.getUniqueId());
        game.bypassing().remove(player.getUniqueId());
    }

    // ------------------------------------------------------------------ death

    /**
     * One death ends the run. The player keeps everything they were carrying - the
     * challenge is over, so there is nothing to punish them for any more.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!state.isActive() || game.isExempt(player) || !worlds.isGameWorld(player.getWorld())) {
            return;
        }
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.setShouldDropExperience(false);
        game.fail(player);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Location deathSpot = game.takeDeathSpot(player.getUniqueId());
        if (deathSpot != null) {
            // Failed run: come back where you fell, as a spectator, with your stuff.
            event.setRespawnLocation(deathSpot);
            Bukkit.getScheduler().runTask(plugin, () -> player.setGameMode(GameMode.SPECTATOR));
            return;
        }
        Location anchor = worlds.anchor();
        if (anchor == null) {
            return;
        }
        // Without a bed vanilla respawns at the world spawn - which sits far outside the
        // play area on purpose, see WorldService#relocateWorldSpawn. Send them home instead.
        if (!event.isBedSpawn() && !event.isAnchorSpawn()) {
            event.setRespawnLocation(anchor);
            return;
        }
        if (state.mode() != Mode.LEVEL_BLOCK || !state.isActive()) {
            return;
        }
        Location respawn = event.getRespawnLocation();
        ColumnSet columns = regions.peek(respawn.getWorld());
        if (columns == null || columns.isEmpty()
                || columns.contains(respawn.getBlockX(), respawn.getBlockZ())) {
            return;
        }
        // A bed outside the unlocked area would drop them straight back out of bounds.
        event.setRespawnLocation(anchor);
    }

    // --------------------------------------------------------- shared XP pool

    /**
     * Orbs only ever land on the player who walked over them. In {@link Sharing#SHARED}
     * the points belong to the team, so they are taken out of the individual pickup and
     * added to the pool, which then hands the same total to everybody.
     * <p>
     * Redirecting the points instead of copying the picker's values afterwards is what
     * makes the bar match as well as the level: the pool is a single number that level,
     * progress and point count are all derived from, and two players collecting in the
     * same tick both add to it instead of overwriting each other.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExpChange(PlayerExpChangeEvent event) {
        if (state.sharing() != Sharing.SHARED || !state.isRunning() || game.isSyncing()) {
            return;
        }
        Player player = event.getPlayer();
        if (!worlds.isGameWorld(player.getWorld())) {
            return;
        }
        int amount = event.getAmount();
        if (amount <= 0) {
            return;
        }
        event.setAmount(0);
        game.addSharedExperience(player, amount);
    }

    // ------------------------------------------------------------ dimensions

    @EventHandler(ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (state.mode() != Mode.LEVEL_BLOCK || !worlds.isGameWorld(event.getFrom().getWorld())) {
            return;
        }
        Location to = event.getTo();
        if (to == null || to.getWorld() == null || !worlds.isGameWorld(to.getWorld())) {
            return;
        }
        ColumnSet columns = regions.peek(to.getWorld());
        if (columns == null || columns.isEmpty()) {
            // First visit: the arrival point becomes the beachhead, seeded on arrival.
            return;
        }
        if (columns.contains(to.getBlockX(), to.getBlockZ())) {
            return;
        }
        Location inside = game.nearestInside(to.getWorld(), to);
        if (inside != null) {
            event.setTo(inside);
        }
    }

    /**
     * A world border is stored in {@code level.dat} and survives restarts, and it keeps mobs
     * from spawning outside itself. The plugin never puts one on a world - players get a
     * personal border instead - so a leftover from an older setup gets cleared on load.
     */
    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        border.clearWorldBorders();
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        World world = player.getWorld();
        border.attach(player);
        if (!worlds.isGameWorld(world) || !state.isActive()) {
            return;
        }
        game.ensureRegionFor(world, player.getLocation());
        game.rescue(player);
        // The pool only reaches the challenge worlds, so somebody walking back in from a
        // lobby has to be brought up to the team total again.
        game.adoptSharedExperience(player);
    }
}
