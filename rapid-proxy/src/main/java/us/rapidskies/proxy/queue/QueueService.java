package us.rapidskies.proxy.queue;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.slf4j.Logger;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.redis.Channels;
import us.rapidskies.common.redis.RedisBus;
import us.rapidskies.common.registry.ServerStatus;
import us.rapidskies.proxy.rank.ProxyRanks;

/**
 * Runs the {@link JoinQueue}: every second it sends whoever can go, and every few seconds
 * it shows everyone waiting their place on the action bar.
 *
 * <p>Requests come from {@code /play}, from backends over Redis ({@link Channels#CONNECT}:
 * the hub selector and NPCs), from party follow and from {@code /friend join}.
 */
public final class QueueService {

    private static final int MAX_ATTEMPTS = 3;
    private static final int ACTION_BAR_EVERY_TICKS = 2;

    private final ProxyServer proxy;
    private final Lang lang;
    private final QueueSettings settings;
    private final ProxyRanks ranks;
    private final Supplier<List<ServerStatus>> servers;
    private final Logger logger;
    private final JoinQueue queue = new JoinQueue();
    private ScheduledTask task;
    private long ticks;

    public QueueService(ProxyServer proxy, Lang lang, QueueSettings settings, ProxyRanks ranks,
                        Supplier<List<ServerStatus>> servers, Logger logger) {
        this.proxy = proxy;
        this.lang = lang;
        this.settings = settings;
        this.ranks = ranks;
        this.servers = servers;
        this.logger = logger;
    }

    public void start(Object plugin, RedisBus redis) {
        redis.subscribeJson(Channels.CONNECT, Channels.ConnectRequest.class, this::onConnectRequest);
        task = proxy.getScheduler().buildTask(plugin, this::tick).repeat(1, TimeUnit.SECONDS).schedule();
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
    }

    public QueueSettings settings() {
        return settings;
    }

    /** Resolves what a player typed (or a backend sent) to a target, if it's one players may join. */
    public Optional<QueueTarget> resolve(String input) {
        String key = input.toLowerCase(java.util.Locale.ROOT);
        if (settings.groups().containsKey(key)) {
            return Optional.of(QueueTarget.group(key));
        }
        // A specific instance of a joinable group (smp-1).
        return servers.get().stream()
                .filter(s -> s.id().equalsIgnoreCase(key) && settings.groups().containsKey(s.group()))
                .findFirst()
                .map(s -> QueueTarget.server(s.id()));
    }

    /** True if players may be sent to {@code serverId} through the queue (not build or staging). */
    public boolean joinable(String serverId) {
        return servers.get().stream().anyMatch(s -> s.id().equals(serverId) && settings.groups().containsKey(s.group()));
    }

    /** The group a live server belongs to, from its heartbeat. */
    public Optional<String> groupOf(String serverId) {
        return servers.get().stream().filter(s -> s.id().equals(serverId)).findFirst().map(ServerStatus::group);
    }

    public String displayName(QueueTarget target) {
        if (target.kind() == QueueTarget.Kind.GROUP) {
            return settings.groups().getOrDefault(target.name(), target.name());
        }
        return servers.get().stream().filter(s -> s.id().equals(target.name())).findFirst()
                .map(s -> settings.groups().getOrDefault(s.group(), s.group()) + " (" + s.id() + ")")
                .orElse(target.name());
    }

    /** Puts the player in line with their own priority, or sends them straight away if there's room. */
    public void join(Player player, QueueTarget target) {
        ProxyRanks.Standing standing = ranks.of(player);
        int priority = standing.staff().isPresent() ? settings.staffPriority() : settings.priority().get(standing.rank());
        join(player, target, priority, true);
    }

    /**
     * {@code announce}: tell the player where they are in line. Party follow and friend
     * joins pass their own messages and use false.
     */
    public void join(Player player, QueueTarget target, int priority, boolean announce) {
        Optional<String> current = player.getCurrentServer().map(c -> c.getServerInfo().getName());
        if (current.isPresent() && servers.get().stream()
                .anyMatch(s -> s.id().equals(current.get()) && target.matches(s))) {
            lang.send(player, "queue.already-there", name(target));
            return;
        }
        JoinQueue.Position pos = queue.join(player.getUniqueId(), target, priority, System.currentTimeMillis());
        boolean sent = dispatch().stream().anyMatch(d -> d.entry().player().equals(player.getUniqueId()));
        if (sent || !announce) {
            return;
        }
        if (JoinQueue.noServerUp(target, servers.get(), System.currentTimeMillis())) {
            lang.send(player, "queue.joined-offline", name(target));
        } else {
            lang.send(player, "queue.joined", name(target),
                    Placeholder.unparsed("place", Integer.toString(pos.place())),
                    Placeholder.unparsed("of", Integer.toString(pos.of())));
        }
    }

    public boolean leave(Player player) {
        return queue.remove(player.getUniqueId());
    }

    public Optional<JoinQueue.Position> position(UUID player) {
        return queue.position(player);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        queue.remove(event.getPlayer().getUniqueId());
    }

    /** Got there another way (/server, a kick to the hub they queued for, ...): stop waiting. */
    @Subscribe
    public void onServerConnected(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        queue.position(player.getUniqueId()).ifPresent(pos -> player.getCurrentServer().ifPresent(conn -> {
            String id = conn.getServerInfo().getName();
            if (servers.get().stream().anyMatch(s -> s.id().equals(id) && pos.target().matches(s))) {
                queue.remove(player.getUniqueId());
            }
        }));
    }

    private void onConnectRequest(Channels.ConnectRequest request) {
        // Redis subscriber thread: everything below is thread-safe.
        Optional<Player> player;
        try {
            player = proxy.getPlayer(request.player());
        } catch (IllegalArgumentException e) {
            logger.warn("Ignoring connect request with a bad uuid: {}", request.uuid());
            return;
        }
        if (player.isEmpty()) {
            return;
        }
        Optional<QueueTarget> target = request.target() == null ? Optional.empty() : resolve(request.target());
        if (target.isEmpty()) {
            logger.warn("Ignoring connect request for {} to unknown or staff-only target '{}'",
                    player.get().getUsername(), request.target());
            lang.send(player.get(), "queue.unknown-target", Placeholder.unparsed("target", String.valueOf(request.target())));
            return;
        }
        join(player.get(), target.get());
    }

    private void tick() {
        try {
            dispatch();
            if (++ticks % ACTION_BAR_EVERY_TICKS == 0) {
                showPositions();
            }
        } catch (RuntimeException e) {
            logger.error("Queue tick failed", e);
        }
    }

    private List<JoinQueue.Dispatch> dispatch() {
        List<JoinQueue.Dispatch> sends = queue.drain(servers.get(), System.currentTimeMillis(),
                id -> proxy.getServer(id).isPresent());
        for (JoinQueue.Dispatch d : sends) {
            Optional<Player> player = proxy.getPlayer(d.entry().player());
            Optional<RegisteredServer> server = proxy.getServer(d.serverId());
            if (player.isEmpty() || server.isEmpty()) {
                continue;
            }
            connect(player.get(), server.get(), d.entry());
        }
        return sends;
    }

    private void connect(Player player, RegisteredServer server, JoinQueue.Entry entry) {
        lang.send(player, "queue.sending", name(entry.target()));
        player.createConnectionRequest(server).connect().whenComplete((result, error) -> {
            if (error == null && (result.isSuccessful()
                    || result.getStatus() == ConnectionRequestBuilder.Status.ALREADY_CONNECTED)) {
                return;
            }
            if (!player.isActive()) {
                return;
            }
            if (error == null && result.getStatus() == ConnectionRequestBuilder.Status.CONNECTION_CANCELLED) {
                return; // another plugin (or the player) cancelled it on purpose
            }
            Component reason = error != null ? Component.text(error.getClass().getSimpleName())
                    : result.getReasonComponent().orElse(Component.empty());
            if (entry.attempts() + 1 < MAX_ATTEMPTS) {
                queue.retry(entry);
                lang.send(player, "queue.retrying", name(entry.target()), Placeholder.component("reason", reason));
            } else {
                lang.send(player, "queue.failed", name(entry.target()), Placeholder.component("reason", reason));
            }
        });
    }

    private void showPositions() {
        for (Map.Entry<UUID, JoinQueue.Position> e : queue.all().entrySet()) {
            proxy.getPlayer(e.getKey()).ifPresent(p -> p.sendActionBar(lang.get(
                    JoinQueue.noServerUp(e.getValue().target(), servers.get(), System.currentTimeMillis())
                            ? "queue.actionbar-offline" : "queue.actionbar",
                    name(e.getValue().target()),
                    Placeholder.unparsed("place", Integer.toString(e.getValue().place())),
                    Placeholder.unparsed("of", Integer.toString(e.getValue().of())))));
        }
    }

    private net.kyori.adventure.text.minimessage.tag.resolver.TagResolver name(QueueTarget target) {
        return Placeholder.unparsed("target", displayName(target));
    }
}
