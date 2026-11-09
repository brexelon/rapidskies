package us.rapidskies.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.command.Command;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.event.ResultedEvent.ComponentResult;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import us.rapidskies.common.config.ConfigView;
import us.rapidskies.common.config.DatabaseSettings;
import us.rapidskies.common.config.RedisSettings;
import us.rapidskies.common.db.Database;
import us.rapidskies.common.economy.Ledger;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.profile.ProfileRepository;
import us.rapidskies.common.redis.RedisBus;
import us.rapidskies.common.registry.ServerRegistry;

/**
 * Velocity entry point (PLAN.md §4): hub load balancing from the server registry, the
 * version gate, MOTD, maintenance mode, and the console command the store uses.
 * Parties, friends, the join queue, cross-server messaging and staff chat come next.
 *
 * <p>If MariaDB or Redis is unreachable at boot (e.g. the node rebooted and the proxy came
 * up first), the proxy stays up, refuses logins with lang {@code kick.starting}, and keeps
 * retrying every {@value #RETRY_SECONDS}s.
 */
@Plugin(
        id = "rapid-proxy",
        name = "rapid-proxy",
        version = BuildConstants.VERSION,
        description = "RapidSkies network proxy plugin",
        url = "https://rapidskies.us",
        authors = {"RapidSkies"})
public final class RapidProxy {

    private static final long RETRY_SECONDS = 10;

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDir;

    private ConfigView config;
    private Lang lang;
    private volatile boolean ready;
    private volatile boolean shuttingDown;
    private Database database;
    private RedisBus redis;
    private NetworkDirectory directory;

    @Inject
    public RapidProxy(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDir = dataDir;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) throws Exception {
        ClassLoader cl = getClass().getClassLoader();
        // Local files only; a broken config is a deploy mistake and should fail loudly.
        config = ConfigView.loadOrCreate(cl, "config.yml", dataDir.resolve("config.yml"));
        lang = Lang.load(cl, Lang.DEFAULT_RESOURCE, dataDir.resolve(Lang.DEFAULT_RESOURCE), logger);
        proxy.getScheduler().buildTask(this, this::connect).schedule();
    }

    /** Until the backing services are up, nobody gets in (staff included: nothing would work). */
    @Subscribe(priority = Short.MAX_VALUE) // first
    public void onLogin(LoginEvent event) {
        if (!ready) {
            event.setResult(ComponentResult.denied(lang.get("kick.starting")));
        }
    }

    private void connect() {
        if (shuttingDown) {
            return;
        }
        try {
            ClassLoader cl = getClass().getClassLoader();
            database = Database.connect(DatabaseSettings.from(config.section("database")), "rapid-proxy", logger);
            database.migrate(cl, "core");
            redis = RedisBus.connect(RedisSettings.from(config.section("redis")), "proxy", logger);
        } catch (Exception e) {
            logger.error("Can't reach the database or Redis ({}); refusing logins, retrying in {}s",
                    e.getMessage(), RETRY_SECONDS, e);
            closeServices();
            proxy.getScheduler().buildTask(this, this::connect).delay(RETRY_SECONDS, TimeUnit.SECONDS).schedule();
            return;
        }

        ProxySettings settings = ProxySettings.from(config);
        directory = new NetworkDirectory(proxy, new ServerRegistry(redis), redis, settings, logger);
        directory.start(this);

        proxy.getEventManager().register(this, new ConnectionListener(lang, directory, settings));
        proxy.getEventManager().register(this, new PingListener(lang, directory, settings));
        register("maintenance", new MaintenanceCommand(proxy, lang, redis, directory, logger));
        register("rapid", new AdminCommand(proxy, lang, Ledger.bolts(database), new ProfileRepository(database), logger));

        ready = true;
        logger.info("rapid-proxy {} ready", BuildConstants.VERSION);
    }

    private void register(String name, Command command) {
        CommandMeta meta = proxy.getCommandManager().metaBuilder(name).plugin(this).build();
        proxy.getCommandManager().register(meta, command);
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        shuttingDown = true;
        ready = false;
        closeServices();
    }

    private void closeServices() {
        if (directory != null) {
            directory.stop();
            directory = null;
        }
        if (redis != null) {
            redis.close();
            redis = null;
        }
        if (database != null) {
            database.close();
            database = null;
        }
    }
}
