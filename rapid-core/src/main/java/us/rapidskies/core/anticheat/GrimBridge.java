package us.rapidskies.core.anticheat;

import ac.grim.grimac.api.GrimAPIProvider;
import ac.grim.grimac.api.GrimAbstractAPI;
import ac.grim.grimac.api.event.EventBus;
import ac.grim.grimac.api.event.events.CompletePredictionEvent;
import ac.grim.grimac.api.event.events.FlagEvent;
import ac.grim.grimac.api.event.events.GrimQuitEvent;
import ac.grim.grimac.api.plugin.GrimPlugin;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

/**
 * rapid-core's hooks into Grim (the anticheat): the {@link OlderClientGrace} window for
 * players on clients older than the server. Only loaded when GrimAC is installed, so Grim's
 * types are never needed otherwise.
 *
 * <p>Cross-server alerts and violation history are Grim's own features, configured in
 * {@code infra/profiles/paper-base/plugins/GrimAC/} (infra/README.md → Anticheat).
 */
public final class GrimBridge implements Listener {

    private final Logger log;
    private final OlderClientGrace grace;
    private final Predicate<UUID> olderClient;
    private final EventBus events;
    private final GrimPlugin owner;

    private GrimBridge(Plugin plugin, OlderClientGrace grace, Predicate<UUID> olderClient, EventBus events,
                       GrimPlugin owner) {
        this.log = plugin.getSLF4JLogger();
        this.grace = grace;
        this.olderClient = olderClient;
        this.events = events;
        this.owner = owner;
    }

    /** Hooks into Grim, or returns null (logged) if its API isn't up. */
    public static GrimBridge register(Plugin plugin, OlderClientGrace grace) {
        GrimAbstractAPI api;
        try {
            api = GrimAPIProvider.get();
        } catch (RuntimeException | LinkageError e) {
            plugin.getSLF4JLogger().warn("GrimAC is installed but its API isn't available ({}); older clients "
                    + "get no grace after joining", e.toString());
            return null;
        }
        Predicate<UUID> olderClient;
        if (Bukkit.getPluginManager().getPlugin("ViaVersion") != null) {
            olderClient = ViaClients::olderThanServer;
        } else {
            // Without ViaVersion every client is on the server's own version.
            olderClient = id -> false;
        }
        GrimBridge bridge = new GrimBridge(plugin, grace, olderClient, api.getEventBus(),
                api.getGrimPlugin(plugin));
        bridge.subscribe();
        Bukkit.getPluginManager().registerEvents(bridge, plugin);
        plugin.getSLF4JLogger().info("Hooked into GrimAC {}: older clients get a settling window after joining",
                api.getGrimVersion());
        return bridge;
    }

    private void subscribe() {
        // Grim fires these on its netty threads.
        events.get(FlagEvent.class).onFlagSupplier(owner, (user, check, verbose, cancelled) -> {
            if (cancelled || !grace.suppresses(user.getUniqueId(), check.getCheckName())) {
                return cancelled;
            }
            if (log.isDebugEnabled()) {
                log.debug("Dropped Grim {} flag for {} (older client, settling): {}", check.getCheckName(),
                        user.getName(), verbose.get());
            }
            return true;
        });
        // Once per movement tick Grim simulated: that's what the window is counted in.
        events.get(CompletePredictionEvent.class).onCompletePrediction(owner, (user, check, offset, cancelled) -> {
            grace.tick(user.getUniqueId());
            return cancelled;
        });
        events.get(GrimQuitEvent.class).onQuit(owner, user -> grace.forget(user.getUniqueId()));
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
        events.unregisterAllListeners(owner);
    }

    private void startIfOlder(Player player) {
        UUID id = player.getUniqueId();
        if (olderClient.test(id)) {
            grace.start(id);
        }
    }

    // ------------------------------------------------------------------ Bukkit

    // Each of these resets Grim's position tracking: it waits for the client to accept a new
    // teleport, and the mispredictions come right after that. The events fire before the
    // client can answer, so the window is open in time.

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        startIfOlder(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        startIfOlder(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(PlayerTeleportEvent event) {
        if (event.getTo().getWorld() != null && !event.getTo().getWorld().equals(event.getFrom().getWorld())) {
            startIfOlder(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        grace.forget(event.getPlayer().getUniqueId());
    }
}
