package us.rapidskies.essentials.teleport;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import us.rapidskies.essentials.RapidEssentials;

/**
 * {@code /back}: returns a player to where they were before their last teleport by command
 * or plugin ({@code /spawn}, {@code /home}, {@code /tpa}, {@code /rtp}, {@code /town warp},
 * {@code /back} itself, so two in a row swap between places) or, if {@code back.on-death} is
 * on, to where they died. Whichever happened last wins.
 *
 * <p>Spots with nothing solid underneath (a fall off a sky island that the void rescue
 * caught) are never remembered, and on the way back the player lands on the nearest safe
 * footing in the same column, or is told the spot isn't safe any more (died in lava). Other
 * plugins can rewrite or drop a remembered origin with {@link #addOriginFilter}: Horizons
 * turns a walk through its spawn portal into "back to the spawn". Kept in memory: a restart
 * forgets them.
 */
public final class BackService implements Listener {

    public static final String PERMISSION = "rapid.command.back";

    /** Same-world teleports shorter than this aren't worth going back from (blocks). */
    private static final double MIN_DISTANCE = 6;

    /** A remembered spot. The world by id, so a retired Frontier world is never held on to. */
    private record Spot(UUID world, double x, double y, double z, float yaw, float pitch, boolean death) {
    }

    private record Filter(Plugin owner, UnaryOperator<Location> rewrite) {
    }

    private final RapidEssentials plugin;
    private final Map<UUID, Spot> last = new ConcurrentHashMap<>();
    private final List<Filter> filters = new CopyOnWriteArrayList<>();

    public BackService(RapidEssentials plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ API

    /**
     * Rewrites where {@code /back} returns to after a teleport that started at a given spot:
     * the filter gets that spot and returns the one to remember (itself to keep it, null to
     * remember nothing). Removed when {@code owner} disables.
     */
    public void addOriginFilter(Plugin owner, UnaryOperator<Location> rewrite) {
        filters.add(new Filter(owner, rewrite));
    }

    /** Makes {@code at} the place {@code /back} returns {@code player} to. */
    public void remember(Player player, Location at) {
        remember(player, at, false);
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        filters.removeIf(f -> f.owner().equals(event.getPlugin()));
    }

    // ------------------------------------------------------------------ remembering

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (cause != PlayerTeleportEvent.TeleportCause.COMMAND && cause != PlayerTeleportEvent.TeleportCause.PLUGIN) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getWorld().equals(to.getWorld()) && from.distanceSquared(to) < MIN_DISTANCE * MIN_DISTANCE) {
            return;
        }
        Location origin = from;
        for (Filter f : filters) {
            try {
                origin = f.rewrite().apply(origin);
            } catch (RuntimeException e) {
                plugin.getSLF4JLogger().warn("/back origin filter from {} failed", f.owner().getName(), e);
            }
            if (origin == null) {
                return;
            }
        }
        if (SafeSpot.hasGround(origin)) {
            remember(event.getPlayer(), origin, false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (!plugin.settings().back().onDeath()) {
            return;
        }
        Location at = event.getPlayer().getLocation();
        if (SafeSpot.hasGround(at)) {
            remember(event.getPlayer(), at, true);
        }
    }

    private void remember(Player player, Location at, boolean death) {
        last.put(player.getUniqueId(), new Spot(at.getWorld().getUID(), at.getX(), at.getY(), at.getZ(), at.getYaw(),
                at.getPitch(), death));
    }

    // ------------------------------------------------------------------ /back

    public void back(Player player) {
        UUID id = player.getUniqueId();
        Spot spot = last.get(id);
        if (spot == null) {
            plugin.lang().send(player, "back.none");
            return;
        }
        if (Bukkit.getWorld(spot.world()) == null) {
            // A Frontier that has since been retired.
            last.remove(id);
            plugin.lang().send(player, "back.gone");
            return;
        }
        plugin.teleports().teleport(player, TeleportKind.BACK, () -> location(spot), true,
                () -> plugin.lang().send(player, spot.death() ? "back.returned-death" : "back.returned"));
    }

    /** Looked up again when the teleport happens (after any warmup): null if the world is gone. */
    private static Location location(Spot spot) {
        World world = Bukkit.getWorld(spot.world());
        return world == null ? null : new Location(world, spot.x(), spot.y(), spot.z(), spot.yaw(), spot.pitch());
    }
}
