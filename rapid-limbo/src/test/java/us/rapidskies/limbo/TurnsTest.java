package us.rapidskies.limbo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TurnsTest {

    @Test
    void walkingWithoutTurningIsNotATurn() {
        assertFalse(Turns.turned(90f, 10f, 90f, 10f));
        assertFalse(Turns.turned(90f, 10f, 90.5f, 10.5f));
    }

    @Test
    void turningEitherWayCounts() {
        assertTrue(Turns.turned(90f, 10f, 92f, 10f));
        assertTrue(Turns.turned(90f, 10f, 90f, 8f));
    }

    @Test
    void yawWrapsAround() {
        assertEquals(2f, Turns.angle(1f - 359f), 1e-4);
        assertFalse(Turns.turned(359.8f, 0f, 0.2f + 360f, 0f));
        assertTrue(Turns.turned(359f, 0f, 1f, 0f));
        assertFalse(Turns.turned(-180f, 0f, 180f, 0f));
    }
}
