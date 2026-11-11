package us.rapidskies.common.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import us.rapidskies.common.quest.QuestRepository.Assigned;
import us.rapidskies.common.quest.QuestRepository.Flushed;
import us.rapidskies.common.quest.QuestRepository.Step;
import us.rapidskies.common.testing.TestServices;

@EnabledIf("us.rapidskies.common.testing.TestServices#dockerAvailable")
class QuestRepositoryIT {

    private QuestRepository quests;
    private UUID steve;

    @BeforeEach
    void setUp() throws Exception {
        TestServices.truncate("quests_progress", "player_stats", "achievements");
        quests = new QuestRepository(TestServices.database());
        steve = UUID.randomUUID();
    }

    @Test
    void assigningTwiceKeepsTheFirstPick() throws Exception {
        quests.assign(steve, "2026-11-11", "daily", List.of(new Assigned(0, "fish-8", 8)));
        quests.assign(steve, "2026-11-11", "daily", List.of(new Assigned(0, "play-30", 30), new Assigned(1, "x", 3)));
        List<QuestRepository.Row> rows = quests.quests(steve, List.of("2026-11-11"));
        assertEquals(2, rows.size());
        assertEquals("fish-8", rows.get(0).quest());
        assertEquals("x", rows.get(1).quest());
    }

    @Test
    void progressCapsAndCompletesOnce() throws Exception {
        quests.assign(steve, "2026-W46", "weekly", List.of(new Assigned(0, "s-expedition-2", 2)));
        Flushed first = quests.flush(steve, List.of(new Step("2026-W46", 0, 1)), Map.of("expedition", 1L));
        assertFalse(first.quests().getFirst().justCompleted());
        assertEquals(1L, first.stats().get("expedition"));
        Flushed second = quests.flush(steve, List.of(new Step("2026-W46", 0, 5)), Map.of("expedition", 5L));
        assertTrue(second.quests().getFirst().justCompleted());
        assertEquals(2, second.quests().getFirst().progress());
        assertEquals(6L, second.stats().get("expedition"));
        Flushed third = quests.flush(steve, List.of(new Step("2026-W46", 0, 1)), Map.of());
        assertTrue(third.quests().isEmpty(), "a finished quest doesn't move");
        assertTrue(quests.quests(steve, List.of("2026-W46")).getFirst().completed());
    }

    @Test
    void twoServersFinishingTogetherPayOnce() throws Exception {
        quests.assign(steve, "2026-11-11", "daily", List.of(new Assigned(0, "fish-8", 8)));
        quests.flush(steve, List.of(new Step("2026-11-11", 0, 7)), Map.of());
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Flushed>> tasks = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                tasks.add(() -> quests.flush(steve, List.of(new Step("2026-11-11", 0, 1)), Map.of("catch-fish", 1L)));
            }
            int completions = 0;
            for (Future<Flushed> f : pool.invokeAll(tasks)) {
                completions += (int) f.get().quests().stream().filter(QuestRepository.Advanced::justCompleted).count();
            }
            assertEquals(1, completions);
            assertEquals(6L, quests.stats(steve).get("catch-fish"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void achievementsCompleteOnce() throws Exception {
        assertTrue(quests.completeAchievement(steve, "angler"));
        assertFalse(quests.completeAchievement(steve, "angler"));
        assertTrue(quests.achievements(steve).containsKey("angler"));
    }
}
