package us.rapidskies.hub.parkour;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import us.rapidskies.hub.parkour.Layout.Kind;
import us.rapidskies.hub.parkour.Layout.Trigger;
import us.rapidskies.hub.parkour.Run.Step;

class RunTest {

    private static final Course COURSE = new Course("main", 120, 40, Optional.empty());
    private static final Layout LAYOUT = CourseGenerator.generate(COURSE, 7L);

    private static Trigger checkpoint(int index) {
        return new Trigger(Kind.CHECKPOINT, index);
    }

    private static final Trigger FINISH = new Trigger(Kind.FINISH, 0);

    @Test
    void checkpointsInOrderThenFinish() {
        Run run = new Run(COURSE, LAYOUT);
        assertEquals(2, LAYOUT.checkpoints().size());
        assertEquals(Step.CHECKPOINT, run.step(checkpoint(0)));
        assertEquals(Step.CHECKPOINT, run.step(checkpoint(1)));
        assertEquals(2, run.reached());
        assertEquals(Step.FINISHED, run.step(FINISH));
    }

    @Test
    void skippingACheckpointBlocksTheFinish() {
        Run run = new Run(COURSE, LAYOUT);
        assertEquals(Step.MISSED, run.step(checkpoint(1)));
        assertEquals(Step.MISSED, run.step(FINISH));
        assertEquals(0, run.reached());
        // Going back for it fixes the run.
        assertEquals(Step.CHECKPOINT, run.step(checkpoint(0)));
        assertEquals(Step.CHECKPOINT, run.step(checkpoint(1)));
        assertEquals(Step.FINISHED, run.step(FINISH));
    }

    @Test
    void revisitingACheckpointChangesNothing() {
        Run run = new Run(COURSE, LAYOUT);
        run.step(checkpoint(0));
        assertEquals(Step.NONE, run.step(checkpoint(0)));
        assertEquals(1, run.reached());
    }

    @Test
    void aCourseWithoutCheckpointsFinishesStraightAway() {
        Course sprint = new Course("sprint", 40, 50, Optional.empty());
        Layout layout = CourseGenerator.generate(sprint, 1L);
        assertEquals(0, layout.checkpoints().size());
        assertEquals(Step.FINISHED, new Run(sprint, layout).step(FINISH));
    }

    @Test
    void theClockWaitsUntilTheStartPlatformIsLeft() {
        Run run = new Run(COURSE, LAYOUT);
        assertFalse(run.started());
        assertEquals(0, run.elapsedMillis(5_000_000_000L));
        run.start(1_000_000L);
        assertTrue(run.started());
        assertEquals(42_317, run.elapsedMillis(1_000_000L + 42_317_000_000L));
    }

    @Test
    void restartingResetsTheClockAndCheckpoints() {
        Run run = new Run(COURSE, LAYOUT);
        run.start(0);
        run.step(checkpoint(0));
        run.reset();
        assertFalse(run.started());
        assertEquals(0, run.reached());
        assertEquals(Step.MISSED, run.step(FINISH));
    }
}
