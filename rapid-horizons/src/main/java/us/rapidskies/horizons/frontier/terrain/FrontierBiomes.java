package us.rapidskies.horizons.frontier.terrain;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.key.Key;
import org.bukkit.Registry;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeParameterPoint;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;
import us.rapidskies.horizons.frontier.Modifier;
import us.rapidskies.horizons.frontier.ModifierBiomes;

/**
 * {@link ModifierBiomes} on Paper's climate sampling. The climate comes from vanilla's noise
 * for the world's seed even where the terrain is our own (Shattered Skies), so biomes spread
 * the way players expect.
 */
final class FrontierBiomes extends BiomeProvider {

    private final Modifier modifier;
    /** Shattered Skies only: the sea's surface, below which the biome is ocean. */
    private final int seaLevel;
    private final Map<String, Biome> cache = new ConcurrentHashMap<>();
    private final Biome fallback;

    FrontierBiomes(Modifier modifier, int seaLevel) {
        this.modifier = modifier;
        this.seaLevel = seaLevel;
        this.fallback = lookup(switch (modifier) {
            case OVERGROWTH -> "minecraft:forest";
            case DROUGHT -> "minecraft:desert";
            case SHATTERED_SKIES -> "minecraft:meadow";
            case DEEP_DARK -> "minecraft:plains";
        });
    }

    private Biome lookup(String key) {
        return cache.computeIfAbsent(key, k -> {
            Registry<Biome> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME);
            Biome b = registry.get(Key.key(k));
            return b != null ? b : registry.getOrThrow(Key.key("minecraft:plains"));
        });
    }

    @Override
    public Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
        return fallback;
    }

    @Override
    public Biome getBiome(WorldInfo worldInfo, int x, int y, int z, BiomeParameterPoint p) {
        if (modifier == Modifier.SHATTERED_SKIES) {
            return lookup(ModifierBiomes.skies(p.getTemperature(), p.getHumidity(), p.getWeirdness(), y, seaLevel));
        }
        return lookup(ModifierBiomes.biome(modifier, p.getTemperature(), p.getHumidity(), p.getContinentalness(),
                p.getErosion(), p.getWeirdness(), p.getDepth()));
    }

    @Override
    public List<Biome> getBiomes(WorldInfo worldInfo) {
        List<Biome> out = new ArrayList<>();
        for (String key : ModifierBiomes.all(modifier)) {
            Biome b = lookup(key);
            if (!out.contains(b)) {
                out.add(b);
            }
        }
        return out;
    }
}
