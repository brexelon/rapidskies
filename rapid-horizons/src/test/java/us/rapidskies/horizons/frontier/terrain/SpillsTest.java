package us.rapidskies.horizons.frontier.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator.ChunkData;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.junit.jupiter.api.Test;
import us.rapidskies.horizons.HorizonsSettings;

/** Liquid the Frontier generators leave where it would run, and what they do about it. */
class SpillsTest {

    private static final long SEED = 8_675_309L;
    private static final int MIN = -64;
    private static final int MAX = 320;

    // ------------------------------------------------------------------ fakes

    /** Blocks by position; anything unset is {@code fill}. */
    private static final class Blocks {
        final Map<Long, Material> set = new HashMap<>();
        final Material fill;

        Blocks(Material fill) {
            this.fill = fill;
        }

        static long key(int x, int y, int z) {
            return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
        }

        Material get(int x, int y, int z) {
            return set.getOrDefault(key(x, y, z), fill);
        }

        void put(int x, int y, int z, Material m) {
            set.put(key(x, y, z), m);
        }
    }

    private static LimitedRegion region(Blocks blocks) {
        return region(blocks, 0, 0);
    }

    /** A region over the chunks around one chunk (vanilla's decoration reach). */
    private static LimitedRegion region(Blocks blocks, int chunkX, int chunkZ) {
        int x0 = (chunkX - 1) << 4;
        int z0 = (chunkZ - 1) << 4;
        return (LimitedRegion) Proxy.newProxyInstance(LimitedRegion.class.getClassLoader(),
                new Class<?>[] {LimitedRegion.class}, (p, m, a) -> switch (m.getName()) {
                    case "isInRegion" -> {
                        int x = (int) a[0];
                        int y = (int) a[1];
                        int z = (int) a[2];
                        yield x >= x0 && x < x0 + 48 && z >= z0 && z < z0 + 48 && y >= MIN && y < MAX;
                    }
                    case "getType" -> blocks.get((int) a[0], (int) a[1], (int) a[2]);
                    case "setType" -> {
                        blocks.put((int) a[0], (int) a[1], (int) a[2], (Material) a[3]);
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    /** One chunk's data, with a flat worldgen surface at {@code surface}. */
    private static ChunkData chunk(Blocks blocks, int surface) {
        return (ChunkData) Proxy.newProxyInstance(ChunkData.class.getClassLoader(), new Class<?>[] {ChunkData.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getMinHeight" -> MIN;
                    case "getMaxHeight" -> MAX;
                    case "getHeight" -> surface;
                    case "getType" -> blocks.get((int) a[0], (int) a[1], (int) a[2]);
                    case "setBlock" -> {
                        blocks.put((int) a[0], (int) a[1], (int) a[2], (Material) a[3]);
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private static WorldInfo world() {
        return (WorldInfo) Proxy.newProxyInstance(WorldInfo.class.getClassLoader(), new Class<?>[] {WorldInfo.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getSeed" -> SEED;
                    case "getMinHeight" -> MIN;
                    case "getMaxHeight" -> MAX;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    // ------------------------------------------------------------------ sealing

    @Test
    void liquidOpenBesideOrBelowIsSealedIntoTheGroundUnderIt() {
        Blocks blocks = new Blocks(Material.STONE);
        LimitedRegion region = region(blocks);
        blocks.put(5, 40, 5, Material.WATER);
        blocks.put(6, 40, 5, Material.AIR);
        blocks.put(5, 39, 5, Material.GRANITE);
        assertTrue(Spills.seal(region, 5, 40, 5));
        assertEquals(Material.GRANITE, blocks.get(5, 40, 5));

        blocks.put(8, 20, 8, Material.LAVA);
        blocks.put(8, 19, 8, Material.CAVE_AIR);
        assertTrue(Spills.seal(region, 8, 20, 8));
        assertEquals(Material.STONE, blocks.get(8, 20, 8), "nothing to copy over air: stone");

        blocks.put(8, -20, 8, Material.WATER);
        blocks.put(8, -21, 8, Material.AIR);
        assertTrue(Spills.seal(region, 8, -20, 8));
        assertEquals(Material.DEEPSLATE, blocks.get(8, -20, 8), "deepslate below 0");

        blocks.put(2, 70, 2, Material.WATER);
        blocks.put(3, 70, 2, Material.AIR);
        blocks.put(2, 69, 2, Material.GRASS_BLOCK);
        assertTrue(Spills.seal(region, 2, 70, 2));
        assertEquals(Material.DIRT, blocks.get(2, 70, 2), "never grass in a wall");
    }

    @Test
    void poolsOpenOnlyToTheSkyStay() {
        Blocks blocks = new Blocks(Material.STONE);
        LimitedRegion region = region(blocks);
        blocks.put(5, 40, 5, Material.WATER);
        blocks.put(5, 41, 5, Material.AIR);
        assertFalse(Spills.seal(region, 5, 40, 5));
        assertEquals(Material.WATER, blocks.get(5, 40, 5));
        assertFalse(Spills.seal(region, 6, 40, 5), "stone isn't liquid");
    }

    @Test
    void airOutsideTheRegionIsNotSeen() {
        Blocks blocks = new Blocks(Material.STONE);
        blocks.put(31, 40, 5, Material.WATER);
        blocks.put(32, 40, 5, Material.AIR); // out of the region's reach
        assertFalse(Spills.wouldRun(region(blocks), 31, 40, 5));
    }

    // ------------------------------------------------------------------ Drought

    private static DroughtGenerator drought() {
        return new DroughtGenerator(new HorizonsSettings.Drought(true, 0, 48, 0));
    }

    @Test
    void droughtDrainsFromTheWaterLevelUpToTheSurface() {
        Blocks blocks = new Blocks(Material.STONE);
        for (int y = 40; y <= 62; y++) {
            blocks.put(3, y, 3, Material.WATER); // a sea down to y 40
        }
        blocks.put(4, 95, 4, Material.WATER); // a hillside aquifer above the old y 80 cut-off
        drought().drain(chunk(blocks, 110));
        for (int y = 40; y <= 48; y++) {
            assertEquals(Material.WATER, blocks.get(3, y, 3), "the pool keeps its water at " + y);
        }
        for (int y = 49; y <= 62; y++) {
            assertEquals(Material.AIR, blocks.get(3, y, 3), "drained at " + y);
        }
        assertEquals(Material.AIR, blocks.get(4, 95, 4));
    }

    @Test
    void droughtDrainsWhatTheCarversRefill() {
        Blocks blocks = new Blocks(Material.STONE);
        ChunkData data = chunk(blocks, 70);
        DroughtGenerator gen = drought();
        gen.generateSurface(world(), new Random(1), 0, 0, data);
        // A canyon carved through the drained river bank, refilled by the aquifer to sea level.
        for (int y = 52; y <= 62; y++) {
            blocks.put(9, y, 9, Material.WATER);
        }
        gen.generateCaves(world(), new Random(1), 0, 0, data);
        for (int y = 52; y <= 62; y++) {
            assertEquals(Material.AIR, blocks.get(9, y, 9), "carver water left at " + y);
        }
    }

    @Test
    void droughtDrainsAtLeastToEightyWhereTheHeightmapIsLow() {
        Blocks blocks = new Blocks(Material.STONE);
        blocks.put(1, 75, 1, Material.WATER);
        drought().drain(chunk(blocks, MIN));
        assertEquals(Material.AIR, blocks.get(1, 75, 1));
    }

    // ------------------------------------------------------------------ Deep Dark

    @Test
    void deepDarkSealsLiquidAgainstANeighboursCavern() {
        Blocks blocks = new Blocks(Material.DEEPSLATE);
        PerSeed<CavernField> caverns = new PerSeed<>(seed -> new CavernField(seed, -48, 8, 35));
        // An aquifer on chunk 0, 0's east edge; the next chunk's cavern opens right against it.
        blocks.put(15, -10, 5, Material.WATER);
        blocks.put(16, -10, 5, Material.CAVE_AIR);
        // Lava in the north-west corner, against a cavern in the chunk to the north.
        blocks.put(0, -30, 0, Material.LAVA);
        blocks.put(0, -30, -1, Material.CAVE_AIR);
        // A spring inside the chunk is vanilla's own and stays.
        blocks.put(8, -10, 8, Material.WATER);
        blocks.put(9, -10, 8, Material.CAVE_AIR);
        new DeepDarkGenerator.EdgeSeal(caverns).populate(world(), new Random(1), 0, 0, region(blocks));
        assertEquals(Material.DEEPSLATE, blocks.get(15, -10, 5));
        assertEquals(Material.DEEPSLATE, blocks.get(0, -30, 0));
        assertEquals(Material.WATER, blocks.get(8, -10, 8));
    }

    // ------------------------------------------------------------------ Shattered Skies

    @Test
    void skiesSealsSpringsThatWouldPourOffAnIsland() {
        int sea = 24;
        PerSeed<IslandField> field = new PerSeed<>(seed -> new IslandField(seed, 25));
        IslandField islands = field.get(SEED);
        int[] spans = new int[4];
        // A column of island at least 5 blocks thick, away from its chunk's edges.
        for (int x = 1; x < 4096; x += 16) {
            for (int z = 1; z < 15; z++) {
                if (islands.spans(x, z, spans) == 0 || spans[1] - Math.max(sea + 2, spans[0]) < 4) {
                    continue;
                }
                int y = Math.max(sea + 2, spans[0]) + 2;
                Blocks blocks = new Blocks(Material.STONE);
                blocks.put(x, y, z, Material.WATER);
                blocks.put(x + 1, y, z, Material.AIR);
                blocks.put(x, y + 1, z + 1, Material.WATER); // a pool only open above stays
                blocks.put(x, y + 2, z + 1, Material.AIR);
                new ShatteredSkiesGenerator.DrySprings(field, sea)
                        .populate(world(), new Random(1), x >> 4, 0, region(blocks, x >> 4, 0));
                assertEquals(Material.STONE, blocks.get(x, y, z));
                assertEquals(Material.WATER, blocks.get(x, y + 1, z + 1));
                return;
            }
        }
        throw new AssertionError("no island found to test on");
    }
}
