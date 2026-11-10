package us.rapidskies.claims.town;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import us.rapidskies.claims.model.ChunkKey;
import us.rapidskies.claims.model.TagColor;
import us.rapidskies.claims.model.Town;
import us.rapidskies.claims.model.TownRole;

/** Upkeep, ruins and release (PLAN.md §4.1): 14 days behind becomes ruins, 7 more and it's gone. */
class UpkeepRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    private static final int GRACE = 14;
    private static final int RUINS = 7;

    private static Town town(Instant paidUntil, Town.State state, Optional<Instant> ruinsSince) {
        UUID mayor = UUID.randomUUID();
        return new Town(UUID.randomUUID(), "Oakvale", "OAK", TagColor.GREEN, mayor,
                new ChunkKey("minecraft:overworld", 0, 0), Optional.empty(), paidUntil, state, ruinsSince,
                Map.of(mayor, TownRole.MAYOR), Town.defaultRoles(), Set.of());
    }

    private static Instant daysAgo(int days) {
        return NOW.minus(Duration.ofDays(days));
    }

    @Test
    void dueOnceThePaidDayIsOver() {
        assertFalse(UpkeepRules.due(town(NOW.plusSeconds(1), Town.State.ACTIVE, Optional.empty()), NOW));
        assertTrue(UpkeepRules.due(town(NOW, Town.State.ACTIVE, Optional.empty()), NOW));
    }

    @Test
    void payingMovesTheDateADayAtATime() {
        Town behind = town(daysAgo(3), Town.State.ACTIVE, Optional.empty());
        assertEquals(daysAgo(2), UpkeepRules.paid(behind, NOW).upkeepPaidUntil(), "each missed day is paid");
    }

    @Test
    void shortBanksFallIntoRuinsAfterTheGracePeriod() {
        Town t = town(daysAgo(GRACE - 1), Town.State.ACTIVE, Optional.empty());
        assertEquals(Optional.of(t), UpkeepRules.unpaid(t, NOW, GRACE, RUINS), "still within grace");
        Town late = town(daysAgo(GRACE), Town.State.ACTIVE, Optional.empty());
        Town ruined = UpkeepRules.unpaid(late, NOW, GRACE, RUINS).orElseThrow();
        assertTrue(ruined.ruined());
        assertEquals(Optional.of(NOW), ruined.ruinsSince());
    }

    @Test
    void ruinsAreReleasedAfterTheirOwnPeriod() {
        Town fresh = town(daysAgo(20), Town.State.RUINS, Optional.of(daysAgo(RUINS - 1)));
        assertEquals(Optional.of(fresh), UpkeepRules.unpaid(fresh, NOW, GRACE, RUINS), "still protected");
        Town old = town(daysAgo(21), Town.State.RUINS, Optional.of(daysAgo(RUINS)));
        assertTrue(UpkeepRules.unpaid(old, NOW, GRACE, RUINS).isEmpty(), "released");
    }

    @Test
    void payingRuinsRestoresTheTownAndForgivesTheBacklog() {
        Town ruins = town(daysAgo(18), Town.State.RUINS, Optional.of(daysAgo(4)));
        Town restored = UpkeepRules.paid(ruins, NOW);
        assertFalse(restored.ruined());
        assertTrue(restored.ruinsSince().isEmpty());
        assertEquals(NOW.plus(Duration.ofDays(1)), restored.upkeepPaidUntil());
        assertFalse(UpkeepRules.due(restored, NOW));
    }

    @Test
    void personalRuins() {
        assertFalse(UpkeepRules.personalRuinsExpired(daysAgo(6), NOW, RUINS));
        assertTrue(UpkeepRules.personalRuinsExpired(daysAgo(7), NOW, RUINS));
    }
}
