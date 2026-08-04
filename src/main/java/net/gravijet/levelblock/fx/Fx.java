package net.gravijet.levelblock.fx;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Small effect helpers.
 * <p>
 * Sounds go through Adventure with explicit resource keys rather than {@code org.bukkit.Sound}
 * constants: the Bukkit sound registry gets reshuffled between drops, a namespaced key does not.
 */
public final class Fx {

    public static final Key BEACON_ACTIVATE = Key.key("block.beacon.activate");
    public static final Key BEACON_POWER = Key.key("block.beacon.power_select");
    public static final Key AMETHYST_CHIME = Key.key("block.amethyst_block.chime");
    public static final Key AMETHYST_BREAK = Key.key("block.amethyst_cluster.break");
    public static final Key GLASS_BREAK = Key.key("block.glass.break");
    public static final Key ORB_PICKUP = Key.key("entity.experience_orb.pickup");
    public static final Key LEVEL_UP = Key.key("entity.player.levelup");
    public static final Key NOTE_PLING = Key.key("block.note_block.pling");
    public static final Key NOTE_BASS = Key.key("block.note_block.bass");
    public static final Key CHALLENGE_COMPLETE = Key.key("ui.toast.challenge_complete");
    public static final Key FIREWORK_LAUNCH = Key.key("entity.firework_rocket.launch");
    public static final Key FIREWORK_BLAST = Key.key("entity.firework_rocket.blast");
    public static final Key PORTAL_TRAVEL = Key.key("block.portal.travel");
    public static final Key ANVIL_LAND = Key.key("block.anvil.land");
    public static final Key WOOL_HIT = Key.key("block.wool.hit");
    public static final Key NO = Key.key("block.note_block.didgeridoo");
    public static final Key DRAGON_GROWL = Key.key("entity.ender_dragon.growl");
    public static final Key TOTEM_USE = Key.key("item.totem.use");

    private Fx() {
    }

    public static void play(Player player, Location at, Key key, float volume, float pitch) {
        player.playSound(Sound.sound(key, Sound.Source.MASTER, volume, pitch), at.getX(), at.getY(), at.getZ());
    }

    public static void play(Player player, Key key, float volume, float pitch) {
        player.playSound(Sound.sound(key, Sound.Source.MASTER, volume, pitch), Sound.Emitter.self());
    }

    /** Plays a positioned sound for every player in the world within {@code radius}. */
    public static void playNearby(Location at, double radius, Key key, float volume, float pitch) {
        World world = at.getWorld();
        if (world == null) {
            return;
        }
        double radiusSq = radius * radius;
        Sound sound = Sound.sound(key, Sound.Source.MASTER, volume, pitch);
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(at) <= radiusSq) {
                player.playSound(sound, at.getX(), at.getY(), at.getZ());
            }
        }
    }

    public static void dust(World world, Location at, int count, double spread, Color color, float size) {
        world.spawnParticle(Particle.DUST, at, count, spread, spread, spread, 0.0D,
                new Particle.DustOptions(color, size));
    }

    public static void dust(Player player, Location at, int count, double spread, Color color, float size) {
        player.spawnParticle(Particle.DUST, at, count, spread, spread, spread, 0.0D,
                new Particle.DustOptions(color, size));
    }

    /**
     * Single dust point sent with {@code force}, so the client draws it even on the lowest
     * particle setting and from further away. The barrier line depends on being visible.
     */
    public static void dustForced(Player player, double x, double y, double z, Color color, float size) {
        player.spawnParticle(Particle.DUST, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D,
                new Particle.DustOptions(color, size), true);
    }

    /** Horizontal ring of points around {@code center}; the consumer receives each point. */
    public static void ring(Location center, double radius, int points, java.util.function.Consumer<Location> sink) {
        if (points <= 0) {
            return;
        }
        double step = (Math.PI * 2.0D) / points;
        Location cursor = center.clone();
        for (int i = 0; i < points; i++) {
            double angle = step * i;
            cursor.setX(center.getX() + Math.cos(angle) * radius);
            cursor.setZ(center.getZ() + Math.sin(angle) * radius);
            sink.accept(cursor);
        }
    }

    /** Blends two RGB colours; {@code t} is clamped to {@code [0, 1]}. */
    public static Color lerp(Color from, Color to, double t) {
        double f = Math.max(0.0D, Math.min(1.0D, t));
        return Color.fromRGB(
                (int) Math.round(from.getRed() + (to.getRed() - from.getRed()) * f),
                (int) Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * f),
                (int) Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * f));
    }
}
