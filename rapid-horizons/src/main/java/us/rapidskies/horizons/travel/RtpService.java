package us.rapidskies.horizons.travel;

import java.time.Duration;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import us.rapidskies.common.time.Durations;
import us.rapidskies.essentials.teleport.SafeBlocks;
import us.rapidskies.horizons.HorizonsSettings;
import us.rapidskies.horizons.RapidHorizons;
import us.rapidskies.horizons.travel.RtpSearch.Point;

/**
 * The way from the Horizons spawn into the Heartland (closes the phase 2 open item):
 * {@code /rtp} and an optional portal region at the spawn both drop the player on a random
 * safe surface spot, never in or next to someone's claim.
 *
 * <p>Chunks load asynchronously ({@code getChunkAtAsync}), one try at a time, so a search
 * never stalls the server; the Heartland is pre-generated with Chunky, so loads are reads,
 * not generation. Cooldowns live in memory: a restart clears them, which costs nothing.
 */
public final class RtpService implements Listener {

    public static final String NO_COOLDOWN = "rapid.horizons.rtp.nocooldown";

    private final RapidHorizons plugin;
    private final Random random = new Random();
    private final Set<UUID> searching = ConcurrentHashMap.newKeySet();
    /** When each player may /rtp again (millis). Kept across relogs on purpose. */
    private final Map<UUID, Long> cooldownUntil = new ConcurrentHashMap<>();
    /** Last portal trigger (millis), so standing in the arch doesn't fire every step. */
    private final Map<UUID, Long> portalGuard = new ConcurrentHashMap<>();

    public RtpService(RapidHorizons plugin) {
        this.plugin = plugin;
    }

    private HorizonsSettings.Rtp settings() {
        return plugin.settings().rtp();
    }

    /** Starts a search for {@code player}; from the portal the cooldown may be skipped (config). */
    public void start(Player player, boolean fromPortal) {
        HorizonsSettings.Rtp rtp = settings();
        UUID id = player.getUniqueId();
        if (plugin.pvp().inCombat(id)) {
            plugin.lang().send(player, "pvp.no-teleport");
            return;
        }
        boolean skipCooldown = player.hasPermission(NO_COOLDOWN) || (fromPortal && rtp.portalIgnoresCooldown());
        long now = System.currentTimeMillis();
        Long until = cooldownUntil.get(id);
        if (!skipCooldown && until != null && until > now) {
            plugin.lang().send(player, "travel.cooldown",
                    Placeholder.unparsed("time", Durations.format(Duration.ofMillis(until - now))));
            return;
        }
        NamespacedKey key = NamespacedKey.fromString(rtp.world());
        World world = key == null ? null : Bukkit.getWorld(key);
        if (world == null) {
            plugin.getSLF4JLogger().error("rtp.world '{}' isn't loaded", rtp.world());
            plugin.lang().send(player, "travel.unavailable");
            return;
        }
        search(player, world, rtp.centerX(), rtp.centerZ(), rtp.minRadius(), rtp.maxRadius(), true, "travel.searching", spot -> {
            cooldownUntil.put(id, System.currentTimeMillis() + settings().cooldownSeconds() * 1000L);
            plugin.lang().send(player, "travel.arrived",
                    Placeholder.unparsed("x", Integer.toString(spot.getBlockX())),
                    Placeholder.unparsed("z", Integer.toString(spot.getBlockZ())));
        });
    }

    /**
     * Finds a random safe surface spot in {@code world} between {@code minRadius} and
     * {@code maxRadius} blocks of the centre (and inside the border), loading one chunk at a
     * time asynchronously, and sends {@code player} there; {@code arrived} runs once they are.
     * Also used for the Frontier ({@code /frontier go}). One search per player at a time.
     *
     * @param avoidClaims  never land in or next to a claimed chunk
     * @param searchingKey lang key told while searching
     */
    public void search(Player player, World world, int centerX, int centerZ, int minRadius, int maxRadius,
                       boolean avoidClaims, String searchingKey, Consumer<Location> arrived) {
        UUID id = player.getUniqueId();
        if (!searching.add(id)) {
            plugin.lang().send(player, "travel.already-searching");
            return;
        }
        plugin.lang().send(player, searchingKey);
        attempt(new Search(player, world, centerX, centerZ, minRadius, maxRadius, avoidClaims, arrived),
                settings().attempts());
    }

    private record Search(Player player, World world, int centerX, int centerZ, int minRadius, int maxRadius,
                          boolean avoidClaims, Consumer<Location> arrived) {
    }

    private void attempt(Search s, int left) {
        Player player = s.player();
        World world = s.world();
        if (!player.isOnline()) {
            searching.remove(player.getUniqueId());
            return;
        }
        // Cheap rejections first (border, claims), without loading anything.
        Point found = null;
        int tries = left;
        int reach = reach(world, s.maxRadius());
        while (tries > 0 && found == null) {
            tries--;
            Point candidate = RtpSearch.candidate(random, s.centerX(), s.centerZ(), Math.min(s.minRadius(), reach - 1),
                    reach);
            if (world.getWorldBorder().isInside(new Location(world, candidate.x() + 0.5, 64, candidate.z() + 0.5))
                    && !(s.avoidClaims() && claimedNear(world, candidate))) {
                found = candidate;
            }
        }
        if (found == null) {
            fail(player);
            return;
        }
        Point target = found;
        int remaining = tries;
        // Callback runs on the main thread once the chunk is loaded.
        world.getChunkAtAsync(target.x() >> 4, target.z() >> 4, true, chunk -> {
            Location spot = surface(world, target);
            if (spot != null) {
                land(player, spot, s.arrived());
            } else if (remaining > 0) {
                attempt(s, remaining);
            } else {
                fail(player);
            }
        });
    }

    /** Never further than the border allows (minus a margin), so tries aren't wasted outside it. */
    private static int reach(World world, int maxRadius) {
        int half = (int) (world.getWorldBorder().getSize() / 2) - 16;
        return Math.max(1, Math.min(maxRadius, half));
    }

    /** The target's chunk, or any chunk touching it, is claimed: land elsewhere, not at someone's door. */
    private boolean claimedNear(World world, Point p) {
        for (int dx = -16; dx <= 16; dx += 16) {
            for (int dz = -16; dz <= 16; dz += 16) {
                if (plugin.land().isClaimed(world, p.x() + dx, p.z() + dz)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A safe standing spot at the surface of {@code p}, or null. Main thread, chunk loaded. */
    private static Location surface(World world, Point p) {
        Block ground = world.getHighestBlockAt(p.x(), p.z(), HeightMap.MOTION_BLOCKING);
        if (ground.getY() <= world.getMinHeight() || !ground.getType().isSolid()
                || !SafeBlocks.safeGround(ground.getType().name())) {
            return null;
        }
        Block feet = ground.getRelative(0, 1, 0);
        Block head = ground.getRelative(0, 2, 0);
        if (!feet.isPassable() || !head.isPassable() || feet.isLiquid() || head.isLiquid()
                || !SafeBlocks.safeSpace(feet.getType().name()) || !SafeBlocks.safeSpace(head.getType().name())) {
            return null;
        }
        float yaw = (float) (java.util.concurrent.ThreadLocalRandom.current().nextDouble() * 360 - 180);
        return new Location(world, p.x() + 0.5, ground.getY() + 1, p.z() + 0.5, yaw, 0);
    }

    private void land(Player player, Location spot, Consumer<Location> arrived) {
        UUID id = player.getUniqueId();
        if (!player.isOnline()) {
            searching.remove(id);
            return;
        }
        player.setFallDistance(0);
        player.teleportAsync(spot, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(ok -> {
            searching.remove(id);
            if (!ok) {
                plugin.lang().send(player, "travel.failed");
                return;
            }
            arrived.accept(spot);
        });
    }

    private void fail(Player player) {
        searching.remove(player.getUniqueId());
        plugin.lang().send(player, "travel.failed");
    }

    // ------------------------------------------------------------------ portal

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        var portal = settings().portal();
        if (portal.isEmpty() || !event.hasChangedBlock()) {
            return;
        }
        Location to = event.getTo();
        if (!portal.get().contains(to.getWorld().getKey().asString(), to.getBlockX(), to.getBlockY(), to.getBlockZ())) {
            return;
        }
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        Long last = portalGuard.get(player.getUniqueId());
        if (last != null && now - last < settings().portalRetriggerSeconds() * 1000L) {
            return;
        }
        portalGuard.put(player.getUniqueId(), now);
        start(player, true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        searching.remove(event.getPlayer().getUniqueId());
        portalGuard.remove(event.getPlayer().getUniqueId());
    }
}
