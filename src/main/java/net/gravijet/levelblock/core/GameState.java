package net.gravijet.levelblock.core;

import net.gravijet.levelblock.Mode;
import net.gravijet.levelblock.Sharing;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Everything that has to survive a restart: mode, timer, the anchor the run was started
 * on, the shared experience pool and the stats. The timer is stored as wall-clock millis
 * so server lag never makes it drift.
 * <p>
 * There is no currency here. In {@link Mode#LEVEL_BLOCK} players pay for blocks with their
 * own XP levels, which live on the player, not in this class. {@link #totalLevels()} is
 * the running count of everything the team ever earned and drives the border in
 * {@link Mode#LEVEL_BORDER}.
 */
public final class GameState {

    public enum Phase {
        IDLE,
        COUNTDOWN,
        RUNNING,
        PAUSED,
        FINISHED,
        /** Someone died - the run is over for good. */
        FAILED
    }

    private Mode mode = Mode.LEVEL_BLOCK;
    private Sharing sharing = Sharing.INDIVIDUAL;
    private Phase phase = Phase.IDLE;

    private long accumulatedMillis;
    private long resumedAtMillis;

    /** Where {@code /timer start} was run. {@code null} until the first start. */
    private String anchorWorld;
    private int anchorX;
    private int anchorY;
    private int anchorZ;

    private long totalLevels;

    /**
     * The team's experience in {@link Sharing#SHARED}, as an absolute point total.
     * <p>
     * One number, not one per player: level and bar progress are derived from it, so
     * everybody always shows the same level, the same bar and the same point count. The
     * old approach of copying one player's values onto the others could only ever be as
     * correct as the moment it was read.
     */
    private long teamExperience;

    /**
     * Current border size in {@link Mode#LEVEL_BORDER}. Kept here rather than derived from
     * the level count so an admin can set it with {@code /border set} without the next
     * level snapping it back to whatever the formula says.
     * <p>
     * A non-positive value means "never initialised" - the run has not started yet, so the
     * configured start size applies.
     */
    private double borderSize;

    private final Map<UUID, Long> levelsByPlayer = new HashMap<>();
    private final Map<UUID, String> nameByPlayer = new HashMap<>();

    private boolean dirty;

    public Mode mode() {
        return mode;
    }

    public void mode(Mode mode) {
        this.mode = mode;
        markDirty();
    }

    public Sharing sharing() {
        return sharing;
    }

    public void sharing(Sharing sharing) {
        this.sharing = sharing;
        markDirty();
    }

    public Phase phase() {
        return phase;
    }

    public boolean isRunning() {
        return phase == Phase.RUNNING;
    }

    /** {@code true} while the challenge restricts play: countdown, running or paused. */
    public boolean isActive() {
        return phase == Phase.RUNNING || phase == Phase.PAUSED || phase == Phase.COUNTDOWN;
    }

    public boolean isOver() {
        return phase == Phase.FINISHED || phase == Phase.FAILED;
    }

    // ---------------------------------------------------------------- anchor

    public String anchorWorld() {
        return anchorWorld;
    }

    public int anchorX() {
        return anchorX;
    }

    public int anchorY() {
        return anchorY;
    }

    public int anchorZ() {
        return anchorZ;
    }

    public boolean hasAnchor() {
        return anchorWorld != null && !anchorWorld.isBlank();
    }

    public void anchor(String world, int x, int y, int z) {
        this.anchorWorld = world;
        this.anchorX = x;
        this.anchorY = y;
        this.anchorZ = z;
        markDirty();
    }

    // ---------------------------------------------------------------- timer

    public void startTimer() {
        accumulatedMillis = 0L;
        resumedAtMillis = System.currentTimeMillis();
        phase = Phase.RUNNING;
        markDirty();
    }

    /** Puts the clock back on, from a pause as well as from a finished or failed run. */
    public void resumeTimer() {
        if (phase != Phase.RUNNING) {
            resumedAtMillis = System.currentTimeMillis();
            phase = Phase.RUNNING;
            markDirty();
        }
    }

    public void pauseTimer() {
        if (phase == Phase.RUNNING) {
            accumulatedMillis += System.currentTimeMillis() - resumedAtMillis;
            phase = Phase.PAUSED;
            markDirty();
        }
    }

    public void stopTimer(Phase newPhase) {
        if (phase == Phase.RUNNING) {
            accumulatedMillis += System.currentTimeMillis() - resumedAtMillis;
        }
        phase = newPhase;
        markDirty();
    }

    public void resetTimer() {
        accumulatedMillis = 0L;
        resumedAtMillis = System.currentTimeMillis();
        markDirty();
    }

    public long elapsedMillis() {
        return phase == Phase.RUNNING
                ? accumulatedMillis + (System.currentTimeMillis() - resumedAtMillis)
                : accumulatedMillis;
    }

    public long elapsedSeconds() {
        return elapsedMillis() / 1000L;
    }

    public void elapsedSeconds(long seconds) {
        accumulatedMillis = Math.max(0L, seconds) * 1000L;
        resumedAtMillis = System.currentTimeMillis();
        markDirty();
    }

    public void phase(Phase phase) {
        this.phase = phase;
        markDirty();
    }

    // ------------------------------------------------------- shared experience

    public long teamExperience() {
        return teamExperience;
    }

    public void teamExperience(long points) {
        this.teamExperience = Math.max(0L, points);
        markDirty();
    }

    // ---------------------------------------------------------------- border

    /** Stored border size, or {@code 0} when the run has not set one yet. */
    public double borderSize() {
        return Math.max(0.0D, borderSize);
    }

    public boolean hasBorderSize() {
        return borderSize > 0.0D;
    }

    public void borderSize(double size) {
        this.borderSize = Math.max(0.0D, size);
        markDirty();
    }

    // ---------------------------------------------------------------- levels

    public long totalLevels() {
        return totalLevels;
    }

    public void totalLevels(long value) {
        this.totalLevels = Math.max(0L, value);
        markDirty();
    }

    public void addLevels(UUID player, String name, int amount) {
        totalLevels(totalLevels + amount);
        if (player != null) {
            levelsByPlayer.merge(player, (long) amount, Long::sum);
            nameByPlayer.put(player, name);
        }
    }

    public Map<UUID, Long> levelsByPlayer() {
        return levelsByPlayer;
    }

    public String nameOf(UUID id) {
        return nameByPlayer.getOrDefault(id, "?");
    }

    public void putName(UUID id, String name) {
        nameByPlayer.put(id, name);
    }

    // ----------------------------------------------------------------- reset

    public void resetProgress() {
        totalLevels = 0L;
        teamExperience = 0L;
        borderSize = 0.0D;
        levelsByPlayer.clear();
        accumulatedMillis = 0L;
        resumedAtMillis = System.currentTimeMillis();
        phase = Phase.IDLE;
        markDirty();
    }

    public boolean dirty() {
        return dirty;
    }

    public void markDirty() {
        this.dirty = true;
    }

    public void clearDirty() {
        this.dirty = false;
    }
}
