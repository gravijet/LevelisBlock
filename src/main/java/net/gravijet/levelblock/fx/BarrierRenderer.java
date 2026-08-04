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

import java.util.HashMap;
import java.util.Map;

/**
 * Draws the edge of the unlocked area as a red line along the ground.
 * <p>
 * There is deliberately no wall here - no block displays, no barrier blocks, nothing the
 * player can collide with or clip through. The line is pure decoration that marks exactly
 * where the movement clamp in {@code ContainmentListener} kicks in.
 * <p>
 * Where the ground steps up or down across the edge the line climbs with it, so a cliff
 * face reads as a wall instead of the line vanishing into the rock or hanging in mid-air.
 * <p>
 * Everything is drawn per player and only within {@code barrier.render-distance}, so the
 * cost scales with how much edge is actually being looked at, not with the region size.
 * The dust is sent forced, which is what makes it show up far away and on the lowest
 * particle setting.
 */
public final class BarrierRenderer {

    /** Lifts the dust off the surface so it does not disappear inside the block below. */
    private static final double GROUND_OFFSET = 0.08D;
    /** How far around the player's own height the surface is looked for. */
    private static final int GROUND_SCAN_DOWN = 24;
    private static final int GROUND_SCAN_UP = 8;
    /** Vertical spacing of the climbing part, in blocks. */
    private static final double HEIGHT_STEP = 1.0D;
    /** Only every n-th point along a face climbs, so a tall cliff cannot eat the budget. */
    private static final int CLIMB_EVERY = 2;

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
        // Neighbouring faces keep asking for the same columns, and a ground scan is the
        // only expensive thing here - so it is done once per column and per pass.
        Map<Long, Integer> groundCache = new HashMap<>();

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
                    int inside = groundY(world, x, z, feetY, groundCache);
                    for (int dir = 0; dir < 4 && budget > 0; dir++) {
                        int nx = x + ColumnSet.DX[dir];
                        int nz = z + ColumnSet.DZ[dir];
                        if (columns.contains(nx, nz)) {
                            continue;
                        }
                        int outside = groundY(world, nx, nz, feetY, groundCache);
                        budget -= drawFace(player, x, z, dir, inside, outside);
                    }
                }
            }
        }
    }

    /**
     * One block face: a row of dust along the ground plus, where the two sides sit at
     * different heights, a curtain climbing the step between them.
     *
     * @return how many points were drawn
     */
    private int drawFace(Player player, int x, int z, int dir, int insideY, int outsideY) {
        int points = Math.max(1, cfg.barrierPointsPerBlock);
        double step = 1.0D / points;
        boolean alongX = dir == 0 || dir == 2;
        // The line sits exactly on the block boundary the movement clamp uses.
        double edgeX = dir == 1 ? x + 1.0D : x;
        double edgeZ = dir == 2 ? z + 1.0D : z;

        // The step is drawn between the two ground heights, so it covers a wall in front of
        // the player as well as a drop behind the edge. Flat ground gets the row only.
        boolean stepped = insideY != outsideY && cfg.barrierMaxHeight > 0;
        double low = Math.min(insideY, outsideY) + GROUND_OFFSET;
        double high = Math.min(Math.max(insideY, outsideY),
                Math.min(insideY, outsideY) + cfg.barrierMaxHeight) + GROUND_OFFSET;

        int drawn = 0;
        for (int i = 0; i < points; i++) {
            double offset = (i + 0.5D) * step;
            double dustX = alongX ? x + offset : edgeX;
            double dustZ = alongX ? edgeZ : z + offset;

            Fx.dustForced(player, dustX, insideY + GROUND_OFFSET, dustZ,
                    cfg.barrierColor, cfg.barrierParticleSize);
            drawn++;

            if (!stepped || i % CLIMB_EVERY != 0) {
                continue;
            }
            for (double y = low; y <= high; y += HEIGHT_STEP) {
                Fx.dustForced(player, dustX, y, dustZ, cfg.barrierColor, cfg.barrierParticleSize);
                drawn++;
            }
        }
        return drawn;
    }

    /** Surface height at the column, searched around the player's own feet. */
    private static int groundY(World world, int x, int z, int feetY, Map<Long, Integer> cache) {
        return cache.computeIfAbsent(Keys.pack(x, z), ignored -> scanGround(world, x, z, feetY));
    }

    private static int scanGround(World world, int x, int z, int feetY) {
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
