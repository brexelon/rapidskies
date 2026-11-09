package us.rapidskies.hub;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import us.rapidskies.common.registry.ServerRegistry;
import us.rapidskies.common.registry.ServerStatus;

/**
 * Players per gamemode, from the server heartbeats in Redis, for the menu, the NPC labels
 * and the sidebar. Vanished staff aren't counted (the heartbeats' {@code visible}). Polled off the main thread; listeners run on the main thread when a count changes.
 */
public final class ModeCounts {

    private static final long POLL_TICKS = 40;

    /** A server group's players; {@code open} if at least one of its servers takes players. */
    public record Status(int players, boolean open) {
        static final Status OFFLINE = new Status(0, false);
    }

    private final RapidHub hub;
    private final ServerRegistry registry;
    private volatile Map<String, Status> byGroup = Map.of();
    private volatile boolean warned;
    private BukkitTask task;

    public ModeCounts(RapidHub hub, ServerRegistry registry) {
        this.hub = hub;
        this.registry = registry;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(hub, this::poll, 1, POLL_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public Status status(String group) {
        return byGroup.getOrDefault(group, Status.OFFLINE);
    }

    private void poll() {
        Map<String, Status> next;
        try {
            next = summarize(registry.fetchAll(), System.currentTimeMillis());
            warned = false;
        } catch (RuntimeException e) {
            if (!warned) {
                warned = true;
                hub.getSLF4JLogger().warn("Couldn't read the server list from Redis: {}", e.getMessage());
            }
            return;
        }
        if (!next.equals(byGroup)) {
            byGroup = next;
            Bukkit.getScheduler().runTask(hub, hub::countsChanged);
        }
    }

    static Map<String, Status> summarize(Iterable<ServerStatus> servers, long now) {
        Map<String, Status> out = new HashMap<>();
        for (ServerStatus s : servers) {
            if (!s.isFresh(now, ServerRegistry.STALE_MILLIS)
                    || (s.state() != ServerStatus.State.ONLINE && s.state() != ServerStatus.State.RESTRICTED)) {
                continue;
            }
            Status before = out.getOrDefault(s.group(), Status.OFFLINE);
            out.put(s.group(), new Status(before.players() + s.visible(),
                    before.open() || s.state() == ServerStatus.State.ONLINE));
        }
        return Map.copyOf(out);
    }
}
