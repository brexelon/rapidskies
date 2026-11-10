package us.rapidskies.claims.model;

import java.util.Objects;

/**
 * A chunk in a world, the unit of every claim (PLAN.md §4.1). {@code world} is the world's
 * key ({@code minecraft:overworld} for the Heartland), so it survives folder renames.
 */
public record ChunkKey(String world, int x, int z) {

    public ChunkKey {
        Objects.requireNonNull(world, "world");
    }

    /** The chunk holding block {@code blockX, blockZ}. */
    public static ChunkKey ofBlock(String world, int blockX, int blockZ) {
        return new ChunkKey(world, blockX >> 4, blockZ >> 4);
    }

    public ChunkKey offset(int dx, int dz) {
        return new ChunkKey(world, x + dx, z + dz);
    }

    /** The four chunks sharing an edge with this one. */
    public ChunkKey[] neighbours() {
        return new ChunkKey[] {offset(1, 0), offset(-1, 0), offset(0, 1), offset(0, -1)};
    }

    /** Block coordinates of the chunk's centre, for teleports and messages. */
    public int centerBlockX() {
        return (x << 4) + 8;
    }

    public int centerBlockZ() {
        return (z << 4) + 8;
    }

    /** {@code world;x;z}, the form used in Redis messages. */
    public String format() {
        return world + ";" + x + ";" + z;
    }

    public static ChunkKey parse(String s) {
        String[] parts = s.split(";");
        if (parts.length != 3) {
            throw new IllegalArgumentException("expected world;x;z, got '" + s + "'");
        }
        return new ChunkKey(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }
}
