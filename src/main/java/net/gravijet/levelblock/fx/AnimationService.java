package net.gravijet.levelblock.fx;

import net.gravijet.levelblock.config.Cfg;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Firework;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/** All the "juice": the unlock burst, the start shockwave and the end-of-run finale. */
public final class AnimationService {

    private final JavaPlugin plugin;
    private final Cfg cfg;
    private final NamespacedKey fxKey;

    public AnimationService(JavaPlugin plugin, Cfg cfg, NamespacedKey fxKey) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.fxKey = fxKey;
    }

    // ----------------------------------------------------------------- unlock

    /** A red ring collapsing into the freed column plus a short rising spark. */
    public void unlock(World world, int x, int z) {
        int surface = world.getHighestBlockYAt(x, z) + 1;
        Location center = new Location(world, x + 0.5D, surface, z + 0.5D);

        Fx.playNearby(center, 32.0D, Fx.BEACON_POWER, cfg.volume, 1.7F);
        Fx.playNearby(center, 32.0D, Fx.AMETHYST_CHIME, cfg.volume * 0.8F, 1.2F);

        if (!cfg.unlockAnimation) {
            return;
        }
        Color from = cfg.barrierColor;
        Color to = cfg.themeColor;

        new BukkitRunnable() {
            private int t;

            @Override
            public void run() {
                if (t >= 8) {
                    cancel();
                    return;
                }
                double radius = 1.4D - t * 0.16D;
                Color tint = Fx.lerp(from, to, t / 8.0D);
                double y = center.getY() + 0.05D + t * 0.12D;
                Location cursor = center.clone();
                cursor.setY(y);
                Fx.ring(cursor, radius, 12, point -> Fx.dust(world, point, 1, 0.0D, tint, 1.0F));
                if (t == 2) {
                    world.spawnParticle(Particle.END_ROD, center.clone().add(0.0D, 0.6D, 0.0D),
                            8, 0.2D, 0.35D, 0.2D, 0.03D);
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ------------------------------------------------------------------ start

    /** One number of the pre-start countdown: a tightening ring and a rising pling. */
    public void countdownPulse(World world, Location center, int remaining, int total) {
        float pitch = total <= 1 ? 1.5F : 0.7F + (total - remaining) / (float) total * 1.1F;
        Fx.playNearby(center, 64.0D, Fx.NOTE_PLING, cfg.volume, Math.min(2.0F, pitch));
        if (!cfg.startAnimation) {
            return;
        }
        double radius = 1.0D + remaining * 0.9D;
        Fx.ring(center, radius, 28, point ->
                Fx.dust(world, point.clone().add(0.0D, 0.4D, 0.0D), 1, 0.0D, cfg.themeColor, 1.2F));
    }

    /** The "GO": layered sounds, a fast expanding shockwave and a burst of fireworks. */
    public void start(World world, Location center) {
        Fx.playNearby(center, 96.0D, Fx.CHALLENGE_COMPLETE, cfg.volume, 1.0F);
        Fx.playNearby(center, 96.0D, Fx.BEACON_ACTIVATE, cfg.volume, 1.2F);

        if (!cfg.startAnimation) {
            return;
        }
        Color color = cfg.themeColor;
        Color white = Color.fromRGB(0xFFFFFF);

        new BukkitRunnable() {
            private int t;

            @Override
            public void run() {
                if (t >= 30) {
                    cancel();
                    return;
                }
                double radius = 1.0D + t * 1.1D;
                int points = Math.min(90, 14 + t * 3);
                Color tint = Fx.lerp(white, color, t / 30.0D);
                double lift = Math.sin(t / 30.0D * Math.PI) * 1.6D;
                Fx.ring(center, radius, points, point -> {
                    Location at = point.clone().add(0.0D, 0.5D + lift, 0.0D);
                    Fx.dust(world, at, 1, 0.0D, tint, 1.4F);
                });
                if (t % 6 == 0) {
                    world.spawnParticle(Particle.END_ROD, center.clone().add(0.0D, 1.0D, 0.0D),
                            18, 0.4D, 0.8D, 0.4D, 0.18D);
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);

        if (cfg.startFireworks) {
            launchFireworks(world, center, 3);
        }
    }

    // ----------------------------------------------------------------- border

    /** Pulse along the new border edge; skipped once the border is too big to trace. */
    public void borderGrow(World world, Location center, double size) {
        Fx.playNearby(center, 96.0D, Fx.BEACON_POWER, cfg.volume, 1.3F);
        Fx.playNearby(center, 96.0D, Fx.ORB_PICKUP, cfg.volume, 0.8F);
        if (!cfg.unlockAnimation || size > 128.0D) {
            return;
        }
        double half = size / 2.0D;
        Color color = cfg.themeColor;

        new BukkitRunnable() {
            private int t;

            @Override
            public void run() {
                if (t >= 12) {
                    cancel();
                    return;
                }
                double y = center.getY() + t * 0.35D;
                int steps = (int) Math.min(80.0D, Math.max(8.0D, size));
                for (int i = 0; i <= steps; i++) {
                    double f = (double) i / steps;
                    double offset = -half + size * f;
                    dustAt(world, center.getX() + offset, y, center.getZ() - half, color);
                    dustAt(world, center.getX() + offset, y, center.getZ() + half, color);
                    dustAt(world, center.getX() - half, y, center.getZ() + offset, color);
                    dustAt(world, center.getX() + half, y, center.getZ() + offset, color);
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    // ----------------------------------------------------------------- finale

    public void finish(World world, Location center) {
        Fx.playNearby(center, 128.0D, Fx.CHALLENGE_COMPLETE, cfg.volume, 0.8F);
        Fx.playNearby(center, 128.0D, Fx.ANVIL_LAND, cfg.volume, 0.6F);
        if (cfg.startFireworks) {
            launchFireworks(world, center, 5);
        }
    }

    public void failed(World world, Location center) {
        Fx.playNearby(center, 128.0D, Fx.DRAGON_GROWL, cfg.volume, 0.7F);
        Fx.playNearby(center, 128.0D, Fx.ANVIL_LAND, cfg.volume, 0.5F);
    }

    /**
     * Fireworks are tagged so {@code DamageListener} can drop their explosion damage.
     * A celebration that blows the team up would be a bad way to start a challenge.
     */
    private void launchFireworks(World world, Location center, int waves) {
        new BukkitRunnable() {
            private int wave;

            @Override
            public void run() {
                if (wave >= waves) {
                    cancel();
                    return;
                }
                for (int i = 0; i < 3; i++) {
                    double angle = (Math.PI * 2.0D / 3.0D) * i + wave;
                    Location at = center.clone().add(Math.cos(angle) * 3.0D, 0.5D, Math.sin(angle) * 3.0D);
                    world.spawn(at, Firework.class, firework -> {
                        FireworkMeta meta = firework.getFireworkMeta();
                        meta.addEffect(FireworkEffect.builder()
                                .withColor(cfg.themeColor, Color.WHITE)
                                .withFade(Color.AQUA)
                                .with(FireworkEffect.Type.BALL_LARGE)
                                .withFlicker()
                                .withTrail()
                                .build());
                        meta.setPower(1);
                        firework.setFireworkMeta(meta);
                        firework.getPersistentDataContainer().set(fxKey, PersistentDataType.BYTE, (byte) 1);
                    });
                }
                wave++;
            }
        }.runTaskTimer(plugin, 0L, 12L);
    }

    private static void dustAt(World world, double x, double y, double z, Color color) {
        world.spawnParticle(Particle.DUST, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D,
                new Particle.DustOptions(color, 1.2F));
    }
}
