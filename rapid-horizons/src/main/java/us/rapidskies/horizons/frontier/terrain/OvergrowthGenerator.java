package us.rapidskies.horizons.frontier.terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import us.rapidskies.horizons.HorizonsSettings;
import us.rapidskies.horizons.frontier.Modifier;

/**
 * Overgrowth: vanilla terrain under the modifier's forests, grown thicker. The forest floor
 * turns to moss, podzol and rooted dirt in patches; extra trees of the biome's own kind
 * stand between vanilla's; ferns, leafy bushes and azaleas fill the gaps.
 */
final class OvergrowthGenerator extends FrontierGenerator {

    private final HorizonsSettings.Overgrowth settings;
    private final PerSeed<Noise.Patches> moss;

    OvergrowthGenerator(HorizonsSettings.Overgrowth settings) {
        super(Modifier.OVERGROWTH);
        this.settings = settings;
        this.moss = new PerSeed<>(seed -> new Noise.Patches(seed, 21, 1 / 18.0, settings.mossPercent()));
    }

    @Override
    public void generateSurface(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData data) {
        if (settings.mossPercent() <= 0) {
            return;
        }
        Noise.Patches patches = moss.get(worldInfo.getSeed());
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int wx = (chunkX << 4) + x;
                int wz = (chunkZ << 4) + z;
                if (!patches.at(wx, wz)) {
                    continue;
                }
                int y = topBlock(data, x, z);
                if (y < data.getMinHeight() || !grass(data.getType(x, y, z))) {
                    continue;
                }
                double roll = Noise.hash(worldInfo.getSeed(), wx, wz);
                data.setBlock(x, y, z, roll < 0.6 ? Material.MOSS_BLOCK : roll < 0.85 ? Material.PODZOL
                        : Material.ROOTED_DIRT);
            }
        }
    }

    @Override
    public List<BlockPopulator> getDefaultPopulators(World world) {
        List<BlockPopulator> out = new ArrayList<>();
        if (settings.extraTrees() > 0) {
            out.add(new ExtraTrees(settings.extraTrees()));
        }
        if (settings.groundCover() > 0) {
            out.add(new GroundCover(settings.groundCover()));
        }
        return out;
    }

    private static boolean forestFloor(Material m) {
        return m == Material.GRASS_BLOCK || m == Material.DIRT || m == Material.PODZOL || m == Material.COARSE_DIRT
                || m == Material.MOSS_BLOCK || m == Material.ROOTED_DIRT;
    }

    /** A few more trees per chunk, of the kind the biome grows. */
    static final class ExtraTrees extends BlockPopulator {

        private final int perChunk;

        ExtraTrees(int perChunk) {
            this.perChunk = perChunk;
        }

        @Override
        public void populate(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, LimitedRegion region) {
            for (int i = 0; i < perChunk; i++) {
                int x = (chunkX << 4) + 2 + random.nextInt(12);
                int z = (chunkZ << 4) + 2 + random.nextInt(12);
                int y = region.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE_WG);
                if (y <= worldInfo.getMinHeight() || y >= worldInfo.getMaxHeight() - 32
                        || !region.isInRegion(x, y, z)) {
                    continue;
                }
                if (!forestFloor(region.getType(x, y - 1, z)) || !region.getType(x, y, z).isAir()) {
                    continue;
                }
                TreeType tree = tree(region.getBiome(x, y, z), random);
                if (tree != null) {
                    region.generateTree(new Location(null, x, y, z), random, tree);
                }
            }
        }

        private static TreeType tree(Biome biome, Random random) {
            String key = biome.key().value();
            return switch (key) {
                case "dark_forest" -> random.nextInt(4) == 0 ? TreeType.BIG_TREE : TreeType.DARK_OAK;
                case "birch_forest", "old_growth_birch_forest" -> random.nextBoolean() ? TreeType.TALL_BIRCH
                        : TreeType.BIRCH;
                case "jungle", "bamboo_jungle", "sparse_jungle" -> random.nextInt(3) == 0 ? TreeType.JUNGLE_BUSH
                        : TreeType.SMALL_JUNGLE;
                case "old_growth_spruce_taiga", "old_growth_pine_taiga", "snowy_taiga" -> random.nextBoolean()
                        ? TreeType.TALL_REDWOOD : TreeType.REDWOOD;
                case "cherry_grove" -> TreeType.CHERRY;
                case "swamp" -> TreeType.SWAMP;
                case "mangrove_swamp" -> TreeType.MANGROVE;
                case "forest", "flower_forest" -> random.nextInt(5) == 0 ? TreeType.BIRCH
                        : random.nextInt(4) == 0 ? TreeType.BIG_TREE : TreeType.TREE;
                default -> null;
            };
        }
    }

    /** Ferns, leafy bushes, azaleas and moss carpet on the forest floor. */
    static final class GroundCover extends BlockPopulator {

        private final int perChunk;

        GroundCover(int perChunk) {
            this.perChunk = perChunk;
        }

        @Override
        public void populate(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, LimitedRegion region) {
            for (int i = 0; i < perChunk; i++) {
                int x = (chunkX << 4) + random.nextInt(16);
                int z = (chunkZ << 4) + random.nextInt(16);
                int y = region.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                if (y <= worldInfo.getMinHeight() || y >= worldInfo.getMaxHeight() - 4
                        || !region.isInRegion(x, y + 1, z) || !region.isInRegion(x, y - 1, z)) {
                    continue;
                }
                Material ground = region.getType(x, y - 1, z);
                if (!forestFloor(ground) || !region.getType(x, y, z).isAir()
                        || !region.getType(x, y + 1, z).isAir()) {
                    continue;
                }
                int roll = random.nextInt(10);
                if (roll < 4) {
                    region.setType(x, y, z, Material.FERN);
                } else if (roll < 6) {
                    BlockData lower = Material.LARGE_FERN.createBlockData(d -> ((Bisected) d).setHalf(Bisected.Half.BOTTOM));
                    BlockData upper = Material.LARGE_FERN.createBlockData(d -> ((Bisected) d).setHalf(Bisected.Half.TOP));
                    region.setBlockData(x, y, z, lower);
                    region.setBlockData(x, y + 1, z, upper);
                } else if (roll < 8) {
                    Material leaves = leaves(region.getBiome(x, y, z));
                    BlockData bush = leaves.createBlockData(d -> ((Leaves) d).setPersistent(true));
                    region.setBlockData(x, y, z, bush);
                    if (random.nextBoolean()) {
                        region.setBlockData(x, y + 1, z, bush);
                    }
                } else if (ground == Material.MOSS_BLOCK) {
                    region.setType(x, y, z, random.nextBoolean() ? Material.MOSS_CARPET : Material.AZALEA);
                } else {
                    region.setType(x, y, z, random.nextInt(3) == 0 ? Material.FLOWERING_AZALEA : Material.FERN);
                }
            }
        }

        private static Material leaves(Biome biome) {
            String key = biome.key().value();
            if (key.contains("taiga")) {
                return Material.SPRUCE_LEAVES;
            }
            if (key.contains("jungle")) {
                return Material.JUNGLE_LEAVES;
            }
            if (key.contains("birch")) {
                return Material.BIRCH_LEAVES;
            }
            if (key.equals("dark_forest")) {
                return Material.DARK_OAK_LEAVES;
            }
            return Material.OAK_LEAVES;
        }
    }
}
