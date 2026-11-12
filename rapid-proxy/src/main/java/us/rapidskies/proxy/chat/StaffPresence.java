package us.rapidskies.proxy.chat;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.rank.StaffRole;
import us.rapidskies.proxy.rank.ProxyRanks;
import us.rapidskies.proxy.session.VanishedStaff;

/**
 * Tells the team (everyone with {@value StaffChatCommand#PERMISSION}) when a team member
 * connects to the network, switches servers or leaves (PLAN.md §9). Only the proxy can tell
 * a server switch from a fresh join or a real quit, which is why this lives here and not in
 * rapid-core, whose servers only see players arrive and go. A vanished member's notices only
 * reach staff who can see vanished staff, and the member themselves. A "left" can't reach
 * the member who left: their connection is already gone by then.
 */
public final class StaffPresence {

    private final ProxyServer proxy;
    private final Lang lang;
    private final ProxyRanks ranks;
    private final VanishedStaff vanish;
    /** Team members online, with their role and server as of their last connect. */
    private final Map<UUID, Session> online = new ConcurrentHashMap<>();

    private record Session(StaffRole role, String server) {
    }

    public StaffPresence(ProxyServer proxy, Lang lang, ProxyRanks ranks, VanishedStaff vanish) {
        this.proxy = proxy;
        this.lang = lang;
        this.ranks = ranks;
        this.vanish = vanish;
    }

    /** A first server after login is "connected"; any later one is a switch (kick redirects included). */
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        String server = event.getServer().getServerInfo().getName();
        StaffRole role = ranks.of(player).staff().orElse(null);
        if (role == null) {
            // Not (or no longer) on the team.
            online.remove(player.getUniqueId());
            return;
        }
        online.put(player.getUniqueId(), new Session(role, server));
        var previous = event.getPreviousServer();
        if (previous.isEmpty()) {
            announce(player, role, "staffchat.presence.connected", Placeholder.unparsed("server", server));
        } else {
            announce(player, role, "staffchat.presence.switched", Placeholder.unparsed("server", server),
                    Placeholder.unparsed("from", previous.get().getServerInfo().getName()));
        }
    }

    /**
     * Only players who reached a server got a "connected", so only they get a "left". Their
     * role is the one remembered at connect: LuckPerms may already be unloading them.
     */
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        Session session = online.remove(player.getUniqueId());
        if (session == null || event.getLoginStatus() != DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN) {
            return;
        }
        announce(player, session.role(), "staffchat.presence.left", Placeholder.unparsed("server", session.server()));
    }

    private void announce(Player member, StaffRole role, String key, TagResolver... extra) {
        boolean vanished = vanish.vanished(member.getUniqueId());
        Component message = lang.get(key, TagResolver.resolver(extra),
                Placeholder.component("role", lang.get("grant.name." + role.group()).append(Component.space())),
                Placeholder.unparsed("player", member.getUsername()),
                Placeholder.component("vanished",
                        vanished ? lang.get("staffchat.presence.vanished") : Component.empty()));
        for (Player p : proxy.getAllPlayers()) {
            // The member hears their own notices too, if they can use staff chat; their own
            // vanish is no secret to them, so that check is waived for them.
            if (p.hasPermission(StaffChatCommand.PERMISSION)
                    && (!vanished || p.equals(member) || p.hasPermission(VanishedStaff.SEE))) {
                p.sendMessage(message);
            }
        }
    }
}
