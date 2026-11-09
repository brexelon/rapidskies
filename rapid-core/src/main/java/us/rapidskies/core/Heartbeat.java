package us.rapidskies.core;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import us.rapidskies.common.registry.ServerRegistry;
import us.rapidskies.common.registry.ServerStatus;
import us.rapidskies.common.registry.ServerStatus.State;
import us.rapidskies.core.staff.Vanish;

/**
 * Publishes this server's {@link ServerStatus} to Redis every few seconds. Vanished staff
 * count towards {@code online} (they take a slot) but not {@code visible}, the number the
 * network shows players. The values are
 * read on the main thread and written to Redis off it, so a slow Redis never costs a tick.
 */
final class Heartbeat {

    private final RapidCore plugin;
    private final ServerRegistry registry;
    private final CoreSettings settings;
    private final long startedAt = System.currentTimeMillis();
    private BukkitTask task;

    Heartbeat(RapidCore plugin, ServerRegistry registry, CoreSettings settings) {
        this.plugin = plugin;
        this.registry = registry;
        this.settings = settings;
    }

    void start() {
        long period = ServerRegistry.HEARTBEAT_SECONDS * 20;
        // First beat on the first tick, i.e. once the server has finished starting.
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::beat, 1, period);
    }

    private void beat() {
        // A whitelisted server (build, staging) only lets staff in: keep hub traffic away.
        State state = Bukkit.hasWhitelist() && Bukkit.isWhitelistEnforced() ? State.RESTRICTED : State.ONLINE;
        Vanish vanish = plugin.staffMode().vanish();
        int online = 0;
        int visible = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            online++;
            if (!vanish.isVanished(player)) {
                visible++;
            }
        }
        ServerStatus status = new ServerStatus(settings.serverId(), settings.group(), settings.advertiseAddress(),
                state, online, visible, Bukkit.getMaxPlayers(), startedAt, System.currentTimeMillis());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                registry.publish(status);
            } catch (RuntimeException e) {
                plugin.getSLF4JLogger().warn("Heartbeat to Redis failed: {}", e.getMessage());
            }
        });
    }

    /** Drops the heartbeat at shutdown so the proxy stops sending players here immediately. */
    void stop() {
        if (task != null) {
            task.cancel();
        }
        try {
            registry.remove(settings.serverId());
        } catch (RuntimeException e) {
            plugin.getSLF4JLogger().warn("Couldn't remove heartbeat: {}", e.getMessage());
        }
    }
}
