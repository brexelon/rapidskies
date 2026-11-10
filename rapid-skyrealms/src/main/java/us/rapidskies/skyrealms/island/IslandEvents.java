package us.rapidskies.skyrealms.island;

import com.bgsoftware.superiorskyblock.api.events.IslandDisbandEvent;
import com.bgsoftware.superiorskyblock.api.events.IslandGenerateBlockEvent;
import com.bgsoftware.superiorskyblock.api.events.PostIslandCreateEvent;
import java.util.UUID;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import us.rapidskies.skyrealms.RapidSkyRealms;

/**
 * SuperiorSkyblock2's island events, handed to the services as plain ids: a new island starts on
 * the first Realm Tier and from zero in the season; a disbanded one ends its Trade Routes and is
 * forgotten; a block from the island generator is remembered so mining it counts for tier goals.
 */
public final class IslandEvents implements Listener {

    private final RapidSkyRealms plugin;

    public IslandEvents(RapidSkyRealms plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCreated(PostIslandCreateEvent event) {
        UUID id = event.getIsland().getUniqueId();
        plugin.tiers().islandCreated(id);
        plugin.seasons().islandCreated(id);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDisband(IslandDisbandEvent event) {
        UUID id = event.getIsland().getUniqueId();
        plugin.routes().islandDisbanded(id);
        plugin.tiers().islandDisbanded(id);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGenerate(IslandGenerateBlockEvent event) {
        if (event.isPlaceBlock()) {
            plugin.tiers().markGenerated(event.getLocation().getBlock());
        }
    }
}
