package us.rapidskies.market;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import us.rapidskies.common.economy.EconomyMode;

/**
 * rapid-market's API for other plugins, through Bukkit's ServicesManager ({@link #find()}).
 *
 * <p>The active-listing cap is the same for everyone (PLAN.md §4.2) and rises only with
 * progress in the mode: rapid-horizons adds listings for Trade levels, rapid-skyrealms (phase
 * 4b) for island level. Never register a source tied to a rank or anything bought.
 */
public final class MarketService {

    private final EconomyMode mode;
    private final int base;
    private final Map<Plugin, ToIntFunction<UUID>> bonuses = new ConcurrentHashMap<>();
    private final org.slf4j.Logger logger;

    MarketService(EconomyMode mode, int base, org.slf4j.Logger logger) {
        this.mode = mode;
        this.base = base;
        this.logger = logger;
    }

    public static Optional<MarketService> find() {
        var reg = Bukkit.getServicesManager().getRegistration(MarketService.class);
        return reg == null ? Optional.empty() : Optional.of(reg.getProvider());
    }

    /** The mode (and currency) this server's auction house trades in. */
    public EconomyMode mode() {
        return mode;
    }

    /**
     * Adds extra active listings per player. {@code extra} must be cheap and main-thread
     * safe (it's read from memory when listing). Remove it in onDisable.
     */
    public void registerListingBonus(Plugin plugin, ToIntFunction<UUID> extra) {
        bonuses.put(plugin, extra);
    }

    public void unregisterListingBonus(Plugin plugin) {
        bonuses.remove(plugin);
    }

    /** How many listings {@code player} may have up at once. Main thread. */
    public int maxListings(UUID player) {
        int sum = base;
        for (Map.Entry<Plugin, ToIntFunction<UUID>> e : bonuses.entrySet()) {
            try {
                sum += Math.max(0, e.getValue().applyAsInt(player));
            } catch (RuntimeException ex) {
                logger.warn("Listing bonus from {} failed: {}", e.getKey().getName(), ex.toString());
            }
        }
        return sum;
    }
}
