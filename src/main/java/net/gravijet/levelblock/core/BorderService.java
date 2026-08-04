package net.gravijet.levelblock.core;

import net.gravijet.levelblock.Mode;
import net.gravijet.levelblock.config.Cfg;
import net.gravijet.levelblock.fx.AnimationService;
import net.gravijet.levelblock.world.WorldService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Drives the world border for {@link Mode#LEVEL_BORDER}.
 * <p>
 * The border is a <em>per-player</em> border ({@link Player#setWorldBorder}), never the
 * world's own one. That is what keeps the challenge off the rest of the world: the level
 * border stays at its vanilla size, so mobs spawn and walk outside the ring exactly as
 * they normally would, while the players are the only ones who cannot leave it.
 */
public final class BorderService {

    /** Damage is off - the client already refuses to walk through, so it would only be noise. */
    private static final double NO_DAMAGE = 0.0D;
    /** How far a player may drift past the border before they get pulled back. */
    private static final double SLACK = 1.5D;

    private final Cfg cfg;
    private final GameState state;
    private final WorldService worlds;
    private final AnimationService animations;

    private WorldBorder virtual;

    public BorderService(Cfg cfg, GameState state, WorldService worlds, AnimationService animations) {
        this.cfg = cfg;
        this.state = state;
        this.worlds = worlds;
        this.animations = animations;
    }

    public double targetSize() {
        double size = cfg.borderStartSize + state.totalLevels() * cfg.borderPerLevel;
        return Math.min(cfg.borderMaxSize, Math.max(1.0D, size));
    }

    public boolean enabled() {
        return state.mode() == Mode.LEVEL_BORDER && state.isActive() && state.hasAnchor();
    }

    // ------------------------------------------------------------------ apply

    /** Pushes the current size to every player inside the challenge worlds. */
    public void apply(boolean animated) {
        clearWorldBorders();
        if (!enabled()) {
            release();
            return;
        }
        WorldBorder border = border();
        border.setCenter(state.anchorX() + 0.5D, state.anchorZ() + 0.5D);
        border.setWarningDistance(cfg.borderWarningDistance);
        border.setDamageAmount(NO_DAMAGE);
        border.setDamageBuffer(0.0D);

        double size = targetSize();
        long ticks = animated ? Math.max(0L, (long) (cfg.borderGrowSeconds * 20.0D)) : 0L;
        border.changeSize(size, ticks);

        for (Player player : Bukkit.getOnlinePlayers()) {
            attach(player);
        }
    }

    /** Gives one player the challenge border, or removes it when they are outside the run. */
    public void attach(Player player) {
        if (!enabled() || !worlds.isGameWorld(player.getWorld())) {
            player.setWorldBorder(null);
            return;
        }
        player.setWorldBorder(border());
    }

    /** Called when a level is gained in border mode. */
    public void grow() {
        apply(true);
        World world = worlds.anchorWorld();
        Location anchor = worlds.anchor();
        if (world != null && anchor != null) {
            animations.borderGrow(world, anchor, targetSize());
        }
    }

    /** Hands everyone back the vanilla border, e.g. after the run ended. */
    public void release() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setWorldBorder(null);
        }
    }

    /**
     * Makes sure the challenge worlds carry no border of their own.
     * <p>
     * A world-level border is stored in {@code level.dat} and survives restarts, and it is
     * what stops mobs from spawning outside the ring. The plugin never wants one - the
     * players get their personal border instead - so any leftover gets cleared here.
     */
    public void clearWorldBorders() {
        String base = worlds.baseName();
        if (base == null) {
            return;
        }
        for (String name : List.of(base, base + "_nether", base + "_the_end")) {
            World world = Bukkit.getWorld(name);
            if (world != null && world.getWorldBorder().getSize() < 59_999_968.0D) {
                world.getWorldBorder().reset();
            }
        }
    }

    // ---------------------------------------------------------------- rescue

    /**
     * Pulls a player who ended up outside the ring back in. The client blocks the border
     * on its own, so this only ever fires after a teleport, a login or a plugin push.
     */
    public void enforce(Player player) {
        if (!enabled() || !worlds.isGameWorld(player.getWorld())) {
            return;
        }
        double half = targetSize() / 2.0D;
        double centerX = state.anchorX() + 0.5D;
        double centerZ = state.anchorZ() + 0.5D;
        Location at = player.getLocation();
        double x = at.getX();
        double z = at.getZ();

        if (x >= centerX - half - SLACK && x <= centerX + half + SLACK
                && z >= centerZ - half - SLACK && z <= centerZ + half + SLACK) {
            return;
        }
        double inset = Math.min(0.3D, half / 4.0D);
        Location corrected = at.clone();
        corrected.setX(clamp(x, centerX - half + inset, centerX + half - inset));
        corrected.setZ(clamp(z, centerZ - half + inset, centerZ + half - inset));
        player.teleport(corrected);
    }

    private static double clamp(double value, double min, double max) {
        return min > max ? (min + max) / 2.0D : Math.max(min, Math.min(max, value));
    }

    private WorldBorder border() {
        if (virtual == null) {
            virtual = Bukkit.createWorldBorder();
        }
        return virtual;
    }
}
