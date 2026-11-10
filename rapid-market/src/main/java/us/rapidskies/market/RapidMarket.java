package us.rapidskies.market;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.List;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import us.rapidskies.common.config.ConfigView;
import us.rapidskies.common.db.Database;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.core.RapidCore;
import us.rapidskies.core.economy.ModeEconomy;
import us.rapidskies.market.command.AuctionCommand;
import us.rapidskies.market.custody.Custody;
import us.rapidskies.market.data.MarketRepository;

/**
 * rapid-market (PLAN.md §4.2, phase 4a): the auction house, one plugin on Horizons and
 * SkyRealms. Each server trades in its own mode's currency (rapid-core's
 * {@code server.gamemode}: Crowns or Coins), and listings never cross modes.
 *
 * <p>Listings, collection bins, notices and price history live in MariaDB; money moves
 * through rapid-core's per-mode {@code Ledger} in the same transactions as the items
 * ({@link MarketRepository}), and items moving between inventories and the database are
 * journaled on disk ({@link Custody}) so a crash never loses or duplicates one.
 *
 * <p>Without a gamemode currency on this server (hub, build) the plugin turns itself off.
 */
public final class RapidMarket extends JavaPlugin {

    private MarketSettings settings;
    private Lang lang;
    private Market market;
    private MarketService service;

    @Override
    public void onEnable() {
        RapidCore core = RapidCore.get();
        ModeEconomy economy = core.economy();
        if (economy == null) {
            getSLF4JLogger().error("rapid-market needs a gamemode currency (rapid-core server.gamemode: horizons or "
                    + "skyrealms); disabling it on this server");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        Custody custody;
        try {
            lang = Lang.load(getClassLoader(), Lang.DEFAULT_RESOURCE, getDataPath().resolve(Lang.DEFAULT_RESOURCE),
                    getSLF4JLogger());
            settings = MarketSettings.from(ConfigView.loadOrCreate(getClassLoader(), "config.yml",
                    getDataPath().resolve("config.yml")));
            core.database().migrate(getClassLoader(), "market");
            custody = new Custody(getDataPath().resolve("custody"), getSLF4JLogger());
        } catch (Exception e) {
            // Nothing is listed or collected without the plugin, so the server can keep running.
            getSLF4JLogger().error("rapid-market can't start (fix the config or database, then restart)", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        int unsettled = custody.entries().size();
        if (unsettled > 0) {
            getSLF4JLogger().info("{} item(s) in the custody journal from before; settling them now", unsettled);
        }

        MarketRepository repo = new MarketRepository(core.database(), core.modeLedger(economy.mode()),
                economy.mode(), core.settings().serverId(), settings.historyDays());
        service = new MarketService(economy.mode(), settings.baseListings(), getSLF4JLogger());
        market = new Market(this, repo, custody, economy, service);
        getServer().getPluginManager().registerEvents(market, this);
        getServer().getServicesManager().register(MarketService.class, service, this, ServicePriority.Normal);

        AuctionCommand ah = new AuctionCommand(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register("ah", "The auction house: browse, buy, sell and collect",
                        List.of("auction", "auctions", "auctionhouse", "market"), ah));
        market.start();
        getSLF4JLogger().info("rapid-market {} enabled: trading in {}", getPluginMeta().getVersion(),
                economy.mode().currencyName(2));
    }

    @Override
    public void onDisable() {
        if (market == null) {
            return;
        }
        market.stop();
        RapidCore.get().prompts().cancelAll(this);
        // Our menus call back into this plugin: close any still open.
        for (var p : getServer().getOnlinePlayers()) {
            var holder = p.getOpenInventory().getTopInventory().getHolder(false);
            if (holder != null && holder.getClass().getPackageName().startsWith("us.rapidskies.market")) {
                p.closeInventory();
            }
        }
        getServer().getServicesManager().unregisterAll(this);
        // Moves still in flight finish on the database thread, or the custody journal settles
        // them on the next start.
    }

    /**
     * Runs blocking database work off the main thread, then {@code then} on the main thread
     * with its result. A failure is logged and {@code who} (if any) is told to try again.
     */
    public <T> void async(Database.SqlSupplier<T> work, Consumer<T> then, CommandSender who) {
        async(work, then, null, who);
    }

    /** As above, with {@code failed} run on the main thread when the work threw. */
    public <T> void async(Database.SqlSupplier<T> work, Consumer<T> then, Runnable failed, CommandSender who) {
        RapidCore.get().database().async(work).whenComplete((result, error) -> {
            if (!isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(this, () -> {
                if (error != null) {
                    getSLF4JLogger().error("rapid-market database work failed", error);
                    if (who != null) {
                        lang.send(who, "errors.database");
                    }
                    if (failed != null) {
                        failed.run();
                    }
                    return;
                }
                then.accept(result);
            });
        });
    }

    public MarketSettings settings() {
        return settings;
    }

    public Lang lang() {
        return lang;
    }

    public Market market() {
        return market;
    }

    public MarketService service() {
        return service;
    }
}
