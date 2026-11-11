package us.rapidskies.common.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import us.rapidskies.common.db.Database;

/**
 * {@code store_transactions} (PLAN.md §6.3, §10): one row per Tebex transaction, written
 * when the proxy delivers it, and marked when it's charged back or refunded. The ledger and
 * {@code grants} rows hold what was given; this is the record staff look a payment up by.
 * Every method blocks.
 */
public final class StoreTransactions {

    public enum Status {
        DELIVERED, CHARGEBACK, REFUNDED
    }

    public record Transaction(String txnId, UUID player, String packageName, Status status, String note) {
    }

    private final Database db;

    public StoreTransactions(Database db) {
        this.db = db;
    }

    /**
     * The store's transaction id from a delivery's own id: {@code tbx-1:bolts} and
     * {@code tbx-1:rank} both belong to {@code tbx-1}.
     */
    public static String base(String txn) {
        int colon = txn.indexOf(':');
        return colon < 0 ? txn : txn.substring(0, colon);
    }

    /** Records a delivery; a transaction already recorded keeps its first package name. */
    public void delivered(String txn, UUID player, String packageName) throws SQLException {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO store_transactions (txn_id, uuid,"
                    + " package, status) VALUES (?, ?, ?, 'DELIVERED')")) {
                ps.setString(1, truncate(base(txn), 128));
                ps.setString(2, player.toString());
                ps.setString(3, truncate(packageName, 64));
                return ps.executeUpdate();
            }
        });
    }

    /** Marks a transaction charged back or refunded (creating the row if the delivery predates this table). */
    public void mark(String txn, UUID player, Status status, String note) throws SQLException {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO store_transactions (txn_id, uuid, package,"
                    + " status, note) VALUES (?, ?, 'unknown', ?, ?) ON DUPLICATE KEY UPDATE status = VALUES(status),"
                    + " note = VALUES(note)")) {
                ps.setString(1, truncate(base(txn), 128));
                ps.setString(2, player.toString());
                ps.setString(3, status.name());
                ps.setString(4, note == null ? null : truncate(note, 255));
                return ps.executeUpdate();
            }
        });
    }

    public Optional<Transaction> find(String txn) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT txn_id, uuid, package, status, note FROM store_transactions WHERE txn_id = ?")) {
                ps.setString(1, base(txn));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new Transaction(rs.getString(1), UUID.fromString(rs.getString(2)),
                            rs.getString(3), Status.valueOf(rs.getString(4)), rs.getString(5)));
                }
            }
        });
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
