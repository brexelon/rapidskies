package us.rapidskies.horizons.frontier.terrain;

import java.util.List;
import java.util.Random;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import us.rapidskies.horizons.HorizonsSettings;
import us.rapidskies.horizons.frontier.Modifier;

/**
 * Deep Dark: vanilla terrain with a thin, barren surface (open plains and gravelly hills,
 * grass worn to coarse dirt and gravel in patches) over huge caverns ({@link CavernField})
 * hollowed out after vanilla's own caves. Vanilla then decorates them by biome
 * ({@code ModifierBiomes}): most of the deep is deep dark, with its sculk, shriekers and
 * ancient cities; higher up, dripstone, and lush caves where it's wet.
 *
 * <p>Caverns keep a roof at least {@link #ROOF} blocks thick under the ground or sea floor,
 * and a block next to water or lava stays, so aquifers and lava pools keep their walls instead
 * of pouring into the caverns. Carving sees only its own chunk, so where a chunk edge puts
 * liquid against a neighbour's cavern, {@link EdgeSeal} walls it off once both are carved.
 */
final class DeepDarkGenerator extends FrontierGenerator {

    static final int ROOF = 20;

    private final HorizonsSettings.DeepDark settings;
    private final PerSeed<CavernField> caverns;
    private final PerSeed<Noise.Patches> barren;

    DeepDarkGenerator(HorizonsSettings.DeepDark settings) {
        super(Modifier.DEEP_DARK);
        this.settings = settings;
        this.caverns = new PerSeed<>(seed -> new CavernField(seed, settings.cavernFloor(), settings.cavernTop(),
                settings.openness()));
        this.barren = new PerSeed<>(seed -> new Noise.Patches(seed, 41, 1 / 16.0, settings.barrenPercent()));
    }

    @Override
    public void generateSurface(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData data) {
        if (settings.barrenPercent() <= 0) {
            return;
        }
        Noise.Patches patches = barren.get(worldInfo.getSeed());
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int wx = (chunkX << 4) + x;
                int wz = (chunkZ << 4) + z;
                if (!patches.at(wx, wz)) {
                    continue;
                }
                int y = topBlock(data, x, z);
                if (y >= data.getMinHeight() && grass(data.getType(x, y, z))) {
                    data.setBlock(x, y, z, Noise.hash(worldInfo.getSeed(), wx, wz) < 0.7 ? Material.COARSE_DIRT
                            : Material.GRAVEL);
                }
            }
        }
    }

    @Override
    public void generateCaves(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData data) {
        CavernField field = caverns.get(worldInfo.getSeed());
        int floor = Math.max(field.floor(), data.getMinHeight() + 1);
        boolean[] open = new boolean[field.height() * 256];
        field.carve(chunkX, chunkZ, open);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int ground = data.getHeight(HeightMap.OCEAN_FLOOR_WG, x, z);
                int top = Math.min(field.top(), ground - ROOF);
                for (int y = floor; y <= top; y++) {
                    if (!open[(y - field.floor()) * 256 + z * 16 + x]) {
                        continue;
                    }
                    Material m = data.getType(x, y, z);
                    if (m.isAir() || m == Material.BEDROCK || Spills.liquid(m) || nextToLiquid(data, x, y, z)) {
                        continue;
                    }
                    data.setBlock(x, y, z, Material.CAVE_AIR);
                }
            }
        }
    }

    @Override
    public List<BlockPopulator> getDefaultPopulators(World world) {
        return List.of(new EdgeSeal(caverns));
    }

    /** Neighbours in this chunk only; {@link EdgeSeal} handles the ones across its edges. */
    private static boolean nextToLiquid(ChunkData data, int x, int y, int z) {
        return (x > 0 && Spills.liquid(data.getType(x - 1, y, z)))
                || (x < 15 && Spills.liquid(data.getType(x + 1, y, z)))
                || (z > 0 && Spills.liquid(data.getType(x, y, z - 1)))
                || (z < 15 && Spills.liquid(data.getType(x, y, z + 1)))
                || Spills.liquid(data.getType(x, y - 1, z)) || Spills.liquid(data.getType(x, y + 1, z));
    }

    /**
     * Seals water and lava on a chunk's edges that a neighbouring chunk's cavern left open to
     * the air. Decoration waits for the neighbours to be carved, so by now both sides are
     * final; each chunk seals its own liquid, so every edge is covered once.
     */
    static final class EdgeSeal extends BlockPopulator {

        private final PerSeed<CavernField> caverns;

        EdgeSeal(PerSeed<CavernField> caverns) {
            this.caverns = caverns;
        }

        @Override
        public void populate(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, LimitedRegion region) {
            CavernField field = caverns.get(worldInfo.getSeed());
            int from = Math.max(worldInfo.getMinHeight() + 1, field.floor() - 1);
            int to = Math.min(worldInfo.getMaxHeight() - 2, field.top() + 1);
            int bx = chunkX << 4;
            int bz = chunkZ << 4;
            for (int i = 0; i < 16; i++) {
                for (int y = from; y <= to; y++) {
                    Spills.seal(region, bx + i, y, bz);
                    Spills.seal(region, bx + i, y, bz + 15);
                    if (i > 0 && i < 15) {
                        Spills.seal(region, bx, y, bz + i);
                        Spills.seal(region, bx + 15, y, bz + i);
                    }
                }
            }
        }
    }
}
