package us.rapidskies.skyrealms;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import us.rapidskies.common.config.ConfigView;
import us.rapidskies.common.db.Database;
import us.rapidskies.common.economy.EconomyMode;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.core.RapidCore;
import us.rapidskies.skyrealms.data.SkyRealmsRepository;
import us.rapidskies.skyrealms.expedition.ExpeditionService;
import us.rapidskies.skyrealms.island.IslandEvents;
import us.rapidskies.skyrealms.island.IslandSchematics;
import us.rapidskies.skyrealms.island.Islands;
import us.rapidskies.skyrealms.route.RouteService;
import us.rapidskies.skyrealms.season.SeasonService;
import us.rapidskies.skyrealms.shop.ShopService;
import us.rapidskies.skyrealms.tier.TierService;

/**
 * rapid-skyrealms, the SkyRealms co-op SkyBlock mode (PLAN.md §5.1, phase 4b), on top of
 * SuperiorSkyblock2 (islands, members, upgrades, island value):
 *
 * <ul>
 *   <li>starter islands built in code and registered as SuperiorSkyblock2 schematics
 *       ({@link IslandSchematics}, from {@link #onLoad});</li>
 *   <li>Realm Tiers, Meadow → Desert → Frost ({@code /realm}, {@link TierService}): goals,
 *       generator odds, biomes, shop categories;</li>
 *   <li>the server shop, where Coins are earned ({@code /shop}, {@code /sell});</li>
 *   <li>Expeditions in their own world ({@code /expedition}, {@link ExpeditionService});</li>
 *   <li>Trade Routes between islands ({@code /route}, {@link RouteService});</li>
 *   <li>seasons of the island-value leaderboard ({@code /season}, {@link SeasonService}).</li>
 * </ul>
 *
 * <p>Island level adds auction house listings when rapid-market is installed.
 */
public final class RapidSkyRealms extends JavaPlugin implements Listener {

    private SkyRealmsSettings settings;
    private Lang lang;
    private final Islands islands = new Islands();
    private SkyRealmsRepository repo;
    private Announcer announcer;
    private TierService tiers;
    private ShopService shop;
    private RouteService routes;
    private ExpeditionService expeditions;
    private SeasonService seasons;
    private boolean market;
    private boolean started;

    /**
     * Before SuperiorSkyblock2 enables (and reads its schematics): the island layouts go in. A
     * bad config is reported in onEnable.
     */
    @Override
    public void onLoad() {
        try {
            settings = loadSettings();
        } catch (IOException | IllegalArgumentException e) {
            return;
        }
        try {
            IslandSchematics.install(this, settings.islands().layouts(), () -> settings.islands().starterChest(),
                    getSLF4JLogger());
        } catch (RuntimeException | LinkageError e) {
            getSLF4JLogger().error("Couldn't register the island layouts with SuperiorSkyblock2", e);
        }
    }

    @Override
    public void onEnable() {
        try {
            lang = loadLang();
            settings = loadSettings();
        } catch (IOException | IllegalArgumentException e) {
            getSLF4JLogger().error("rapid-skyrealms can't start (fix the config, then restart): {}", e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (core().economy() == null || core().economy().mode() != EconomyMode.SKYREALMS) {
            getSLF4JLogger().error("rapid-core isn't serving Coins here (server.gamemode must be skyrealms); "
                    + "disabling rapid-skyrealms");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        repo = new SkyRealmsRepository(core().database());
        announcer = new Announcer(this);
        tiers = new TierService(this);
        shop = new ShopService(this);
        routes = new RouteService(this);
        expeditions = new ExpeditionService(this);
        seasons = new SeasonService(this);
        try {
            core().database().migrate(getClassLoader(), "skyrealms");
            tiers.start();
            routes.start();
            seasons.start();
            expeditions.start();
        } catch (SQLException | RuntimeException e) {
            getSLF4JLogger().error("rapid-skyrealms can't load its data; disabling it", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        started = true;

        var pm = getServer().getPluginManager();
        pm.registerEvents(this, this);
        pm.registerEvents(tiers, this);
        pm.registerEvents(expeditions, this);
        pm.registerEvents(new IslandEvents(this), this);
        if (pm.isPluginEnabled("PlaceholderAPI")) {
            // Its own class, so PlaceholderAPI's types are only loaded when it's installed.
            new us.rapidskies.skyrealms.hook.SkyRealmsPlaceholders(this).register();
        }
        if (pm.isPluginEnabled("rapid-market")) {
            // Its own class, so rapid-market's types are only loaded when it's installed.
            market = us.rapidskies.skyrealms.hook.MarketListings.register(this, this::marketBonus);
        }

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            var r = event.registrar();
            r.register("realm", "Your island's Realm Tier: goals, ascending, biome", List.of("realms", "tier"),
                    Commands.realm(this));
            r.register("shop", "The SkyRealms shop: buy basics, sell what your island makes", List.of("market"),
                    Commands.shop(this));
            r.register("sell", "Sell the held stack (or everything: /sell all) to the shop", List.of(),
                    Commands.sell(this));
            r.register("expedition", "When the next Expedition arrives, and go to it", List.of("expeditions", "exped"),
                    Commands.expedition(this));
            r.register("route", "Trade Routes between islands", List.of("routes", "traderoute", "traderoutes"),
                    Commands.route(this));
            r.register("season", "This season's island leaderboard", List.of("seasons"), Commands.season(this));
            r.register("skyrealms", "rapid-skyrealms admin: reload", List.of(), Commands.admin(this));
        });
        tiers.reapplyOnline();
        getSLF4JLogger().info("rapid-skyrealms {} enabled ({} tiers, {} shop categories{})",
                getPluginMeta().getVersion(), settings.tiers().size(), settings.shop().categories().size(),
                market ? ", auction house listings from island level" : "");
    }

    @Override
    public void onDisable() {
        if (!started) {
            return;
        }
        for (Player p : getServer().getOnlinePlayers()) {
            var holder = p.getOpenInventory().getTopInventory().getHolder(false);
            if (holder != null && holder.getClass().getName().startsWith("us.rapidskies.skyrealms.")) {
                p.closeInventory();
            }
        }
        expeditions.stop();
        routes.stop();
        seasons.stop();
        if (market) {
            us.rapidskies.skyrealms.hook.MarketListings.unregister(this);
        }
        // rapid-core (and its database) disables after us: we depend on it.
        tiers.stop();
    }

    /** Extra auction house listings for the player's island level. Main thread. */
    private int marketBonus(java.util.UUID player) {
        return islands.of(player).map(i -> us.rapidskies.skyrealms.hook.MarketListings.bonus(
                islands.level(i.id()).doubleValue(), settings.market().levelsPerListing(), settings.market().maxExtra()))
                .orElse(0);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        shop.forget(event.getPlayer().getUniqueId());
    }

    private Lang loadLang() throws IOException {
        Path data = getDataPath();
        return Lang.load(getClassLoader(), Lang.DEFAULT_RESOURCE, data.resolve(Lang.DEFAULT_RESOURCE), getSLF4JLogger());
    }

    private SkyRealmsSettings loadSettings() throws IOException {
        return SkyRealmsSettings.from(ConfigView.loadOrCreate(getClassLoader(), "config.yml",
                getDataPath().resolve("config.yml")));
    }

    /**
     * Re-reads the config and messages. Island layouts are registered once at startup, so a
     * change to {@code islands.layouts} waits for a restart. Returns false (changing nothing) if
     * the new config is invalid.
     */
    public boolean reload() {
        try {
            SkyRealmsSettings next = loadSettings();
            Lang nextLang = loadLang();
            settings = next;
            lang = nextLang;
            tiers.reapplyOnline();
            return true;
        } catch (IOException | IllegalArgumentException e) {
            getSLF4JLogger().error("rapid-skyrealms reload failed, keeping the running config: {}", e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------ async

    /** Database work off the main thread, then {@code then} on it. Failures are logged. */
    public <T> void async(Database.SqlSupplier<T> work, Consumer<T> then) {
        async(work, then, null, null);
    }

    /**
     * Database work off the main thread, then {@code then} on it. On a failure {@code who} (if
     * any) is told and {@code failed} (if any) runs on the main thread instead.
     */
    public <T> void async(Database.SqlSupplier<T> work, Consumer<T> then, Player who, Runnable failed) {
        core().database().async(work).whenComplete((value, error) -> {
            if (!isEnabled()) {
                return;
            }
            getServer().getScheduler().runTask(this, () -> {
                if (error != null) {
                    getSLF4JLogger().warn("rapid-skyrealms database work failed: {}", error.toString());
                    if (who != null && who.isOnline()) {
                        lang.send(who, "errors.database");
                    }
                    if (failed != null) {
                        failed.run();
                    }
                    return;
                }
                then.accept(value);
            });
        });
    }

    // ------------------------------------------------------------------ parts, for each other

    public RapidCore core() {
        return RapidCore.get();
    }

    public SkyRealmsSettings settings() {
        return settings;
    }

    public Lang lang() {
        return lang;
    }

    public Islands islands() {
        return islands;
    }

    public SkyRealmsRepository repo() {
        return repo;
    }

    public Announcer announcer() {
        return announcer;
    }

    public TierService tiers() {
        return tiers;
    }

    public ShopService shop() {
        return shop;
    }

    public RouteService routes() {
        return routes;
    }

    public ExpeditionService expeditions() {
        return expeditions;
    }

    public SeasonService seasons() {
        return seasons;
    }
}
