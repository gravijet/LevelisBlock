package net.gravijet.levelblock.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.gravijet.levelblock.Mode;
import net.gravijet.levelblock.config.Cfg;
import net.gravijet.levelblock.core.GameService;
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
 * Only exists while {@link Mode#LEVEL_BLOCK} is being played; there are no blocks to
 * unlock in border mode, so the command says so rather than printing zeroes.
 */
public final class BlocksCommand {

    private final Cfg cfg;
    private final GameState state;
    private final RegionService regions;
    private final UnlockService unlocks;
    private final GameService game;
    private final Msg msg;

    public BlocksCommand(Cfg cfg, GameState state, RegionService regions, UnlockService unlocks,
                         GameService game, Msg msg) {
        this.cfg = cfg;
        this.state = state;
        this.regions = regions;
        this.unlocks = unlocks;
        this.game = game;
        this.msg = msg;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("blocks").executes(this::show).build();
    }

    private int show(CommandContext<CommandSourceStack> context) {
        CommandSender to = context.getSource().getSender();
        if (state.mode() != Mode.LEVEL_BLOCK) {
            msg.send(to, "only-in-mode", "mode", Mode.LEVEL_BLOCK.display());
            return Command.SINGLE_SUCCESS;
        }
        int levels = to instanceof Player player ? game.availableLevels(player) : 0;

        to.sendMessage(Text.mm(cfg.prefix + "<white>Bloecke</white>"));
        to.sendMessage(msg.of("blocks-info",
                "blocks", regions.totalColumns(),
                "cost", unlocks.currentCost(),
                "levels", levels));
        return Command.SINGLE_SUCCESS;
    }
}
