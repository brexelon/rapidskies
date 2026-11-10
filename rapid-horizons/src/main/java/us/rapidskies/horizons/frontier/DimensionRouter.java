package us.rapidskies.horizons.frontier;

import io.papermc.paper.event.entity.EntityPortalReadyEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.PortalType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import us.rapidskies.common.time.Durations;
import us.rapidskies.horizons.RapidHorizons;
import us.rapidskies.horizons.frontier.FrontierService.Place;

/**
 * The Nether and End rotate with the Frontier (PLAN.md §5.2): every portal leads to the
 * open Frontier's Nether and End, never to the Heartland's vanilla ones (which nobody
 * reaches). Shared by the Heartland and the Frontier:
 *
 * <ul>
 *   <li>Nether portals in the Heartland or the Frontier lead to the rotating Nether; Nether
 *       portals there lead back to the overworld the player came from (the Heartland if
 *       unknown, e.g. after a restart). Paper scales the coordinates and finds or builds the
 *       exit portal as usual, since only the target world changes
 *       ({@link EntityPortalReadyEvent}).</li>
 *   <li>End portals lead to the rotating End's obsidian platform once it has opened (the
 *       community dragon fight, {@link DragonFight}); until then they refuse with the time
 *       left. The exit portal takes players to their bed or the spawn.</li>
 * </ul>
 *
 * Items and mobs follow Nether portals the same way; End portals only take players.
 */
public final class DimensionRouter implements Listener {

    /** The End's spawn platform, where vanilla puts arrivals. */
    private static final int PLATFORM_X = 100;
    private static final int PLATFORM_Y = 49;
    private static final int PLATFORM_Z = 0;

    private final RapidHorizons plugin;
    private final FrontierService frontier;
    /** The overworld each player last left through a Nether portal. */
    private final Map<UUID, String> cameFrom = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastNotice = new ConcurrentHashMap<>();

    public DimensionRouter(RapidHorizons plugin, FrontierService frontier) {
        this.plugin = plugin;
        this.frontier = frontier;
    }

    /** On a server without the rotation (frontier.enabled off), portals work as in vanilla. */
    private boolean rotating() {
        return plugin.settings().frontier().enabled();
    }

    private boolean vanillaNetherOrEnd(World world) {
        String key = world.getKey().asString();
        return key.equals("minecraft:the_nether") || key.equals("minecraft:the_end");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortalReady(EntityPortalReadyEvent event) {
        if (event.getPortalType() != PortalType.NETHER || !rotating()) {
            return;
        }
        World from = event.getEntity().getWorld();
        Place place = frontier.place(from);
        switch (place) {
            case HEARTLAND, FRONTIER -> {
                World nether = frontier.nether();
                if (nether == null) {
                    event.setCancelled(true);
                    notice(event.getEntity(), "dimension.nether-closed");
                    return;
                }
                if (event.getEntity() instanceof Player p) {
                    cameFrom.put(p.getUniqueId(), from.getKey().asString());
                }
                event.setTargetWorld(nether);
            }
            case NETHER -> event.setTargetWorld(returnWorld(event.getEntity()));
            default -> {
                if (vanillaNetherOrEnd(from)) {
                    event.setTargetWorld(returnWorld(event.getEntity()));
                }
            }
        }
    }

    /** Where a Nether portal in the Nether leads: back where the player came from, else the Heartland. */
    private World returnWorld(org.bukkit.entity.Entity entity) {
        if (entity instanceof Player p) {
            String key = cameFrom.get(p.getUniqueId());
            World w = key == null ? null : FrontierWorlds.loaded(key);
            if (w != null && (frontier.place(w) == Place.HEARTLAND || frontier.place(w) == Place.FRONTIER)) {
                return w;
            }
        }
        World heartland = frontier.heartland();
        return heartland != null ? heartland : plugin.getServer().getWorlds().getFirst();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.END_PORTAL || !rotating()) {
            return;
        }
        Player player = event.getPlayer();
        World from = event.getFrom().getWorld();
        Place place = frontier.place(from);
        switch (place) {
            case HEARTLAND, FRONTIER -> {
                World end = frontier.end();
                if (end == null) {
                    event.setCancelled(true);
                    notice(player, "dimension.end-closed");
                    return;
                }
                if (!frontier.endOpen()) {
                    event.setCancelled(true);
                    notice(player, "dimension.end-not-yet");
                    return;
                }
                event.setTo(platform(end));
            }
            case END -> event.setTo(home(player));
            default -> {
                if (vanillaNetherOrEnd(from)) {
                    event.setTo(home(player));
                }
            }
        }
    }

    /** Items and mobs don't travel through End portals: the End only ever opens for players. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (event.getPortalType() != PortalType.ENDER || !rotating()) {
            return;
        }
        Place place = frontier.place(event.getFrom().getWorld());
        if (place != Place.OTHER || vanillaNetherOrEnd(event.getFrom().getWorld())) {
            event.setCancelled(true);
        }
    }

    /** The bed or anchor if it's still there, else the spawn. */
    private Location home(Player player) {
        Location bed = player.getRespawnLocation();
        if (bed != null && bed.getWorld() != null && frontier.place(bed.getWorld()) != Place.END) {
            return bed;
        }
        return plugin.core().worlds().spawn();
    }

    /** Vanilla's 5×5 obsidian platform with room above, built every time like vanilla does. */
    static Location platform(World end) {
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                Block floor = end.getBlockAt(PLATFORM_X + x, PLATFORM_Y - 1, PLATFORM_Z + z);
                if (floor.getType() != Material.OBSIDIAN) {
                    floor.setType(Material.OBSIDIAN);
                }
                for (int y = 0; y < 3; y++) {
                    Block air = end.getBlockAt(PLATFORM_X + x, PLATFORM_Y + y, PLATFORM_Z + z);
                    if (!air.getType().isAir()) {
                        air.setType(Material.AIR);
                    }
                }
            }
        }
        return new Location(end, PLATFORM_X + 0.5, PLATFORM_Y, PLATFORM_Z + 0.5, 90, 0);
    }

    private void notice(org.bukkit.entity.Entity entity, String key) {
        if (!(entity instanceof Player player)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastNotice.get(player.getUniqueId());
        if (last != null && now - last < 3000) {
            return;
        }
        lastNotice.put(player.getUniqueId(), now);
        Instant opens = frontier.current().flatMap(FrontierRow::endOpensAt).orElse(Instant.now());
        plugin.lang().send(player, key, Placeholder.unparsed("time",
                Durations.format(FrontierSchedule.until(Instant.now(), opens))));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastNotice.remove(event.getPlayer().getUniqueId());
    }
}
