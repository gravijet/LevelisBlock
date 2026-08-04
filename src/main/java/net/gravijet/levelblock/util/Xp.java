package net.gravijet.levelblock.util;

import org.bukkit.entity.Player;

/**
 * Vanilla experience maths, so a shared pool can be kept as one absolute number of points.
 * <p>
 * Level and bar progress are two halves of the same value and drift apart the moment they
 * are copied separately: the bar is a fraction of the <em>current</em> level's span, and
 * that span grows with the level. Converting both ways through a point total removes the
 * problem - everyone is handed the same number and derives the same level, the same bar
 * and the same point count from it.
 */
public final class Xp {

    /** Far past any reachable level; only guards the loop in {@link #levelOf}. */
    private static final int LEVEL_CAP = 100_000;

    private Xp() {
    }

    /** Points needed to get from {@code level} to the next one. */
    public static int pointsToNext(int level) {
        int value = Math.max(0, level);
        if (value >= 30) {
            return 112 + (value - 30) * 9;
        }
        if (value >= 15) {
            return 37 + (value - 15) * 5;
        }
        return 7 + value * 2;
    }

    /** Points needed to reach {@code level} starting from nothing. */
    public static long totalForLevel(int level) {
        long value = Math.max(0, level);
        if (value <= 16) {
            return value * value + 6L * value;
        }
        if (value <= 31) {
            return Math.round(2.5D * value * value - 40.5D * value + 360.0D);
        }
        return Math.round(4.5D * value * value - 162.5D * value + 2220.0D);
    }

    /**
     * The point total behind a level plus a bar fraction.
     * <p>
     * Rounds down and stops one point short of the next level on purpose. Rounding up a
     * nearly full bar would push the total over the boundary, and the pool would hand out
     * a level nobody earned - a bar at 99% is still the level below.
     */
    public static long total(int level, float progress) {
        float clamped = Math.max(0.0F, Math.min(1.0F, progress));
        int span = pointsToNext(level);
        // The epsilon keeps a float that came back out of progressOf landing on its
        // original point again instead of one below.
        int inside = (int) Math.floor(clamped * span + 1.0E-4D);
        return totalForLevel(level) + Math.max(0, Math.min(inside, span - 1));
    }

    public static long totalOf(Player player) {
        return total(player.getLevel(), player.getExp());
    }

    /** Level a player sits at with this many points. */
    public static int levelOf(long total) {
        long remaining = Math.max(0L, total);
        int level = 0;
        while (level < LEVEL_CAP) {
            int step = pointsToNext(level);
            if (remaining < step) {
                return level;
            }
            remaining -= step;
            level++;
        }
        return LEVEL_CAP;
    }

    /** Bar fill inside the current level, {@code 0.0} to just under {@code 1.0}. */
    public static float progressOf(long total) {
        int level = levelOf(total);
        int span = pointsToNext(level);
        if (span <= 0) {
            return 0.0F;
        }
        long inside = Math.max(0L, total) - totalForLevel(level);
        return Math.max(0.0F, Math.min(0.9999F, (float) inside / span));
    }

    /**
     * Writes a point total onto a player: level, bar and point counter in one go.
     * <p>
     * The counter matters as much as the bar - it is what {@code getTotalExperience}
     * reports, so leaving it alone would keep two players who look identical on screen
     * holding different numbers underneath.
     */
    public static void apply(Player player, long total) {
        long value = Math.max(0L, total);
        player.setLevel(levelOf(value));
        player.setExp(progressOf(value));
        player.setTotalExperience((int) Math.min(Integer.MAX_VALUE, value));
    }
}
