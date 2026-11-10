package us.rapidskies.horizons.frontier.terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import us.rapidskies.horizons.HorizonsSettings;
import us.rapidskies.horizons.frontier.Modifier;

/**
 * Shattered Skies: floating islands at two heights ({@link IslandField}) over a calm sea. The
 * terrain is ours; vanilla then decorates it for each biome (trees, flowers, coal, iron and
 * copper, snow), spawns its animals, and fills the sea with kelp and fish. A fall ends in
 * water, never the void. Rare ores (diamond, emerald, gold, lapis, redstone) vein the
 * islands' stone, the only place up high they're found. No structures: villages and
 * strongholds don't fit on shards of land. Vanilla's springs are sealed where they'd pour
 * off an island ({@link DrySprings}): an endless fall of water or lava from the sky.
 */
final class ShatteredSkiesGenerator extends FrontierGenerator {

    private final HorizonsSettings.ShatteredSkies settings;
    private final PerSeed<IslandField> field;

    ShatteredSkiesGenerator(HorizonsSettings.ShatteredSkies settings) {
        super(Modifier.SHATTERED_SKIES);
        this.settings = settings;
        this.field = new PerSeed<>(seed -> new IslandField(seed, settings.islandDensity()));
    }

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        return new FrontierBiomes(modifier, settings.seaLevel());
    }

    @Override
    public void generateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData data) {
        IslandField islands = field.get(worldInfo.getSeed());
        int min = data.getMinHeight();
        int max = data.getMaxHeight() - 1;
        int sea = settings.seaLevel();
        int[] spans = new int[4];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int wx = (chunkX << 4) + x;
                int wz = (chunkZ << 4) + z;
                // The sea: bedrock, deepslate and stone, a sandy floor, water to the surface.
                int floor = Math.max(min + 4, islands.seabed(wx, wz, sea));
                data.setBlock(x, min, z, Material.BEDROCK);
                // setRegion's upper bounds are exclusive; an empty range does nothing.
                data.setRegion(x, min + 1, z, x + 1, Math.min(0, floor - 2), z + 1, Material.DEEPSLATE);
                data.setRegion(x, Math.max(0, min + 1), z, x + 1, floor - 2, z + 1, Material.STONE);
                data.setRegion(x, Math.max(min + 1, floor - 2), z, x + 1, floor + 1, z + 1, Material.SAND);
                data.setRegion(x, floor + 1, z, x + 1, Math.min(sea, max) + 1, z + 1, Material.WATER);
                // The islands.
                int n = islands.spans(wx, wz, spans);
                for (int i = 0; i < n; i++) {
                    int bottom = Math.max(sea + 2, spans[i * 2]);
                    int top = Math.min(max - 1, spans[i * 2 + 1]);
                    data.setRegion(x, bottom, z, x + 1, top + 1, z + 1, Material.STONE);
                }
                for (int i = 0; i < n; i++) {
                    int top = Math.min(max - 1, spans[i * 2 + 1]);
                    int bottom = Math.max(sea + 2, spans[i * 2]);
                    if (top < bottom || !data.getType(x, top + 1, z).isAir()) {
                        continue; // covered by the other height's island
                    }
                    data.setBlock(x, top, z, Material.GRASS_BLOCK);
                    for (int y = top - 1; y >= Math.max(bottom, top - 3); y--) {
                        data.setBlock(x, y, z, Material.DIRT);
                    }
                }
            }
        }
    }

    @Override
    public int getBaseHeight(WorldInfo worldInfo, Random random, int x, int z, HeightMap heightMap) {
        IslandField islands = field.get(worldInfo.getSeed());
        int top = islands.top(x, z);
        if (top != Integer.MIN_VALUE) {
            return top + 1;
        }
        boolean floor = heightMap == HeightMap.OCEAN_FLOOR || heightMap == HeightMap.OCEAN_FLOOR_WG;
        return (floor ? islands.seabed(x, z, settings.seaLevel()) : settings.seaLevel()) + 1;
    }

    /** The world's spawn: on the island nearest the centre (it's never used to land players; /frontier go is). */
    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        IslandField islands = field.get(world.getSeed());
        for (int r = 0; r <= 512; r += 8) {
            for (int dx = -r; dx <= r; dx += 8) {
                for (int dz = -r; dz <= r; dz += 8) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    int top = islands.top(dx, dz);
                    if (top != Integer.MIN_VALUE) {
                        return new Location(world, dx + 0.5, top + 1, dz + 0.5);
                    }
                }
            }
        }
        return new Location(world, 0.5, settings.seaLevel() + 1, 0.5);
    }

    @Override
    public List<BlockPopulator> getDefaultPopulators(World world) {
        List<BlockPopulator> out = new ArrayList<>();
        out.add(new DrySprings(field, settings.seaLevel()));
        if (settings.oreVeins() > 0) {
            out.add(new RareOres(field, settings.oreVeins(), settings.seaLevel()));
        }
        return out;
    }

    /**
     * Seals water and lava in the islands that would run: vanilla's springs, placed in any
     * stone wall with one side open, which on an island means a fall to the sea. Pools that
     * only see the sky above them stay.
     */
    static final class DrySprings extends BlockPopulator {

        private final PerSeed<IslandField> field;
        private final int seaLevel;

        DrySprings(PerSeed<IslandField> field, int seaLevel) {
            this.field = field;
            this.seaLevel = seaLevel;
        }

        @Override
        public void populate(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, LimitedRegion region) {
            IslandField islands = field.get(worldInfo.getSeed());
            int max = worldInfo.getMaxHeight() - 1;
            int[] spans = new int[4];
            for (int x = chunkX << 4; x < (chunkX << 4) + 16; x++) {
                for (int z = chunkZ << 4; z < (chunkZ << 4) + 16; z++) {
                    int n = islands.spans(x, z, spans);
                    for (int i = 0; i < n; i++) {
                        int bottom = Math.max(seaLevel + 2, spans[i * 2]);
                        int top = Math.min(max - 1, spans[i * 2 + 1]);
                        for (int y = bottom; y <= top; y++) {
                            Spills.seal(region, x, y, z);
                        }
                    }
                }
            }
        }
    }

    /** Veins of rare ore in the islands' stone. */
    static final class RareOres extends BlockPopulator {

        private final PerSeed<IslandField> field;
        private final int veins;
        private final int seaLevel;

        RareOres(PerSeed<IslandField> field, int veins, int seaLevel) {
            this.field = field;
            this.veins = veins;
            this.seaLevel = seaLevel;
        }

        @Override
        public void populate(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, LimitedRegion region) {
            IslandField islands = field.get(worldInfo.getSeed());
            int[] spans = new int[4];
            for (int i = 0; i < veins; i++) {
                int x = (chunkX << 4) + random.nextInt(16);
                int z = (chunkZ << 4) + random.nextInt(16);
                int n = islands.spans(x, z, spans);
                if (n == 0) {
                    continue;
                }
                int s = random.nextInt(n);
                int bottom = Math.max(seaLevel + 2, spans[s * 2]);
                int top = spans[s * 2 + 1] - 4;
                if (top <= bottom) {
                    continue;
                }
                int y = bottom + random.nextInt(top - bottom);
                Material ore = pick(random);
                int size = switch (ore) {
                    case DIAMOND_ORE -> 2 + random.nextInt(3);
                    case EMERALD_ORE -> 1 + random.nextInt(2);
                    default -> 3 + random.nextInt(5);
                };
                for (int b = 0; b < size; b++) {
                    if (region.isInRegion(x, y, z) && region.getType(x, y, z) == Material.STONE) {
                        region.setType(x, y, z, ore);
                    }
                    switch (random.nextInt(6)) {
                        case 0 -> x++;
                        case 1 -> x--;
                        case 2 -> y++;
                        case 3 -> y--;
                        case 4 -> z++;
                        default -> z--;
                    }
                }
            }
        }

        private static Material pick(Random random) {
            int roll = random.nextInt(100);
            if (roll < 15) {
                return Material.DIAMOND_ORE;
            }
            if (roll < 25) {
                return Material.EMERALD_ORE;
            }
            if (roll < 50) {
                return Material.GOLD_ORE;
            }
            if (roll < 75) {
                return Material.LAPIS_ORE;
            }
            return Material.REDSTONE_ORE;
        }
    }
}
