package us.rapidskies.skyrealms.hook;

import java.util.UUID;
import java.util.function.ToIntFunction;
import org.bukkit.plugin.Plugin;
import us.rapidskies.market.MarketService;

/**
 * Extra auction house listings from island level (PLAN.md §4.2: the cap rises with island
 * level, never with rank). rapid-market is optional (softdepend), so this is the only class that
 * touches its types, and it's only loaded when rapid-market is enabled.
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

    /** One listing per {@code levelsPerListing} island levels, at most {@code max}. */
    public static int bonus(double level, int levelsPerListing, int max) {
        if (level <= 0 || levelsPerListing <= 0) {
            return 0;
        }
        return (int) Math.min(max, Math.floor(level / levelsPerListing));
    }
}
