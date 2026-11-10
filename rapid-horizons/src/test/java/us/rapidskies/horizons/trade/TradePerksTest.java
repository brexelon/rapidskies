package us.rapidskies.horizons.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TradePerksTest {

    private final TradePerks perks = new TradePerks(20, TradePerks.defaults());

    @Test
    void halfAtHalfLevelAndNeverTheWholeCap() {
        assertEquals(0, perks.percent(Trade.MINER, 0), "nothing at level 0");
        assertEquals(20.0, perks.percent(Trade.MINER, 20), 1e-9, "half of 40% at level 20");
        double atMax = perks.percent(Trade.MINER, 50);
        assertTrue(atMax < 40 && atMax > 28, "level 50 is about 71% of the cap: " + atMax);
        assertTrue(perks.percent(Trade.MINER, 1000) < 40, "the cap is never reached");
    }

    /** PLAN.md §5.2: a casual player isn't hopelessly behind a no-lifer. */
    @Test
    void theSoftCapKeepsCasualsClose() {
        double casual = perks.percent(Trade.FARMER, 10);
        double grinder = perks.percent(Trade.FARMER, 50);
        assertTrue(grinder / casual < 2.2, "five times the levels is barely twice the perk");
    }

    @Test
    void offWhenHalfLevelIsZeroOrNoMax() {
        TradePerks off = new TradePerks(0, TradePerks.defaults());
        assertEquals(0, off.percent(Trade.MINER, 40));
        TradePerks noBuilder = new TradePerks(20, Map.of(Trade.MINER, 40.0));
        assertEquals(0, noBuilder.percent(Trade.BUILDER, 40));
    }

    @Test
    void fishingWaitsShrinkButNeverBelowASecond() {
        assertEquals(300, TradePerks.shortenedWait(400, 0.25));
        assertEquals(20, TradePerks.shortenedWait(100, 0.95), "at least a second");
        assertEquals(10, TradePerks.shortenedWait(10, 0.5), "a short wait is never made longer");
        assertEquals(400, TradePerks.shortenedWait(400, 0));
    }
}
