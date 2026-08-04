package net.gravijet.levelblock.core;

import net.gravijet.levelblock.config.Cfg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * Lets players walk through each other.
 * <p>
 * On a 3x3 starting patch collision is genuinely hostile: two people standing on it push
 * each other around, and the one who gets shoved is the one who walks into the barrier and
 * spends a level nobody meant to spend. Minecraft only exposes this through a scoreboard
 * team, so the plugin keeps one team on the main scoreboard with collision switched off
 * and puts everybody in it.
 */
public final class CollisionService {

    private static final String TEAM_NAME = "challenge_nocollide";

    private final Cfg cfg;

    public CollisionService(Cfg cfg) {
        this.cfg = cfg;
    }

    /** Applies the current setting to everyone online. Safe to call again after a reload. */
    public void apply() {
        if (cfg.playerCollision) {
            disband();
            return;
        }
        Team team = team();
        if (team == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            addTo(team, player);
        }
    }

    public void add(Player player) {
        if (cfg.playerCollision) {
            return;
        }
        Team team = team();
        if (team != null) {
            addTo(team, player);
        }
    }

    /** Drops the team again, e.g. on shutdown, so nothing is left behind on the scoreboard. */
    public void disband() {
        Scoreboard board = board();
        if (board == null) {
            return;
        }
        Team team = board.getTeam(TEAM_NAME);
        if (team != null) {
            team.unregister();
        }
    }

    private static void addTo(Team team, Player player) {
        // A player can only be in one team, so leave anybody another plugin already
        // claimed alone rather than silently stealing them.
        Team current = team.getScoreboard().getEntryTeam(player.getName());
        if (current != null && !current.equals(team)) {
            return;
        }
        if (current == null) {
            team.addEntry(player.getName());
        }
    }

    private Team team() {
        Scoreboard board = board();
        if (board == null) {
            return null;
        }
        Team team = board.getTeam(TEAM_NAME);
        if (team == null) {
            team = board.registerNewTeam(TEAM_NAME);
        }
        team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        return team;
    }

    private static Scoreboard board() {
        return Bukkit.getScoreboardManager().getMainScoreboard();
    }
}
