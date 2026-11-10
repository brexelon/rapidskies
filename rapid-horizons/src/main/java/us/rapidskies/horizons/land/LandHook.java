package us.rapidskies.horizons.land;

import java.util.UUID;
import java.util.function.ToIntFunction;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * What Horizons asks of land claims. rapid-claims is optional (softdepend), so every use of
 * its types lives in {@link ClaimsLandHook}, which is only loaded when it's enabled;
 * without it {@link #none()} answers as if nothing were claimed.
 */
public interface LandHook {

    /** What claims say about PvP at a spot (mirrors rapid-claims' {@code PvpRule}). */
    enum PvpZone {
        /** Not claimed: personal toggles decide. */
        UNCLAIMED,
        /** A claim with PvP off: nobody fights. */
        DENY,
        /** A claim with PvP on: fighting is allowed whatever the toggles say. */
        FORCE
    }

    boolean isClaimed(World world, int blockX, int blockZ);

    PvpZone pvp(Location at);

    /** Extra personal claim chunks (from Trade levels). */
    void registerAllowance(Plugin plugin, ToIntFunction<UUID> extraChunks);

    void unregisterAllowance(Plugin plugin);

    static LandHook none() {
        return new LandHook() {
            @Override
            public boolean isClaimed(World world, int blockX, int blockZ) {
                return false;
            }

            @Override
            public PvpZone pvp(Location at) {
                return PvpZone.UNCLAIMED;
            }

            @Override
            public void registerAllowance(Plugin plugin, ToIntFunction<UUID> extraChunks) {
            }

            @Override
            public void unregisterAllowance(Plugin plugin) {
            }
        };
    }
}
