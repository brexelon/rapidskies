package us.rapidskies.hub.parkour;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import us.rapidskies.hub.parkour.Layout.Block;
import us.rapidskies.hub.parkour.Layout.Pad;
import us.rapidskies.hub.parkour.Layout.Piece;

/**
 * Builds a random layout for a course (PLAN.md §7, hub parkour): single-block jumps along +x,
 * a 3x3 checkpoint platform every {@code checkpointEvery} blocks and a finish platform once
 * the course has covered its {@code length}.
 *
 * <p>Every jump is one a vanilla player can make on every supported client (1.20+), with a
 * sprint jump at most: a gap of at least one block, at most 3 blocks across on the level,
 * a little less going up a block and a little more going down. Every jump moves forward, so
 * no block ever sits above the path or right next to an earlier one. Height and sideways
 * drift stay within {@link #MAX_RISE} and {@link #MAX_SIDEWAYS} of the start, so a lane's
 * blocks stay inside a known box.
 *
 * <p>The same seed always gives the same layout.
 */
public final class CourseGenerator {

    /** Blocks up or down from the start's height the course may wander. */
    public static final int MAX_RISE = 16;
    /** Blocks left or right of the start's line jumps may wander (platforms stay inside it too). */
    public static final int MAX_SIDEWAYS = 12;

    static final double MIN_JUMP = 2.0;
    static final double MAX_JUMP_UP = 3.2;
    static final double MAX_JUMP_LEVEL = 4.0;
    static final double MAX_JUMP_DOWN = 4.5;

    private CourseGenerator() {
    }

    public static Layout generate(Course course, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        List<Block> blocks = new ArrayList<>();
        List<Pad> checkpoints = new ArrayList<>();
        List<Integer> floors = new ArrayList<>();

        Pad start = pad(new BlockPos(-1, 0, 0), Piece.START, 0, blocks, false);
        BlockPos at = edge(start);
        int stretch = 0;
        int floor = 0;
        int nextCheckpoint = course.checkpointEvery();
        int checkpointsLeft = course.checkpoints();

        while (true) {
            // Every checkpoint comes before the finish, so each layout has the course's count.
            boolean checkpoint = checkpointsLeft > 0 && at.x() + 2 >= nextCheckpoint;
            // Platforms are 3 long, so the finish's far edge reaches the course length.
            boolean last = !checkpoint && checkpointsLeft == 0 && at.x() + 4 >= course.length();
            BlockPos land = jump(random, at, last || checkpoint);
            floor = Math.min(floor, land.y());
            if (last) {
                Pad finish = pad(land, Piece.FINISH, stretch, blocks, true);
                floors.add(floor);
                return new Layout(course.length(), blocks, start, checkpoints, finish, floors);
            }
            if (checkpoint) {
                Pad pad = pad(land, Piece.CHECKPOINT, stretch, blocks, true);
                checkpoints.add(pad);
                floors.add(floor);
                stretch++;
                floor = land.y();
                nextCheckpoint += course.checkpointEvery();
                checkpointsLeft--;
                at = edge(pad);
            } else {
                blocks.add(new Block(land, Piece.JUMP, stretch));
                at = land;
            }
        }
    }

    /**
     * The block to jump to from {@code from}. A platform ({@code toPad}) is 3 wide with its
     * near edge on the landing block, so it lands at least two ahead and one in from the side
     * limit, clear of the block before it.
     */
    static BlockPos jump(SplittableRandom random, BlockPos from, boolean toPad) {
        int dy = rise(random, from.y());
        double max = dy > 0 ? MAX_JUMP_UP : dy == 0 ? MAX_JUMP_LEVEL : MAX_JUMP_DOWN;
        int sideLimit = toPad ? MAX_SIDEWAYS - 1 : MAX_SIDEWAYS;
        List<int[]> options = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        for (int dx = toPad ? 2 : 1; dx <= 4; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                int z = from.z() + dz;
                if (d < MIN_JUMP || d > max || Math.abs(z) > sideLimit) {
                    continue;
                }
                double w = 1.0;
                // The longest jumps are fine now and then, not every time.
                if (d > max - 0.5) {
                    w *= 0.5;
                }
                // Drift back toward the line once off to one side.
                if (dz != 0 && Integer.signum(dz) == -Integer.signum(from.z())) {
                    w *= 1 + Math.abs(from.z()) / 4.0;
                }
                options.add(new int[] {dx, dz});
                weights.add(w);
            }
        }
        int[] pick = options.get(pickWeighted(random, weights));
        return new BlockPos(from.x() + pick[0], from.y() + dy, from.z() + pick[1]);
    }

    /** Up a block, level or down a block, steering back once far from the start's height. */
    private static int rise(SplittableRandom random, int y) {
        int roll = random.nextInt(100);
        if (y >= MAX_RISE) {
            return roll < 50 ? -1 : 0;
        }
        if (y <= -MAX_RISE) {
            return roll < 50 ? 1 : 0;
        }
        return roll < 30 ? 1 : roll < 75 ? 0 : -1;
    }

    private static int pickWeighted(SplittableRandom random, List<Double> weights) {
        double total = weights.stream().mapToDouble(Double::doubleValue).sum();
        double r = random.nextDouble() * total;
        for (int i = 0; i < weights.size(); i++) {
            r -= weights.get(i);
            if (r < 0) {
                return i;
            }
        }
        return weights.size() - 1;
    }

    /** A 3x3 platform whose near (west) middle block is {@code near}; {@code plate} puts one on its centre. */
    private static Pad pad(BlockPos near, Piece piece, int stretch, List<Block> blocks, boolean plate) {
        List<BlockPos> area = new ArrayList<>();
        for (int x = 0; x < 3; x++) {
            for (int z = -1; z <= 1; z++) {
                BlockPos p = new BlockPos(near.x() + x, near.y(), near.z() + z);
                area.add(p);
                blocks.add(new Block(p, piece, stretch));
            }
        }
        BlockPos centre = new BlockPos(near.x() + 1, near.y(), near.z());
        if (plate) {
            blocks.add(new Block(new BlockPos(centre.x(), centre.y() + 1, centre.z()), Piece.PLATE, stretch));
        }
        return new Pad(centre, area);
    }

    /** Where the next jump leaves from: the far (east) middle block of a platform. */
    private static BlockPos edge(Pad pad) {
        return new BlockPos(pad.centre().x() + 1, pad.centre().y(), pad.centre().z());
    }
}
