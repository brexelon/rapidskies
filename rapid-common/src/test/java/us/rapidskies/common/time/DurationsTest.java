package us.rapidskies.common.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DurationsTest {

    @Test
    void parsesSingleAndCombinedUnits() {
        assertEquals(Optional.of(Duration.ofDays(30)), Durations.parse("30d"));
        assertEquals(Optional.of(Duration.ofMinutes(90)), Durations.parse("1h30m"));
        assertEquals(Optional.of(Duration.ofDays(14)), Durations.parse("2W"));
        assertEquals(Optional.of(Duration.ofDays(60)), Durations.parse("2mo"), "mo is months, m is minutes");
        assertEquals(Optional.of(Duration.ofDays(365).plusSeconds(5)), Durations.parse("1y5s"));
    }

    @Test
    void rejectsAnythingElse() {
        for (String bad : new String[] {"", "30", "d", "0d", "1h 30m", "1x", "abc", "-5d", "1d!", "x1d"}) {
            assertTrue(Durations.parse(bad).isEmpty(), bad);
        }
    }

    @Test
    void permanentWords() {
        assertTrue(Durations.isPermanent("perm"));
        assertTrue(Durations.isPermanent("Permanent"));
        assertTrue(Durations.isPermanent("forever"));
        assertTrue(!Durations.isPermanent("30d"));
    }

    @Test
    void formatsTheTwoLargestUnits() {
        assertEquals("29d 23h", Durations.format(Duration.ofDays(30).minusHours(1)));
        assertEquals("2h 15m", Durations.format(Duration.ofMinutes(135)));
        assertEquals("45s", Durations.format(Duration.ofSeconds(45)));
        assertEquals("1d", Durations.format(Duration.ofDays(1).plusMinutes(5)), "no gap-skipping");
        assertEquals("0s", Durations.format(Duration.ofSeconds(-3)));
    }
}
