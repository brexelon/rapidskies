package us.rapidskies.hub.parkour;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import us.rapidskies.hub.parkour.Layout.Block;
import us.rapidskies.hub.parkour.Layout.Kind;
import us.rapidskies.hub.parkour.Layout.Pad;
import us.rapidskies.hub.parkour.Layout.Piece;
import us.rapidskies.hub.parkour.Layout.Trigger;

/** Generated layouts are always playable, the right length and inside their lane. */
class CourseGeneratorTest {

    private static final int SEEDS = 500;
    private static final List<Course> COURSES = List.of(
            new Course("main", 150, 50, Optional.empty()),
            new Course("short", 30, 15, Optional.empty()),
            new Course("long", 1000, 40, Optional.empty()),
            // A checkpoint threshold right at the end: it must still come before the finish.
            new Course("tight", 161, 40, Optional.empty()));

    @Test
    void sameSeedSameLayout() {
        Course c = COURSES.getFirst();
        assertEquals(CourseGenerator.generate(c, 42L), CourseGenerator.generate(c, 42L));
    }

    @Test
    void differentSeedsDifferentLayouts() {
        Course c = COURSES.getFirst();
        Set<List<Block>> seen = new HashSet<>();
        for (long seed = 0; seed < 20; seed++) {
            seen.add(CourseGenerator.generate(c, seed).blocks());
        }
        assertTrue(seen.size() > 15, "layouts should vary, got " + seen.size() + " distinct of 20");
    }

    @Test
    void everyLayoutHasTheCoursesCheckpointsAndLength() {
        for (Course c : COURSES) {
            for (long seed = 0; seed < SEEDS; seed++) {
                Layout l = CourseGenerator.generate(c, seed);
                assertEquals(c.checkpoints(), l.checkpoints().size(), c.id() + " seed " + seed);
                assertEquals(c.checkpoints() + 1, l.floors().size());
                int finishFar = l.finish().centre().x() + 1;
                assertTrue(finishFar >= c.length(), c.id() + " seed " + seed + ": finish ends at " + finishFar);
                assertTrue(finishFar <= c.length() + 12, c.id() + " seed " + seed + ": finish overshoots to " + finishFar);
                for (int i = 0; i < l.checkpoints().size(); i++) {
                    int x = l.checkpoints().get(i).centre().x();
                    int threshold = (i + 1) * c.checkpointEvery();
                    assertTrue(x >= threshold - 2 && x <= threshold + 6,
                            c.id() + " seed " + seed + ": checkpoint " + (i + 1) + " at x " + x);
                }
            }
        }
    }

    @Test
    void everyJumpIsMakeable() {
        for (Course c : COURSES) {
            for (long seed = 0; seed < SEEDS; seed++) {
                Layout l = CourseGenerator.generate(c, seed);
                List<BlockPos> path = path(l);
                for (int i = 1; i < path.size(); i++) {
                    BlockPos a = path.get(i - 1);
                    BlockPos b = path.get(i);
                    int dx = b.x() - a.x();
                    int dz = b.z() - a.z();
                    int dy = b.y() - a.y();
                    double d = Math.sqrt(dx * dx + dz * dz);
                    String where = c.id() + " seed " + seed + ": " + a.format() + " -> " + b.format();
                    assertTrue(dx >= 1, where + " goes backwards");
                    assertTrue(Math.abs(dy) <= 1, where + " changes height by " + dy);
                    assertTrue(d >= CourseGenerator.MIN_JUMP, where + " is a step, not a jump");
                    double max = dy > 0 ? CourseGenerator.MAX_JUMP_UP
                            : dy == 0 ? CourseGenerator.MAX_JUMP_LEVEL : CourseGenerator.MAX_JUMP_DOWN;
                    assertTrue(d <= max, where + " is too far (" + d + ")");
                }
            }
        }
    }

    @Test
    void blocksStayInTheLaneAndNeverOverlap() {
        for (Course c : COURSES) {
            for (long seed = 0; seed < SEEDS; seed++) {
                Layout l = CourseGenerator.generate(c, seed);
                Set<BlockPos> seen = new HashSet<>();
                for (Block b : l.blocks()) {
                    assertTrue(seen.add(b.pos()), c.id() + " seed " + seed + ": two blocks at " + b.pos().format());
                    assertTrue(Math.abs(b.pos().z()) <= CourseGenerator.MAX_SIDEWAYS, "sideways " + b.pos().format());
                    // Plates sit one above a platform.
                    int limit = CourseGenerator.MAX_RISE + 1 + (b.piece() == Piece.PLATE ? 1 : 0);
                    assertTrue(Math.abs(b.pos().y()) <= limit, "height " + b.pos().format());
                    assertTrue(b.pos().x() >= -1 && b.pos().x() <= c.length() + 12, "length " + b.pos().format());
                }
                // Nothing in the two blocks above a jump block, where the player's body goes.
                for (Block b : l.blocks()) {
                    if (b.piece() == Piece.JUMP) {
                        assertTrue(!seen.contains(new BlockPos(b.pos().x(), b.pos().y() + 1, b.pos().z()))
                                && !seen.contains(new BlockPos(b.pos().x(), b.pos().y() + 2, b.pos().z())),
                                "headroom over " + b.pos().format());
                    }
                }
            }
        }
    }

    @Test
    void platformsTriggerFromAnywhereOnThem() {
        Layout l = CourseGenerator.generate(COURSES.getFirst(), 3L);
        Map<BlockPos, Trigger> triggers = l.triggers();
        assertEquals(9 * (l.checkpoints().size() + 1), triggers.size());
        Pad first = l.checkpoints().getFirst();
        for (BlockPos p : first.area()) {
            assertEquals(new Trigger(Kind.CHECKPOINT, 0), triggers.get(new BlockPos(p.x(), p.y() + 1, p.z())));
        }
        BlockPos onFinish = l.finish().centre();
        assertEquals(new Trigger(Kind.FINISH, 0), triggers.get(new BlockPos(onFinish.x(), onFinish.y() + 1, onFinish.z())));
        assertTrue(l.start().over(new BlockPos(1, 3, -1)), "jumping on the start platform is still on it");
        assertTrue(!l.start().over(new BlockPos(2, 1, 0)), "past its edge is off it");
    }

    @Test
    void floorsAreTheLowestBlockOfEachStretch() {
        Layout l = CourseGenerator.generate(COURSES.getFirst(), 11L);
        for (int stretch = 0; stretch < l.floors().size(); stretch++) {
            int s = stretch;
            int lowest = l.blocks().stream().filter(b -> b.stretch() == s && b.piece() != Piece.PLATE)
                    .mapToInt(b -> b.pos().y()).min().orElseThrow();
            assertTrue(l.floor(stretch) <= lowest, "stretch " + stretch);
        }
        assertEquals(l.floors().getLast(), l.floor(99), "past the last checkpoint: the last stretch");
    }

    /** The blocks a player jumps between, in order: start edge, jumps, each platform's near and far edge, finish. */
    private static List<BlockPos> path(Layout l) {
        List<BlockPos> out = new ArrayList<>();
        out.add(new BlockPos(l.start().centre().x() + 1, l.start().centre().y(), l.start().centre().z()));
        List<Pad> pads = new ArrayList<>(l.checkpoints());
        pads.add(l.finish());
        int next = 0;
        for (Block b : l.blocks()) {
            if (b.piece() == Piece.JUMP) {
                out.add(b.pos());
            } else if ((b.piece() == Piece.CHECKPOINT || b.piece() == Piece.FINISH) && next < pads.size()) {
                Pad pad = pads.get(next);
                BlockPos near = new BlockPos(pad.centre().x() - 1, pad.centre().y(), pad.centre().z());
                if (b.pos().equals(near)) {
                    out.add(near);
                    out.add(new BlockPos(pad.centre().x() + 1, pad.centre().y(), pad.centre().z()));
                    next++;
                }
            }
        }
        return out;
    }
}
