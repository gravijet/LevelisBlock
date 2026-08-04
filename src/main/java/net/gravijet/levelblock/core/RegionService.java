package net.gravijet.levelblock.core;

import org.bukkit.World;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Owns one {@link ColumnSet} per world. A world without a region is unrestricted, which
 * is what keeps lobby worlds (and any non-game world) playable as normal.
 */
public final class RegionService {

    private final Map<String, ColumnSet> byWorld = new HashMap<>();

    public ColumnSet getOrCreate(World world) {
        return byWorld.computeIfAbsent(world.getName(), ignored -> new ColumnSet());
    }

    /** Same as {@link #getOrCreate(World)} but usable before the world is loaded. */
    public ColumnSet forName(String worldName) {
        return byWorld.computeIfAbsent(worldName, ignored -> new ColumnSet());
    }

    public ColumnSet peek(World world) {
        return world == null ? null : byWorld.get(world.getName());
    }

    public ColumnSet peek(String worldName) {
        return byWorld.get(worldName);
    }

    public boolean hasRegion(World world) {
        ColumnSet set = peek(world);
        return set != null && !set.isEmpty();
    }

    /** {@code true} when the column is walkable, or when this world is not restricted at all. */
    public boolean isUnlocked(World world, int x, int z) {
        ColumnSet set = peek(world);
        return set == null || set.isEmpty() || set.contains(x, z);
    }

    /** Strict variant that never treats a missing region as "everything unlocked". */
    public boolean isUnlockedStrict(World world, int x, int z) {
        ColumnSet set = peek(world);
        return set != null && set.contains(x, z);
    }

    /** Unlocks a {@code size x size} square centred on the given column. */
    public int seedArea(World world, int centerX, int centerZ, int size) {
        ColumnSet set = getOrCreate(world);
        int radius = Math.max(0, (size - 1) / 2);
        int added = 0;
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                if (set.unlock(x, z)) {
                    added++;
                }
            }
        }
        return added;
    }

    public int totalColumns() {
        int total = 0;
        for (ColumnSet set : byWorld.values()) {
            total += set.size();
        }
        return total;
    }

    public Map<String, ColumnSet> all() {
        return Collections.unmodifiableMap(byWorld);
    }

    public void remove(String worldName) {
        byWorld.remove(worldName);
    }

    public void clear() {
        byWorld.clear();
    }
}
