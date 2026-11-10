package us.rapidskies.claims.display;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import us.rapidskies.claims.ClaimService;
import us.rapidskies.claims.RapidClaims;
import us.rapidskies.claims.model.ChunkKey;
import us.rapidskies.claims.model.Claim;
import us.rapidskies.claims.model.LandIndex;
import us.rapidskies.claims.model.Owner;
import us.rapidskies.claims.model.Role;

/**
 * Shows claim borders (PLAN.md §4.1, claiming UX): particle walls along every edge where
 * ownership changes, plus a client-side outline of blocks on the ground, both only for the
 * viewing player. Particles and fake blocks work the same on every client from 1.20 up.
 *
 * <p>The outline materials differ in texture as well as color (gold: yours, diamond: your
 * town's, iron: land you're trusted on, redstone: someone else's), so they read without
 * relying on color; {@code /claim show} prints the legend.
 */
public final class BorderDisplay implements Listener {

    private static final int RADIUS_CHUNKS = 3;
    private static final int FLASH_SECONDS = 5;

    /** How a piece of land relates to the viewer. */
    public enum Relation {
        OWN(Material.GOLD_BLOCK, Color.fromRGB(0xFFD800)),
        TOWN(Material.DIAMOND_BLOCK, Color.fromRGB(0x55FFFF)),
        TRUSTED(Material.IRON_BLOCK, Color.fromRGB(0xFFFFFF)),
        OTHER(Material.REDSTONE_BLOCK, Color.fromRGB(0xFF5555));

        final Material outline;
        final Color color;

        Relation(Material outline, Color color) {
            this.outline = outline;
            this.color = color;
        }
    }

    private record Showing(long until, List<Segment> segments, List<Location> faked) {
    }

    /**
     * One chunk edge to draw: particles from (x1, z1) to (x2, z2) in block coordinates;
     * {@code inside} is the row of blocks just inside the chunk along that edge (a z for
     * edges running along x, an x otherwise), where the ground outline goes.
     */
    private record Segment(World world, double x1, double z1, double x2, double z2, int inside, Relation relation) {
    }

    private final RapidClaims plugin;
    private final LandIndex land;
    private final Map<UUID, Showing> showing = new HashMap<>();
    private BukkitTask task;

    public BorderDisplay(RapidClaims plugin) {
        this.plugin = plugin;
        this.land = plugin.land();
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
        for (UUID id : List.copyOf(showing.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                hide(p);
            }
        }
        showing.clear();
    }

    public Relation relation(UUID viewer, Claim claim) {
        Owner owner = claim.owner();
        if (owner.equals(Owner.player(viewer))) {
            return Relation.OWN;
        }
        if (owner.isTown() && land.townOf(viewer).map(t -> t.id().equals(owner.id())).orElse(false)) {
            return land.plot(claim.chunk()).map(p -> p.resident().equals(viewer)).orElse(false) ? Relation.OWN
                    : Relation.TOWN;
        }
        return land.role(viewer, claim.chunk()).atLeast(Role.TRUSTED) ? Relation.TRUSTED : Relation.OTHER;
    }

    /** {@code /claim show}: every border near the player for the configured time. */
    public void show(Player p) {
        hide(p);
        ChunkKey center = ClaimService.chunk(p.getLocation());
        List<Segment> segments = new ArrayList<>();
        for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {
            for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
                land.claim(center.offset(dx, dz)).ifPresent(c -> segments.addAll(edges(p, c, false)));
            }
        }
        start(p, segments, plugin.settings().display().borderSeconds());
    }

    /** Briefly outlines one chunk (just claimed, say). */
    public void flash(Player p, ChunkKey chunk) {
        land.claim(chunk).ifPresent(c -> {
            hide(p);
            start(p, edges(p, c, true), FLASH_SECONDS);
        });
    }

    private void start(Player p, List<Segment> segments, int seconds) {
        List<Location> faked = outline(p, segments);
        showing.put(p.getUniqueId(), new Showing(System.currentTimeMillis() + seconds * 1000L, segments, faked));
        draw(p, segments);
    }

    /** The edges of {@code c} that border different land ({@code all}: every edge). */
    private List<Segment> edges(Player viewer, Claim c, boolean all) {
        World world = Bukkit.getWorld(org.bukkit.NamespacedKey.fromString(c.chunk().world()));
        if (world == null) {
            return List.of();
        }
        Relation rel = relation(viewer.getUniqueId(), c);
        int x0 = c.chunk().x() << 4;
        int z0 = c.chunk().z() << 4;
        List<Segment> out = new ArrayList<>(4);
        if (all || differs(c, c.chunk().offset(0, -1))) {
            out.add(new Segment(world, x0, z0, x0 + 16, z0, z0, rel));
        }
        if (all || differs(c, c.chunk().offset(0, 1))) {
            out.add(new Segment(world, x0, z0 + 16, x0 + 16, z0 + 16, z0 + 15, rel));
        }
        if (all || differs(c, c.chunk().offset(-1, 0))) {
            out.add(new Segment(world, x0, z0, x0, z0 + 16, x0, rel));
        }
        if (all || differs(c, c.chunk().offset(1, 0))) {
            out.add(new Segment(world, x0 + 16, z0, x0 + 16, z0 + 16, x0 + 15, rel));
        }
        return out;
    }

    private boolean differs(Claim c, ChunkKey neighbour) {
        Optional<Claim> n = land.claim(neighbour);
        return n.isEmpty() || !n.get().owner().equals(c.owner())
                || !land.plot(neighbour).map(p -> p.resident()).equals(land.plot(c.chunk()).map(p -> p.resident()));
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (var e : List.copyOf(showing.entrySet())) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) {
                showing.remove(e.getKey());
            } else if (now >= e.getValue().until()) {
                hide(p);
            } else {
                draw(p, e.getValue().segments());
            }
        }
    }

    private static void draw(Player p, List<Segment> segments) {
        double y = p.getLocation().getY() + 1.2;
        for (Segment s : segments) {
            if (s.world() != p.getWorld()) {
                continue;
            }
            Particle.DustOptions dust = new Particle.DustOptions(s.relation().color, 1.3f);
            double len = Math.max(Math.abs(s.x2() - s.x1()), Math.abs(s.z2() - s.z1()));
            for (double t = 0; t <= len; t += 1.0) {
                double x = s.x1() + (s.x2() - s.x1()) * t / len;
                double z = s.z1() + (s.z2() - s.z1()) * t / len;
                p.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, dust);
            }
        }
    }

    /** Swaps the ground under each border edge for the relation's block, for this player only. */
    private static List<Location> outline(Player p, List<Segment> segments) {
        List<BlockState> fake = new ArrayList<>();
        List<Location> where = new ArrayList<>();
        for (Segment s : segments) {
            if (s.world() != p.getWorld()) {
                continue;
            }
            boolean alongX = s.z1() == s.z2();
            int from = (int) Math.min(alongX ? s.x1() : s.z1(), alongX ? s.x2() : s.z2());
            for (int i = from; i < from + 16; i++) {
                int bx = alongX ? i : s.inside();
                int bz = alongX ? s.inside() : i;
                if (!s.world().isChunkLoaded(bx >> 4, bz >> 4)) {
                    continue;
                }
                Block ground = s.world().getHighestBlockAt(bx, bz);
                BlockState state = ground.getState();
                state.setType(s.relation().outline);
                fake.add(state);
                where.add(ground.getLocation());
            }
        }
        if (!fake.isEmpty()) {
            p.sendBlockChanges(fake);
        }
        return where;
    }

    private void hide(Player p) {
        Showing s = showing.remove(p.getUniqueId());
        if (s == null || s.faked().isEmpty()) {
            return;
        }
        List<BlockState> real = new ArrayList<>(s.faked().size());
        for (Location l : s.faked()) {
            if (l.getWorld() == p.getWorld() && l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) {
                real.add(l.getBlock().getState());
            }
        }
        if (!real.isEmpty()) {
            p.sendBlockChanges(real);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        showing.remove(event.getPlayer().getUniqueId());
    }
}
