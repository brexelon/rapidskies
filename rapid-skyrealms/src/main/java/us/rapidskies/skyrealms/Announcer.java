package us.rapidskies.skyrealms;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import us.rapidskies.common.redis.Channels;

/**
 * Network-wide announcements for SkyRealms (an Expedition on its way, a season's winners). They
 * go out on the {@code /broadcast} Redis channel, so every backend, the hub included, shows
 * them (PLAN.md §7: events are a reason to come over). Without Redis, this server still hears.
 */
public final class Announcer {

    private final RapidSkyRealms plugin;

    public Announcer(RapidSkyRealms plugin) {
        this.plugin = plugin;
    }

    /** To every server on the network. Main thread. */
    public void network(Component message) {
        Channels.Broadcast broadcast = new Channels.Broadcast(MiniMessage.miniMessage().serialize(message), "SkyRealms");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.core().redis().publishJson(Channels.BROADCAST, broadcast);
            } catch (RuntimeException e) {
                plugin.getSLF4JLogger().warn("Couldn't announce on the network ({}); showing it here only",
                        e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> local(message));
            }
        });
    }

    /** To everyone on this server. */
    public void local(Component message) {
        Bukkit.getServer().sendMessage(message);
    }
}
