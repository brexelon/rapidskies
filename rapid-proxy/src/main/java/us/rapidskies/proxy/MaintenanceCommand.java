package us.rapidskies.proxy;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.redis.Channels;
import us.rapidskies.common.redis.RedisBus;

/**
 * {@code /maintenance on|off|status}. The flag lives in Redis so it survives proxy
 * restarts and applies to every proxy. Turning it on disconnects everyone without
 * {@value ConnectionListener#MAINTENANCE_BYPASS}.
 */
final class MaintenanceCommand implements SimpleCommand {

    static final String PERMISSION = "rapid.command.maintenance";

    private final ProxyServer proxy;
    private final Lang lang;
    private final RedisBus redis;
    private final NetworkDirectory directory;
    private final Logger logger;

    MaintenanceCommand(ProxyServer proxy, Lang lang, RedisBus redis, NetworkDirectory directory, Logger logger) {
        this.proxy = proxy;
        this.lang = lang;
        this.redis = redis;
        this.directory = directory;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "on" -> set(source, true);
            case "off" -> set(source, false);
            case "status" -> lang.send(source, directory.maintenance() ? "maintenance.status-on" : "maintenance.status-off");
            default -> lang.send(source, "maintenance.usage");
        }
    }

    private void set(CommandSource source, boolean on) {
        String by = source instanceof Player p ? p.getUsername() : "console";
        try {
            if (on) {
                redis.set(Channels.MAINTENANCE_KEY, "true");
            } else {
                redis.delete(Channels.MAINTENANCE_KEY);
            }
            redis.publishJson(Channels.MAINTENANCE, new Channels.MaintenanceChanged(on, by));
        } catch (RuntimeException e) {
            logger.error("Couldn't change maintenance mode", e);
            lang.send(source, "maintenance.failed");
            return;
        }
        directory.setMaintenanceLocally(on);
        logger.info("Maintenance mode {} by {}", on ? "enabled" : "disabled", by);
        if (on) {
            for (Player p : proxy.getAllPlayers()) {
                if (!p.hasPermission(ConnectionListener.MAINTENANCE_BYPASS)) {
                    p.disconnect(lang.get("kick.maintenance"));
                }
            }
        }
        lang.send(source, on ? "maintenance.enabled" : "maintenance.disabled");
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(PERMISSION);
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        String[] args = invocation.arguments();
        String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (args.length > 1) {
            return CompletableFuture.completedFuture(List.of());
        }
        return CompletableFuture.completedFuture(
                List.of("on", "off", "status").stream().filter(s -> s.startsWith(typed)).toList());
    }
}
