package net.gravijet.levelblock.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.gravijet.levelblock.core.GameService;
import net.gravijet.levelblock.core.GameState;
import net.gravijet.levelblock.util.Msg;
import net.gravijet.levelblock.util.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /timer} - running the clock, and nothing else.
 * <p>
 * Deliberately small: start, pause, resume, reset and set are what you reach for mid-round,
 * so they get a command that has no other branches to tab through. Everything about the
 * challenge itself - mode, experience model, config - lives in {@code /lb}.
 */
public final class TimerCommand {

    private static final String ADMIN = "levelblock.admin";

    private final GameState state;
    private final GameService game;
    private final Msg msg;

    public TimerCommand(GameState state, GameService game, Msg msg) {
        this.state = state;
        this.game = game;
        this.msg = msg;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("timer")
                .executes(this::showTimer)
                .then(admin("start").executes(this::start))
                .then(admin("pause").executes(context -> {
                    game.pause(sender(context));
                    return Command.SINGLE_SUCCESS;
                }))
                .then(admin("resume").executes(context -> {
                    game.resume(sender(context));
                    return Command.SINGLE_SUCCESS;
                }))
                .then(admin("reset").executes(context -> {
                    game.resetGame(sender(context));
                    return Command.SINGLE_SUCCESS;
                }))
                .then(admin("set")
                        .then(Commands.argument("zeit", StringArgumentType.word())
                                .executes(this::setTimer)))
                .build();
    }

    private static LiteralArgumentBuilder<CommandSourceStack> admin(String name) {
        return Commands.literal(name).requires(source -> source.getSender().hasPermission(ADMIN));
    }

    // ---------------------------------------------------------------- actions

    private int start(CommandContext<CommandSourceStack> context) {
        CommandSender sender = sender(context);
        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg.prefixed("players-only"));
            return Command.SINGLE_SUCCESS;
        }
        game.start(player);
        return Command.SINGLE_SUCCESS;
    }

    private int showTimer(CommandContext<CommandSourceStack> context) {
        CommandSender to = sender(context);
        to.sendMessage(game.describeStatus());
        if (to.hasPermission(ADMIN)) {
            to.sendMessage(Text.mm("<dark_gray>/timer start | pause | resume | reset | set <zeit>"
                    + "  <gray>-</gray>  /lb <gray>fuer alles andere</gray></dark_gray>"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int setTimer(CommandContext<CommandSourceStack> context) {
        String raw = StringArgumentType.getString(context, "zeit");
        long seconds = Text.parseTime(raw);
        CommandSender to = sender(context);
        if (seconds < 0L) {
            to.sendMessage(Text.mm("<red>Ungueltige Zeit. Beispiele: <white>90</white>, "
                    + "<white>10m</white>, <white>1:30:00</white></red>"));
            return Command.SINGLE_SUCCESS;
        }
        state.elapsedSeconds(seconds);
        msg.send(to, "timer-set", "time", Text.formatTime(seconds));
        return Command.SINGLE_SUCCESS;
    }

    private static CommandSender sender(CommandContext<CommandSourceStack> context) {
        return context.getSource().getSender();
    }
}
