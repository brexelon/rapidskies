package us.rapidskies.core;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import us.rapidskies.common.redis.Channels;
import us.rapidskies.common.redis.RedisBus;
import us.rapidskies.common.registry.ServerRegistry;
import us.rapidskies.common.registry.ServerStatus;
import us.rapidskies.common.registry.ServerStatus.State;
import us.rapidskies.core.staff.Vanish;

/**
 * Publishes this server's {@link ServerStatus} to Redis every few seconds. Vanished staff
 * count towards {@code online} (they take a slot) but not {@code visible}, the number the
 * network shows players. The values are
 * read on the main thread and written to Redis off it, so a slow Redis never costs a tick.
 *
 * <p>On shutdown it also tells the proxy who was here ({@link Channels#SERVER_STOPPING}),
 * so they're queued to come back once the server is up again.
 */
final class Heartbeat {

    private final RapidCore plugin;
    private final RedisBus redis;
    private final ServerRegistry registry;
    private final CoreSettings settings;
    private final long startedAt = System.currentTimeMillis();
    private BukkitTask task;

    Heartbeat(RapidCore plugin, RedisBus redis, CoreSettings settings) {
        this.plugin = plugin;
        this.redis = redis;
        this.registry = new ServerRegistry(redis);
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

    /**
     * Drops the heartbeat at shutdown so the proxy stops sending players here immediately.
     * Plugins are disabled before Paper kicks anyone, so the players are all still here.
     */
    void stop() {
        if (task != null) {
            task.cancel();
        }
        if (Bukkit.isStopping()) { // not a plugin reload: nobody is being moved then
            try {
                redis.publishJson(Channels.SERVER_STOPPING, new Channels.ServerStopping(settings.serverId(),
                        settings.group(), startedAt,
                        Bukkit.getOnlinePlayers().stream().map(p -> p.getUniqueId().toString()).toList()));
            } catch (RuntimeException e) {
                plugin.getSLF4JLogger().warn("Couldn't tell the proxy this server is stopping: {}", e.getMessage());
            }
        }
        try {
            registry.remove(settings.serverId());
        } catch (RuntimeException e) {
            plugin.getSLF4JLogger().warn("Couldn't remove heartbeat: {}", e.getMessage());
        }
    }
}
