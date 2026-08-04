package net.gravijet.levelblock.core;

import net.gravijet.levelblock.Mode;
import net.gravijet.levelblock.config.Cfg;
import net.gravijet.levelblock.fx.AnimationService;
import net.gravijet.levelblock.fx.Fx;
import net.gravijet.levelblock.hud.ActionBarService;
import net.gravijet.levelblock.util.Msg;
import net.kyori.adventure.text.Component;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Buying a column.
 * <p>
 * There is no unlock item and no menu: walking into the barrier <em>is</em> the gesture.
 * {@link net.gravijet.levelblock.listener.ContainmentListener} works out which single
 * column the player is pushing against and offers it here, so a player who clips a corner
 * still only ever pays for - and opens - one block.
 * <p>
 * There is deliberately no rate limit either. One move event can only ever buy one column,
 * so walking is already the only throttle there is: keep running into the edge and the area
 * keeps opening up, exactly as fast as you can walk.
 */
public final class UnlockService {

    private static final long DENY_FEEDBACK_MILLIS = 1200L;

    private final Cfg cfg;
    private final GameState state;
    private final RegionService regions;
    private final AnimationService animations;
    private final ActionBarService actionBar;
    private final Msg msg;

    private final Map<UUID, Long> lastDeny = new HashMap<>();

    public UnlockService(Cfg cfg, GameState state, RegionService regions, AnimationService animations,
                         ActionBarService actionBar, Msg msg) {
        this.cfg = cfg;
        this.state = state;
        this.regions = regions;
        this.animations = animations;
        this.actionBar = actionBar;
        this.msg = msg;
    }

    public int currentCost() {
        int cost = cfg.unlockCost;
        if (cfg.costIncreaseEvery > 0) {
            cost += regions.totalColumns() / cfg.costIncreaseEvery;
        }
        return Math.max(0, cost);
    }

    /**
     * Tries to buy the column the player just walked into.
     *
     * @return {@code true} when the column is now open and the move may go through
     */
    public boolean tryUnlock(Player player, int x, int z) {
        if (state.mode() != Mode.LEVEL_BLOCK || !state.isRunning()) {
            return false;
        }
        if (!player.hasPermission("levelblock.play")) {
            return false;
        }
        // Sneaking is the "I just want to stand at the edge" gesture.
        if (cfg.sneakBlocks && player.isSneaking()) {
            return false;
        }

        UUID id = player.getUniqueId();
        World world = player.getWorld();
        ColumnSet columns = regions.peek(world);
        if (columns == null || columns.isEmpty() || columns.contains(x, z)) {
            return false;
        }

        int cost = currentCost();
        if (!state.spendCredits(id, cost)) {
            deny(player, cost);
            return false;
        }
        unlockColumn(world, x, z);

        Fx.play(player, Fx.BEACON_POWER, cfg.volume, 1.6F);
        if (cfg.broadcastUnlock) {
            Component line = msg.prefixed("unlocked-broadcast",
                    "player", player.getName(), "blocks", regions.totalColumns());
            for (Player online : player.getServer().getOnlinePlayers()) {
                if (!online.equals(player)) {
                    online.sendMessage(line);
                }
            }
        }
        return true;
    }

    private void deny(Player player, int cost) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long previous = lastDeny.get(id);
        if (previous != null && now - previous < DENY_FEEDBACK_MILLIS) {
            return;
        }
        lastDeny.put(id, now);
        actionBar.flash(player, msg.of("not-enough-credits",
                "cost", cost, "credits", state.credits(id)), DENY_FEEDBACK_MILLIS);
        Fx.play(player, Fx.NO, cfg.volume * 0.7F, 0.8F);
    }

    /** Unlocks a column without charging; used by the flow above and by admin commands. */
    public boolean unlockColumn(World world, int x, int z) {
        ColumnSet columns = regions.getOrCreate(world);
        if (!columns.unlock(x, z)) {
            return false;
        }
        animations.unlock(world, x, z);
        state.markDirty();
        return true;
    }

    public void forget(UUID playerId) {
        lastDeny.remove(playerId);
    }
}
