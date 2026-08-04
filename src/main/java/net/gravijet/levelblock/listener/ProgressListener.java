package net.gravijet.levelblock.listener;

import net.gravijet.levelblock.Sharing;
import net.gravijet.levelblock.core.GameService;
import net.gravijet.levelblock.core.GameState;
import net.gravijet.levelblock.world.WorldService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLevelChangeEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Watches XP levels. In border mode a gained level widens the ring; in block mode the
 * level simply stays on the player until they spend it on a block.
 */
public final class ProgressListener implements Listener {

    private final JavaPlugin plugin;
    private final GameState state;
    private final GameService game;
    private final WorldService worlds;

    public ProgressListener(JavaPlugin plugin, GameState state, GameService game, WorldService worlds) {
        this.plugin = plugin;
        this.state = state;
        this.game = game;
        this.worlds = worlds;
    }

    @EventHandler
    public void onLevelChange(PlayerLevelChangeEvent event) {
        if (!state.isRunning() || game.isSyncing()) {
            return;
        }
        Player player = event.getPlayer();
        if (!worlds.isGameWorld(player.getWorld())) {
            return;
        }
        if (state.sharing() == Sharing.SHARED) {
            resyncPool(player);
            return;
        }
        // Losing levels must not subtract progress the team already earned.
        int gained = event.getNewLevel() - event.getOldLevel();
        if (gained > 0) {
            game.grantLevels(player, gained);
        }
    }

    /**
     * Pulls the shared pool back in line after something changed a level directly:
     * an enchanting table, an anvil, {@code /xp}, another plugin.
     * <p>
     * Deliberately one tick later. Vanilla fires this event from the middle of its own
     * level-up loop, at a point where the bar progress is still an unnormalised
     * intermediate value - reading it here would spread that garbage across the team, which
     * is exactly how levels ended up matching while the bars did not.
     */
    private void resyncPool(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                game.syncSharedFrom(player);
            }
        });
    }
}
