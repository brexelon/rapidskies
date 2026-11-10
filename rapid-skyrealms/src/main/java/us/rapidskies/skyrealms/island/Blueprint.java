package us.rapidskies.skyrealms.island;

import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.Material;

/**
 * Blocks relative to an origin, built in code (starter islands, Expedition islands). Pure data,
 * so layouts are unit-tested without a server; {@link Block#data()} is what's handed to
 * {@code Bukkit.createBlockData} when it's placed. A later put at the same spot replaces the
 * earlier one.
 */
public final class Blueprint {

    /** One block: its position, material and any block state ({@code [axis=x]}, or empty). */
    public record Block(int x, int y, int z, Material material, String state) {
        public String data() {
            return material.getKey().asString() + state;
        }
    }

    private final Map<Long, Block> blocks = new LinkedHashMap<>();

    static long key(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }

    public Blueprint put(int x, int y, int z, Material material) {
        return put(x, y, z, material, "");
    }

    public Blueprint put(int x, int y, int z, Material material, String state) {
        if (material == Material.AIR) {
            blocks.remove(key(x, y, z));
        } else {
            blocks.put(key(x, y, z), new Block(x, y, z, material, state));
        }
        return this;
    }

    public Block get(int x, int y, int z) {
        return blocks.get(key(x, y, z));
    }

    public Material material(int x, int y, int z) {
        Block b = get(x, y, z);
        return b == null ? Material.AIR : b.material();
    }

    public boolean isAir(int x, int y, int z) {
        return get(x, y, z) == null;
    }

    public Collection<Block> blocks() {
        return blocks.values();
    }

    public int size() {
        return blocks.size();
    }

    /** How many of each material (SuperiorSkyblock2 leaves a schematic's own blocks out of island worth). */
    public Map<Material, Integer> counts() {
        Map<Material, Integer> out = new EnumMap<>(Material.class);
        for (Block b : blocks.values()) {
            out.merge(b.material(), 1, Integer::sum);
        }
        return out;
    }

    /** Whether any of the six neighbours is air (a player can reach the block's face). */
    public boolean exposed(int x, int y, int z) {
        return isAir(x + 1, y, z) || isAir(x - 1, y, z) || isAir(x, y + 1, z) || isAir(x, y - 1, z)
                || isAir(x, y, z + 1) || isAir(x, y, z - 1);
    }

    /** The highest block in a column, or Integer.MIN_VALUE if it's empty. */
    public int top(int x, int z) {
        int best = Integer.MIN_VALUE;
        for (Block b : blocks.values()) {
            if (b.x() == x && b.z() == z && b.y() > best) {
                best = b.y();
            }
        }
        return best;
    }

    /** Smallest and largest coordinate on each axis: {minX, minY, minZ, maxX, maxY, maxZ}. */
    public int[] bounds() {
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (Block k : blocks.values()) {
            b[0] = Math.min(b[0], k.x());
            b[1] = Math.min(b[1], k.y());
            b[2] = Math.min(b[2], k.z());
            b[3] = Math.max(b[3], k.x());
            b[4] = Math.max(b[4], k.y());
            b[5] = Math.max(b[5], k.z());
        }
        return b;
    }

    // ------------------------------------------------------------------ shapes shared by layouts

    /** A filled disc of {@code m} at height {@code y}, radius {@code r} (x² + z² ≤ r²). */
    public void disc(int cx, int y, int cz, double r, Material m) {
        int ir = (int) Math.ceil(r);
        for (int x = -ir; x <= ir; x++) {
            for (int z = -ir; z <= ir; z++) {
                if (x * x + z * z <= r * r) {
                    put(cx + x, y, cz + z, m);
                }
            }
        }
    }

    /** A straight tree: a trunk of {@code height} logs with a rounded crown of persistent leaves. */
    public void tree(int x, int y, int z, int height, Material log, Material leaves, int crown) {
        for (int dy = height - 2; dy <= height + 1; dy++) {
            int r = dy >= height ? crown - 1 : crown;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    // Rounded: no corners on any layer.
                    if (r > 0 && Math.abs(dx) == r && Math.abs(dz) == r) {
                        continue;
                    }
                    put(x + dx, y + dy, z + dz, leaves, "[persistent=true]");
                }
            }
        }
        for (int dy = 0; dy < height; dy++) {
            put(x, y + dy, z, log, "[axis=y]");
        }
    }
}
