package us.rapidskies.proxy.limbo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AfkTimersTest {

    private static final long MINUTE = 60_000;
    private static final long NOW = 100 * MINUTE;
    private static final long LIMIT = 15 * MINUTE;

    private final AfkTimers timers = new AfkTimers();
    private final UUID player = UUID.randomUUID();

    @Test
    void dueOnceIdleLongEnoughCountingFromLastInput() {
        // rapid-core reports AFK after 5 idle minutes; the limit counts from the last input.
        timers.report(player, "smp-1", true, NOW - 5 * MINUTE, Optional.of("smp-1"), NOW);
        assertTrue(timers.due(NOW + 9 * MINUTE, LIMIT).isEmpty());
        assertEquals(Map.of(player, "smp-1"), timers.due(NOW + 10 * MINUTE, LIMIT));
    }

    @Test
    void comingBackClearsIt() {
        timers.report(player, "smp-1", true, NOW, Optional.of("smp-1"), NOW);
        timers.report(player, "smp-1", false, 0, Optional.of("smp-1"), NOW + MINUTE);
        assertTrue(timers.due(NOW + LIMIT, LIMIT).isEmpty());
    }

    @Test
    void lateReportFromAServerTheyLeftIsIgnored() {
        timers.report(player, "hub-1", true, NOW, Optional.of("smp-1"), NOW);
        timers.report(player, "hub-1", true, NOW, Optional.empty(), NOW);
        assertTrue(timers.due(NOW + LIMIT, LIMIT).isEmpty());
    }

    @Test
    void cameBackReportFromAnotherServerDoesNotClearTheCurrentOne() {
        timers.report(player, "smp-1", true, NOW, Optional.of("smp-1"), NOW);
        timers.report(player, "hub-1", false, 0, Optional.of("smp-1"), NOW);
        assertEquals(Map.of(player, "smp-1"), timers.due(NOW + LIMIT, LIMIT));
    }

    @Test
    void backendClockAheadDoesNotDelayIt() {
        timers.report(player, "smp-1", true, NOW + 10 * MINUTE, Optional.of("smp-1"), NOW);
        assertEquals(Map.of(player, "smp-1"), timers.due(NOW + LIMIT, LIMIT));
    }

    @Test
    void snoozedUntilTheGivenTime() {
        timers.report(player, "smp-1", true, NOW, Optional.of("smp-1"), NOW);
        timers.snooze(player, NOW + LIMIT + MINUTE);
        assertTrue(timers.due(NOW + LIMIT, LIMIT).isEmpty());
        assertEquals(Map.of(player, "smp-1"), timers.due(NOW + LIMIT + MINUTE, LIMIT));
    }

    @Test
    void switchingServersClearsIt() {
        timers.report(player, "smp-1", true, NOW, Optional.of("smp-1"), NOW);
        timers.clear(player);
        assertTrue(timers.due(NOW + LIMIT, LIMIT).isEmpty());
    }
}
