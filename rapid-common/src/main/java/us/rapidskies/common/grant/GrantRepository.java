package us.rapidskies.common.grant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import us.rapidskies.common.db.Database;

/**
 * Reads and writes {@code grants} (core V4). Every method blocks: call from async contexts
 * only.
 *
 * <p>Writes take an {@link Effect}, the change to the permission system, and run it inside
 * the same transaction just before committing: if the effect throws, the row is rolled
 * back, so the record and the permission system can't disagree about a failed grant.
 */
public final class GrantRepository {

    private static final String COLUMNS = "id, uuid, kind, value, granted_by, granted_by_name, reason, txn_id,"
            + " created_at, starts_at, expires_at, revoked_at, revoked_by, revoked_by_name, revoke_reason";

    private final Database db;

    public GrantRepository(Database db) {
        this.db = db;
    }

    /** Work done before the transaction commits; throwing rolls it back. */
    @FunctionalInterface
    public interface Effect {
        void apply() throws SQLException;
    }

    /** A grant to record. {@code grantedBy}, {@code reason}, {@code txn} and {@code expiresAt} may be null. */
    public record NewGrant(UUID player, Grant.Kind kind, String value, UUID grantedBy, String grantedByName,
            String reason, String txn, Instant createdAt, Instant startsAt, Instant expiresAt) {
    }

    public sealed interface InsertResult permits Inserted, Duplicate {
    }

    public record Inserted(Grant grant) implements InsertResult {
    }

    /** The transaction id was used before; nothing was stored and the effect didn't run. */
    public record Duplicate(Grant existing) implements InsertResult {
    }

    public InsertResult insert(NewGrant g, Effect effect) throws SQLException {
        return db.transaction(c -> {
            if (g.txn() != null) {
                Optional<Grant> existing = findByTxn(c, g.txn());
                if (existing.isPresent()) {
                    return new Duplicate(existing.get());
                }
            }
            long id;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO grants (uuid, kind, value, granted_by, granted_by_name, reason, txn_id,"
                            + " created_at, starts_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, g.player().toString());
                ps.setString(2, g.kind().id());
                ps.setString(3, g.value());
                ps.setString(4, g.grantedBy() == null ? null : g.grantedBy().toString());
                ps.setString(5, truncate(g.grantedByName(), 16));
                ps.setString(6, g.reason() == null ? null : truncate(g.reason(), 255));
                ps.setString(7, g.txn());
                ps.setObject(8, utc(g.createdAt()));
                ps.setObject(9, utc(g.startsAt()));
                ps.setObject(10, utc(g.expiresAt()));
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("no id returned for the new grant");
                    }
                    id = keys.getLong(1);
                }
            }
            effect.apply();
            return new Inserted(find(c, id).orElseThrow(() -> new SQLException("grant #" + id + " vanished")));
        });
    }

    /**
     * Marks {@code grant} revoked and moves live timed grants of the same thing that start
     * at or after its end {@code shift} earlier (see {@link GrantTerms#revoke}). False, with
     * nothing changed and the effect not run, if it was already revoked.
     */
    public boolean revoke(Grant grant, UUID by, String byName, String reason, Instant at, Duration shift,
            Effect effect) throws SQLException {
        return db.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE grants SET revoked_at = ?, revoked_by = ?, revoked_by_name = ?, revoke_reason = ?"
                            + " WHERE id = ? AND revoked_at IS NULL")) {
                ps.setObject(1, utc(at));
                ps.setString(2, by == null ? null : by.toString());
                ps.setString(3, truncate(byName, 16));
                ps.setString(4, reason == null ? null : truncate(reason, 255));
                ps.setLong(5, grant.id());
                if (ps.executeUpdate() == 0) {
                    return false;
                }
            }
            if (!grant.permanent() && !shift.isZero()) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE grants SET starts_at = starts_at - INTERVAL ? MICROSECOND,"
                                + " expires_at = expires_at - INTERVAL ? MICROSECOND"
                                + " WHERE uuid = ? AND kind = ? AND value = ? AND id <> ? AND revoked_at IS NULL"
                                + " AND expires_at IS NOT NULL AND expires_at > ? AND starts_at >= ?")) {
                    long micros = shift.toNanos() / 1_000;
                    ps.setLong(1, micros);
                    ps.setLong(2, micros);
                    ps.setString(3, grant.player().toString());
                    ps.setString(4, grant.kind().id());
                    ps.setString(5, grant.value());
                    ps.setLong(6, grant.id());
                    ps.setObject(7, utc(at));
                    ps.setObject(8, utc(grant.expiresAt()));
                    ps.executeUpdate();
                }
            }
            effect.apply();
            return true;
        });
    }

    public Optional<Grant> find(long id) throws SQLException {
        return db.withConnection(c -> find(c, id));
    }

    public Optional<Grant> findByTxn(String txn) throws SQLException {
        return db.withConnection(c -> findByTxn(c, txn));
    }

    /**
     * Grants whose transaction id is {@code prefix} or starts with {@code prefix + ":"}
     * ({@code tbx-1:rank}), oldest first: what one store transaction granted, for chargebacks.
     */
    public List<Grant> byTxnPrefix(String prefix) throws SQLException {
        String like = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + ":%";
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + COLUMNS + " FROM grants WHERE txn_id = ? OR txn_id LIKE ? ORDER BY id")) {
                ps.setString(1, prefix);
                ps.setString(2, like);
                return all(ps);
            }
        });
    }

    /** A player's grants, newest first. */
    public List<Grant> history(UUID player, int limit, int offset) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + COLUMNS + " FROM grants WHERE uuid = ? ORDER BY id DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, player.toString());
                ps.setInt(2, limit);
                ps.setInt(3, offset);
                return all(ps);
            }
        });
    }

    public int count(UUID player) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM grants WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }

    /** Grants of one rank or permission that aren't revoked or expired at {@code now}, queued ones included. */
    public List<Grant> live(UUID player, Grant.Kind kind, String value, Instant now) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + COLUMNS + " FROM grants WHERE uuid = ? AND kind = ? AND value = ?"
                            + " AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > ?) ORDER BY id")) {
                ps.setString(1, player.toString());
                ps.setString(2, kind.id());
                ps.setString(3, value);
                ps.setObject(4, utc(now));
                return all(ps);
            }
        });
    }

    private static Optional<Grant> find(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM grants WHERE id = ?")) {
            ps.setLong(1, id);
            return all(ps).stream().findFirst();
        }
    }

    private static Optional<Grant> findByTxn(Connection c, String txn) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM grants WHERE txn_id = ?")) {
            ps.setString(1, txn);
            return all(ps).stream().findFirst();
        }
    }

    private static List<Grant> all(PreparedStatement ps) throws SQLException {
        List<Grant> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Grant(
                        rs.getLong("id"),
                        UUID.fromString(rs.getString("uuid")),
                        Grant.Kind.byId(rs.getString("kind")),
                        rs.getString("value"),
                        uuid(rs.getString("granted_by")),
                        rs.getString("granted_by_name"),
                        rs.getString("reason"),
                        rs.getString("txn_id"),
                        instant(rs, "created_at"),
                        instant(rs, "starts_at"),
                        instant(rs, "expires_at"),
                        instant(rs, "revoked_at"),
                        uuid(rs.getString("revoked_by")),
                        rs.getString("revoked_by_name"),
                        rs.getString("revoke_reason")));
            }
        }
        return out;
    }

    private static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime t = rs.getObject(column, LocalDateTime.class);
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }

    private static UUID uuid(String s) {
        return s == null ? null : UUID.fromString(s);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
