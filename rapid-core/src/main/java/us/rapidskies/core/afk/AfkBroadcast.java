package us.rapidskies.core.afk;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import us.rapidskies.common.redis.Channels;
import us.rapidskies.common.redis.RedisBus;

/**
 * Tells the proxy when a player becomes AFK here or comes back ({@link Channels#AFK}). The
 * proxy moves players who stay AFK long enough to the Limbo server, and back here once
 * they're active again.
 */
public final class AfkBroadcast implements Listener {

    private final Plugin plugin;
    private final AfkTracker afk;
    private final RedisBus redis;
    private final String serverId;

    public AfkBroadcast(Plugin plugin, AfkTracker afk, RedisBus redis, String serverId) {
        this.plugin = plugin;
        this.afk = afk;
        this.redis = redis;
        this.serverId = serverId;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAfkChange(PlayerAfkChangeEvent event) {
        var player = event.getPlayer();
        Channels.AfkChanged msg = new Channels.AfkChanged(player.getUniqueId().toString(), serverId,
                event.isAfk(), afk.lastActive(player.getUniqueId()));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                redis.publishJson(Channels.AFK, msg);
            } catch (RuntimeException e) {
                plugin.getSLF4JLogger().warn("Couldn't tell the proxy {} is {}: {}", player.getName(),
                        msg.afk() ? "AFK" : "back", e.getMessage());
            }
        });
    }
}
