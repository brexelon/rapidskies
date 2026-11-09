package us.rapidskies.hub;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import us.rapidskies.common.config.ConfigView;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.parkour.ParkourTimes;
import us.rapidskies.common.registry.ServerRegistry;
import us.rapidskies.core.RapidCore;
import us.rapidskies.core.world.SpawnPoint;
import us.rapidskies.hub.display.Labels;
import us.rapidskies.hub.leaderboard.Leaderboards;
import us.rapidskies.hub.npc.HubNpcs;
import us.rapidskies.hub.parkour.ParkourCommand;
import us.rapidskies.hub.parkour.ParkourService;

/**
 * rapid-hub (PLAN.md §4, phase 2): the hub hotbar (game menu, player visibility), the mode
 * NPCs on the stage, double jump and hub flight, parkour and the leaderboards. The spawn,
 * build protection and void rescue are rapid-core's ({@code WorldService}).
 *
 * <p>NPCs, labels and leaderboards are rebuilt from the config on every start and on
 * {@code /rapidhub reload}; none of them is ever saved into the world.
 */
public final class RapidHub extends JavaPlugin {

    private HubSettings settings;
    private Lang lang;
    private Labels labels;
    private ModeCounts counts;
    private ModeSelector selector;
    private HubHotbar hotbar;
    private DoubleJump jump;
    private ParkourService parkour;
    private Leaderboards leaderboards;
    private HubNpcs npcs;

    @Override
    public void onEnable() {
        try {
            lang = loadLang();
        } catch (IOException e) {
            getSLF4JLogger().error("rapid-hub can't read its messages; disabling it", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            settings = loadSettings();
        } catch (IOException | IllegalArgumentException e) {
            getSLF4JLogger().error("rapid-hub's config is invalid, so the hub runs without modes, NPCs, parkour "
                    + "or leaderboards until it's fixed (/rapidhub reload): {}", e.getMessage());
            settings = HubSettings.from(ConfigView.empty());
        }

        RapidCore core = core();
        labels = new Labels(this);
        counts = new ModeCounts(this, new ServerRegistry(core.redis()));
        selector = new ModeSelector(this);
        hotbar = new HubHotbar(this);
        jump = new DoubleJump(this);
        parkour = new ParkourService(this, new ParkourTimes(core.database()));
        leaderboards = new Leaderboards(this);

        var pm = getServer().getPluginManager();
        pm.registerEvents(hotbar, this);
        pm.registerEvents(jump, this);
        pm.registerEvents(parkour, this);

        BasicCommand games = new BasicCommand() {
            @Override
            public void execute(CommandSourceStack stack, String[] args) {
                if (stack.getSender() instanceof Player player) {
                    selector.open(player);
                } else {
                    lang.send(stack.getSender(), "errors.players-only");
                }
            }

            @Override
            public String permission() {
                return "rapid.command.games";
            }
        };
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register("games", "Open the game menu", List.of("game", "menu"), games);
            event.registrar().register("parkour", "Hub parkour: start, your best, leave, checkpoint, restart, top",
                    List.of("pk"), new ParkourCommand(this));
            event.registrar().register("fly", "Fly in the hub", List.of(), jump.flyCommand());
            event.registrar().register("rapidhub", "rapid-hub admin: here, reload", List.of(),
                    new HubAdminCommand(this));
        });

        counts.start();
        parkour.start();
        if (pm.getPlugin("PlaceholderAPI") != null) {
            // Its own class, so PlaceholderAPI's types are only loaded when it's installed.
            new us.rapidskies.hub.display.HubPlaceholders(this).register();
        }
        // After every plugin (Citizens) is enabled and the worlds are loaded.
        Bukkit.getScheduler().runTask(this, () -> {
            build();
            Bukkit.getOnlinePlayers().forEach(this::setUp);
        });
        getSLF4JLogger().info("rapid-hub {} enabled: {} modes, {} parkour courses, {} leaderboards",
                getPluginMeta().getVersion(), settings.modes().size(), settings.courses().size(),
                settings.boards().size());
    }

    @Override
    public void onDisable() {
        if (parkour == null) {
            return;
        }
        teardown();
        parkour.stop();
        counts.stop();
        hotbar.showAll();
        jump.resetAll();
    }

    private Lang loadLang() throws IOException {
        Path data = getDataPath();
        return Lang.load(getClassLoader(), Lang.DEFAULT_RESOURCE, data.resolve(Lang.DEFAULT_RESOURCE),
                getSLF4JLogger());
    }

    private HubSettings loadSettings() throws IOException {
        return HubSettings.from(ConfigView.loadOrCreate(getClassLoader(), "config.yml",
                getDataPath().resolve("config.yml")));
    }

    private void build() {
        parkour.load(settings);
        leaderboards.start(settings.boards());
        if (getServer().getPluginManager().isPluginEnabled("Citizens")) {
            // Its own class, so Citizens' types are only loaded when it's installed.
            npcs = new us.rapidskies.hub.npc.CitizensNpcs(this);
            npcs.start();
        } else if (settings.modes().stream().anyMatch(m -> m.npc().isPresent())) {
            getSLF4JLogger().warn("Citizens isn't installed, so there are no mode NPCs (the game menu still works)");
        }
    }

    private void teardown() {
        parkour.endAll();
        leaderboards.stop();
        if (npcs != null) {
            npcs.stop();
            npcs = null;
        }
        labels.removeAll();
    }

    private void setUp(Player player) {
        hotbar.setUp(player);
        jump.refresh(player);
    }

    /**
     * Re-reads the config and messages and rebuilds everything from them. Running parkour
     * runs end. Returns false (changing nothing) if the new config is invalid.
     */
    public boolean reload() {
        HubSettings next;
        Lang nextLang;
        try {
            next = loadSettings();
            nextLang = loadLang();
        } catch (IOException | IllegalArgumentException e) {
            getSLF4JLogger().error("rapid-hub reload failed, keeping the running config: {}", e.getMessage());
            return false;
        }
        teardown();
        settings = next;
        lang = nextLang;
        build();
        Bukkit.getOnlinePlayers().forEach(this::setUp);
        return true;
    }

    /** Player counts changed: redraw what shows them. */
    void countsChanged() {
        if (npcs != null) {
            npcs.updateLabels();
        }
    }

    /** A configured position as a location, or null (logged) if its world isn't loaded. */
    public Location labelLocation(SpawnPoint point, String where) {
        Location at = Labels.location(point);
        if (at == null) {
            getSLF4JLogger().error("{}: world '{}' isn't loaded", where, point.world());
        }
        return at;
    }

    // ------------------------------------------------------------------ parts, for each other

    public RapidCore core() {
        return RapidCore.get();
    }

    public HubSettings settings() {
        return settings;
    }

    public Lang lang() {
        return lang;
    }

    public Labels labels() {
        return labels;
    }

    public ModeCounts counts() {
        return counts;
    }

    public ModeSelector selector() {
        return selector;
    }

    public HubHotbar hotbar() {
        return hotbar;
    }

    public DoubleJump jump() {
        return jump;
    }

    public ParkourService parkour() {
        return parkour;
    }

    public Leaderboards leaderboards() {
        return leaderboards;
    }
}
