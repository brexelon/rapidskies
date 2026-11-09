package us.rapidskies.proxy;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.server.ServerPing;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import us.rapidskies.common.lang.Lang;

/**
 * Server list entry (PLAN.md §2): branded line 1, rotating line 2, network-wide player
 * count, and a red version label for clients that can't join or during maintenance.
 */
final class PingListener {

    private final Lang lang;
    private final NetworkDirectory directory;
    private final ProxySettings settings;

    PingListener(Lang lang, NetworkDirectory directory, ProxySettings settings) {
        this.lang = lang;
        this.directory = directory;
        this.settings = settings;
    }

    @Subscribe
    public void onPing(ProxyPingEvent event) {
        ServerPing.Builder ping = event.getPing().asBuilder();
        int clientProtocol = event.getConnection().getProtocolVersion().getProtocol();

        Component line2;
        if (directory.maintenance()) {
            line2 = lang.get("motd.maintenance");
            // A protocol no client speaks makes the client show the name in red instead of bars.
            ping.version(new ServerPing.Version(-1, plain("motd.version-maintenance")));
        } else {
            List<Component> rotation = lang.list("motd.rotation");
            long slot = System.currentTimeMillis() / 1000 / settings.motdRotateSeconds();
            line2 = rotation.isEmpty() ? Component.empty() : rotation.get((int) (slot % rotation.size()));
            if (clientProtocol < settings.minimumProtocol()) {
                ping.version(new ServerPing.Version(ProtocolVersion.MAXIMUM_VERSION.getProtocol(),
                        plain("motd.version-unsupported")));
            }
        }
        ping.description(lang.get("motd.line1").append(Component.newline()).append(line2));

        // Heartbeat totals cover every backend; fall back to this proxy's count if the
        // registry is empty (Redis trouble).
        int online = directory.networkOnline();
        if (online > 0) {
            ping.onlinePlayers(online);
        }
        event.setPing(ping.build());
    }

    private String plain(String key) {
        return PlainTextComponentSerializer.plainText().serialize(lang.get(key));
    }
}
