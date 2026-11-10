package us.rapidskies.horizons.travel;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class RtpSearchTest {

    @Test
    void candidatesStayInTheRing() {
        Random random = new Random(42);
        int near = 0;
        for (int i = 0; i < 10_000; i++) {
            RtpSearch.Point p = RtpSearch.candidate(random, 100, -50, 200, 1000);
            double d = Math.hypot(p.x() - 100, p.z() + 50);
            assertTrue(d >= 199 && d <= 1001, "distance " + d);
            if (d < 600) {
                near++;
            }
        }
        // Even over the area: the inner part of the ring (200..600) is a third of it, not half.
        assertTrue(near > 2_800 && near < 3_900, "inner share " + near);
    }

    @Test
    void degenerateRadiiStillWork() {
        Random random = new Random(1);
        RtpSearch.Point p = RtpSearch.candidate(random, 0, 0, 500, 100);
        assertTrue(Math.hypot(p.x(), p.z()) <= 101, "min above max is clamped to max");
        p = RtpSearch.candidate(random, 0, 0, 0, 0);
        assertTrue(Math.abs(p.x()) <= 1 && Math.abs(p.z()) <= 1);
    }
}
