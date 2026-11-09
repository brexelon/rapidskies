package us.rapidskies.hub.parkour;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One generated course, in coordinates relative to its lane's origin: it runs along +x from
 * the start platform (centred on 0, 0, 0) to the finish platform, at least {@code length}
 * blocks further on.
 *
 * @param blocks      every block to place (platforms, jumps and the plates on top)
 * @param start       the start platform
 * @param checkpoints the checkpoint platforms, in order
 * @param finish      the finish platform
 * @param floors      per stretch (0 = start to checkpoint 1, ..., the last one ends at the
 *                    finish): its lowest block. Falling well below it means a missed jump.
 */
public record Layout(int length, List<Block> blocks, Pad start, List<Pad> checkpoints, Pad finish,
                     List<Integer> floors) {

    /** What a block is for; the service picks the material. */
    public enum Piece {
        START, JUMP, CHECKPOINT, FINISH, PLATE
    }

    /** A block of the course; {@code stretch} is the stretch it belongs to (for its color). */
    public record Block(BlockPos pos, Piece piece, int stretch) {
    }

    /** A 3x3 platform: {@code centre} is its middle block, {@code area} all nine. */
    public record Pad(BlockPos centre, List<BlockPos> area) {

        public Pad {
            area = List.copyOf(area);
        }

        /** Whether {@code feet} are over this platform, at any height (jumping on it counts). */
        public boolean over(BlockPos feet) {
            return Math.abs(feet.x() - centre.x()) <= 1 && Math.abs(feet.z() - centre.z()) <= 1;
        }
    }

    /** What stepping onto a platform means. */
    public enum Kind {
        CHECKPOINT, FINISH
    }

    /** A platform reached; {@code index} is the checkpoint's 0-based number. */
    public record Trigger(Kind kind, int index) {
    }

    public Layout {
        blocks = List.copyOf(blocks);
        checkpoints = List.copyOf(checkpoints);
        floors = List.copyOf(floors);
    }

    /** Feet positions (one above each platform block) to what standing there means. */
    public Map<BlockPos, Trigger> triggers() {
        Map<BlockPos, Trigger> out = new HashMap<>();
        for (int i = 0; i < checkpoints.size(); i++) {
            for (BlockPos p : checkpoints.get(i).area()) {
                out.put(above(p), new Trigger(Kind.CHECKPOINT, i));
            }
        }
        for (BlockPos p : finish.area()) {
            out.put(above(p), new Trigger(Kind.FINISH, 0));
        }
        return out;
    }

    /** The lowest block of the stretch a player is on after reaching {@code reached} checkpoints. */
    public int floor(int reached) {
        return floors.get(Math.min(reached, floors.size() - 1));
    }

    private static BlockPos above(BlockPos p) {
        return new BlockPos(p.x(), p.y() + 1, p.z());
    }
}
