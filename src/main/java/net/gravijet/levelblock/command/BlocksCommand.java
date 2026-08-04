package net.gravijet.levelblock.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.gravijet.levelblock.config.Cfg;
import net.gravijet.levelblock.core.GameState;
import net.gravijet.levelblock.core.RegionService;
import net.gravijet.levelblock.core.UnlockService;
import net.gravijet.levelblock.util.Msg;
import net.gravijet.levelblock.util.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /blocks} - how much of the world is open and what the next block costs.
 * <p>
 * The action bar only carries the clock, so the numbers everybody keeps asking for get a
 * command of their own. Free for all players, no permission: it is pure information about
 * a run they are playing anyway.
 */
public final class BlocksCommand {

    private final Cfg cfg;
    private final GameState state;
    private final RegionService regions;
    private final UnlockService unlocks;
    private final Msg msg;

    public BlocksCommand(Cfg cfg, GameState state, RegionService regions, UnlockService unlocks, Msg msg) {
        this.cfg = cfg;
        this.state = state;
        this.regions = regions;
        this.unlocks = unlocks;
        this.msg = msg;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("blocks").executes(this::show).build();
    }

    private int show(CommandContext<CommandSourceStack> context) {
        CommandSender to = context.getSource().getSender();
        int credits = to instanceof Player player ? state.credits(player.getUniqueId()) : 0;

        to.sendMessage(Text.mm(cfg.prefix + "<white>Bloecke</white>"));
        to.sendMessage(msg.of("blocks-info",
                "blocks", regions.totalColumns(),
                "credits", credits,
                "cost", unlocks.currentCost(),
                "levels", state.totalLevels()));
        return Command.SINGLE_SUCCESS;
    }
}
