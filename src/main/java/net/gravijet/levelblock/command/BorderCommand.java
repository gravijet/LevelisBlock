package net.gravijet.levelblock.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.gravijet.levelblock.Mode;
import net.gravijet.levelblock.config.Cfg;
import net.gravijet.levelblock.core.BorderService;
import net.gravijet.levelblock.core.GameState;
import net.gravijet.levelblock.hud.ActionBarService;
import net.gravijet.levelblock.util.Msg;
import net.gravijet.levelblock.util.Text;
import org.bukkit.command.CommandSender;

/**
 * {@code /border} - the current ring size, and {@code /border set <groesse>} to change it.
 * <p>
 * Only exists while {@link Mode#LEVEL_BORDER} is being played. Reading it is free for
 * everyone; changing it needs the admin permission. The size is stored in the game state,
 * so an admin correction survives the next level-up instead of being recomputed away.
 */
public final class BorderCommand {

    private static final String ADMIN = "levelblock.admin";

    private final Cfg cfg;
    private final GameState state;
    private final BorderService border;
    private final Msg msg;

    public BorderCommand(Cfg cfg, GameState state, BorderService border, Msg msg) {
        this.cfg = cfg;
        this.state = state;
        this.border = border;
        this.msg = msg;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("border")
                .executes(this::show)
                .then(Commands.literal("set")
                        .requires(source -> source.getSender().hasPermission(ADMIN))
                        .then(Commands.argument("groesse", DoubleArgumentType.doubleArg(1.0D, 59_999_968.0D))
                                .executes(this::set)))
                .build();
    }

    private boolean wrongMode(CommandSender to) {
        if (state.mode() == Mode.LEVEL_BORDER) {
            return false;
        }
        msg.send(to, "only-in-mode", "mode", Mode.LEVEL_BORDER.display());
        return true;
    }

    private int show(CommandContext<CommandSourceStack> context) {
        CommandSender to = context.getSource().getSender();
        if (wrongMode(to)) {
            return Command.SINGLE_SUCCESS;
        }
        to.sendMessage(Text.mm(cfg.prefix + "<white>Border</white>"));
        to.sendMessage(msg.of("border-info",
                "size", ActionBarService.formatSize(border.targetSize()),
                "per", ActionBarService.formatSize(cfg.borderPerLevel),
                "next", ActionBarService.formatSize(border.nextSize()),
                "levels", state.totalLevels()));
        if (to.hasPermission(ADMIN)) {
            to.sendMessage(Text.mm("<dark_gray>/border set <groesse></dark_gray>"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int set(CommandContext<CommandSourceStack> context) {
        CommandSender to = context.getSource().getSender();
        if (wrongMode(to)) {
            return Command.SINGLE_SUCCESS;
        }
        border.setSize(DoubleArgumentType.getDouble(context, "groesse"), true);
        msg.send(to, "border-set", "size", ActionBarService.formatSize(border.targetSize()));
        return Command.SINGLE_SUCCESS;
    }
}
