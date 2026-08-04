package net.gravijet.levelblock.listener;

import net.gravijet.levelblock.Mode;
import net.gravijet.levelblock.config.Cfg;
import net.gravijet.levelblock.core.ColumnSet;
import net.gravijet.levelblock.core.GameService;
import net.gravijet.levelblock.core.GameState;
import net.gravijet.levelblock.core.RegionService;
import net.gravijet.levelblock.core.UnlockService;
import net.gravijet.levelblock.fx.Fx;
import net.gravijet.levelblock.util.Keys;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps players inside the unlocked columns - and turns walking into the edge into the
 * unlock gesture.
 * <p>
 * A blocked move is never cancelled outright. Cancelling rubber-bands the player back to
 * where the server thinks they are, which is exactly the stutter the old glass wall had.
 * Instead only the illegal <em>axis</em> is clamped back into the column the player came
 * from while the legal axis keeps its value, which produces the same wall-sliding as real
 * block collision.
 * <p>
 * Diagonals get special care: a player cutting a corner touches three columns at once, so
 * the code picks the single column they are actually pushing hardest against, offers only
 * that one to {@link UnlockService}, and holds the other axis. One step never buys more
 * than one block.
 */
public final class ContainmentListener implements Listener {

    /** Keeps the player a hair inside the column so float rounding cannot push them out. */
    private static final double EDGE_INSET = 0.02D;
    private static final long BUMP_COOLDOWN_MILLIS = 350L;

    private final Cfg cfg;
    private final GameState state;
    private final RegionService regions;
    private final GameService game;
    private final UnlockService unlocks;
    private final Map<UUID, Long> lastBump = new HashMap<>();

    public ContainmentListener(Cfg cfg, GameState state, RegionService regions, GameService game,
                               UnlockService unlocks) {
        this.cfg = cfg;
        this.state = state;
        this.regions = regions;
        this.game = game;
        this.unlocks = unlocks;
    }

    // ------------------------------------------------------------------- move

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();

        if (game.isFrozen() && !game.isExempt(player)
                && (from.getX() != to.getX() || from.getZ() != to.getZ())) {
            Location hold = from.clone();
            hold.setYaw(to.getYaw());
            hold.setPitch(to.getPitch());
            event.setTo(hold);
            return;
        }

        int fx = from.getBlockX();
        int fz = from.getBlockZ();
        int tx = to.getBlockX();
        int tz = to.getBlockZ();
        if (fx == tx && fz == tz) {
            return;
        }
        ColumnSet columns = activeColumns(player);
        if (columns == null || columns.contains(tx, tz)) {
            return;
        }
        // Standing outside already (login in a stale spot, /tp, world edit) - put them back.
        if (!columns.contains(fx, fz)) {
            game.rescue(player);
            return;
        }

        int stepX = Integer.signum(tx - fx);
        int stepZ = Integer.signum(tz - fz);
        boolean xOpen = stepX == 0 || columns.contains(fx + stepX, fz);
        boolean zOpen = stepZ == 0 || columns.contains(fx, fz + stepZ);

        if (!xOpen || !zOpen) {
            long key = chooseColumn(from, to, fx, fz, stepX, stepZ, xOpen, zOpen);
            if (unlocks.tryUnlock(player, Keys.unpackX(key), Keys.unpackZ(key))) {
                xOpen = stepX == 0 || columns.contains(fx + stepX, fz);
                zOpen = stepZ == 0 || columns.contains(fx, fz + stepZ);
            }
        }

        Location corrected = to.clone();
        boolean blocked = false;
        if (!xOpen) {
            corrected.setX(clampInto(fx, to.getX()));
            blocked = true;
        }
        if (!zOpen) {
            corrected.setZ(clampInto(fz, to.getZ()));
            blocked = true;
        }
        // Both neighbours open but the diagonal between them is not: hold the weaker axis
        // so the player slides along the edge instead of clipping through the corner.
        if (!blocked && stepX != 0 && stepZ != 0) {
            if (pushesX(from, to)) {
                corrected.setZ(clampInto(fz, to.getZ()));
            } else {
                corrected.setX(clampInto(fx, to.getX()));
            }
            blocked = true;
        }
        // Long steps (velocity, pistons, elytra) can jump clean over an open column.
        if (!columns.contains(corrected.getBlockX(), corrected.getBlockZ())) {
            corrected.setX(clampInto(fx, to.getX()));
            corrected.setZ(clampInto(fz, to.getZ()));
            blocked = true;
        }
        if (blocked) {
            event.setTo(corrected);
            bump(player, corrected);
        }
    }

    /**
     * The one column this step is really aimed at, packed as {@code x << 32 | z}.
     * Preference goes to the axis the player moved further along, so a diagonal into a
     * corner buys the block in front of them rather than the one they brushed past.
     */
    private static long chooseColumn(Location from, Location to, int fx, int fz,
                                     int stepX, int stepZ, boolean xOpen, boolean zOpen) {
        boolean takeX = !xOpen && (zOpen || pushesX(from, to));
        return takeX ? Keys.pack(fx + stepX, fz) : Keys.pack(fx, fz + stepZ);
    }

    private static boolean pushesX(Location from, Location to) {
        return Math.abs(to.getX() - from.getX()) >= Math.abs(to.getZ() - from.getZ());
    }

    private static double clampInto(int blockCoord, double value) {
        return Math.max(blockCoord + EDGE_INSET, Math.min(blockCoord + 1.0D - EDGE_INSET, value));
    }

    private void bump(Player player, Location at) {
        if (!cfg.bumpFeedback) {
            return;
        }
        long now = System.currentTimeMillis();
        Long previous = lastBump.get(player.getUniqueId());
        if (previous != null && now - previous < BUMP_COOLDOWN_MILLIS) {
            return;
        }
        lastBump.put(player.getUniqueId(), now);
        Fx.play(player, at, Fx.WOOL_HIT, cfg.volume * 0.4F, 1.4F);
    }

    // --------------------------------------------------------------- teleport

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        ColumnSet columns = activeColumns(player);
        if (columns == null) {
            return;
        }
        // Portals are fixed up in PlayerListener; admin teleports must stay possible.
        switch (event.getCause()) {
            case PLUGIN, COMMAND, UNKNOWN, NETHER_PORTAL, END_PORTAL, END_GATEWAY, SPECTATE -> {
                return;
            }
            default -> {
            }
        }
        Location to = event.getTo();
        ColumnSet target = regions.peek(to.getWorld());
        if (target == null || target.isEmpty() || target.contains(to.getBlockX(), to.getBlockZ())) {
            return;
        }
        event.setCancelled(true);
        bump(player, player.getLocation());
    }

    // --------------------------------------------------------------- vehicles

    @EventHandler(ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        if (state.mode() != Mode.LEVEL_BLOCK || !state.isActive()) {
            return;
        }
        boolean carriesRestrictedPlayer = false;
        for (Entity passenger : event.getVehicle().getPassengers()) {
            if (passenger instanceof Player player && !game.isExempt(player)) {
                carriesRestrictedPlayer = true;
                break;
            }
        }
        if (!carriesRestrictedPlayer) {
            return;
        }
        ColumnSet columns = regions.peek(to.getWorld());
        if (columns == null || columns.isEmpty() || columns.contains(to.getBlockX(), to.getBlockZ())) {
            return;
        }
        event.getVehicle().setVelocity(new Vector(0.0D, 0.0D, 0.0D));
        event.getVehicle().teleport(from);
    }

    // ------------------------------------------------------------ block edits

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (blockedEdit(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (blockedEdit(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (blockedEdit(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    private boolean blockedEdit(Player player, Block block) {
        if (!cfg.restrictBlockEdits) {
            return false;
        }
        ColumnSet columns = activeColumns(player);
        if (columns == null || columns.contains(block.getX(), block.getZ())) {
            return false;
        }
        Fx.play(player, Fx.NO, cfg.volume * 0.6F, 1.2F);
        return true;
    }

    // ------------------------------------------------------------ explosions

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        filterExplosion(event.getEntity().getWorld(), event.blockList());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        filterExplosion(event.getBlock().getWorld(), event.blockList());
    }

    private void filterExplosion(World world, java.util.List<Block> blocks) {
        if (!cfg.restrictExplosions || state.mode() != Mode.LEVEL_BLOCK) {
            return;
        }
        ColumnSet columns = regions.peek(world);
        if (columns == null || columns.isEmpty()) {
            return;
        }
        blocks.removeIf(block -> !columns.contains(block.getX(), block.getZ()));
    }

    // ----------------------------------------------------------------- shared

    /** The columns restricting this player right now, or {@code null} when unrestricted. */
    private ColumnSet activeColumns(Player player) {
        if (state.mode() != Mode.LEVEL_BLOCK || !state.isActive() || game.isExempt(player)) {
            return null;
        }
        ColumnSet columns = regions.peek(player.getWorld());
        return columns == null || columns.isEmpty() ? null : columns;
    }

    public void forget(UUID playerId) {
        lastBump.remove(playerId);
    }
}
