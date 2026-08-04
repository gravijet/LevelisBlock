package net.gravijet.levelblock.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.gravijet.levelblock.config.Cfg;
import net.gravijet.levelblock.util.Msg;
import net.gravijet.levelblock.util.Text;
import net.gravijet.levelblock.world.WorldService;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code /levels} - what everybody currently has to spend.
 * <p>
 * Levels are the currency of the whole plugin, so this is the number people actually keep
 * asking each other for. It shows the bar as a percentage as well, because "level 4" reads
 * very differently at 5% than at 95% when the next block costs a level.
 */
public final class LevelsCommand {

    private final Cfg cfg;
    private final WorldService worlds;
    private final Msg msg;

    public LevelsCommand(Cfg cfg, WorldService worlds, Msg msg) {
        this.cfg = cfg;
        this.worlds = worlds;
        this.msg = msg;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("levels").executes(this::show).build();
    }

    private int show(CommandContext<CommandSourceStack> context) {
        CommandSender to = context.getSource().getSender();

        List<Player> players = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (worlds.isGameWorld(player.getWorld())) {
                players.add(player);
            }
        }
        // Highest first, then by name so the order does not jump around between calls.
        players.sort(Comparator.comparingInt(Player::getLevel).reversed()
                .thenComparing(Player::getName, String.CASE_INSENSITIVE_ORDER));

        to.sendMessage(Text.mm(cfg.prefix + "<white>Level</white>"));
        if (players.isEmpty()) {
            to.sendMessage(msg.of("levels-empty"));
            return Command.SINGLE_SUCCESS;
        }
        for (Player player : players) {
            to.sendMessage(msg.of("levels-entry",
                    "player", player.getName(),
                    "level", player.getLevel(),
                    "next", player.getLevel() + 1,
                    "percent", Math.round(player.getExp() * 100.0F)));
        }
        return Command.SINGLE_SUCCESS;
    }
}
