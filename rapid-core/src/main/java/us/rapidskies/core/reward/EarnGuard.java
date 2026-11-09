package us.rapidskies.core.reward;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
import us.rapidskies.common.redis.Channels;
import us.rapidskies.common.redis.RedisBus;
import us.rapidskies.core.afk.AfkTracker;

/**
 * Anti-farm rules for Bolts earned by playing (PLAN.md §6.2): nothing while AFK, nothing for
 * an account that shares its IP with one that connected earlier (the proxy decides which,
 * {@link Channels#SHARED_IP_KEY}), and a daily cap per reason across the whole network.
 * Every activity reward (playtime now; quests, streaks and levels later) goes through
 * {@link #award}.
 */
public final class EarnGuard implements Listener {

    private static final long REFRESH_SECONDS = 60;

    private final Plugin plugin;
    private final Database db;
    private final Ledger bolts;
    private final RedisBus redis;
    private final AfkTracker afk;
    private final String serverId;
    private final Set<UUID> sharedIp = ConcurrentHashMap.newKeySet();
    private BukkitTask refresh;

    public EarnGuard(Plugin plugin, Database db, Ledger bolts, RedisBus redis, AfkTracker afk, String serverId) {
        this.plugin = plugin;
        this.db = db;
        this.bolts = bolts;
        this.redis = redis;
        this.afk = afk;
        this.serverId = serverId;
    }

    public void start() {
        refresh = Bukkit.getScheduler().runTaskTimer(plugin, () -> refresh(onlineIds()), 1, REFRESH_SECONDS * 20);
    }

    public void stop() {
        if (refresh != null) {
            refresh.cancel();
        }
    }

    /** Whether activity rewards may go to this player right now (main thread). */
    public boolean mayEarn(Player player) {
        return !afk.isAfk(player) && !sharedIp.contains(player.getUniqueId());
    }

    public boolean sharesIp(Player player) {
        return sharedIp.contains(player.getUniqueId());
    }

    /**
     * Pays up to {@code amount} Bolts for {@code reason}, keeping the player's total for that
     * reason today (UTC) at or under {@code dailyCap} (0 for no cap). Call on the main
     * thread; completes with what was actually paid (0 if blocked, capped, or
     * {@code txnId} was already used).
     */
    public CompletableFuture<Long> award(Player player, long amount, String reason, long dailyCap, String txnId) {
        if (!mayEarn(player)) {
            return CompletableFuture.completedFuture(0L);
        }
        return pay(player.getUniqueId(), amount, reason, dailyCap, txnId);
    }

    /** {@link #award} without the AFK and shared-IP checks, for callers that already made them. */
    CompletableFuture<Long> pay(UUID player, long amount, String reason, long dailyCap, String txnId) {
        return db.async(() -> {
            long pay = amount;
            if (dailyCap > 0) {
                long earned = bolts.earnedSince(player, reason, startOfToday());
                pay = Math.min(amount, dailyCap - earned);
            }
            if (pay <= 0) {
                return 0L;
            }
            return bolts.credit(player, pay, reason, serverId, txnId) instanceof Ledger.Result.Applied ? pay : 0L;
        });
    }

    static Instant startOfToday() {
        return LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        refresh(List.of(event.getPlayer().getUniqueId()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        sharedIp.remove(event.getPlayer().getUniqueId());
    }

    private List<UUID> onlineIds() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).toList();
    }

    private void refresh(Collection<UUID> players) {
        if (players.isEmpty()) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                for (UUID id : players) {
                    if (redis.isMember(Channels.SHARED_IP_KEY, id.toString())) {
                        sharedIp.add(id);
                    } else {
                        sharedIp.remove(id);
                    }
                }
            } catch (RuntimeException e) {
                // Keep the last known flags; Redis being down shouldn't hand alts Bolts.
                plugin.getSLF4JLogger().warn("Couldn't refresh shared-IP flags: {}", e.getMessage());
            }
        });
    }
}
