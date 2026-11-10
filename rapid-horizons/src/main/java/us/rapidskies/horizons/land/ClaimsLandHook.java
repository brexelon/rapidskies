package us.rapidskies.horizons.land;

import java.util.UUID;
import java.util.function.ToIntFunction;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import us.rapidskies.claims.ClaimService;

/** {@link LandHook} backed by rapid-claims. Only loaded when rapid-claims is enabled. */
public final class ClaimsLandHook implements LandHook {

    private final ClaimService claims;

    private ClaimsLandHook(ClaimService claims) {
        this.claims = claims;
    }

    /** The hook, or null if rapid-claims hasn't registered its service. */
    public static LandHook create() {
        return ClaimService.find().<LandHook>map(ClaimsLandHook::new).orElse(null);
    }

    @Override
    public boolean isClaimed(World world, int blockX, int blockZ) {
        return claims.isClaimed(world, blockX, blockZ);
    }

    @Override
    public PvpZone pvp(Location at) {
        return switch (claims.pvp(at)) {
            case UNCLAIMED -> PvpZone.UNCLAIMED;
            case DENY -> PvpZone.DENY;
            case FORCE -> PvpZone.FORCE;
        };
    }

    @Override
    public void registerAllowance(Plugin plugin, ToIntFunction<UUID> extraChunks) {
        claims.registerAllowance(plugin, extraChunks);
    }

    @Override
    public void unregisterAllowance(Plugin plugin) {
        claims.unregisterAllowance(plugin);
    }
}
