package net.gravijet.levelblock.core;

import net.gravijet.levelblock.Mode;
import net.gravijet.levelblock.config.Cfg;
import net.gravijet.levelblock.fx.AnimationService;
import net.gravijet.levelblock.fx.Fx;
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
 * A purchase costs the credit <em>and</em> the experience level behind it, so the XP bar
 * really starts over after every block instead of quietly carrying on upwards.
 * <p>
 * There is deliberately no rate limit. One move event can only ever buy one column, so
 * walking is already the only throttle there is: keep running into the edge and the area
 * keeps opening up, exactly as fast as you can walk.
 */
public final class UnlockService {

    /** Audio feedback is immediate; the text behind it only repeats rarely. */
    private static final long DENY_SOUND_MILLIS = 1_200L;
    private static final long DENY_TEXT_MILLIS = 8_000L;

    private final Cfg cfg;
    private final GameState state;
    private final RegionService regions;
    private final GameService game;
    private final AnimationService animations;
    private final Msg msg;

    private final Map<UUID, Long> lastDenySound = new HashMap<>();
    private final Map<UUID, Long> lastDenyText = new HashMap<>();

    public UnlockService(Cfg cfg, GameState state, RegionService regions, GameService game,
                         AnimationService animations, Msg msg) {
        this.cfg = cfg;
        this.state = state;
        this.regions = regions;
        this.game = game;
        this.animations = animations;
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
        game.chargeLevels(player, cost);
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

        Long lastSound = lastDenySound.get(id);
        if (lastSound == null || now - lastSound >= DENY_SOUND_MILLIS) {
            lastDenySound.put(id, now);
            Fx.play(player, Fx.NO, cfg.volume * 0.7F, 0.8F);
        }
        // The action bar belongs to the timer, so the reason goes to chat - rarely, because
        // pressing against the edge without credits happens for seconds at a time.
        Long lastText = lastDenyText.get(id);
        if (lastText == null || now - lastText >= DENY_TEXT_MILLIS) {
            lastDenyText.put(id, now);
            player.sendMessage(msg.prefixed("not-enough-credits",
                    "cost", cost, "credits", state.credits(id)));
        }
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
        lastDenySound.remove(playerId);
        lastDenyText.remove(playerId);
    }
}
