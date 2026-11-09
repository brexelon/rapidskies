package us.rapidskies.hub.parkour;

import java.util.Optional;

/**
 * A parkour course from the config: the rules every run's layout is generated from. Each
 * player gets their own layout ({@link CourseGenerator}), so a course is a distance and a
 * pace of checkpoints rather than fixed blocks.
 *
 * @param length          blocks from the start platform to the finish platform (the end distance)
 * @param checkpointEvery a checkpoint platform after each this many blocks of distance
 * @param entrance        optional block in the hub world (where a pressure plate is) that starts a run
 */
public record Course(String id, int length, int checkpointEvery, Optional<BlockPos> entrance) {

    public static final int MIN_LENGTH = 30;
    public static final int MAX_LENGTH = 1000;
    public static final int MIN_CHECKPOINT_EVERY = 15;

    /** Checkpoints every layout of this course has: one per {@code checkpointEvery} before the finish. */
    public int checkpoints() {
        return (length - 1) / checkpointEvery;
    }
}
