package us.rapidskies.core;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import us.rapidskies.common.config.ConfigView;
import us.rapidskies.common.config.DatabaseSettings;
import us.rapidskies.common.config.RedisSettings;
import us.rapidskies.common.db.Database;
import us.rapidskies.common.economy.EconomyMode;
import us.rapidskies.common.economy.Ledger;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.profile.ProfileRepository;
import us.rapidskies.common.redis.RedisBus;
import us.rapidskies.common.registry.ServerRegistry;
import us.rapidskies.core.chat.ChatListener;
import us.rapidskies.core.combat.CombatTiming;
import us.rapidskies.core.command.BoltsCommand;
import us.rapidskies.core.command.PlusCommand;
import us.rapidskies.core.profile.ProfileService;
import us.rapidskies.core.rank.RankDisplay;

/**
 * rapid-core: runs on every Paper backend and is the API the other rapid-* plugins build on
 * (PLAN.md §4). Phase 1 so far: profiles, the Bolts and per-mode ledgers, the server
 * heartbeat, chat with rank prefixes and plus colors, {@code /plus}, {@code /bolts}, and
 * 1.8-style combat timing. Staff mode, the GUI framework and Tailwind come next.
 *
 * <p>Other plugins get the services through {@link #get()} after declaring
 * {@code depend: [rapid-core]}.
 *
 * <p>If MariaDB or Redis can't be reached at startup the server shuts itself down; Wings
 * restarts it (crash detection), so it keeps retrying without ever letting players onto a
 * server that can't load their data.
 */
public final class RapidCore extends JavaPlugin {

    private static final int CONNECT_ATTEMPTS = 3;
    private static final long CONNECT_RETRY_MILLIS = 5_000;

    private CoreSettings settings;
    private Lang lang;
    private Database database;
    private RedisBus redis;
    private ProfileRepository profileRepository;
    private ProfileService profiles;
    private Ledger bolts;
    private final Map<EconomyMode, Ledger> modeLedgers = new EnumMap<>(EconomyMode.class);
    private RankDisplay ranks;
    private Heartbeat heartbeat;
    private CombatTiming combat;

    /** The running instance, for plugins that depend on rapid-core. */
    public static RapidCore get() {
        return JavaPlugin.getPlugin(RapidCore.class);
    }

    @Override
    public void onEnable() {
        Logger log = getSLF4JLogger();
        Path data = getDataPath();
        ClassLoader cl = getClassLoader();
        try {
            ConfigView config = ConfigView.loadOrCreate(cl, "config.yml", data.resolve("config.yml"));
            settings = CoreSettings.from(config, getServer().getPort(), log);
            lang = Lang.load(cl, Lang.DEFAULT_RESOURCE, data.resolve(Lang.DEFAULT_RESOURCE), log);
            connect(config, cl, log);
        } catch (Exception e) {
            log.error("rapid-core can't start; shutting the server down so Wings restarts it", e);
            getServer().shutdown();
            return;
        }

        profileRepository = new ProfileRepository(database);
        bolts = Ledger.bolts(database);
        for (EconomyMode mode : EconomyMode.values()) {
            modeLedgers.put(mode, Ledger.mode(database, mode));
        }
        LuckPerms luckPerms = LuckPermsProvider.get();
        profiles = new ProfileService(this, profileRepository, redis, settings, lang);
        ranks = new RankDisplay(luckPerms, profiles, lang);

        var pm = getServer().getPluginManager();
        pm.registerEvents(profiles, this);
        pm.registerEvents(new ChatListener(ranks, lang), this);
        combat = new CombatTiming(this, settings);
        pm.registerEvents(combat, this);
        combat.applyToOnline();

        PlusCommand plus = new PlusCommand(database, ranks, profiles, profileRepository, lang);
        BoltsCommand boltsCommand = new BoltsCommand(database, bolts, profileRepository, lang);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register("plus", "Choose the color of the pluses in your rank prefix", List.of(), plus);
            event.registrar().register("bolts", "Show your Bolts balance", List.of("bolt"), boltsCommand);
        });

        heartbeat = new Heartbeat(this, new ServerRegistry(redis), settings);
        heartbeat.start();
        log.info("rapid-core {} enabled as {} (group {})", getPluginMeta().getVersion(), settings.serverId(),
                settings.group());
    }

    private void connect(ConfigView config, ClassLoader cl, Logger log) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= CONNECT_ATTEMPTS; attempt++) {
            try {
                database = Database.connect(DatabaseSettings.from(config.section("database")),
                        "rapid-core-" + settings.serverId(), log);
                database.migrate(cl, "core");
                redis = RedisBus.connect(RedisSettings.from(config.section("redis")), settings.serverId(), log);
                return;
            } catch (SQLException | RuntimeException e) {
                last = e;
                log.warn("Connecting to the database/Redis failed (attempt {}/{}): {}", attempt, CONNECT_ATTEMPTS,
                        e.getMessage());
                closeServices();
                if (attempt < CONNECT_ATTEMPTS) {
                    Thread.sleep(CONNECT_RETRY_MILLIS);
                }
            }
        }
        throw last;
    }

    @Override
    public void onDisable() {
        if (heartbeat != null) {
            heartbeat.stop();
        }
        if (combat != null) {
            combat.removeFromOnline();
        }
        closeServices();
    }

    private void closeServices() {
        if (redis != null) {
            redis.close();
            redis = null;
        }
        if (database != null) {
            database.close();
            database = null;
        }
    }

    // ------------------------------------------------------------------ API for other rapid-* plugins

    public CoreSettings settings() {
        return settings;
    }

    public Database database() {
        return database;
    }

    public RedisBus redis() {
        return redis;
    }

    public ProfileService profiles() {
        return profiles;
    }

    /** The network-wide Bolts ledger. */
    public Ledger bolts() {
        return bolts;
    }

    /** A gamemode's own currency (Crowns, Coins). Vault providers per mode build on this. */
    public Ledger modeLedger(EconomyMode mode) {
        return modeLedgers.get(mode);
    }

    public RankDisplay ranks() {
        return ranks;
    }
}
