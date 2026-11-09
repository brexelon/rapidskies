package us.rapidskies.core;

import org.slf4j.Logger;
import us.rapidskies.common.config.ConfigView;

/** rapid-core's non-connection settings. */
public record CoreSettings(String serverId, String group, String advertiseAddress, boolean noAttackCooldown,
                           boolean disableSweepAttacks) {

    static CoreSettings from(ConfigView c, int port, Logger log) {
        String id = c.string("server.id", "").trim();
        if (id.isEmpty()) {
            id = "server-" + port;
            log.warn("server.id is empty in plugins/rapid-core/config.yml; using '{}'. Run `rsdeploy.py apply` "
                    + "so the proxy can match this server to its velocity.toml entry.", id);
        }
        String group = c.string("server.group", "").trim();
        if (group.isEmpty()) {
            group = "unknown";
            log.warn("server.group is empty in plugins/rapid-core/config.yml; this server won't receive hub traffic.");
        }
        return new CoreSettings(id, group, c.string("server.advertise-address", "").trim(),
                c.bool("combat.no-attack-cooldown", true), c.bool("combat.disable-sweep-attacks", true));
    }
}
