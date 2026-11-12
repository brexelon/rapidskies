package us.rapidskies.core.anticheat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OlderClientGraceTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    @Test
    void dropsListedChecksUntilTheTicksRunOut() {
        OlderClientGrace grace = new OlderClientGrace(3, Set.of("Simulation", "AimModulo360"));
        grace.start(PLAYER);

        assertTrue(grace.suppresses(PLAYER, "Simulation"));
        assertTrue(grace.suppresses(PLAYER, "aimmodulo360"), "check names match ignoring case");
        assertFalse(grace.suppresses(PLAYER, "Reach"), "only the listed checks");
        assertFalse(grace.suppresses(OTHER, "Simulation"), "only players whose window was opened");

        grace.tick(PLAYER);
        grace.tick(PLAYER);
        assertTrue(grace.suppresses(PLAYER, "Simulation"));
        grace.tick(PLAYER);
        assertFalse(grace.suppresses(PLAYER, "Simulation"), "closed after 3 simulated ticks");
    }

    @Test
    void startReopensAFullWindow() {
        OlderClientGrace grace = new OlderClientGrace(2, Set.of("Simulation"));
        grace.start(PLAYER);
        grace.tick(PLAYER);
        grace.start(PLAYER); // e.g. changed worlds right after joining
        grace.tick(PLAYER);
        assertTrue(grace.suppresses(PLAYER, "Simulation"));
        grace.tick(PLAYER);
        assertFalse(grace.suppresses(PLAYER, "Simulation"));
    }

    @Test
    void ticksForPlayersWithoutAWindowDoNothing() {
        OlderClientGrace grace = new OlderClientGrace(2, Set.of("Simulation"));
        grace.tick(PLAYER);
        assertFalse(grace.suppresses(PLAYER, "Simulation"));
    }

    @Test
    void forgetClosesTheWindow() {
        OlderClientGrace grace = new OlderClientGrace(40, Set.of("Simulation"));
        grace.start(PLAYER);
        grace.forget(PLAYER);
        assertFalse(grace.suppresses(PLAYER, "Simulation"));
    }

    @Test
    void zeroTicksOrNoChecksIsOff() {
        OlderClientGrace noTicks = new OlderClientGrace(0, Set.of("Simulation"));
        noTicks.start(PLAYER);
        assertFalse(noTicks.enabled());
        assertFalse(noTicks.suppresses(PLAYER, "Simulation"));

        OlderClientGrace noChecks = new OlderClientGrace(40, Set.of());
        noChecks.start(PLAYER);
        assertFalse(noChecks.enabled());
        assertFalse(noChecks.suppresses(PLAYER, "Simulation"));
    }
}
