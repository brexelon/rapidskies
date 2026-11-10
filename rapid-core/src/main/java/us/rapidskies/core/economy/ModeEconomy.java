package us.rapidskies.core.economy;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.Plugin;
import us.rapidskies.common.economy.EconomyMode;
import us.rapidskies.common.economy.Ledger;
import us.rapidskies.common.redis.Channels;
import us.rapidskies.common.redis.RedisBus;

/**
 * This server's gamemode currency (Coins on SkyRealms, Crowns on Horizons; PLAN.md §5),
 * shaped for synchronous callers such as the Vault API (PLAN.md §4: per-mode Vault
 * economy provider).
 *
 * <p>Balances of online players are cached, loaded before they join, so reads never touch
 * the database on the main thread. Every change still goes straight through the
 * {@link Ledger}: its guarded debit is what makes double-spends and duplication impossible,
 * and a cache can't give that across servers. That means a deposit or withdrawal blocks the
 * calling thread for one short transaction (a few milliseconds on the node's MariaDB). That
 * cost is accepted because economy calls are rare (a shop sale, an island upgrade), not
 * per-tick. Other servers that change a balance announce it on {@link Channels#MODE_BALANCE}
 * and this server reloads its copy.
 */
public final class ModeEconomy implements Listener {

    private final Plugin plugin;
    private final EconomyMode mode;
    private final Ledger ledger;
    private final RedisBus redis;
    private final String serverId;
    private final Map<UUID, Long> cached = new ConcurrentHashMap<>();

    public ModeEconomy(Plugin plugin, EconomyMode mode, Ledger ledger, RedisBus redis, String serverId) {
        this.plugin = plugin;
        this.mode = mode;
        this.ledger = ledger;
        this.redis = redis;
        this.serverId = serverId;
        redis.subscribeJson(Channels.MODE_BALANCE, Channels.ModeBalanceChanged.class, this::onRemoteChange);
    }

    public EconomyMode mode() {
        return mode;
    }

    /** The outcome of a change: whether it happened, and the balance afterwards. */
    public record Change(boolean success, long balance) {
    }

    /** The cached balance of a player online here, without touching the database. */
    public java.util.OptionalLong cachedBalance(UUID player) {
        Long c = cached.get(player);
        return c == null ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(c);
    }

    /** Cached for online players; a database read for anyone else. */
    public long balance(UUID player) throws SQLException {
        Long c = cached.get(player);
        return c != null ? c : ledger.balance(player);
    }

    public Change deposit(UUID player, long amount, String reason) throws SQLException {
        if (amount == 0) {
            return new Change(true, balance(player));
        }
        return applied(player, ledger.credit(player, amount, reason, serverId, null));
    }

    public Change withdraw(UUID player, long amount, String reason) throws SQLException {
        if (amount == 0) {
            return new Change(true, balance(player));
        }
        return applied(player, ledger.debit(player, amount, reason, serverId, null));
    }

    /**
     * Moves {@code amount} between two accounts in one transaction (a player paying into a
     * town bank, a bank paying a mayor out). Accounts needn't be players. Blocks: call off
     * the main thread. Returns the payer's balance afterwards.
     */
    public Change transfer(UUID from, UUID to, long amount, String reason, String txnId) throws SQLException {
        return switch (ledger.transfer(from, to, amount, reason, serverId, txnId)) {
            case Ledger.Transfer.Applied a -> {
                cached.computeIfPresent(from, (k, old) -> a.fromBalance());
                cached.computeIfPresent(to, (k, old) -> a.toBalance());
                announce(from, a.fromBalance());
                announce(to, a.toBalance());
                yield new Change(true, a.fromBalance());
            }
            case Ledger.Transfer.Insufficient i -> {
                cached.computeIfPresent(from, (k, old) -> i.fromBalance());
                yield new Change(false, i.fromBalance());
            }
            case Ledger.Transfer.Duplicate d -> new Change(false, balance(from));
        };
    }

    /** Like {@link #withdraw}, with a {@code txnId} so a retried charge (daily upkeep) is applied once. */
    public Change withdraw(UUID player, long amount, String reason, String txnId) throws SQLException {
        return applied(player, ledger.debit(player, amount, reason, serverId, txnId));
    }

    /** Like {@link #deposit}, with a {@code txnId} so a retried payment is applied once. */
    public Change deposit(UUID player, long amount, String reason, String txnId) throws SQLException {
        return applied(player, ledger.credit(player, amount, reason, serverId, txnId));
    }

    /**
     * For plugins that change balances through the {@link Ledger} inside their own
     * transaction (rapid-market): updates the cached balance and tells the other servers,
     * as the methods here do after their own changes. Any thread.
     */
    public void changed(UUID player, long balance) {
        cached.computeIfPresent(player, (k, old) -> balance);
        announce(player, balance);
    }

    private Change applied(UUID player, Ledger.Result result) {
        return switch (result) {
            case Ledger.Result.Applied a -> {
                cached.computeIfPresent(player, (k, old) -> a.balance());
                announce(player, a.balance());
                yield new Change(true, a.balance());
            }
            case Ledger.Result.Insufficient i -> {
                cached.computeIfPresent(player, (k, old) -> i.balance());
                yield new Change(false, i.balance());
            }
            case Ledger.Result.Duplicate d -> new Change(false, d.balance());
        };
    }

    private void announce(UUID player, long balance) {
        Channels.ModeBalanceChanged msg = new Channels.ModeBalanceChanged(mode.id(), player.toString(), balance, serverId);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                redis.publishJson(Channels.MODE_BALANCE, msg);
            } catch (RuntimeException e) {
                plugin.getSLF4JLogger().warn("Couldn't announce {} balance change for {}: {}", mode.id(), player,
                        e.getMessage());
            }
        });
    }

    // MONITOR: only logins that will go ahead. Async thread, so blocking is fine.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        try {
            cached.put(event.getUniqueId(), ledger.balance(event.getUniqueId()));
        } catch (SQLException e) {
            // Reads fall back to the database for this player.
            plugin.getSLF4JLogger().warn("Couldn't load the {} balance of {}: {}", mode.id(), event.getName(),
                    e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConnectionClose(PlayerConnectionCloseEvent event) {
        cached.remove(event.getPlayerUniqueId());
    }

    // Redis thread: reload off it. The database is the truth, so message order doesn't matter.
    private void onRemoteChange(Channels.ModeBalanceChanged change) {
        if (!mode.id().equals(change.mode()) || serverId.equals(change.origin())
                || !cached.containsKey(change.player())) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                long fresh = ledger.balance(change.player());
                cached.computeIfPresent(change.player(), (k, old) -> fresh);
            } catch (SQLException e) {
                plugin.getSLF4JLogger().warn("Couldn't reload the {} balance of {}: {}", mode.id(), change.player(),
                        e.getMessage());
            }
        });
    }
}
