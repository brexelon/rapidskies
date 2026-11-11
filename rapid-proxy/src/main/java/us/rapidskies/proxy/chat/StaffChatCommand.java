package us.rapidskies.proxy.chat;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.slf4j.Logger;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.rank.StaffRole;
import us.rapidskies.common.redis.Channels;
import us.rapidskies.proxy.rank.ProxyRanks;

/**
 * {@code /staffchat <message>} (alias {@code /sc}): a channel for the team across every
 * server (PLAN.md §9). Only players with {@value #PERMISSION} can read or write it; the
 * console can write to it too.
 */
public final class StaffChatCommand implements SimpleCommand {

    public static final String PERMISSION = "rapid.staffchat";

    private final ProxyServer proxy;
    private final Lang lang;
    private final ProxyRanks ranks;
    private final Logger logger;

    public StaffChatCommand(ProxyServer proxy, Lang lang, ProxyRanks ranks, Logger logger) {
        this.proxy = proxy;
        this.lang = lang;
        this.ranks = ranks;
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
        Optional<StaffRole> role = invocation.source() instanceof Player p ? ranks.of(p).staff() : Optional.empty();
        broadcast(lang.get("staffchat.format", Placeholder.component("role", role(role)),
                Placeholder.unparsed("player", name),
                Placeholder.unparsed("server", server), Placeholder.unparsed("message", message)));
        logger.info("[Staff] [{}] {}{}: {}", server, role.map(r -> r.group() + " ").orElse(""), name, message);
    }

    /** The role tag in front of a name, in its {@code grant.name} colors, or nothing. */
    private Component role(Optional<StaffRole> role) {
        return role.map(r -> lang.get("grant.name." + r.group()).append(Component.space()))
                .orElse(Component.empty());
    }

    /** Sends to everyone on the team (anti-cheat alerts will use this too). */
    public void broadcast(Component message) {
        for (Player p : proxy.getAllPlayers()) {
            if (p.hasPermission(PERMISSION)) {
                p.sendMessage(message);
            }
        }
    }

    /** An alert a backend sent over Redis ({@link Channels#STAFF_ALERT}); runs on the Redis thread. */
    public void alert(Channels.StaffAlert alert) {
        broadcast(lang.get("staffchat.alert", Placeholder.unparsed("server", alert.server()),
                Placeholder.component("message", Lang.parse(alert.message()))));
        logger.info("[Staff alert] [{}] {}", alert.server(), alert.message());
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(PERMISSION);
    }
}
