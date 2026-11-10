package us.rapidskies.skyrealms.route;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import us.rapidskies.skyrealms.SkyRealmsSettings;

/** The arithmetic and checks of Trade Routes, kept apart from Bukkit so they're unit-tested. */
public final class RouteRules {

    private RouteRules() {
    }

    /** Why an offer is refused, or empty if it's fine. Values are lang keys under {@code route.}. */
    public static Optional<String> checkOffer(SkyRealmsSettings.Routes cfg, int qty, long price, String interval,
                                              UUID from, UUID to) {
        if (from.equals(to)) {
            return Optional.of("route.own-island");
        }
        if (qty < cfg.minQuantity() || qty > cfg.maxQuantity()) {
            return Optional.of("route.bad-quantity");
        }
        if (price < cfg.minPrice() || price > cfg.maxPrice()) {
            return Optional.of("route.bad-price");
        }
        if (!cfg.intervals().containsKey(interval)) {
            return Optional.of("route.bad-interval");
        }
        return Optional.empty();
    }

    /** Routes (offered or active) an island may take part in at Realm Tier index {@code tierIndex} (0-based). */
    public static int cap(SkyRealmsSettings.Routes cfg, int tierIndex) {
        return cfg.maxPerIsland() + cfg.perTier() * Math.max(0, tierIndex);
    }

    /** How many live (offered or active) routes involve {@code island}. */
    public static long live(Collection<Route> routes, UUID island) {
        return routes.stream().filter(r -> r.state() != Route.State.ENDED && r.involves(island)).count();
    }

    /** The share of a payment kept as tax (rounded down, so the seller never gets less than the rest). */
    public static long tax(long price, int percent) {
        return Math.floorDiv(price * percent, 100);
    }

    /**
     * The next delivery after one at {@code due}: one interval on, but never in the past (a route
     * that was due during a long outage delivers once, then gets back on schedule).
     */
    public static Instant next(Instant due, Route route, Instant now) {
        Instant n = due.plus(route.interval());
        return n.isBefore(now) ? now.plus(route.interval()) : n;
    }

    public static boolean offerExpired(Route route, Instant now, int offerHours) {
        return route.state() == Route.State.OFFERED && route.createdAt().plusSeconds(offerHours * 3600L).isBefore(now);
    }
}
