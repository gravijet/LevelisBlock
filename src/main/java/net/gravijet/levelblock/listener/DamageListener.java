package net.gravijet.levelblock.listener;

import net.gravijet.levelblock.core.GameService;
import net.gravijet.levelblock.world.WorldService;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.persistence.PersistentDataType;

/**
 * Keeps the start harmless.
 * <p>
 * Two separate guards: the celebration fireworks are tagged and can never deal damage at
 * all, and on top of that nobody can be hurt during the countdown or the first few seconds
 * of a run. Since a single death ends the challenge, an unlucky spawn or a stray rocket
 * must not be able to decide the game before it started.
 */
public final class DamageListener implements Listener {

    private final GameService game;
    private final WorldService worlds;
    private final NamespacedKey fxKey;

    public DamageListener(GameService game, WorldService worlds, NamespacedKey fxKey) {
        this.game = game;
        this.worlds = worlds;
        this.fxKey = fxKey;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent byEntity
                && byEntity.getDamager().getPersistentDataContainer().has(fxKey, PersistentDataType.BYTE)) {
            event.setCancelled(true);
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (game.isProtected() && worlds.isGameWorld(player.getWorld())) {
            event.setCancelled(true);
        }
    }
}
