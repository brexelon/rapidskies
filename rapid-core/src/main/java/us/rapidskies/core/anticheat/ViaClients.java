package us.rapidskies.core.anticheat;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import java.util.UUID;

/**
 * Which players ViaVersion/ViaBackwards translate for: those whose client is older than the
 * server. Its own class, so ViaVersion's types are only loaded when it's installed.
 */
final class ViaClients {

    private ViaClients() {
    }

    static boolean olderThanServer(UUID player) {
        ProtocolVersion client = Via.getAPI().getPlayerProtocolVersion(player);
        ProtocolVersion server = Via.getAPI().getServerVersion().lowestSupportedProtocolVersion();
        return client != null && client.isKnown() && server.isKnown() && client.olderThan(server);
    }
}
