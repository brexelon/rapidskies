package us.rapidskies.common.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class FeaturedRotationTest {

    private static List<String> pool(int n) {
        return IntStream.range(0, n).mapToObj(i -> "tag:item-" + i).toList();
    }

    @Test
    void weeksStartOnMondayUtc() {
        long sunday = FeaturedRotation.week(Instant.parse("2026-11-15T23:59:59Z"));
        long monday = FeaturedRotation.week(Instant.parse("2026-11-16T00:00:00Z"));
        assertEquals(sunday + 1, monday);
        assertEquals(Instant.parse("2026-11-16T00:00:00Z"), FeaturedRotation.endOf(sunday));
        assertEquals(0, FeaturedRotation.week(Instant.parse("1970-01-05T00:00:00Z")));
        assertEquals(java.time.LocalDate.parse("2026-11-09"),
                FeaturedRotation.monday(Instant.parse("2026-11-11T08:00:00Z")));
    }

    @Test
    void sameWeekSamePickWhateverTheOrder() {
        List<String> pool = pool(16);
        List<String> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled);
        assertEquals(FeaturedRotation.pick(pool, 2914, 4), FeaturedRotation.pick(shuffled, 2914, 4));
        assertEquals(4, new HashSet<>(FeaturedRotation.pick(pool, 2914, 4)).size());
    }

    @Test
    void neverTheSameItemTwoWeeksRunning() {
        for (int n = 12; n <= 20; n++) {
            List<String> pool = pool(n);
            for (long week = 2800; week < 3200; week++) {
                Set<String> now = new HashSet<>(FeaturedRotation.pick(pool, week, 4));
                for (String s : FeaturedRotation.pick(pool, week + 1, 4)) {
                    assertFalse(now.contains(s), s + " in weeks " + week + " and " + (week + 1) + " (pool " + n + ")");
                }
            }
        }
    }

    @Test
    void everyItemComesRound() {
        List<String> pool = pool(16);
        Set<String> seen = new HashSet<>();
        // A cycle is pool/size weeks; two cycles cover everything even with the boundary swaps.
        for (long week = 100; week < 108; week++) {
            seen.addAll(FeaturedRotation.pick(pool, week, 4));
        }
        assertEquals(new HashSet<>(pool), seen);
    }

    @Test
    void smallPoolsShowEverything() {
        assertEquals(List.of("a", "b"), FeaturedRotation.pick(List.of("b", "a"), 5, 4));
        assertTrue(FeaturedRotation.pick(pool(10), 5, 0).isEmpty());
    }
}
