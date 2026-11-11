package us.rapidskies.common.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NetworkLevelsTest {

    private static final NetworkLevels LEVELS = new NetworkLevels(250, 50, 1500, 100);

    @Test
    void curveRisesThenCaps() {
        assertEquals(250, LEVELS.toNext(1));
        assertEquals(300, LEVELS.toNext(2));
        assertEquals(1500, LEVELS.toNext(26));
        assertEquals(1500, LEVELS.toNext(80));
        assertEquals(0, LEVELS.totalFor(1));
        assertEquals(250, LEVELS.totalFor(2));
        assertEquals(550, LEVELS.totalFor(3));
    }

    @Test
    void levelOfAndTotalForAgree() {
        for (int level = 1; level <= 100; level++) {
            long total = LEVELS.totalFor(level);
            assertEquals(level, LEVELS.levelOf(total), "at " + total);
            if (level > 1) {
                assertEquals(level - 1, LEVELS.levelOf(total - 1));
            }
        }
        assertEquals(100, LEVELS.levelOf(Long.MAX_VALUE / 4));
    }

    @Test
    void progressWithinALevel() {
        NetworkLevels.Progress p = LEVELS.progress(400);
        assertEquals(2, p.level());
        assertEquals(150, p.into());
        assertEquals(300, p.needed());
        assertTrue(LEVELS.progress(LEVELS.totalFor(100)).maxed());
    }

    @Test
    void refusesNonsense() {
        assertThrows(IllegalArgumentException.class, () -> new NetworkLevels(0, 50, 1500, 100));
        assertThrows(IllegalArgumentException.class, () -> new NetworkLevels(250, 50, 100, 100));
        assertThrows(IllegalArgumentException.class, () -> new NetworkLevels(250, 50, 1500, 1));
    }
}
