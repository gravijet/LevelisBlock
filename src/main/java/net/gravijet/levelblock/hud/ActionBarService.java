package net.gravijet.levelblock.hud;

import net.gravijet.levelblock.config.Cfg;
import net.gravijet.levelblock.core.GameState;
import net.gravijet.levelblock.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * The only HUD element: the timer, drawn over the hotbar in the same colour gradient the
 * chat prefix uses.
 * <p>
 * The action bar carries the clock and nothing else - no levels, no block count, no
 * plugin feedback. Everything else has a command ({@code /blocks}, {@code /border},
 * {@code /lb}), which keeps the bar readable and stops messages from fighting the timer.
 */
public final class ActionBarService {

    private final Cfg cfg;
    private final GameState state;

    private double phase;

    public ActionBarService(Cfg cfg, GameState state) {
        this.cfg = cfg;
        this.state = state;
    }

    public void tick() {
        if (!cfg.actionbarEnabled) {
            return;
        }
        // Outside a run the bar stays vanilla, so item names still show up normally.
        if (!state.isActive() && !state.isOver()) {
            return;
        }
        if (cfg.actionbarAnimate) {
            phase = (phase + cfg.actionbarSpeed) % 1.0D;
        }
        Component timer = build();
        // Deliberately every world: the timer belongs to the run, not to a place.
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendActionBar(timer);
        }
    }

    private Component build() {
        long seconds = cfg.countDown
                ? Math.max(0L, cfg.countdownFromSeconds - state.elapsedSeconds())
                : state.elapsedSeconds();

        return Text.gradient(Text.formatTime(seconds), cfg.actionbarGradient,
                cfg.actionbarAnimate ? phase : 0.0D, cfg.actionbarBold);
    }

    public static String formatSize(double size) {
        return size == Math.floor(size)
                ? String.valueOf((long) size)
                : String.format(Locale.ROOT, "%.1f", size);
    }
}
