package us.rapidskies.claims.protect;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import us.rapidskies.claims.ClaimService;
import us.rapidskies.claims.LandService;
import us.rapidskies.claims.RapidClaims;
import us.rapidskies.claims.model.ChunkKey;
import us.rapidskies.claims.model.Claim;
import us.rapidskies.claims.model.Flag;
import us.rapidskies.claims.model.LandIndex;
import us.rapidskies.claims.model.Owner;
import us.rapidskies.claims.model.Plot;

/**
 * Walking between claims (PLAN.md §4.1): players an owner keeps out can't walk, pearl,
 * chorus-fruit or teleport into that land, and everyone sees whose land they're entering on
 * the action bar (owner, plot, PvP on or off). Only runs when a move crosses a chunk border.
 */
public final class EntryListener implements Listener {

    private static final long DENY_MESSAGE_MS = 2000;

    private final RapidClaims plugin;
    private final ClaimService service;
    private final LandIndex land;
    private final Map<UUID, Long> lastDenied = new ConcurrentHashMap<>();

    public EntryListener(RapidClaims plugin) {
        this.plugin = plugin;
        this.service = plugin.service();
        this.land = plugin.land();
    }

    private boolean exempt(Player p) {
        return p.hasPermission(LandService.ENTRY_EXEMPT) || service.bypassing(p.getUniqueId());
    }

    private boolean deniedAt(Player p, Location to) {
        return to.getWorld() != null && service.claimable(to.getWorld()) && !exempt(p)
                && land.isDenied(p.getUniqueId(), ClaimService.chunk(to));
    }

    private void refuse(Player p, Location to) {
        long now = System.currentTimeMillis();
        Long last = lastDenied.get(p.getUniqueId());
        if (last == null || now - last > DENY_MESSAGE_MS) {
            lastDenied.put(p.getUniqueId(), now);
            land.owner(ClaimService.chunk(to)).ifPresent(owner -> p.sendActionBar(plugin.lang().get("entry.denied",
                    Placeholder.component("owner", plugin.ownerName(owner)))));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getWorld() == to.getWorld() && (from.getBlockX() >> 4) == (to.getBlockX() >> 4)
                && (from.getBlockZ() >> 4) == (to.getBlockZ() >> 4)) {
            return;
        }
        Player p = event.getPlayer();
        if (deniedAt(p, to)) {
            event.setCancelled(true);
            refuse(p, to);
            return;
        }
        if (plugin.settings().display().enterMessages() && to.getWorld() != null && service.claimable(to.getWorld())) {
            announce(p, from, to);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (deniedAt(event.getPlayer(), event.getTo())) {
            event.setCancelled(true);
            refuse(event.getPlayer(), event.getTo());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.lands().ejectIfDenied(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lastDenied.remove(event.getPlayer().getUniqueId());
    }

    /** "Entering Steve's land" when the owner (or plot) changes; "Wilderness" when leaving land. */
    private void announce(Player p, Location fromLoc, Location toLoc) {
        ChunkKey from = ClaimService.chunk(fromLoc);
        ChunkKey to = ClaimService.chunk(toLoc);
        Optional<Claim> before = from.world().equals(to.world()) ? land.claim(from) : Optional.empty();
        Optional<Claim> after = land.claim(to);
        Optional<Plot> plotBefore = land.plot(from);
        Optional<Plot> plotAfter = land.plot(to);
        boolean sameOwner = before.map(Claim::owner).equals(after.map(Claim::owner));
        boolean samePlot = plotBefore.map(Plot::resident).equals(plotAfter.map(Plot::resident));
        boolean samePvp = before.map(c -> c.flag(Flag.PVP)).equals(after.map(c -> c.flag(Flag.PVP)));
        if (sameOwner && samePlot && samePvp) {
            return;
        }
        if (after.isEmpty()) {
            p.sendActionBar(plugin.lang().get("entry.wilderness"));
            return;
        }
        Owner owner = after.get().owner();
        Component pvp = plugin.lang().get(after.get().flag(Flag.PVP) ? "entry.pvp-on" : "entry.pvp-off");
        if (plotAfter.isPresent()) {
            p.sendActionBar(plugin.lang().get("entry.plot", Placeholder.component("owner", plugin.ownerName(owner)),
                    Placeholder.unparsed("resident", plugin.names().name(plotAfter.get().resident())),
                    Placeholder.component("pvp", pvp)));
        } else {
            p.sendActionBar(plugin.lang().get("entry.land", Placeholder.component("owner", plugin.ownerName(owner)),
                    Placeholder.component("pvp", pvp)));
        }
    }
}
