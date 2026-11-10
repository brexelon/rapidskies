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
 * Drought: vanilla terrain under deserts, badlands and savanna, with its water taken away.
 * Seas, rivers and aquifers are drained down to {@code water-level}: what's left are deep warm
 * pools in the old sea basins, ringed by dry seabed where shipwrecks and ruins lie beached,
 * and dry river valleys. Grass cracks into coarse earth in patches, and dead bushes dot the
 * exposed seabed.
 *
 * <p>The water is drained twice: after the surface (so the cracked patches and heightmaps see
 * dry ground) and again after vanilla's carvers, which fill every cave and canyon they cut
 * below sea level with water from the aquifer. Left alone, those cuts through drained river
 * banks hang over the dry valleys as walls of water and pour out once the chunk loads. Each
 * drain reaches from {@code water-level} up to the column's surface, so no aquifer is cut off
 * part-way up and left floating over the air it drained into. Desert wells and springs come
 * later, with vanilla's decorations, and keep their water.
 */
final class DroughtGenerator extends FrontierGenerator {

    /**
     * The least a drain reaches up to: vanilla's sea level and the aquifers just above it.
     * Where the surface is higher, the drain goes up to the surface.
     */
    private static final int DRAIN_TOP = 80;

    private final HorizonsSettings.Drought settings;
    private final PerSeed<Noise.Patches> cracked;

    DroughtGenerator(HorizonsSettings.Drought settings) {
        super(Modifier.DROUGHT);
        this.settings = settings;
        this.cracked = new PerSeed<>(seed -> new Noise.Patches(seed, 31, 1 / 14.0, settings.crackedPercent()));
    }

    @Override
    public void generateSurface(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData data) {
        drain(data);
        Noise.Patches patches = settings.crackedPercent() > 0 ? cracked.get(worldInfo.getSeed()) : null;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (patches != null && patches.at((chunkX << 4) + x, (chunkZ << 4) + z)) {
                    int top = topBlock(data, x, z);
                    if (top >= data.getMinHeight() && grass(data.getType(x, top, z))) {
                        data.setBlock(x, top, z, Material.COARSE_DIRT);
                    }
                }
            }
        }
    }

    /** Runs after vanilla's carvers, which refill what they cut below sea level from the aquifer. */
    @Override
    public void generateCaves(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData data) {
        drain(data);
    }

    /** Takes away the water from {@code water-level} up to each column's surface. */
    void drain(ChunkData data) {
        int from = Math.max(data.getMinHeight(), settings.waterLevel() + 1);
        int ceiling = data.getMaxHeight() - 1;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int to = Math.min(ceiling, Math.max(DRAIN_TOP, data.getHeight(HeightMap.WORLD_SURFACE_WG, x, z) + 1));
                for (int y = from; y <= to; y++) {
                    if (data.getType(x, y, z) == Material.WATER) {
                        data.setBlock(x, y, z, Material.AIR);
                    }
                }
            }
        }
    }

    @Override
    public List<BlockPopulator> getDefaultPopulators(World world) {
        return List.of(new DrySeabed(settings.waterLevel()));
    }

    /** Dead bushes on the drained seabed and riverbeds. */
    static final class DrySeabed extends BlockPopulator {

        private static final int ATTEMPTS = 3;
        /** Vanilla's sea level: below it, dry ground was underwater. */
        private static final int SEA_LEVEL = 63;
        private final int waterLevel;

        DrySeabed(int waterLevel) {
            this.waterLevel = waterLevel;
        }

        @Override
        public void populate(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, LimitedRegion region) {
            for (int i = 0; i < ATTEMPTS; i++) {
                int x = (chunkX << 4) + random.nextInt(16);
                int z = (chunkZ << 4) + random.nextInt(16);
                int y = region.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE_WG);
                if (y <= waterLevel + 1 || y > SEA_LEVEL || !region.isInRegion(x, y, z)
                        || !region.isInRegion(x, y - 1, z) || !region.getType(x, y, z).isAir()) {
                    continue;
                }
                Material ground = region.getType(x, y - 1, z);
                if (ground == Material.SAND || ground == Material.RED_SAND || ground == Material.TERRACOTTA
                        || ground == Material.COARSE_DIRT || ground == Material.DIRT) {
                    region.setType(x, y, z, Material.DEAD_BUSH);
                }
            }
        }
    }
}
