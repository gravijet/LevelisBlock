package net.gravijet.levelblock.hud;

import net.gravijet.levelblock.Mode;
import net.gravijet.levelblock.config.Cfg;
import net.gravijet.levelblock.core.BorderService;
import net.gravijet.levelblock.core.GameState;
import net.gravijet.levelblock.core.RegionService;
import net.gravijet.levelblock.util.Text;
import net.gravijet.levelblock.world.WorldService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The only HUD element: the timer, drawn over the hotbar in a moving colour gradient.
 * <p>
 * No scoreboard and no boss bar - the action bar carries the run on its own. Because the
 * bar is a single line that anything can overwrite, short-lived plugin feedback goes
 * through {@link #flash} instead of being sent directly, so it cannot fight the timer.
 */
public final class ActionBarService {

    private record Flash(Component text, long until) {
    }

    private final Cfg cfg;
    private final GameState state;
    private final RegionService regions;
    private final BorderService border;
    private final WorldService worlds;

    private final Map<UUID, Flash> flashes = new HashMap<>();
    private double phase;

    public ActionBarService(Cfg cfg, GameState state, RegionService regions, BorderService border,
                            WorldService worlds) {
        this.cfg = cfg;
        this.state = state;
        this.regions = regions;
        this.border = border;
        this.worlds = worlds;
    }

    /** Shows {@code text} instead of the timer for a moment. */
    public void flash(Player player, Component text, long millis) {
        flashes.put(player.getUniqueId(), new Flash(text, System.currentTimeMillis() + millis));
        player.sendActionBar(text);
    }

    public void tick() {
        if (!cfg.actionbarEnabled) {
            return;
        }
        // The timer only takes the bar over while a run is going on, so item names and
        // other vanilla action-bar messages still work outside the challenge.
        if (!state.isActive() && !state.isOver()) {
            return;
        }
        if (cfg.actionbarAnimate) {
            phase = (phase + cfg.actionbarSpeed) % 1.0D;
        }
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            // Outside the challenge worlds the action bar stays vanilla.
            if (!worlds.isGameWorld(player.getWorld())) {
                continue;
            }
            Flash flash = flashes.get(player.getUniqueId());
            if (flash != null) {
                if (flash.until() > now) {
                    player.sendActionBar(flash.text());
                    continue;
                }
                flashes.remove(player.getUniqueId());
            }
            player.sendActionBar(build(player));
        }
    }

    private Component build(Player player) {
        long seconds = cfg.countDown
                ? Math.max(0L, cfg.countdownFromSeconds - state.elapsedSeconds())
                : state.elapsedSeconds();

        Component time = Text.gradient(Text.formatTime(seconds), cfg.actionbarGradient,
                cfg.actionbarAnimate ? phase : 0.0D, cfg.actionbarBold);

        String suffix = state.mode() == Mode.LEVEL_BORDER ? cfg.actionbarSuffixBorder : cfg.actionbarSuffixBlock;
        if (suffix.isBlank()) {
            return time;
        }
        return Component.empty().append(time).append(Text.mm(suffix, placeholders(player)));
    }

    private Object[] placeholders(Player player) {
        return new Object[]{
                "credits", state.credits(player.getUniqueId()),
                "blocks", regions.totalColumns(),
                "levels", state.totalLevels(),
                "border", formatSize(border.targetSize()),
                "mode", state.mode().display(),
                "xpmode", state.sharing().display(),
                "status", statusText(),
                "players", Bukkit.getOnlinePlayers().size()
        };
    }

    private String statusText() {
        return switch (state.phase()) {
            case RUNNING -> "Laeuft";
            case PAUSED -> "Pausiert";
            case COUNTDOWN -> "Countdown";
            case FINISHED -> "Beendet";
            case FAILED -> "Gescheitert";
            case IDLE -> "Bereit";
        };
    }

    public static String formatSize(double size) {
        return size == Math.floor(size)
                ? String.valueOf((long) size)
                : String.format(Locale.ROOT, "%.1f", size);
    }

    public void forget(UUID playerId) {
        flashes.remove(playerId);
    }

    public void shutdown() {
        flashes.clear();
    }
}
