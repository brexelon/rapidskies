package us.rapidskies.skyrealms.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import us.rapidskies.skyrealms.SkyRealmsSettings;

class RouteRulesTest {

    private static final SkyRealmsSettings.Routes CFG = new SkyRealmsSettings.Routes(2, 1, 3,
            Map.of("daily", 24, "weekly", 168), 16, 1728, 10, 1_000_000, 72, 3, 60);
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-11-10T12:00:00Z");

    private static Route route(Route.State state, UUID seller, UUID buyer) {
        return new Route(1, seller, buyer, "A", "B", Material.CACTUS, 64, 2000, 168, state, A, Route.Side.SELLER,
                Optional.empty(), Optional.of(T0), 0, 0, T0);
    }

    @Test
    void offersAreChecked() {
        assertEquals(Optional.empty(), RouteRules.checkOffer(CFG, 64, 2000, "weekly", A, B));
        assertEquals(Optional.of("route.own-island"), RouteRules.checkOffer(CFG, 64, 2000, "weekly", A, A));
        assertEquals(Optional.of("route.bad-quantity"), RouteRules.checkOffer(CFG, 8, 2000, "weekly", A, B));
        assertEquals(Optional.of("route.bad-quantity"), RouteRules.checkOffer(CFG, 5000, 2000, "weekly", A, B));
        assertEquals(Optional.of("route.bad-price"), RouteRules.checkOffer(CFG, 64, 5, "weekly", A, B));
        assertEquals(Optional.of("route.bad-interval"), RouteRules.checkOffer(CFG, 64, 2000, "hourly", A, B));
    }

    @Test
    void capGrowsWithTiers() {
        assertEquals(2, RouteRules.cap(CFG, 0));
        assertEquals(4, RouteRules.cap(CFG, 2));
    }

    @Test
    void liveCountsOffersAndActiveButNotEnded() {
        UUID c = UUID.randomUUID();
        List<Route> all = List.of(route(Route.State.ACTIVE, A, B), route(Route.State.OFFERED, c, A),
                route(Route.State.ENDED, A, c));
        assertEquals(2, RouteRules.live(all, A));
        assertEquals(1, RouteRules.live(all, B));
    }

    @Test
    void taxRoundsDown() {
        assertEquals(60, RouteRules.tax(2000, 3));
        assertEquals(0, RouteRules.tax(33, 3));
        assertEquals(0, RouteRules.tax(2000, 0));
    }

    @Test
    void nextDeliveryNeverInThePast() {
        Route r = route(Route.State.ACTIVE, A, B);
        assertEquals(T0.plus(Duration.ofDays(7)), RouteRules.next(T0, r, T0.plusSeconds(60)));
        Instant late = T0.plus(Duration.ofDays(20));
        assertEquals(late.plus(Duration.ofDays(7)), RouteRules.next(T0, r, late), "after an outage: back on schedule");
    }

    @Test
    void offersExpire() {
        Route offer = route(Route.State.OFFERED, A, B);
        assertFalse(RouteRules.offerExpired(offer, T0.plus(Duration.ofHours(71)), 72));
        assertTrue(RouteRules.offerExpired(offer, T0.plus(Duration.ofHours(73)), 72));
        assertFalse(RouteRules.offerExpired(route(Route.State.ACTIVE, A, B), T0.plus(Duration.ofDays(30)), 72));
    }

    @Test
    void routeViews() {
        Route r = route(Route.State.OFFERED, A, B);
        assertEquals(B, r.answeringIsland(), "the seller offered, so the buyer answers");
        assertEquals("B", r.partnerName(A));
        assertTrue(r.involves(B));
        Route signed = r.signed(T0.plus(Duration.ofDays(7)));
        assertEquals(Route.State.ACTIVE, signed.state());
        assertFalse(signed.due(T0));
        assertTrue(signed.due(T0.plus(Duration.ofDays(7))));
        Route delivered = signed.missedOne(T0).delivered(T0.plus(Duration.ofDays(14)));
        assertEquals(1, delivered.deliveries());
        assertEquals(0, delivered.missed(), "a delivery resets the misses");
        assertEquals("3d 4h", RoutesMenu.duration(Duration.ofHours(76)));
        assertEquals("5h 10m", RoutesMenu.duration(Duration.ofMinutes(310)));
        assertEquals("1m", RoutesMenu.duration(Duration.ofSeconds(-5)));
    }
}
