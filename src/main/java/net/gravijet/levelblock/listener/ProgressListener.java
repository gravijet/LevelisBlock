package net.gravijet.levelblock.listener;

import net.gravijet.levelblock.Sharing;
import net.gravijet.levelblock.core.GameService;
import net.gravijet.levelblock.core.GameState;
import net.gravijet.levelblock.world.WorldService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLevelChangeEvent;

/**
 * Turns XP levels into unlock credits (block mode) or border growth (border mode).
 * In border mode the levels of every player add up into one shared total.
 */
public final class ProgressListener implements Listener {

    private final GameState state;
    private final GameService game;
    private final WorldService worlds;

    public ProgressListener(GameState state, GameService game, WorldService worlds) {
        this.state = state;
        this.game = game;
        this.worlds = worlds;
    }

    @EventHandler
    public void onLevelChange(PlayerLevelChangeEvent event) {
        if (!state.isRunning() || game.isSyncing()) {
            return;
        }
        if (!worlds.isGameWorld(event.getPlayer().getWorld())) {
            return;
        }
        // Losing levels must not subtract progress the team already earned.
        int gained = event.getNewLevel() - event.getOldLevel();
        if (gained > 0) {
            game.grantLevels(event.getPlayer(), gained);
        } else if (state.sharing() == Sharing.SHARED) {
            // Spending levels counts as well: without this an enchant or an anvil would
            // leave the team on different values and "everybody has the same experience"
            // would only hold until the first enchanting table.
            game.mirrorExperience(event.getPlayer());
        }
    }
}
