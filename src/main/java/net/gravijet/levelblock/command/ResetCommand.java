package net.gravijet.levelblock.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.gravijet.levelblock.config.Cfg;
import net.gravijet.levelblock.util.Msg;
import net.gravijet.levelblock.util.Text;
import net.gravijet.levelblock.world.WorldReset;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;

/**
 * {@code /reset} - wipes every world and stops the server so it comes back on a fresh
 * random seed. Guarded behind an explicit {@code confirm} because there is no undo.
 */
public final class ResetCommand {

    private static final String ADMIN = "levelblock.admin";

    private final JavaPlugin plugin;
    private final Cfg cfg;
    private final WorldReset worldReset;
    private final Msg msg;
    private final Consumer<Boolean> shutdownHook;

    public ResetCommand(JavaPlugin plugin, Cfg cfg, WorldReset worldReset, Msg msg,
                        Consumer<Boolean> shutdownHook) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.worldReset = worldReset;
        this.msg = msg;
        this.shutdownHook = shutdownHook;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("reset")
                .requires(source -> source.getSender().hasPermission(ADMIN))
                .executes(context -> {
                    msg.send(context.getSource().getSender(), "reset-world-confirm");
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("confirm").executes(this::perform))
                .build();
    }

    private int perform(CommandContext<CommandSourceStack> context) {
        CommandSender initiator = context.getSource().getSender();

        List<File> folders = worldReset.arm();
        if (folders.isEmpty()) {
            initiator.sendMessage(Text.mm(
                    "<red>Es wurde keine loeschbare Welt gefunden - siehe Konsole.</red>"));
            return Command.SINGLE_SUCCESS;
        }
        plugin.getLogger().info("Weltreset angefordert von " + initiator.getName()
                + " - zu loeschen: " + folders.size() + " Welt(en).");

        // Stop the plugin from writing its state back out over the reset.
        shutdownHook.accept(Boolean.TRUE);
        msg.broadcast("reset-world-start");

        if (!cfg.resetShutdown) {
            initiator.sendMessage(msg.prefixed("reset-world-manual"));
            return Command.SINGLE_SUCCESS;
        }

        Component kickMessage = msg.of("reset-kick");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.kick(kickMessage);
            }
            Bukkit.shutdown();
        }, 40L);
        return Command.SINGLE_SUCCESS;
    }
}
