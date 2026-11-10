package us.rapidskies.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.command.Command;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.event.ResultedEvent.ComponentResult;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import net.luckperms.api.LuckPermsProvider;
import org.slf4j.Logger;
import us.rapidskies.common.config.ConfigView;
import us.rapidskies.common.config.DatabaseSettings;
import us.rapidskies.common.config.RedisSettings;
import us.rapidskies.common.db.Database;
import us.rapidskies.common.economy.Ledger;
import us.rapidskies.common.grant.GrantRepository;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.profile.ProfileRepository;
import us.rapidskies.common.redis.Channels;
import us.rapidskies.common.redis.RedisBus;
import us.rapidskies.common.registry.ServerRegistry;
import us.rapidskies.proxy.chat.PrivateMessages;
import us.rapidskies.proxy.chat.StaffChatCommand;
import us.rapidskies.proxy.friend.FriendCommand;
import us.rapidskies.proxy.friend.FriendService;
import us.rapidskies.proxy.friend.FriendSettings;
import us.rapidskies.proxy.grant.GrantCommand;
import us.rapidskies.proxy.grant.GrantService;
import us.rapidskies.proxy.grant.GrantsCommand;
import us.rapidskies.proxy.party.PartyChatCommand;
import us.rapidskies.proxy.party.PartyCommand;
import us.rapidskies.proxy.party.PartyService;
import us.rapidskies.proxy.party.PartySettings;
import us.rapidskies.proxy.queue.PlayCommand;
import us.rapidskies.proxy.queue.QueueService;
import us.rapidskies.proxy.queue.QueueSettings;
import us.rapidskies.proxy.rank.ProxyRanks;
import us.rapidskies.proxy.session.FrozenPlayers;
import us.rapidskies.proxy.session.SharedIps;
import us.rapidskies.proxy.session.TailwindSessions;
import us.rapidskies.proxy.session.VanishedStaff;

/**
 * Velocity entry point (PLAN.md §4): hub load balancing from the server registry, the
 * version gate, MOTD, maintenance mode, the console command the store uses, and the social
 * layer: parties, friends, the join queue, cross-server messages and staff chat, plus
 * {@code /grant} for ranks and permissions (applied through LuckPerms). It also
 * owns what only a whole-network view can decide: Tailwind accrual between sessions, which
 * accounts share an IP (anti-farm), and hiding vanished staff from social features.
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
        authors = {"RapidSkies"},
        // Ranks (party and friend-list size, queue priority) come from LuckPerms groups,
        // and /grant writes them through its API.
        dependencies = {@Dependency(id = "luckperms")})
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
    private QueueService queue;
    private PartyService parties;
    private SharedIps sharedIps;

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

        ProfileRepository profiles = new ProfileRepository(database);
        VanishedStaff vanish = new VanishedStaff(redis, logger);
        sharedIps = new SharedIps(proxy, redis, logger);
        sharedIps.start(this);
        var luckPerms = LuckPermsProvider.get();
        ProxyRanks ranks = new ProxyRanks(luckPerms, proxy);
        queue = new QueueService(proxy, lang, QueueSettings.from(config), ranks, directory::servers,
                settings.hubGroup(), logger);
        queue.start(this, redis);
        parties = new PartyService(proxy, lang, PartySettings.from(config), ranks, queue);
        parties.start(this);
        FriendService friends = new FriendService(proxy, lang, FriendSettings.from(config), ranks, queue, database,
                profiles, vanish, logger);
        PrivateMessages messages = new PrivateMessages(proxy, lang, vanish);
        GrantService grants = GrantService.create(proxy, lang, new GrantRepository(database),
                new PlayerLookup(proxy, profiles), luckPerms, ranks, logger);

        var events = proxy.getEventManager();
        events.register(this, new ConnectionListener(lang, directory, settings));
        events.register(this, new PingListener(lang, directory, settings));
        events.register(this, queue);
        events.register(this, parties);
        events.register(this, friends);
        events.register(this, messages);
        events.register(this, new TailwindSessions(proxy, database, settings.tailwind(), logger));
        events.register(this, sharedIps);
        events.register(this, new FrozenPlayers(proxy, lang, redis, logger));

        register("maintenance", new MaintenanceCommand(proxy, lang, redis, directory, logger));
        register("rapid", new AdminCommand(proxy, lang, Ledger.bolts(database), profiles, logger));
        register("play", new PlayCommand(lang, queue, parties));
        register("hub", PlayCommand.hub(lang, queue, settings.hubGroup()), "lobby");
        register("leavequeue", PlayCommand.leave(lang, queue), "lq");
        register("party", new PartyCommand(proxy, lang, parties), "p");
        register("pc", new PartyChatCommand(lang, parties));
        register("friend", new FriendCommand(proxy, lang, friends), "f", "friends");
        // The usual /msg aliases, so muscle memory works and LiteBans' mute list covers them.
        register("msg", messages.msgCommand(), "tell", "w", "whisper", "m", "t", "pm", "message");
        register("r", messages.replyCommand(), "reply");
        StaffChatCommand staffChat = new StaffChatCommand(proxy, lang, logger);
        register("staffchat", staffChat, "sc");
        // Alerts from the backends (rapid-market's suspicious-trade check), shown in staff chat.
        redis.subscribeJson(Channels.STAFF_ALERT, Channels.StaffAlert.class, staffChat::alert);
        register("grant", new GrantCommand(proxy, lang, grants));
        register("grants", new GrantsCommand(proxy, lang, grants));

        ready = true;
        logger.info("rapid-proxy {} ready", BuildConstants.VERSION);
    }

    private void register(String name, Command command, String... aliases) {
        CommandMeta meta = proxy.getCommandManager().metaBuilder(name).aliases(aliases).plugin(this).build();
        proxy.getCommandManager().register(meta, command);
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        shuttingDown = true;
        ready = false;
        closeServices();
    }

    private void closeServices() {
        if (queue != null) {
            queue.stop();
            queue = null;
        }
        if (parties != null) {
            parties.stop();
            parties = null;
        }
        if (sharedIps != null) {
            sharedIps.stop();
            sharedIps = null;
        }
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
