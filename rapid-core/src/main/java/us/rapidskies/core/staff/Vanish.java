package us.rapidskies.core.staff;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.FixedMetadataValue;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.redis.Channels;
import us.rapidskies.common.redis.RedisBus;
import us.rapidskies.core.RapidCore;

/**
 * Vanish (PLAN.md §9). A vanished staff member is hidden from every player without
 * {@value #SEE}, mobs ignore them, and they don't pick items up or join/quit in chat.
 *
 * <p>The state is network-wide and survives relogs: it lives in the Redis set
 * {@link Channels#VANISHED_KEY}, checked before each login, and changes are announced on
 * {@link Channels#VANISH} so the proxy hides them from friend lists and {@code /msg}.
 * The {@code vanished} metadata is the convention TAB (through TAB-Bridge) and other
 * plugins read to hide a player from the tab list.
 */
public final class Vanish implements Listener {

    public static final String USE = "rapid.staff.vanish";
    public static final String SEE = "rapid.staff.vanish.see";
    private static final String METADATA = "vanished";

    private final RapidCore plugin;
    private final RedisBus redis;
    private final StaffLog log;
    private final Lang lang;
    /** Vanished players on this server. */
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();
    /** Found vanished at pre-login, applied on join. */
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();

    public Vanish(RapidCore plugin, RedisBus redis, StaffLog log, Lang lang) {
        this.plugin = plugin;
        this.redis = redis;
        this.log = log;
        this.lang = lang;
    }

    public boolean isVanished(Player player) {
        return vanished.contains(player.getUniqueId());
    }

    /** Whether {@code viewer} may see {@code target}. */
    public boolean canSee(Player viewer, Player target) {
        return !isVanished(target) || viewer.equals(target) || viewer.hasPermission(SEE);
    }

    public void toggle(Player player) {
        set(player, !isVanished(player));
    }

    public void set(Player player, boolean vanish) {
        if (vanish == isVanished(player)) {
            return;
        }
        if (vanish) {
            apply(player);
        } else {
            vanished.remove(player.getUniqueId());
            player.removeMetadata(METADATA, plugin);
            player.setAffectsSpawning(true);
            for (Player other : Bukkit.getOnlinePlayers()) {
                other.showPlayer(plugin, player);
            }
        }
        lang.send(player, vanish ? "staff.vanish.enabled" : "staff.vanish.disabled");
        log.log(player, vanish ? "vanish" : "unvanish", null, null);
        publish(player.getUniqueId(), vanish);
    }

    // Bukkit's metadata API is deprecated, but the "vanished" metadata is still how TAB-Bridge
    // and other plugins detect vanish.
    @SuppressWarnings("deprecation")
    private void apply(Player player) {
        vanished.add(player.getUniqueId());
        player.setMetadata(METADATA, new FixedMetadataValue(plugin, true));
        player.setAffectsSpawning(false);
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!canSee(other, player)) {
                other.hidePlayer(plugin, player);
            }
        }
    }

    private void publish(UUID player, boolean vanish) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (vanish) {
                    redis.addToSet(Channels.VANISHED_KEY, player.toString());
                } else {
                    redis.removeFromSet(Channels.VANISHED_KEY, player.toString());
                }
                redis.publishJson(Channels.VANISH, new Channels.VanishChanged(player.toString(), vanish));
            } catch (RuntimeException e) {
                plugin.getSLF4JLogger().warn("Couldn't save vanish state for {}: {}", player, e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        try {
            if (redis.isMember(Channels.VANISHED_KEY, event.getUniqueId().toString())) {
                pending.add(event.getUniqueId());
            }
        } catch (RuntimeException e) {
            plugin.getSLF4JLogger().warn("Couldn't check vanish state of {}: {}", event.getName(), e.getMessage());
        }
    }

    /** LOWEST so the player is hidden before anyone else's join handling runs. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player joined = event.getPlayer();
        for (UUID id : vanished) {
            Player staff = Bukkit.getPlayer(id);
            if (staff != null && !canSee(joined, staff)) {
                joined.hidePlayer(plugin, staff);
            }
        }
        if (pending.remove(joined.getUniqueId())) {
            if (joined.hasPermission(USE)) {
                apply(joined);
                event.joinMessage(null);
                lang.send(joined, "staff.vanish.still");
            } else {
                // Lost the permission since: make it official everywhere.
                publish(joined.getUniqueId(), false);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (vanished.remove(event.getPlayer().getUniqueId())) {
            event.quitMessage(null);
        }
    }

    /** A login that passed pre-login but never joined (whitelist, full server). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onConnectionClose(PlayerConnectionCloseEvent event) {
        pending.remove(event.getPlayerUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player p && isVanished(p)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player p && isVanished(p)) {
            event.setCancelled(true);
        }
    }

    /** Shows everyone again when rapid-core is disabled; the Redis state stays for next time. */
    public void showAll() {
        for (UUID id : vanished) {
            Player staff = Bukkit.getPlayer(id);
            if (staff != null) {
                staff.removeMetadata(METADATA, plugin);
                staff.setAffectsSpawning(true);
                Bukkit.getOnlinePlayers().forEach(o -> o.showPlayer(plugin, staff));
            }
        }
        vanished.clear();
    }

    /** Re-applies vanish to online staff after a reload (the Redis set is the truth). */
    public void restoreOnline() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Set<String> ids;
            try {
                ids = redis.members(Channels.VANISHED_KEY);
            } catch (RuntimeException e) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (ids.contains(p.getUniqueId().toString()) && p.hasPermission(USE)) {
                        apply(p);
                    }
                }
            });
        });
    }
}
