package us.rapidskies.proxy.limbo;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import us.rapidskies.common.config.ConfigView;

/**
 * {@code limbo:} in rapid-proxy's config.yml.
 *
 * @param server           the Limbo server's name in velocity.toml
 * @param afkMinutes       minutes without input before a player is moved to limbo; 0 turns it off
 * @param afkExemptGroups  server groups whose AFK players are left where they are
 */
public record LimboSettings(String server, int afkMinutes, Set<String> afkExemptGroups) {

    public static LimboSettings from(ConfigView c) {
        return new LimboSettings(
                c.string("limbo.server", "limbo"),
                Math.max(0, c.integer("limbo.afk.minutes", 15)),
                Set.copyOf(c.stringList("limbo.afk.exempt-groups")));
    }

    public boolean afkEnabled() {
        return afkMinutes > 0;
    }

    public long afkMillis() {
        return TimeUnit.MINUTES.toMillis(afkMinutes);
    }
}
