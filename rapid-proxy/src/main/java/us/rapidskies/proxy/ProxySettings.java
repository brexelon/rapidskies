package us.rapidskies.proxy;

import us.rapidskies.common.config.ConfigView;

/** Non-connection settings from rapid-proxy's config.yml. */
record ProxySettings(String hubGroup, long registryPollMillis, boolean autoRegister, long motdRotateSeconds,
                     int minimumProtocol) {

    static ProxySettings from(ConfigView c) {
        return new ProxySettings(
                c.string("hub-group", "hub"),
                Math.max(500, c.integer("registry.poll-millis", 2000)),
                c.bool("registry.auto-register", true),
                Math.max(1, c.integer("motd.rotate-seconds", 8)),
                c.integer("minimum-protocol", 47));
    }
}
