package us.rapidskies.horizons.world;

import java.util.Optional;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import us.rapidskies.horizons.frontier.Modifier;

/**
 * Who creates, loads and unloads the Horizons worlds: Multiverse-Core when it's installed
 * ({@link MultiverseWorldHost}; it records each world's generator and settings in its
 * {@code worlds.yml}, and staff manage them with {@code /mv}), else Bukkit directly
 * ({@link BukkitWorldHost}). Main thread only.
 */
public interface WorldHost {

    /**
     * A world Horizons wants loaded.
     *
     * @param generator    the Frontier modifier whose generator builds it; empty for vanilla
     * @param alias        the name Multiverse shows for it
     * @param allowWeather false keeps it clear (Drought)
     */
    record Spec(NamespacedKey key, World.Environment environment, long seed, Optional<Modifier> generator,
                String alias, boolean allowWeather) {
    }

    /** The loaded world, loading it (or creating it, when new) first. Throws if that fails. */
    World load(Spec spec);

    /**
     * Saves and unloads {@code world} and forgets it, so it isn't loaded again (its folder is
     * left alone, for the archive). False if it couldn't be unloaded.
     */
    boolean unload(World world);

    /**
     * Takes an existing world (the Heartland) under management without touching its terrain
     * or generator: Multiverse imports it if it hasn't already.
     */
    void adopt(World world, String alias);

    /** For the startup log: "Multiverse-Core 5.8.2" or "Bukkit". */
    String describe();

    /** Called when rapid-horizons disables. */
    default void close() {
    }
}
