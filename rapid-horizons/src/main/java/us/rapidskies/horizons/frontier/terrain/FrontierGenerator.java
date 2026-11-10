package us.rapidskies.horizons.frontier.terrain;

import java.util.Optional;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import us.rapidskies.horizons.HorizonsSettings;
import us.rapidskies.horizons.frontier.Modifier;

/**
 * A Frontier overworld's generator, one per modifier. rapid-horizons registers them with
 * Multiverse-Core as {@code rapid-horizons:<modifier>} ({@code rapid-horizons:shattered_skies}),
 * so Multiverse records the generator in its {@code worlds.yml} and every later load of the
 * world asks rapid-horizons for the same one ({@code RapidHorizons#getDefaultWorldGenerator}).
 *
 * <ul>
 *   <li>{@link OvergrowthGenerator}, {@link DroughtGenerator} and {@link DeepDarkGenerator}
 *       start from vanilla terrain, caves, structures, decorations and mobs, then reshape it
 *       for the modifier (mossy forest floors; drained seas; hollowed caverns).</li>
 *   <li>{@link ShatteredSkiesGenerator} builds its own terrain and lets vanilla decorate it.</li>
 * </ul>
 *
 * Every block they place exists in 1.20.
 */
public abstract class FrontierGenerator extends ChunkGenerator {

    /** The plugin name Multiverse and Bukkit resolve generator strings against. */
    public static final String PLUGIN = "rapid-horizons";

    protected final Modifier modifier;

    protected FrontierGenerator(Modifier modifier) {
        this.modifier = modifier;
    }

    /** The generator for {@code modifier}, tuned by the {@code frontier} config. */
    public static FrontierGenerator create(Modifier modifier, HorizonsSettings.Frontier settings) {
        return switch (modifier) {
            case OVERGROWTH -> new OvergrowthGenerator(settings.overgrowth());
            case DROUGHT -> new DroughtGenerator(settings.drought());
            case SHATTERED_SKIES -> new ShatteredSkiesGenerator(settings.shatteredSkies());
            case DEEP_DARK -> new DeepDarkGenerator(settings.deepDark());
        };
    }

    /** The modifier a generator id ({@code shattered_skies}, as Multiverse passes it) names. */
    public static Optional<Modifier> modifierOf(String id) {
        return id == null || id.isBlank() ? Optional.empty() : Modifier.parse(id);
    }

    /** What Multiverse stores as the world's generator: {@code rapid-horizons:<id>}. */
    public static String generatorString(Modifier modifier) {
        return PLUGIN + ":" + modifier.id();
    }

    public Modifier modifier() {
        return modifier;
    }

    // Vanilla does the work unless a modifier says otherwise.

    @Override
    public boolean shouldGenerateNoise() {
        return true;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return true;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return true;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return true;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return true;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return true;
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        return new FrontierBiomes(modifier, 0);
    }

    // ------------------------------------------------------------------ for the surface passes

    /**
     * The highest non-air block of a column in chunk data, or {@code min - 1}. Reads the
     * worldgen heightmap and steps down past any air it reports, so it holds whichever way the
     * server counts.
     */
    static int topBlock(ChunkData data, int x, int z) {
        int min = data.getMinHeight();
        int y = Math.min(data.getMaxHeight() - 1, data.getHeight(HeightMap.WORLD_SURFACE_WG, x, z) + 1);
        while (y >= min && data.getType(x, y, z).isAir()) {
            y--;
        }
        return y;
    }

    static boolean grass(Material m) {
        return m == Material.GRASS_BLOCK;
    }
}
