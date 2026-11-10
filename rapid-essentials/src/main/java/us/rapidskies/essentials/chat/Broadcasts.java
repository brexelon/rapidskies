package us.rapidskies.essentials.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.redis.Channels;
import us.rapidskies.essentials.RapidEssentials;

/**
 * {@code /broadcast}: an announcement on every server at once. It goes out on Redis
 * ({@link Channels#BROADCAST}) and each backend, this one included, shows it to everyone on
 * it. The text is MiniMessage with the brand tags; only admins have the command.
 */
public final class Broadcasts {

    public static final String PERMISSION = "rapid.command.broadcast";

    private final RapidEssentials plugin;

    public Broadcasts(RapidEssentials plugin) {
        this.plugin = plugin;
    }

    /** Listens for broadcasts. Call once, from onEnable. */
    public void start() {
        plugin.core().redis().subscribeJson(Channels.BROADCAST, Channels.Broadcast.class, b -> {
            // Redis thread; the bus outlives this plugin, so a late message is dropped.
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> show(b));
            }
        });
    }

    public void send(CommandSender sender, String message) {
        Channels.Broadcast broadcast = new Channels.Broadcast(message, sender.getName());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.core().redis().publishJson(Channels.BROADCAST, broadcast);
            } catch (RuntimeException e) {
                plugin.getSLF4JLogger().warn("Couldn't publish a broadcast: {}", e.getMessage());
                // Better here only than nowhere.
                Bukkit.getScheduler().runTask(plugin, () -> {
                    show(broadcast);
                    plugin.lang().send(sender, "broadcast.local-only");
                });
            }
        });
    }

    private void show(Channels.Broadcast broadcast) {
        Component text = Lang.parse(broadcast.message());
        Component line = plugin.lang().get("broadcast.format", Placeholder.component("message", text));
        Bukkit.getServer().sendMessage(line);
        plugin.getSLF4JLogger().info("Broadcast by {}: {}", broadcast.by(), broadcast.message());
    }
}
