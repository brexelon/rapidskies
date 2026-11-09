package us.rapidskies.hub.parkour;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TimeFormatTest {

    @Test
    void runTimes() {
        assertEquals("0:00.000", TimeFormat.run(0));
        assertEquals("0:42.317", TimeFormat.run(42_317));
        assertEquals("12:03.004", TimeFormat.run(723_004));
        assertEquals("1:02:03.004", TimeFormat.run(3_723_004));
    }

    @Test
    void timerShowsTenths() {
        assertEquals("0:42.3", TimeFormat.timer(42_399));
        assertEquals("61:00.0", TimeFormat.timer(3_660_000));
    }

    @Test
    void countdownUsesTheTwoLargestUnits() {
        assertEquals("2d 4h", TimeFormat.countdown(Duration.ofHours(52).plusMinutes(30)));
        assertEquals("3h 12m", TimeFormat.countdown(Duration.ofMinutes(192)));
        assertEquals("45m", TimeFormat.countdown(Duration.ofMinutes(45)));
        assertEquals("<1m", TimeFormat.countdown(Duration.ofSeconds(20)));
        assertEquals("<1m", TimeFormat.countdown(Duration.ofSeconds(-5)));
    }
}
