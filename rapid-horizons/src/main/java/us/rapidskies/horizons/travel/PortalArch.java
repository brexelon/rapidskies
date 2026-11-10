package us.rapidskies.horizons.travel;

import java.util.List;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import us.rapidskies.core.display.Labels;
import us.rapidskies.core.world.SpawnPoint;
import us.rapidskies.horizons.HorizonsSettings.Region;
import us.rapidskies.horizons.RapidHorizons;

/**
 * The arch at the spawn that leads into the Heartland ({@link PortalLayout}). Built from the
 * config at startup and on {@code /horizons reload} when {@code rtp.portal.build} is on,
 * setting only the blocks that differ, so it's in the world exactly as configured even after
 * the spawn is re-shipped. Walking into it is {@link RtpService}'s job.
 *
 * <p>The portal blocks are for looks: their frame isn't obsidian, so block updates are kept
 * from breaking them, and vanilla never gets to send anyone (or any item) to the Nether.
 *
 * <p>The floating text in front of it ({@code rtp.portal.hologram}, lang
 * {@code travel.portal-hologram}) is a text display, spawned fresh on every start and reload.
 */
public final class PortalArch implements Listener {

    private final RapidHorizons plugin;
    private final Labels labels;

    public PortalArch(RapidHorizons plugin) {
        this.plugin = plugin;
        this.labels = new Labels(plugin);
    }

    private Optional<Region> region() {
        return plugin.settings().rtp().portal();
    }

    /** Builds the arch and shows the hologram, as far as the config asks for them. Main thread. */
    public void build() {
        buildArch();
        showHologram();
    }

    /** Removes the hologram (the arch's blocks stay). Main thread. */
    public void clear() {
        labels.removeAll();
    }

    private void showHologram() {
        labels.removeAll();
        Optional<SpawnPoint> point = plugin.settings().rtp().portalHologram();
        if (point.isEmpty()) {
            return;
        }
        Location at = Labels.location(point.get());
        if (at == null) {
            plugin.getSLF4JLogger().error("rtp.portal.hologram: world '{}' isn't loaded, so there's no hologram",
                    point.get().world());
            return;
        }
        labels.spawn(at, plugin.lang().get("travel.portal-hologram"));
    }

    private void buildArch() {
        var rtp = plugin.settings().rtp();
        if (rtp.portal().isEmpty() || !rtp.portalBuild()) {
            return;
        }
        Region region = rtp.portal().get();
        NamespacedKey key = NamespacedKey.fromString(region.world());
        World world = key == null ? null : Bukkit.getWorld(key);
        if (world == null) {
            plugin.getSLF4JLogger().error("rtp.portal.region: world '{}' isn't loaded, so the arch isn't built",
                    region.world());
            return;
        }
        Optional<List<PortalLayout.Placement>> layout = PortalLayout.of(region);
        if (layout.isEmpty()) {
            plugin.getSLF4JLogger().warn("rtp.portal.region isn't one block thick, so no arch is built around it "
                    + "(set rtp.portal.build: false if the arch is hand-built)");
            return;
        }
        int changed = 0;
        for (PortalLayout.Placement p : layout.get()) {
            BlockData data = Bukkit.createBlockData(p.data());
            Block block = world.getBlockAt(p.x(), p.y(), p.z());
            if (!block.getBlockData().matches(data)) {
                // No physics: the portal blocks would break at once without an obsidian frame.
                block.setBlockData(data, false);
                changed++;
            }
        }
        if (changed > 0) {
            plugin.getSLF4JLogger().info("Built the Heartland portal arch at {} ({} blocks set)", region, changed);
        }
    }

    /** Coordinates first: physics events are frequent, and the world key is a new string each time. */
    private boolean inRegion(Block block) {
        Optional<Region> region = region();
        if (region.isEmpty()) {
            return false;
        }
        Region r = region.get();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        return x >= r.minX() && x <= r.maxX() && y >= r.minY() && y <= r.maxY() && z >= r.minZ() && z <= r.maxZ()
                && block.getWorld().getKey().asString().equals(r.world());
    }

    /** A neighbour update would otherwise pop the frameless portal blocks. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        Block block = event.getBlock();
        if (inRegion(block) && block.getType() == Material.NETHER_PORTAL) {
            event.setCancelled(true);
        }
    }

    /** Standing in the arch only ever means the Heartland (RtpService), never the Nether. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (inRegion(event.getFrom().getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (inRegion(event.getFrom().getBlock())) {
            event.setCancelled(true);
        }
    }
}
