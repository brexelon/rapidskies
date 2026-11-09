package us.rapidskies.common.parkour;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import us.rapidskies.common.profile.ProfileRepository;
import us.rapidskies.common.testing.TestServices;

@EnabledIf("us.rapidskies.common.testing.TestServices#dockerAvailable")
class ParkourTimesIT {

    // A Wednesday; the week runs Monday 2026-11-09 to Sunday 2026-11-15.
    private static final Instant WEDNESDAY = Instant.parse("2026-11-11T12:00:00Z");
    private static final Instant NEXT_WEEK = Instant.parse("2026-11-16T00:00:01Z");

    private ParkourTimes times;
    private UUID steve;
    private UUID alex;

    @BeforeEach
    void setUp() throws Exception {
        TestServices.truncate("parkour_times", "players");
        times = new ParkourTimes(TestServices.database());
        ProfileRepository profiles = new ProfileRepository(TestServices.database());
        steve = profiles.recordLogin(UUID.randomUUID(), "Steve", "hub-1").uuid();
        alex = profiles.recordLogin(UUID.randomUUID(), "Alex", "hub-1").uuid();
    }

    @Test
    void keepsOnlyTheBestTime() throws Exception {
        ParkourTimes.Result first = times.record("main", steve, 50_000, WEDNESDAY);
        assertTrue(first.personalBest());
        assertEquals(Optional.empty(), first.previousBest());
        assertEquals(new ParkourTimes.Standing(50_000, 1), first.allTime());

        ParkourTimes.Result slower = times.record("main", steve, 60_000, WEDNESDAY.plusSeconds(60));
        assertFalse(slower.personalBest());
        assertFalse(slower.weeklyBest());
        assertEquals(50_000, slower.allTime().timeMs());

        ParkourTimes.Result faster = times.record("main", steve, 45_000, WEDNESDAY.plusSeconds(120));
        assertTrue(faster.personalBest());
        assertEquals(Optional.of(50_000), faster.previousBest());
        assertEquals(45_000, times.top("main", ParkourTimes.ALL_TIME, 10).getFirst().timeMs());
    }

    @Test
    void ranksFastestFirstAndTiesByWhoWasFirst() throws Exception {
        times.record("main", steve, 40_000, WEDNESDAY.plusSeconds(10));
        times.record("main", alex, 40_000, WEDNESDAY);

        List<ParkourTimes.Entry> top = times.top("main", ParkourTimes.ALL_TIME, 10);
        assertEquals(List.of("Alex", "Steve"), top.stream().map(ParkourTimes.Entry::name).toList());
        assertEquals(List.of(1, 2), top.stream().map(ParkourTimes.Entry::rank).toList());
        assertEquals(2, times.standing("main", ParkourTimes.ALL_TIME, steve).orElseThrow().rank());

        ParkourTimes.Result beat = times.record("main", steve, 39_999, WEDNESDAY.plusSeconds(20));
        assertEquals(1, beat.allTime().rank());
        assertEquals(2, times.standing("main", ParkourTimes.ALL_TIME, alex).orElseThrow().rank());
    }

    @Test
    void weeklyBoardsStartEmptyEachWeek() throws Exception {
        times.record("main", steve, 30_000, WEDNESDAY);
        String thisWeek = ParkourTimes.week(WEDNESDAY);
        assertEquals("2026-W46", thisWeek);
        assertEquals(1, times.top("main", thisWeek, 10).size());

        // A slower run next week is still that week's best, but not an all-time best.
        ParkourTimes.Result nextWeek = times.record("main", steve, 35_000, NEXT_WEEK);
        assertTrue(nextWeek.weeklyBest());
        assertFalse(nextWeek.personalBest());
        assertEquals(new ParkourTimes.Standing(35_000, 1), nextWeek.weekly());
        assertEquals(30_000, nextWeek.allTime().timeMs());
        assertEquals(30_000, times.top("main", thisWeek, 10).getFirst().timeMs());
    }

    @Test
    void coursesAreSeparate() throws Exception {
        times.record("main", steve, 30_000, WEDNESDAY);
        assertTrue(times.top("tower", ParkourTimes.ALL_TIME, 10).isEmpty());
        assertTrue(times.standing("tower", ParkourTimes.ALL_TIME, steve).isEmpty());
    }
}
