package us.rapidskies.claims;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import us.rapidskies.claims.command.ClaimAdminCommand;
import us.rapidskies.claims.command.ClaimCommand;
import us.rapidskies.claims.command.TownChatCommand;
import us.rapidskies.claims.command.TownCommand;
import us.rapidskies.claims.data.ClaimRepository;
import us.rapidskies.claims.display.BorderDisplay;
import us.rapidskies.claims.model.Claim;
import us.rapidskies.claims.model.LandIndex;
import us.rapidskies.claims.model.Owner;
import us.rapidskies.claims.model.Plot;
import us.rapidskies.claims.model.Town;
import us.rapidskies.claims.protect.EntryListener;
import us.rapidskies.claims.protect.EnvironmentListener;
import us.rapidskies.claims.protect.ProtectionListener;
import us.rapidskies.claims.town.ProjectService;
import us.rapidskies.claims.town.TownService;
import us.rapidskies.claims.town.TownVault;
import us.rapidskies.claims.town.Upkeep;
import us.rapidskies.common.config.ConfigView;
import us.rapidskies.common.db.Database;
import us.rapidskies.common.economy.EconomyMode;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.profile.ProfileRepository;
import us.rapidskies.core.RapidCore;
import us.rapidskies.core.economy.ModeEconomy;

/**
 * rapid-claims (PLAN.md §4.1, phase 3a): chunk claims in the Heartland with trust roles,
 * flags and a full protection suite, the claim map, towns with roles, plots, a bank, daily
 * upkeep and ruins, Town Projects, and {@code /claimadmin}.
 *
 * <p>Everything lives in MariaDB and is mirrored in memory ({@link LandIndex}); every
 * change is written to the database first and applied to the index on the main thread
 * once it succeeded. Other Horizons instances (when there's more than one) hear about
 * changes over Redis ({@link Sync}).
 *
 * <p>If the claims can't be read at startup, the server shuts down (Wings restarts it):
 * an SMP whose claims are silently unprotected is worse than one that's briefly offline.
 */
public final class RapidClaims extends JavaPlugin {

    private ClaimSettings settings;
    private Lang lang;
    private ClaimRepository repo;
    private final LandIndex land = new LandIndex();
    private ClaimService service;
    private Names names;
    private Sync sync;
    private LandService lands;
    private TownService towns;
    private Upkeep upkeep;
    private ProjectService projects;
    private TownVault vault;
    private BorderDisplay borders;
    private java.util.Map<UUID, ClaimRepository.PlayerRow> loadedRows = java.util.Map.of();
    private boolean started;

    @Override
    public void onEnable() {
        RapidCore core = RapidCore.get();
        try {
            lang = Lang.load(getClassLoader(), Lang.DEFAULT_RESOURCE, getDataPath().resolve(Lang.DEFAULT_RESOURCE),
                    getSLF4JLogger());
            settings = ClaimSettings.from(ConfigView.loadOrCreate(getClassLoader(), "config.yml",
                    getDataPath().resolve("config.yml")));
            core.database().migrate(getClassLoader(), "claims");
        } catch (Exception e) {
            getSLF4JLogger().error("rapid-claims can't start, and Horizons must not run without claim protection:"
                    + " shutting the server down", e);
            getServer().shutdown();
            return;
        }
        if (core.economy() == null || core.economy().mode() != EconomyMode.HORIZONS) {
            getSLF4JLogger().warn("rapid-claims expects server.gamemode horizons in rapid-core's config: towns, chunk"
                    + " purchases and projects charge Crowns, which this server doesn't serve");
        }

        repo = new ClaimRepository(core.database());
        names = new Names(repo, new ProfileRepository(core.database()));
        service = new ClaimService(land, settings.worlds());
        try {
            load();
        } catch (Exception e) {
            getSLF4JLogger().error("rapid-claims can't load the claims: shutting the server down", e);
            getServer().shutdown();
            return;
        }

        borders = new BorderDisplay(this);
        lands = new LandService(this, loadedRows);
        loadedRows = java.util.Map.of();
        towns = new TownService(this);
        projects = new ProjectService(this);
        vault = new TownVault(this);
        upkeep = new Upkeep(this);
        sync = new Sync(this, core.redis(), core.settings().serverId());

        var pm = getServer().getPluginManager();
        pm.registerEvents(names, this);
        pm.registerEvents(lands, this);
        pm.registerEvents(towns, this);
        pm.registerEvents(vault, this);
        pm.registerEvents(new ProtectionListener(this), this);
        pm.registerEvents(new EnvironmentListener(this), this);
        pm.registerEvents(new EntryListener(this), this);
        getServer().getServicesManager().register(ClaimService.class, service, this, ServicePriority.Normal);

        core.chatTags().register(this, player -> towns.chatTag(player.getUniqueId()));
        if (pm.getPlugin("PlaceholderAPI") != null) {
            // Its own class, so PlaceholderAPI's types are only loaded when it's installed.
            new us.rapidskies.claims.display.ClaimPlaceholders(this).register();
        }
        if (pm.getPlugin("QuickShop-Hikari") != null) {
            pm.registerEvents(new us.rapidskies.claims.hook.QuickShopHook(this), this);
        }

        ClaimCommand claim = new ClaimCommand(this);
        TownCommand town = new TownCommand(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register("claim", "Claim land and manage your claims", List.of("claims", "c"), claim);
            event.registrar().register("town", "Found, join and run a town", List.of("t", "towns"), town);
            event.registrar().register("tc", "Talk in your town's chat", List.of("townchat"),
                    new TownChatCommand(this));
            event.registrar().register("claimadmin", "Inspect, transfer, unclaim or restore claims and towns",
                    List.of("ca"), new ClaimAdminCommand(this));
        });

        sync.start();
        upkeep.start();
        borders.start();
        vault.start();
        started = true;
        getSLF4JLogger().info("rapid-claims {} enabled: {} claimed chunks, {} towns in {}",
                getPluginMeta().getVersion(), land.size(), land.towns().size(), settings.worlds());
    }

    @Override
    public void onDisable() {
        if (!started) {
            return;
        }
        RapidCore.get().chatTags().unregister(this);
        getServer().getServicesManager().unregisterAll(this);
        upkeep.stop();
        borders.stop();
        vault.saveAllNow();
    }

    /** Reads every claim, trust list, town and plot into the index. Blocking (startup only). */
    private void load() throws Exception {
        ClaimRepository.Snapshot s = repo.load(settings.worlds());
        Set<UUID> people = new HashSet<>();
        for (Claim c : s.claims()) {
            land.put(c);
            if (c.owner().type() == Owner.Type.PLAYER) {
                people.add(c.owner().id());
            }
        }
        s.access().forEach((owner, access) -> {
            land.setAccess(owner, access);
            people.addAll(access.trust().keySet());
            people.addAll(access.denied());
        });
        for (Town t : s.towns()) {
            land.putTown(t);
            people.addAll(t.members().keySet());
        }
        for (Plot p : s.plots()) {
            land.putPlot(p);
        }
        names.preload(people);
        loadedRows = s.players();
    }

    // ------------------------------------------------------------------ helpers for the parts

    /**
     * Runs blocking database work off the main thread, then {@code then} on the main thread
     * with its result. A failure is logged and {@code who} (if any) is told to try again.
     */
    public <T> void async(Database.SqlSupplier<T> work, Consumer<T> then, CommandSender who) {
        async(work, then, null, who);
    }

    /**
     * As {@link #async(Database.SqlSupplier, Consumer, CommandSender)}, and {@code failed}
     * (if not null) runs on the main thread when the work threw, to undo anything done
     * before it started (items taken from an inventory).
     */
    public <T> void async(Database.SqlSupplier<T> work, Consumer<T> then, Runnable failed, CommandSender who) {
        RapidCore.get().database().async(work).whenComplete((result, error) -> Bukkit.getScheduler().runTask(this, () -> {
            if (error != null) {
                getSLF4JLogger().error("rapid-claims database work failed", error);
                if (who != null) {
                    lang.send(who, "errors.database");
                }
                if (failed != null) {
                    failed.run();
                }
                return;
            }
            then.accept(result);
        }));
    }

    /** "Steve" or the town's colored name, for messages about land. */
    public Component ownerName(Owner owner) {
        if (owner.type() == Owner.Type.PLAYER) {
            return lang.get("owner.player", Placeholder.unparsed("name", names.name(owner.id())));
        }
        return land.town(owner.id())
                .map(t -> lang.get("owner.town", Placeholder.component("name", TownService.colored(t, t.name()))))
                .orElseGet(() -> lang.get("owner.unknown-town"));
    }

    /** This server's Crowns, or null if it isn't a Horizons server (nothing can be bought then). */
    public ModeEconomy crowns() {
        ModeEconomy e = RapidCore.get().economy();
        return e != null && e.mode() == EconomyMode.HORIZONS ? e : null;
    }

    public ClaimSettings settings() {
        return settings;
    }

    public Lang lang() {
        return lang;
    }

    public ClaimRepository repo() {
        return repo;
    }

    public LandIndex land() {
        return land;
    }

    public ClaimService service() {
        return service;
    }

    public Names names() {
        return names;
    }

    public Sync sync() {
        return sync;
    }

    public LandService lands() {
        return lands;
    }

    public TownService towns() {
        return towns;
    }

    public Upkeep upkeep() {
        return upkeep;
    }

    public ProjectService projects() {
        return projects;
    }

    public TownVault vault() {
        return vault;
    }

    public BorderDisplay borders() {
        return borders;
    }
}
