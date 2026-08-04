package net.gravijet.levelblock.core;

import net.gravijet.levelblock.util.Keys;
import net.gravijet.levelblock.util.LongHashSet;

import java.util.HashMap;
import java.util.Map;

/**
 * The unlocked area of a single world, stored as a set of full-height 1x1 columns.
 * <p>
 * Alongside the raw set it maintains a chunk-indexed list of <em>boundary</em> columns
 * (unlocked columns that touch at least one locked neighbour). The wall renderer only
 * ever needs the boundary near a player, so this index turns "draw the wall" from a scan
 * over the whole region into a lookup over a handful of chunks.
 */
public final class ColumnSet {

    /** Neighbour offsets, indexed by face direction: 0 = north, 1 = east, 2 = south, 3 = west. */
    public static final int[] DX = {0, 1, 0, -1};
    public static final int[] DZ = {-1, 0, 1, 0};

    private final LongHashSet columns = new LongHashSet(1024);
    private final Map<Long, LongHashSet> boundaryByChunk = new HashMap<>();

    public boolean contains(int x, int z) {
        return columns.contains(Keys.pack(x, z));
    }

    public int size() {
        return columns.size();
    }

    public boolean isEmpty() {
        return columns.isEmpty();
    }

    public boolean unlock(int x, int z) {
        if (!columns.add(Keys.pack(x, z))) {
            return false;
        }
        refreshBoundaryAround(x, z);
        return true;
    }

    public boolean lock(int x, int z) {
        if (!columns.remove(Keys.pack(x, z))) {
            return false;
        }
        refreshBoundaryAround(x, z);
        return true;
    }

    /** True when at least one orthogonally adjacent column is already unlocked. */
    public boolean hasUnlockedNeighbour(int x, int z) {
        for (int dir = 0; dir < 4; dir++) {
            if (contains(x + DX[dir], z + DZ[dir])) {
                return true;
            }
        }
        return false;
    }

    /**
     * Unlocked column closest to the given position. Linear over the region, so it is only
     * used for rare one-off lookups such as snapping a portal exit back inside.
     */
    public java.util.OptionalLong nearest(int x, int z) {
        long best = 0L;
        double bestDistance = Double.MAX_VALUE;
        boolean found = false;
        for (long key : columns.toArray()) {
            double dx = Keys.unpackX(key) - x;
            double dz = Keys.unpackZ(key) - z;
            double distance = dx * dx + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = key;
                found = true;
            }
        }
        return found ? java.util.OptionalLong.of(best) : java.util.OptionalLong.empty();
    }

    /** Boundary columns inside the given chunk, or {@code null} when there are none. */
    public LongHashSet boundaryInChunk(int chunkX, int chunkZ) {
        return boundaryByChunk.get(Keys.pack(chunkX, chunkZ));
    }

    public void clear() {
        columns.clear();
        boundaryByChunk.clear();
    }

    public long[] toArray() {
        return columns.toArray();
    }

    /** Bulk load path: skips the boundary index, call {@link #rebuildBoundary()} afterwards. */
    public void addRaw(long key) {
        columns.add(key);
    }

    public void rebuildBoundary() {
        boundaryByChunk.clear();
        for (long key : columns.toArray()) {
            updateBoundary(Keys.unpackX(key), Keys.unpackZ(key));
        }
    }

    private void refreshBoundaryAround(int x, int z) {
        updateBoundary(x, z);
        for (int dir = 0; dir < 4; dir++) {
            updateBoundary(x + DX[dir], z + DZ[dir]);
        }
    }

    private void updateBoundary(int x, int z) {
        long chunkKey = Keys.chunkOf(x, z);
        long key = Keys.pack(x, z);
        boolean onBoundary = columns.contains(key) && !isEnclosed(x, z);

        if (onBoundary) {
            boundaryByChunk.computeIfAbsent(chunkKey, ignored -> new LongHashSet(32)).add(key);
            return;
        }
        LongHashSet inChunk = boundaryByChunk.get(chunkKey);
        if (inChunk != null && inChunk.remove(key) && inChunk.isEmpty()) {
            boundaryByChunk.remove(chunkKey);
        }
    }

    private boolean isEnclosed(int x, int z) {
        for (int dir = 0; dir < 4; dir++) {
            if (!columns.contains(Keys.pack(x + DX[dir], z + DZ[dir]))) {
                return false;
            }
        }
        return true;
    }
}
