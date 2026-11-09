package us.rapidskies.proxy;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.scheduler.ScheduledTask;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import us.rapidskies.common.redis.Channels;
import us.rapidskies.common.redis.RedisBus;
import us.rapidskies.common.registry.ServerRegistry;
import us.rapidskies.common.registry.ServerSelector;
import us.rapidskies.common.registry.ServerStatus;

/**
 * The proxy's view of the network, refreshed from Redis every few seconds: which backends
 * are alive and how full they are, and whether maintenance mode is on. Event handlers read
 * the latest snapshot and never wait on Redis.
 */
final class NetworkDirectory {

    private final ProxyServer proxy;
    private final ServerRegistry registry;
    private final RedisBus redis;
    private final ProxySettings settings;
    private final Logger logger;

    private volatile List<ServerStatus> servers = List.of();
    private volatile boolean maintenance;
    private volatile boolean registryHealthy = true;
    private ScheduledTask task;

    NetworkDirectory(ProxyServer proxy, ServerRegistry registry, RedisBus redis, ProxySettings settings, Logger logger) {
        this.proxy = proxy;
        this.registry = registry;
        this.redis = redis;
        this.settings = settings;
        this.logger = logger;
    }

    void start(Object plugin) {
        redis.subscribeJson(Channels.MAINTENANCE, Channels.MaintenanceChanged.class, m -> maintenance = m.enabled());
        refresh();
        task = proxy.getScheduler().buildTask(plugin, this::refresh)
                .repeat(settings.registryPollMillis(), TimeUnit.MILLISECONDS)
                .schedule();
    }

    void stop() {
        if (task != null) {
            task.cancel();
        }
    }

    private void refresh() {
        try {
            List<ServerStatus> fresh = registry.fetchAll();
            maintenance = "true".equals(redis.get(Channels.MAINTENANCE_KEY));
            servers = fresh;
            if (!registryHealthy) {
                logger.info("Server registry reachable again ({} servers)", fresh.size());
                registryHealthy = true;
            }
            if (settings.autoRegister()) {
                fresh.forEach(this::registerIfNew);
            }
        } catch (RuntimeException e) {
            // Keep the last snapshot; logins fall back to velocity.toml's `try` order once it goes stale.
            if (registryHealthy) {
                logger.warn("Can't read the server registry from Redis: {}", e.getMessage());
                registryHealthy = false;
            }
        }
    }

    private void registerIfNew(ServerStatus s) {
        if (s.address() == null || s.address().isBlank() || proxy.getServer(s.id()).isPresent()) {
            return;
        }
        InetSocketAddress address = parseInternal(s.address());
        if (address == null) {
            logger.warn("Ignoring heartbeat from {}: address '{}' isn't a private host:port", s.id(), s.address());
            return;
        }
        proxy.registerServer(new ServerInfo(s.id(), address));
        logger.info("Registered new backend {} ({}) at {}", s.id(), s.group(), s.address());
    }

    /**
     * Parses {@code host:port} and accepts only private or loopback IPs, so a bad heartbeat
     * can never point the proxy at a public host.
     */
    static InetSocketAddress parseInternal(String hostPort) {
        int colon = hostPort.lastIndexOf(':');
        if (colon <= 0) {
            return null;
        }
        try {
            int port = Integer.parseInt(hostPort.substring(colon + 1));
            String host = hostPort.substring(0, colon);
            if (!host.matches("[0-9.]+")) {
                return null; // IPs only: no DNS lookups from a heartbeat
            }
            InetAddress ip = InetAddress.getByName(host);
            if (!(ip.isSiteLocalAddress() || ip.isLoopbackAddress()) || port < 1 || port > 65535) {
                return null;
            }
            return new InetSocketAddress(ip, port);
        } catch (Exception e) {
            return null;
        }
    }

    boolean maintenance() {
        return maintenance;
    }

    void setMaintenanceLocally(boolean on) {
        maintenance = on;
    }

    /** Live backends, as of the last refresh. */
    List<ServerStatus> servers() {
        return servers;
    }

    /** Players across all backends (heartbeat counts), leaving vanished staff out. */
    int networkOnline() {
        long now = System.currentTimeMillis();
        return servers.stream().filter(s -> s.isFresh(now, ServerRegistry.STALE_MILLIS)).mapToInt(ServerStatus::visible).sum();
    }

    /** The best hub to send someone to, other than {@code except} (may be null). */
    Optional<RegisteredServer> bestHub(RegisteredServer except) {
        String skip = except == null ? null : except.getServerInfo().getName();
        return ServerSelector.leastLoaded(servers, settings.hubGroup(), System.currentTimeMillis(),
                        s -> !s.id().equals(skip) && proxy.getServer(s.id()).isPresent())
                .flatMap(s -> proxy.getServer(s.id()));
    }
}
