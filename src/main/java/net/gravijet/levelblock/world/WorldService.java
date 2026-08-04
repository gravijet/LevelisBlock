package net.gravijet.levelblock.world;

import net.gravijet.levelblock.config.Cfg;
import net.gravijet.levelblock.core.GameState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Resolves which worlds belong to the running challenge.
 * <p>
 * The plugin does not create or own any world: the run happens wherever {@code /timer start}
 * was used. That spot becomes the anchor, and the anchor's world plus its nether and end
 * form the challenge. Every other world (a lobby, a build world) stays completely untouched.
 */
public final class WorldService {

    private static final String NETHER_SUFFIX = "_nether";
    private static final String END_SUFFIX = "_the_end";

    private final Cfg cfg;
    private final GameState state;

    public WorldService(Cfg cfg, GameState state) {
        this.cfg = cfg;
        this.state = state;
    }

    // ------------------------------------------------------------------ names

    /** Overworld name of the challenge, or {@code null} while no run has been anchored. */
    public String baseName() {
        String anchor = state.anchorWorld();
        if (anchor == null || anchor.isBlank()) {
            return null;
        }
        if (anchor.endsWith(NETHER_SUFFIX)) {
            return anchor.substring(0, anchor.length() - NETHER_SUFFIX.length());
        }
        if (anchor.endsWith(END_SUFFIX)) {
            return anchor.substring(0, anchor.length() - END_SUFFIX.length());
        }
        return anchor;
    }

    public boolean isGameWorld(World world) {
        String base = baseName();
        if (world == null || base == null) {
            return false;
        }
        String name = world.getName();
        return name.equals(base)
                || name.equals(base + NETHER_SUFFIX)
                || name.equals(base + END_SUFFIX);
    }

    /** The overworld of the run, or {@code null} when it is not loaded (or none is set). */
    public World gameWorld() {
        String base = baseName();
        return base == null ? null : Bukkit.getWorld(base);
    }

    public World anchorWorld() {
        String name = state.anchorWorld();
        return name == null ? null : Bukkit.getWorld(name);
    }

    /** Centre of the challenge, or {@code null} when the anchor world is not loaded. */
    public Location anchor() {
        World world = anchorWorld();
        if (world == null) {
            return null;
        }
        return new Location(world, state.anchorX() + 0.5D, state.anchorY(), state.anchorZ() + 0.5D);
    }

    // ------------------------------------------------------------ spawn setup

    /**
     * Nudges a location onto solid ground so the start area is stand-on-able. Keeps the
     * x/z the player chose - {@code /timer start} means "here", not "somewhere near here".
     */
    public Location groundedAt(Location wanted) {
        World world = wanted.getWorld();
        int x = wanted.getBlockX();
        int z = wanted.getBlockZ();
        int y = wanted.getBlockY();

        // Only snap when the player is standing in air (flying, falling, on a ladder).
        Block below = world.getBlockAt(x, y - 1, z);
        if (below.getType().isSolid()) {
            return new Location(world, x + 0.5D, y, z + 0.5D, wanted.getYaw(), wanted.getPitch());
        }
        int surface = world.getHighestBlockYAt(x, z) + 1;
        return new Location(world, x + 0.5D, surface, z + 0.5D, wanted.getYaw(), wanted.getPitch());
    }

    /**
     * Moves the world spawn point out of the play area and returns where it ended up,
     * or {@code null} when the feature is switched off.
     * <p>
     * This is not cosmetic. Two vanilla rules hang off the spawn point, and both quietly
     * ruin a challenge that is played right on top of it:
     * <ul>
     *   <li>natural spawning refuses every position within 24 blocks of the spawn point -
     *       in <em>all</em> dimensions, since they share one spawn position. On a 3x3 start
     *       that means no monsters and no animals ever come to you.</li>
     *   <li>{@code spawn-protection} in server.properties (16 blocks by default) stops
     *       every non-op from breaking or placing a single block around it.</li>
     * </ul>
     * Neither can be switched off from a plugin, so the spawn point gets moved instead.
     * Nobody respawns out there: {@code PlayerListener} sends respawns to the anchor.
     */
    public Location relocateWorldSpawn(World world, int anchorX, int anchorZ) {
        int distance = cfg.spawnPointDistance;
        if (distance <= 0) {
            return null;
        }
        int x = anchorX + distance;
        int z = anchorZ + distance;
        int y = Math.max(world.getMinHeight() + 1, world.getHighestBlockYAt(x, z) + 1);
        world.setSpawnLocation(x, y, z);
        return new Location(world, x + 0.5D, y, z + 0.5D);
    }

    /** Makes the start area walkable: fills liquid or missing ground with the platform block. */
    public void preparePlatform(World world, int centerX, int centerZ, int size) {
        if (!cfg.preparePlatform) {
            return;
        }
        int radius = Math.max(0, (size - 1) / 2);
        Material fill = cfg.platformMaterial.isBlock() ? cfg.platformMaterial : Material.STONE;

        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                int y = world.getHighestBlockYAt(x, z);
                Block ground = world.getBlockAt(x, y, z);
                if (ground.isLiquid() || ground.getType().isAir() || !ground.getType().isSolid()) {
                    ground.setType(fill, false);
                }
                for (int dy = 1; dy <= 3; dy++) {
                    Block above = world.getBlockAt(x, y + dy, z);
                    if (above.isLiquid()) {
                        above.setType(Material.AIR, false);
                    }
                }
            }
        }
    }
}
