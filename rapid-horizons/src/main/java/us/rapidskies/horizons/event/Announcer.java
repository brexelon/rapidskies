package us.rapidskies.horizons.event;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import us.rapidskies.common.redis.Channels;
import us.rapidskies.horizons.RapidHorizons;

/**
 * Network-wide announcements for Horizons' events (a Skyborne fortress ten minutes out, a new
 * Frontier, the End opening; PLAN.md §5.2). They go out on the same Redis channel as
 * {@code /broadcast} ({@link Channels#BROADCAST}), so every backend's rapid-essentials shows
 * them, this one included, and players in the hub hear about them too. If Redis can't be
 * reached, this server still gets the message.
 */
public final class Announcer {

    private final RapidHorizons plugin;

    public Announcer(RapidHorizons plugin) {
        this.plugin = plugin;
    }

    /** To every server on the network. Main thread. */
    public void network(Component message) {
        String text = MiniMessage.miniMessage().serialize(message);
        Channels.Broadcast broadcast = new Channels.Broadcast(text, "Horizons");
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
