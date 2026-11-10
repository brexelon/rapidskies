package us.rapidskies.horizons.trade;

import java.util.Arrays;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Remembers which rewarded blocks (ores, melons, pumpkins) a player placed, so mining or
 * harvesting them again pays nothing: placing a stack of iron ore and breaking it over and
 * over must not be income.
 *
 * <p>Kept in each chunk's persistent data as a sorted int array of packed in-chunk
 * positions, so it's saved with the world, survives restarts and needs no table. Only
 * rewarded materials are marked, so the arrays stay small. Blocks moved by pistons lose
 * their mark (a known, minor gap). Main thread only.
 */
public final class PlacedBlocks {

    private final NamespacedKey key;

    public PlacedBlocks(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "placed");
    }

    /** {@code y} in [-2048, 2047], x and z within the chunk. */
    static int pack(int x, int y, int z) {
        return ((y + 2048) << 8) | ((x & 15) << 4) | (z & 15);
    }

    public void mark(Block block) {
        PersistentDataContainer pdc = block.getChunk().getPersistentDataContainer();
        int[] current = pdc.getOrDefault(key, PersistentDataType.INTEGER_ARRAY, new int[0]);
        int[] next = withAdded(current, pack(block.getX(), block.getY(), block.getZ()));
        if (next != current) {
            pdc.set(key, PersistentDataType.INTEGER_ARRAY, next);
        }
    }

    /** Whether {@code block} was marked; the mark is removed either way (the block is going). */
    public boolean take(Block block) {
        Chunk chunk = block.getChunk();
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        int[] current = pdc.get(key, PersistentDataType.INTEGER_ARRAY);
        if (current == null) {
            return false;
        }
        int[] next = withRemoved(current, pack(block.getX(), block.getY(), block.getZ()));
        if (next == current) {
            return false;
        }
        if (next.length == 0) {
            pdc.remove(key);
        } else {
            pdc.set(key, PersistentDataType.INTEGER_ARRAY, next);
        }
        return true;
    }

    /** {@code sorted} with {@code value} inserted, or {@code sorted} itself if it's already there. */
    static int[] withAdded(int[] sorted, int value) {
        int i = Arrays.binarySearch(sorted, value);
        if (i >= 0) {
            return sorted;
        }
        int at = -i - 1;
        int[] out = new int[sorted.length + 1];
        System.arraycopy(sorted, 0, out, 0, at);
        out[at] = value;
        System.arraycopy(sorted, at, out, at + 1, sorted.length - at);
        return out;
    }

    /** {@code sorted} without {@code value}, or {@code sorted} itself if it wasn't there. */
    static int[] withRemoved(int[] sorted, int value) {
        int i = Arrays.binarySearch(sorted, value);
        if (i < 0) {
            return sorted;
        }
        int[] out = new int[sorted.length - 1];
        System.arraycopy(sorted, 0, out, 0, i);
        System.arraycopy(sorted, i + 1, out, i, sorted.length - i - 1);
        return out;
    }
}
