package net.gravijet.levelblock;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.gravijet.levelblock.command.BlocksCommand;
import net.gravijet.levelblock.command.BorderCommand;
import net.gravijet.levelblock.command.LevelBlockCommand;
import net.gravijet.levelblock.command.LevelsCommand;
import net.gravijet.levelblock.command.ResetCommand;
import net.gravijet.levelblock.command.TimerCommand;
import net.gravijet.levelblock.config.Cfg;
import net.gravijet.levelblock.core.BorderService;
import net.gravijet.levelblock.core.CollisionService;
import net.gravijet.levelblock.core.GameService;
import net.gravijet.levelblock.core.GameState;
import net.gravijet.levelblock.core.RegionService;
import net.gravijet.levelblock.core.UnlockService;
import net.gravijet.levelblock.fx.AnimationService;
import net.gravijet.levelblock.fx.BarrierRenderer;
import net.gravijet.levelblock.hud.ActionBarService;
import net.gravijet.levelblock.listener.ContainmentListener;
import net.gravijet.levelblock.listener.DamageListener;
import net.gravijet.levelblock.listener.PlayerListener;
import net.gravijet.levelblock.listener.ProgressListener;
import net.gravijet.levelblock.store.Storage;
import net.gravijet.levelblock.util.Msg;
import net.gravijet.levelblock.world.WorldReset;
import net.gravijet.levelblock.world.WorldService;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

/** Plugin entry point: builds the service graph, wires the listeners and runs the loops. */
public final class Main extends JavaPlugin {

    private Cfg cfg;
    private GameState state;
    private RegionService regions;
    private Storage storage;
    private ActionBarService actionBar;
    private BarrierRenderer barrier;
    private BorderService border;
    private GameService game;
    private CollisionService collisions;

    private final List<BukkitTask> tasks = new ArrayList<>();
    private boolean resetting;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        NamespacedKey fxKey = new NamespacedKey(this, "fx");

        cfg = new Cfg(this);
        cfg.load();

        Msg msg = new Msg(cfg);
        state = new GameState();
        regions = new RegionService();
        storage = new Storage(this, cfg, state, regions);
        storage.load();

        WorldService worlds = new WorldService(cfg, state);
        AnimationService animations = new AnimationService(this, cfg, fxKey);
        border = new BorderService(cfg, state, worlds, animations);
        actionBar = new ActionBarService(cfg, state);
        barrier = new BarrierRenderer(cfg, state, regions);
        game = new GameService(this, cfg, state, regions, worlds, border, animations, storage, msg);
        collisions = new CollisionService(cfg);
        UnlockService unlocks = new UnlockService(cfg, state, regions, game, animations, msg);

        ContainmentListener containment =
                new ContainmentListener(cfg, state, regions, game, unlocks, border);
        getServer().getPluginManager().registerEvents(containment, this);
        getServer().getPluginManager().registerEvents(new PlayerListener(
                this, state, regions, game, unlocks, border, worlds, collisions, containment), this);
        getServer().getPluginManager().registerEvents(new ProgressListener(this, state, game, worlds), this);
        getServer().getPluginManager().registerEvents(new DamageListener(game, worlds, fxKey), this);

        WorldReset worldReset = new WorldReset(this);
        TimerCommand timer = new TimerCommand(state, game, msg);
        BlocksCommand blocks = new BlocksCommand(cfg, state, regions, unlocks, game, msg);
        BorderCommand borderCommand = new BorderCommand(cfg, state, border, msg);
        LevelsCommand levels = new LevelsCommand(cfg, worlds, msg);
        LevelBlockCommand admin =
                new LevelBlockCommand(this, cfg, state, game, storage, msg, this::reloadEverything);
        ResetCommand reset = new ResetCommand(this, cfg, worldReset, msg, this::beginReset);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register(timer.build(),
                    "Startet, pausiert und stellt die Zeit der Challenge");
            event.registrar().register(blocks.build(),
                    "Zeigt freigeschaltete Bloecke, Guthaben und die naechsten Kosten");
            event.registrar().register(borderCommand.build(),
                    "Zeigt die Bordergroesse und setzt sie mit /border set");
            event.registrar().register(levels.build(),
                    "Zeigt die Level aller Spieler in der Challenge");
            event.registrar().register(admin.build(),
                    "Steuert die Level = Block / Level = Border Challenge",
                    List.of("lb", "levelborder"));
            event.registrar().register(reset.build(),
                    "Loescht alle Welten und startet den Server mit neuem Seed neu");
        });

        // Worlds and players are only fully available once the server finished starting.
        getServer().getScheduler().runTask(this, () -> {
            worldReset.reportPreviousReset();
            border.apply(false);
            collisions.apply();
        });

        startLoops();
    }

    private void startLoops() {
        tasks.add(getServer().getScheduler().runTaskTimer(this,
                () -> actionBar.tick(), 20L, cfg.actionbarRefreshTicks));

        tasks.add(getServer().getScheduler().runTaskTimer(this,
                () -> barrier.tick(), 25L, cfg.barrierRefreshTicks));

        // Safety net for everything a move event cannot see, plus the countdown timer.
        tasks.add(getServer().getScheduler().runTaskTimer(this, () -> {
            game.enforceAll();
            game.checkTimerExpiry();
        }, 30L, 10L));

        long autosaveTicks = cfg.autosaveSeconds * 20L;
        tasks.add(getServer().getScheduler().runTaskTimer(this, () -> {
            if (state.dirty()) {
                storage.saveAll(true);
            }
        }, autosaveTicks, autosaveTicks));
    }

    /** Reloads config.yml and restarts the loops so new tick rates take effect at once. */
    private void reloadEverything() {
        cfg.load();
        stopLoops();
        startLoops();
        // Border sizes and the warning distance are read when the border is pushed out,
        // so a changed value only reaches the clients if we push it again.
        border.apply(false);
        collisions.apply();
    }

    /** Called by {@code /reset}: drop the stored data and stop writing any more of it. */
    private void beginReset(boolean confirmed) {
        if (!confirmed) {
            return;
        }
        resetting = true;
        storage.wipe();
    }

    private void stopLoops() {
        for (BukkitTask task : tasks) {
            task.cancel();
        }
        tasks.clear();
    }

    @Override
    public void onDisable() {
        stopLoops();

        if (game != null) {
            game.shutdown();
        }
        if (collisions != null) {
            collisions.disband();
        }
        if (storage != null && !resetting) {
            storage.saveAll(false);
        }
    }
}
