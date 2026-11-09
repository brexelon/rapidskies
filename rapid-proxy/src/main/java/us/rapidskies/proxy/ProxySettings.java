package us.rapidskies.proxy;

import java.time.Duration;
import us.rapidskies.common.config.ConfigView;
import us.rapidskies.common.tailwind.TailwindBank;

/** Non-connection settings from rapid-proxy's config.yml. */
record ProxySettings(String hubGroup, long registryPollMillis, boolean autoRegister, long motdRotateSeconds,
                     int minimumProtocol, TailwindBank.Rules tailwind) {

    static ProxySettings from(ConfigView c) {
        return new ProxySettings(
                c.string("hub-group", "hub"),
                Math.max(500, c.integer("registry.poll-millis", 2000)),
                c.bool("registry.auto-register", true),
                Math.max(1, c.integer("motd.rotate-seconds", 8)),
                c.integer("minimum-protocol", 763),
                new TailwindBank.Rules(
                        Duration.ofMinutes(Math.max(0, c.integer("tailwind.minutes-per-offline-day", 120))),
                        Math.max(0, c.integer("tailwind.max-offline-days", 3))));
    }
}
