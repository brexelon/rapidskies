package us.rapidskies.core.afk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

class AfkTrackerTest {

    @Test
    void beingPushedAroundIsNotActivity() {
        assertFalse(AfkTracker.turned(at(0, 0, 90, 10), at(5, 3, 90, 10)), "water stream, same view");
        assertFalse(AfkTracker.turned(at(0, 0, 90, 10), at(0, 0, 90.4f, 10.4f)), "jitter under a degree");
    }

    @Test
    void turningTheViewIsActivity() {
        assertTrue(AfkTracker.turned(at(0, 0, 90, 10), at(0, 0, 95, 10)));
        assertTrue(AfkTracker.turned(at(0, 0, 90, 10), at(0, 0, 90, -20)));
    }

    @Test
    void yawWrapsAround() {
        assertTrue(AfkTracker.turned(at(0, 0, 359, 0), at(0, 0, 3, 0)), "359 to 3 is a 4 degree turn");
        assertFalse(AfkTracker.turned(at(0, 0, 359.8f, 0), at(0, 0, -0.1f, 0)), "same direction written two ways");
        assertFalse(AfkTracker.turned(at(0, 0, 180, 0), at(0, 0, -180, 0)));
    }

    private static Location at(double x, double z, float yaw, float pitch) {
        return new Location(null, x, 64, z, yaw, pitch);
    }
}
