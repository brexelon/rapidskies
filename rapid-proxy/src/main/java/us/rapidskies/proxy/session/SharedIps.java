package us.rapidskies.proxy.session;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import us.rapidskies.common.redis.Channels;
import us.rapidskies.common.redis.RedisBus;

/**
 * Anti-farm (PLAN.md §6.2): publishes {@link Channels#SHARED_IP_KEY}, the online players who
 * share an IP address with an account that connected before them. Backends don't pay
 * activity Bolts to those players, so alts gain nothing, while the first account on a
 * household's connection still earns normally.
 *
 * <p>Only the proxy sees every connection, which is why this lives here. Addresses are
 * the real client addresses as long as Velocity is the edge, or the DDoS proxy in front of
 * it passes them on (PROXY protocol, {@code haproxy-protocol = true}).
 */
public final class SharedIps {

    private static final long RESYNC_SECONDS = 60;

    /** Where a player connected from, and when (a sequence number; lower is earlier). */
    record Session(InetAddress address, long order) {
    }

    private final ProxyServer proxy;
    private final RedisBus redis;
    private final Logger logger;
    private final Map<UUID, Session> online = new ConcurrentHashMap<>();
    private final AtomicLong order = new AtomicLong();
    /** What Redis holds; guarded by {@code this}. */
    private final Set<UUID> published = new HashSet<>();
    private volatile Object plugin;
    private ScheduledTask resync;

    public SharedIps(ProxyServer proxy, RedisBus redis, Logger logger) {
        this.proxy = proxy;
        this.redis = redis;
        this.logger = logger;
    }

    /** Clears what a previous proxy run left behind and starts a periodic resync. */
    public void start(Object plugin) {
        this.plugin = plugin;
        proxy.getScheduler().buildTask(plugin, () -> {
            synchronized (this) {
                try {
                    redis.delete(Channels.SHARED_IP_KEY);
                    published.clear();
                } catch (RuntimeException e) {
                    logger.warn("Couldn't clear {}: {}", Channels.SHARED_IP_KEY, e.getMessage());
                }
            }
            sync();
        }).schedule();
        // Catches up after a Redis outage dropped an update.
        resync = proxy.getScheduler().buildTask(plugin, this::sync)
                .delay(RESYNC_SECONDS, TimeUnit.SECONDS).repeat(RESYNC_SECONDS, TimeUnit.SECONDS).schedule();
    }

    public void stop() {
        if (resync != null) {
            resync.cancel();
        }
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        online.put(event.getPlayer().getUniqueId(),
                new Session(event.getPlayer().getRemoteAddress().getAddress(), order.incrementAndGet()));
        schedule();
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (online.remove(event.getPlayer().getUniqueId()) != null) {
            schedule();
        }
    }

    private void schedule() {
        Object p = plugin;
        if (p != null) {
            proxy.getScheduler().buildTask(p, this::sync).schedule();
        }
    }

    private synchronized void sync() {
        Set<UUID> desired = later(online);
        Set<UUID> add = new HashSet<>(desired);
        add.removeAll(published);
        Set<UUID> remove = new HashSet<>(published);
        remove.removeAll(desired);
        try {
            if (!add.isEmpty()) {
                redis.addToSet(Channels.SHARED_IP_KEY, ids(add));
                published.addAll(add);
            }
            if (!remove.isEmpty()) {
                redis.removeFromSet(Channels.SHARED_IP_KEY, ids(remove));
                published.removeAll(remove);
            }
        } catch (RuntimeException e) {
            logger.warn("Couldn't update {}: {}", Channels.SHARED_IP_KEY, e.getMessage());
        }
    }

    /** Every player except the earliest-connected one on each address. */
    static <K> Set<K> later(Map<K, Session> sessions) {
        Map<InetAddress, List<Map.Entry<K, Session>>> byAddress = new HashMap<>();
        for (Map.Entry<K, Session> e : sessions.entrySet()) {
            byAddress.computeIfAbsent(e.getValue().address(), a -> new ArrayList<>()).add(Map.entry(e.getKey(), e.getValue()));
        }
        Set<K> out = new HashSet<>();
        for (List<Map.Entry<K, Session>> group : byAddress.values()) {
            if (group.size() > 1) {
                group.sort(Comparator.comparingLong(e -> e.getValue().order()));
                group.subList(1, group.size()).forEach(e -> out.add(e.getKey()));
            }
        }
        return out;
    }

    private static String[] ids(Collection<UUID> uuids) {
        return uuids.stream().map(UUID::toString).toArray(String[]::new);
    }
}
