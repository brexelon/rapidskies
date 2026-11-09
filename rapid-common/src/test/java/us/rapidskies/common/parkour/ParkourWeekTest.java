package us.rapidskies.common.parkour;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ParkourWeekTest {

    @Test
    void weeksAreIsoWeeksInUtc() {
        // Monday 2026-11-09 00:00 UTC starts week 46; the Sunday before is still week 45.
        assertEquals("2026-W45", ParkourTimes.week(Instant.parse("2026-11-08T23:59:59Z")));
        assertEquals("2026-W46", ParkourTimes.week(Instant.parse("2026-11-09T00:00:00Z")));
        // ISO week-based year: 2027-01-01 is a Friday, so it's in 2026's last week.
        assertEquals("2026-W53", ParkourTimes.week(Instant.parse("2027-01-01T12:00:00Z")));
        assertEquals("2027-W01", ParkourTimes.week(Instant.parse("2027-01-04T00:00:00Z")));
    }

    @Test
    void weekEndsAtTheNextMondayMidnight() {
        Instant monday = Instant.parse("2026-11-16T00:00:00Z");
        assertEquals(monday, ParkourTimes.weekEnd(Instant.parse("2026-11-11T15:30:00Z")));
        assertEquals(monday, ParkourTimes.weekEnd(Instant.parse("2026-11-15T23:59:59Z")));
        // At Monday midnight the week that just started ends a week later.
        assertEquals(Instant.parse("2026-11-23T00:00:00Z"), ParkourTimes.weekEnd(monday));
    }
}
