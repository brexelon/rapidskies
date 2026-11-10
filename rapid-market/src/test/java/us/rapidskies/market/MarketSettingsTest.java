package us.rapidskies.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import us.rapidskies.common.config.ConfigView;

class MarketSettingsTest {

    private static MarketSettings bundled() throws Exception {
        try (InputStream in = MarketSettingsTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            return MarketSettings.from(ConfigView.load(in));
        }
    }

    @Test
    void bundledConfigMatchesThePlan() throws Exception {
        MarketSettings s = bundled();
        // PLAN.md §4.2: 12, 24 or 48 hours; 2% fee; 5% tax; 10 listings; 7-day average.
        assertEquals(List.of(Duration.ofHours(12), Duration.ofHours(24), Duration.ofHours(48)), s.durations());
        assertEquals(Duration.ofHours(24), s.defaultDuration());
        assertEquals(20, s.fees().listingFee(1000));
        assertEquals(50, s.fees().tax(1000));
        assertEquals(10, s.baseListings());
        assertEquals(7, s.historyDays());
        assertTrue(s.blockedItems().isEmpty());
    }

    @Test
    void durationsAreTypedInHoursOrDays() throws Exception {
        MarketSettings s = bundled();
        assertEquals(Duration.ofHours(24), s.duration(null).orElseThrow(), "the default");
        assertEquals(Duration.ofHours(12), s.duration("12").orElseThrow());
        assertEquals(Duration.ofHours(48), s.duration("48h").orElseThrow());
        assertEquals(Duration.ofHours(48), s.duration("2d").orElseThrow());
        assertTrue(s.duration("36").isEmpty(), "not one of the choices");
        assertTrue(s.duration("soon").isEmpty());
    }

    @Test
    void problemsAreReportedTogether() {
        ConfigView bad = ConfigView.of(Map.of(
                "listing", Map.of("durations-hours", List.of(12, "x"), "default-hours", 6, "min-price", 0),
                "fees", Map.of("sales-tax-percent", "120")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> MarketSettings.from(bad));
        assertTrue(e.getMessage().contains("durations-hours"), e.getMessage());
        assertTrue(e.getMessage().contains("default-hours"), e.getMessage());
        assertTrue(e.getMessage().contains("min-price"), e.getMessage());
        assertTrue(e.getMessage().contains("sales tax"), e.getMessage());
    }

    @Test
    void blockedItemsGetANamespace() {
        MarketSettings s = MarketSettings.from(ConfigView.of(Map.of("listing", Map.of("blocked-items",
                List.of("Bedrock", "custom:thing")))));
        assertEquals(Set.of("minecraft:bedrock", "custom:thing"), s.blockedItems());
    }
}
