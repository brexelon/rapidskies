package us.rapidskies.horizons.pvp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import us.rapidskies.horizons.pvp.CombatLogRules.Leave;

class CombatLogRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    private static final Instant TAGGED = NOW.plusSeconds(10);

    @Test
    void leavingMidFightIsPunished() {
        assertTrue(CombatLogRules.punish(true, false, Leave.DISCONNECTED, NOW, TAGGED, false, false));
        assertTrue(CombatLogRules.punish(true, false, Leave.TIMED_OUT, NOW, TAGGED, false, false),
                "pulling the cable is no way out");
    }

    @Test
    void nobodyOutOfCombatIsPunished() {
        assertFalse(CombatLogRules.punish(true, false, Leave.DISCONNECTED, NOW, null, false, false));
        assertFalse(CombatLogRules.punish(true, false, Leave.DISCONNECTED, NOW, NOW, false, false), "tag just ran out");
        assertFalse(CombatLogRules.punish(true, false, Leave.DISCONNECTED, NOW, NOW.minusSeconds(1), false, false));
    }

    @Test
    void restartsBypassAndTheSwitchSpareEveryone() {
        assertFalse(CombatLogRules.punish(true, true, Leave.KICKED, NOW, TAGGED, false, true), "server stopping");
        assertFalse(CombatLogRules.punish(true, false, Leave.DISCONNECTED, NOW, TAGGED, true, false), "bypass");
        assertFalse(CombatLogRules.punish(false, false, Leave.DISCONNECTED, NOW, TAGGED, false, false), "turned off");
    }

    @Test
    void kicksCountOnlyWhenConfigured() {
        assertFalse(CombatLogRules.punish(true, false, Leave.KICKED, NOW, TAGGED, false, false));
        assertTrue(CombatLogRules.punish(true, false, Leave.KICKED, NOW, TAGGED, false, true));
        assertFalse(CombatLogRules.punish(true, false, Leave.ERRONEOUS_STATE, NOW, TAGGED, false, true),
                "a server-side error is never the player's fault");
    }

    @Test
    void countdownRoundsUp() {
        assertEquals(10, CombatLogRules.secondsLeft(NOW, TAGGED));
        assertEquals(1, CombatLogRules.secondsLeft(NOW, NOW.plusMillis(200)));
        assertEquals(30, CombatLogRules.secondsLeft(NOW, NOW.plusMillis(29_001)));
        assertEquals(0, CombatLogRules.secondsLeft(NOW, NOW));
        assertEquals(0, CombatLogRules.secondsLeft(NOW, null));
    }

    @Test
    void commandsAreFilledIn() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertEquals("eco take Steve 100", CombatLogRules.command("eco take <player> 100", "Steve", id, "Alex"));
        assertEquals("note 00000000-0000-0000-0000-000000000001 fled from Alex",
                CombatLogRules.command("note <uuid> fled from <attacker>", "Steve", id, "Alex"));
        assertEquals("say Steve fled from nobody", CombatLogRules.command("say <player> fled from <attacker>", "Steve", id,
                null));
    }
}
