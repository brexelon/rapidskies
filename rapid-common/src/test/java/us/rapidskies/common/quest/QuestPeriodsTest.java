package us.rapidskies.common.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import us.rapidskies.common.reward.LoginStreaks;
import us.rapidskies.common.store.StoreTransactions;

class QuestPeriodsTest {

    private static final Instant WEDNESDAY = Instant.parse("2026-11-11T09:30:00Z");

    @Test
    void periodKeys() {
        assertEquals("2026-11-11", QuestPeriods.day(WEDNESDAY));
        assertEquals("2026-W46", QuestPeriods.week(WEDNESDAY));
        assertTrue(QuestPeriods.isWeek("2026-W46"));
        assertFalse(QuestPeriods.isWeek("2026-11-11"));
        // ISO weeks: 2027-01-01 is a Friday in 2026-W53.
        assertEquals("2026-W53", QuestPeriods.week(Instant.parse("2027-01-01T00:00:00Z")));
        assertEquals(Instant.parse("2026-11-16T00:00:00Z"), QuestPeriods.weekEnds(WEDNESDAY));
        assertEquals(Instant.parse("2026-11-14T00:00:00Z"), QuestPeriods.dayCloses("2026-11-11", 3));
    }

    @Test
    void dailiesBankForThreeDaysButNotBeforeTheFirstJoin() {
        assertEquals(List.of("2026-11-11", "2026-11-10", "2026-11-09"),
                QuestPeriods.openDays(WEDNESDAY, 3, LocalDate.parse("2026-01-01")));
        assertEquals(List.of("2026-11-11", "2026-11-10"),
                QuestPeriods.openDays(WEDNESDAY, 3, LocalDate.parse("2026-11-10")));
        assertEquals(List.of("2026-11-11"), QuestPeriods.openDays(WEDNESDAY, 3, LocalDate.parse("2026-11-11")));
    }

    @Test
    void picksAreStableAndAvoidTaken() {
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000000042");
        List<String> pool = List.of("a", "b", "c", "d");
        Optional<String> first = QuestPeriods.pick(pool, Set.of(), player, "2026-11-11", 0);
        assertEquals(first, QuestPeriods.pick(List.of("d", "c", "b", "a"), Set.of(), player, "2026-11-11", 0));
        String taken = first.orElseThrow();
        assertFalse(QuestPeriods.pick(pool, Set.of(taken), player, "2026-11-11", 0).orElseThrow().equals(taken));
        assertTrue(QuestPeriods.pick(pool, Set.of("a", "b", "c", "d"), player, "2026-11-11", 0).isEmpty());
    }

    @Test
    void loginStreakCountsConsecutiveDays() {
        LocalDate today = LocalDate.parse("2026-11-11");
        assertEquals(1, LoginStreaks.next(0, null, today));
        assertEquals(5, LoginStreaks.next(4, today.minusDays(1), today));
        assertEquals(4, LoginStreaks.next(4, today, today));
        assertEquals(1, LoginStreaks.next(9, today.minusDays(2), today));
    }

    @Test
    void storeTransactionBase() {
        assertEquals("tbx-1a2b", StoreTransactions.base("tbx-1a2b:bolts"));
        assertEquals("tbx-1a2b", StoreTransactions.base("tbx-1a2b"));
    }
}
