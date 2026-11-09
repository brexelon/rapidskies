package us.rapidskies.hub.parkour;

import us.rapidskies.hub.parkour.Layout.Trigger;

/**
 * One player's run through their layout: which checkpoint is next and when the clock
 * started. The clock starts when they jump off the start platform, not when they arrive.
 * Pure state, so the rules (checkpoints in order, no finishing early) are unit-tested; the
 * service does the blocks, teleports, messages and saving.
 */
public final class Run {

    /** What stepping onto a platform meant for this run. */
    public enum Step {
        /** Nothing: an earlier checkpoint again. */
        NONE,
        /** The next checkpoint. */
        CHECKPOINT,
        /** A later checkpoint or the finish, with checkpoints skipped. */
        MISSED,
        /** The finish, every checkpoint touched. */
        FINISHED
    }

    private final Course course;
    private final Layout layout;
    private long startedNanos;
    private boolean started;
    private int reached;

    public Run(Course course, Layout layout) {
        this.course = course;
        this.layout = layout;
    }

    public Course course() {
        return course;
    }

    public Layout layout() {
        return layout;
    }

    /** Starts the clock (the player left the start platform). */
    public void start(long nowNanos) {
        startedNanos = nowNanos;
        started = true;
    }

    public boolean started() {
        return started;
    }

    /** Back on the start platform: the clock waits again and checkpoints count afresh. */
    public void reset() {
        started = false;
        reached = 0;
    }

    /** Checkpoints touched so far. */
    public int reached() {
        return reached;
    }

    /** The 0-based index of the checkpoint to touch next (equal to the count when all are done). */
    public int next() {
        return reached;
    }

    /** Time on the clock; 0 until it starts. */
    public long elapsedMillis(long nowNanos) {
        return started ? (nowNanos - startedNanos) / 1_000_000L : 0;
    }

    /** A step onto a platform of this run's layout. */
    public Step step(Trigger trigger) {
        return switch (trigger.kind()) {
            case CHECKPOINT -> {
                if (trigger.index() < reached) {
                    yield Step.NONE;
                }
                if (trigger.index() > reached) {
                    yield Step.MISSED;
                }
                reached++;
                yield Step.CHECKPOINT;
            }
            case FINISH -> reached == layout.checkpoints().size() ? Step.FINISHED : Step.MISSED;
        };
    }
}
