package us.rapidskies.horizons.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TradeRulesTest {

    // max 2 active, max level 50, level XP 100 + 25n, +1%/level, 2000 points/day, floor 10%, 5 levels per chunk, max 20.
    private final TradeRules rules = new TradeRules(2, 50, 100, 25, 1, 2000, 10, 5, 20);

    @Test
    void levelsFollowTheArithmeticCurve() {
        assertEquals(100, rules.xpToNext(0));
        assertEquals(125, rules.xpToNext(1));
        assertEquals(0, rules.xpForLevel(0));
        assertEquals(100, rules.xpForLevel(1));
        assertEquals(225, rules.xpForLevel(2));
        assertEquals(375, rules.xpForLevel(3));
        assertEquals(0, rules.levelFor(99.9));
        assertEquals(1, rules.levelFor(100));
        assertEquals(2, rules.levelFor(300));
        assertEquals(50, rules.levelFor(1e12), "capped at the max level");
        assertEquals(0.5, rules.progress(162.5, 1), 1e-9, "half way from 100 to 225");
        assertEquals(1.0, rules.progress(1e12, 50));
    }

    @Test
    void softCapHalvesPastEachThreshold() {
        assertEquals(1.0, rules.softCap(0));
        assertEquals(1.0, rules.softCap(1999));
        assertEquals(0.5, rules.softCap(2000));
        assertEquals(0.5, rules.softCap(3999));
        assertEquals(0.25, rules.softCap(4000));
        assertEquals(0.125, rules.softCap(6000));
        assertEquals(0.1, rules.softCap(8000), "never below the floor");
        assertEquals(0.1, rules.softCap(Long.MAX_VALUE / 2));
        assertEquals(1.0, new TradeRules(2, 50, 100, 25, 1, 0, 10, 5, 20).softCap(1_000_000), "0 disables the cap");
    }

    @Test
    void rewardAppliesSoftCapTailwindAndLevelBonus() {
        TradeRules.Reward fresh = rules.reward(10, 0.5, 0, 0, 1.0);
        assertEquals(10, fresh.xp(), 1e-9);
        assertEquals(5, fresh.crowns(), 1e-9);

        TradeRules.Reward veteranRested = rules.reward(10, 0.5, 50, 0, 1.5);
        assertEquals(15, veteranRested.xp(), 1e-9, "Tailwind boosts XP");
        assertEquals(10 * 0.5 * 1.5 * 1.5, veteranRested.crowns(), 1e-9, "+50% from levels, +50% Tailwind");

        TradeRules.Reward capped = rules.reward(10, 0.5, 0, 2500, 1.0);
        assertEquals(5, capped.xp(), 1e-9);
        assertEquals(2.5, capped.crowns(), 1e-9);
    }

    @Test
    void levelBonusIsSmallSoCasualsKeepUp() {
        assertTrue(rules.incomeMultiplier(50) <= 1.5);
        assertEquals(1.5, rules.incomeMultiplier(80), 1e-9, "levels past the max add nothing");
    }

    @Test
    void claimChunksComeFromTotalLevels() {
        assertEquals(0, rules.claimChunks(4));
        assertEquals(1, rules.claimChunks(5));
        assertEquals(12, rules.claimChunks(60));
        assertEquals(20, rules.claimChunks(300), "capped");
        assertEquals(0, new TradeRules(2, 50, 100, 25, 1, 2000, 10, 0, 20).claimChunks(300), "0 turns it off");
    }

    @Test
    void dropCooldown() {
        Instant now = Instant.parse("2026-11-09T12:00:00Z");
        Duration day = Duration.ofHours(24);
        assertEquals(Duration.ZERO, TradeRules.cooldownLeft(null, now, day), "never dropped");
        assertEquals(Duration.ofHours(23), TradeRules.cooldownLeft(now.minus(Duration.ofHours(1)), now, day));
        assertEquals(Duration.ZERO, TradeRules.cooldownLeft(now.minus(day), now, day));
    }
}
