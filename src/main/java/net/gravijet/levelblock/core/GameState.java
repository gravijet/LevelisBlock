package net.gravijet.levelblock.core;

import net.gravijet.levelblock.Mode;
import net.gravijet.levelblock.Sharing;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Everything that has to survive a restart: mode, timer, the anchor the run was started
 * on, per-player credits and stats. The timer is stored as wall-clock millis so server
 * lag never makes it drift.
 * <p>
 * Credits are per player on purpose: in {@link Mode#LEVEL_BLOCK} everyone spends their own
 * experience. {@link #totalLevels()} on the other hand is the shared pool that drives the
 * border in {@link Mode#LEVEL_BORDER}, where the whole team's XP counts together.
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
    private int sharedCredits;

    private final Map<UUID, Integer> creditsByPlayer = new HashMap<>();
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

    public void resumeTimer() {
        if (phase == Phase.PAUSED || phase == Phase.IDLE) {
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

    // -------------------------------------------------------------- credits

    /** Credits available to {@code player} - the shared pool when {@link Sharing#SHARED}. */
    public int credits(UUID player) {
        return sharing == Sharing.SHARED ? sharedCredits : creditsByPlayer.getOrDefault(player, 0);
    }

    public void credits(UUID player, int credits) {
        int value = Math.max(0, credits);
        if (sharing == Sharing.SHARED) {
            sharedCredits = value;
        } else {
            creditsByPlayer.put(player, value);
        }
        markDirty();
    }

    public void addCredits(UUID player, int amount) {
        credits(player, credits(player) + amount);
    }

    public boolean spendCredits(UUID player, int amount) {
        int have = credits(player);
        if (have < amount) {
            return false;
        }
        credits(player, have - amount);
        return true;
    }

    public Map<UUID, Integer> creditsByPlayer() {
        return creditsByPlayer;
    }

    public int sharedCredits() {
        return sharedCredits;
    }

    public void sharedCredits(int value) {
        this.sharedCredits = Math.max(0, value);
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
        sharedCredits = 0;
        levelsByPlayer.clear();
        creditsByPlayer.clear();
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
