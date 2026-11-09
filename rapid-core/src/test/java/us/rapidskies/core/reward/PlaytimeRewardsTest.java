package us.rapidskies.core.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PlaytimeRewardsTest {

    private static final long STEP = 60;
    private static final long PERIOD = 15 * 60;

    @Test
    void paysOncePerFinishedInterval() {
        assertEquals(0, PlaytimeRewards.completedInterval(60, STEP, PERIOD));
        assertEquals(0, PlaytimeRewards.completedInterval(840, STEP, PERIOD));
        assertEquals(1, PlaytimeRewards.completedInterval(900, STEP, PERIOD));
        assertEquals(0, PlaytimeRewards.completedInterval(960, STEP, PERIOD));
        assertEquals(2, PlaytimeRewards.completedInterval(1800, STEP, PERIOD));
    }

    @Test
    void countingEveryMinuteForADayPaysEachIntervalExactlyOnce() {
        long paid = 0;
        long last = 0;
        for (long total = STEP; total <= 24 * 3600; total += STEP) {
            long n = PlaytimeRewards.completedInterval(total, STEP, PERIOD);
            if (n > 0) {
                assertEquals(last + 1, n, "intervals are numbered in order, none skipped");
                last = n;
                paid++;
            }
        }
        assertEquals(96, paid);
    }

    @Test
    void theDayStartsAtUtcMidnight() {
        Instant start = EarnGuard.startOfToday();
        assertEquals(0, start.atOffset(ZoneOffset.UTC).getHour());
        assertEquals(0, start.atOffset(ZoneOffset.UTC).getMinute());
    }
}
