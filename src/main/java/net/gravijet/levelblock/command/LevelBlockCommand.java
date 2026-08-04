package net.gravijet.levelblock.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import net.gravijet.levelblock.Mode;
import net.gravijet.levelblock.Sharing;
import net.gravijet.levelblock.config.Cfg;
import net.gravijet.levelblock.core.GameService;
import net.gravijet.levelblock.core.GameState;
import net.gravijet.levelblock.store.Storage;
import net.gravijet.levelblock.util.Msg;
import net.gravijet.levelblock.util.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * {@code /lb} (also {@code /levelblock} and {@code /levelborder}) - everything about the
 * challenge that is not the clock.
 * <p>
 * There is no challenge menu on purpose: with two formats a flat command tree is quicker
 * than clicking through inventories, and nothing is hidden. Every setting the config holds
 * can also be changed live through {@code /lb config}, so a running server never has to be
 * restarted for a tweak.
 */
public final class LevelBlockCommand {

    private static final String ADMIN = "levelblock.admin";
    private static final String BYPASS = "levelblock.bypass";

    /** Config paths offered by {@code /lb config}. */
    private static final List<String> CONFIG_KEYS = List.of(
            "mode",
            "general.creative-bypasses",
            "general.spectator-bypasses",
            "start.area-size",
            "start.countdown-seconds",
            "start.invulnerable-seconds",
            "start.spawn-point-distance",
            "start.reset-players",
            "start.force-survival",
            "start.prepare-platform",
            "start.platform-material",
            "timer.mode",
            "timer.countdown-from-seconds",
            "timer.resume-after-restart",
            "progress.sharing",
            "progress.credits-per-level",
            "progress.starting-credits",
            "unlock.cost",
            "unlock.cost-increase-every",
            "unlock.sneak-blocks",
            "unlock.take-levels",
            "unlock.broadcast",
            "barrier.enabled",
            "barrier.color",
            "barrier.particle-size",
            "barrier.points-per-block",
            "barrier.render-distance",
            "barrier.refresh-ticks",
            "barrier.max-points",
            "barrier.bump-feedback",
            "border.start-size",
            "border.blocks-per-level",
            "border.max-size",
            "border.grow-animation-seconds",
            "border.warning-distance",
            "actionbar.enabled",
            "actionbar.refresh-ticks",
            "actionbar.animate",
            "actionbar.animation-speed",
            "actionbar.bold",
            "effects.color",
            "effects.volume",
            "effects.unlock-animation",
            "effects.start-animation",
            "effects.start-fireworks",
            "protection.restrict-block-edits",
            "protection.restrict-explosions",
            "reset.shutdown-server");

    private final JavaPlugin plugin;
    private final Cfg cfg;
    private final GameState state;
    private final GameService game;
    private final Storage storage;
    private final Msg msg;
    private final Runnable reloadHook;

    public LevelBlockCommand(JavaPlugin plugin, Cfg cfg, GameState state, GameService game,
                             Storage storage, Msg msg, Runnable reloadHook) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.state = state;
        this.game = game;
        this.storage = storage;
        this.msg = msg;
        this.reloadHook = reloadHook;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("levelblock")
                .executes(this::showInfo)
                .then(Commands.literal("info").executes(this::showInfo))
                .then(Commands.literal("help").executes(this::showHelp))
                .then(Commands.literal("top").executes(this::showTop))
                .then(admin("stop").executes(context -> {
                    game.stop(sender(context));
                    return Command.SINGLE_SUCCESS;
                }))
                .then(admin("mode")
                        .executes(context -> {
                            sender(context).sendMessage(Text.mm(
                                    "<gray>Modus:</gray> <white>%mode%</white>", "mode", state.mode().display()));
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("level_block").executes(context -> setMode(context, Mode.LEVEL_BLOCK)))
                        .then(Commands.literal("level_border").executes(context -> setMode(context, Mode.LEVEL_BORDER))))
                .then(admin("xp")
                        .executes(context -> {
                            sender(context).sendMessage(Text.mm(
                                    "<gray>Erfahrung:</gray> <white>%mode%</white>",
                                    "mode", state.sharing().display()));
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("individual")
                                .executes(context -> setSharing(context, Sharing.INDIVIDUAL)))
                        .then(Commands.literal("shared")
                                .executes(context -> setSharing(context, Sharing.SHARED))))
                .then(admin("credits")
                        .then(Commands.literal("set").then(creditArgument((id, value) -> state.credits(id, value))))
                        .then(Commands.literal("give").then(creditArgument(state::addCredits)))
                        .then(Commands.literal("take")
                                .then(creditArgument((id, value) -> state.credits(id, state.credits(id) - value)))))
                .then(admin("config")
                        .then(Commands.argument("option", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
                                    CONFIG_KEYS.stream()
                                            .filter(key -> key.startsWith(typed))
                                            .forEach(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .executes(this::showConfig)
                                .then(Commands.argument("wert", StringArgumentType.greedyString())
                                        .executes(this::setConfig))))
                .then(Commands.literal("bypass")
                        .requires(source -> source.getSender().hasPermission(BYPASS))
                        .executes(this::toggleBypass))
                .then(admin("save").executes(context -> {
                    storage.saveAll(false);
                    sender(context).sendMessage(Text.mm("<green>Gespeichert.</green>"));
                    return Command.SINGLE_SUCCESS;
                }))
                .then(admin("reload").executes(context -> {
                    reloadHook.run();
                    msg.send(sender(context), "reloaded");
                    return Command.SINGLE_SUCCESS;
                }))
                .build();
    }

    // --------------------------------------------------------------- builders

    private static LiteralArgumentBuilder<CommandSourceStack> admin(String name) {
        return Commands.literal(name).requires(source -> source.getSender().hasPermission(ADMIN));
    }

    private RequiredArgumentBuilder<CommandSourceStack, ?> creditArgument(BiConsumer<UUID, Integer> mutation) {
        return Commands.argument("spieler", ArgumentTypes.player())
                .then(Commands.argument("menge", IntegerArgumentType.integer(0))
                        .executes(context -> {
                            Player target = context.getArgument("spieler", PlayerSelectorArgumentResolver.class)
                                    .resolve(context.getSource()).getFirst();
                            mutation.accept(target.getUniqueId(), IntegerArgumentType.getInteger(context, "menge"));
                            msg.send(sender(context), "credits-changed",
                                    "player", target.getName(),
                                    "credits", state.credits(target.getUniqueId()));
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    // ---------------------------------------------------------------- actions

    private int showInfo(CommandContext<CommandSourceStack> context) {
        CommandSender to = sender(context);
        to.sendMessage(Text.mm(cfg.prefix + "<white>Uebersicht</white>"));
        to.sendMessage(game.describeStatus());
        if (to instanceof Player player && state.mode() == Mode.LEVEL_BLOCK) {
            to.sendMessage(msg.of("credits-own", "credits", state.credits(player.getUniqueId())));
        }
        if (to.hasPermission(ADMIN)) {
            to.sendMessage(Text.mm("<dark_gray>/lb help <gray>zeigt alle Befehle.</gray></dark_gray>"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int showHelp(CommandContext<CommandSourceStack> context) {
        CommandSender to = sender(context);
        to.sendMessage(Text.mm(cfg.prefix + "<white>Befehle</white>"));
        to.sendMessage(Text.mm("<gray>/lb <dark_gray>-</dark_gray> Status und eigenes Guthaben</gray>"));
        to.sendMessage(Text.mm("<gray>/blocks <dark_gray>-</dark_gray> freigeschaltete Bloecke und Kosten</gray>"));
        to.sendMessage(Text.mm("<gray>/border <dark_gray>-</dark_gray> aktuelle Bordergroesse</gray>"));
        to.sendMessage(Text.mm("<gray>/lb top <dark_gray>-</dark_gray> Rangliste der gesammelten Level</gray>"));
        if (!to.hasPermission(ADMIN)) {
            return Command.SINGLE_SUCCESS;
        }
        to.sendMessage(Text.mm("""
                <gray>/timer start <dark_gray>|</dark_gray> pause <dark_gray>|</dark_gray> resume \
                <dark_gray>|</dark_gray> reset <dark_gray>|</dark_gray> set <zeit></gray>"""));
        to.sendMessage(Text.mm("<gray>/timer resume <dark_gray>-</dark_gray> weiterspielen: alle in "
                + "Survival und zurueck zum Start</gray>"));
        to.sendMessage(Text.mm("<gray>/border set <groesse> <dark_gray>-</dark_gray> Border von Hand setzen</gray>"));
        to.sendMessage(Text.mm("<gray>/lb stop <dark_gray>-</dark_gray> Challenge beenden</gray>"));
        to.sendMessage(Text.mm("<gray>/lb mode <white>level_block|level_border</white></gray>"));
        to.sendMessage(Text.mm("<gray>/lb xp <white>individual|shared</white> "
                + "<dark_gray>-</dark_gray> eigene oder geteilte Erfahrung</gray>"));
        to.sendMessage(Text.mm("<gray>/lb credits <white>set|give|take</white> <spieler> <menge></gray>"));
        to.sendMessage(Text.mm("<gray>/lb config <option> [wert] "
                + "<dark_gray>-</dark_gray> jede Einstellung live aendern</gray>"));
        to.sendMessage(Text.mm("<gray>/lb bypass <dark_gray>|</dark_gray> save <dark_gray>|</dark_gray> reload</gray>"));
        to.sendMessage(Text.mm("<gray>/reset confirm <dark_gray>-</dark_gray> alle Welten loeschen "
                + "und mit neuem Seed neu generieren</gray>"));
        return Command.SINGLE_SUCCESS;
    }

    private int showTop(CommandContext<CommandSourceStack> context) {
        CommandSender to = sender(context);
        List<Map.Entry<UUID, Long>> ranking = state.levelsByPlayer().entrySet().stream()
                .sorted(Map.Entry.<UUID, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(10L)
                .toList();

        if (ranking.isEmpty()) {
            msg.send(to, "top-empty");
            return Command.SINGLE_SUCCESS;
        }
        to.sendMessage(msg.prefixed("top-header"));
        int rank = 1;
        for (Map.Entry<UUID, Long> entry : ranking) {
            to.sendMessage(msg.of("top-entry",
                    "rank", rank++,
                    "player", state.nameOf(entry.getKey()),
                    "levels", entry.getValue()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int toggleBypass(CommandContext<CommandSourceStack> context) {
        CommandSender sender = sender(context);
        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg.prefixed("players-only"));
            return Command.SINGLE_SUCCESS;
        }
        UUID id = player.getUniqueId();
        if (game.bypassing().remove(id)) {
            msg.send(player, "bypass-off");
        } else {
            game.bypassing().add(id);
            msg.send(player, "bypass-on");
        }
        return Command.SINGLE_SUCCESS;
    }

    private int setMode(CommandContext<CommandSourceStack> context, Mode mode) {
        game.switchMode(mode);
        plugin.getConfig().set("mode", mode.name());
        plugin.saveConfig();
        msg.broadcast("mode-changed", "mode", mode.display());
        return Command.SINGLE_SUCCESS;
    }

    private int setSharing(CommandContext<CommandSourceStack> context, Sharing sharing) {
        game.switchSharing(sharing);
        plugin.getConfig().set("progress.sharing", sharing.name());
        plugin.saveConfig();
        msg.broadcast("xp-mode-changed", "mode", sharing.display());
        return Command.SINGLE_SUCCESS;
    }

    // ----------------------------------------------------------------- config

    private int showConfig(CommandContext<CommandSourceStack> context) {
        String key = StringArgumentType.getString(context, "option");
        CommandSender to = sender(context);
        if (!plugin.getConfig().contains(key)) {
            to.sendMessage(Text.mm("<red>Unbekannte Option <white>%key%</white>.</red>", "key", key));
            return Command.SINGLE_SUCCESS;
        }
        msg.send(to, "config-value", "key", key, "value", String.valueOf(plugin.getConfig().get(key)));
        return Command.SINGLE_SUCCESS;
    }

    /** Writes a value into config.yml and reloads, so every setting is live-editable. */
    private int setConfig(CommandContext<CommandSourceStack> context) {
        String key = StringArgumentType.getString(context, "option");
        String raw = StringArgumentType.getString(context, "wert").trim();
        CommandSender to = sender(context);

        if (!plugin.getConfig().contains(key)) {
            to.sendMessage(Text.mm("<red>Unbekannte Option <white>%key%</white>.</red>", "key", key));
            return Command.SINGLE_SUCCESS;
        }
        plugin.getConfig().set(key, coerce(raw));
        plugin.saveConfig();
        reloadHook.run();
        // mode and progress.sharing live in the state as well, so keep both in step.
        if (key.equals("mode")) {
            game.switchMode(Mode.parse(raw, state.mode()));
        } else if (key.equals("progress.sharing")) {
            game.switchSharing(Sharing.parse(raw, state.sharing()));
        }
        msg.send(to, "config-set", "key", key, "value", raw);
        return Command.SINGLE_SUCCESS;
    }

    /** Turns the raw command text into the YAML type the option expects. */
    private static Object coerce(String raw) {
        if (raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(raw);
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException ignored) {
            // not an int
        }
        try {
            return Double.valueOf(raw);
        } catch (NumberFormatException ignored) {
            // keep it a string
        }
        return raw;
    }

    private static CommandSender sender(CommandContext<CommandSourceStack> context) {
        return context.getSource().getSender();
    }
}
