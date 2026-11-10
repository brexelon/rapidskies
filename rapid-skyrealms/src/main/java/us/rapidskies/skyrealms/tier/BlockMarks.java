package us.rapidskies.skyrealms.tier;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.block.Block;

/**
 * Remembered block positions: which blocks the island generator made (so mining them counts)
 * and which plants players placed (so breaking them doesn't count as a harvest). In memory and
 * bounded: after a restart the first break of an old block simply doesn't count, which only
 * ever errs against farming the goals.
 */
final class BlockMarks {

    private record Key(UUID world, long pos) {
    }

    private final Set<Key> marks = ConcurrentHashMap.newKeySet();
    private final int limit;

    BlockMarks(int limit) {
        this.limit = limit;
    }

    static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    void mark(Block b) {
        if (marks.size() >= limit) {
            marks.clear(); // a runaway farm; forgetting errs against counting
        }
        marks.add(new Key(b.getWorld().getUID(), pack(b.getX(), b.getY(), b.getZ())));
    }

    /** Whether the block was marked; the mark is removed either way. */
    boolean take(Block b) {
        return marks.remove(new Key(b.getWorld().getUID(), pack(b.getX(), b.getY(), b.getZ())));
    }

    boolean has(Block b) {
        return marks.contains(new Key(b.getWorld().getUID(), pack(b.getX(), b.getY(), b.getZ())));
    }
}
