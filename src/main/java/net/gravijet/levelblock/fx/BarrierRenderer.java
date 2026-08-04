package net.gravijet.levelblock.fx;

import net.gravijet.levelblock.Mode;
import net.gravijet.levelblock.config.Cfg;
import net.gravijet.levelblock.core.ColumnSet;
import net.gravijet.levelblock.core.GameState;
import net.gravijet.levelblock.core.RegionService;
import net.gravijet.levelblock.util.Keys;
import net.gravijet.levelblock.util.LongHashSet;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * Draws the edge of the unlocked area as a solid red line on the ground.
 * <p>
 * There is deliberately no wall here - no block displays, no barrier blocks, nothing the
 * player can collide with or clip through. The line is pure decoration that marks exactly
 * where the movement clamp in {@code ContainmentListener} kicks in, which is why it sits
 * flat on the surface: it has to read as "this is the edge", not as an obstacle.
 * <p>
 * Everything is drawn per player and only within {@code barrier.render-distance}, so the
 * cost scales with how much edge is actually being looked at, not with the region size.
 */
public final class BarrierRenderer {

    /** Lifts the dust off the surface so it does not disappear inside the block below. */
    private static final double GROUND_OFFSET = 0.08D;
    /** How far down we look for the surface, relative to the player's feet. */
    private static final int GROUND_SCAN_DOWN = 8;
    private static final int GROUND_SCAN_UP = 2;

    private final Cfg cfg;
    private final GameState state;
    private final RegionService regions;

    public BarrierRenderer(Cfg cfg, GameState state, RegionService regions) {
        this.cfg = cfg;
        this.state = state;
        this.regions = regions;
    }

    public void tick() {
        if (!cfg.barrierEnabled || state.mode() != Mode.LEVEL_BLOCK || !state.isActive()) {
            return;
        }
        for (Map.Entry<String, ColumnSet> entry : regions.all().entrySet()) {
            World world = Bukkit.getWorld(entry.getKey());
            ColumnSet columns = entry.getValue();
            if (world == null || columns.isEmpty()) {
                continue;
            }
            for (Player player : world.getPlayers()) {
                draw(player, columns);
            }
        }
    }

    private void draw(Player player, ColumnSet columns) {
        World world = player.getWorld();
        int range = cfg.barrierRenderDistance;
        int rangeSq = range * range;
        int chunkRadius = (range >> 4) + 1;
        int playerChunkX = player.getLocation().getBlockX() >> 4;
        int playerChunkZ = player.getLocation().getBlockZ() >> 4;
        double px = player.getX();
        double pz = player.getZ();
        int feetY = player.getLocation().getBlockY();

        int budget = cfg.barrierMaxPoints;

        for (int cx = playerChunkX - chunkRadius; cx <= playerChunkX + chunkRadius && budget > 0; cx++) {
            for (int cz = playerChunkZ - chunkRadius; cz <= playerChunkZ + chunkRadius && budget > 0; cz++) {
                LongHashSet boundary = columns.boundaryInChunk(cx, cz);
                if (boundary == null) {
                    continue;
                }
                for (long key : boundary.toArray()) {
                    if (budget <= 0) {
                        break;
                    }
                    int x = Keys.unpackX(key);
                    int z = Keys.unpackZ(key);
                    double dx = (x + 0.5D) - px;
                    double dz = (z + 0.5D) - pz;
                    if (dx * dx + dz * dz > rangeSq) {
                        continue;
                    }
                    double y = groundY(world, x, z, feetY) + GROUND_OFFSET;
                    for (int dir = 0; dir < 4 && budget > 0; dir++) {
                        if (columns.contains(x + ColumnSet.DX[dir], z + ColumnSet.DZ[dir])) {
                            continue;
                        }
                        budget -= drawFace(player, x, z, dir, y);
                    }
                }
            }
        }
    }

    /**
     * One dense row of dust along a single block face.
     *
     * @return how many points were drawn
     */
    private int drawFace(Player player, int x, int z, int dir, double y) {
        int points = cfg.barrierPointsPerBlock;
        double step = 1.0D / points;
        boolean alongX = dir == 0 || dir == 2;
        // The line sits exactly on the block boundary the movement clamp uses.
        double edgeX = dir == 1 ? x + 1.0D : x;
        double edgeZ = dir == 2 ? z + 1.0D : z;

        for (int i = 0; i < points; i++) {
            double offset = (i + 0.5D) * step;
            double px = alongX ? x + offset : edgeX;
            double pz = alongX ? edgeZ : z + offset;
            Fx.dustForced(player, px, y, pz, cfg.barrierColor, cfg.barrierParticleSize);
        }
        return points;
    }

    /** Surface height at the column, searched around the player's own feet. */
    private static int groundY(World world, int x, int z, int feetY) {
        int top = Math.min(world.getMaxHeight() - 1, feetY + GROUND_SCAN_UP);
        int bottom = Math.max(world.getMinHeight(), feetY - GROUND_SCAN_DOWN);
        for (int y = top; y >= bottom; y--) {
            Block block = world.getBlockAt(x, y, z);
            if (!block.isPassable() && block.getType().isSolid()) {
                return y + 1;
            }
        }
        return feetY;
    }
}
