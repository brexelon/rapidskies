package us.rapidskies.core.economy;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import us.rapidskies.common.db.Database;
import us.rapidskies.common.economy.Ledger;

/**
 * Bolts balances of the players online here, for displays that read them every tick (the
 * sidebar's {@code %rapid_bolts%}). Loaded on join and re-read for everyone online in one
 * query every {@link #REFRESH_TICKS}, so a store purchase or a payout on another server
 * shows up within a minute. Spending never reads this: it always goes through the
 * {@link Ledger}.
 */
public final class BoltsCache implements Listener {

    private static final long REFRESH_TICKS = 60 * 20;

    private final Plugin plugin;
    private final Database db;
    private final Ledger bolts;
    private final Map<UUID, Long> cached = new ConcurrentHashMap<>();
    private BukkitTask task;

    public BoltsCache(Plugin plugin, Database db, Ledger bolts) {
        this.plugin = plugin;
        this.db = db;
        this.bolts = bolts;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, REFRESH_TICKS, REFRESH_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        cached.clear();
    }

    /** The last balance read for a player online here, if it has been read yet. */
    public OptionalLong cached(UUID player) {
        Long c = cached.get(player);
        return c == null ? OptionalLong.empty() : OptionalLong.of(c);
    }

    /** Re-reads one player's balance now (after a change this server made). */
    public void refresh(UUID player) {
        load(List.of(player));
    }

    private void refreshAll() {
        List<UUID> online = Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).toList();
        if (!online.isEmpty()) {
            load(online);
        }
    }

    private void load(List<UUID> players) {
        db.async(() -> bolts.balances(players)).whenComplete((balances, error) -> {
            if (error != null) {
                plugin.getSLF4JLogger().warn("Couldn't refresh Bolts balances for the sidebar: {}", error.getMessage());
                return;
            }
            for (UUID id : players) {
                // Only players still online: a late answer mustn't bring back someone who left.
                if (Bukkit.getPlayer(id) != null) {
                    cached.put(id, balances.getOrDefault(id, 0L));
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cached.remove(event.getPlayer().getUniqueId());
    }
}
