package us.rapidskies.horizons.market;

import java.util.UUID;
import java.util.function.ToIntFunction;
import org.bukkit.plugin.Plugin;
import us.rapidskies.market.MarketService;

/**
 * Extra auction house listings from Trade levels (PLAN.md §4.2: the cap rises with Trade
 * level, never with rank). rapid-market is optional (softdepend), so this is the only class
 * that touches its types, and it's only loaded when rapid-market is enabled.
 */
public final class MarketListings {

    private MarketListings() {
    }

    /** Registers {@code bonus}; false if rapid-market hasn't registered its service. */
    public static boolean register(Plugin plugin, ToIntFunction<UUID> bonus) {
        return MarketService.find().map(m -> {
            m.registerListingBonus(plugin, bonus);
            return true;
        }).orElse(false);
    }

    public static void unregister(Plugin plugin) {
        MarketService.find().ifPresent(m -> m.unregisterListingBonus(plugin));
    }
}
