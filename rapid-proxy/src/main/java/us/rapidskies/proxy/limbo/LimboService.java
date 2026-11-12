package us.rapidskies.proxy.limbo;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.slf4j.Logger;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.redis.Channels;
import us.rapidskies.common.redis.RedisBus;
import us.rapidskies.proxy.queue.QueueService;
import us.rapidskies.proxy.queue.QueueSettings;
import us.rapidskies.proxy.queue.QueueTarget;

/**
 * The Limbo server (LOOHP's Limbo with ViaLimbo and rapid-limbo): a tiny world players are
 * parked in instead of being disconnected or taking a slot on a real server.
 *
 * <ul>
 *   <li><b>No lobby up.</b> Players joining, or kicked off a server, while every lobby is
 *       down go to limbo and wait in the lobby queue, like a gamemode queue: the action bar
 *       shows it and they're sent the moment a lobby is back. {@code /play} still takes them
 *       to a mode that's up.</li>
 *   <li><b>AFK.</b> A player AFK (rapid-core's rules, reported over {@link Channels#AFK}) for
 *       {@link LimboSettings#afkMinutes} is moved to limbo, leaving any queue they were in.
 *       As soon as they turn their view (rapid-limbo tells the proxy on
 *       {@value #ACTIVITY_CHANNEL}), chat or run a command, they're queued to go back to the
 *       server they were on, ahead of new arrivals. If it isn't back within the queue's
 *       return time they go to a lobby instead.</li>
 * </ul>
 *
 * <p>Only players this service sent are managed; staff who {@code /server limbo} are left
 * alone. If the Limbo server isn't in velocity.toml, everything here is off and the proxy
 * behaves as before (no lobby: disconnect).
 */
public final class LimboService {

    /** Staff idling on purpose (watching a player, vanished) stay where they are. */
    public static final String AFK_EXEMPT = "rapid.limbo.afk-exempt";
    static final String ACTIVITY_CHANNEL = "rapidskies:activity";
    private static final MinecraftChannelIdentifier ACTIVITY = MinecraftChannelIdentifier.from(ACTIVITY_CHANNEL);

    /** How long an exempt player, or one we failed to move, is left alone before checking again. */
    private static final long RECHECK_MILLIS = 60_000;
    /** A player sent here who hasn't arrived by then never will (the connection failed or was redirected). */
    private static final long ARRIVE_MILLIS = 60_000;
    /** How long a player waiting for a lobby may be out of every line before they're put back in. */
    private static final long REQUEUE_MILLIS = 5_000;
    private static final int ACTION_BAR_EVERY_TICKS = 2;

    private enum Reason { LOBBY, AFK }

    /** Why this service sent a player to limbo. */
    private static final class Stay {
        final long sentAt;
        /** Where an AFK player goes back to. Null when waiting for a lobby. */
        final String returnServer;
        final String returnGroup;
        /** Display name of the line an AFK player left on the way here, or null. */
        final String leftQueue;
        volatile Reason reason;
        volatile boolean arrived;
        /** AFK and gave input: going back on the next tick. */
        volatile boolean active;
        /** Waiting for a lobby: when they were first seen out of every line, or 0. */
        long unqueuedSince;

        Stay(Reason reason, String returnServer, String returnGroup, String leftQueue, long sentAt) {
            this.reason = reason;
            this.returnServer = returnServer;
            this.returnGroup = returnGroup;
            this.leftQueue = leftQueue;
            this.sentAt = sentAt;
        }
    }

    private final ProxyServer proxy;
    private final Lang lang;
    private final LimboSettings settings;
    private final QueueService queue;
    private final String hubGroup;
    private final Predicate<UUID> frozen;
    private final Logger logger;
    private final Map<UUID, Stay> stays = new ConcurrentHashMap<>();
    private final AfkTimers afk = new AfkTimers();
    private ScheduledTask task;
    private long ticks;

    public LimboService(ProxyServer proxy, Lang lang, LimboSettings settings, QueueService queue, String hubGroup,
                        Predicate<UUID> frozen, Logger logger) {
        this.proxy = proxy;
        this.lang = lang;
        this.settings = settings;
        this.queue = queue;
        this.hubGroup = hubGroup;
        this.frozen = frozen;
        this.logger = logger;
    }

    public void start(Object plugin, RedisBus redis) {
        proxy.getChannelRegistrar().register(ACTIVITY);
        redis.subscribeJson(Channels.AFK, Channels.AfkChanged.class, this::onAfkReport);
        task = proxy.getScheduler().buildTask(plugin, this::tick).repeat(1, TimeUnit.SECONDS).schedule();
        if (server().isEmpty()) {
            logger.warn("Limbo server '{}' isn't in velocity.toml: with every lobby down, players are "
                    + "disconnected, and AFK players aren't moved", settings.server());
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
        proxy.getChannelRegistrar().unregister(ACTIVITY);
    }

    /** The Limbo server, if it's registered. */
    public Optional<RegisteredServer> server() {
        return proxy.getServer(settings.server());
    }

    public boolean isLimbo(RegisteredServer server) {
        return server != null && server.getServerInfo().getName().equalsIgnoreCase(settings.server());
    }

    /** Players on the Limbo server (it sends no heartbeat, so the registry doesn't count them). */
    public int online() {
        return server().map(s -> s.getPlayersConnected().size()).orElse(0);
    }

    /**
     * {@code player} is about to be sent to limbo because no lobby is up. On arrival they're
     * put in the lobby queue.
     */
    public void sendingToWait(Player player) {
        stays.put(player.getUniqueId(), new Stay(Reason.LOBBY, null, null, null, System.currentTimeMillis()));
    }

    // ------------------------------------------------------------------ events

    @Subscribe
    public void onServerConnected(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        afk.clear(id); // every server starts a player as active
        if (!onLimbo(player)) {
            stays.remove(id);
            return;
        }
        Stay stay = stays.get(id);
        if (stay == null) {
            return; // came on their own (staff: /server limbo)
        }
        stay.arrived = true;
        if (stay.reason == Reason.AFK) {
            lang.send(player, "limbo.afk", returnName(stay));
            if (stay.leftQueue != null) {
                lang.send(player, "limbo.afk-left-queue", Placeholder.unparsed("target", stay.leftQueue));
            }
        } else {
            lang.send(player, "limbo.no-lobby");
            if (!queue.waiting(id)) {
                joinLobbyLine(player);
            }
        }
    }

    /**
     * An AFK player here is heading somewhere else on their own (/hub, /play, /server, a
     * party leader): that replaces going back to their old server.
     */
    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Stay stay = stays.get(event.getPlayer().getUniqueId());
        if (stay != null && stay.arrived && stay.reason == Reason.AFK && !isLimbo(event.getOriginalServer())) {
            stay.reason = Reason.LOBBY;
            stay.active = false;
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        stays.remove(event.getPlayer().getUniqueId());
        afk.clear(event.getPlayer().getUniqueId());
    }

    /** rapid-limbo: the player turned their view. Never forwarded to the client. */
    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(ACTIVITY)) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        // Only Limbo itself may say this; a client sending it is ignored.
        if (event.getSource() instanceof ServerConnection conn && isLimbo(conn.getServer())) {
            active(conn.getPlayer());
        }
    }

    /** Limbo has chat off, so tell players waiting there what still works. */
    @Subscribe
    public void onChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        if (onLimbo(player) && !active(player)) {
            lang.send(player, "limbo.chat-off");
        }
    }

    @Subscribe
    public void onCommand(CommandExecuteEvent event) {
        var info = event.getInvocationInfo();
        if (event.getCommandSource() instanceof Player player
                && (info == null || info.source() == CommandExecuteEvent.Source.PLAYER) && onLimbo(player)) {
            active(player);
        }
    }

    private void onAfkReport(Channels.AfkChanged report) {
        // Redis subscriber thread: AfkTimers and the Velocity lookups are thread-safe.
        UUID id;
        try {
            id = report.player();
        } catch (IllegalArgumentException e) {
            return;
        }
        proxy.getPlayer(id).ifPresent(player -> afk.report(id, report.server(), report.afk(), report.idleSince(),
                currentName(player), System.currentTimeMillis()));
    }

    /** Marks an AFK player here as back. True if they were AFK here. */
    private boolean active(Player player) {
        Stay stay = stays.get(player.getUniqueId());
        if (stay == null || !stay.arrived || stay.reason != Reason.AFK) {
            return false;
        }
        // Handled on the next tick, after a command like /hub has had its say.
        stay.active = true;
        return true;
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        try {
            long now = System.currentTimeMillis();
            if (settings.afkEnabled()) {
                server().ifPresent(limbo -> parkAfkPlayers(limbo, now));
            }
            boolean hints = ++ticks % ACTION_BAR_EVERY_TICKS == 0;
            stays.forEach((id, stay) -> update(id, stay, now, hints));
        } catch (RuntimeException e) {
            logger.error("Limbo tick failed", e);
        }
    }

    private void parkAfkPlayers(RegisteredServer limbo, long now) {
        afk.due(now, settings.afkMillis()).forEach((id, serverId) -> {
            Optional<Player> player = proxy.getPlayer(id);
            if (player.isEmpty() || !currentName(player.get()).filter(serverId::equals).isPresent()) {
                afk.clear(id);
                return;
            }
            if (stays.containsKey(id)) {
                return;
            }
            String group = queue.groupOf(serverId).orElse(null);
            // Checked again later: permissions, freezes and settings can change.
            afk.snooze(id, now + RECHECK_MILLIS);
            if (player.get().hasPermission(AFK_EXEMPT) || frozen.test(id)
                    || (group != null && settings.afkExemptGroups().contains(group))) {
                return;
            }
            park(player.get(), limbo, serverId, group, now);
        });
    }

    /** Moves an AFK player to limbo. AFK players don't hold a place in any line. */
    private void park(Player player, RegisteredServer limbo, String serverId, String group, long now) {
        UUID id = player.getUniqueId();
        String leftQueue = queue.position(id).map(p -> queue.displayName(p.target())).orElse(null);
        queue.leave(player);
        Stay stay = new Stay(Reason.AFK, serverId, group, leftQueue, now);
        stays.put(id, stay);
        player.createConnectionRequest(limbo).connect().whenComplete((result, error) -> {
            if (error == null && (result.isSuccessful()
                    || result.getStatus() == ConnectionRequestBuilder.Status.ALREADY_CONNECTED)) {
                return;
            }
            // Tried again after RECHECK_MILLIS if they're still AFK.
            stays.remove(id, stay);
            logger.warn("Couldn't move AFK player {} to limbo: {}", player.getUsername(),
                    error != null ? error.toString() : result.getStatus());
        });
    }

    private void update(UUID id, Stay stay, long now, boolean hints) {
        Optional<Player> found = proxy.getPlayer(id);
        if (found.isEmpty()) {
            stays.remove(id, stay);
            return;
        }
        Player player = found.get();
        if (!stay.arrived) {
            if (now - stay.sentAt > ARRIVE_MILLIS) {
                stays.remove(id, stay);
            }
            return;
        }
        if (!onLimbo(player)) {
            stays.remove(id, stay); // left (ServerPostConnectEvent normally gets there first)
            return;
        }
        if (stay.reason == Reason.AFK) {
            if (stay.active) {
                goBack(player, stay, now);
            } else if (hints) {
                player.sendActionBar(lang.get("limbo.afk-actionbar", returnName(stay)));
            }
            return;
        }
        // Waiting for a lobby: always in some line. The grace covers a queue send that failed
        // and is about to be retried, and lets /leavequeue + /play go through.
        if (queue.waiting(id)) {
            stay.unqueuedSince = 0;
        } else if (stay.unqueuedSince == 0) {
            stay.unqueuedSince = now;
        } else if (now - stay.unqueuedSince >= REQUEUE_MILLIS) {
            stay.unqueuedSince = 0;
            joinLobbyLine(player);
        }
    }

    /**
     * An AFK player is back: queue them for the server they were on, ahead of new arrivals.
     * If they already picked somewhere (/hub, /play), that wins.
     */
    private void goBack(Player player, Stay stay, long now) {
        stay.active = false;
        stay.reason = Reason.LOBBY; // from now on: in a line, or else the lobby line
        stay.unqueuedSince = 0;
        UUID id = player.getUniqueId();
        if (queue.waiting(id)) {
            return;
        }
        QueueTarget target = returnTarget(stay);
        lang.send(player, "limbo.back");
        long expiresAt = now + TimeUnit.MINUTES.toMillis(Math.max(1, queue.settings().returnMinutes()));
        queue.join(player, target, QueueSettings.RETURN_PRIORITY, false, expiresAt);
        if (queue.position(id).isPresent()) { // not sent straight away: full or not up
            lang.send(player, "limbo.back-queued", Placeholder.unparsed("target", queue.displayName(target)));
        }
    }

    /** Their old server while the registry knows it; otherwise its group, or a lobby. */
    private QueueTarget returnTarget(Stay stay) {
        if (queue.groupOf(stay.returnServer).isPresent()) {
            return QueueTarget.server(stay.returnServer);
        }
        return QueueTarget.group(stay.returnGroup != null ? stay.returnGroup : hubGroup);
    }

    private void joinLobbyLine(Player player) {
        queue.join(player, QueueTarget.group(hubGroup), queue.priority(player), false);
    }

    private TagResolver returnName(Stay stay) {
        return Placeholder.unparsed("target", queue.displayName(QueueTarget.server(stay.returnServer)));
    }

    private boolean onLimbo(Player player) {
        return player.getCurrentServer().map(ServerConnection::getServer).filter(this::isLimbo).isPresent();
    }

    private static Optional<String> currentName(Player player) {
        return player.getCurrentServer().map(c -> c.getServerInfo().getName());
    }
}
