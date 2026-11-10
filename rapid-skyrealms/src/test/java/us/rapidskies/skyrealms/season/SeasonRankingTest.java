package us.rapidskies.skyrealms.season;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import us.rapidskies.skyrealms.hook.MarketListings;

class SeasonRankingTest {

    private static final UUID OLD_GIANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NEWCOMER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID IDLE = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID TIED = UUID.fromString("00000000-0000-0000-0000-000000000004");

    @Test
    void ranksGainsNotTotals() {
        Map<UUID, BigDecimal> worths = Map.of(OLD_GIANT, new BigDecimal("1000500"), NEWCOMER, new BigDecimal("2000"),
                IDLE, new BigDecimal("5000"), TIED, new BigDecimal("2500"));
        Map<UUID, BigDecimal> baselines = Map.of(OLD_GIANT, new BigDecimal("1000000"), IDLE, new BigDecimal("5000"),
                TIED, new BigDecimal("500"));
        List<SeasonRanking.Entry> ranking = SeasonRanking.rank(worths, baselines);
        // The newcomer (no baseline: created this season) gained 2000, as did TIED; ties by id.
        assertEquals(List.of(NEWCOMER, TIED, OLD_GIANT), ranking.stream().map(SeasonRanking.Entry::island).toList(),
                "a long-established island doesn't win by its size alone; no gain, no place");
        assertEquals(1, SeasonRanking.rankOf(ranking, NEWCOMER));
        assertEquals(0, SeasonRanking.rankOf(ranking, IDLE));
        assertEquals("2,000", SeasonService.worth(new BigDecimal("2000.75")));
    }

    @Test
    void marketListingsFromIslandLevel() {
        assertEquals(0, MarketListings.bonus(99, 100, 10));
        assertEquals(1, MarketListings.bonus(150, 100, 10));
        assertEquals(10, MarketListings.bonus(50_000, 100, 10), "capped");
        assertEquals(0, MarketListings.bonus(-5, 100, 10));
    }
}
