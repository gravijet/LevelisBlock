package net.gravijet.levelblock.util;

import net.gravijet.levelblock.config.Cfg;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

/** Sends configured, prefixed MiniMessage lines. */
public final class Msg {

    private final Cfg cfg;

    public Msg(Cfg cfg) {
        this.cfg = cfg;
    }

    public Component of(String key, Object... placeholders) {
        return Text.mm(cfg.msg(key), placeholders);
    }

    public Component prefixed(String key, Object... placeholders) {
        return Text.mm(cfg.prefix + cfg.msg(key), placeholders);
    }

    public void send(CommandSender to, String key, Object... placeholders) {
        to.sendMessage(prefixed(key, placeholders));
    }

    public void broadcast(String key, Object... placeholders) {
        Component line = prefixed(key, placeholders);
        Bukkit.getServer().sendMessage(line);
    }

    public void actionBar(org.bukkit.entity.Player player, String key, Object... placeholders) {
        player.sendActionBar(of(key, placeholders));
    }
}
