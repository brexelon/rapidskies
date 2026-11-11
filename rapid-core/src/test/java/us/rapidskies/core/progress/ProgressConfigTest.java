package us.rapidskies.core.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import us.rapidskies.common.config.ConfigView;
import us.rapidskies.common.rank.Rank;
import us.rapidskies.core.cosmetic.Catalog;
import us.rapidskies.core.cosmetic.Cosmetic;

class ProgressConfigTest {

    private static final Catalog CATALOG;
    private static final ProgressConfig CONFIG;

    static {
        try {
            CATALOG = Catalog.load(ProgressConfigTest.class.getClassLoader());
            CONFIG = ProgressConfig.load(ProgressConfigTest.class.getClassLoader(), CATALOG);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static ConfigView yaml(String text) {
        return ConfigView.load(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void bundledConfigMatchesThePlan() {
        // PLAN.md §6.2: 25 Bolts a level, a 5 → 25 login streak.
        assertEquals(25, CONFIG.boltsPerLevel());
        assertEquals(5, CONFIG.streakReward(1));
        assertEquals(25, CONFIG.streakReward(7));
        assertEquals(25, CONFIG.streakReward(40));
        // PLAN.md §6.1: monthly stipends.
        assertEquals(100, CONFIG.stipend(Rank.RAPID_PLUS));
        assertEquals(250, CONFIG.stipend(Rank.RAPID_PLUS_PLUS));
        assertEquals(600, CONFIG.stipend(Rank.RAPID_PLUS_PLUS_PLUS));
        assertEquals(0, CONFIG.stipend(Rank.DEFAULT));
        assertTrue(CONFIG.eventXp().keySet().containsAll(java.util.Set.of("expedition", "skyborne", "parkour")));
    }

    @Test
    void levelRewardsAreEarnedCosmetics() {
        CONFIG.levelRewards().forEach((level, ids) -> ids.forEach(id -> {
            Cosmetic c = CATALOG.find(id).orElseThrow();
            assertEquals(Cosmetic.Obtain.LEVEL, c.obtain(), id + " (level " + level + ") isn't a level cosmetic");
        }));
        assertEquals(java.util.Optional.of(10), CONFIG.nextRewardLevel(5));
    }

    @Test
    void seasonOneIsAboutEightWeeksOfCosmetics() {
        ProgressConfig.Season s1 = CONFIG.seasons().getFirst();
        long days = java.time.temporal.ChronoUnit.DAYS.between(s1.starts(), s1.ends());
        assertTrue(days >= 49 && days <= 70, "season 1 runs " + days + " days");
        for (int t = 1; t <= s1.tiers(); t++) {
            for (String id : s1.free(t)) {
                assertEquals(Cosmetic.Obtain.PASS, CATALOG.find(id).orElseThrow().obtain(), id);
            }
            for (String id : s1.premium(t)) {
                assertEquals(Cosmetic.Obtain.PASS, CATALOG.find(id).orElseThrow().obtain(), id);
            }
        }
        assertTrue(s1.running(s1.startsAt()));
        assertFalse(s1.running(s1.endsAt()));
        assertEquals(s1, CONFIG.season(s1.startsAt().plusSeconds(3600)).orElseThrow());
        assertEquals(s1, CONFIG.nextSeason(s1.startsAt().minusSeconds(1)).orElseThrow());
        assertEquals(s1.tiers(), s1.tierOf(Long.MAX_VALUE / 2));
        assertEquals(1, s1.tierOf(s1.xpPerTier()));
        assertEquals(0, s1.tierOf(s1.xpPerTier() - 1));
        // Every pass cosmetic is reachable from some season, so none is unobtainable.
        for (Cosmetic c : CATALOG.all()) {
            if (c.obtain() == Cosmetic.Obtain.PASS) {
                boolean listed = CONFIG.seasons().stream().anyMatch(s -> s.free().values().stream()
                        .anyMatch(l -> l.contains(c.id())) || s.premium().values().stream().anyMatch(l -> l.contains(c.id())));
                assertTrue(listed, c.id() + " isn't on any Season Pass");
            }
        }
    }

    @Test
    void refusesUnknownCosmeticsAndOverlappingSeasons() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ProgressConfig.parse(yaml("""
                levels:
                  rewards:
                    5: ["title:nope"]
                streak:
                  rewards: [5]
                pass:
                  seasons:
                    - { id: a, starts: "2027-01-01", ends: "2027-03-01", tiers: 10, xp-per-tier: 100, premium-price: 5 }
                    - { id: b, starts: "2027-02-01", ends: "2027-04-01", tiers: 10, xp-per-tier: 100, premium-price: 5 }
                """), CATALOG));
        assertTrue(e.getMessage().contains("title:nope"), e.getMessage());
        assertTrue(e.getMessage().contains("overlap"), e.getMessage());
        assertTrue(CONFIG.season(Instant.parse("2020-01-01T00:00:00Z")).isEmpty());
    }
}
