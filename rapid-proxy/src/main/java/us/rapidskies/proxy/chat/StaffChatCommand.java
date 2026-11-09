package us.rapidskies.proxy.chat;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.slf4j.Logger;
import us.rapidskies.common.lang.Lang;

/**
 * {@code /staffchat <message>} (alias {@code /sc}): a channel for the team across every
 * server (PLAN.md §9). Only players with {@value #PERMISSION} can read or write it; the
 * console can write to it too.
 */
public final class StaffChatCommand implements SimpleCommand {

    public static final String PERMISSION = "rapid.staffchat";

    private final ProxyServer proxy;
    private final Lang lang;
    private final Logger logger;

    public StaffChatCommand(ProxyServer proxy, Lang lang, Logger logger) {
        this.proxy = proxy;
        this.lang = lang;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        if (invocation.arguments().length == 0) {
            lang.send(invocation.source(), "staffchat.usage");
            return;
        }
        String message = String.join(" ", invocation.arguments());
        String name = invocation.source() instanceof Player p ? p.getUsername() : "Console";
        String server = invocation.source() instanceof Player p
                ? p.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse("proxy") : "proxy";
        broadcast(lang.get("staffchat.format", Placeholder.unparsed("player", name),
                Placeholder.unparsed("server", server), Placeholder.unparsed("message", message)));
        logger.info("[Staff] [{}] {}: {}", server, name, message);
    }

    /** Sends to everyone on the team (anti-cheat alerts will use this too). */
    public void broadcast(Component message) {
        for (Player p : proxy.getAllPlayers()) {
            if (p.hasPermission(PERMISSION)) {
                p.sendMessage(message);
            }
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(PERMISSION);
    }
}
