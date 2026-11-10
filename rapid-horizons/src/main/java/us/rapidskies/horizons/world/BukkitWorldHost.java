package us.rapidskies.horizons.world;

import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.generator.ChunkGenerator;
import org.slf4j.Logger;
import us.rapidskies.horizons.frontier.Modifier;

/**
 * Worlds through Bukkit alone, for a server without Multiverse-Core. Bukkit doesn't remember
 * a world's generator, so every load passes it again; nothing else ever loads these worlds.
 */
public final class BukkitWorldHost implements WorldHost {

    private final Logger log;
    private final Function<Modifier, ChunkGenerator> generators;

    public BukkitWorldHost(Logger log, Function<Modifier, ChunkGenerator> generators) {
        this.log = log;
        this.generators = generators;
    }

    @Override
    public World load(Spec spec) {
        World w = Bukkit.getWorld(spec.key());
        if (w != null) {
            return w;
        }
        WorldCreator creator = WorldCreator.ofKey(spec.key()).environment(spec.environment()).seed(spec.seed())
                .generateStructures(true);
        spec.generator().ifPresent(m -> creator.generator(generators.apply(m)));
        w = creator.createWorld();
        if (w == null) {
            throw new IllegalStateException("Paper refused to create " + spec.key());
        }
        log.info("Loaded world {}{}", spec.key(), spec.generator().map(m -> " (" + m.id() + ")").orElse(""));
        return w;
    }

    @Override
    public boolean unload(World world) {
        return Bukkit.unloadWorld(world, true);
    }

    @Override
    public void adopt(World world, String alias) {
        // Nothing to register: the world is the server's own.
    }

    @Override
    public String describe() {
        return "Bukkit";
    }
}
