package us.rapidskies.common.economy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import us.rapidskies.common.db.Database;

/**
 * An append-only currency ledger with a running balance (PLAN.md §10).
 *
 * <p>Every change inserts a ledger row and updates the balance row in the same
 * transaction. Debits are a single guarded {@code UPDATE ... WHERE balance >= amount}, so
 * two servers spending the same player's money at once can never take it below zero.
 * A {@code txnId} makes a change idempotent: replaying it (a Tebex retry, a double-clicked
 * command) returns {@link Result.Duplicate} and changes nothing.
 *
 * <p>Methods here block; call them through {@link Database#async} or the {@code *Async}
 * variants, never on a server's main thread.
 */
public final class Ledger {

    private final Database db;
    private final String currency;
    private final String ledgerTable;
    private final String balanceTable;
    /** Extra key column for per-mode ledgers ("mode"), or null for Bolts. */
    private final String scopeColumn;
    private final String scope;

    private Ledger(Database db, String currency, String ledgerTable, String balanceTable,
                   String scopeColumn, String scope) {
        this.db = db;
        this.currency = currency;
        this.ledgerTable = ledgerTable;
        this.balanceTable = balanceTable;
        this.scopeColumn = scopeColumn;
        this.scope = scope;
    }

    /** The network-wide Bolts currency (PLAN.md §6.2). */
    public static Ledger bolts(Database db) {
        return new Ledger(db, "bolts", "bolts_ledger", "bolts_balance", null, null);
    }

    /** A gamemode's earn-only currency: Crowns on Horizons, Coins on SkyRealms. */
    public static Ledger mode(Database db, EconomyMode mode) {
        return new Ledger(db, mode.id(), "mode_economy_ledger", "mode_balance", "mode", mode.id());
    }

    public String currency() {
        return currency;
    }

    public sealed interface Result {
        /** The change was applied; {@code balance} is the new balance. */
        record Applied(long balance) implements Result {
        }

        /** {@code txnId} was already used; nothing changed. */
        record Duplicate(long balance) implements Result {
        }

        /** A debit larger than the balance; nothing changed. */
        record Insufficient(long balance) implements Result {
        }
    }

    public record Entry(long id, UUID player, long delta, String reason, String source, String txnId,
                        Instant createdAt) {
    }

    /** The outcome of {@link #transfer}. */
    public sealed interface Transfer {
        /** Both sides changed; the balances afterwards. */
        record Applied(long fromBalance, long toBalance) implements Transfer {
        }

        /** {@code txnId} was already used; nothing changed. */
        record Duplicate() implements Transfer {
        }

        /** The payer's balance doesn't cover it; nothing changed. */
        record Insufficient(long fromBalance) implements Transfer {
        }
    }

    public long balance(UUID player) throws SQLException {
        return db.withConnection(c -> balance(c, player));
    }

    public CompletableFuture<Long> balanceAsync(UUID player) {
        return db.async(() -> balance(player));
    }

    /**
     * Balances of several players in one query (sidebar caches). Players without a balance
     * row are left out; their balance is 0.
     */
    public Map<UUID, Long> balances(Collection<UUID> players) throws SQLException {
        Map<UUID, Long> out = new HashMap<>();
        if (players.isEmpty()) {
            return out;
        }
        List<UUID> ids = List.copyOf(players);
        String marks = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, balance FROM " + balanceTable
                    + " WHERE " + scopeWhere() + "uuid IN (" + marks + ")")) {
                int i = bindScope(ps, 1);
                for (UUID id : ids) {
                    ps.setString(i++, id.toString());
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(UUID.fromString(rs.getString(1)), rs.getLong(2));
                    }
                }
            }
            return out;
        });
    }

    /** Adds {@code amount} (must be positive). */
    public Result credit(UUID player, long amount, String reason, String source, String txnId) throws SQLException {
        requirePositive(amount);
        return change(player, amount, reason, source, txnId);
    }

    /** Removes {@code amount} (must be positive) if the balance covers it. */
    public Result debit(UUID player, long amount, String reason, String source, String txnId) throws SQLException {
        requirePositive(amount);
        return change(player, -amount, reason, source, txnId);
    }

    /**
     * Moves {@code amount} (must be positive) from one account to another in one transaction:
     * either both ledger rows and both balances change, or nothing does. Accounts are any
     * UUID, so a town bank (rapid-claims) is an account like a player's. The debit row
     * carries {@code txnId}; the credit row carries {@code txnId + "/in"}.
     */
    public Transfer transfer(UUID from, UUID to, long amount, String reason, String source, String txnId)
            throws SQLException {
        requirePositive(amount);
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(source, "source");
        if (from.equals(to)) {
            throw new IllegalArgumentException("can't transfer to the same account");
        }
        // Two transfers in opposite directions between the same accounts would each lock one
        // balance row and wait for the other: a deadlock. So both rows are made to exist first
        // (a locking read of a missing row takes a gap lock, which deadlocks the same way) and
        // then locked in uuid order, so every transfer queues on the same row first.
        db.withConnection(c -> {
            ensureBalanceRow(c, from);
            ensureBalanceRow(c, to);
            return null;
        });
        UUID first = from.toString().compareTo(to.toString()) < 0 ? from : to;
        UUID second = first.equals(from) ? to : from;
        for (int attempt = 1; ; attempt++) {
            try {
                return db.transaction(c -> {
                    lockBalance(c, first);
                    lockBalance(c, second);
                    if (txnId != null && txnExists(c, txnId)) {
                        return new Transfer.Duplicate();
                    }
                    if (!debitGuarded(c, from, amount)) {
                        return new Transfer.Insufficient(balance(c, from));
                    }
                    creditUpsert(c, to, amount);
                    insertEntry(c, from, -amount, reason, source, txnId);
                    insertEntry(c, to, amount, reason, source, txnId == null ? null : txnId + "/in");
                    return new Transfer.Applied(balance(c, from), balance(c, to));
                });
            } catch (SQLException e) {
                if (txnId != null && isUniqueViolation(e)) {
                    return new Transfer.Duplicate();
                }
                // Ordered locking rules out the opposite-transfer deadlock; anything else
                // InnoDB rolls back in full, so a retry is safe.
                if (attempt >= 3 || !"40001".equals(e.getSQLState())) {
                    throw e;
                }
            }
        }
    }

    private void ensureBalanceRow(Connection c, UUID account) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO " + balanceTable + " ("
                + scopeInsertColumns() + "uuid, balance) VALUES (" + scopeInsertValues() + "?, 0)")) {
            int i = bindScope(ps, 1);
            ps.setString(i, account.toString());
            ps.executeUpdate();
        }
    }

    private void lockBalance(Connection c, UUID account) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT balance FROM " + balanceTable + " WHERE " + scopeWhere() + "uuid = ? FOR UPDATE")) {
            int i = bindScope(ps, 1);
            ps.setString(i, account.toString());
            ps.executeQuery().close();
        }
    }

    public CompletableFuture<Transfer> transferAsync(UUID from, UUID to, long amount, String reason, String source,
                                                     String txnId) {
        return db.async(() -> transfer(from, to, amount, reason, source, txnId));
    }

    public CompletableFuture<Result> creditAsync(UUID player, long amount, String reason, String source, String txnId) {
        return db.async(() -> credit(player, amount, reason, source, txnId));
    }

    public CompletableFuture<Result> debitAsync(UUID player, long amount, String reason, String source, String txnId) {
        return db.async(() -> debit(player, amount, reason, source, txnId));
    }

    /**
     * The sum of credits for {@code reason} (e.g. {@code playtime}) since {@code since}, on
     * any server, for daily earning caps (PLAN.md §6.2, anti-farm).
     */
    public long earnedSince(UUID player, String reason, Instant since) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(SUM(delta), 0) FROM " + ledgerTable
                    + " WHERE " + scopeWhere() + "uuid = ? AND created_at >= ? AND reason = ? AND delta > 0")) {
                int i = bindScope(ps, 1);
                ps.setString(i++, player.toString());
                ps.setObject(i++, LocalDateTime.ofInstant(since, ZoneOffset.UTC));
                ps.setString(i, truncate(reason, 64));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    /** Most recent entries first. */
    public List<Entry> history(UUID player, int limit) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, delta, reason, source, txn_id, created_at FROM " + ledgerTable
                            + " WHERE " + scopeWhere() + "uuid = ? ORDER BY id DESC LIMIT ?")) {
                int i = bindScope(ps, 1);
                ps.setString(i++, player.toString());
                ps.setInt(i, limit);
                List<Entry> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Entry(rs.getLong(1), player, rs.getLong(2), rs.getString(3), rs.getString(4),
                                rs.getString(5), rs.getObject(6, LocalDateTime.class).toInstant(ZoneOffset.UTC)));
                    }
                }
                return out;
            }
        });
    }

    private Result change(UUID player, long delta, String reason, String source, String txnId) throws SQLException {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(source, "source");
        for (int attempt = 1; ; attempt++) {
            try {
                return changeOnce(player, delta, reason, source, txnId);
            } catch (SQLException e) {
                // InnoDB can deadlock two first-time upserts of the same balance row; the
                // loser was rolled back in full, so retrying is safe.
                if (attempt >= 3 || !"40001".equals(e.getSQLState())) {
                    throw e;
                }
            }
        }
    }

    private Result changeOnce(UUID player, long delta, String reason, String source, String txnId) throws SQLException {
        try {
            return db.transaction(c -> {
                if (txnId != null && txnExists(c, txnId)) {
                    return new Result.Duplicate(balance(c, player));
                }
                if (delta > 0) {
                    creditUpsert(c, player, delta);
                } else if (!debitGuarded(c, player, -delta)) {
                    return new Result.Insufficient(balance(c, player));
                }
                insertEntry(c, player, delta, reason, source, txnId);
                return new Result.Applied(balance(c, player));
            });
        } catch (SQLException e) {
            // Two servers raced on the same txnId: the unique key stopped the second one.
            if (txnId != null && isUniqueViolation(e)) {
                return new Result.Duplicate(balance(player));
            }
            throw e;
        }
    }

    private void creditUpsert(Connection c, UUID player, long amount) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + balanceTable + " ("
                + scopeInsertColumns() + "uuid, balance) VALUES (" + scopeInsertValues() + "?, ?)"
                + " ON DUPLICATE KEY UPDATE balance = balance + VALUES(balance)")) {
            int i = bindScope(ps, 1);
            ps.setString(i++, player.toString());
            ps.setLong(i, amount);
            ps.executeUpdate();
        }
    }

    /** The guarded debit: false (nothing changed) if the balance doesn't cover {@code amount}. */
    private boolean debitGuarded(Connection c, UUID player, long amount) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE " + balanceTable
                + " SET balance = balance - ? WHERE " + scopeWhere() + "uuid = ? AND balance >= ?")) {
            ps.setLong(1, amount);
            int i = bindScope(ps, 2);
            ps.setString(i++, player.toString());
            ps.setLong(i, amount);
            return ps.executeUpdate() == 1;
        }
    }

    private void insertEntry(Connection c, UUID player, long delta, String reason, String source, String txnId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + ledgerTable + " ("
                + scopeInsertColumns() + "uuid, delta, reason, source, txn_id) VALUES ("
                + scopeInsertValues() + "?, ?, ?, ?, ?)")) {
            int i = bindScope(ps, 1);
            ps.setString(i++, player.toString());
            ps.setLong(i++, delta);
            ps.setString(i++, truncate(reason, 64));
            ps.setString(i++, truncate(source, 64));
            ps.setString(i, txnId);
            ps.executeUpdate();
        }
    }

    private boolean txnExists(Connection c, String txnId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM " + ledgerTable + " WHERE " + scopeWhere() + "txn_id = ?")) {
            int i = bindScope(ps, 1);
            ps.setString(i, txnId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private long balance(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT balance FROM " + balanceTable + " WHERE " + scopeWhere() + "uuid = ?")) {
            int i = bindScope(ps, 1);
            ps.setString(i, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private String scopeWhere() {
        return scopeColumn == null ? "" : scopeColumn + " = ? AND ";
    }

    private String scopeInsertColumns() {
        return scopeColumn == null ? "" : scopeColumn + ", ";
    }

    private String scopeInsertValues() {
        return scopeColumn == null ? "" : "?, ";
    }

    /** Binds the scope value if there is one; returns the next parameter index. */
    private int bindScope(PreparedStatement ps, int index) throws SQLException {
        if (scopeColumn == null) {
            return index;
        }
        ps.setString(index, scope);
        return index + 1;
    }

    private static boolean isUniqueViolation(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException s && s.getSQLState() != null && s.getSQLState().startsWith("23")) {
                return true;
            }
        }
        return false;
    }

    private static void requirePositive(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive, got " + amount);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
