package us.rapidskies.core;

import java.util.Optional;
import org.slf4j.Logger;
import us.rapidskies.common.config.ConfigView;
import us.rapidskies.common.economy.EconomyMode;

/** rapid-core's non-connection settings. */
public record CoreSettings(String serverId, String group, String advertiseAddress, Optional<EconomyMode> gamemode,
                           Afk afk, Tailwind tailwind, Playtime playtime, Staff staff, boolean trails) {

    /** AFK detection (PLAN.md §6.2 anti-farm, §5.3). */
    public record Afk(int idleSeconds) {
    }

    /** Tailwind spending on gamemode servers (PLAN.md §5.3); accrual is the proxy's. */
    public record Tailwind(long starterSeconds, int bonusPercent) {
    }

    /** Bolts for active play (PLAN.md §6.2: 2 per 15 minutes, capped at 20 a day). */
    public record Playtime(boolean enabled, long amount, int everyMinutes, long dailyCap) {
    }

    public record Staff(boolean vanishOnStaffMode, int freezeReminderSeconds) {
    }

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
        Optional<EconomyMode> gamemode = Optional.empty();
        String mode = c.string("server.gamemode", "").trim();
        if (!mode.isEmpty()) {
            try {
                gamemode = Optional.of(EconomyMode.byId(mode));
            } catch (IllegalArgumentException e) {
                log.error("server.gamemode '{}' is not skyrealms or horizons; running as a non-gamemode server", mode);
            }
        }
        return new CoreSettings(id, group, c.string("server.advertise-address", "").trim(), gamemode,
                new Afk(Math.max(30, c.integer("afk.idle-seconds", 300))),
                new Tailwind(Math.max(0, c.integer("tailwind.starter-minutes", 120)) * 60L,
                        Math.max(0, c.integer("tailwind.bonus-percent", 50))),
                new Playtime(c.bool("rewards.playtime.enabled", true),
                        Math.max(1, c.integer("rewards.playtime.amount", 2)),
                        Math.max(1, c.integer("rewards.playtime.every-minutes", 15)),
                        Math.max(0, c.integer("rewards.playtime.daily-cap", 20))),
                new Staff(c.bool("staff.vanish-on-staff-mode", true),
                        Math.max(5, c.integer("staff.freeze-reminder-seconds", 15))),
                c.bool("cosmetics.trails", true));
    }
}
